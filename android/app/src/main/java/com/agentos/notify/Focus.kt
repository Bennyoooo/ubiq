package com.agentos.notify

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.compose.ui.graphics.asImageBitmap
import org.json.JSONArray
import org.json.JSONObject

/**
 * A personal focus mode (`focus_mode`): only the people the user named reach them, in any app; calls from contacts
 * still ring; everything else waits and is summed up when focus ends.
 *
 * How it holds things back, with only a notification listener (no ROM change):
 *  - Do Not Disturb: with notification policy access (an enabled listener has it on stock Android) the policy is set
 *    to "calls from contacts, repeat callers, alarms; no messages", and the previous policy and filter are restored
 *    at the end. Without it, the listener asks for priority mode, which uses whatever the user's own DND allows.
 *  - Other people's notifications are snoozed: until the end time, or in [CHUNK_MS] slices when focus has no end
 *    (Android lets only the system unsnooze early, so a slice that comes back while focus is still on is snoozed again).
 *  - An allowed person's notification is let through and also dropped as an island card, because DND keeps it silent.
 */
object Focus {
    private const val TAG = "AgentOS/Focus"
    private const val CHUNK_MS = 15 * 60_000L
    private val main by lazy { Handler(Looper.getMainLooper()) }
    private lateinit var app: Context

    data class Held(val key: String, val pkg: String, val app: String, val sender: String, val text: String, val time: Long, val needsReply: Boolean) {
        fun toJson() = JSONObject().put("key", key).put("pkg", pkg).put("app", app).put("sender", sender).put("text", text).put("time", time).put("reply", needsReply)
        companion object {
            fun from(o: JSONObject) = Held(o.optString("key"), o.optString("pkg"), o.optString("app"), o.optString("sender"), o.optString("text"), o.optLong("time"), o.optBoolean("reply"))
        }
    }

    data class State(
        val people: List<String>, val allowCalls: Boolean, val started: Long, val until: Long,
        /** What to put back at the end: the interruption filter, and the DND policy if we changed it. */
        val prevFilter: Int, val prevPolicy: JSONObject?,
        val held: List<Held> = emptyList(), val letThrough: Int = 0,
    ) {
        fun toJson() = JSONObject().put("people", JSONArray(people)).put("calls", allowCalls).put("started", started).put("until", until)
            .put("prevFilter", prevFilter).put("prevPolicy", prevPolicy ?: JSONObject.NULL).put("through", letThrough)
            .put("held", JSONArray().apply { held.forEach { put(it.toJson()) } })
        companion object {
            fun from(o: JSONObject) = State(
                o.optJSONArray("people")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty(), o.optBoolean("calls", true),
                o.optLong("started"), o.optLong("until"), o.optInt("prevFilter", NotificationManager.INTERRUPTION_FILTER_ALL),
                o.optJSONObject("prevPolicy"),
                o.optJSONArray("held")?.let { a -> (0 until a.length()).map { Held.from(a.getJSONObject(it)) } }.orEmpty(), o.optInt("through"))
        }
    }

    @Volatile var state: State? = null
        private set

    fun init(ctx: Context) {
        if (::app.isInitialized) return
        app = ctx.applicationContext
        state = com.agentos.data.Prefs.focusState(app)?.let { runCatching { State.from(JSONObject(it)) }.getOrNull() }
        state?.let { scheduleEnd(it) }
    }

    val isOn: Boolean get() = state != null

    // ---------- start / stop ----------

    fun start(ctx: Context, people: List<String>, until: Long, allowCalls: Boolean = true): String {
        init(ctx)
        val names = people.map { it.trim() }.filter { it.isNotEmpty() }.distinctBy { it.lowercase() }
        if (names.isEmpty()) return "Name at least one person who may reach the user."
        val w = NotificationWatcher.instance ?: return "Focus needs notification access: Ubiq Settings > Notifications > Allow access."
        val nm = app.getSystemService(NotificationManager::class.java)
        val old = state
        val prevFilter = old?.prevFilter ?: nm.currentInterruptionFilter
        val prevPolicy = old?.prevPolicy ?: if (nm.isNotificationPolicyAccessGranted) policyJson(nm.notificationPolicy) else null
        val s = State(names, allowCalls, old?.started ?: System.currentTimeMillis(), until, prevFilter, prevPolicy, old?.held.orEmpty(), old?.letThrough ?: 0)
        save(s)
        val dnd = applyDnd(w, nm, allowCalls)
        scheduleEnd(s)
        if (old == null) com.agentos.control.FocusVeil.play(true)
        val end = if (until > 0) " until ${clock(until)}" else " until the user turns it off"
        return "Focus is on$end. Reaching the user: ${names.joinToString()} (in every app)" + (if (allowCalls) ", and calls from contacts" else "") +
            ". Everything else waits for the end-of-focus summary. $dnd"
    }

