package com.agentos.core.api

import android.app.AutomaticZenRule
import android.app.NotificationManager
import android.app.NotificationManager.Policy
import android.os.Build
import android.service.notification.ZenPolicy
import android.annotation.TargetApi
import org.json.JSONObject

/**
 * How a Do Not Disturb mode travels between Ubiq and Core, and how it reads to the model and the user.
 * Both sides build and read this format, so it lives with the contract.
 *
 * A mode: {"id","name","owner","type","active","level","allow":{...},"manual","trigger","until"}.
 * `allow` (only for level "priority"; a missing key means the mode does not set it, so the user's usual
 * Do Not Disturb setting applies):
 *   calls, messages: "anyone" | "contacts" | "starred" | "none"
 *   conversations: "all" | "important" | "none"
 *   repeat_callers, alarms, media, touch_sounds, reminders, events, priority_apps: true | false
 *   notifications: "shown" | "no_popups" | "hidden"   (what silenced notifications still show)
 * `level`: "priority" (the allow list decides) | "alarms_only" | "total_silence".
 */
object ZenModes {
    /** The id Core gives the user's own Do Not Disturb (the manual mode, not an automatic rule). */
    const val DND_ID = "dnd"

    val ALLOW_KEYS = listOf("calls", "messages", "conversations", "repeat_callers", "alarms", "media", "touch_sounds",
        "reminders", "events", "priority_apps", "notifications")

    // ---------- level ----------

    fun levelName(filter: Int): String = when (filter) {
        NotificationManager.INTERRUPTION_FILTER_ALARMS -> "alarms_only"
        NotificationManager.INTERRUPTION_FILTER_NONE -> "total_silence"
        NotificationManager.INTERRUPTION_FILTER_ALL -> "off"
        else -> "priority"
    }

    fun levelFilter(name: String): Int? = when (name.trim().lowercase()) {
        "", "priority" -> NotificationManager.INTERRUPTION_FILTER_PRIORITY
        "alarms_only", "alarms" -> NotificationManager.INTERRUPTION_FILTER_ALARMS
        "total_silence", "silence", "none" -> NotificationManager.INTERRUPTION_FILTER_NONE
        else -> null
    }

    // ---------- type ----------

    private val TYPES = listOf("other", "schedule_time", "schedule_calendar", "bedtime", "driving", "immersive", "theater", "managed")

    fun typeName(type: Int): String = TYPES.getOrNull(type) ?: "unknown"

    /** A type an app may give its own mode ("managed" is for device owners only). */
    fun typeFrom(name: String): Int? = when (val n = name.trim().lowercase().replace(' ', '_')) {
        "", "other", "focus", "work" -> 0
        "managed" -> null
        else -> TYPES.indexOf(n).takeIf { it >= 0 }
    }

    // ---------- ZenPolicy <-> allow ----------

    private fun people(t: Int) = when (t) {
        ZenPolicy.PEOPLE_TYPE_ANYONE -> "anyone"; ZenPolicy.PEOPLE_TYPE_CONTACTS -> "contacts"
        ZenPolicy.PEOPLE_TYPE_STARRED -> "starred"; ZenPolicy.PEOPLE_TYPE_NONE -> "none"; else -> null
    }
    private fun peopleType(s: String) = when (s) {
        "anyone" -> ZenPolicy.PEOPLE_TYPE_ANYONE; "contacts" -> ZenPolicy.PEOPLE_TYPE_CONTACTS
        "starred" -> ZenPolicy.PEOPLE_TYPE_STARRED; "none" -> ZenPolicy.PEOPLE_TYPE_NONE
        else -> throw IllegalArgumentException("calls and messages take anyone, contacts, starred or none, not \"$s\"")
    }
    private fun state(s: Int): Boolean? = when (s) { ZenPolicy.STATE_ALLOW -> true; ZenPolicy.STATE_DISALLOW -> false; else -> null }

