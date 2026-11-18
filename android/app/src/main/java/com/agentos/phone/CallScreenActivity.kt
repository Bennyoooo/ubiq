package com.agentos.phone

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.agentos.ui.AgentOsTheme
import com.agentos.ui.FitText
import com.agentos.ui.Key
import com.agentos.ui.KeyColor
import com.agentos.ui.Led
import com.agentos.ui.TextKey
import com.agentos.ui.Ubiq
import kotlinx.coroutines.delay

/**
 * The owner's view of a call Ubiq is screening: who is calling and why, the conversation as it happens, and the
 * decision (pick up, decline, or tell them something). Shows over the lock screen; closes when the call ends.
 */
class CallScreenActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true); setTurnScreenOn(true)
        enableEdgeToEdge()
        setContent { AgentOsTheme { ScreeningScreen(onClose = { finish() }) } }
    }

    override fun onResume() { super.onResume(); CallScreener.ownerWatching = true }
    override fun onPause() { super.onPause(); CallScreener.ownerWatching = false }
}

private val QUICK = listOf("Text me instead", "I'll call back later", "What's it about exactly?", "Leave it at the door")

@Composable
private fun ScreeningScreen(onClose: () -> Unit) {
    val live by CallScreener.live.collectAsStateWithLifecycle()
    LaunchedEffect(live == null) { if (live == null) { delay(400); onClose() } }
    val l = live ?: return
    var reply by remember { mutableStateOf("") }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    fun decided() = ScreenNotices.clearAsk(ctx)

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding().navigationBarsPadding().imePadding().padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextKey("‹ hide", onClose)
            Spacer(Modifier.weight(1f))
            Led(if (l.askingOwner) Ubiq.Yellow else Ubiq.Lit, size = 8.dp)
            Spacer(Modifier.width(7.dp))
            Text(if (l.askingOwner) "your call" else "screening", style = MaterialTheme.typography.labelLarge)
        }
        Spacer(Modifier.height(22.dp))
        Text("ubiq is answering", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        FitText(l.who, style = MaterialTheme.typography.headlineMedium, minScale = 0.6f)
        if (l.caller.isNotBlank() && l.number.isNotBlank())
            Text(l.number, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (l.spoofed) Text("caller ID not verified", style = MaterialTheme.typography.bodySmall, color = Ubiq.Red)
        Spacer(Modifier.height(10.dp))
        Text(when {
            l.reason.isNotBlank() -> (if (l.urgent) "Urgent: " else "") + l.reason
            else -> l.phase
        }, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
        Spacer(Modifier.height(16.dp))
        Transcript(l.lines, Modifier.weight(1f))
        // Tell them something: quick replies and a box.
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            QUICK.forEach { q -> Key(q.lowercase(), { CallScreener.ownerSays(q); decided() }, compact = true) }
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(reply, { reply = it }, Modifier.weight(1f), placeholder = { Text("Tell them…") }, singleLine = true, shape = RoundedCornerShape(20.dp))
            Spacer(Modifier.width(8.dp))
            Key("say", { CallScreener.ownerSays(reply.trim()); reply = ""; decided() }, enabled = reply.isNotBlank(), compact = true)
        }
        Row(Modifier.fillMaxWidth().padding(vertical = 18.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            Key("pick up", { CallScreener.pickUp(); decided() }, color = KeyColor.Green)
            Key("decline", { CallScreener.decline(); decided() }, color = KeyColor.Light)
            Key("end", { CallScreener.hangUp(); decided() }, color = KeyColor.Red)
        }
    }
}

/** The caller's words in the muted colour on the right, Ubiq's plain on the left, the owner's notes small. */
@Composable
private fun Transcript(lines: List<CallScreener.Line>, modifier: Modifier) {
    val state = rememberLazyListState()
    LaunchedEffect(lines.size) { if (lines.isNotEmpty()) state.animateScrollToItem(lines.size - 1) }
    LazyColumn(modifier.fillMaxWidth(), state = state, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items(lines, key = { it.id }) { line ->
            when (line.who) {
                CallScreener.Who.NOTE -> Text(line.text, Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                else -> Text(line.text, Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodyLarge,
                    color = if (line.who == CallScreener.Who.CALLER) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onBackground,
                    textAlign = if (line.who == CallScreener.Who.CALLER) TextAlign.End else TextAlign.Start, maxLines = 6, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
