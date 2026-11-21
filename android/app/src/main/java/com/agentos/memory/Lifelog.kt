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
 * The owner's timeline (docs/MEMORY.md): what Ubiq observed on the phone, kept on the phone only, as text, for
 * [days] (90 unless the owner changes it).
 * Screens (new lines only, see [Capture]), notifications and places (see [Places]) are events in one SQLite table
 * with a full-text index; `recall` searches it. Everything here is *observed*: context, never the owner's words.
 */
object Lifelog {
    private const val TAG = "AgentOS/Lifelog"
    val DAY_CHOICES = listOf(7, 30, 90)
    enum class Kind(val source: String) { SCREEN("screens"), NOTIFICATION("notifications"), PLACE("places"), EPISODE("") }

    class Event(val id: Long, val time: Long, val until: Long, val kind: Kind, val app: String, val text: String, val lat: Double?, val lon: Double?)

    /** Never recorded, whatever the settings: Ubiq itself (its conversations are kept already), System UI, settings search and keyboards ([keyboards]). */
    private val ALWAYS_OUT = setOf("com.android.systemui", "com.android.settings.intelligence")
    /** Kept out until the owner says otherwise (Settings): password managers and authenticators. */
    val DEFAULT_EXCLUDED = setOf("com.x8bit.bitwarden", "com.onepassword.android", "com.lastpass.lpandroid", "com.dashlane",
        "keepass2android.keepass2android", "com.google.android.apps.authenticator2", "com.authy.authy", "com.azure.authenticator")

    private lateinit var helper: Helper
    private lateinit var appCtx: Context
    private val db: SQLiteDatabase? get() = if (::helper.isInitialized) try { helper.writableDatabase } catch (t: Throwable) { Log.w(TAG, "open", t); null } else null

    fun init(ctx: Context) {
        if (::helper.isInitialized) return
        appCtx = ctx.applicationContext
        helper = Helper(appCtx)
        // Builds before 2026-11-17 recorded Ubiq's own screens (the exclude list named the old app id): they go.
        com.agentos.agent.AgentRuntime.scope.launch { purge(); deleteApp(appCtx.packageName) }
    }

    /** Recording is on (the owner turned it on in Settings and has not paused it). */
    fun enabled(ctx: Context) = Prefs.personalContext(ctx)
    /** Recording is on for this source. */
    fun enabled(ctx: Context, kind: Kind) = enabled(ctx) && Prefs.recordSource(ctx, kind.source)
    fun days(ctx: Context) = Prefs.timelineDays(ctx)

    /** The apps the owner keeps out (Settings), the defaults until they change them. */
    fun excludedApps(ctx: Context): Set<String> = Prefs.timelineExcluded(ctx) ?: DEFAULT_EXCLUDED

    /** Whether [pkg] is kept out of the timeline: always for Ubiq, System UI and keyboards, else as the owner set it. */
    fun excluded(ctx: Context, pkg: String) = pkg == ctx.packageName || pkg in ALWAYS_OUT || pkg in keyboards(ctx) || pkg in excludedApps(ctx)

    @Volatile private var keyboardCache: Pair<Long, Set<String>> = 0L to emptySet()
    /** Every installed keyboard: what is typed is never recorded, nor are the keyboard's suggestions. */
    fun keyboards(ctx: Context): Set<String> {
        val (at, set) = keyboardCache
        if (System.currentTimeMillis() - at < 60_000) return set
        val now = try { ctx.getSystemService(android.view.inputmethod.InputMethodManager::class.java)?.inputMethodList?.map { it.packageName }?.toSet() } catch (_: Throwable) { null } ?: set
        keyboardCache = System.currentTimeMillis() to now
        return now
    }

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

    /** Raw events older than [days] go; episodes stay. */
    fun purge(days: Int = Prefs.timelineDays(appCtx)) {
        val cut = System.currentTimeMillis() - days * 86_400_000L
        val d = db ?: return
        try {
            d.execSQL("DELETE FROM events_fts WHERE docid IN (SELECT id FROM events WHERE until < ? AND kind != 'EPISODE')", arrayOf<Any>(cut))
            d.execSQL("DELETE FROM events WHERE until < ? AND kind != 'EPISODE'", arrayOf<Any>(cut))
        } catch (t: Throwable) { Log.w(TAG, "purge", t) }
    }

    /** Settings: one moment the owner removed. */
    fun delete(id: Long) {
        try { db?.execSQL("DELETE FROM events_fts WHERE docid = ?", arrayOf<Any>(id)); db?.execSQL("DELETE FROM events WHERE id = ?", arrayOf<Any>(id)) }
        catch (t: Throwable) { Log.w(TAG, "delete", t) }
    }

    /** Settings: everything recorded from an app the owner just kept out. */
    fun deleteApp(pkg: String) {
        try {
            db?.execSQL("DELETE FROM events_fts WHERE docid IN (SELECT id FROM events WHERE app = ?)", arrayOf<Any>(pkg))
            db?.execSQL("DELETE FROM events WHERE app = ?", arrayOf<Any>(pkg))
        } catch (t: Throwable) { Log.w(TAG, "deleteApp", t) }
    }

    /** Settings > Delete all. */
    fun clear() {
        val d = db ?: return
        try { d.execSQL("DELETE FROM events"); d.execSQL("DELETE FROM events_fts") } catch (t: Throwable) { Log.w(TAG, "clear", t) }
        Capture.reset()
        Places.forget()
    }
}
