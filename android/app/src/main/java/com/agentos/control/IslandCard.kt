package com.agentos.control

import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.agentos.ui.AgentOsTheme
import com.agentos.ui.Key
import com.agentos.ui.KeyColor
import com.agentos.ui.Led
import com.agentos.ui.TextKey
import com.agentos.ui.Ubiq
import com.agentos.ui.moulded

/**
 * The card that drops from the island (docs/UX_SPEC.md, "The heads-up card"): Ubiq's questions
 * with their answer keys. Other apps keep Android's own banner; Ubiq never blocks them.
 *
 * The app you are in stays usable. Tap a key to answer or act, tap the card to open it, swipe it
 * up to dismiss. Left alone, it goes back into the island after a few seconds.
 */
object IslandCard {
    private const val TAG = "AgentOS/IslandCard"

    class Card(
        val source: String,
        val title: String,
        val text: String,
        /** The app's icon (other apps), or null for Ubiq, which shows its light instead. */
        val icon: ImageBitmap? = null,
        /** Ubiq's light colour when [icon] is null. */
        val light: Color = Ubiq.Yellow,
        /** Answer keys (Ubiq) or the notification's own actions (other apps); the first is the main one. */
        val keys: List<Pair<String, () -> Unit>> = emptyList(),
        val onOpen: (() -> Unit)? = null,
        val showMillis: Long = 6_000,
        /** Same key as an earlier card: replace it rather than stack. */
        val key: String = source + title,
    )

    private val main = Handler(Looper.getMainLooper())
    private var window: ComposeView? = null
    private var owner: Owner? = null
    private var shown: Card? = null
    private var visible: MutableTransitionState<Boolean>? = null
    private val hideLater = Runnable { hide() }

    fun drop(card: Card) {
        main.post {
            val svc = ControlService.instance ?: return@post
            remove(svc)
            shown = card
            val state = MutableTransitionState(false).apply { targetState = true }
            visible = state
            val o = Owner().also { it.start() }
            val v = ComposeView(svc).apply {
                setViewTreeLifecycleOwner(o)
                setViewTreeSavedStateRegistryOwner(o)
                setContent { AgentOsTheme { CardView(card, state) } }
            }
            val wm = svc.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val lp = WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT).apply {
                gravity = Gravity.TOP
                y = Island.bottom()
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
            try { wm.addView(v, lp); window = v; owner = o } catch (t: Throwable) { Log.w(TAG, "card failed", t); o.stop(); return@post }
            main.removeCallbacks(hideLater)
            main.postDelayed(hideLater, card.showMillis)
        }
    }

    /** Takes the card back into the island (a question's light stays there until it is answered). */
    fun hide() {
        main.post {
            main.removeCallbacks(hideLater)
            val st = visible ?: return@post
            st.targetState = false
            // Remove the window once the animation has played.
            main.postDelayed({ ControlService.instance?.let { if (visible === st) remove(it) } }, 260)
        }
    }

    /** Hides the card if it is this one (its question was answered elsewhere). */
    fun hideIf(key: String) { main.post { if (shown?.key == key) hide() } }

    internal fun remove(ctx: Context) {
        window?.let { try { (ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(it) } catch (_: Throwable) {} }
        owner?.stop()
        window = null; owner = null; shown = null; visible = null
    }

    private fun act(f: () -> Unit) { try { f() } catch (t: Throwable) { Log.w(TAG, "card action", t) }; hide() }

    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    private fun CardView(card: Card, state: MutableTransitionState<Boolean>) {
        // It grows out of the island: from the top centre, a little scaled down.
        AnimatedVisibility(state, enter = scaleIn(tween(220), initialScale = 0.6f, transformOrigin = TransformOrigin(0.5f, 0f)) +
            expandVertically(tween(220), expandFrom = Alignment.Top) + fadeIn(tween(150)),
            exit = shrinkVertically(tween(200), shrinkTowards = Alignment.Top) + fadeOut(tween(180))) {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 10.dp)
                    .moulded(Ubiq.Deck, Color.Black, 28.dp, 4.dp).padding(bottom = 4.dp)
                    .clip(RoundedCornerShape(28.dp))
                    .pointerInput(card) {
                        var dy = 0f
                        detectVerticalDragGestures(onDragStart = { dy = 0f; main.removeCallbacks(hideLater) },
                            onDragEnd = { if (dy < -30f) hide() else main.postDelayed(hideLater, card.showMillis) }) { _, d -> dy += d }
                    }
                    .clickable(enabled = card.onOpen != null) { card.onOpen?.let { act(it) } }
                    .padding(horizontal = 18.dp, vertical = 14.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (card.icon != null) Image(card.icon, null, Modifier.size(18.dp))
                    else Box(Modifier.size(18.dp), contentAlignment = Alignment.Center) { Led(card.light) }
                    Spacer(Modifier.width(8.dp))
                    Text(card.source, style = MaterialTheme.typography.labelMedium, color = Color(0xFFA9A9A6), maxLines = 1)
                }
                Spacer(Modifier.size(6.dp))
                if (card.title.isNotBlank()) Text(card.title, style = MaterialTheme.typography.titleMedium, color = Ubiq.Paper, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (card.text.isNotBlank()) Text(card.text, style = MaterialTheme.typography.bodyMedium, color = Color(0xFFD6D6D0), maxLines = 3, overflow = TextOverflow.Ellipsis)
                if (card.keys.isNotEmpty()) FlowRow(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (card.icon == null) card.keys.forEachIndexed { i, (label, f) -> Key(label, { act(f) }, color = if (i == 0) KeyColor.Yellow else KeyColor.Light, compact = true) }
                    // Other apps' actions stay quiet: text keys, so the card reads as one message.
                    else card.keys.forEach { (label, f) -> TextKey(label, { act(f) }, color = Ubiq.Yellow) }
                }
            }
        }
    }

    /** A minimal lifecycle so Compose can run in an overlay window. */
    private class Owner : LifecycleOwner, SavedStateRegistryOwner {
        private val registry = LifecycleRegistry(this)
        private val saved = SavedStateRegistryController.create(this)
        override val lifecycle: Lifecycle get() = registry
        override val savedStateRegistry: SavedStateRegistry get() = saved.savedStateRegistry
        fun start() { saved.performRestore(null); registry.currentState = Lifecycle.State.RESUMED }
        fun stop() { registry.currentState = Lifecycle.State.DESTROYED }
    }
}
