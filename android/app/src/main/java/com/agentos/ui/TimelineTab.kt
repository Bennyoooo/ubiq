package com.agentos.ui

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.agentos.agent.AgentRuntime
import com.agentos.data.Prefs
import com.agentos.memory.Lifelog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private const val PAGE = 200
private val DAY = DateTimeFormatter.ofPattern("EEE, MMM d")
private val HOUR = DateTimeFormatter.ofPattern("HH:00")
private val MINUTE = DateTimeFormatter.ofPattern("HH:mm")

/**
 * Space > timeline: the owner's own view of what personal context recorded (docs/MEMORY.md), newest first, a day at a
 * time and an hour per row (the hour's summary once episodes exist). An hour opens to its screens, notifications and
 * places; any of them, or the whole hour, can be deleted. The search uses the same index as `recall`, so it finds what
 * Ubiq can find. What is recorded, and for how long, is set in Settings > Personal context.
 */
@Composable
internal fun ColumnScope.TimelineTab() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var recording by remember { mutableStateOf(Prefs.personalContext(ctx)) }
    var query by remember { mutableStateOf("") }
    var events by remember { mutableStateOf<List<Lifelog.Event>?>(null) }
    var more by remember { mutableStateOf(false) }
    var open by remember { mutableStateOf(setOf<String>()) }
    var reload by remember { mutableIntStateOf(0) }
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) { recording = Prefs.personalContext(ctx); reload++; onPauseOrDispose {} }

    LaunchedEffect(query, reload) {
        if (query.isNotBlank()) delay(250)
        val page = withContext(Dispatchers.IO) { Lifelog.search(query.ifBlank { null }, limit = PAGE) }
        events = page; more = page.size == PAGE
    }
    val pm = ctx.packageManager
    val labels = remember { HashMap<String, String>() }
    fun source(e: Lifelog.Event) = when (e.kind) {
        Lifelog.Kind.PLACE -> "place"
        Lifelog.Kind.EPISODE -> "summary"
        else -> labels.getOrPut(e.app) { runCatching { pm.getApplicationLabel(pm.getApplicationInfo(e.app, 0)).toString() }.getOrDefault(e.app) }
    }
    fun forget(ids: Set<Long>) {
        events = events?.filter { it.id !in ids }
        AgentRuntime.scope.launch { ids.forEach(Lifelog::delete) }
    }

    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(if (recording) "recording on this phone only" else "recording is off", Modifier.weight(1f),
            style = MaterialTheme.typography.labelMedium, color = plate.muted)
        TextButton({ ctx.startActivity(Intent(ctx, SettingsActivity::class.java)) }) { Text(if (recording) "what it records" else "turn on") }
    }
    BasicTextField(query, { query = it },
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).slot(plate.plateLow, plate.plateEdge, 20.dp)
            .padding(horizontal = 16.dp, vertical = 11.dp),
        textStyle = MaterialTheme.typography.bodyMedium.copy(color = plate.ink), singleLine = true, cursorBrush = SolidColor(plate.ink),
        decorationBox = { inner ->
            Box { if (query.isEmpty()) Text("search what ubiq saw", style = MaterialTheme.typography.bodyMedium, color = plate.muted); inner() }
        })

    val shown = events
    val today = LocalDate.now()
    val zone = ZoneId.systemDefault()
    // Hours in order, newest first; a search opens every hour it found something in.
    val hours = shown.orEmpty().groupBy { Instant.ofEpochMilli(it.time).atZone(zone).withMinute(0).withSecond(0).withNano(0) }.entries.toList()
    LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)) {
        if (shown != null && shown.isEmpty()) item {
            SpaceEmpty(when {
                query.isNotBlank() -> "nothing matches \"$query\"."
                !recording -> "nothing recorded. turn on personal context in settings and ubiq keeps a private timeline of your screens, notifications and places here, on this phone."
                else -> "nothing yet. what you look at, your notifications and the places you stay show up here as they happen."
            })
        }
        var lastDay: LocalDate? = null
        for ((hour, list) in hours) {
            val day = hour.toLocalDate()
            if (day != lastDay) {
                lastDay = day
                item(key = "day-$day") {
                    Text(when (day) { today -> "today"; today.minusDays(1) -> "yesterday"; else -> hour.format(DAY).lowercase() },
                        Modifier.padding(start = 4.dp, top = 14.dp, bottom = 2.dp), style = MaterialTheme.typography.titleSmall, color = plate.ink)
                }
            }
            val key = hour.toString()
            val expanded = query.isNotBlank() || key in open
            item(key = "hour-$key") {
                val episode = list.firstOrNull { it.kind == Lifelog.Kind.EPISODE }
                val apps = list.filter { it.kind != Lifelog.Kind.EPISODE }.map(::source).distinct()
                // The sparkle marks an hour the on-device model has summarised; until then it is a plain hour.
                SpaceRow(if (episode != null) Icons.Default.AutoAwesome else Icons.Default.Schedule, Ubiq.Blue, hour.format(HOUR),
                    episode?.text ?: (apps.take(4).joinToString(", ") + if (apps.size > 4) " and ${apps.size - 4} more" else ""),
                    "${list.size} ${if (list.size == 1) "moment" else "moments"}" + if (expanded) "" else " · tap to open") {
                    open = if (key in open) open - key else open + key
                }
            }
            if (expanded) {
                items(list, key = { "e-${it.id}" }) { e -> Moment(e, source(e)) { forget(setOf(e.id)) } }
                item(key = "del-$key") {
                    TextButton({ forget(list.map { it.id }.toSet()) }, Modifier.padding(start = 28.dp)) { Text(if (query.isBlank()) "delete this hour" else "delete these") }
                }
            }
        }
        if (more) item(key = "more") {
            TextButton({
                val oldest = shown?.lastOrNull()?.time ?: return@TextButton
                scope.launch {
                    val page = withContext(Dispatchers.IO) { Lifelog.search(query.ifBlank { null }, to = oldest - 1, limit = PAGE) }
                    events = shown + page; more = page.size == PAGE
                }
            }, Modifier.fillMaxWidth()) { Text("earlier") }
        }
    }
}

/** One recorded moment: what kind, from where and when, its text as it was stored, and a delete. */
@Composable
private fun Moment(e: Lifelog.Event, source: String, onDelete: () -> Unit) {
    val (icon, accent) = when (e.kind) {
        Lifelog.Kind.SCREEN -> Icons.Default.PhoneAndroid to Ubiq.Blue
        Lifelog.Kind.NOTIFICATION -> Icons.Default.Notifications to Ubiq.Orange
        Lifelog.Kind.PLACE -> Icons.Default.Place to Ubiq.Green
        Lifelog.Kind.EPISODE -> Icons.Default.AutoAwesome to Ubiq.Blue
    }
    val zone = ZoneId.systemDefault()
    val span = Instant.ofEpochMilli(e.time).atZone(zone).format(MINUTE) +
        if (e.until - e.time >= 60_000) "-" + Instant.ofEpochMilli(e.until).atZone(zone).format(MINUTE) else ""
    Row(Modifier.fillMaxWidth().padding(start = 24.dp).clip(RoundedCornerShape(14.dp)).padding(vertical = 6.dp), verticalAlignment = Alignment.Top) {
        KindBadge(icon, accent, Modifier.padding(top = 1.dp), size = 18.dp)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text("$source · $span", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = plate.ink)
            Text(e.text.lines().filter { it.isNotBlank() }.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = plate.muted,
                maxLines = 4, overflow = TextOverflow.Ellipsis)
        }
        Text("delete", Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onDelete).padding(horizontal = 8.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelMedium, color = plate.muted)
    }
}
