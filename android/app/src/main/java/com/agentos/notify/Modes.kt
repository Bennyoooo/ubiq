package com.agentos.notify

import android.app.AlarmManager
import android.app.AutomaticZenRule
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.service.notification.Condition
import android.util.Log
import androidx.annotation.RequiresApi
import com.agentos.agent.ToolOutcome
import com.agentos.core.CoreClient
import com.agentos.core.api.CoreContract
import com.agentos.core.api.ZenModes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * The `phone_modes` tool: Do Not Disturb modes, switched through Android's API instead of the Settings screens.
 *
 * Ubiq creates and runs its own modes: each shows in quick settings and Settings > Modes as "Managed by Ubiq",
 * with what it lets through, and the user can start or stop it there too. On the Ubiq ROM, Core
 * (platform-signed) also sees and switches every other mode: the user's own Do Not Disturb, Bedtime, Driving,
 * modes the user made and other apps' modes ([CoreClient] "modes.*"). Without Core, those stay out of reach.
 *
 * Android has no end time for an automatic mode, so `until` is an exact alarm here ([EndReceiver]); the user's
 * own Do Not Disturb uses Android's countdown instead (it shows "Until 8:00" in quick settings).
 */
object Modes {
    private const val TAG = "AgentOS/Modes"
    private const val PREFS = "phone_modes"
    private const val EXTRA_ID = "id"

    private fun nm(ctx: Context) = ctx.getSystemService(NotificationManager::class.java)

