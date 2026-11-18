package com.agentos.workspace

import android.app.ActivityManager
import android.app.ActivityOptions
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.WindowManager
import com.agentos.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume

/**
 * Hidden workspaces: virtual screens the agent works on while the user keeps using the phone.
 * There are up to [MAX_DESKS] of them ("desks"), one per agent session running in the background,
 * so several tasks can run at once without seeing each other's apps.
 *
 * The displays are created by a [WorkspaceBackend]: Ubiq Core (com.agentos.core, the platform-signed
 * broker on the ROM) when it is installed, otherwise [WorkspaceShell] through Shizuku. They render into ImageReaders owned
 * by this process, so Ubiq can screenshot them for the model and show a live view to the user.
 * Once [WorkspaceHomeActivity] sits on a display, Ubiq counts as present there and may start any
 * other app on it with plain ActivityOptions.setLaunchDisplayId - no shell round-trip needed.
 */
object Workspace {
    private const val TAG = "AgentOS/Workspace"
    const val MAX_DESKS = 3

    /** Tried in order; the first one that connects runs the workspace. */
    private val backends: List<WorkspaceBackend> = listOf(com.agentos.core.CoreWorkspaceBackend, ShizukuBackend)

    sealed class Availability(val summary: String) {
        /** Usable through [via]: Ubiq Core (ROM) or Shizuku (stock Android). */
        class Ready(val via: WorkspaceBackend) : Availability("${via.name} connected") {
            val viaCore: Boolean get() = via.isCore
        }
        data object NotInstalled : Availability("Shizuku is not installed")
        data object NotRunning : Availability("Shizuku is installed but not running (start it from the Shizuku app)")
        data object NeedsPermission : Availability("Ubiq needs Shizuku permission")
    }

    internal lateinit var ctx: Context
    private val shellMutex = Mutex()
    @Volatile internal var shell: IWorkspaceShell? = null
    internal val thread = HandlerThread("agentos-frames").apply { start() }
    internal val handler = Handler(thread.looper)
    internal val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)

    private val _availability = MutableStateFlow<Availability>(Availability.NotInstalled)
    val availability: StateFlow<Availability> = _availability
    var lastError = ""
        internal set

    val desks: List<Desk> = List(MAX_DESKS) { Desk(it) }

    /** The screen stayed off for so long that the task should give up rather than wait on. */
    const val SCREEN_OFF_TIMEOUT_MESSAGE = "The phone's screen stayed off for too long, and on this phone the background workspace cannot run without it. " +
        "Stop here and tell the user where you got to."
    /** Set when a desk could not get its own power group, so tasks pause while the screen is off: why, for Settings. */
    @Volatile var sleepsWithPhone: String? = null
        internal set
    /** The workspace has its own power group: it just needs a moment to wake. */
    const val WAKING_MESSAGE = "The workspace display was dozing and is waking up; wait a second and try again."

    fun init(context: Context) {
        ctx = context.applicationContext
        val listener = object : WorkspaceBackend.Listener {
            override fun changed() = refreshAvailability()
            override fun lost(backend: WorkspaceBackend) {
                if (active !== backend) return
                shell = null; active = null; desks.forEach { it.lost() }
            }
        }
        backends.forEach { it.init(ctx, listener) }
        refreshAvailability()
    }

    /** The first ready backend, else why the last one (the stock-phone fallback) is not ready. */
    fun refreshAvailability() {
        var last: Availability = Availability.NotInstalled
        for (b in backends) {
            last = try { b.availability() } catch (t: Throwable) { Log.w(TAG, "availability ${b.name}", t); Availability.NotRunning }
            if (last is Availability.Ready) break
        }
        _availability.value = last
    }

    val isReady: Boolean get() = availability.value is Availability.Ready

    /** Asks for Shizuku permission (the stock-phone backend). */
    fun requestPermission() = ShizukuBackend.requestPermission()

    // ---------- desks ----------

    /**
     * A free desk for [owner] (a session id), preferring the one it used last so its apps are
     * still where it left them. Null when all desks are taken.
     */
    @Synchronized fun acquire(owner: String): Desk? {
        val free = desks.filter { it.owner == null }
        val pick = free.firstOrNull { it.lastOwner == owner } ?: free.firstOrNull { it.lastOwner == null } ?: free.firstOrNull() ?: return null
        pick.owner = owner; pick.lastOwner = owner
        return pick
    }

    @Synchronized fun free(desk: Desk) { desk.owner = null; desk.setActive(false) }

    fun deskFor(owner: String): Desk? = desks.firstOrNull { it.owner == owner } ?: desks.firstOrNull { it.lastOwner == owner && it.hasDisplay.value }

    /** Tears every display down (apps on them are destroyed). */
    fun releaseAll() { desks.forEach { it.release() }; try { shell?.releaseDisplay() } catch (_: Throwable) {} }

    // ---------- shell ----------

    /** The backend [shell] came from. */
    @Volatile private var active: WorkspaceBackend? = null

    internal suspend fun shell(): IWorkspaceShell? = shellMutex.withLock {
        shell?.takeIf { it.asBinder().isBinderAlive }?.let { return it }
        refreshAvailability()
        if (!isReady) { lastError = availability.value.summary; return null }
        for (b in backends) {
            if (b.availability() !is Availability.Ready) continue
            b.connect()?.let { active = b; shell = it; return it }
            Log.w(TAG, "${b.name} is ready but its workspace did not connect: $lastError")
        }
        active = null
        null
    }

    /** True if [pkg] has an app window on the real screen right now (the user is in it). */
    fun isOnScreen(svc: com.agentos.control.ControlService, pkg: String): Boolean = try {
        svc.windowsOn(android.view.Display.DEFAULT_DISPLAY).any { w ->
            w.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION && w.root?.packageName == pkg
        }
    } catch (_: Throwable) { false }

    /** Floor activity task per display, recorded by [WorkspaceHomeActivity] so "home" can bring the right one forward. */
    internal val floorTasks = java.util.concurrent.ConcurrentHashMap<Int, Int>()
}

