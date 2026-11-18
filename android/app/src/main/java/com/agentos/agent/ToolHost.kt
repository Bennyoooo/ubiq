package com.agentos.agent

import android.content.Context
import android.util.Base64
import android.util.Log
import com.agentos.data.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap

/**
 * The phone's tools for an outside harness (Pi, see docs/AGENT_DESIGN.md), as an MCP server over streamable HTTP on
 * 127.0.0.1:[PORT]. The harness is only the brain: every call goes through [AgentTools.execute], so the effect
 * ledger, receipts, payment and job guards apply exactly as in the built-in loop, and tool text is redacted
 * ([Secrets]) before it leaves the phone.
 *
 * - Auth: `Authorization: Bearer <Prefs.harnessToken>`. Other apps can reach loopback too.
 * - Which conversation: `X-AgentOS-Session: <session id>`. A call is accepted only while that session runs a harness
 *   turn ([bind]); its desk, leases, ledger and secrets are that turn's [TaskTarget].
 * - Plain JSON responses (no SSE stream), one request per connection.
 *
 * On the Mac, `adb forward tcp:8787 tcp:8787` makes the same URL work for a harness running there (scripts/pi-dev.sh).
 */
object ToolHost {
    private const val TAG = "AgentOS/ToolHost"
    const val PORT = 8787
    private const val MAX_BODY = 4 * 1024 * 1024

    /** Read-only tools, marked for the harness (MCP readOnlyHint); everything else may change something. */
    private val READ_ONLY = setOf("get_screen", "screenshot", "read_list", "wait", "read_notifications", "summarize_notifications", "unreplied_messages",
        "calendar_events", "contacts_search", "space_search", "list_jobs", "job_result", "task_status", "recall")
    /** Ending a turn is the harness's own business (its final reply), checked by [CompletionCheck] in [Session]. */
    private val HIDDEN = setOf("done")

    /** A running harness turn. Its tool calls run in [job], so Stop (or the turn ending) also stops a navigate in flight. */
    private class Binding(val session: Session, val target: TaskTarget) { val job = SupervisorJob(); val calls = java.util.concurrent.atomic.AtomicInteger() }
    private val bindings = ConcurrentHashMap<String, Binding>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var server: ServerSocket? = null
    private lateinit var app: Context

    val url: String get() = "http://127.0.0.1:$PORT/mcp"

    /** Starts listening (once). Called when a harness turn starts, so nothing listens while the built-in loop is used. */
    @Synchronized fun ensureStarted(ctx: Context) {
        app = ctx.applicationContext
        if (server?.isClosed == false) return
        val ss = ServerSocket(PORT, 16, InetAddress.getByName("127.0.0.1"))
        server = ss
        Thread({
            while (!ss.isClosed) {
                val s = try { ss.accept() } catch (_: Throwable) { break }
                scope.launch { try { serve(s) } catch (t: Throwable) { Log.w(TAG, "connection", t) } finally { try { s.close() } catch (_: Throwable) {} } }
            }
        }, "agentos-toolhost").apply { isDaemon = true }.start()
        Log.i(TAG, "listening on $url")
    }

    fun bind(session: Session, target: TaskTarget) { bindings[session.id] = Binding(session, target) }
    fun unbind(sessionId: String) { bindings.remove(sessionId)?.job?.cancel() }

    /** The tool list in MCP form: our OpenAI function definitions, minus hosted MCP entries and [HIDDEN]. */
    fun tools(ctx: Context): JSONArray = JSONArray().apply {
        for (d in AgentTools.definitions(ctx)) {
            if (d.optString("type") != "function") continue
            val name = d.optString("name")
            if (name in HIDDEN) continue
            put(JSONObject().put("name", name).put("description", d.optString("description"))
                .put("inputSchema", d.optJSONObject("parameters") ?: JSONObject().put("type", "object"))
                .put("annotations", JSONObject().put("readOnlyHint", name in READ_ONLY).put("destructiveHint", name !in READ_ONLY)))
        }
    }

