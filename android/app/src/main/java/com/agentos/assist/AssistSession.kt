package com.agentos.assist

import android.Manifest
import android.app.assist.AssistStructure
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.view.View
import android.view.WindowManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.agentos.agent.AgentRuntime
import com.agentos.agent.AgentStatus
import com.agentos.agent.Attachment
import com.agentos.agent.ChatItem
import com.agentos.agent.Role
import com.agentos.ui.AgentOsTheme
import com.agentos.ui.HomeActivity
import com.agentos.voice.Speaker
import com.agentos.voice.VoiceInput
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The assistant sheet: opened by the assist gesture over whatever app is on screen. It knows what
 * the user is looking at (the app's screen text and a screenshot, shown as a removable "About this
 * screen" chip), listens straight away, and runs requests about that app on the visible screen.
 */
class AssistSession(ctx: Context) : VoiceInteractionSession(ctx), LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner {
    private val registry = LifecycleRegistry(this)
    private val savedState = SavedStateRegistryController.create(this)
    private val store = ViewModelStore()
    override val lifecycle: Lifecycle get() = registry
    override val savedStateRegistry: SavedStateRegistry get() = savedState.savedStateRegistry
    override val viewModelStore: ViewModelStore get() = store

    // What the user was looking at when the sheet opened.
    private var screenshot by mutableStateOf<Bitmap?>(null)
    private var screenText by mutableStateOf<String?>(null)
    private var appLabel by mutableStateOf<String?>(null)
    private var useContext by mutableStateOf(true)
    /**
     * The sheet works in the main conversation, like every entry point (docs/UX_SPEC.md). Each invocation shows only
     * its own slice of it: the items from [sliceFrom] on.
     */
    private var session by mutableStateOf<com.agentos.agent.Session?>(null)
    private var sliceFrom by mutableStateOf(0)
    private var listenTick by mutableIntStateOf(0)

    init { setTheme(com.agentos.R.style.Theme_AgentOS_Assist) }

    override fun onCreate() {
        super.onCreate()
        savedState.performRestore(null)
        registry.currentState = Lifecycle.State.CREATED
        window.window?.apply {
            setBackgroundDrawable(ColorDrawable(android.graphics.Color.TRANSPARENT))
            // The sheet lifts itself by the part of the keyboard that covers this window (see Sheet).
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
    }

    override fun onCreateContentView(): View = ComposeView(context).apply {
        setViewTreeLifecycleOwner(this@AssistSession)
        setViewTreeSavedStateRegistryOwner(this@AssistSession)
        setViewTreeViewModelStoreOwner(this@AssistSession)
        setContent { AgentOsTheme { Sheet() } }
    }

    override fun onPrepareShow(args: Bundle?, showFlags: Int) {
        super.onPrepareShow(args, showFlags)
        // The last invocation's screen is dropped here, before the system hands over the new one. Clearing it in onShow
        // would also drop the new screen if the assist data ever came first (the order isn't guaranteed).
        val capture = showFlags and (SHOW_WITH_ASSIST or SHOW_WITH_SCREENSHOT) != 0
        val fresh = args?.getBoolean(AgentVoiceService.ARG_FRESH) == true
        if (capture || fresh || session == null) { screenshot = null; screenText = null; appLabel = null; useContext = true }
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        registry.currentState = Lifecycle.State.RESUMED
        val capture = showFlags and (SHOW_WITH_ASSIST or SHOW_WITH_SCREENSHOT) != 0
        val fresh = args?.getBoolean(AgentVoiceService.ARG_FRESH) == true
        android.util.Log.i(TAG, "show flags=$showFlags fresh=$fresh app=$appLabel shot=${screenshot != null} text=${screenText?.length}")
        if (capture || fresh || session == null) {
            // A fresh invocation: new slice of the conversation, start listening (the screen was reset in onPrepareShow).
            session = AgentRuntime.main
            sliceFrom = AgentRuntime.main.items.value.size
            listenTick++
        }
        if (fresh) AgentVoiceService.pendingContext?.let { c ->
            AgentVoiceService.pendingContext = null
            appLabel = if (c.pkg.isBlank() || c.pkg == context.packageName) null
                else try { context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(c.pkg, 0)).toString() } catch (_: Throwable) { c.pkg }
            screenText = c.text.ifBlank { null }
            screenshot = c.png?.let { android.graphics.BitmapFactory.decodeByteArray(it, 0, it.size) }
        }
        session?.assistUi = ui
    }

    override fun onHide() {
        super.onHide()
        listening?.cancel(); listening = null
        if (registry.currentState.isAtLeast(Lifecycle.State.STARTED)) registry.currentState = Lifecycle.State.CREATED
    }

    override fun onDestroy() {
        session?.let { if (it.assistUi === ui) it.assistUi = null }
        registry.currentState = Lifecycle.State.DESTROYED
        store.clear()
        super.onDestroy()
    }

    override fun onHandleAssist(state: AssistState) {
        android.util.Log.i(TAG, "assist data: structure=${state.assistStructure != null} focused=${state.isFocused} app=${state.assistStructure?.activityComponent?.packageName}")
        val structure = state.assistStructure ?: return
        if (!state.isFocused) return
        appLabel = structure.activityComponent?.packageName?.let { pkg ->
            if (pkg == context.packageName) null
            else try { context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(pkg, 0)).toString() } catch (_: Throwable) { pkg }
        }
        screenText = readText(structure).ifBlank { null }
    }

    override fun onHandleScreenshot(screenshot: Bitmap?) {
        android.util.Log.i(TAG, "screenshot: ${screenshot?.let { "${it.width}x${it.height} ${it.config}" }}")
        this.screenshot = screenshot
    }

    private val ui = object : AgentRuntime.AssistUi {
        override suspend fun hideForAction() {
            withContext(Dispatchers.Main) { hide() }
            delay(400) // let the window go so the agent reads and taps the app, not the sheet
        }
        override fun showAgain() { AgentVoiceService.reshow() }
    }

    private var listening: Job? = null
    private var voice: VoiceInput? = null

    private fun hasContext() = useContext && appLabel != null && (screenshot != null || screenText != null)

    private fun send(text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        Speaker.stop()
        val s = session ?: AgentRuntime.main.also { session = it; sliceFrom = it.items.value.size; it.assistUi = ui }
        if (!hasContext()) { s.send(t, fromAssist = true); return }
        // After the agent has acted, the captured screen is out of date: keep working on the same
        // app, but have the agent look again rather than trust the old screenshot.
        if (s.items.value.drop(sliceFrom).any { it.role == Role.ACTION }) {
            s.send(t, modelText = "[Still about $appLabel, which is on screen; it may have changed since, so read the screen before acting.]\n$t", onScreen = true, fromAssist = true)
            return
        }
        val model = buildString {
            append("[I'm looking at ").append(appLabel).append(" right now. The attached screenshot shows my screen; the request is about it.]\n")
            append(t)
            screenText?.let { append("\n\n(Text on that screen:\n").append(it.take(3500)).append(")") }
        }
        val shot = screenshot?.let { listOf(Attachment.fromBitmap("screen.jpg", it)) } ?: emptyList()
        s.send(t, shot, modelText = model, onScreen = true, fromAssist = true)
    }

    private fun openFull() {
        AgentRuntime.focusMain()
        context.startActivity(Intent(context, HomeActivity::class.java).putExtra(HomeActivity.EXTRA_OPEN_CHAT, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        hide()
    }

    @Composable
    private fun Sheet() {
        val s = session ?: return
        val items by s.items.collectAsStateWithLifecycle()
        val status by s.status.collectAsStateWithLifecycle()
        val all by AgentRuntime.sessions.collectAsStateWithLifecycle()
        val elsewhere = all.filter { it !== s && it.status.value is AgentStatus.Running }
        var input by androidx.compose.runtime.remember { mutableStateOf("") }
        var partial by androidx.compose.runtime.remember { mutableStateOf("") }
        var isListening by androidx.compose.runtime.remember { mutableStateOf(false) }
        val scope = rememberCoroutineScope()
        val list = rememberLazyListState()
        val mine = items.drop(sliceFrom)
        // How far the keyboard reaches into this window, measured on screen: the system may already have shortened the
        // window for part of it, and padding by the whole IME inset on top of that lifted the sheet far above the keyboard.
        val view = androidx.compose.ui.platform.LocalView.current
        val imePx = WindowInsets.ime.getBottom(androidx.compose.ui.platform.LocalDensity.current)
        var winBottom by androidx.compose.runtime.remember { mutableIntStateOf(0) }
        val keyboard = run {
            val screenH = view.rootView.resources.displayMetrics.heightPixels.let { h -> maxOf(h, view.rootView.display?.let { d -> android.graphics.Point().also { d.getRealSize(it) }.y } ?: h) }
            if (imePx <= 0 || winBottom <= 0) 0 else (winBottom - (screenH - imePx)).coerceAtLeast(0)
        }

        fun listen() {
            if (isListening) return
            if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
            val vi = VoiceInput(context)
            if (!vi.available) return
            Speaker.stop()
            isListening = true; partial = ""; voice = vi
            listening = scope.launch {
                val r = try { vi.dictate(onText = { partial = it }) } finally { isListening = false; voice = null }
                partial = ""
                if (!r.isNullOrBlank()) send(r)
            }
        }

        LaunchedEffect(listenTick) { if (listenTick > 0 && s.status.value !is AgentStatus.Running) { delay(150); listen() } }
        LaunchedEffect(mine.size) { if (mine.isNotEmpty()) list.animateScrollToItem(mine.size - 1) }

        Box(Modifier.fillMaxSize().onGloballyPositioned { val loc = IntArray(2); view.getLocationOnScreen(loc); winBottom = loc[1] + it.size.height }) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.28f))
                .clickable(interactionSource = androidx.compose.runtime.remember { MutableInteractionSource() }, indication = null) { hide() })
            Surface(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    .clickable(interactionSource = androidx.compose.runtime.remember { MutableInteractionSource() }, indication = null) {},
                shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                color = MaterialTheme.colorScheme.background, tonalElevation = 0.dp, shadowElevation = 12.dp,
            ) {
                // The sheet's own padding keeps it above the keyboard (or the nav bar), and its colour runs on under the keyboard.
                Column((if (keyboard > 0) Modifier.padding(bottom = with(androidx.compose.ui.platform.LocalDensity.current) { keyboard.toDp() }) else Modifier.navigationBarsPadding())
                    .padding(horizontal = 16.dp, vertical = 10.dp)) {
                    Box(Modifier.align(Alignment.CenterHorizontally).size(width = 36.dp, height = 4.dp).clip(RoundedCornerShape(2.dp))
                        .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)))
                    Spacer(Modifier.height(10.dp))

                    // Tasks running in other sessions (e.g. in the background).
                    if (elsewhere.isNotEmpty() && mine.none { it.role == Role.USER }) {
                        Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            com.agentos.ui.StatusLed(AgentStatus.Running(0, ""))
                            Spacer(Modifier.width(10.dp))
                            Text(if (elsewhere.size == 1) "Working: ${elsewhere[0].title.value}" else "${elsewhere.size} tasks running in the background",
                                Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }

                    if (appLabel != null && (screenshot != null || screenText != null) && mine.none { it.role == Role.USER }) {
                        ContextChip(appLabel!!, screenshot, useContext) { useContext = !useContext }
                        Spacer(Modifier.height(8.dp))
                    }

                    if (mine.isNotEmpty()) {
                        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 340.dp), state = list) {
                            val liveAsk = mine.lastOrNull { it.role == Role.ASSISTANT || it.role == Role.USER }?.takeIf { it.role == Role.ASSISTANT && it.options.isNotEmpty() }?.id
                            items(mine, key = { it.id }) { SheetLine(it, live = it.id == liveAsk, onOption = { o -> send(o) }) }
                            if (status is AgentStatus.Running && mine.any { it.role == Role.USER }) item {
                                Row(Modifier.padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                    com.agentos.ui.StatusLed(status)
                                    Spacer(Modifier.width(10.dp))
                                    Text((status as AgentStatus.Running).note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                    }

                    if (isListening) Text(partial.ifBlank { "listening… tap the mic to send" }, Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                        color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyLarge)

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(input, { input = it; if (isListening) { listening?.cancel(); isListening = false } }, Modifier.weight(1f),
                            placeholder = { Text(if (status is AgentStatus.WaitingForUser) "Answer…" else if (hasContext()) "ask about this screen" else "ask ubiq") },
                            shape = RoundedCornerShape(24.dp), maxLines = 3, colors = com.agentos.ui.brandFieldColors())
                        Spacer(Modifier.width(6.dp))
                        com.agentos.ui.MicButton(isListening, onStart = { listen() }, onStop = { voice?.stop() })
                        // While it works: Send queues the message for the agent's next step; Stop shows when there is nothing to send.
                        if (status is AgentStatus.Running && input.isBlank()) {
                            com.agentos.ui.IconKey(Icons.Default.Stop, "Stop", { s.cancel(com.agentos.agent.Session.CANCEL_SHEET) }, com.agentos.ui.KeyColor.Red)
                        } else com.agentos.ui.IconKey(Icons.Default.Send, "Send", { val t = input; input = ""; send(t) }, com.agentos.ui.KeyColor.Yellow)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        com.agentos.ui.TextKey("open conversation", { openFull() }, icon = Icons.Default.OpenInFull)
                    }
                }
            }
        }
    }

    companion object {
        private const val TAG = "AgentOS/Assist"

        /** Visible text of the app under the sheet, in reading order, de-duplicated. */
        fun readText(structure: AssistStructure): String {
            val seen = LinkedHashSet<String>()
            fun walk(n: AssistStructure.ViewNode, depth: Int) {
                if (depth > 60 || seen.size > 400) return
                if (n.visibility == View.VISIBLE) {
                    n.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { seen += it.take(500) }
                    if (n.text.isNullOrBlank()) n.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { seen += it.take(200) }
                    for (i in 0 until n.childCount) walk(n.getChildAt(i), depth + 1)
                }
            }
            for (i in 0 until structure.windowNodeCount) walk(structure.getWindowNodeAt(i).rootViewNode, 0)
            return seen.joinToString("\n")
        }
    }
}