/**
 * One hidden workspace display, used by one session at a time.
 */
class Desk internal constructor(val slot: Int) {
    private val tag = "AgentOS/Desk$slot"
    private val mutex = Mutex()
    private var reader: ImageReader? = null
    private var latest: Image? = null
    private val frameLock = Any()
    private val viewers = AtomicInteger(0)
    private var lastPush = 0L
    private val opened = HashSet<String>()
    /** Session currently using the desk, and the one that used it last. */
    @Volatile var owner: String? = null
        internal set
    @Volatile var lastOwner: String? = null
        internal set

    var displayId: Int = -1
        private set
    var width = 0; private set
    var height = 0; private set
    var densityDpi = 0; private set

    /** True while a task is being carried out on this display. */
    val active = MutableStateFlow(false)
    /** True while the display exists (whether or not a task is running). */
    val hasDisplay = MutableStateFlow(false)
    /** Latest rendered frame, pushed only while someone is watching (see [addViewer]). */
    val frame = MutableStateFlow<Bitmap?>(null)
    /** The user is driving the desk by hand from the viewer; frames are pushed faster. */
    @Volatile var liveTouch = false
    var ownPowerGroup = false
        private set
    var displayKind = ""
        private set
    private var wakeLock: PowerManager.WakeLock? = null
    private var wantAwake = false

    private val ctx get() = Workspace.ctx

    fun setActive(on: Boolean) {
        active.value = on
        scheduleIdleClear(on)
        keepAwake(on)
    }

    /**
     * Keeps the desk renderable. A virtual-device display has its own power group, kept awake by a
     * display-bound wake lock in the shell helper, so the phone may sleep; otherwise the display
     * lives in the phone's group and the phone has to stay on (an app dim wake lock).
     */
    @Suppress("DEPRECATION")
    fun keepAwake(on: Boolean) { wantAwake = on; updateAwake() }

