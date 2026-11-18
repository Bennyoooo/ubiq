package com.agentos.agent

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.util.concurrent.Executors

/**
 * One JSONL file per conversation: what the user said, each tool call and its result, pauses, and how
 * the run ended. Logcat lasts minutes and app-private files need root, so this lives in the app's
 * external files directory, where `adb pull /sdcard/Android/data/ai.ubiq.app/files/traces` reaches it
 * on any build and other apps cannot. It holds only text the model saw, after secrets became tokens.
 */
object Trace {
    private const val TAG = "AgentOS/Trace"
    private const val MAX_ENTRY = 8_000
    private const val MAX_FILE = 512_000L
    private val writer = Executors.newSingleThreadExecutor { r -> Thread(r, "agentos-trace").apply { isDaemon = true } }
    @Volatile private var dir: File? = null

    fun init(ctx: Context) { dir = try { ctx.getExternalFilesDir("traces") } catch (t: Throwable) { Log.w(TAG, "no trace directory", t); null } }

    fun log(session: String, kind: String, text: String) {
        val d = dir ?: return
        val at = Instant.now().toString()
        writer.execute {
            try {
                val f = File(d, "$session.jsonl")
                if (f.length() > MAX_FILE) f.renameTo(File(d, "$session.old.jsonl"))
                f.appendText(JSONObject().put("at", at).put("kind", kind).put("text", text.take(MAX_ENTRY)).toString() + "\n")
            } catch (t: Throwable) { Log.w(TAG, "write", t) }
        }
    }

    fun delete(session: String) {
        val d = dir ?: return
        writer.execute { File(d, "$session.jsonl").delete(); File(d, "$session.old.jsonl").delete() }
    }
}
