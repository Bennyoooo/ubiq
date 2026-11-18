package com.agentos.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.agentos.agent.AgentRuntime
import com.agentos.jobs.Jobs

/** Space > Jobs: one-time, recurring and trigger jobs, with their next and last runs. */
@Composable
internal fun ColumnScope.JobsTab(onOpenChat: (String) -> Unit) {
    val jobs by Jobs.jobs.collectAsStateWithLifecycle()
    // Re-read session state (last results) whenever any session changes.
    val sessions by AgentRuntime.sessions.collectAsStateWithLifecycle()
    var open by remember { mutableStateOf<Int?>(null) }
    var draft by remember { mutableStateOf("") }

    LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)) {
        if (jobs.isEmpty()) item {
            SpaceEmpty("no jobs yet. ask for one below or in any chat, for example \"every weekday at 8, brief me on my calendar\", " +
                "\"tomorrow at 9, check if the tickets are on sale\", or \"when mom texts, read it to me\". " +
                "plain reminders and alarms go in clock or calendar.")
        }
        for (kind in Jobs.Kind.entries) {
            val group = jobs.filter { it.kind == kind }.sortedWith(compareBy<Jobs.Job> { it.finished }.thenBy { !it.enabled }.thenBy { if (it.nextRun > 0) it.nextRun else Long.MAX_VALUE })
            if (group.isEmpty()) continue
            item(key = "h-$kind") {
                Text(kind.label.lowercase(), Modifier.padding(top = 14.dp, bottom = 2.dp, start = 4.dp),
                    style = MaterialTheme.typography.labelMedium, color = plate.muted)
            }
            items(group, key = { it.id }) { j -> JobCard(j, sessions.size) { open = j.id } }
        }
    }
    SpaceSlot(draft, { draft = it }, "describe a new job", "Ask ubiq to set it up") {
        if (draft.isNotBlank()) {
            AgentRuntime.toMain("Set up a job: ${draft.trim()}")
            draft = ""
            onOpenChat(AgentRuntime.MAIN_ID)
        }
    }

    open?.let { id -> Jobs.get(id) }?.let { j ->
        AlertDialog(
            onDismissRequest = { open = null },
            title = { Text(j.title) },
            text = {
                Column {
                    Text(j.schedule(), fontWeight = FontWeight.SemiBold)
                    Text(j.status(), style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.size(8.dp))
                    Text(if (j.action == "digest") "Summarizes ${j.app} notifications${if (j.onDevice) " on the phone" else ""}." else j.instruction,
                        style = MaterialTheme.typography.bodyMedium)
                    if (j.lastRun > 0) {
                        Spacer(Modifier.size(8.dp))
                        Text("Last run ${Jobs.fmt(j.lastRun)}", style = MaterialTheme.typography.labelMedium)
                        lastLine(j)?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 6, overflow = TextOverflow.Ellipsis) }
                    } else if (j.lastNote.isNotBlank()) Text(j.lastNote, style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                Row {
                    if (j.lastSession != null && AgentRuntime.session(j.lastSession) != null)
                        TextButton(onClick = { open = null; onOpenChat(j.lastSession) }) { Text("Open run") }
                    TextButton(onClick = { Jobs.run(j); open = null }) { Text("Run now") }
                }
            },
            dismissButton = {
                Row {
                    if (!j.finished) TextButton(onClick = { Jobs.setEnabled(j.id, !j.enabled); open = null }) { Text(if (j.enabled) "Pause" else "Resume") }
                    TextButton(onClick = { Jobs.delete(j.id); open = null }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                }
            },
        )
    }
}

private fun Jobs.Kind.accent() = when (this) { Jobs.Kind.ONCE -> Brand.Butter; Jobs.Kind.RECURRING -> Brand.Lilac; Jobs.Kind.TRIGGER -> Brand.Tangerine }

private fun lastLine(j: Jobs.Job): String? =
    if (j.action == "digest") com.agentos.notify.NotifyRules.digests.value.firstOrNull { it.ruleId == j.id }?.text ?: j.lastNote.ifBlank { null }
    else Jobs.lastResult(j) ?: j.lastNote.ifBlank { null } ?: j.lastSession?.let { AgentRuntime.session(it) }?.status?.value
        ?.takeIf { it !is com.agentos.agent.AgentStatus.Idle }?.let { "Running…" }

@Composable
private fun JobCard(j: Jobs.Job, @Suppress("UNUSED_PARAMETER") tick: Int, onClick: () -> Unit) {
    val last = if (j.lastRun > 0) lastLine(j)?.let { "last ${Jobs.fmt(j.lastRun)}: ${it.lineSequence().first()}" } else null
    SpaceRow(when (j.kind) { Jobs.Kind.ONCE -> Icons.Default.Event; Jobs.Kind.RECURRING -> Icons.Default.Repeat; Jobs.Kind.TRIGGER -> Icons.Default.Bolt },
        j.kind.accent(), j.title, last ?: j.status(), "${j.schedule()} · ${j.status()}".lowercase(), dim = !j.enabled, onClick = onClick)
}
