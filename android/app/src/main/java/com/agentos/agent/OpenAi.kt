package com.agentos.agent

import android.content.Context
import android.util.Base64
import com.agentos.data.Prefs
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Thin client for the OpenAI Responses API (POST /v1/responses).
 * Conversation state is kept server-side via previous_response_id, so each call only sends new input items.
 */
object OpenAi {
    private const val ENDPOINT = "https://api.openai.com/v1/responses"
    private val JSON = "application/json; charset=utf-8".toMediaType()

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.MINUTES)
            .retryOnConnectionFailure(true)
            .build()
    }

    class ApiException(val status: Int, val code: String?, message: String) : IOException(message)

    data class FnCall(val callId: String, val name: String, val arguments: JSONObject)

    class Result(
        val id: String,
        val text: String,
        val functionCalls: List<FnCall>,
        val status: String,
        val incompleteReason: String?,
    )

    // ----- input item helpers -----

    fun userText(text: String): JSONObject = JSONObject()
        .put("type", "message").put("role", "user")
        .put("content", JSONArray().put(JSONObject().put("type", "input_text").put("text", text)))

    fun userImage(png: ByteArray, caption: String? = null, mime: String = "image/png"): JSONObject {
        val content = JSONArray()
        if (caption != null) content.put(JSONObject().put("type", "input_text").put("text", caption))
        content.put(
            JSONObject().put("type", "input_image").put("detail", "auto")
                .put("image_url", "data:$mime;base64," + Base64.encodeToString(png, Base64.NO_WRAP))
        )
        return JSONObject().put("type", "message").put("role", "user").put("content", content)
    }

    /** A user message with text plus attached images/files, in the shapes the Responses API accepts. */
    fun userMessage(text: String, attachments: List<Attachment>): JSONObject {
        if (attachments.isEmpty()) return userText(text)
        val content = JSONArray()
        val intro = StringBuilder(text.ifBlank { "(see attachment)" })
        intro.append("\n\nAttached by the user (each is also saved on the phone, path given):")
        for (a in attachments) intro.append("\n- ").append(a.name).append(" (").append(a.mime).append(", ").append(a.bytes.size / 1024).append(" KB)")
            .append(a.savedAs?.let { " saved at $it" } ?: "")
        content.put(JSONObject().put("type", "input_text").put("text", intro.toString()))
        for (a in attachments) {
            when {
                a.isImage -> content.put(JSONObject().put("type", "input_image").put("detail", "auto")
                    .put("image_url", "data:${a.mime};base64," + Base64.encodeToString(a.bytes, Base64.NO_WRAP)))
                a.isPdf -> content.put(JSONObject().put("type", "input_file").put("filename", a.name)
                    .put("file_data", "data:application/pdf;base64," + Base64.encodeToString(a.bytes, Base64.NO_WRAP)))
                a.isText -> {
                    val body = String(a.bytes, Charsets.UTF_8)
                    val shown = if (body.length > 120_000) body.take(120_000) + "\n…(truncated)" else body
                    content.put(JSONObject().put("type", "input_text").put("text", "Contents of ${a.name}:\n$shown"))
                }
                else -> content.put(JSONObject().put("type", "input_text").put("text", "(${a.name} is a ${a.mime} file; its contents cannot be shown inline, but it is saved on the phone at ${a.savedAs ?: "an unknown location"}.)"))
            }
        }
        return JSONObject().put("type", "message").put("role", "user").put("content", content)
    }

    fun functionOutput(callId: String, output: String): JSONObject = JSONObject()
        .put("type", "function_call_output").put("call_id", callId).put("output", output)

    fun functionTool(name: String, description: String, parameters: JSONObject): JSONObject = JSONObject()
        .put("type", "function").put("name", name).put("description", description)
        .put("parameters", parameters).put("strict", false)

    /** A strict structured-output format: the reply is one JSON object matching [schema]. */
    fun jsonSchema(name: String, schema: JSONObject): JSONObject = JSONObject()
        .put("type", "json_schema").put("name", name).put("strict", true).put("schema", schema)

    /**
     * One small decision from a cheap model: [schema]-shaped JSON, nothing stored server-side.
     * Used by the navigator, memory selection and consults; never for text the user sees.
     */
    fun decide(ctx: Context, model: String, instructions: String, prompt: String, schema: JSONObject,
               effort: String = "low", tier: String? = "fast", maxOutputTokens: Int = 1500): JSONObject {
        val r = respond(ctx, model, instructions, JSONArray().put(userText(prompt)), effort = effort, maxOutputTokens = maxOutputTokens,
            store = false, serviceTier = tier, format = jsonSchema("decision", schema))
        return runCatching { JSONObject(r.text) }.getOrElse { throw IOException("unparseable decision: ${r.text.take(120)}") }
    }

    // ----- the call -----

    fun respond(
        ctx: Context,
        model: String,
        instructions: String,
        input: JSONArray,
        tools: JSONArray? = null,
        previousResponseId: String? = null,
        effort: String? = Prefs.effort(ctx),
        maxOutputTokens: Int = 6000,
        store: Boolean = true,
        serviceTier: String? = Prefs.serviceTier(ctx),
        /** A `text.format` object (structured outputs), e.g. from [jsonSchema]. */
        format: JSONObject? = null,
    ): Result {
        val key = Prefs.apiKey(ctx)
        if (key.isBlank()) throw ApiException(0, "no_key", "No API key configured. Open Ubiq Settings and paste your OpenAI API key.")

        val body = JSONObject()
            .put("model", model)
            .put("instructions", instructions)
            .put("input", input)
            .put("max_output_tokens", maxOutputTokens)
            .put("store", store)
            .put("parallel_tool_calls", false)
        if (tools != null && tools.length() > 0) body.put("tools", tools).put("tool_choice", "auto")
        if (previousResponseId != null) body.put("previous_response_id", previousResponseId)
        if (!effort.isNullOrBlank()) body.put("reasoning", JSONObject().put("effort", effort))
        if (!serviceTier.isNullOrBlank()) body.put("service_tier", serviceTier)
        if (format != null) body.put("text", JSONObject().put("format", format))

        val req = Request.Builder().url(ENDPOINT)
            .header("Authorization", "Bearer $key")
            .header("Content-Type", "application/json")
            .post(body.toString().toRequestBody(JSON))
            .build()

        http.newCall(req).execute().use { resp ->
            val raw = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                val err = runCatching { JSONObject(raw).optJSONObject("error") }.getOrNull()
                throw ApiException(resp.code, err?.optString("code"), err?.optString("message")?.ifBlank { null } ?: "HTTP ${resp.code}")
            }
            return parse(JSONObject(raw))
        }
    }

    private fun parse(j: JSONObject): Result {
        val out = j.optJSONArray("output") ?: JSONArray()
        val text = StringBuilder()
        val calls = ArrayList<FnCall>()
        for (i in 0 until out.length()) {
            val item = out.getJSONObject(i)
            when (item.optString("type")) {
                "message" -> {
                    val content = item.optJSONArray("content") ?: continue
                    for (k in 0 until content.length()) {
                        val c = content.getJSONObject(k)
                        when (c.optString("type")) {
                            "output_text" -> text.append(c.optString("text"))
                            "refusal" -> text.append(c.optString("refusal"))
                        }
                    }
                }
                "function_call" -> {
                    val args = runCatching { JSONObject(item.optString("arguments", "{}")) }.getOrDefault(JSONObject())
                    calls += FnCall(item.optString("call_id"), item.optString("name"), args)
                }
            }
        }
        return Result(
            id = j.optString("id"),
            text = text.toString().trim(),
            functionCalls = calls,
            status = j.optString("status"),
            incompleteReason = j.optJSONObject("incomplete_details")?.optString("reason"),
        )
    }
}
