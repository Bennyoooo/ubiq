package com.agentos.workspace

import android.companion.AssociationRequest
import android.companion.CompanionDeviceManager
import android.companion.virtual.VirtualDeviceManager
import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.hardware.display.VirtualDisplayConfig
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface

/**
 * Runs inside the Shizuku helper process (uid 2000, the ADB shell user), which may create trusted
 * virtual displays and start activities on them - things a normal app is not allowed to do.
 * Everything here is reachable from the app through [IWorkspaceShell].
 *
 * Two ways to make the workspace display:
 *
 * 1. **Virtual device** (preferred). The shell user may create a `VirtualDevice` (the API behind
 *    app streaming to Chromebooks and cars) once it has a companion-device association, which the
 *    `cmd companiondevice` shell command grants without any UI. A display owned by a virtual
 *    device gets its own *display group* and *power group*, the system itself holds a wake lock
 *    for it, and the lock screen never covers it. So it keeps rendering, and apps on it keep
 *    running, while the phone's own screen is off and locked. The power button only sleeps the
 *    phone's group.
 * 2. **DisplayManager** fallback. A plain trusted virtual display. We ask for its own display
 *    group so the keyguard stays off it, but Android (17 on the Pixel 7 Pro at least) still puts
 *    it in the phone's group: it sleeps when the phone sleeps, so the app has to hold a wake lock
 *    while the agent works.
 */
class WorkspaceShell : IWorkspaceShell.Stub {
    private var appCtx: Context? = null
    /** One per workspace display (one per running agent session). */
    private class Entry(val vd: VirtualDisplay, val kind: String, val ownGroup: Boolean, val flags: Int) { var wakeLock: PowerManager.WakeLock? = null }
    private val displays = java.util.LinkedHashMap<Int, Entry>()
    private var virtualDevice: Any? = null // android.companion.virtual.VirtualDeviceManager.VirtualDevice (system API), shared by all displays
    private var shellCtx: Context? = null
    private var usedFlags = 0
    private var ownGroup = false
    private var kind = ""
    @Volatile private var error = ""

    constructor() : super()
    /** Shizuku >= 13 passes a Context for our package; the shell package context is derived from it. */
    constructor(context: Context) : super() { appCtx = context }

    private fun shellContext(): Context {
        val base = appCtx ?: run {
            // Older Shizuku: borrow the system context like scrcpy does.
            val at = Class.forName("android.app.ActivityThread")
            val thread = at.getMethod("systemMain").invoke(null)
            at.getMethod("getSystemContext").invoke(thread) as Context
        }
        // The display service checks that the package name belongs to the calling uid (shell).
        return base.createPackageContext("com.android.shell", 0)
    }