    @Suppress("DEPRECATION")
    private fun updateAwake() {
        val watching = viewers.get() > 0
        try {
            if (ownPowerGroup) {
                if (wakeLock?.isHeld == true) wakeLock?.release()
                if (displayId >= 0) Workspace.shell?.setDisplayAwakeFor(displayId, wantAwake || watching)
            } else if (wantAwake) {
                if (wakeLock == null) wakeLock = ctx.getSystemService(PowerManager::class.java)
                    .newWakeLock(PowerManager.SCREEN_DIM_WAKE_LOCK or PowerManager.ON_AFTER_RELEASE, "agentos:workspace-$slot")
                if (wakeLock?.isHeld != true) wakeLock?.acquire(30 * 60 * 1000L)
            } else if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (t: Throwable) { Log.w(tag, "wake lock", t) }
    }

    fun isAwake(): Boolean = try {
        val id = if (displayId >= 0) displayId else android.view.Display.DEFAULT_DISPLAY
        ctx.getSystemService(DisplayManager::class.java).getDisplay(id)?.state == android.view.Display.STATE_ON
    } catch (_: Throwable) { true }

    suspend fun awaitAwake(timeoutMs: Long): Boolean {
        val start = SystemClock.uptimeMillis()
        while (!isAwake() && SystemClock.uptimeMillis() - start < timeoutMs) kotlinx.coroutines.delay(300)
        return isAwake()
    }

    /** The shell helper died: the display went with it. */
    internal fun lost() { displayId = -1; hasDisplay.value = false }

    /** Creates the display if needed. Returns its id or null (see [Workspace.lastError]). */
    suspend fun ensure(): Int? = mutex.withLock {
        val s = Workspace.shell() ?: return null
        if (displayId >= 0 && s.asBinder().isBinderAlive) return displayId
        val wm = ctx.getSystemService(WindowManager::class.java)
        val b = wm.maximumWindowMetrics.bounds
        width = b.width(); height = b.height(); densityDpi = ctx.resources.displayMetrics.densityDpi
        val r = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3)
        r.setOnImageAvailableListener({ onFrame(it) }, Workspace.handler)
        val id = try { s.createDisplay(r.surface, width, height, densityDpi) } catch (t: Throwable) { Workspace.lastError = "createDisplay: $t"; -1 }
        if (id < 0) {
            Workspace.lastError = try { s.lastError() } catch (_: Throwable) { "display creation failed" }
            Log.w(tag, "display creation failed: ${Workspace.lastError}"); r.close(); return null
        }
        reader?.close(); reader = r
        displayId = id
        opened.clear()
        ownPowerGroup = try { s.ownPowerGroup() } catch (_: Throwable) { false }
        displayKind = try { s.displayKind() } catch (_: Throwable) { "" }
        // A session about to use this desk: stay awake through setup rather than sleep until setActive.
        if (owner != null) wantAwake = true
        updateAwake()
        hasDisplay.value = true
        Workspace.sleepsWithPhone = if (ownPowerGroup) null else {
            val why = try { s.lastError() } catch (_: Throwable) { "" }
            Log.w(tag, "desk $id has no power group of its own, so tasks pause while the screen is off: $why")
            why.ifBlank { "unknown reason" }
        }
        // Put our floor on the display so later launches can come straight from this process.
        val floor = ComponentName(ctx, WorkspaceHomeActivity::class.java).flattenToShortString()
        val out = try { s.startActivity(floor, id, true) } catch (t: Throwable) { "$t" }
        if (out.contains("Error", true) || out.contains("Exception")) { Workspace.lastError = "could not start the workspace floor: $out"; Log.w(tag, Workspace.lastError) }
        waitForDisplay(id)
        Log.i(tag, "desk display $id ready (${width}x$height @ $densityDpi, $displayKind, own power group=$ownPowerGroup)")
        return id
    }

    private suspend fun waitForDisplay(id: Int) {
        val dm = ctx.getSystemService(DisplayManager::class.java)
        val start = SystemClock.uptimeMillis()
        while (SystemClock.uptimeMillis() - start < 3000) {
            if (dm.getDisplay(id) != null && latest != null) return
            kotlinx.coroutines.delay(100)
        }
    }

    private var idleJob: kotlinx.coroutines.Job? = null

    /**
     * Closes the apps the agent opened on this desk, keeping the floor. Android keeps one task per
     * singleTask app, so an app left on a hidden display would come to the front *there* when the
     * user taps it from a notification or the launcher. Clearing after a short idle period (and
     * when a session is deleted) means the user's own launches always get a visible task.
     */
    fun clear(): Int {
        opened.clear()
        if (displayId < 0) return 0
        val floor = "${ctx.packageName}/${WorkspaceHomeActivity::class.java.name}"
        return try { Workspace.shell?.clearDisplay(displayId, floor) ?: 0 } catch (t: Throwable) { Log.w(tag, "clear", t); 0 }
    }

