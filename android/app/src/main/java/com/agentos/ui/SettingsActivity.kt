package com.agentos.ui

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.delay
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.agentos.control.ControlService
import com.agentos.data.Prefs
import com.agentos.workspace.Workspace
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.TextButton
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.net.Uri
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SwitchDefaults
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color

class SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { AgentOsTheme { Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { SettingsScreen() } } }
    }
}

private val MODELS = listOf("gpt-6-astra", "gpt-6.1-sol", "gpt-6-luna")
private val EFFORTS = listOf("low", "medium", "high")

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun SettingsScreen() {
    val ctx = LocalContext.current
    var key by remember { mutableStateOf(Prefs.apiKey(ctx)) }
    var model by remember { mutableStateOf(Prefs.model(ctx)) }
    var effort by remember { mutableStateOf(Prefs.effort(ctx)) }
    var speak by remember { mutableStateOf(Prefs.speakReplies(ctx)) }
    var shots by remember { mutableStateOf(Prefs.useScreenshots(ctx)) }
    var steps by remember { mutableStateOf(Prefs.maxSteps(ctx).toString()) }
    var background by remember { mutableStateOf(Prefs.backgroundMode(ctx)) }
    val shizuku by Workspace.availability.collectAsStateWithLifecycle()

    // Insets before the scroll, so the page scrolls below the status bar instead of under it.
    val onRom = com.agentos.core.CoreClient.available
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(Modifier.padding(horizontal = 4.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text("settings", style = MaterialTheme.typography.headlineMedium)
                Text("Tune how Ubiq thinks, talks and works.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        Section("Look") {
            // The system's own Dark theme switch (Settings > Display > Dark theme); every app, Ubiq included, follows it.
            val ui = ctx.getSystemService(android.app.UiModeManager::class.java)
            var dark by remember { mutableStateOf(ui.nightMode == android.app.UiModeManager.MODE_NIGHT_YES) }
            FieldLabel("Theme")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp)) {
                listOf(false to "light", true to "dark").forEach { (d, l) ->
                    BrandChip(dark == d, l) {
                        // MODIFY_DAY_NIGHT_MODE is privileged (granted on the ROM). Without it Android ignores the call (it only
                        // logs "Night mode locked"), so check the result and open the system page instead.
                        val want = if (d) android.app.UiModeManager.MODE_NIGHT_YES else android.app.UiModeManager.MODE_NIGHT_NO
                        runCatching { ui.setNightMode(want) }
                        if (ui.nightMode == want) dark = d
                        else ctx.startActivity(android.content.Intent("android.settings.DARK_THEME_SETTINGS").addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                }
            }
            Hint("For the whole phone, not just Ubiq. Also in Settings, Display, Dark theme.")
        }

        Section("Brain") {
            FieldLabel("OpenAI API key")
            OutlinedTextField(key, { key = it; Prefs.setApiKey(ctx, it) }, Modifier.fillMaxWidth(), placeholder = { Text("sk-…") },
                visualTransformation = PasswordVisualTransformation(), singleLine = true, shape = CircleShape, colors = settingsFieldColors())
            Hint("Stored only on this phone, in Ubiq's private storage.")
            Spacer(Modifier.height(14.dp))
            FieldLabel("Engine")
            var engine by remember { mutableStateOf(Prefs.engine(ctx)) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp)) {
                listOf("pi" to "Pi", "builtin" to "Built-in").forEach { (v, l) -> BrandChip(engine == v, l) { engine = v; Prefs.setEngine(ctx, v); if (v != "pi") com.agentos.agent.PiRuntime.stop("engine switched") } }
            }
            if (engine == "pi") {
                var url by remember { mutableStateOf(Prefs.harnessUrl(ctx)) }
                var advanced by remember { mutableStateOf(url.isNotBlank()) }
                val reachable by produceState<Boolean?>(initialValue = null, url) {
                    while (true) { value = com.agentos.agent.Harness.healthy(ctx); delay(3000) }
                }
                Hint(when {
                    url.isNotBlank() -> when (reachable) {
                        true -> "Connected to the harness at $url."
                        false -> "Not reachable. On a Mac, run scripts/pi-dev.sh with the phone plugged in."
                        null -> "Checking…"
                    }
                    !com.agentos.agent.PiRuntime.bundled(ctx) -> "This build doesn't include Pi. Build it with scripts/build-node-runtime.sh."
                    reachable == true -> "Pi runs on this phone and is ready. The phone keeps the tools, receipts and approvals."
                    else -> "Pi runs on this phone. It starts with your next message and sleeps when idle to save battery."
                })
                Text(if (advanced) "Hide developer options" else "Developer options", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary, modifier = Modifier.clickable { advanced = !advanced }.padding(vertical = 6.dp))
                if (advanced) {
                    OutlinedTextField(url, { url = it.trim(); Prefs.setHarnessUrl(ctx, it) }, Modifier.fillMaxWidth(), label = { Text("Harness on another computer") },
                        placeholder = { Text("Blank: this phone") }, singleLine = true, shape = RoundedCornerShape(20.dp), colors = settingsFieldColors())
                }
            }
            Spacer(Modifier.height(14.dp))
            FieldLabel("Model")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp)) {
                MODELS.forEach { m -> BrandChip(model == m, m.removePrefix("gpt-")) { model = m; Prefs.setModel(ctx, m) } }
            }
            Spacer(Modifier.height(10.dp))
            FieldLabel("Effort (speed vs. care)")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp)) {
                EFFORTS.forEach { e -> BrandChip(effort == e, e) { effort = e; Prefs.setEffort(ctx, e) } }
            }
            Spacer(Modifier.height(10.dp))
            FieldLabel("Speed tier")
            var tier by remember { mutableStateOf(Prefs.serviceTier(ctx)) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp)) {
                listOf("" to "default", "fast" to "fast", "flex" to "flex").forEach { (v, l) -> BrandChip(tier == v, l) { tier = v; Prefs.setServiceTier(ctx, v) } }
            }
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(steps, { steps = it; it.toIntOrNull()?.let { n -> Prefs.setMaxSteps(ctx, n.coerceIn(5, 200)) } }, Modifier.fillMaxWidth(),
                label = { Text("Max steps per task") }, singleLine = true, shape = RoundedCornerShape(20.dp), colors = settingsFieldColors())
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Speak replies aloud", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge); Switch(speak, { speak = it; Prefs.setSpeakReplies(ctx, it) }, colors = brandSwitch())
            }
            if (speak) {
                FieldLabel("Voice (tap to hear it)")
                var voice by remember { mutableStateOf(Prefs.voice(ctx)) }
                androidx.compose.foundation.layout.FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp)) {
                    com.agentos.voice.CloudVoice.VOICES.forEach { v ->
                        BrandChip(voice == v, v) { voice = v; Prefs.setVoice(ctx, v); com.agentos.voice.Speaker.speak("Hi, I'm Ubiq. What can I do for you?", v) }
                    }
                }
                Spacer(Modifier.height(6.dp))
            }
            HeyUbiqRows()
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Let the agent look at screenshots", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge); Switch(shots, { shots = it; Prefs.setUseScreenshots(ctx, it) }, colors = brandSwitch())
            }
            val conditions by produceState(initialValue = "") {
                while (true) { value = try { com.agentos.agent.DeviceConditions.summary(ctx) } catch (_: Throwable) { "" }; delay(5000) }
            }
            if (conditions.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                FieldLabel("Phone right now")
                Hint("$conditions. When the battery is low, digests wait; when memory is short, tasks take turns" + (if (com.agentos.agent.OnDeviceModel.installed != null) "; when the phone is hot, the on-device model rests." else "."))
            }
            Spacer(Modifier.height(6.dp))
            var nav by remember { mutableStateOf(Prefs.navigator(ctx)) }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Quick navigator (a small model taps through apps)", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge); Switch(nav, { nav = it; Prefs.setNavigator(ctx, it) }, colors = brandSwitch())
            }
            Spacer(Modifier.height(6.dp))
            var express by remember { mutableStateOf(Prefs.expressLane(ctx)) }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Express lane (weather, directions and music answered at once)", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge); Switch(express, { express = it; Prefs.setExpressLane(ctx, it) }, colors = brandSwitch())
            }
            Spacer(Modifier.height(6.dp))
            var complete by remember { mutableStateOf(Prefs.autocomplete(ctx)) }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Reply suggestions over the keyboard (tap to fill the box; never sends)", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                Switch(complete, { complete = it; Prefs.setAutocomplete(ctx, it); if (!it) com.agentos.control.Autocomplete.hide() }, colors = brandSwitch())
            }
            if (com.agentos.agent.Playbooks.available) {
                Spacer(Modifier.height(6.dp))
                var demo by remember { mutableStateOf(Prefs.demoPlaybooks(ctx)) }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Demo playbooks (matching requests play a script)", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge); Switch(demo, { demo = it; Prefs.setDemoPlaybooks(ctx, it) }, colors = brandSwitch())
                }
            }
        }

        // On the ROM, Core sets all of this up at first boot; the section only appears if something was undone.
        val control by produceState(initialValue = ControlService.isRunning) {
            while (true) { delay(1000); value = ControlService.isRunning }
        }
        val roles = ctx.getSystemService(android.app.role.RoleManager::class.java)
        val home = roles.isRoleHeld(android.app.role.RoleManager.ROLE_HOME)
        val assistant = roles.isRoleHeld(android.app.role.RoleManager.ROLE_ASSISTANT)
        if (!onRom || !(control && home && assistant)) Section("Phone integration") {
            StatusLine(if (control) "Agent control is on" else "Agent control is off: the assistant cannot operate apps yet", control)
            StatusLine(if (home) "Ubiq is the home screen" else "Ubiq is not the home screen", home)
            StatusLine(if (assistant) "Ubiq is the assistant (hold the power button)" else "Ubiq is not the assistant", assistant)
            if (!control) { Spacer(Modifier.height(8.dp)); PillButton("Accessibility settings (enable Ubiq Agent Control)", { ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }, Modifier.fillMaxWidth()) }
            if (!home) { Spacer(Modifier.height(8.dp)); SoftPillButton("Set Ubiq as default home", { ctx.startActivity(Intent(Settings.ACTION_HOME_SETTINGS)) }, Modifier.fillMaxWidth()) }
            if (!assistant) { Spacer(Modifier.height(8.dp)); SoftPillButton("Set Ubiq as the assistant", { ctx.startActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)) }, Modifier.fillMaxWidth()) }
        }

        Section("Background workspace") {
            Hint("With this on, the agent works on a hidden second screen while you keep using the phone. You can watch it any time with the Watch button." +
                if (onRom) "" else " On stock Android this needs Shizuku (a free helper that runs with ADB rights).")
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Run tasks in the background", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge); Switch(background, { background = it; Prefs.setBackgroundMode(ctx, it) }, colors = brandSwitch())
            }
            LaunchedEffect(Unit) { while (true) { Workspace.refreshAvailability(); delay(1500) } }
            // On the ROM Core always provides the workspace: the switch is all there is, unless something broke.
            val ready = shizuku is Workspace.Availability.Ready
            if (!(onRom && ready)) when (shizuku) {
                is Workspace.Availability.Ready -> StatusLine(if ((shizuku as Workspace.Availability.Ready).viaCore) "Ubiq Core: background mode available" else "Shizuku connected: background mode available", true)
                else -> StatusLine("${shizuku.summary} (tasks run on the visible screen until then)", false)
            }
            Workspace.sleepsWithPhone?.let { StatusLine("Background tasks pause while the screen is off: $it", false) }
            if (!onRom || !ready) Spacer(Modifier.height(8.dp))
            if (!onRom || !ready) when (shizuku) {
                is Workspace.Availability.NotInstalled -> PillButton("Install Shizuku from Google Play", {
                    ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=moe.shizuku.privileged.api")))
                }, Modifier.fillMaxWidth())
                is Workspace.Availability.NotRunning -> PillButton("Open Shizuku to start it", {
                    ctx.packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")?.let { ctx.startActivity(it) }
                }, Modifier.fillMaxWidth())
                is Workspace.Availability.NeedsPermission -> PillButton("Grant Ubiq access to Shizuku", { Workspace.requestPermission() }, Modifier.fillMaxWidth())
                is Workspace.Availability.Ready -> SoftPillButton("Close the workspace (quits apps the agent opened)", { Workspace.releaseAll() }, Modifier.fillMaxWidth())
            }
        }

        // The owner's rules: in Ubiq Core on the ROM (only Core's own screen can loosen them), in the app otherwise.
        Section("Agent rules") {
            Hint("The stop switch and what the agent may do in each app (full, ask, read only, off). The agent itself can only make these stricter.")
            Spacer(Modifier.height(8.dp))
            SoftPillButton("Open agent rules", { com.agentos.rules.PolicyGate.current().openRules(ctx) }, Modifier.fillMaxWidth())
        }

        Section("Connectors") { ConnectorsSection() }

        Section("Notifications") { NotificationSection() }

        Section("Calls") { CallScreeningSection() }

        Section("Personal context") { PersonalContextSection() }

        com.agentos.agent.OnDeviceModel.installed?.let { m -> Section("On-device model") { m.Settings() } }
        Spacer(Modifier.height(16.dp))
        Text("Ubiq ${com.agentos.BuildConfig.VERSION_NAME} • requests go directly from this phone to api.openai.com" + (if (com.agentos.agent.OnDeviceModel.installed != null) "; on-device requests never leave it" else ""),
            Modifier.padding(horizontal = 8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** "Hey Ubiq" (voice/WakeWord): the switch, then with it on, screen-off listening and how keen it is. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun HeyUbiqRows() {
    val ctx = LocalContext.current
    var on by remember { mutableStateOf(Prefs.wakeWord(ctx)) }
    var screenOff by remember { mutableStateOf(Prefs.wakeScreenOff(ctx)) }
    var sensitivity by remember { mutableStateOf(Prefs.wakeSensitivity(ctx)) }
    val state by com.agentos.voice.WakeWord.state.collectAsStateWithLifecycle()
    val mic = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { ok ->
        on = ok; Prefs.setWakeWord(ctx, ok); com.agentos.voice.WakeWord.sync(ctx)
    }
    fun set(v: Boolean) {
        if (v && ctx.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) { mic.launch(android.Manifest.permission.RECORD_AUDIO); return }
        on = v; Prefs.setWakeWord(ctx, v); com.agentos.voice.WakeWord.sync(ctx)
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Wake with \"Hey Ubiq\"", style = MaterialTheme.typography.bodyLarge)
            Text(when {
                !on -> "Say it to open Ubiq, hands free. Heard on the phone only."
                state == com.agentos.voice.WakeWord.State.Listening -> "Listening, on the phone only"
                state == com.agentos.voice.WakeWord.State.Yielding -> "Paused while something else uses the mic"
                state == com.agentos.voice.WakeWord.State.Paused -> "Paused while the screen is off"
                state == com.agentos.voice.WakeWord.State.Error -> "Can't listen right now"
                else -> "Starting…"
            }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(on, { set(it) }, colors = brandSwitch())
    }
    if (on) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Also with the screen off", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            Switch(screenOff, { screenOff = it; Prefs.setWakeScreenOff(ctx, it); com.agentos.voice.WakeWord.sync(ctx) }, colors = brandSwitch())
        }
        FieldLabel("How easily it wakes")
        androidx.compose.foundation.layout.FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp)) {
            listOf(0 to "strict", 1 to "normal", 2 to "eager").forEach { (v, l) -> BrandChip(sensitivity == v, l) { sensitivity = v; Prefs.setWakeSensitivity(ctx, v) } }
        }
        Spacer(Modifier.height(6.dp))
    }
}

