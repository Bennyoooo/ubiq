package com.agentos.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.agentos.agent.AgentRuntime
import com.agentos.agent.AgentTools
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File

/**
 * adb shell am broadcast -a com.agentos.DEBUG_TOOL --es name tap --es input '{"id":3}' ai.ubiq.app
 * Result goes to logcat (tag AgentOS/Debug) and to files/debug_last.txt; screenshots to files/debug_last.png.
 */
private val CoreScope = CoroutineScope(Dispatchers.IO)

class ToolDebugReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == "com.agentos.DEBUG_A11Y_BUTTON") {
            // Same path as the nav-handle hold: the sheet over the current app, with its screen.
            android.os.Handler(android.os.Looper.getMainLooper()).post { com.agentos.control.ControlService.instance?.openSheetWithScreen() }
            return
        }
        if (intent.action == "com.agentos.DEBUG_SCREEN") {
            // adb shell "am broadcast -a com.agentos.DEBUG_SCREEN --es op start|say|pickup|decline|reply|end|fake [--es text '...'] [--es number +15551234567] ai.ubiq.app"
            // start: screen a pretend call with no call audio (the caller's words come from say). fake --ez on true:
            // a real incoming call (emulator: adb emu gsm call <n>) is screened the same way, without Core's audio.
            val p = com.agentos.phone.CallScreener
            when (intent.getStringExtra("op") ?: "start") {
                "start" -> { p.fakeAudio = true; p.screen(context, intent.getStringExtra("number") ?: "+15550100", false) }
                "fake" -> p.fakeAudio = intent.getBooleanExtra("on", true)
                "say" -> intent.getStringExtra("text")?.let { p.callerSays(it) }
                "pickup" -> p.pickUp()
                "decline" -> p.decline()
                "reply" -> intent.getStringExtra("text")?.let { p.ownerSays(it) }
                "end" -> p.hangUp()
                "show" -> context.startActivity(android.content.Intent(context, com.agentos.phone.CallScreenActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            val l = p.live.value
            Log.i("AgentOS/Debug", "screen: ${l?.let { "${it.who} | ${it.reason} | asking=${it.askingOwner} | ${it.phase} | " + it.lines.joinToString(" | ") { x -> x.who.name + ": " + x.text } } ?: "no call; last: " + p.transcriptForDebug.replace('\n', '|')}")
            return
        }
        if (intent.action == "com.agentos.DEBUG_LIVE") {
            // adb shell "am broadcast -a com.agentos.DEBUG_LIVE --es op start|say|end [--es text 'words'] com.agentos"
            // say: the words reach the live call as if spoken (no mic needed).
            when (intent.getStringExtra("op") ?: "start") {
                "start" -> com.agentos.voice.LiveCallActivity.open(context)
                "say" -> intent.getStringExtra("text")?.let { com.agentos.voice.LiveCall.sayAsUser(it) }
                "mute" -> com.agentos.voice.LiveCall.toggleMute()
                "end" -> com.agentos.voice.LiveCall.hangUp()
            }
            Log.i("AgentOS/Debug", "live: phase=${com.agentos.voice.LiveCall.phase.value} lines=${com.agentos.voice.LiveCall.lines.value.joinToString(" | ") { it.who.name + ": " + it.text }}")
            return
        }
        if (intent.action == "com.agentos.DEBUG_SAY") {
            // adb shell am broadcast -a com.agentos.DEBUG_SAY --es text 'open settings' ai.ubiq.app
            // --ez new true: a fresh session (to run several at once); otherwise the focused one.
            intent.getStringExtra("text")?.let { t -> if (intent.getBooleanExtra("new", false)) AgentRuntime.create().send(t) else AgentRuntime.send(t) }
            return
        }
        if (intent.action == "com.agentos.DEBUG_ASK") {
            // adb shell am broadcast -a com.agentos.DEBUG_ASK --es text 'Which table?' --es options '7:00|7:30|8:00' ai.ubiq.app
            // The focused conversation asks, exactly as ask_user does (keys in the chat, notification buttons, island light).
            val q = intent.getStringExtra("text") ?: "Which works for you?"
            val opts = intent.getStringExtra("options")?.split('|')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
            val s = AgentRuntime.focused.value
            CoroutineScope(Dispatchers.Main).launch { Log.i("AgentOS/Debug", "answer: " + s.askFromHarness(q, opts)); s.cancel() }
            return
        }
        if (intent.action == "com.agentos.DEBUG_ATTACH") {
            // adb shell am broadcast -a com.agentos.DEBUG_ATTACH --es path files/x.jpg --es text 'what is this' ai.ubiq.app
            val app = context.applicationContext
            val f = File(app.filesDir.parentFile, intent.getStringExtra("path") ?: return)
            val text = intent.getStringExtra("text") ?: ""
            CoroutineScope(Dispatchers.IO).launch {
                try { AgentRuntime.send(text, listOf(com.agentos.agent.Attachment.load(app, android.net.Uri.fromFile(f)))) }
                catch (t: Throwable) { Log.w("AgentOS/Debug", "attach failed", t); AgentRuntime.systemNote("attach failed: $t") }
            }
            return
        }
        if (intent.action == "com.agentos.DEBUG_LOCAL") {
            // adb shell am broadcast -a com.agentos.DEBUG_LOCAL --es action status|download|download_vision|ask --es text '...' ai.ubiq.app -> files/debug_local.txt
            val app = context.applicationContext
            val action = intent.getStringExtra("action") ?: "status"
            CoroutineScope(Dispatchers.IO).launch {
                val out = try { com.agentos.agent.OnDeviceModel.installed?.debug(app, action, intent.getStringExtra("text")) ?: "this build has no on-device model" }
                catch (t: Throwable) { Log.w("AgentOS/Debug", "local", t); "EXC: $t" }
                File(app.filesDir, "debug_local.txt").writeText(out)
            }
            return
        }
        if (intent.action == "com.agentos.DEBUG_MINIAPP") {
            // Builds with mini apps: see their plug-in for the extras. Writes files/debug_miniapp.txt.
            com.agentos.agent.MiniAppPlugin.installed?.debug(context.applicationContext, intent.extras ?: android.os.Bundle())
                ?: Log.i("AgentOS/Debug", "this build has no mini apps")
            return
        }
        if (intent.action == "com.agentos.DEBUG_SPEAK") {
            // adb shell am broadcast -a com.agentos.DEBUG_SPEAK --es text 'Hello' [--es voice cedar] ai.ubiq.app
            com.agentos.voice.Speaker.speak(intent.getStringExtra("text") ?: "Hi, I'm Ubiq.", intent.getStringExtra("voice"))
            return
        }
        if (intent.action == "com.agentos.DEBUG_STOP") {
            // adb shell am broadcast -a com.agentos.DEBUG_STOP ai.ubiq.app  (the Stop button on the focused session)
            AgentRuntime.focused.value.cancel()
            return
        }
        if (intent.action == "com.agentos.DEBUG_PREF") {
            // adb shell am broadcast -a com.agentos.DEBUG_PREF --es key effort|tier|navigator|model|engine|harness_url|screen_calls|screen_rules|owner_name --es value low ai.ubiq.app
            val c = context.applicationContext
            val v = intent.getStringExtra("value") ?: ""
            when (intent.getStringExtra("key")) {
                "effort" -> com.agentos.data.Prefs.setEffort(c, v)
                "tier" -> com.agentos.data.Prefs.setServiceTier(c, v)
                "navigator" -> com.agentos.data.Prefs.setNavigator(c, v == "true")
                "model" -> com.agentos.data.Prefs.setModel(c, v)
                "engine" -> { com.agentos.data.Prefs.setEngine(c, v); com.agentos.data.Prefs.harnessToken(c) } // makes files/harness_token
                "harness_url" -> com.agentos.data.Prefs.setHarnessUrl(c, v)
                "screen_calls" -> com.agentos.data.Prefs.setScreenCalls(c, v == "true")
                "screen_rules" -> com.agentos.data.Prefs.setScreenRules(c, v)
                "owner_name" -> com.agentos.data.Prefs.setOwnerName(c, v)
            }
            Log.i("AgentOS/Debug", "pref ${intent.getStringExtra("key")}=$v")
            return
        }
        if (intent.action == "com.agentos.DEBUG_DUMP") {
            // adb shell am broadcast -a com.agentos.DEBUG_DUMP ai.ubiq.app  -> files/debug_chat.txt
            val sb = StringBuilder("status=${AgentRuntime.status.value}\n")
            sb.append("stats=").append(AgentRuntime.focused.value.lastStats).append('\n')
            AgentRuntime.items.value.forEach { sb.append(it.role.name).append(": ").append(it.text).append('\n') }
            sb.append("\n=== sessions\n")
            AgentRuntime.sessions.value.take(8).forEach { s ->
                sb.append("[").append(s.id).append("] ").append(s.title.value).append(" | ").append(s.status.value)
                    .append(" | desk=").append(s.desk?.let { "${it.slot}/${it.displayId}${if (it.owner == s.id) " held" else ""}" } ?: "-").append('\n')
            }
            sb.append("leases=").append(com.agentos.agent.AppLeases.holders.value).append('\n')
            File(context.applicationContext.filesDir, "debug_chat.txt").writeText(sb.toString())
            return
        }
        val name = intent.getStringExtra("name") ?: return
        if (name == "workspace_clear") { CoroutineScope(Dispatchers.IO).launch { Log.i("AgentOS/Debug", "cleared ${com.agentos.workspace.Workspace.desks.sumOf { it.clear() }} tasks") }; return }
        val input = try { JSONObject(intent.getStringExtra("input") ?: "{}") } catch (t: Throwable) {
            File(context.applicationContext.filesDir, "debug_last.txt").writeText("EXC: bad input json: $t"); return
        }
        if (name == "core") {
            // --es name core --es input '{"tool":"policy.get","args":{},"origin":"agent","opId":"x"}': one Ubiq Core call.
            val app = context.applicationContext
            CoreScope.launch {
                val r = try {
                    com.agentos.core.CoreClient.call(input.optString("tool"), input.optJSONObject("args") ?: JSONObject(), input.optString("origin", "agent"),
                        input.optString("task", "debug"), input.optString("opId").ifBlank { null })?.toString(2) ?: "NO CORE (not installed, no CONTROL permission, or bind failed)"
                } catch (t: Throwable) { "EXC: $t" }
                File(app.filesDir, "debug_last.txt").writeText(r)
                Log.i("AgentOS/Debug", r)
            }
            return
        }
        // --ez workspace true runs the tool on the hidden workspace display (needs Shizuku).
        // Debug tool calls run in their own (never saved, since it stays empty) session so they get a desk like real tasks.
        val dbg = AgentRuntime.sessions.value.firstOrNull { it.origin == "debug" } ?: AgentRuntime.create("Debug tools", focus = false).also { it.origin = "debug" }
        val target = com.agentos.agent.TaskTarget(intent.getBooleanExtra("workspace", false), dbg).also { it.guarded = intent.getBooleanExtra("guarded", false) }
        val app = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            val text = try {
                val r = try { AgentTools.execute(app, name, input, AgentRuntime.apps, target) } finally { target.close() }
                r.imagePng?.let { File(app.filesDir, "debug_last.png").writeBytes(it) }
                (if (r.isError) "ERROR: " else "") + r.text
            } catch (t: Throwable) { "EXC: $t" }
            File(app.filesDir, "debug_last.txt").writeText(text)
            Log.i("AgentOS/Debug", text)
        }
    }
}