    /** Ends focus and returns the summary (also shown as a card). Null when focus was off. */
    fun stop(ctx: Context, why: String = "ended"): String? {
        init(ctx)
        val s = state ?: return null
        main.removeCallbacks(endRunnable)
        save(null)
        val nm = app.getSystemService(NotificationManager::class.java)
        try {
            s.prevPolicy?.let { p -> if (nm.isNotificationPolicyAccessGranted) nm.notificationPolicy = policyFrom(p) }
            val w = NotificationWatcher.instance
            if (nm.isNotificationPolicyAccessGranted) nm.setInterruptionFilter(s.prevFilter)
            else w?.requestInterruptionFilter(listenerFilter(s.prevFilter))
        } catch (t: Throwable) { Log.w(TAG, "restore DND", t) }
        com.agentos.control.FocusVeil.play(false)
        val summary = summary(s)
        Log.i(TAG, "focus $why: ${s.held.size} held, ${s.letThrough} let through")
        showSummary(s, summary)
        return summary
    }

    fun status(ctx: Context): String {
        init(ctx)
        val s = state ?: return "Focus is off."
        val end = if (s.until > 0) "until ${clock(s.until)}" else "with no end time"
        return "Focus is on $end (since ${clock(s.started)}). Allowed: ${s.people.joinToString()}" + (if (s.allowCalls) ", calls from contacts" else "") +
            ". Held back so far: ${s.held.size} (${s.held.count { it.needsReply }} may need a reply); let through: ${s.letThrough}."
    }

    fun summary(s: State): String {
        val need = s.held.filter { it.needsReply }
        val head = "While you were focused: ${s.held.size} notification${if (s.held.size == 1) "" else "s"}, ${need.size} need${if (need.size == 1) "s" else ""} you."
        if (s.held.isEmpty()) return head
        val lines = (need + s.held.filterNot { it.needsReply }).take(8).map { "• ${it.sender.ifBlank { it.app }} (${it.app}): ${it.text.take(80)}" }
        return head + "\n" + lines.joinToString("\n")
    }

    // ---------- the listener's side ----------

    /**
     * Called for every notification posted while the listener is bound. Returns true when focus held it back
     * (snoozed), so it is not treated as a fresh arrival.
     */
    fun onPosted(w: NotificationListenerService, sbn: StatusBarNotification): Boolean {
        if (!::app.isInitialized) init(w)
        val s = state ?: return false
        if (s.until in 1..System.currentTimeMillis()) { stop(w, "time"); return false }
        val n = sbn.notification ?: return false
        if (exempt(w, sbn, n, s)) return false
        val names = FocusMatch.senders(n.extras)
        val app = label(w, sbn.packageName)
        val text = FocusMatch.text(n.extras)
        Log.i(TAG, "${sbn.packageName}: senders=$names allowed=${FocusMatch.allowed(s.people, names)}")
        if (FocusMatch.allowed(s.people, names)) {
            // Re-posts of the same message (apps update them) don't count twice.
            if (s.held.none { it.key == sbn.key }) save(s.copy(letThrough = s.letThrough + 1))
            mirror(w, sbn, app, names.firstOrNull().orEmpty(), text)
            return false
        }
        val left = if (s.until > 0) s.until - System.currentTimeMillis() else CHUNK_MS
        if (left <= 1000) return false
        try { w.snoozeNotification(sbn.key, left) } catch (t: Throwable) { Log.w(TAG, "snooze ${sbn.packageName}", t); return false }
        val held = Held(sbn.key, sbn.packageName, app, names.firstOrNull().orEmpty(), text, sbn.postTime, FocusMatch.needsReply(n.extras, n.category))
        save(s.copy(held = s.held.filterNot { it.key == sbn.key } + held))
        return true
    }

