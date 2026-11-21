package com.agentos.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.agentos.agent.AgentRuntime
import com.agentos.control.ControlService
import com.agentos.data.Prefs
import com.agentos.memory.Lifelog
import com.agentos.memory.Places
import com.agentos.notify.NotificationWatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Settings > Personal context (docs/MEMORY.md): every boundary of the owner's timeline in one place, each one shown
 * as it is on this phone and, where the owner may change it, changeable: what is recorded, what never is, which apps
 * stay out, how long it is kept, who sees it, and what is in it now.
 */
@Composable
internal fun PersonalContextSection() {
    val ctx = LocalContext.current
    var on by remember { mutableStateOf(Prefs.personalContext(ctx)) }
    // Bumped by every change below, so stats, the list of moments and permission lines read again.
    var tick by remember { mutableIntStateOf(0) }
    val changed: () -> Unit = { tick++ }
    // Coming back from Android's settings (a permission granted) reads everything again.
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) { tick++; onPauseOrDispose {} }

    Text("Ubiq can keep a private timeline on this phone of the screens you look at (as text), your notifications and the places you stay, " +
        "so it remembers things for you. It is off until you turn it on, and every boundary is below.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(8.dp))
    SwitchRow("Record personal context", null, on) { on = it; Prefs.setPersonalContext(ctx, it); changed() }

    Group("What it records")
    SourceRow(ctx, "screens", "Screens", "The text of the app in front once the screen settles; only lines it hasn't seen before.", on, tick, changed) {
        if (ControlService.isRunning) null else "Needs Ubiq's phone control (Accessibility) turned on" to Settings.ACTION_ACCESSIBILITY_SETTINGS
    }
    SourceRow(ctx, "notifications", "Notifications", "Each notification's app, title and text.", on, tick, changed) {
        if (NotificationWatcher.hasAccess(ctx)) null else "Needs notification access" to Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS
    }
    SourceRow(ctx, "places", "Places", "Where you stay 10 minutes or more, named by its address. Location is checked every 10 minutes at low power.", on, tick, changed) {
        when {
            !Places.allowed(ctx) -> "Needs location" to null
            ctx.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) != PackageManager.PERMISSION_GRANTED ->
                "Location is allowed only while Ubiq is open: choose \"Allow all the time\"" to null
            else -> null
        }
    }

    Group("Never recorded")
    Hint("Password fields and what you type, codes and one-time passwords, any keyboard, Ubiq itself (your conversations are kept already), " +
        "the status bar, notification shade and lock screen, and anything while recording is off. These can't be changed.")

    Group("Apps kept out")
    ExcludedApps(ctx, changed)

    Group("Where it goes")
    Boundary("Stored", "On this phone only, in Ubiq's private storage. Not in phone backups.")
    Boundary("Kept for", null)
    var days by remember { mutableIntStateOf(Lifelog.days(ctx)) }
    Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Lifelog.DAY_CHOICES.forEach { d ->
            BrandChip(days == d, "$d") {
                days = d; Prefs.setTimelineDays(ctx, d)
                AgentRuntime.scope.launch { Lifelog.purge(d); changed() }
            }
            Spacer(Modifier.width(8.dp))
        }
        Text("days", style = MaterialTheme.typography.bodyMedium)
    }
    Hint("Older moments are deleted every day. Choosing fewer days deletes the older ones now.")
    var searchable by remember { mutableStateOf(Prefs.timelineSearchable(ctx)) }
    Spacer(Modifier.height(6.dp))
    SwitchRow("Ubiq can search it", if (searchable)
        "When a request needs your past, Ubiq searches the timeline and the cloud model sees only what it finds, plus the place you're at. Never the whole timeline."
        else "Ubiq searches only your conversations, and the cloud model sees nothing from the timeline.", searchable) {
        searchable = it; Prefs.setTimelineSearchable(ctx, it)
    }
    Boundary("Summaries", if (com.agentos.agent.OnDeviceModel.installed != null)
        "None yet. They will be made on this phone by the on-device model while it charges, never in the cloud."
        else "None. This build has no on-device model, and the timeline is never sent anywhere to be summarized.")
    Boundary("Using it", "Before Ubiq types, sends or saves a number, address, email or link it found here, it asks you. This can't be turned off.")
    Boundary("This phone", if (com.agentos.core.CoreClient.available) "The Ubiq ROM: location is granted by the system and Ubiq may run in the background."
        else "The Ubiq app: you grant each permission above, and Android may delay place checks to save battery, so some stays can be missed.")

    Group("What's in it")
    Moments(ctx, tick, changed)
}

@Composable
private fun Group(title: String) {
    Spacer(Modifier.height(16.dp))
    Text(title, Modifier.padding(bottom = 4.dp), style = MaterialTheme.typography.titleSmall)
}

@Composable
private fun Boundary(label: String, value: String?) {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
        value?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
private fun SwitchRow(title: String, detail: String?, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
            detail?.let { Hint(it) }
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked, onChange, enabled = enabled, colors = brandSwitch())
    }
}

