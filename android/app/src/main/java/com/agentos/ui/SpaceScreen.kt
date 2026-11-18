package com.agentos.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.ui.draw.clip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.agentos.space.Space

/**
 * Space: the Library (the user's quick notes, the agent's memories, saved things) and Jobs
 * (scheduled, recurring and triggered work the user set up by asking). Plain, like the ROM's
 * settings: lowercase text tabs with a light on the chosen one, rows without cards, a slot to type in.
 */
@Composable
internal fun SpaceScreen(onBack: () -> Unit, onOpenChat: (String) -> Unit) {
    var tab by rememberSaveable { mutableStateOf(0) }
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
        val ctx = androidx.compose.ui.platform.LocalContext.current
        ScreenHeader("space", onBack, Icons.AutoMirrored.Filled.ArrowBack) {
            BubbleIcon(Icons.Default.Settings, "Settings",
                { ctx.startActivity(android.content.Intent(ctx, SettingsActivity::class.java)) })
        }
        LitTabs(listOf("library", "jobs"), tab, { tab = it }, Modifier.padding(start = 20.dp, top = 2.dp, bottom = 6.dp), big = true)
        if (tab == 0) LibraryTab() else JobsTab(onOpenChat)
    }
}

/** Lowercase text tabs; the chosen one is in ink with a small yellow light, the rest muted. */
@Composable
internal fun LitTabs(labels: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier, big: Boolean = false) {
    Row(modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(if (big) 22.dp else 18.dp),
        verticalAlignment = Alignment.CenterVertically) {
        labels.forEachIndexed { i, label ->
            val on = i == selected
            Row(Modifier.clip(RoundedCornerShape(8.dp)).clickable { onSelect(i) }
                .semantics { role = Role.Tab; this.selected = on }.padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically) {
                if (on) { Led(Ubiq.Yellow, size = 6.dp); Spacer(Modifier.width(7.dp)) }
                Text(label, maxLines = 1,
                    style = if (big) MaterialTheme.typography.titleMedium else MaterialTheme.typography.labelLarge,
                    fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (on) plate.ink else plate.muted)
            }
        }
    }
}

/** The bottom slot to type into, with a small yellow send key. */
@Composable
internal fun SpaceSlot(draft: String, onDraft: (String) -> Unit, placeholder: String, sendDescription: String, onSend: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        BasicTextField(draft, onDraft,
            Modifier.weight(1f).slot(plate.plateLow, plate.plateEdge, 20.dp).padding(horizontal = 16.dp, vertical = 11.dp),
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = plate.ink), maxLines = 4,
            cursorBrush = SolidColor(plate.ink),
            decorationBox = { inner ->
                Box { if (draft.isEmpty()) Text(placeholder, style = MaterialTheme.typography.bodyMedium, color = plate.muted); inner() }
            })
        Spacer(Modifier.width(10.dp))
        IconKey(Icons.AutoMirrored.Filled.Send, sendDescription, onSend, KeyColor.Yellow, size = 38.dp)
    }
}

/** A plain row: a bare kind icon, the title, two lines of text and a quiet meta line. */
@Composable
internal fun SpaceRow(icon: androidx.compose.ui.graphics.vector.ImageVector, accent: Color, title: String, text: String, meta: String,
                      dim: Boolean = false, pinned: Boolean = false, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick)
        .alpha(if (dim) 0.55f else 1f).padding(horizontal = 4.dp, vertical = 10.dp)) {
        KindBadge(icon, accent, Modifier.padding(top = 1.dp), size = 20.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            if (title.isNotBlank()) Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold,
                color = plate.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (text.isNotBlank()) Text(text, style = MaterialTheme.typography.bodyMedium, color = if (title.isBlank()) plate.ink else plate.muted,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(meta, Modifier.padding(top = 2.dp), style = MaterialTheme.typography.labelSmall, color = plate.muted,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (pinned) Icon(Icons.Default.PushPin, "Pinned", Modifier.padding(start = 8.dp, top = 2.dp).size(14.dp), tint = plate.muted)
    }
}

/** "just now", "5 minutes ago", "yesterday", in lowercase. */
internal fun spaceAgo(time: Long): String =
    if (System.currentTimeMillis() - time < 60_000) "just now"
    else android.text.format.DateUtils.getRelativeTimeSpanString(time).toString().lowercase()

/** Empty-state text: quiet, left-aligned, no ornament. */
@Composable
internal fun SpaceEmpty(text: String) {
    Text(text, Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 14.dp),
        style = MaterialTheme.typography.bodyMedium, color = plate.muted)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ColumnScope.LibraryTab() {
    val all by Space.items.collectAsStateWithLifecycle()
    var filter by remember { mutableStateOf<Space.Kind?>(null) }
    var draft by remember { mutableStateOf("") }
    var open by remember { mutableStateOf<Space.Item?>(null) }
    val shown = all.filter { filter == null || it.kind == filter }

    run {
        val kinds = listOf<Space.Kind?>(null, Space.Kind.NOTE, Space.Kind.MEMORY, Space.Kind.SAVED)
        LitTabs(listOf("all", "notes", "memory", "saved"), kinds.indexOf(filter), { filter = kinds[it] },
            Modifier.padding(start = 20.dp, bottom = 2.dp))
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)) {
            if (shown.isEmpty()) item {
                SpaceEmpty(when (filter) {
                    Space.Kind.MEMORY -> "nothing remembered yet. tell ubiq things like \"i'm vegetarian\" or \"my sister is amy\" and it keeps them here."
                    Space.Kind.SAVED -> "lists, plans and drafts ubiq saves for you show up here."
                    else -> "jot a note below. ubiq can read your notes when they help, and keeps its memories here too."
                })
            }
            items(shown, key = { it.id }) { item ->
                val (icon, accent) = when (item.kind) {
                    Space.Kind.NOTE -> Icons.Default.EditNote to Brand.Butter
                    Space.Kind.MEMORY -> Icons.Default.AutoAwesome to Brand.Lilac
                    Space.Kind.SAVED -> Icons.Default.Bookmark to Ubiq.Green
                }
                SpaceRow(icon, accent, item.title, item.text,
                    "${item.kind.label.lowercase()} · ${if (item.byAgent) "ubiq" else "you"} · ${spaceAgo(item.updated)}",
                    pinned = item.pinned) { open = item }
            }
        }
        SpaceSlot(draft, { draft = it }, "jot a note", "Save note") {
            if (draft.isNotBlank()) { Space.add(Space.Kind.NOTE, "", draft, byAgent = false); draft = "" }
        }
    }

    open?.let { item ->
        var title by remember(item.id) { mutableStateOf(item.title) }
        var text by remember(item.id) { mutableStateOf(item.text) }
        AlertDialog(
            onDismissRequest = { open = null },
            title = { Text(item.kind.label + if (item.byAgent) " (saved by the assistant)" else "") },
            text = {
                Column {
                    OutlinedTextField(title, { title = it }, Modifier.fillMaxWidth(), label = { Text("Title") }, singleLine = true)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth().height(220.dp), label = { Text("Text") })
                }
            },
            confirmButton = { TextButton(onClick = { Space.update(item.id, title, text); open = null }) { Text("Save") } },
            dismissButton = {
                Row {
                    TextButton(onClick = { Space.update(item.id, pinned = !item.pinned); open = null }) { Text(if (item.pinned) "Unpin" else "Pin") }
                    TextButton(onClick = { Space.delete(item.id); open = null }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                }
            },
        )
    }
}
