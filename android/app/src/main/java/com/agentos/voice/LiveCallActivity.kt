package com.agentos.voice

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.PowerManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.agentos.agent.AgentRuntime
import com.agentos.agent.AgentStatus
import com.agentos.ui.AgentOsTheme
import com.agentos.ui.FitText
import com.agentos.ui.HomeActivity
import com.agentos.ui.IconKey
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.PhoneInTalk
import com.agentos.ui.KeyColor
import com.agentos.ui.Led
import com.agentos.ui.TextKey
import com.agentos.ui.Ubiq
import kotlinx.coroutines.delay

/**
 * The call screen for live mode ([LiveCall]). Opening it starts a call; it shows over the lock screen, and
 * with the voice on the earpiece the proximity sensor turns the screen off at the ear, as in a phone call.
 * Leaving it does not end the call: the ongoing-call notification brings it back.
 */
class LiveCallActivity : ComponentActivity() {
    private var proximity: PowerManager.WakeLock? = null

    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) LiveCall.start(this) else finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true); setTurnScreenOn(true)
        enableEdgeToEdge()
        if (!LiveCall.active) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) LiveCall.start(this)
            else micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
        setContent { AgentOsTheme { CallScreen(onEnd = { LiveCall.hangUp() }, onClose = { finish() }, onChat = { HomeActivity.bringToFront(this); finish() }) } }
    }

    override fun onResume() { super.onResume(); updateProximity() }
    override fun onPause() { super.onPause(); proximity?.takeIf { it.isHeld }?.release() }

    /** On the earpiece, the screen goes dark at the ear; on speaker it stays on. */
    internal fun updateProximity() {
        val pm = getSystemService(PowerManager::class.java)
        if (!pm.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)) return
        val want = LiveCall.active && LiveCall.onEarpiece(this)
        val wl = proximity ?: pm.newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, "Ubiq:live-ear").also { proximity = it }
        if (want && !wl.isHeld) wl.acquire(60 * 60_000L) else if (!want && wl.isHeld) wl.release()
    }

    companion object {
        /**
         * Always on the phone's own screen. Started from a non-activity context (the island, drawn by the
         * accessibility service), Android otherwise uses whichever display has focus, and while the agent works
         * that is its hidden workspace: the call screen opened there, invisibly, and every later tap on the island
         * raised it there again (seen 2026-11-15). Naming the display also pulls a task stranded there back.
         */
        fun open(ctx: Context) {
            val opts = android.app.ActivityOptions.makeBasic().setLaunchDisplayId(android.view.Display.DEFAULT_DISPLAY).toBundle()
            ctx.startActivity(Intent(ctx, LiveCallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP), opts)
        }
    }
}

@Composable
private fun CallScreen(onEnd: () -> Unit, onClose: () -> Unit, onChat: () -> Unit) {
    val phase by LiveCall.phase.collectAsStateWithLifecycle()
    val lines by LiveCall.lines.collectAsStateWithLifecycle()
    val muted by LiveCall.muted.collectAsStateWithLifecycle()
    val speaker by LiveCall.speaker.collectAsStateWithLifecycle()
    val error by LiveCall.error.collectAsStateWithLifecycle()
    val level by LiveCall.level.collectAsStateWithLifecycle()
    val agent by AgentRuntime.main.status.collectAsStateWithLifecycle()
    val activity = androidx.compose.ui.platform.LocalContext.current as? LiveCallActivity
    // The screen closes a moment after the call ends, unless there is an error to read.
    LaunchedEffect(phase, error) { if (phase == LiveCall.Phase.Ended && error == null) { delay(700); onClose() } }
    LaunchedEffect(speaker, phase) { activity?.updateProximity() }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding().navigationBarsPadding().padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextKey("‹ chat", onChat, Modifier.semantics { contentDescription = "Back to the chat; the call goes on" })
            Spacer(Modifier.weight(1f))
            Led(if (phase == LiveCall.Phase.Ended) Ubiq.Red else Ubiq.Lit, size = 8.dp, glow = phase != LiveCall.Phase.Ended)
            Spacer(Modifier.width(7.dp))
            Text(if (phase == LiveCall.Phase.Ended) "ended" else "live", style = MaterialTheme.typography.labelLarge)
        }
        // No name and no timer (user feedback 2026-11-13): the four lights are the call.
        Spacer(Modifier.height(64.dp))
        VoiceLeds(phase, level)
        Spacer(Modifier.height(18.dp))
        val label = when {
            error != null -> error!!
            phase == LiveCall.Phase.Connecting -> "calling…"
            muted && phase != LiveCall.Phase.Ended -> "muted"
            phase == LiveCall.Phase.Hearing -> "listening…"
            phase == LiveCall.Phase.Speaking -> "ubiq is talking"
            phase == LiveCall.Phase.Thinking -> "thinking…"
            phase == LiveCall.Phase.Ended -> "call ended"
            else -> "go ahead"
        }
        Text(label, style = MaterialTheme.typography.bodyMedium, color = if (error != null) Ubiq.Red else MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        // What the agent is doing on the phone, while it works.
        (agent as? AgentStatus.Running)?.let { r ->
            Spacer(Modifier.height(10.dp))
            Row(Modifier.clip(RoundedCornerShape(10.dp)).clickable(onClick = onChat).padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Led(Ubiq.Yellow, size = 7.dp)
                Spacer(Modifier.width(8.dp))
                FitText(r.note.lowercase(), style = MaterialTheme.typography.bodySmall, minScale = 0.8f)
            }
        }
        Spacer(Modifier.height(16.dp))
        Transcript(lines, Modifier.weight(1f))
        // Three round keys with a small word under each, like a phone's call screen: nothing to crop (user feedback 2026-11-13).
        Row(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 22.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.Top) {
            val live = phase != LiveCall.Phase.Ended
            CallKey(if (muted) Icons.Default.MicOff else Icons.Default.Mic, if (muted) "unmute" else "mute",
                { if (live) LiveCall.toggleMute() }, if (muted && live) KeyColor.Yellow else KeyColor.Light)
            CallKey(if (speaker) Icons.AutoMirrored.Filled.VolumeUp else Icons.Default.PhoneInTalk, if (speaker) "speaker" else "earpiece",
                { if (live) LiveCall.toggleSpeaker() }, if (speaker && live) KeyColor.Blue else KeyColor.Light)
            if (live) CallKey(Icons.Default.CallEnd, "end", onEnd, KeyColor.Red)
            else CallKey(Icons.Default.Close, "close", onClose, KeyColor.Light)
        }
    }
}

