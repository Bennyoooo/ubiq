package com.agentos.core

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Binder
import android.os.IBinder
import android.util.Log
import com.agentos.core.api.CoreContract
import com.agentos.workspace.IWorkspaceShell
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.util.UUID

/**
 * The app's side of Ubiq Core (com.agentos.core, the platform-signed broker on the ROM). Core
 * holds the privileges (workspace displays, input, settings, roles) and the owner's policy; this
 * app only asks. When Core is not installed (stock Pixel), every entry point here is a no-op and
 * the app falls back to Shizuku and its own accessibility service.
 */
object CoreClient {
    private const val TAG = "AgentOS/Core"
    private lateinit var app: Context
    @Volatile private var core: ICore? = null
    @Volatile private var connecting: CompletableDeferred<ICore?>? = null
    /** Called when Core's process goes away (its workspace displays went with it). */
    @Volatile var onLost: (() -> Unit)? = null
    private val clientToken = Binder()

    fun init(ctx: Context) { app = ctx.applicationContext }

    /** Core is installed and this app holds CONTROL. */
    val available: Boolean get() = try {
        app.packageManager.getPackageInfo(CoreContract.PACKAGE, 0)
        app.checkSelfPermission(CoreContract.PERMISSION_CONTROL) == PackageManager.PERMISSION_GRANTED
    } catch (_: Throwable) { false }

    suspend fun core(): ICore? {
        core?.takeIf { it.asBinder().isBinderAlive }?.let { return it }
        if (!available) return null
        val wait = synchronized(this) {
            connecting ?: CompletableDeferred<ICore?>().also { d ->
                connecting = d
                val conn = object : ServiceConnection {
                    override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                        val c = binder?.let { ICore.Stub.asInterface(it) }
                        try { c?.linkClient(clientToken) } catch (t: Throwable) { Log.w(TAG, "linkClient", t) }
                        core = c; d.complete(c); synchronized(this@CoreClient) { connecting = null }
                        Log.i(TAG, "connected to Ubiq Core v${try { c?.version() } catch (_: Throwable) { -1 }}")
                    }
                    override fun onServiceDisconnected(name: ComponentName?) { core = null; onLost?.invoke() }
                    override fun onBindingDied(name: ComponentName?) { core = null; onLost?.invoke(); try { app.unbindService(this) } catch (_: Throwable) {} }
                }
                val ok = try { app.bindService(Intent(CoreContract.ACTION_BIND).setPackage(CoreContract.PACKAGE), conn, Context.BIND_AUTO_CREATE) }
                    catch (t: Throwable) { Log.w(TAG, "bind", t); false }
                if (!ok) { d.complete(null); connecting = null }
            }
        }
        return withTimeoutOrNull(5000) { wait.await() }
    }

    /** One Core tool call; null when Core is absent or unreachable. */
    suspend fun call(tool: String, args: JSONObject = JSONObject(), origin: String = "agent", task: String = "", opId: String? = null): JSONObject? {
        val c = core() ?: return null
        val req = JSONObject().put("opId", opId ?: UUID.randomUUID().toString()).put("tool", tool).put("args", args).put("origin", origin).put("task", task)
        return try { JSONObject(c.call(req.toString())) } catch (t: Throwable) { Log.w(TAG, "call $tool", t); null }
    }

    /** The active call's audio pipes ([0] the caller to read, [1] Ubiq's voice to write), or null with the reason logged. */
    suspend fun openCallAudio(rate: Int): Array<android.os.ParcelFileDescriptor>? = try {
        core()?.takeIf { it.version() >= 2 }?.openCallAudio(rate)
    } catch (t: Throwable) { Log.w(TAG, "openCallAudio: $t"); null }

    suspend fun workspace(): IWorkspaceShell? = try { core()?.workspace } catch (t: Throwable) { Log.w(TAG, "getWorkspace", t); null }
}