/**
 * Services the agent uses directly instead of operating their apps: the phone's calendars and
 * contacts, Gmail, and the user's own MCP servers.
 */
@Composable
private fun ConnectorsSection() {
    val ctx = LocalContext.current
    var tick by remember { mutableStateOf(0) }
    val calendar = remember(tick) { com.agentos.connect.DeviceData.calendarAllowed(ctx) }
    val contacts = remember(tick) { com.agentos.connect.DeviceData.contactsAllowed(ctx) }
    val texts = remember(tick) { com.agentos.notify.Unreplied.smsAllowed(ctx) }
    val google = remember(tick) { com.agentos.connect.GoogleAccount.connected(ctx) }
    var googleError by remember { mutableStateOf<String?>(null) }
    var googleBusy by remember { mutableStateOf(false) }
    val perms = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()) { tick++ }
    val consent = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartIntentSenderForResult()) { r ->
        com.agentos.connect.GoogleAccount.finish(ctx, r.data) { err -> googleError = err; googleBusy = false; tick++ }
    }
    val servers by com.agentos.connect.McpServers.servers.collectAsStateWithLifecycle()

    Text("The assistant uses these directly, which is faster and more reliable than operating apps. It falls back to the apps for anything they don't cover.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(8.dp))
    if (calendar && contacts && texts) {
        val calendarIssue = com.agentos.connect.DeviceData.calendarIssue(ctx)
        StatusLine("Calendar, contacts and texts allowed" + (calendarIssue?.let { ". $it" } ?: ""), calendarIssue == null)
    }
    else PillButton("Allow calendar, contacts and texts", { perms.launch(arrayOf(android.Manifest.permission.READ_CALENDAR, android.Manifest.permission.WRITE_CALENDAR, android.Manifest.permission.READ_CONTACTS, android.Manifest.permission.READ_SMS)) },
        Modifier.fillMaxWidth())
    Spacer(Modifier.height(8.dp))
    if (google) Row(verticalAlignment = Alignment.CenterVertically) {
        StatusLine("Gmail: ${com.agentos.connect.GoogleAccount.email(ctx)}", true, Modifier.weight(1f))
        TextButton(onClick = { com.agentos.connect.GoogleAccount.disconnect(ctx); tick++ }) { Text("Disconnect") }
    } else PillButton(if (googleBusy) "Connecting…" else "Connect Gmail (Google account on this phone)", {
        googleBusy = true; googleError = null
        com.agentos.connect.GoogleAccount.begin(ctx,
            onConsent = { pi -> consent.launch(androidx.activity.result.IntentSenderRequest.Builder(pi.intentSender).build()) },
            onDone = { err -> googleError = err; googleBusy = false; tick++ })
    }, Modifier.fillMaxWidth(), enabled = !googleBusy)
    googleError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }

    Spacer(Modifier.height(12.dp))
    Text("MCP servers", style = MaterialTheme.typography.titleSmall)
    servers.forEach { srv ->
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("${srv.label} · ${srv.url.removePrefix("https://").take(36)}", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { com.agentos.connect.McpServers.remove(ctx, srv.label) }) { Text("Remove") }
        }
    }
    var label by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var mcpError by remember { mutableStateOf<String?>(null) }
    OutlinedTextField(label, { label = it }, Modifier.fillMaxWidth().padding(top = 6.dp), label = { Text("Name (e.g. zapier)") }, singleLine = true, shape = RoundedCornerShape(20.dp), colors = settingsFieldColors())
    OutlinedTextField(url, { url = it }, Modifier.fillMaxWidth().padding(top = 6.dp), label = { Text("Server URL (https://…)") }, singleLine = true, shape = RoundedCornerShape(20.dp), colors = settingsFieldColors())
    OutlinedTextField(token, { token = it }, Modifier.fillMaxWidth().padding(top = 6.dp), label = { Text("Bearer token (optional)") }, singleLine = true,
        visualTransformation = PasswordVisualTransformation(), shape = RoundedCornerShape(20.dp), colors = settingsFieldColors())
    mcpError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
    SoftPillButton("Add MCP server", {
        mcpError = com.agentos.connect.McpServers.add(ctx, label, url, token)
        if (mcpError == null) { label = ""; url = ""; token = "" }
    }, Modifier.fillMaxWidth().padding(top = 10.dp))
}