    @TargetApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    fun allowOf(p: ZenPolicy): JSONObject {
        val o = JSONObject()
        when (p.priorityCategoryCalls) { ZenPolicy.STATE_DISALLOW -> o.put("calls", "none"); ZenPolicy.STATE_ALLOW -> people(p.priorityCallSenders)?.let { o.put("calls", it) } }
        when (p.priorityCategoryMessages) { ZenPolicy.STATE_DISALLOW -> o.put("messages", "none"); ZenPolicy.STATE_ALLOW -> people(p.priorityMessageSenders)?.let { o.put("messages", it) } }
        when (p.priorityCategoryConversations) {
            ZenPolicy.STATE_DISALLOW -> o.put("conversations", "none")
            ZenPolicy.STATE_ALLOW -> when (p.priorityConversationSenders) {
                ZenPolicy.CONVERSATION_SENDERS_ANYONE -> o.put("conversations", "all")
                ZenPolicy.CONVERSATION_SENDERS_IMPORTANT -> o.put("conversations", "important")
                ZenPolicy.CONVERSATION_SENDERS_NONE -> o.put("conversations", "none")
            }
        }
        state(p.priorityCategoryRepeatCallers)?.let { o.put("repeat_callers", it) }
        state(p.priorityCategoryAlarms)?.let { o.put("alarms", it) }
        state(p.priorityCategoryMedia)?.let { o.put("media", it) }
        state(p.priorityCategorySystem)?.let { o.put("touch_sounds", it) }
        state(p.priorityCategoryReminders)?.let { o.put("reminders", it) }
        state(p.priorityCategoryEvents)?.let { o.put("events", it) }
        state(p.priorityChannelsAllowed)?.let { o.put("priority_apps", it) }
        when {
            p.visualEffectNotificationList == ZenPolicy.STATE_DISALLOW -> o.put("notifications", "hidden")
            p.visualEffectPeek == ZenPolicy.STATE_DISALLOW -> o.put("notifications", "no_popups")
            p.visualEffectPeek == ZenPolicy.STATE_ALLOW -> o.put("notifications", "shown")
        }
        return o
    }

    /** [base] with the keys present in [allow] changed. Throws IllegalArgumentException on a bad value. */
    @TargetApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    fun zenPolicy(allow: JSONObject, base: ZenPolicy?): ZenPolicy {
        unknownKeys(allow)?.let { throw IllegalArgumentException(it) }
        val b = ZenPolicy.Builder()
        if (base != null) copy(base, b)
        if (allow.has("calls")) b.allowCalls(peopleType(allow.getString("calls")))
        if (allow.has("messages")) b.allowMessages(peopleType(allow.getString("messages")))
        if (allow.has("conversations")) b.allowConversations(when (val c = allow.getString("conversations")) {
            "all", "anyone" -> ZenPolicy.CONVERSATION_SENDERS_ANYONE; "important", "priority" -> ZenPolicy.CONVERSATION_SENDERS_IMPORTANT
            "none" -> ZenPolicy.CONVERSATION_SENDERS_NONE
            else -> throw IllegalArgumentException("conversations takes all, important or none, not \"$c\"")
        })
        if (allow.has("repeat_callers")) b.allowRepeatCallers(allow.getBoolean("repeat_callers"))
        if (allow.has("alarms")) b.allowAlarms(allow.getBoolean("alarms"))
        if (allow.has("media")) b.allowMedia(allow.getBoolean("media"))
        if (allow.has("touch_sounds")) b.allowSystem(allow.getBoolean("touch_sounds"))
        if (allow.has("reminders")) b.allowReminders(allow.getBoolean("reminders"))
        if (allow.has("events")) b.allowEvents(allow.getBoolean("events"))
        if (allow.has("priority_apps")) b.allowPriorityChannels(allow.getBoolean("priority_apps"))
        if (allow.has("notifications")) when (val n = allow.getString("notifications")) {
            "shown" -> b.showAllVisualEffects()
            "hidden" -> b.hideAllVisualEffects()
            "no_popups" -> b.showFullScreenIntent(false).showLights(false).showPeeking(false).showInAmbientDisplay(false)
                .showStatusBarIcons(true).showBadges(true).showInNotificationList(true)
            else -> throw IllegalArgumentException("notifications takes shown, no_popups or hidden, not \"$n\"")
        }
        return b.build()
    }

