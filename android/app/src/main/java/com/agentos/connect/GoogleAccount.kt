package com.agentos.connect

import android.app.PendingIntent
import android.content.Context
import android.util.Base64
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Tasks
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Gmail through Google's API, with the Google account already on the phone (Google Identity
 * authorization; no password ever reaches Ubiq). The first connect shows Google's consent
 * screen from Settings; after that, tokens are fetched silently whenever a tool needs one.
 *
 * Needs an Android OAuth client for ai.ubiq.app (package + signing SHA-1) in a Google Cloud
 * project with the Gmail API enabled: see README "Connectors".
 */
object GoogleAccount {
    val SCOPES = listOf("https://www.googleapis.com/auth/gmail.readonly", "https://www.googleapis.com/auth/gmail.send")
    private const val API = "https://gmail.googleapis.com/gmail/v1/users/me"
    private val http by lazy { OkHttpClient.Builder().readTimeout(60, TimeUnit.SECONDS).build() }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("connectors", Context.MODE_PRIVATE)
    fun connected(ctx: Context) = prefs(ctx).getBoolean("google", false)
    fun email(ctx: Context): String = prefs(ctx).getString("google_email", "") ?: ""
    fun disconnect(ctx: Context) = prefs(ctx).edit().putBoolean("google", false).remove("google_email").apply()

    private fun request() = AuthorizationRequest.builder().setRequestedScopes(SCOPES.map { Scope(it) }).build()

    /** Starts connecting: either done at once, or a consent screen to show (then call [finish]). */
    fun begin(ctx: Context, onConsent: (PendingIntent) -> Unit, onDone: (String?) -> Unit) {
        Identity.getAuthorizationClient(ctx).authorize(request())
            .addOnSuccessListener { r -> if (r.hasResolution()) r.pendingIntent?.let(onConsent) ?: onDone("Google returned no consent screen.") else complete(ctx, r, onDone) }
            .addOnFailureListener { onDone(explain(it)) }
    }

    fun finish(ctx: Context, data: android.content.Intent?, onDone: (String?) -> Unit) {
        try { complete(ctx, Identity.getAuthorizationClient(ctx).getAuthorizationResultFromIntent(data), onDone) }
        catch (t: Throwable) { onDone(explain(t)) }
    }

    private fun complete(ctx: Context, r: AuthorizationResult, onDone: (String?) -> Unit) {
        val token = r.accessToken ?: return onDone("Google gave no access token.")
        Thread {
            val mail = try { JSONObject(get("$API/profile", token)).optString("emailAddress") } catch (t: Throwable) { return@Thread onDone("Connected, but Gmail answered: ${t.message}") }
            prefs(ctx).edit().putBoolean("google", true).putString("google_email", mail).apply()
            onDone(null)
        }.start()
    }

    private fun explain(t: Throwable): String {
        val code = (t as? com.google.android.gms.common.api.ApiException)?.statusCode
        return if (code == 10 || t.message?.contains("10:") == true)
            "This build of Ubiq is not registered with Google yet (developer error 10). Add an Android OAuth client for ai.ubiq.app with this app's SHA-1 in Google Cloud, enable the Gmail API, then try again."
        else "Google sign-in failed: ${t.message}"
    }

    /** A fresh access token, silently. Must not run on the main thread. */
    private fun token(ctx: Context): String {
        val r = Tasks.await(Identity.getAuthorizationClient(ctx).authorize(request()), 30, TimeUnit.SECONDS)
        if (r.hasResolution()) throw IllegalStateException("Google needs the user to reconnect: Ubiq Settings > Connectors > Google.")
        return r.accessToken ?: throw IllegalStateException("No Google access token.")
    }

    private fun get(url: String, token: String): String = http.newCall(Request.Builder().url(url).header("Authorization", "Bearer $token").build()).execute().use {
        val body = it.body?.string().orEmpty()
        if (!it.isSuccessful) throw java.io.IOException("HTTP ${it.code}: ${body.take(200)}")
        body
    }

    fun search(ctx: Context, query: String, max: Int): String {
        val rows = searchRows(ctx, query, max)
        if (rows.isEmpty()) return "No emails match \"$query\"."
        return rows.joinToString("\n") { r ->
            "[${r["id"]}] ${r["date"]!!.take(22)} | ${r["from"]} | ${r["subject"]}" + (if (r["unread"] == "true") " (unread)" else "") + "\n    ${r["snippet"]!!.take(160)}"
        }
    }