    /** Things focus never holds: ongoing and system notifications, calls, alarms, navigation, Ubiq itself. */
    private fun exempt(ctx: Context, sbn: StatusBarNotification, n: Notification, s: State): Boolean {
        val pkg = sbn.packageName
        if (pkg == ctx.packageName || pkg == "android" || pkg == "com.android.systemui" || pkg.startsWith("com.agentos")) return true
        if (sbn.isOngoing || n.flags and Notification.FLAG_FOREGROUND_SERVICE != 0 || n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return true
        // Calls ring by the DND policy (contacts only), not by this filter; snoozing a ringing call would hide it.
        return n.category in setOf(Notification.CATEGORY_CALL, Notification.CATEGORY_ALARM, Notification.CATEGORY_NAVIGATION,
            Notification.CATEGORY_TRANSPORT, Notification.CATEGORY_STOPWATCH, Notification.CATEGORY_SYSTEM, Notification.CATEGORY_MISSED_CALL)
    }

    // ---------- DND ----------

    private fun applyDnd(w: NotificationListenerService, nm: NotificationManager, allowCalls: Boolean): String = try {
        if (nm.isNotificationPolicyAccessGranted) {
            val cats = NotificationManager.Policy.PRIORITY_CATEGORY_ALARMS or
                (if (allowCalls) NotificationManager.Policy.PRIORITY_CATEGORY_CALLS or NotificationManager.Policy.PRIORITY_CATEGORY_REPEAT_CALLERS else 0)
            nm.notificationPolicy = NotificationManager.Policy(cats, NotificationManager.Policy.PRIORITY_SENDERS_CONTACTS, NotificationManager.Policy.PRIORITY_SENDERS_STARRED)
            nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
            "Do Not Disturb is on" + (if (allowCalls) " with calls from contacts allowed." else ".")
        } else {
            w.requestInterruptionFilter(NotificationListenerService.INTERRUPTION_FILTER_PRIORITY)
            "Do Not Disturb is on with the user's own priority settings (Ubiq has no policy access, so calls follow those)."
        }
    } catch (t: Throwable) { Log.w(TAG, "DND", t); "Do Not Disturb could not be turned on; other notifications are still held back." }

    private fun listenerFilter(f: Int) = when (f) {
        NotificationManager.INTERRUPTION_FILTER_PRIORITY -> NotificationListenerService.INTERRUPTION_FILTER_PRIORITY
        NotificationManager.INTERRUPTION_FILTER_NONE -> NotificationListenerService.INTERRUPTION_FILTER_NONE
        NotificationManager.INTERRUPTION_FILTER_ALARMS -> NotificationListenerService.INTERRUPTION_FILTER_ALARMS
        else -> NotificationListenerService.INTERRUPTION_FILTER_ALL
    }

    private fun policyJson(p: NotificationManager.Policy) = JSONObject().put("cats", p.priorityCategories).put("calls", p.priorityCallSenders)
        .put("msgs", p.priorityMessageSenders).put("fx", p.suppressedVisualEffects).put("conv", p.priorityConversationSenders)

    private fun policyFrom(o: JSONObject) = NotificationManager.Policy(o.optInt("cats"), o.optInt("calls"), o.optInt("msgs"), o.optInt("fx"), o.optInt("conv"))

    // ---------- end time ----------

    private val endRunnable = Runnable { if (::app.isInitialized) state?.let { if (it.until in 1..System.currentTimeMillis() + 500) stop(app, "time") } }

    private fun scheduleEnd(s: State) {
        main.removeCallbacks(endRunnable)
        if (s.until > 0) main.postDelayed(endRunnable, (s.until - System.currentTimeMillis()).coerceAtLeast(0))
    }

    // ---------- cards ----------

    private fun mirror(ctx: Context, sbn: StatusBarNotification, app: String, sender: String, text: String) {
        if (!com.agentos.control.ControlService.isRunning) return
        val open = sbn.notification.contentIntent
        val act: () -> Unit = { try { open?.send() } catch (_: Throwable) {} }
        com.agentos.control.IslandCard.drop(com.agentos.control.IslandCard.Card(
            source = "$app · focus", title = sender, text = text, icon = icon(ctx, sbn.packageName),
            keys = if (open != null) listOf("open" to act) else emptyList(), onOpen = open?.let { act }, key = "focus:" + sbn.key,
        ))
    }

    private fun showSummary(s: State, text: String) {
        val nm = app.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(android.app.NotificationChannel("focus", "Focus summary", NotificationManager.IMPORTANCE_LOW))
        val first = text.lineSequence().first()
        nm.notify(4710, Notification.Builder(app, "focus").setSmallIcon(com.agentos.R.drawable.ic_launcher_monochrome)
            .setContentTitle("Focus ended").setContentText(first).setStyle(Notification.BigTextStyle().bigText(text)).setAutoCancel(true).build())
        if (!com.agentos.control.ControlService.isRunning) return
        // A key per person waiting on a reply (up to two), opening their conversation where the app still has it.
        val keys = s.held.filter { it.needsReply }.distinctBy { it.sender + it.pkg }.take(2).mapNotNull { h ->
            val pi = NotificationStore.byKey(h.key)?.let { NotificationStore.intent(it) } ?: return@mapNotNull null
            "reply to ${h.sender.ifBlank { h.app }.substringBefore(' ')}" to { try { pi.send() } catch (_: Throwable) {} }
        }
        com.agentos.control.IslandCard.drop(com.agentos.control.IslandCard.Card(
            // The heading fits one line; the count leads the body.
            source = "ubiq · focus", title = first.substringBefore(": "),
            text = (listOf(first.substringAfter(": ", "")) + text.lineSequence().drop(1)).filter { it.isNotBlank() }.joinToString("\n"), light = com.agentos.ui.Ubiq.Lit,
            keys = keys, showMillis = 12_000, key = "focus:summary",
        ))
    }

    private fun icon(ctx: Context, pkg: String): androidx.compose.ui.graphics.ImageBitmap? = try {
        val d = ctx.packageManager.getApplicationIcon(pkg)
        val b = android.graphics.Bitmap.createBitmap(64, 64, android.graphics.Bitmap.Config.ARGB_8888)
        d.setBounds(0, 0, 64, 64); d.draw(android.graphics.Canvas(b))
        b.asImageBitmap()
    } catch (_: Throwable) { null }

    private fun label(ctx: Context, pkg: String) = try { ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(pkg, 0)).toString() } catch (_: Throwable) { pkg }

