package com.agentos.notify

import android.app.PendingIntent
import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** One notification as the agent sees it. [id] is a short number the agent can refer to. */
data class Notif(val id: Int, val key: String, val pkg: String, val app: String, val title: String, val text: String, val time: Long) {
    fun line(now: Long = System.currentTimeMillis()): String {
        val mins = ((now - time) / 60_000).coerceAtLeast(0)
        val ago = when { mins < 1 -> "just now"; mins < 60 -> "${mins}m ago"; mins < 1440 -> "${mins / 60}h ago"; else -> "${mins / 1440}d ago" }
        return "[$id] $app · $ago · ${title.ifBlank { "(no title)" }}: ${text.take(400)}"
    }
    fun toJson() = JSONObject().put("id", id).put("key", key).put("pkg", pkg).put("app", app).put("title", title).put("text", text).put("time", time)
    companion object {
        fun from(o: JSONObject) = Notif(o.optInt("id"), o.optString("key"), o.optString("pkg"), o.optString("app"), o.optString("title"), o.optString("text"), o.optLong("time"))
    }
}

/**
 * Recent notifications (last [MAX]), kept on disk so digests survive the process being killed.
 * Content intents are kept in memory only, for `open_notification`.
 */
object NotificationStore {
    private const val MAX = 400
    private lateinit var file: File
    private val list = ArrayList<Notif>()
    private val intents = HashMap<String, PendingIntent>()
    private var seq = 0
    private val _count = MutableStateFlow(0)
    /** Bumps on every change, for UI that wants to refresh. */
    val changes: StateFlow<Int> = _count

    @Synchronized fun init(ctx: Context) {
        if (::file.isInitialized) return
        file = File(ctx.filesDir, "notifications.json")
        try {
            val a = JSONArray(file.readText())
            for (i in 0 until a.length()) list += Notif.from(a.getJSONObject(i))
            seq = list.maxOfOrNull { it.id } ?: 0
        } catch (_: Throwable) {}
    }

    /** Adds a notification unless it repeats the latest one with the same key (apps re-post on every update). */
    @Synchronized fun add(key: String, pkg: String, app: String, title: String, text: String, time: Long, intent: PendingIntent?): Notif? {
        val prev = list.lastOrNull { it.key == key }
        if (prev != null && prev.title == title && prev.text == text) return null
        val n = Notif(++seq, key, pkg, app, title, text, time)
        list += n
        while (list.size > MAX) list.removeAt(0)
        if (intent != null) intents[key] = intent
        save(); _count.value++
        return n
    }

    @Synchronized fun recent(app: String? = null, query: String? = null, since: Long = 0, limit: Int = 30): List<Notif> =
        list.asReversed().asSequence()
            .filter { it.time >= since }
            .filter { app.isNullOrBlank() || matchesApp(it, app) }
            .filter { query.isNullOrBlank() || "${it.title} ${it.text}".contains(query, true) }
            .take(limit).toList().asReversed()

    @Synchronized fun get(id: Int): Notif? = list.firstOrNull { it.id == id }
    @Synchronized fun byKey(key: String): Notif? = list.lastOrNull { it.key == key }
    @Synchronized fun intent(n: Notif): PendingIntent? = intents[n.key]

    fun matchesApp(n: Notif, app: String): Boolean = n.pkg.equals(app, true) || n.app.equals(app, true) ||
        n.app.contains(app, true) || n.pkg.contains(app, true)

    private fun save() {
        try { file.writeText(JSONArray().apply { list.forEach { put(it.toJson()) } }.toString()) } catch (_: Throwable) {}
    }
}
