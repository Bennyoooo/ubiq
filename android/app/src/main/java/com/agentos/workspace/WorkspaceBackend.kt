package com.agentos.workspace

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.util.Log
import com.agentos.BuildConfig
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku
import kotlin.coroutines.resume

/**
 * Something that can create the hidden workspace displays and drive them: an [IWorkspaceShell]
 * running with more rights than this app. [Workspace] tries its backends in order and uses the
 * first that connects. Today: Ubiq Core on the ROM ([com.agentos.core.CoreWorkspaceBackend]),
 * then Shizuku on a stock phone ([ShizukuBackend]). A built-in wireless-debugging connection
 * would be another one.
 */
interface WorkspaceBackend {
    /** Shown in Settings, e.g. "Shizuku". */
    val name: String
    /** True for Ubiq Core (the ROM). */
    val isCore: Boolean get() = false

    /** Registers for changes; call [Listener.changed] when availability may have changed and [Listener.lost] when a connected shell died. */
    fun init(ctx: Context, listener: Listener)

    /** [Workspace.Availability.Ready] when usable now, else why not. */
    fun availability(): Workspace.Availability

    /** A live shell, or null (with [Workspace.lastError] set) when it could not connect. */
    suspend fun connect(): IWorkspaceShell?

    interface Listener {
        fun changed()
        fun lost(backend: WorkspaceBackend)
    }
}

/** Stock Android: a small helper ([WorkspaceShell]) run as the ADB shell user through Shizuku. */
object ShizukuBackend : WorkspaceBackend {
    private const val TAG = "AgentOS/Shizuku"
    private const val PACKAGE = "moe.shizuku.privileged.api"
    private const val REQ = 7001
    private lateinit var ctx: Context
    private lateinit var listener: WorkspaceBackend.Listener

    override val name = "Shizuku"

    override fun init(ctx: Context, listener: WorkspaceBackend.Listener) {
        this.ctx = ctx.applicationContext
        this.listener = listener
        try {
            Shizuku.addBinderReceivedListenerSticky { listener.changed() }
            Shizuku.addBinderDeadListener { listener.lost(this); listener.changed() }
            Shizuku.addRequestPermissionResultListener { _, _ -> listener.changed() }
        } catch (t: Throwable) { Log.w(TAG, "shizuku listeners", t) }
    }

    override fun availability(): Workspace.Availability = try {
        val installed = try { ctx.packageManager.getPackageInfo(PACKAGE, 0); true } catch (_: PackageManager.NameNotFoundException) { false }
        when {
            !installed -> Workspace.Availability.NotInstalled
            !Shizuku.pingBinder() -> Workspace.Availability.NotRunning
            Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED -> Workspace.Availability.NeedsPermission
            else -> Workspace.Availability.Ready(this)
        }
    } catch (t: Throwable) { Log.w(TAG, "availability", t); Workspace.Availability.NotRunning }

    fun requestPermission() {
        try { if (Shizuku.pingBinder()) Shizuku.requestPermission(REQ) } catch (t: Throwable) { Log.w(TAG, "requestPermission", t) }
    }

    override suspend fun connect(): IWorkspaceShell? {
        val args = Shizuku.UserServiceArgs(ComponentName(ctx.packageName, WorkspaceShell::class.java.name))
            .daemon(false).processNameSuffix("workspace").debuggable(BuildConfig.DEBUG).version(BuildConfig.VERSION_CODE)
        return withTimeoutOrNull(15_000) {
            suspendCancellableCoroutine { cont ->
                val conn = object : ServiceConnection {
                    override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                        if (binder == null || !binder.pingBinder()) { if (cont.isActive) cont.resume(null); return }
                        if (cont.isActive) cont.resume(IWorkspaceShell.Stub.asInterface(binder))
                    }
                    override fun onServiceDisconnected(name: ComponentName?) { listener.lost(this@ShizukuBackend) }
                }
                try { Shizuku.bindUserService(args, conn) } catch (t: Throwable) {
                    Workspace.lastError = "Shizuku bind failed: $t"; Log.w(TAG, Workspace.lastError); if (cont.isActive) cont.resume(null)
                }
            }
        } ?: run { Workspace.lastError = "Shizuku helper did not start in time"; null }
    }
}