@Composable
private fun ContextChip(app: String, shot: Bitmap?, on: Boolean, toggle: () -> Unit) {
    Surface(shape = RoundedCornerShape(14.dp), color = if (on) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.surfaceVariant) {
        Row(Modifier.padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
            if (shot != null) Image(shot.asImageBitmap(), null, Modifier.size(width = 30.dp, height = 52.dp).clip(RoundedCornerShape(6.dp)), contentScale = ContentScale.Crop)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(if (on) "About this screen" else "Not using the screen", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                Text(if (on) "$app · I can see and act on it" else "Tap to include $app", style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            IconButton(onClick = toggle) { Icon(if (on) Icons.Default.Close else Icons.Default.OpenInFull, if (on) "Remove screen context" else "Use screen context") }
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun SheetLine(item: ChatItem, live: Boolean = false, onOption: (String) -> Unit = {}) {
    when (item.role) {
        Role.ACTION -> {}
        // The sheet stays compact: the task's card, with live state, is in the Ubiq chat.
        Role.TASK -> Text("Started in the background: ${item.text}. Its result comes back in Ubiq.", Modifier.padding(6.dp),
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Role.SYSTEM -> Text(item.text, Modifier.padding(6.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        // Your words on a raised cap, Ubiq's as plain text (the same as the conversation).
        Role.USER -> Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), horizontalArrangement = Arrangement.End) {
            val p = com.agentos.ui.plate
            Text(item.text, Modifier.widthIn(max = 300.dp).then(with(com.agentos.ui.UbiqParts) { raisedCap(p.cap, p.capEdge) })
                .padding(horizontal = 13.dp, vertical = 8.dp), color = p.capText, style = MaterialTheme.typography.bodyMedium)
        }
        Role.ASSISTANT -> Column(Modifier.fillMaxWidth().padding(vertical = 4.dp, horizontal = 4.dp)) {
            com.agentos.ui.MarkdownText(item.text, color = MaterialTheme.colorScheme.onSurface)
            if (live) androidx.compose.foundation.layout.FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                item.options.forEachIndexed { i, o -> com.agentos.ui.Key(o, { onOption(o) }, color = if (i == 0) com.agentos.ui.KeyColor.Yellow else com.agentos.ui.KeyColor.Light, compact = true) }
            }
        }
    }
}
