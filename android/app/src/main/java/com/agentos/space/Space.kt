package com.agentos.space

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Space: a small library the user and the agent share. The user jots notes; the agent keeps
 * memories (lasting facts and preferences) and saves things worth keeping (lists, plans, drafts).
 * Both can read everything; memories are also given to the agent at the start of every task.
 */
object Space {
    enum class Kind(val label: String) { NOTE("Note"), MEMORY("Memory"), SAVED("Saved") }

    data class Item(
        val id: Long, val kind: Kind, val title: String, val text: String,
        val byAgent: Boolean, val created: Long, val updated: Long, val pinned: Boolean = false,
    ) {
        fun line() = "[$id] ${kind.label}${if (byAgent) " (by you)" else " (by the user)"}: ${title.ifBlank { text.lineSequence().first().take(60) }}"
    }

    private const val TAG = "AgentOS/Space"
    private val _items = MutableStateFlow<List<Item>>(emptyList())
    /** Newest first, pinned on top. */
    val items: StateFlow<List<Item>> = _items
    private var file: File? = null

    fun init(ctx: Context) {
        if (file != null) return
        val f = File(ctx.filesDir, "space.json").also { file = it }
        _items.value = try {
            val a = JSONArray(f.takeIf { it.exists() }?.readText() ?: "[]")
            (0 until a.length()).map { a.getJSONObject(it) }.map { o ->
                Item(o.getLong("id"), runCatching { Kind.valueOf(o.getString("kind")) }.getOrDefault(Kind.NOTE), o.optString("title"), o.optString("text"),
                    o.optBoolean("agent"), o.optLong("created"), o.optLong("updated"), o.optBoolean("pinned"))
            }.sorted()
        } catch (t: Throwable) { Log.w(TAG, "load", t); emptyList() }
    }

    private fun List<Item>.sorted() = sortedWith(compareByDescending<Item> { it.pinned }.thenByDescending { it.updated })

    private fun save(list: List<Item>) {
        _items.value = list.sorted()
        val a = JSONArray()
        list.forEach { a.put(JSONObject().put("id", it.id).put("kind", it.kind.name).put("title", it.title).put("text", it.text)
            .put("agent", it.byAgent).put("created", it.created).put("updated", it.updated).put("pinned", it.pinned)) }
        try { file?.writeText(a.toString()) } catch (t: Throwable) { Log.w(TAG, "save", t) }
    }

    @Synchronized fun add(kind: Kind, title: String, text: String, byAgent: Boolean): Item {
        val now = System.currentTimeMillis()
        val item = Item(maxOf(now, (_items.value.maxOfOrNull { it.id } ?: 0) + 1), kind, title.trim(), text.trim(), byAgent, now, now)
        save(_items.value + item)
        return item
    }

    @Synchronized fun update(id: Long, title: String? = null, text: String? = null, pinned: Boolean? = null): Item? {
        val old = get(id) ?: return null
        val new = old.copy(title = title?.trim() ?: old.title, text = text?.trim() ?: old.text, pinned = pinned ?: old.pinned, updated = System.currentTimeMillis())
        save(_items.value.map { if (it.id == id) new else it })
        return new
    }

    @Synchronized fun delete(id: Long): Boolean {
        if (get(id) == null) return false
        save(_items.value.filter { it.id != id })
        return true
    }

    fun get(id: Long) = _items.value.firstOrNull { it.id == id }

    fun search(query: String?, kind: Kind?): List<Item> {
        val words = query.orEmpty().lowercase().split(Regex("\\s+")).filter { it.length > 1 }
        return _items.value.filter { kind == null || it.kind == kind }
            .map { it to words.count { w -> (it.title + " " + it.text).lowercase().contains(w) } }
            .filter { words.isEmpty() || it.second > 0 }
            .sortedByDescending { it.second }.map { it.first }
    }

    /**
     * What the agent should know at the start of a task: its memories, and what else is there.
     * [only] limits the memories to those picked for this request (agent/Memory.select).
     */
    fun forPrompt(only: Set<Long>? = null): String {
        val all = _items.value.filter { it.kind == Kind.MEMORY }
        val mem = all.filter { only == null || it.id in only }.take(60)
        val others = _items.value.filter { it.kind != Kind.MEMORY }
        return buildString {
            if (mem.isEmpty()) append("(no memories yet)\n")
            mem.forEach { append("- [").append(it.id).append("] ").append(if (it.title.startsWith("Route: ")) "(app route) " else "").append(it.text.replace('\n', ' ').take(240)).append('\n') }
            if (all.size > mem.size) append("(").append(all.size - mem.size).append(" other memories not shown as unrelated to this request; space_search finds them.)\n")
            if (others.isNotEmpty()) append("Also in Space: ").append(others.size).append(" notes/saved items; latest: ")
                .append(others.take(8).joinToString("; ") { "[${it.id}] " + it.title.ifBlank { it.text.take(40) } }).append('\n')
        }
    }
}