    /** Builder(ZenPolicy) isn't in the public SDK: set every field [p] sets. */
    @TargetApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    private fun copy(p: ZenPolicy, b: ZenPolicy.Builder) {
        if (p.priorityCategoryCalls == ZenPolicy.STATE_DISALLOW) b.allowCalls(ZenPolicy.PEOPLE_TYPE_NONE)
        else if (p.priorityCategoryCalls == ZenPolicy.STATE_ALLOW && p.priorityCallSenders != ZenPolicy.PEOPLE_TYPE_UNSET) b.allowCalls(p.priorityCallSenders)
        if (p.priorityCategoryMessages == ZenPolicy.STATE_DISALLOW) b.allowMessages(ZenPolicy.PEOPLE_TYPE_NONE)
        else if (p.priorityCategoryMessages == ZenPolicy.STATE_ALLOW && p.priorityMessageSenders != ZenPolicy.PEOPLE_TYPE_UNSET) b.allowMessages(p.priorityMessageSenders)
        if (p.priorityCategoryConversations == ZenPolicy.STATE_DISALLOW) b.allowConversations(ZenPolicy.CONVERSATION_SENDERS_NONE)
        else if (p.priorityCategoryConversations == ZenPolicy.STATE_ALLOW && p.priorityConversationSenders != ZenPolicy.CONVERSATION_SENDERS_UNSET) b.allowConversations(p.priorityConversationSenders)
        state(p.priorityCategoryRepeatCallers)?.let { b.allowRepeatCallers(it) }
        state(p.priorityCategoryAlarms)?.let { b.allowAlarms(it) }
        state(p.priorityCategoryMedia)?.let { b.allowMedia(it) }
        state(p.priorityCategorySystem)?.let { b.allowSystem(it) }
        state(p.priorityCategoryReminders)?.let { b.allowReminders(it) }
        state(p.priorityCategoryEvents)?.let { b.allowEvents(it) }
        state(p.priorityChannelsAllowed)?.let { b.allowPriorityChannels(it) }
        state(p.visualEffectFullScreenIntent)?.let { b.showFullScreenIntent(it) }
        state(p.visualEffectLights)?.let { b.showLights(it) }
        state(p.visualEffectPeek)?.let { b.showPeeking(it) }
        state(p.visualEffectStatusBar)?.let { b.showStatusBarIcons(it) }
        state(p.visualEffectBadge)?.let { b.showBadges(it) }
        state(p.visualEffectAmbient)?.let { b.showInAmbientDisplay(it) }
        state(p.visualEffectNotificationList)?.let { b.showInNotificationList(it) }
    }

    // ---------- the user's own Do Not Disturb (a NotificationManager.Policy) <-> allow ----------

    private fun senders(s: Int) = when (s) { Policy.PRIORITY_SENDERS_ANY -> "anyone"; Policy.PRIORITY_SENDERS_CONTACTS -> "contacts"; else -> "starred" }
    private fun sendersOf(s: String) = when (s) {
        "anyone" -> Policy.PRIORITY_SENDERS_ANY; "contacts" -> Policy.PRIORITY_SENDERS_CONTACTS; "starred" -> Policy.PRIORITY_SENDERS_STARRED
        else -> throw IllegalArgumentException("calls and messages take anyone, contacts, starred or none, not \"$s\"")
    }