    @Synchronized
    override fun createDisplay(surface: Surface, width: Int, height: Int, dpi: Int): Int {
        val ctx = try { shellContext() } catch (t: Throwable) { error = "no shell context: $t"; return -1 }
        shellCtx = ctx
        val errors = StringBuilder()

        createViaVirtualDevice(ctx, surface, width, height, dpi)?.let { vd ->
            val id = vd.display.displayId
            val group = displayGroupId(vd.display)
            if (group > 0 && waitForState(ctx, id, Display.STATE_ON)) {
                kind = "virtual-device"; ownGroup = true
                usedFlags = VDM_FLAGS or FLAG_TRUSTED or FLAG_ALWAYS_UNLOCKED
                displays[id] = Entry(vd, kind, true, usedFlags)
                setDisplayAwakeFor(id, true)
                Log.i(TAG, "virtual device display $id created (display group $group)")
                return id
            }
            errors.append("virtual device display landed in group $group, state ${vd.display.state}; ")
            vd.release(); if (displays.isEmpty()) closeVirtualDevice()
        } ?: run { Log.w(TAG, "virtual device path unavailable: $error"); errors.append("virtual device: $error; ") }

        val dm = ctx.getSystemService(DisplayManager::class.java)
        // Own focus keeps the agent's taps from stealing focus on the real screen. ALWAYS_UNLOCKED
        // keeps the keyguard off the display (needs OWN_DISPLAY_GROUP to be accepted at all).
        val full = FLAGS_BASE or FLAG_TRUSTED or FLAG_TOUCH_FEEDBACK_DISABLED or FLAG_OWN_FOCUS
        val attempts = listOf(
            full or FLAG_OWN_DISPLAY_GROUP or FLAG_ALWAYS_UNLOCKED,
            full,
            FLAGS_BASE or FLAG_TRUSTED,
            FLAGS_BASE,
        )
        for (flags in attempts) {
            try {
                val vd = dm.createVirtualDisplay("Ubiq workspace", width, height, dpi, surface, flags)
                val id = vd.display.displayId
                kind = "display-manager"
                ownGroup = displayGroupId(vd.display) > 0
                usedFlags = if (ownGroup) flags else flags and FLAG_OWN_DISPLAY_GROUP.inv()
                displays[id] = Entry(vd, kind, ownGroup, usedFlags)
                Log.i(TAG, "virtual display $id created with flags=0x${flags.toString(16)} (own group=$ownGroup)")
                return id
            } catch (t: Throwable) {
                errors.append("flags=0x").append(flags.toString(16)).append(": ").append(t.toString().take(160)).append("; ")
            }
        }
        error = errors.toString()
        return -1
    }