@Composable
private fun CallKey(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit, color: KeyColor) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        IconKey(icon, label, onClick, color = color, size = 64.dp)
        Spacer(Modifier.height(8.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
}

/** Ubiq's four lights, the call's face: they swell with whoever is talking and chase while Ubiq thinks. A small, tight glow. */
@Composable
private fun VoiceLeds(phase: LiveCall.Phase, level: Float) {
    val colors = listOf(Ubiq.Red, Ubiq.Yellow, Ubiq.Blue, Ubiq.Green)
    val talking = phase == LiveCall.Phase.Hearing || phase == LiveCall.Phase.Speaking
    val lvl by animateFloatAsState(if (talking) level else 0f, tween(90), label = "lvl")
    val t by produceState(0f) { while (true) { value = (System.currentTimeMillis() % 1400) / 1400f; delay(50) } }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        colors.forEachIndexed { i, c ->
            val lit = when (phase) {
                LiveCall.Phase.Connecting, LiveCall.Phase.Thinking -> if (((t * 4).toInt()) == i) 1f else 0.3f
                LiveCall.Phase.Ended -> 0.15f
                LiveCall.Phase.Listening -> 0.55f
                else -> (0.4f + lvl * (1.6f - i * 0.25f)).coerceIn(0.4f, 1f)
            }
            Canvas(Modifier.size(20.dp)) {
                // Small, like indicator lights on a panel (user feedback 2026-11-13: "much smaller").
                val r = (4.5f + 2.5f * lit).dp.toPx()
                val o = center
                if (lit > 0.5f) drawCircle(c.copy(alpha = 0.22f * lit), radius = r * 1.35f, center = o)
                drawCircle(Brush.radialGradient(listOf(androidx.compose.ui.graphics.lerp(c, Color.White, 0.5f * lit), c),
                    center = o.copy(x = o.x - r * 0.3f, y = o.y - r * 0.35f), radius = r * 1.3f), radius = r, center = o, alpha = 0.35f + 0.65f * lit)
            }
        }
    }
}

/** What was said on the call: Ubiq's words plain, the user's in the muted colour, no bubbles. */
@Composable
private fun Transcript(lines: List<LiveCall.Line>, modifier: Modifier) {
    val state = rememberLazyListState()
    LaunchedEffect(lines.size) { if (lines.isNotEmpty()) state.animateScrollToItem(lines.size - 1) }
    LazyColumn(modifier.fillMaxWidth(), state = state, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items(lines, key = { it.id }) { l ->
            Text(l.text, Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodyLarge,
                color = if (l.who == LiveCall.Who.USER) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onBackground,
                textAlign = if (l.who == LiveCall.Who.USER) TextAlign.End else TextAlign.Start, maxLines = 6, overflow = TextOverflow.Ellipsis)
        }
    }
}