    fun allowOf(p: Policy): JSONObject {
        val c = p.priorityCategories
        fun has(bit: Int) = c and bit != 0
        val o = JSONObject()
            .put("calls", if (has(Policy.PRIORITY_CATEGORY_CALLS)) senders(p.priorityCallSenders) else "none")
            .put("messages", if (has(Policy.PRIORITY_CATEGORY_MESSAGES)) senders(p.priorityMessageSenders) else "none")
            .put("conversations", if (!has(Policy.PRIORITY_CATEGORY_CONVERSATIONS)) "none" else when (p.priorityConversationSenders) {
                Policy.CONVERSATION_SENDERS_ANYONE -> "all"; Policy.CONVERSATION_SENDERS_IMPORTANT -> "important"; else -> "none" })
            .put("repeat_callers", has(Policy.PRIORITY_CATEGORY_REPEAT_CALLERS))
            .put("alarms", has(Policy.PRIORITY_CATEGORY_ALARMS))
            .put("media", has(Policy.PRIORITY_CATEGORY_MEDIA))
            .put("touch_sounds", has(Policy.PRIORITY_CATEGORY_SYSTEM))
            .put("reminders", has(Policy.PRIORITY_CATEGORY_REMINDERS))
            .put("events", has(Policy.PRIORITY_CATEGORY_EVENTS))
        // Policy.allowPriorityChannels() isn't in the public SDK.
        try { o.put("priority_apps", Policy::class.java.getMethod("allowPriorityChannels").invoke(p) as Boolean) } catch (_: Throwable) {}
        val fx = p.suppressedVisualEffects
        o.put("notifications", when {
            fx and Policy.SUPPRESSED_EFFECT_NOTIFICATION_LIST != 0 -> "hidden"
            fx and Policy.SUPPRESSED_EFFECT_PEEK != 0 -> "no_popups"
            else -> "shown"
        })
        return o
    }

    /** [base] with the keys present in [allow] changed. priority_apps can't be set through a Policy. */
    fun policy(allow: JSONObject, base: Policy): Policy {
        unknownKeys(allow)?.let { throw IllegalArgumentException(it) }
        if (allow.has("priority_apps")) throw IllegalArgumentException("priority_apps can't be changed on the user's own Do Not Disturb; leave it out")
        var cats = base.priorityCategories
        fun set(bit: Int, on: Boolean) { cats = if (on) cats or bit else cats and bit.inv() }
        var callSenders = base.priorityCallSenders
        var msgSenders = base.priorityMessageSenders
        var convSenders = base.priorityConversationSenders
        if (allow.has("calls")) allow.getString("calls").let { if (it == "none") set(Policy.PRIORITY_CATEGORY_CALLS, false) else { set(Policy.PRIORITY_CATEGORY_CALLS, true); callSenders = sendersOf(it) } }
        if (allow.has("messages")) allow.getString("messages").let { if (it == "none") set(Policy.PRIORITY_CATEGORY_MESSAGES, false) else { set(Policy.PRIORITY_CATEGORY_MESSAGES, true); msgSenders = sendersOf(it) } }
        if (allow.has("conversations")) when (val c = allow.getString("conversations")) {
            "none" -> { set(Policy.PRIORITY_CATEGORY_CONVERSATIONS, false); convSenders = Policy.CONVERSATION_SENDERS_NONE }
            "all", "anyone" -> { set(Policy.PRIORITY_CATEGORY_CONVERSATIONS, true); convSenders = Policy.CONVERSATION_SENDERS_ANYONE }
            "important", "priority" -> { set(Policy.PRIORITY_CATEGORY_CONVERSATIONS, true); convSenders = Policy.CONVERSATION_SENDERS_IMPORTANT }
            else -> throw IllegalArgumentException("conversations takes all, important or none, not \"$c\"")
        }
        mapOf("repeat_callers" to Policy.PRIORITY_CATEGORY_REPEAT_CALLERS, "alarms" to Policy.PRIORITY_CATEGORY_ALARMS,
            "media" to Policy.PRIORITY_CATEGORY_MEDIA, "touch_sounds" to Policy.PRIORITY_CATEGORY_SYSTEM,
            "reminders" to Policy.PRIORITY_CATEGORY_REMINDERS, "events" to Policy.PRIORITY_CATEGORY_EVENTS,
        ).forEach { (k, bit) -> if (allow.has(k)) set(bit, allow.getBoolean(k)) }
        var fx = base.suppressedVisualEffects
        if (allow.has("notifications")) {
            val popups = Policy.SUPPRESSED_EFFECT_FULL_SCREEN_INTENT or Policy.SUPPRESSED_EFFECT_LIGHTS or Policy.SUPPRESSED_EFFECT_PEEK or Policy.SUPPRESSED_EFFECT_AMBIENT
            fx = when (val n = allow.getString("notifications")) {
                "shown" -> 0
                "no_popups" -> popups
                "hidden" -> popups or Policy.SUPPRESSED_EFFECT_STATUS_BAR or Policy.SUPPRESSED_EFFECT_BADGE or Policy.SUPPRESSED_EFFECT_NOTIFICATION_LIST
                else -> throw IllegalArgumentException("notifications takes shown, no_popups or hidden, not \"$n\"")
            }
        }
        return Policy(cats, callSenders, msgSenders, fx, convSenders)
    }