    private fun scheduleIdleClear(on: Boolean) {
        idleJob?.cancel(); idleJob = null
        if (!on) idleJob = Workspace.scope.launch { kotlinx.coroutines.delay(IDLE_CLEAR_MS); if (!active.value && owner == null) clear() }
    }

    fun release() {
        val id = displayId
        if (id >= 0) try { Workspace.shell?.releaseDisplayId(id) } catch (_: Throwable) {}
        displayId = -1
        hasDisplay.value = false; ownPowerGroup = false; displayKind = ""
        synchronized(frameLock) { latest?.close(); latest = null }
        reader?.close(); reader = null
        frame.value = null
        opened.clear()
        setActive(false)
    }

    // ---------- frames ----------

    private fun onFrame(r: ImageReader) {
        val img = try { r.acquireLatestImage() } catch (_: Throwable) { null } ?: return
        synchronized(frameLock) { latest?.close(); latest = img }
        val now = SystemClock.uptimeMillis()
        if (viewers.get() > 0 && now - lastPush > (if (liveTouch) 40 else 120)) {
            lastPush = now
            toBitmap()?.let { frame.value = it }
        }
    }

    fun addViewer() { viewers.incrementAndGet(); updateAwake(); toBitmap()?.let { frame.value = it } }
    fun removeViewer() { viewers.decrementAndGet(); updateAwake() }

    private fun toBitmap(): Bitmap? = synchronized(frameLock) {
        val img = latest ?: return null
        try {
            val plane = img.planes[0]
            val rowStride = plane.rowStride
            val pixelStride = plane.pixelStride
            val w = img.width; val h = img.height
            val stridePixels = rowStride / pixelStride
            val buf: ByteBuffer = plane.buffer.also { it.rewind() }
            val bmp = Bitmap.createBitmap(stridePixels, h, Bitmap.Config.ARGB_8888)
            if (buf.remaining() >= rowStride * h) bmp.copyPixelsFromBuffer(buf)
            else { val padded = ByteBuffer.allocate(rowStride * h); padded.put(buf); padded.rewind(); bmp.copyPixelsFromBuffer(padded) }
            if (stridePixels != w) Bitmap.createBitmap(bmp, 0, 0, w, h) else bmp
        } catch (t: Throwable) { Log.w(tag, "frame convert", t); null }
    }

