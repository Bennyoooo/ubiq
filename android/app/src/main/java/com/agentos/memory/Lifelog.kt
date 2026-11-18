package com.agentos.memory

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import com.agentos.agent.Secrets
import com.agentos.data.Prefs
import kotlinx.coroutines.launch

/**
 * The owner's timeline (docs/MEMORY.md): what Ubiq observed on the phone, kept on the phone only, as text, for 90 days.
 * Screens (new lines only, see [Capture]), notifications and places (see [Places]) are events in one SQLite table
 * with a full-text index; `recall` searches it. Everything here is *observed*: context, never the owner's words.
 */
object Lifelog {
    private const val TAG = "AgentOS/Lifelog"
    const val KEEP_DAYS = 90
    enum class Kind { SCREEN, NOTIFICATION, PLACE, EPISODE }

    class Event(val id: Long, val time: Long, val until: Long, val kind: Kind, val app: String, val text: String, val lat: Double?, val lon: Double?)

    /** Apps never recorded: password managers and authenticators, and Ubiq itself (its conversations are kept already). */
    val EXCLUDED = setOf("com.agentos", "com.android.systemui", "com.google.android.inputmethod.latin", "com.x8bit.bitwarden", "com.onepassword.android",
        "com.lastpass.lpandroid", "com.dashlane", "keepass2android.keepass2android", "com.google.android.apps.authenticator2", "com.authy.authy",
        "com.azure.authenticator", "com.android.settings.intelligence")

    private lateinit var helper: Helper
    private val db: SQLiteDatabase? get() = if (::helper.isInitialized) try { helper.writableDatabase } catch (t: Throwable) { Log.w(TAG, "open", t); null } else null

    fun init(ctx: Context) {
        if (::helper.isInitialized) return
        helper = Helper(ctx.applicationContext)
        com.agentos.agent.AgentRuntime.scope.launch { purge() }
    }

    /** Capture is on unless the owner paused it in Settings. */
    fun enabled(ctx: Context) = Prefs.personalContext(ctx)

    private class Helper(ctx: Context) : SQLiteOpenHelper(ctx, "lifelog.db", null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE events (id INTEGER PRIMARY KEY AUTOINCREMENT, time INTEGER NOT NULL, until INTEGER NOT NULL, kind TEXT NOT NULL, app TEXT NOT NULL, text TEXT NOT NULL, lat REAL, lon REAL)")
            db.execSQL("CREATE INDEX events_time ON events(time)")
            db.execSQL("CREATE VIRTUAL TABLE events_fts USING fts4(text, app)")
        }
        override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) {}
    }

    /** One code or password in [text] is enough to keep it out: codes never go to disk. */
    fun clean(text: String): String? {
        val t = text.trim()
        if (t.isEmpty()) return null
        return if (Secrets().redact(t) != t) null else t
    }

    fun add(kind: Kind, app: String, text: String, time: Long = System.currentTimeMillis(), lat: Double? = null, lon: Double? = null): Long? {
        val d = db ?: return null
        return try {
            d.beginTransaction()
            val id = d.insert("events", null, ContentValues().apply {
                put("time", time); put("until", time); put("kind", kind.name); put("app", app); put("text", text)
                if (lat != null) put("lat", lat); if (lon != null) put("lon", lon)
            })
            d.insert("events_fts", null, ContentValues().apply { put("docid", id); put("text", text); put("app", app) })
            d.setTransactionSuccessful()
            id
        } catch (t: Throwable) { Log.w(TAG, "add", t); null } finally { try { d.endTransaction() } catch (_: Throwable) {} }
    }

    /** The event goes on until [until] (the same screen still showing, the same stay). */
    fun extend(id: Long, until: Long) {
        try { db?.execSQL("UPDATE events SET until = ? WHERE id = ?", arrayOf<Any>(until, id)) } catch (t: Throwable) { Log.w(TAG, "extend", t) }
    }

    fun rename(id: Long, text: String) {
        val d = db ?: return
        try {
            d.execSQL("UPDATE events SET text = ? WHERE id = ?", arrayOf<Any>(text, id))
            d.execSQL("UPDATE events_fts SET text = ? WHERE docid = ?", arrayOf<Any>(text, id))
        } catch (t: Throwable) { Log.w(TAG, "rename", t) }
    }

    /**
     * Events matching [query] (full-text words, any of them; blank: all) between [from] and [to], newest first.
     */
    fun search(query: String?, from: Long = 0, to: Long = Long.MAX_VALUE, kinds: Set<Kind>? = null, limit: Int = 40): List<Event> {
        val d = db ?: return emptyList()
        val words = query.orEmpty().lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length > 1 }.take(8)
        val kindSql = kinds?.joinToString(",") { "'${it.name}'" }?.let { " AND e.kind IN ($it)" }.orEmpty()
        val sql: String
        val args: Array<String>
        if (words.isEmpty()) {
            sql = "SELECT e.* FROM events e WHERE e.until >= ? AND e.time <= ?$kindSql ORDER BY e.time DESC LIMIT $limit"
            args = arrayOf(from.toString(), to.toString())
        } else {
            sql = "SELECT e.* FROM events_fts f JOIN events e ON e.id = f.docid WHERE events_fts MATCH ? AND e.until >= ? AND e.time <= ?$kindSql ORDER BY e.time DESC LIMIT $limit"
            args = arrayOf(words.joinToString(" OR ") { "$it*" }, from.toString(), to.toString())
        }
        return try {
            d.rawQuery(sql, args).use { c ->
                val out = ArrayList<Event>()
                while (c.moveToNext()) out += Event(c.getLong(0), c.getLong(1), c.getLong(2), runCatching { Kind.valueOf(c.getString(3)) }.getOrDefault(Kind.SCREEN),
                    c.getString(4), c.getString(5), if (c.isNull(6)) null else c.getDouble(6), if (c.isNull(7)) null else c.getDouble(7))
                out
            }
        } catch (t: Throwable) { Log.w(TAG, "search", t); emptyList() }
    }

    /** Rows and bytes of text, for Settings. */
    fun stats(): Pair<Long, Long> = try {
        db?.rawQuery("SELECT COUNT(*), COALESCE(SUM(LENGTH(text)), 0) FROM events", null)?.use { c -> c.moveToFirst(); c.getLong(0) to c.getLong(1) } ?: (0L to 0L)
    } catch (t: Throwable) { 0L to 0L }

    /** Raw events older than [KEEP_DAYS] go; episodes stay. */
    fun purge() {
        val cut = System.currentTimeMillis() - KEEP_DAYS * 86_400_000L
        val d = db ?: return
        try {
            d.execSQL("DELETE FROM events_fts WHERE docid IN (SELECT id FROM events WHERE until < ? AND kind != 'EPISODE')", arrayOf<Any>(cut))
            d.execSQL("DELETE FROM events WHERE until < ? AND kind != 'EPISODE'", arrayOf<Any>(cut))
        } catch (t: Throwable) { Log.w(TAG, "purge", t) }
    }

    /** Settings > Delete all. */
    fun clear() {
        val d = db ?: return
        try { d.execSQL("DELETE FROM events"); d.execSQL("DELETE FROM events_fts") } catch (t: Throwable) { Log.w(TAG, "clear", t) }
        Capture.reset()
    }
}
