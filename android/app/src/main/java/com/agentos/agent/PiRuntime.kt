package com.agentos.agent

import android.content.Context
import android.util.Log
import com.agentos.data.Prefs
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipInputStream

/**
 * Pi on this phone (docs/AGENT_DESIGN.md, step 4). Termux's Node 24 ships in the APK as libnode_runtime.so with its
 * libraries beside it (scripts/build-node-runtime.sh), so the installer extracts it into nativeLibraryDir, the one app
 * directory Android lets a process exec from. It runs harness/server.mjs (assets/node/harness.zip, unpacked once per
 * build) as a child process on 127.0.0.1:[PORT], with the same token as [ToolHost].
 *
 * Node starts with the first Pi turn and stops after [IDLE_MS] without one, or when memory runs low, so an idle phone
 * carries no Node. A blank [Prefs.harnessUrl] means this runtime; a set one means a harness elsewhere (scripts/pi-dev.sh).
 */
object PiRuntime {
    private const val TAG = "AgentOS.PiRuntime"
    const val PORT = 8789
    private const val IDLE_MS = 10 * 60_000L
    private const val START_MS = 30_000L
    private const val MAX_LOG = 1_000_000L

    private val lock = Mutex()
    private val active = AtomicInteger()
    @Volatile private var proc: Process? = null
    private var idle: Job? = null

    fun local(ctx: Context) = Prefs.harnessUrl(ctx).isBlank()
    fun url(ctx: Context) = if (local(ctx)) "http://127.0.0.1:$PORT" else Prefs.harnessUrl(ctx)
    fun bundled(ctx: Context) = node(ctx).exists()
    /** Pi runs the turns: chosen in Settings, and either in this build or reachable elsewhere. */
    fun enabled(ctx: Context) = Prefs.engine(ctx) == "pi" && (bundled(ctx) || !local(ctx))
    val running get() = proc?.isAlive == true

    private fun node(ctx: Context) = File(ctx.applicationInfo.nativeLibraryDir, "libnode_runtime.so")
    private fun home(ctx: Context) = File(ctx.filesDir, "pi")

    /** Runs [block] with the harness up; local Node is kept alive while any turn is inside, and idles out afterwards. */
    suspend fun <T> use(ctx: Context, block: suspend () -> T): T {
        if (!local(ctx)) return block()
        active.incrementAndGet()
        try {
            ensure(ctx)
            return block()
        } finally {
            if (active.decrementAndGet() == 0) scheduleStop()
        }
    }

    private suspend fun ensure(ctx: Context) = lock.withLock {
        idle?.cancel()
        if (running && Harness.healthy(ctx)) return@withLock
        if (!bundled(ctx)) throw Harness.Unreachable("This build has no Pi runtime (build it with scripts/build-node-runtime.sh).")
        proc?.destroy()
        val app = unpack(ctx)
        // Appended, not replaced: a restart must not erase what happened before it.
        val log = File(home(ctx), "harness.log").apply { if (length() > MAX_LOG) renameTo(File(home(ctx), "harness.old.log")) }
        val tmp = File(ctx.cacheDir, "pi").apply { mkdirs() }
        val p = ProcessBuilder(node(ctx).path, "--max-old-space-size=384", "server.mjs")
            .directory(app)
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.appendTo(log))
            .apply {
                environment().apply {
                    put("AGENTOS_HARNESS_TOKEN", Prefs.harnessToken(ctx))
                    put("AGENTOS_HARNESS_PORT", PORT.toString())
                    put("AGENTOS_HARNESS_STATE", File(home(ctx), "state").path)
                    put("HOME", File(home(ctx), "home").apply { mkdirs() }.path)
                    put("TMPDIR", tmp.path)
                    put("SSL_CERT_FILE", File(app, "cert.pem").path)
                    put("LD_LIBRARY_PATH", ctx.applicationInfo.nativeLibraryDir)
                }
            }.start()
        proc = p
        val t0 = System.currentTimeMillis()
        while (System.currentTimeMillis() - t0 < START_MS) {
            if (!p.isAlive) throw Harness.Unreachable("Pi stopped while starting: ${tail(log)}")
            if (Harness.healthy(ctx)) { Log.i(TAG, "ready in ${System.currentTimeMillis() - t0} ms"); return@withLock }
            delay(150)
        }
        p.destroy(); proc = null
        throw Harness.Unreachable("Pi didn't start within ${START_MS / 1000} s: ${tail(log)}")
    }

    private fun scheduleStop() {
        idle?.cancel()
        idle = AgentRuntime.scope.launch { delay(IDLE_MS); if (active.get() == 0) stop("idle") }
    }

    /** Stops local Node unless a turn is using it (low memory, idle, or the engine switched away). */
    fun stop(why: String) {
        if (active.get() > 0) return
        proc?.let { if (it.isAlive) { Log.i(TAG, "stopping ($why)"); it.destroy() } }
        proc = null
    }

    /** Unpacks harness.zip and the CA bundle into files/pi/app-<version>, once per build; older versions are removed. */
    private fun unpack(ctx: Context): File {
        val version = ctx.assets.open("node/version").bufferedReader().use { it.readText().trim() }
        val dir = File(home(ctx), "app-$version")
        if (File(dir, ".ok").exists()) return dir
        home(ctx).listFiles()?.filter { it.name.startsWith("app-") }?.forEach { it.deleteRecursively() }
        val t0 = System.currentTimeMillis()
        dir.mkdirs()
        val root = dir.canonicalPath + File.separator
        ZipInputStream(ctx.assets.open("node/harness.zip").buffered()).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                val out = File(dir, e.name)
                if (!out.canonicalPath.startsWith(root)) continue
                if (e.isDirectory) { out.mkdirs(); continue }
                out.parentFile?.mkdirs()
                out.outputStream().use { zip.copyTo(it) }
            }
        }
        ctx.assets.open("node/cert.pem").use { i -> File(dir, "cert.pem").outputStream().use { i.copyTo(it) } }
        File(dir, ".ok").writeText(version)
        Log.i(TAG, "unpacked harness $version in ${System.currentTimeMillis() - t0} ms")
        return dir
    }

    private fun tail(f: File) = try { f.readLines().takeLast(4).joinToString(" | ").take(400) } catch (_: Throwable) { "no log" }
}