    fun screenshotPng(maxDim: Int = 1000): ByteArray? {
        val bmp = toBitmap() ?: return null
        val scale = maxDim.toFloat() / maxOf(bmp.width, bmp.height)
        val scaled = if (scale < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true) else bmp
        val bos = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.PNG, 90, bos)
        return bos.toByteArray()
    }

    // ---------- acting on the display ----------

    private fun options(): ActivityOptions = ActivityOptions.makeBasic().setLaunchDisplayId(displayId)

    /**
     * Starts a launcher intent for [pkg] on this desk. The first launch of an app asks for a fresh
     * task so the user's own task of that app is untouched; [pullBack] re-launches without that,
     * which brings the app's existing task back here after the user borrowed it.
     */
    fun launchPackage(pkg: String, pullBack: Boolean = false): Boolean {
        if (displayId < 0) return false
        val intent = ctx.packageManager.getLaunchIntentForPackage(pkg) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        if (!pullBack && (opened.add(pkg) || AppCaps.multiInstance(ctx, pkg))) intent.addFlags(Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
        return try { ctx.startActivity(intent, options().toBundle()); true } catch (t: Throwable) {
            Log.w(tag, "launch on desk failed, trying shell", t)
            val comp = intent.component?.flattenToShortString() ?: return false
            val out = try { Workspace.shell?.startActivity(comp, displayId, !pullBack) ?: "" } catch (e: Throwable) { "$e" }
            !out.contains("Error", true) && !out.contains("Exception")
        }
    }

    fun launch(intent: Intent): Boolean {
        if (displayId < 0) return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try { ctx.startActivity(intent, options().toBundle()); true } catch (t: Throwable) { Log.w(tag, "launch failed", t); false }
    }

    /** Brings this desk's floor to the front (the floor is one task per display). */
    fun home(): Boolean {
        val task = Workspace.floorTasks[displayId]
        if (task != null) try {
            ctx.getSystemService(ActivityManager::class.java).moveTaskToFront(task, 0, options().toBundle()); return true
        } catch (t: Throwable) { Log.w(tag, "floor to front", t) }
        return key(KeyEvent.KEYCODE_HOME)
    }

    /**
     * Moves the app on top of this desk to the phone's real screen, on top, as it is (same page, scroll
     * position, form contents): "open X for me" is prepared here and then handed over. [fallbackPkg] is the
     * app the agent saw on the desk, used when the workspace is too old to move tasks: its launcher intent
     * on the main display brings its task forward from wherever it is. Returns the moved package, or throws
     * with a reason the model can read.
     */
    fun handOver(fallbackPkg: String?): String {
        if (displayId < 0) throw IllegalStateException("the workspace has no display")
        val floor = "${ctx.packageName}/${WorkspaceHomeActivity::class.java.name}"
        val out = try { Workspace.shell?.moveTopTask(displayId, android.view.Display.DEFAULT_DISPLAY, floor) } catch (t: Throwable) { Log.w(tag, "moveTopTask", t); null }
        val pkg = when {
            out != null && out.startsWith("Error") -> throw IllegalStateException(out.removePrefix("Error: "))
            !out.isNullOrBlank() -> out.substringBefore('/')
            else -> {
                val p = fallbackPkg ?: throw IllegalStateException("no app is open on the workspace")
                val intent = ctx.packageManager.getLaunchIntentForPackage(p) ?: throw IllegalStateException("$p has no launcher screen")
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                ctx.startActivity(intent, ActivityOptions.makeBasic().setLaunchDisplayId(android.view.Display.DEFAULT_DISPLAY).toBundle())
                p
            }
        }
        // The task is the user's now: a later launch of this app on the desk must make a fresh one, not pull it back.
        opened.remove(pkg)
        Log.i(tag, "handed $pkg to the main display")
        return pkg
    }

    fun key(keyCode: Int): Boolean = try { Workspace.shell?.injectKey(displayId, keyCode) ?: false } catch (t: Throwable) { Log.w(tag, "key", t); false }

    fun touch(action: Int, x: Float, y: Float, downTime: Long, eventTime: Long) {
        if (displayId < 0) return
        try { Workspace.shell?.injectTouch(displayId, action, x, y, downTime, eventTime) } catch (t: Throwable) { Log.w(tag, "touch", t) }
    }

    fun typeText(text: String): Boolean {
        if (displayId < 0 || text.isEmpty()) return false
        com.agentos.control.ControlService.instance?.let { if (it.setTextOnFocused(text, displayId)) return true }
        val quoted = "'" + text.replace("'", "'\\''").replace(" ", "%s") + "'"
        val out = try { Workspace.shell?.exec("input -d $displayId text $quoted") ?: "" } catch (t: Throwable) { "$t" }
        return !out.contains("Error", true) && !out.contains("Exception")
    }
    fun back(): Boolean = key(KeyEvent.KEYCODE_BACK)
    fun enter(): Boolean = key(KeyEvent.KEYCODE_ENTER)

    companion object { private const val IDLE_CLEAR_MS = 3 * 60 * 1000L }
}

/** What Android lets an app do with several windows at once. */
object AppCaps {
    private val cache = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    /**
     * True if [pkg]'s launcher activity can run as several independent tasks: two sessions can then
     * use it side by side, each in its own window. Apps whose main activity is singleTask or
     * singleInstance have one task, which only one session can hold (see AppLeases).
     */
    fun multiInstance(ctx: Context, pkg: String): Boolean = cache.getOrPut(pkg) {
        try {
            val comp = ctx.packageManager.getLaunchIntentForPackage(pkg)?.component ?: return@getOrPut false
            val info = ctx.packageManager.getActivityInfo(comp, PackageManager.GET_META_DATA)
            val declared = try {
                ctx.packageManager.getProperty("android.window.PROPERTY_SUPPORTS_MULTI_INSTANCE_SYSTEM_UI", comp).boolean
            } catch (_: Throwable) { false }
            // A standard / singleTop launcher activity can be started as an extra task (MULTIPLE_TASK);
            // singleTask / singleInstance ones always reuse their one task.
            val stacking = info.launchMode == android.content.pm.ActivityInfo.LAUNCH_MULTIPLE || info.launchMode == android.content.pm.ActivityInfo.LAUNCH_SINGLE_TOP
            declared || stacking || info.documentLaunchMode == android.content.pm.ActivityInfo.DOCUMENT_LAUNCH_ALWAYS
        } catch (_: Throwable) { false }
    }
}