    // ---------- a mode ----------

    @TargetApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    fun modeOf(id: String, r: AutomaticZenRule, active: Boolean, owner: String?): JSONObject = JSONObject()
        .put("id", id).put("name", r.name).put("owner", owner ?: r.owner?.packageName ?: r.configurationActivity?.packageName ?: "")
        .put("type", typeName(r.type)).put("active", active).put("enabled", r.isEnabled)
        .put("level", levelName(r.interruptionFilter))
        .apply { if (r.interruptionFilter == NotificationManager.INTERRUPTION_FILTER_PRIORITY) r.zenPolicy?.let { put("allow", allowOf(it)) } }
        .put("manual", r.isManualInvocationAllowed)
        .apply { r.triggerDescription?.takeIf { it.isNotBlank() }?.let { put("trigger", it) } }

    /** The allow keys this call doesn't know, as an error message; null when all are known. */
    fun unknownKeys(allow: JSONObject): String? =
        allow.keys().asSequence().filter { it !in ALLOW_KEYS }.toList().takeIf { it.isNotEmpty() }
            ?.let { "unknown allow keys ${it.joinToString()}; use ${ALLOW_KEYS.joinToString()}" }

    /** One line a person can read: "Calls: starred contacts; messages: no one; alarms, media; notifications hidden." */
    fun describeAllow(level: String, allow: JSONObject?): String {
        if (level == "alarms_only") return "Only alarms get through."
        if (level == "total_silence") return "Total silence: nothing gets through, not even alarms."
        val a = allow ?: JSONObject()
        if (a.length() == 0) return "Uses the user's usual Do Not Disturb settings for who gets through."
        fun who(v: String) = when (v) { "anyone" -> "anyone"; "contacts" -> "contacts"; "starred" -> "starred contacts"; else -> "no one" }
        val parts = ArrayList<String>()
        a.optString("calls").takeIf { it.isNotEmpty() }?.let { parts += "calls from ${who(it)}" }
        a.optString("messages").takeIf { it.isNotEmpty() }?.let { parts += "messages from ${who(it)}" }
        a.optString("conversations").takeIf { it.isNotEmpty() }?.let {
            parts += when (it) { "all" -> "all conversations"; "important" -> "priority conversations"; else -> "no conversations" }
        }
        val on = ArrayList<String>(); val off = ArrayList<String>()
        listOf("repeat_callers" to "repeat callers", "alarms" to "alarms", "media" to "media", "touch_sounds" to "touch sounds",
            "reminders" to "reminders", "events" to "events", "priority_apps" to "apps allowed to break through")
            .forEach { (k, label) -> if (a.has(k)) (if (a.optBoolean(k)) on else off) += label }
        val sb = StringBuilder()
        if (parts.isNotEmpty()) sb.append(parts.joinToString(", ").replaceFirstChar { it.uppercase() }).append(". ")
        if (on.isNotEmpty()) sb.append("Allowed: ").append(on.joinToString(", ")).append(". ")
        if (off.isNotEmpty()) sb.append("Silenced: ").append(off.joinToString(", ")).append(". ")
        a.optString("notifications").takeIf { it.isNotEmpty() }?.let {
            sb.append(when (it) { "hidden" -> "Silenced notifications are hidden."; "no_popups" -> "Silenced notifications don't pop up."; else -> "Silenced notifications still show." })
        }
        return sb.toString().trim()
    }
}