    /**
     * A display owned by a virtual device the shell user creates. Needs a companion association for
     * the shell package (made once with the companiondevice shell command, persisted by the system).
     */
    private fun createViaVirtualDevice(ctx: Context, surface: Surface, width: Int, height: Int, dpi: Int): VirtualDisplay? {
        try {
            val associationId = ensureAssociation(ctx) ?: return null
            // The manager sends its Context's AttributionSource to the system, which must name the
            // shell package for the calling uid. The helper's contexts carry the app's attribution,
            // so build the manager by hand around a context that attributes to the shell.
            val binder = Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java).invoke(null, Context.VIRTUAL_DEVICE_SERVICE) as? android.os.IBinder
                ?: run { error = "no virtualdevice service"; return null }
            val stub = Class.forName("android.companion.virtual.IVirtualDeviceManager\$Stub")
            val service = stub.getMethod("asInterface", android.os.IBinder::class.java).invoke(null, binder)
            val ctor = VirtualDeviceManager::class.java.declaredConstructors.first { it.parameterCount == 2 }.apply { isAccessible = true }
            val vdm = ctor.newInstance(service, ShellAttributedContext(ctx)) as VirtualDeviceManager
            val paramsCls = Class.forName("android.companion.virtual.VirtualDeviceParams")
            val builderCls = Class.forName("android.companion.virtual.VirtualDeviceParams\$Builder")
            val b = builderCls.getConstructor().newInstance()
            builderCls.getMethod("setName", String::class.java).invoke(b, "Ubiq workspace")
            builderCls.getMethod("setLockState", Int::class.javaPrimitiveType).invoke(b, LOCK_STATE_ALWAYS_UNLOCKED)
            // Tasks on the workspace stay out of the phone's Recents.
            builderCls.getMethod("setDevicePolicy", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType).invoke(b, POLICY_TYPE_RECENTS, DEVICE_POLICY_CUSTOM)
            val params = builderCls.getMethod("build").invoke(b)
            val device = virtualDevice ?: (VirtualDeviceManager::class.java.getMethod("createVirtualDevice", Int::class.javaPrimitiveType, paramsCls).invoke(vdm, associationId, params)
                ?: run { error = "createVirtualDevice returned null"; return null })
            virtualDevice = device
            val config = VirtualDisplayConfig.Builder("Ubiq workspace", width, height, dpi)
                .setSurface(surface).setFlags(VDM_FLAGS or FLAG_TRUSTED).build()
            val create = device.javaClass.getMethod("createVirtualDisplay", VirtualDisplayConfig::class.java, java.util.concurrent.Executor::class.java, VirtualDisplay.Callback::class.java)
            val vd = create.invoke(device, config, null, null) as VirtualDisplay?
            if (vd == null) { error = "virtual device gave no display"; if (displays.isEmpty()) closeVirtualDevice(); return null }
            return vd
        } catch (t: Throwable) {
            error = "$t".take(200)
            Log.w(TAG, "virtual device path failed", t)
            if (displays.isEmpty()) closeVirtualDevice()
            return null
        }
    }

    private fun ensureAssociation(ctx: Context): Int? {
        val user = Process.myUserHandle().hashCode()
        // Parse the shell command's listing rather than CompanionDeviceManager.getMyAssociations():
        // the helper's Context carries the app's attribution, not the shell's, so the API sees nothing.
        fun find(): Int? = Regex("""mId=(\d+), mUserId=\d+, mPackageName='com\.android\.shell'[^}]*mDeviceProfile='${Regex.escape(AssociationRequest.DEVICE_PROFILE_APP_STREAMING)}'""")
            .find(exec("cmd companiondevice list $user"))?.groupValues?.get(1)?.toIntOrNull()
        find()?.let { return it }
        // Positional form: USER PACKAGE MAC [PROFILE] [SELF_MANAGED]. Self-managed: no real device, no UI.
        val out = exec("cmd companiondevice associate $user com.android.shell 02:00:00:a6:e0:05 ${AssociationRequest.DEVICE_PROFILE_APP_STREAMING} true")
        Thread.sleep(300)
        return find() ?: run { error = "no companion association for the shell (${out.take(120)})"; null }
    }

    private fun closeVirtualDevice() {
        try { virtualDevice?.let { it.javaClass.getMethod("close").invoke(it) } } catch (_: Throwable) {}
        virtualDevice = null
    }

    /** The display's power/display group (0 = the phone's own), read through the hidden DisplayInfo. -1 if unknown. */
    private fun displayGroupId(d: Display): Int = try {
        val infoCls = Class.forName("android.view.DisplayInfo")
        val info = infoCls.getConstructor().newInstance()
        Display::class.java.getMethod("getDisplayInfo", infoCls).invoke(d, info)
        infoCls.getField("displayGroupId").getInt(info)
    } catch (t: Throwable) { Log.w(TAG, "displayGroupId", t); -1 }

    private fun waitForState(ctx: Context, displayId: Int, state: Int): Boolean {
        val dm = ctx.getSystemService(DisplayManager::class.java)
        repeat(30) {
            if (dm.getDisplay(displayId)?.state == state) return true
            Thread.sleep(100)
        }
        return false
    }

    override fun lastError(): String = error
    override fun displayId(): Int = synchronized(this) { displays.keys.firstOrNull() ?: -1 }

    @Synchronized
    override fun setDisplayAwake(on: Boolean) { displays.keys.toList().forEach { setDisplayAwakeFor(it, on) } }

    @Synchronized
    override fun setDisplayAwakeFor(displayId: Int, on: Boolean) {
        val e = displays[displayId] ?: return
        if (!e.ownGroup) return
        try {
            if (on) {
                if (e.wakeLock?.isHeld == true) return
                val ctx = shellCtx ?: shellContext()
                val pm = ctx.getSystemService(PowerManager::class.java)
                val m = PowerManager::class.java.getMethod("newWakeLock", Int::class.javaPrimitiveType, String::class.java, Int::class.javaPrimitiveType)
                val wl = m.invoke(pm, PowerManager.SCREEN_DIM_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP, "agentos:workspace-display-$displayId", displayId) as PowerManager.WakeLock
                wl.acquire()
                e.wakeLock = wl
            } else {
                e.wakeLock?.let { if (it.isHeld) it.release() }
                e.wakeLock = null
            }
        } catch (t: Throwable) { Log.w(TAG, "display wake lock", t) }
    }

    @Synchronized
    override fun releaseDisplayId(displayId: Int) {
        val e = displays.remove(displayId) ?: return
        e.wakeLock?.let { if (it.isHeld) it.release() }
        try { e.vd.release() } catch (_: Throwable) {}
        if (displays.isEmpty()) { closeVirtualDevice(); ownGroup = false; kind = "" }
    }

    @Synchronized
    override fun releaseDisplay() { displays.keys.toList().forEach { releaseDisplayId(it) } }
    override fun displayFlags(): Int = usedFlags
    override fun ownPowerGroup(): Boolean = ownGroup
    override fun displayKind(): String = kind

    override fun startActivity(component: String, displayId: Int, newTask: Boolean): String {
        val flags = if (newTask) "--activity-multiple-task " else ""
        return exec("am start --user 0 --display $displayId $flags-n $component")
    }

    private val inputManager: Any by lazy {
        try { Class.forName("android.hardware.input.InputManagerGlobal").getMethod("getInstance").invoke(null) }
        catch (_: Throwable) { Class.forName("android.hardware.input.InputManager").getMethod("getInstance").invoke(null) }
    }
    private val injectMethod by lazy { inputManager.javaClass.getMethod("injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType) }
    private val setDisplayIdMethod by lazy { InputEvent::class.java.getMethod("setDisplayId", Int::class.javaPrimitiveType) }

    override fun injectKey(displayId: Int, keyCode: Int): Boolean {
        try {
            val now = SystemClock.uptimeMillis()
            for (action in intArrayOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
                val ev = KeyEvent(now, now, action, keyCode, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD)
                setDisplayIdMethod.invoke(ev, displayId)
                injectMethod.invoke(inputManager, ev, 0 /* INJECT_INPUT_EVENT_MODE_ASYNC */)
            }
            return true
        } catch (t: Throwable) {
            Log.w(TAG, "injectInputEvent failed, using input shell command", t)
        }
        val out = exec("input -d $displayId keyevent $keyCode")
        return !out.contains("Error", true) && !out.contains("Exception")
    }

    /** Live touch from the user's finger (the "take over" view), one pointer. */
    override fun injectTouch(displayId: Int, action: Int, x: Float, y: Float, downTime: Long, eventTime: Long) {
        try {
            val ev = MotionEvent.obtain(downTime, eventTime, action, x, y, 0)
            ev.source = InputDevice.SOURCE_TOUCHSCREEN
            setDisplayIdMethod.invoke(ev, displayId)
            injectMethod.invoke(inputManager, ev, 0 /* ASYNC */)
            ev.recycle()
        } catch (t: Throwable) { Log.w(TAG, "injectTouch", t) }
    }

    override fun exec(command: String): String = try {
        val p = ProcessBuilder("/system/bin/sh", "-c", command).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor()
        out.trim()
    } catch (t: Throwable) { "exec failed: $t" }

    override fun clearDisplay(displayId: Int, keepPrefix: String): Int {
        // `am stack list` prints "RootTask id=N ... displayId=D" headers followed by their "taskId=..: component" lines.
        val out = exec("am stack list")
        var removed = 0
        var curId = -1; var curDisplay = -1; var keep = false
        fun flush() {
            if (curId >= 0 && curDisplay == displayId && !keep) {
                val r = exec("am stack remove $curId"); removed++
                Log.i(TAG, "removed root task $curId on display $displayId: ${r.take(80)}")
            }
        }
        for (line in out.lines()) {
            val t = line.trim()
            if (t.startsWith("RootTask id=")) {
                flush()
                curId = t.substringAfter("id=").substringBefore(' ').toIntOrNull() ?: -1
                curDisplay = t.substringAfter("displayId=", "-1").substringBefore(' ').toIntOrNull() ?: -1
                keep = false
            } else if (t.startsWith("taskId=") && t.contains(keepPrefix)) keep = true
        }
        flush()
        return removed
    }

    override fun moveTopTask(fromDisplay: Int, toDisplay: Int, skipPrefix: String): String {
        // `am stack list` lists root tasks top to bottom: "RootTask id=N ... displayId=D", then "taskId=..: pkg/cls ..." lines.
        var curId = -1; var curDisplay = -1; var curTop = ""; var skip = false
        fun candidate(): Boolean = curId >= 0 && curDisplay == fromDisplay && !skip
        var pick = -1; var pickTop = ""
        for (line in exec("am stack list").lines()) {
            val t = line.trim()
            if (t.startsWith("RootTask id=")) {
                if (candidate()) { pick = curId; pickTop = curTop; break }
                curId = t.substringAfter("id=").substringBefore(' ').toIntOrNull() ?: -1
                curDisplay = t.substringAfter("displayId=", "-1").substringBefore(' ').toIntOrNull() ?: -1
                curTop = ""; skip = false
            } else if (t.startsWith("taskId=")) {
                if (t.contains(skipPrefix)) skip = true
                if (curTop.isEmpty()) curTop = t.substringAfter(": ", "").substringBefore(' ')
            }
        }
        if (pick < 0 && candidate()) { pick = curId; pickTop = curTop }
        if (pick < 0) return "Error: no app is open on the workspace"
        val out = exec("am display move-stack $pick $toDisplay")
        Log.i(TAG, "moved root task $pick ($pickTop) from display $fromDisplay to $toDisplay: ${out.take(120)}")
        return if (out.contains("Exception") || out.contains("Error", true)) "Error: $out" else pickTop
    }

    override fun destroy() {
        releaseDisplay()
        System.exit(0)
    }

    /** A context whose attribution (uid 2000, com.android.shell) is what the system services expect from the shell user. */
    private class ShellAttributedContext(base: Context) : android.content.ContextWrapper(base) {
        private val source = android.content.AttributionSource.Builder(Process.myUid()).setPackageName("com.android.shell").build()
        override fun getAttributionSource(): android.content.AttributionSource = source
        override fun getApplicationContext(): Context = this
        override fun getPackageName(): String = "com.android.shell"
        override fun getOpPackageName(): String = "com.android.shell"
    }

    companion object {
        private const val TAG = "AgentOS/Shell"
        // android.hardware.display.DisplayManager hidden flags (same values scrcpy uses).
        private const val FLAG_SUPPORTS_TOUCH = 1 shl 6
        private const val FLAG_ROTATES_WITH_CONTENT = 1 shl 7
        private const val FLAG_DESTROY_CONTENT_ON_REMOVAL = 1 shl 8
        private const val FLAG_TRUSTED = 1 shl 10
        const val FLAG_OWN_DISPLAY_GROUP = 1 shl 11
        private const val FLAG_ALWAYS_UNLOCKED = 1 shl 12
        private const val FLAG_TOUCH_FEEDBACK_DISABLED = 1 shl 13
        private const val FLAG_OWN_FOCUS = 1 shl 14
        private const val FLAGS_BASE = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or
            DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or
            FLAG_SUPPORTS_TOUCH or FLAG_ROTATES_WITH_CONTENT or FLAG_DESTROY_CONTENT_ON_REMOVAL
        // For a virtual-device display: the device adds touch/focus/always-unlocked itself and must
        // not be asked for OWN_DISPLAY_GROUP (that would opt out of the device's own group).
        private const val VDM_FLAGS = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or
            DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or FLAG_ROTATES_WITH_CONTENT or
            FLAG_DESTROY_CONTENT_ON_REMOVAL or FLAG_TOUCH_FEEDBACK_DISABLED or FLAG_OWN_FOCUS
        // android.companion.virtual.VirtualDeviceParams (system API)
        private const val LOCK_STATE_ALWAYS_UNLOCKED = 1
        private const val POLICY_TYPE_RECENTS = 2
        private const val DEVICE_POLICY_CUSTOM = 1
    }
}