    private suspend fun serve(sock: Socket) {
        sock.soTimeout = 30_000
        val input = BufferedInputStream(sock.getInputStream())
        val requestLine = readLine(input) ?: return
        val headers = HashMap<String, String>()
        while (true) {
            val l = readLine(input) ?: return
            if (l.isEmpty()) break
            val i = l.indexOf(':'); if (i > 0) headers[l.substring(0, i).trim().lowercase()] = l.substring(i + 1).trim()
        }
        val (method, path) = requestLine.split(' ').let { (it.getOrNull(0) ?: "") to (it.getOrNull(1) ?: "") }
        if (headers["authorization"] != "Bearer ${Prefs.harnessToken(app)}") return respond(sock, 401, null)
        if (path.substringBefore('?') != "/mcp") return respond(sock, 404, null)
        when (method) {
            "GET" -> return respond(sock, 405, null)        // no server-initiated stream
            "DELETE" -> return respond(sock, 200, null)
            "POST" -> {}
            else -> return respond(sock, 405, null)
        }
        val len = headers["content-length"]?.toIntOrNull() ?: return respond(sock, 411, null)
        if (len > MAX_BODY) return respond(sock, 413, null)
        val body = ByteArray(len).also { var o = 0; while (o < len) { val n = input.read(it, o, len - o); if (n < 0) return; o += n } }
        val msg = try { JSONObject(String(body)) } catch (_: Throwable) { return respond(sock, 400, rpcError(null, -32700, "parse error")) }
        val id = msg.opt("id")
        if (id == null) return respond(sock, 202, null)    // a notification (notifications/initialized and the like)
        sock.soTimeout = 0                                  // tool calls can wait on the user (ask_user) or the navigator
        val reply = try { handle(msg, headers["x-agentos-session"]) } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            Log.w(TAG, "handle", t); rpcError(id, -32603, t.message ?: t.toString())
        }
        respond(sock, 200, reply, sessionHeader = if (msg.optString("method") == "initialize") "agentos-${System.nanoTime()}" else null)
    }

    private suspend fun handle(msg: JSONObject, sessionId: String?): JSONObject {
        val id = msg.opt("id")
        val params = msg.optJSONObject("params") ?: JSONObject()
        return when (msg.optString("method")) {
            "initialize" -> rpcResult(id, JSONObject()
                .put("protocolVersion", params.optString("protocolVersion", "2025-06-18"))
                .put("capabilities", JSONObject().put("tools", JSONObject()))
                .put("serverInfo", JSONObject().put("name", "agentos-phone").put("version", com.agentos.BuildConfig.VERSION_NAME))
                .put("instructions", "The phone's own tools: its screen and apps, notifications, connectors, jobs. Every effect is receipted on the phone."))
            "ping" -> rpcResult(id, JSONObject())
            "tools/list" -> rpcResult(id, JSONObject().put("tools", tools(app)))
            "tools/call" -> rpcResult(id, call(sessionId, params.optString("name"), params.optJSONObject("arguments") ?: JSONObject()))
            else -> rpcError(id, -32601, "method not found")
        }
    }

    private suspend fun call(sessionId: String?, name: String, args: JSONObject): JSONObject {
        val b = sessionId?.let { bindings[it] } ?: return toolResult("No task is running for this conversation on the phone.", isError = true)
        return try { kotlinx.coroutines.withContext(b.job) { callBound(b, name, args) } }
        catch (c: kotlinx.coroutines.CancellationException) { toolResult("Stopped: the task was cancelled on the phone.", isError = true) }
    }

    private suspend fun callBound(b: Binding, name: String, args: JSONObject): JSONObject {
        if (name in HIDDEN) return toolResult("Unknown tool $name.", isError = true)
        val session = b.session
        // Keep the chat in order: the step's own words ("I'll check your flight…") come over the turn's stream, this
        // call over its own connection, so it can arrive first and its card would sit above the words. The harness
        // streams the words, then a "tool" event, before calling here; wait (briefly) until the stream has caught up.
        val n = b.calls.incrementAndGet()
        kotlinx.coroutines.withTimeoutOrNull(1500) { while (session.harnessToolEvents.get() < n) kotlinx.coroutines.delay(15) }
        session.beforeHarnessTool(name, args)?.let { return toolResult(it, isError = true) }
        if (name == "ask_user") {
            val answer = session.askFromHarness(args.optString("question", "Could you clarify?"), askOptions(args))
            return toolResult("The user answered: $answer")
        }
        val outcome = try {
            AgentTools.execute(app, name, args, AgentRuntime.apps, b.target)
        } catch (d: TaskDone) {
            ToolOutcome(d.summary)
        } catch (q: NeedUser) {
            ToolOutcome("The user answered: " + session.askFromHarness(q.question, q.options))
        } catch (c: kotlinx.coroutines.CancellationException) {
            throw c
        } catch (t: Throwable) {
            Log.w(TAG, "tool $name failed", t)
            ToolOutcome.error("Tool error: ${t.message}")
        }
        // One-time codes and passwords never leave the phone: they become tokens type_text can resolve.
        val text = b.target.secrets.redact(outcome.text)
        val hasSecret = text != outcome.text
        val content = JSONArray().put(JSONObject().put("type", "text").put("text",
            session.afterHarnessTool(text) + if (hasSecret && outcome.imagePng != null) "\n(Screenshot withheld: the screen shows a code or password.)" else ""))
        if (outcome.imagePng != null && !hasSecret && AgentTools.screenshotsEnabled(app))
            content.put(JSONObject().put("type", "image").put("mimeType", "image/png").put("data", Base64.encodeToString(outcome.imagePng, Base64.NO_WRAP)))
        return JSONObject().put("content", content).put("isError", outcome.isError)
    }

    private fun toolResult(text: String, isError: Boolean = false) = JSONObject()
        .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", text))).put("isError", isError)
    private fun rpcResult(id: Any?, result: JSONObject) = JSONObject().put("jsonrpc", "2.0").put("id", id ?: JSONObject.NULL).put("result", result)
    private fun rpcError(id: Any?, code: Int, message: String) = JSONObject().put("jsonrpc", "2.0").put("id", id ?: JSONObject.NULL)
        .put("error", JSONObject().put("code", code).put("message", message))

    private fun respond(sock: Socket, status: Int, body: JSONObject?, sessionHeader: String? = null) {
        val bytes = body?.toString()?.toByteArray() ?: ByteArray(0)
        val reason = when (status) { 200 -> "OK"; 202 -> "Accepted"; 400 -> "Bad Request"; 401 -> "Unauthorized"; 404 -> "Not Found"; 405 -> "Method Not Allowed"; 411 -> "Length Required"; 413 -> "Payload Too Large"; else -> "Error" }
        val head = StringBuilder("HTTP/1.1 $status $reason\r\nConnection: close\r\nContent-Length: ${bytes.size}\r\n")
        if (body != null) head.append("Content-Type: application/json\r\n")
        if (sessionHeader != null) head.append("Mcp-Session-Id: $sessionHeader\r\n")
        head.append("\r\n")
        val out = sock.getOutputStream()
        out.write(head.toString().toByteArray()); out.write(bytes); out.flush()
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(c.toChar())
            if (sb.length > 16_384) return null
        }
    }
}