/** One source with its switch and, when Android hasn't given Ubiq what it needs for it, what is missing and a way to grant it. */
@Composable
private fun SourceRow(ctx: Context, source: String, title: String, detail: String, master: Boolean, tick: Int, changed: () -> Unit,
                      missing: () -> Pair<String, String?>?) {
    var checked by remember { mutableStateOf(Prefs.recordSource(ctx, source)) }
    SwitchRow(title, detail, checked && master, enabled = master) { checked = it; Prefs.setRecordSource(ctx, source, it); changed() }
    val need = remember(tick, master, checked) { if (master && checked) missing() else null }
    need?.let { (why, action) ->
            StatusLine(why, false)
            OutlinePillButton("Allow", {
            ctx.startActivity(if (action != null) Intent(action) else Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}")))
        })
    }
    Spacer(Modifier.height(6.dp))
}

/** The owner's own list: the defaults (password managers, authenticators) until they change it; adding an app deletes what was kept from it. */
@Composable
private fun ExcludedApps(ctx: Context, changed: () -> Unit) {
    val pm = ctx.packageManager
    fun label(pkg: String) = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrNull()
    var excluded by remember { mutableStateOf(Lifelog.excludedApps(ctx)) }
    // Only installed apps are listed; the defaults for apps that aren't installed stay in force quietly.
    val shown = excluded.mapNotNull { p -> label(p)?.let { p to it } }.sortedBy { it.second.lowercase() }
    fun save(next: Set<String>) { excluded = next; Prefs.setTimelineExcluded(ctx, next); changed() }
    if (shown.isEmpty()) Hint("None. Password managers and authenticators are kept out when installed.")
    shown.forEach { (pkg, name) ->
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(name, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            TextButton({ save(excluded - pkg) }) { Text("Record it") }
        }
    }
    var adding by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    val apps by produceState(emptyList<Pair<String, String>>(), adding) {
        if (adding) value = withContext(Dispatchers.IO) {
            pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
                .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }.distinctBy { it.first }
                .filter { (p, _) -> p != ctx.packageName }.sortedBy { it.second.lowercase() }
        }
    }
    if (!adding) OutlinePillButton("Keep an app out", { adding = true })
    else {
        OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(top = 6.dp), placeholder = { Text("Search apps") }, singleLine = true,
            shape = RoundedCornerShape(20.dp), colors = settingsFieldColors())
        apps.filter { (p, l) -> p !in excluded && (query.isBlank() || l.contains(query, true)) }.take(8).forEach { (pkg, name) ->
            Text(name, Modifier.fillMaxWidth().clickable {
                save(excluded + pkg); adding = false; query = ""
                AgentRuntime.scope.launch { Lifelog.deleteApp(pkg); changed() }
            }.padding(vertical = 10.dp), style = MaterialTheme.typography.bodyMedium)
        }
        TextButton({ adding = false; query = "" }) { Text("Done") }
    }
    Hint("Keeping an app out also deletes what was recorded from it.")
}

/** How much is kept, the latest moments as they are stored, each one deletable, and Delete all. */
@Composable
private fun Moments(ctx: Context, tick: Int, changed: () -> Unit) {
    val stats by produceState(0L to 0L, tick) { value = withContext(Dispatchers.IO) { Lifelog.stats() } }
    Text("${stats.first} moments, ${stats.second / 1024} KB of text", style = MaterialTheme.typography.bodyMedium)
    var open by remember { mutableStateOf(false) }
    val recent by produceState(emptyList<Lifelog.Event>(), tick, open) {
        value = if (open) withContext(Dispatchers.IO) { Lifelog.search(null, limit = 30) } else emptyList()
    }
    if (stats.first > 0) OutlinePillButton(if (open) "Hide" else "Show the latest", { open = !open })
    if (open) {
        val pm = ctx.packageManager
        val fmt = remember { DateTimeFormatter.ofPattern("EEE HH:mm") }
        recent.forEach { e ->
            val source = when (e.kind) {
                Lifelog.Kind.PLACE -> "Place"
                Lifelog.Kind.EPISODE -> "Summary"
                else -> runCatching { pm.getApplicationLabel(pm.getApplicationInfo(e.app, 0)).toString() }.getOrDefault(e.app)
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text(Instant.ofEpochMilli(e.time).atZone(ZoneId.systemDefault()).format(fmt) + ", " + source,
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(e.text.lines().take(3).joinToString(" | ").take(240), style = MaterialTheme.typography.bodySmall)
                }
                TextButton({ AgentRuntime.scope.launch { Lifelog.delete(e.id); changed() } }) { Text("Delete") }
            }
        }
    }
    Spacer(Modifier.height(8.dp))
    var confirm by remember { mutableStateOf(false) }
    SoftPillButton(if (confirm) "Tap again to delete everything" else "Delete all", {
        if (!confirm) confirm = true
        else { confirm = false; AgentRuntime.scope.launch { Lifelog.clear(); changed() } }
    }, Modifier.fillMaxWidth())
}