/** Notification access (for reading notifications, trigger jobs and digests). */
@Composable
private fun NotificationSection() {
    val ctx = LocalContext.current
    val access by produceState(com.agentos.notify.NotificationWatcher.hasAccess(ctx)) {
        while (true) { delay(1500); value = com.agentos.notify.NotificationWatcher.hasAccess(ctx) }
    }
    Text("With access, the assistant can read your notifications, act when certain ones arrive, and send you digests. " +
        "Set these up by asking, e.g. \"when Mom texts, read it to me\" or \"summarize Slack every evening at 6\". They show in Space > Jobs.", style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(8.dp))
    if (access) StatusLine("Notification access allowed", true)
    else PillButton("Allow notification access", { ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }, Modifier.fillMaxWidth())
}

/** Call screening (phone/CallScreening): Ubiq answers callers who aren't in the contacts, under the owner's rules. */
@Composable
private fun CallScreeningSection() {
    val ctx = LocalContext.current
    var on by remember { mutableStateOf(Prefs.screenCalls(ctx)) }
    var name by remember { mutableStateOf(Prefs.ownerName(ctx)) }
    var rules by remember { mutableStateOf(Prefs.screenRules(ctx)) }
    var tick by remember { mutableStateOf(0) }
    val role by produceState(com.agentos.phone.CallScreening.roleHeld(ctx), tick) {
        while (true) { value = com.agentos.phone.CallScreening.roleHeld(ctx); delay(1500) }
    }
    // null: no Ubiq Core on this phone (stock Android), so Ubiq cannot talk on calls.
    val status by produceState<org.json.JSONObject?>(null, tick) { value = com.agentos.phone.CallScreening.status() }
    val askRole = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { tick++ }
    val askPerms = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()) {
        if (!com.agentos.phone.CallScreening.roleHeld(ctx))
            askRole.launch(ctx.getSystemService(android.app.role.RoleManager::class.java).createRequestRoleIntent(android.app.role.RoleManager.ROLE_CALL_SCREENING))
        tick++
    }
    Text("When someone who isn't in your contacts calls, Ubiq answers first. It asks who's calling and what it's about, hangs up on spam, " +
        "and tells you, so you can pick up, decline, or tell them something. If you don't take it, Ubiq takes a message. People in your contacts ring as usual.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(8.dp))
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("Ubiq answers unknown callers", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Switch(on, {
            on = it; Prefs.setScreenCalls(ctx, it)
            if (it) askPerms.launch(arrayOf(android.Manifest.permission.READ_CONTACTS, android.Manifest.permission.READ_CALL_LOG, android.Manifest.permission.POST_NOTIFICATIONS))
        }, colors = brandSwitch())
    }
    if (on) {
        Spacer(Modifier.height(4.dp))
        if (!role) {
            StatusLine("Ubiq isn't your call screening app yet", false)
            Spacer(Modifier.height(6.dp))
            PillButton("Let Ubiq screen calls", {
                askRole.launch(ctx.getSystemService(android.app.role.RoleManager::class.java).createRequestRoleIntent(android.app.role.RoleManager.ROLE_CALL_SCREENING))
            }, Modifier.fillMaxWidth())
        }
        val talk = status?.optBoolean("interceptable") == true
        when {
            !Prefs.isConfigured(ctx) -> StatusLine("Ubiq needs your OpenAI key (Brain, above) to talk to callers. Until then, unknown callers ring silently.", false)
            status == null -> StatusLine("Talking on calls needs the Ubiq ROM. On this phone, unknown callers ring silently and Ubiq tells you who called.", false)
            !talk -> StatusLine("This phone can't hand call audio to Ubiq. Unknown callers ring silently instead.", false)
            role -> StatusLine("Ubiq is answering unknown callers", true)
        }
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(name, { name = it; Prefs.setOwnerName(ctx, it) }, Modifier.fillMaxWidth(), label = { Text("Your name (what Ubiq tells callers)") },
            placeholder = { Text("e.g. Sam") }, singleLine = true, shape = RoundedCornerShape(20.dp), colors = settingsFieldColors())
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(rules, { rules = it; Prefs.setScreenRules(ctx, it) }, Modifier.fillMaxWidth(), label = { Text("Your rules for calls") },
            placeholder = { Text("e.g. Put my kids' school and any doctor's office through right away.\nDeliveries: ask them to leave it at the door, don't bother me.\nHang up on anyone selling solar or insurance.") },
            minLines = 3, maxLines = 8, shape = RoundedCornerShape(20.dp), colors = settingsFieldColors())
        Hint("Ubiq follows these on every call it answers. It never gives out your address, schedule, codes or payment details, whatever the rules say.")
    }
}