    private fun clock(t: Long) = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(t))

    @Synchronized private fun save(s: State?) {
        state = s
        if (::app.isInitialized) com.agentos.data.Prefs.setFocusState(app, s?.toJson()?.toString())
    }

    /** "18:30", "45" (minutes) or "" (no end) as an end time; null when unreadable. */
    fun parseUntil(v: String, now: Long = System.currentTimeMillis()): Long? {
        val t = v.trim()
        if (t.isEmpty()) return 0L
        t.toDoubleOrNull()?.let { return if (it > 0) now + (it * 60_000).toLong() else null }
        val m = Regex("^(\\d{1,2}):(\\d{2})$").find(t) ?: return null
        val c = java.util.Calendar.getInstance().apply {
            timeInMillis = now
            set(java.util.Calendar.HOUR_OF_DAY, m.groupValues[1].toInt()); set(java.util.Calendar.MINUTE, m.groupValues[2].toInt())
            set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
        }
        if (c.timeInMillis <= now) c.add(java.util.Calendar.DAY_OF_MONTH, 1)
        return c.timeInMillis
    }
}

/** Who a notification is from, and whether that is someone the user allowed. No Android state: unit-tested. */
object FocusMatch {
    /** Sender names, most specific first: the newest message's sender, the conversation title, the title. */
    fun senders(ex: Bundle?): List<String> {
        if (ex == null) return emptyList()
        val out = ArrayList<String>()
        val msgs = try { ex.getParcelableArray(Notification.EXTRA_MESSAGES) } catch (_: Throwable) { null }
        (msgs?.lastOrNull() as? Bundle)?.let { m ->
            val p = try { m.getParcelable<android.app.Person>("sender_person") } catch (_: Throwable) { null }
            (p?.name ?: m.getCharSequence("sender"))?.toString()?.let { out += it }
        }
        ex.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()?.let { out += it }
        ex.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.let { out += it }
        return out.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    }

    fun text(ex: Bundle?): String {
        if (ex == null) return ""
        val msgs = try { ex.getParcelableArray(Notification.EXTRA_MESSAGES) } catch (_: Throwable) { null }
        (msgs?.lastOrNull() as? Bundle)?.getCharSequence("text")?.toString()?.takeIf { it.isNotBlank() }?.let { return it.trim() }
        return (ex.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: ex.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty().trim()
    }

    fun needsReply(ex: Bundle?, category: String?): Boolean {
        if (category == Notification.CATEGORY_MESSAGE || ex?.containsKey(Notification.EXTRA_MESSAGES) == true) return true
        return text(ex).trimEnd().endsWith("?")
    }

    fun allowed(people: List<String>, names: List<String>): Boolean = names.any { n -> people.any { matches(it, n) } }

    /**
     * Whether [name] (as an app shows it: "Mom", "Mom ❤️", "Anna Lee", "Anna Lee (2 messages)", "Family: Anna")
     * is the allowed [person]. Whole words only, so "Ann" never matches "Anna" and "Mom" never matches "Mommy blog".
     * Every word of the person must appear in the name; a one-word name ("Anna") also matches a person saved with
     * a surname ("Anna Lee"), since many chat apps show first names only.
     */
    fun matches(person: String, name: String): Boolean {
        val p = words(person)
        val n = words(name)
        if (p.isEmpty() || n.isEmpty()) return false
        if (n.containsAll(p)) return true
        return n.size == 1 && p.size > 1 && p.first() == n.first()
    }

    private fun words(s: String): List<String> =
        s.lowercase().replace(Regex("\\(.*?\\)"), " ").split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }
}
