package com.agentos.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * The talk button: a bare red mic with no container. Tap to start dictating and tap again to send;
 * or hold it while speaking and let go to send. Pauses in speech never end it. While listening, a
 * red light breathes behind the mic.
 */
@Composable
fun MicButton(listening: Boolean, onStart: () -> Unit, onStop: () -> Unit, modifier: Modifier = Modifier) {
    val isOn by rememberUpdatedState(listening)
    val start by rememberUpdatedState(onStart)
    val stop by rememberUpdatedState(onStop)
    val ring = rememberInfiniteTransition(label = "ring")
    val pulse by ring.animateFloat(0.86f, 1.08f, infiniteRepeatable(tween(700, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "pulse")
    Box(
        modifier.size(32.dp)
            .semantics { contentDescription = if (listening) "Stop and send" else "Talk"; role = Role.Button }
            .pointerInput(Unit) {
                detectTapGestures(onPress = {
                    if (isOn) { if (tryAwaitRelease()) stop(); return@detectTapGestures }
                    val t0 = System.currentTimeMillis()
                    start()
                    tryAwaitRelease()
                    // Held rather than tapped: push-to-talk, so letting go sends.
                    if (System.currentTimeMillis() - t0 > 450) stop()
                })
            },
        contentAlignment = Alignment.Center,
    ) {
        if (listening) Box(Modifier.size(30.dp).scale(pulse).clip(CircleShape).background(Ubiq.Red.copy(alpha = 0.22f)))
        GlyphIcon(Glyph.Mic, size = 22.dp)
    }
}
