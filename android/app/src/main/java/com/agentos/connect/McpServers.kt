package com.agentos.connect

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * Remote MCP servers the user added (Zapier, Notion, a home server, ...). The model reaches them
 * directly through the OpenAI API's MCP tool, so their tools appear next to Ubiq's own.
 * Tokens stay in app-private storage.
 */
object McpServers {
    data class Server(val label: String, val url: String, val token: String)

    private val _servers = MutableStateFlow<List<Server>>(emptyList())
    val servers: StateFlow<List<Server>> = _servers
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("connectors", Context.MODE_PRIVATE)

    fun init(ctx: Context) {
        val a = runCatching { JSONArray(prefs(ctx).getString("mcp", "[]")) }.getOrDefault(JSONArray())
        _servers.value = (0 until a.length()).map { a.getJSONObject(it) }.map { Server(it.optString("label"), it.optString("url"), it.optString("token")) }
    }

    /** Labels may only use letters, digits, '_' and '-'. */
    fun add(ctx: Context, label: String, url: String, token: String): String? {
        val l = label.trim().replace(Regex("[^A-Za-z0-9_-]+"), "_").trim('_').ifBlank { return "Give it a name." }
        if (!url.trim().startsWith("https://")) return "The server URL must start with https://"
        save(ctx, _servers.value.filter { it.label != l } + Server(l, url.trim(), token.trim()))
        return null
    }

    fun remove(ctx: Context, label: String) = save(ctx, _servers.value.filter { it.label != label })

    private fun save(ctx: Context, list: List<Server>) {
        _servers.value = list
        prefs(ctx).edit().putString("mcp", JSONArray().apply { list.forEach { put(JSONObject().put("label", it.label).put("url", it.url).put("token", it.token)) } }.toString()).apply()
    }

    /** Hosted MCP tool entries for the Responses API. */
    fun toolDefinitions(): List<JSONObject> = _servers.value.map { s ->
        JSONObject().put("type", "mcp").put("server_label", s.label).put("server_url", s.url).put("require_approval", "never").apply {
            if (s.token.isNotBlank()) put("headers", JSONObject().put("Authorization", "Bearer ${s.token}"))
        }
    }
}