    /** Matching emails as rows: id, from, from_name, subject, snippet, date (header), when (epoch ms), unread. */
    fun searchRows(ctx: Context, query: String, max: Int): List<Map<String, String>> {
        val t = token(ctx)
        val list = JSONObject(get("$API/messages?maxResults=${max.coerceIn(1, 25)}&q=" + android.net.Uri.encode(query), t)).optJSONArray("messages")
            ?: return emptyList()
        return (0 until list.length()).map { i ->
            val id = list.getJSONObject(i).getString("id")
            val m = JSONObject(get("$API/messages/$id?format=metadata&metadataHeaders=From&metadataHeaders=Subject&metadataHeaders=Date", t))
            val h = headers(m)
            val from = h["From"].orEmpty()
            mapOf("id" to id, "from" to from, "from_name" to from.substringBefore('<').trim().trim('"').ifBlank { from },
                "subject" to h["Subject"].orEmpty(), "snippet" to android.text.Html.fromHtml(m.optString("snippet"), 0).toString(),
                "date" to h["Date"].orEmpty(), "when" to m.optString("internalDate"),
                "unread" to ("UNREAD" in m.optJSONArray("labelIds").toString()).toString())
        }
    }

    fun read(ctx: Context, id: String): String {
        val m = JSONObject(get("$API/messages/$id?format=full", token(ctx)))
        val h = headers(m)
        val body = bodyText(m.optJSONObject("payload")).ifBlank { m.optString("snippet") }
        return "From: ${h["From"]}\nTo: ${h["To"]}\nDate: ${h["Date"]}\nSubject: ${h["Subject"]}\nThread: ${m.optString("threadId")}\n\n${body.take(12_000)}"
    }

    fun send(ctx: Context, to: String, subject: String, body: String, cc: String?, replyTo: String?): String {
        val t = token(ctx)
        var threadId: String? = null
        val extra = StringBuilder()
        var subj = subject
        if (!replyTo.isNullOrBlank()) {
            val orig = JSONObject(get("$API/messages/$replyTo?format=metadata&metadataHeaders=Message-ID&metadataHeaders=Subject", t))
            threadId = orig.optString("threadId")
            headers(orig)["Message-ID"]?.let { extra.append("In-Reply-To: $it\r\nReferences: $it\r\n") }
            if (subj.isBlank()) subj = headers(orig)["Subject"].orEmpty().let { if (it.startsWith("Re:", true)) it else "Re: $it" }
        }
        val mime = "To: $to\r\n" + (if (!cc.isNullOrBlank()) "Cc: $cc\r\n" else "") +
            "Subject: =?UTF-8?B?${Base64.encodeToString(subj.toByteArray(), Base64.NO_WRAP)}?=\r\n$extra" +
            "MIME-Version: 1.0\r\nContent-Type: text/plain; charset=UTF-8\r\n\r\n$body"
        val payload = JSONObject().put("raw", Base64.encodeToString(mime.toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING))
        threadId?.let { payload.put("threadId", it) }
        http.newCall(Request.Builder().url("$API/messages/send").header("Authorization", "Bearer $t")
            .post(payload.toString().toRequestBody("application/json".toMediaType())).build()).execute().use {
            if (!it.isSuccessful) throw java.io.IOException("Gmail refused: HTTP ${it.code} ${it.body?.string()?.take(200)}")
        }
        return "Sent to $to: \"$subj\"."
    }

    private fun headers(m: JSONObject): Map<String, String> {
        val a = m.optJSONObject("payload")?.optJSONArray("headers") ?: return emptyMap()
        return (0 until a.length()).associate { a.getJSONObject(it).let { h -> h.optString("name") to h.optString("value") } }
    }

    /** The plain-text body, or the HTML one with tags stripped. */
    private fun bodyText(part: JSONObject?): String {
        if (part == null) return ""
        val mime = part.optString("mimeType")
        val data = part.optJSONObject("body")?.optString("data").orEmpty()
        if (data.isNotEmpty() && mime.startsWith("text/")) {
            val text = String(Base64.decode(data, Base64.URL_SAFE), Charsets.UTF_8)
            return if (mime == "text/html") android.text.Html.fromHtml(text, android.text.Html.FROM_HTML_MODE_COMPACT).toString() else text
        }
        val parts = part.optJSONArray("parts") ?: return ""
        val kids = (0 until parts.length()).map { parts.getJSONObject(it) }
        kids.firstOrNull { it.optString("mimeType") == "text/plain" }?.let { return bodyText(it) }
        return kids.asSequence().map { bodyText(it) }.firstOrNull { it.isNotBlank() }.orEmpty()
    }
}