    suspend fun run(ctx: Context, input: JSONObject): ToolOutcome {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) return ToolOutcome.error("Modes need Android 15 or later; use the Settings screens.")
        if (!nm(ctx).isNotificationPolicyAccessGranted) return ToolOutcome.error(
            "Ubiq can't manage modes without notification access. Tell the user to allow it: Ubiq Settings > Notifications > Allow access.")
        return try {
            when (val action = input.optString("action")) {
                "status" -> status(ctx)
                "list" -> list(ctx)
                "create" -> create(ctx, input)
                "update" -> update(ctx, input)
                "on", "off" -> switch(ctx, input, action == "on")
                "delete" -> delete(ctx, input)
                else -> ToolOutcome.error("action must be status, list, create, update, on, off or delete.")
            }
        } catch (e: IllegalArgumentException) {
            ToolOutcome.error(e.message ?: "bad argument")
        }
    }

    // ---------- reading ----------

    private class Seen(val modes: List<JSONObject>, val core: Boolean)

    /** Ubiq's own modes, plus every other mode when Core can see them. */
    private suspend fun all(ctx: Context): Seen {
        val nm = nm(ctx)
        val own = nm.automaticZenRules.map { (id, r) -> ZenModes.modeOf(id, r, nm.getAutomaticZenRuleState(id) == Condition.STATE_TRUE, ctx.packageName) }
        val reply = CoreClient.call("modes.list")
        val others = reply?.takeIf { it.optBoolean("ok") }?.optJSONObject("result")?.optJSONArray("modes")
        if (others == null) {
            reply?.let { Log.i(TAG, "Core modes.list: ${it.optString("state")} ${it.opt("result")}") }
            return Seen(own, core = false)
        }
        val ownIds = own.map { it.getString("id") }.toSet()
        return Seen(own + (0 until others.length()).map { others.getJSONObject(it) }.filter { it.optString("id") !in ownIds }, core = true)
    }

    private fun isOwn(ctx: Context, m: JSONObject) = m.optString("owner") == ctx.packageName

    private fun line(ctx: Context, m: JSONObject): String {
        val owner = when {
            isOwn(ctx, m) -> "Ubiq's"
            m.optString("id") == ZenModes.DND_ID -> "the user's own"
            m.optString("owner") in setOf("android", "com.android.settings", "com.android.systemui") -> "made in Settings"
            else -> "from ${m.optString("owner")}"
        }
        val state = if (m.optBoolean("active")) "ON" + (pendingEnd(ctx, m.optString("id")) ?: m.optLong("until").takeIf { it > 0 })?.let { " until ${clock(it)}" }.orEmpty() else "off"
        val type = m.optString("type").takeIf { it != "other" && it != "unknown" }?.let { ", $it" }.orEmpty()
        return "- ${m.optString("name")} [${m.optString("id")}] ($owner$type): $state. " +
            ZenModes.describeAllow(m.optString("level"), m.optJSONObject("allow"))
    }

    private suspend fun status(ctx: Context): ToolOutcome {
        val seen = all(ctx)
        val on = seen.modes.filter { it.optBoolean("active") }
        val filter = nm(ctx).currentInterruptionFilter
        val head = when {
            on.isNotEmpty() -> "On now:\n" + on.joinToString("\n") { line(ctx, it) }
            filter != NotificationManager.INTERRUPTION_FILTER_ALL && !seen.core ->
                "The phone is silenced (${ZenModes.levelName(filter)}) by a mode Ubiq can't see: the user's own Do Not Disturb, Bedtime or another app's mode."
            else -> "No mode is on: everything rings and shows as usual."
        }
        return ToolOutcome(head + reach(seen))
    }

    private suspend fun list(ctx: Context): ToolOutcome {
        val seen = all(ctx)
        val body = if (seen.modes.isEmpty()) "Ubiq has no modes of its own yet." else seen.modes.joinToString("\n") { line(ctx, it) }
        return ToolOutcome("Modes:\n$body" + reach(seen))
    }

    private fun reach(seen: Seen) = if (seen.core) ""
        else "\n(Ubiq only sees and controls the modes it created. The user's own Do Not Disturb, Bedtime, Driving and modes they made " +
            "can't be switched from here: for \"turn on do not disturb\", use or create a Ubiq mode, and say it's Ubiq's.)"

    /** The mode [ref] names: an id, a name, or "dnd"/"do not disturb" for the user's own. */
    private fun find(seen: Seen, ref: String): JSONObject {
        val r = ref.trim()
        if (r.isEmpty()) throw IllegalArgumentException("Say which mode: its id or name (action list shows them).")
        seen.modes.firstOrNull { it.optString("id") == r }?.let { return it }
        val n = r.lowercase()
        seen.modes.filter { it.optString("name").lowercase() == n }.let { if (it.size == 1) return it[0] }
        if (n in setOf("dnd", "do not disturb", "do-not-disturb", "my do not disturb")) {
            seen.modes.firstOrNull { it.optString("id") == ZenModes.DND_ID }?.let { return it }
            throw IllegalArgumentException("The user's own Do Not Disturb is out of Ubiq's reach on this phone. Use or create a Ubiq mode instead.")
        }
        val hits = seen.modes.filter { it.optString("name").lowercase().contains(n) }
        if (hits.size == 1) return hits[0]
        if (hits.size > 1) throw IllegalArgumentException("\"$ref\" matches ${hits.joinToString { "${it.optString("name")} [${it.optString("id")}]" }}; pass the id.")
        throw IllegalArgumentException("No mode called \"$ref\"." + if (!seen.core) " Ubiq only sees its own modes here." else "")
    }

    // ---------- changing ----------

    // Only reached through run(), which returns early below Android 15.
    @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    private suspend fun create(ctx: Context, input: JSONObject): ToolOutcome {
        val name = input.optString("name").trim().ifEmpty { throw IllegalArgumentException("A new mode needs a name, e.g. \"Deep work\".") }
        val nm = nm(ctx)
        nm.automaticZenRules.entries.firstOrNull { it.value.name.equals(name, ignoreCase = true) }?.let {
            throw IllegalArgumentException("Ubiq already has a mode called \"${it.value.name}\" [${it.key}]; update or switch that one.")
        }
        val filter = ZenModes.levelFilter(input.optString("level")) ?: throw IllegalArgumentException("level takes priority, alarms_only or total_silence.")
        val type = ZenModes.typeFrom(input.optString("type")) ?: throw IllegalArgumentException("type takes other, bedtime, driving, immersive, theater, schedule_time or schedule_calendar.")
        val asked = input.optJSONObject("allow")
        if (asked != null && filter != NotificationManager.INTERRUPTION_FILTER_PRIORITY) throw IllegalArgumentException("allow only applies to level priority.")
        asked?.let { ZenModes.unknownKeys(it)?.let { e -> throw IllegalArgumentException(e) } }
        // Android fills what a policy leaves out from its own defaults, not the user's: start from the user's
        // Do Not Disturb when Core can read it, then apply what was asked.
        val usual = if (filter == NotificationManager.INTERRUPTION_FILTER_PRIORITY)
            all(ctx).modes.firstOrNull { it.optString("id") == ZenModes.DND_ID }?.optJSONObject("allow") else null
        val allow = if (usual == null) asked else JSONObject(usual.toString()).also { m -> asked?.let { a -> a.keys().forEach { k -> m.put(k, a.get(k)) } } }
        val cond = Uri.Builder().scheme(Condition.SCHEME).authority(ctx.packageName).appendPath("mode").appendPath(UUID.randomUUID().toString()).build()
        val rule = AutomaticZenRule.Builder(name, cond)
            .setConfigurationActivity(ComponentName(ctx, "com.agentos.ui.HomeActivity"))
            .setType(type)
            .setInterruptionFilter(filter)
            .setManualInvocationAllowed(true)
            .setTriggerDescription("Ubiq turns it on when you ask")
            .apply { if (filter == NotificationManager.INTERRUPTION_FILTER_PRIORITY && allow != null) setZenPolicy(ZenModes.zenPolicy(allow, null)) }
            .build()
        val id = nm.addAutomaticZenRule(rule)
        Log.i(TAG, "created mode $id \"$name\"")
        val msg = StringBuilder("Created mode \"$name\" [$id]. It shows in quick settings and Settings > Modes as managed by Ubiq, and the user can start or stop it there.")
        if (input.optBoolean("on") || input.optString("until").isNotBlank()) {
            val s = switch(ctx, JSONObject(input.toString()).put("mode", id), on = true)
            msg.append(" ").append(s.text)
            if (s.isError) return ToolOutcome.error(msg.toString())
        } else msg.append("\n").append(line(ctx, ZenModes.modeOf(id, nm.getAutomaticZenRule(id), false, ctx.packageName)))
        return ToolOutcome(msg.toString())
    }

    // Only reached through run(), which returns early below Android 15.
    @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    private suspend fun update(ctx: Context, input: JSONObject): ToolOutcome {
        val m = find(all(ctx), input.optString("mode"))
        val id = m.getString("id")
        val allow = input.optJSONObject("allow")
        if (!isOwn(ctx, m)) {
            val args = JSONObject().put("id", id)
            input.optString("name").takeIf { it.isNotBlank() }?.let { args.put("name", it) }
            input.optString("level").takeIf { it.isNotBlank() }?.let { args.put("level", it) }
            allow?.let { args.put("allow", it) }
            return coreReply(ctx, CoreClient.call("modes.update", args), "Changed")
        }
        val nm = nm(ctx)
        val r = nm.getAutomaticZenRule(id)
        input.optString("name").takeIf { it.isNotBlank() }?.let { r.name = it }
        input.optString("level").takeIf { it.isNotBlank() }?.let { r.interruptionFilter = ZenModes.levelFilter(it) ?: throw IllegalArgumentException("level takes priority, alarms_only or total_silence.") }
        if (r.interruptionFilter == NotificationManager.INTERRUPTION_FILTER_PRIORITY) { if (allow != null) r.zenPolicy = ZenModes.zenPolicy(allow, r.zenPolicy) }
        else { if (allow != null) throw IllegalArgumentException("allow only applies to level priority."); r.zenPolicy = null }
        val wasOn = nm.getAutomaticZenRuleState(id) == Condition.STATE_TRUE
        if (!nm.updateAutomaticZenRule(id, r)) return ToolOutcome.error("Android refused to change ${r.name}.")
        // An app's update clears the mode's on/off state: put it back on if it was.
        if (wasOn) nm.setAutomaticZenRuleState(id, Condition(r.conditionId, "On", Condition.STATE_TRUE, Condition.SOURCE_USER_ACTION))
        val now = nm.getAutomaticZenRule(id)
        return ToolOutcome("Changed. " + line(ctx, ZenModes.modeOf(id, now, nm.getAutomaticZenRuleState(id) == Condition.STATE_TRUE, ctx.packageName)) +
            "\n(If the user edited this mode in Settings, Android keeps their edits over Ubiq's.)")
    }

    // Only reached through run(), which returns early below Android 15.
    @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    private suspend fun switch(ctx: Context, input: JSONObject, on: Boolean): ToolOutcome {
        val m = find(all(ctx), input.optString("mode"))
        val id = m.getString("id")
        val until = if (on) com.agentos.notify.Focus.parseUntil(input.optString("until")) ?: throw IllegalArgumentException("until must be HH:MM (24-hour) or a number of minutes.") else 0L
        cancelEnd(ctx, id)
        if (!isOwn(ctx, m)) {
            val nativeEnd = id == ZenModes.DND_ID
            val args = JSONObject().put("id", id).put("on", on).apply { if (nativeEnd && until > 0) put("untilMs", until) }
            val out = coreReply(ctx, CoreClient.call("modes.set_state", args), if (on) "Turned on" else "Turned off")
            if (!out.isError && on && until > 0 && !nativeEnd) scheduleEnd(ctx, id, until)
            return if (!out.isError && on && until > 0) ToolOutcome(out.text.replace(": ON.", ": ON until ${clock(until)}.")) else out
        }
        val nm = nm(ctx)
        val r = nm.getAutomaticZenRule(id)
        nm.setAutomaticZenRuleState(id, Condition(r.conditionId, if (on) "On" else "Off",
            if (on) Condition.STATE_TRUE else Condition.STATE_FALSE, Condition.SOURCE_USER_ACTION))
        val active = nm.getAutomaticZenRuleState(id) == Condition.STATE_TRUE
        if (active != on) return ToolOutcome.error("${r.name} is still ${if (on) "off" else "on"}: Android didn't take the change.")
        if (on && until > 0) scheduleEnd(ctx, id, until)
        return ToolOutcome("${if (on) "Turned on" else "Turned off"}. " + line(ctx, ZenModes.modeOf(id, r, active, ctx.packageName)) +
            if (on) "\nTell the user what gets through, in a sentence." else "")
    }

    private suspend fun delete(ctx: Context, input: JSONObject): ToolOutcome {
        val m = find(all(ctx), input.optString("mode"))
        if (!isOwn(ctx, m)) return ToolOutcome.error("${m.optString("name")} isn't Ubiq's, so Ubiq can't delete it; it can only switch or change it.")
        cancelEnd(ctx, m.getString("id"))
        return if (nm(ctx).removeAutomaticZenRule(m.getString("id"))) ToolOutcome("Deleted mode \"${m.optString("name")}\".")
        else ToolOutcome.error("Android refused to delete ${m.optString("name")}.")
    }

    private fun coreReply(ctx: Context, r: JSONObject?, verb: String): ToolOutcome {
        if (r == null) return ToolOutcome.error("Ubiq Core didn't answer; try again.")
        return when (r.optString("state")) {
            CoreContract.DONE -> ToolOutcome("$verb. " + (r.optJSONObject("result")?.let { line(ctx, it) } ?: ""))
            CoreContract.REFUSED -> ToolOutcome.error("Refused: ${r.opt("result")}")
            else -> ToolOutcome.error("Couldn't: ${r.opt("result")}")
        }
    }

    // ---------- end times ----------

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun pendingEnd(ctx: Context, id: String): Long? = prefs(ctx).getLong("end:$id", 0L).takeIf { it > System.currentTimeMillis() }

    private fun endIntent(ctx: Context, id: String) = PendingIntent.getBroadcast(ctx, id.hashCode(),
        Intent(ctx, EndReceiver::class.java).setAction("com.agentos.MODE_END").putExtra(EXTRA_ID, id),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun scheduleEnd(ctx: Context, id: String, at: Long) {
        prefs(ctx).edit().putLong("end:$id", at).apply()
        ctx.getSystemService(AlarmManager::class.java).setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, endIntent(ctx, id))
    }

    private fun cancelEnd(ctx: Context, id: String) {
        prefs(ctx).edit().remove("end:$id").apply()
        ctx.getSystemService(AlarmManager::class.java).cancel(endIntent(ctx, id))
    }

    /** After a reboot the alarms are gone: set the future ones again, and end the ones that passed meanwhile. */
    private fun rearm(ctx: Context) {
        val now = System.currentTimeMillis()
        for ((k, v) in prefs(ctx).all) {
            if (!k.startsWith("end:") || v !is Long) continue
            val id = k.removePrefix("end:")
            if (v > now) scheduleEnd(ctx, id, v) else ctx.sendBroadcast(Intent(ctx, EndReceiver::class.java).setAction("com.agentos.MODE_END").putExtra(EXTRA_ID, id))
        }
    }

    private fun clock(t: Long) = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(t))

    /** Ends a mode at its `until` time, and re-arms end times after a reboot. */
    class EndReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val ctx = context.applicationContext
            // End times are only set on Android 15+, where modes work at all.
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) return
            if (intent.action == Intent.ACTION_BOOT_COMPLETED) { rearm(ctx); return }
            val id = intent.getStringExtra(EXTRA_ID) ?: return
            val pending = goAsync()
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    CoreClient.init(ctx)
                    prefs(ctx).edit().remove("end:$id").apply()
                    val out = switch(ctx, JSONObject().put("mode", id), on = false)
                    Log.i(TAG, "end time for $id: ${out.text.take(120)}")
                } catch (t: Throwable) { Log.w(TAG, "ending $id", t) } finally { pending.finish() }
            }
        }
    }
}