/** A titled group of settings on a pebble card. */
@Composable
internal fun Section(title: String, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Spacer(Modifier.height(14.dp))
    Text(title, Modifier.padding(start = 8.dp, bottom = 8.dp), style = MaterialTheme.typography.titleLarge)
    Pebble(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surface, padding = androidx.compose.foundation.layout.PaddingValues(18.dp), content = content)
}

@Composable
private fun FieldLabel(text: String) = Text(text, Modifier.padding(bottom = 6.dp), style = MaterialTheme.typography.titleSmall)

@Composable
internal fun Hint(text: String) = Text(text, Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
internal fun BrandChip(selected: Boolean, label: String, onClick: () -> Unit) {
    FilterChip(selected = selected, onClick = onClick, label = { Text(label) }, shape = CircleShape,
        colors = FilterChipDefaults.filterChipColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer, selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer),
        border = FilterChipDefaults.filterChipBorder(true, selected, borderColor = Color.Transparent, selectedBorderColor = Color.Transparent))
}

@Composable
internal fun brandSwitch() = SwitchDefaults.colors(checkedTrackColor = MaterialTheme.colorScheme.primaryContainer, checkedThumbColor = Color.White,
    checkedIconColor = MaterialTheme.colorScheme.onPrimaryContainer)

@Composable
internal fun settingsFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest, unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
    unfocusedBorderColor = Color.Transparent, focusedBorderColor = MaterialTheme.colorScheme.primaryContainer,
)

