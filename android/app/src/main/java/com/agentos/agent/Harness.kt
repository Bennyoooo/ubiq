package com.agentos.agent

import android.content.Context
import android.util.Base64
import com.agentos.data.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/**
 * Client for the Pi harness (harness/server.mjs). A turn is one POST /turn whose response streams one JSON event per
 * line (`tool`, `text`, `end`); the harness calls the phone's tools back through [ToolHost] meanwhile. The phone stays
 * the source of truth for settings: model, effort, tier, key and system prompt travel with every turn, so the harness
 * keeps only the conversation.
 */
object Harness {
    class Result(val text: String, val error: String?)
    class Unreachable(msg: String) : IOException(msg)

    private val open = ConcurrentHashMap<String, HttpURLConnection>()

    private fun conn(ctx: Context, path: String, readTimeoutMs: Int): HttpURLConnection =
        (URL(PiRuntime.url(ctx).trimEnd('/') + path).openConnection() as HttpURLConnection).apply {
            connectTimeout = 4000; readTimeout = readTimeoutMs
            setRequestProperty("Authorization", "Bearer ${Prefs.harnessToken(ctx)}")
            setRequestProperty("Content-Type", "application/json")
        }

    /** True when the harness answers /health with our token. */
    suspend fun healthy(ctx: Context): Boolean = withContext(Dispatchers.IO) {
        try { conn(ctx, "/health", 3000).run { val ok = responseCode == 200; disconnect(); ok } } catch (_: Throwable) { false }
    }

    /** [fresh]: drop the harness's saved copy of this conversation first (the user deleted a message). */
    suspend fun turn(ctx: Context, session: String, text: String, attachments: List<Attachment>, instructions: String,
                     fresh: Boolean = false, onEvent: (JSONObject) -> Unit): Result = withContext(Dispatchers.IO) {
        val images = JSONArray()
        for (a in attachments) if (a.isImage) images.put(JSONObject().put("mimeType", a.mime).put("data", Base64.encodeToString(a.bytes, Base64.NO_WRAP)))
        val body = JSONObject()
            .put("session", session).put("text", text).put("images", images).put("instructions", instructions).put("fresh", fresh)
            .put("model", Prefs.model(ctx)).put("effort", Prefs.effort(ctx)).put("serviceTier", Prefs.serviceTier(ctx))
            .put("apiKey", Prefs.apiKey(ctx))
            .put("toolHost", JSONObject().put("url", ToolHost.url).put("token", Prefs.harnessToken(ctx)))
        val c = conn(ctx, "/turn", 0)
        open[session] = c
        try {
            c.requestMethod = "POST"; c.doOutput = true; c.setChunkedStreamingMode(0)
            try { c.outputStream.use { it.write(body.toString().toByteArray()) } }
            catch (e: IOException) { throw Unreachable("The Pi harness isn't reachable at ${PiRuntime.url(ctx)} (${e.message}).") }
            // Behind `adb reverse` with nothing listening, the connection opens and then closes without an answer.
            val code = try { c.responseCode } catch (e: IOException) { throw Unreachable("The Pi harness isn't answering at ${PiRuntime.url(ctx)} (${e.message}).") }
            if (code != 200) throw IOException("Pi harness: HTTP $code ${c.errorStream?.bufferedReader()?.readText()?.take(200) ?: ""}")
            var end: Result? = null
            c.inputStream.bufferedReader().useLines { lines ->
                for (line in lines) {
                    if (line.isBlank()) continue
                    val ev = try { JSONObject(line) } catch (_: Throwable) { continue }
                    if (ev.optString("type") == "end") { end = Result(ev.optString("text"), ev.optString("error").ifBlank { null }); break }
                    onEvent(ev)
                }
            }
            end ?: Result("", "the harness closed the turn without an answer")
        } finally { open.remove(session); c.disconnect() }
    }

    /** A message the user sent while the turn runs: the harness reads it after the current step. */
    suspend fun steer(ctx: Context, session: String, text: String) = post(ctx, "/steer", JSONObject().put("session", session).put("text", text))

    /** Stops the session's running turn (and drops the streaming connection). */
    suspend fun cancel(ctx: Context, session: String) {
        post(ctx, "/cancel", JSONObject().put("session", session))
        open.remove(session)?.disconnect()
    }

    private suspend fun post(ctx: Context, path: String, body: JSONObject) = withContext(Dispatchers.IO) {
        try {
            val c = conn(ctx, path, 5000)
            c.requestMethod = "POST"; c.doOutput = true
            c.outputStream.use { it.write(body.toString().toByteArray()) }
            c.responseCode; c.disconnect()
        } catch (_: Throwable) {}
    }
}
