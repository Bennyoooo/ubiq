package com.agentos.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import com.agentos.agent.Attachment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.runtime.DisposableEffect
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.layout.onGloballyPositioned
import com.agentos.workspace.Workspace
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.focus.focusRequester
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import kotlinx.coroutines.delay
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.agentos.agent.AgentRuntime
import com.agentos.agent.AgentStatus
import com.agentos.agent.AppEntry
import com.agentos.agent.ChatItem
import com.agentos.agent.Role
import com.agentos.control.ControlService
import com.agentos.data.Prefs
import com.agentos.voice.Speaker
import com.agentos.voice.VoiceInput
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * The app's one activity: the conversation with Ubiq, the other chats and tasks, Space, and the
 * live view of the agent's hidden screen. A phone OS build that adds a [HomeShell] also makes it
 * the home screen; then it opens on home, and leaving the main conversation goes back there.
 */
class HomeActivity : ComponentActivity() {
    private val startVoice = mutableStateOf(false)
    /** Session whose hidden desk the live viewer shows, or null. */
    private val showWorkspace = mutableStateOf<String?>(null)
    private val shell = HomeShell.installed
    private val screen = mutableStateOf(if (shell != null) Screen.HOME else Screen.CHAT)
    /** Bumped by each press of Home, so the home screen leaves its own pages. */
    private val homeReset = androidx.compose.runtime.mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // A launch with no display named goes to the focused display, which can be a hidden desk: the chat would
        // open where nobody can see it. Send it to the real screen instead.
        if ((display?.displayId ?: android.view.Display.DEFAULT_DISPLAY) != android.view.Display.DEFAULT_DISPLAY) {
            val again = Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            finish()
            try { startActivity(again, android.app.ActivityOptions.makeBasic().setLaunchDisplayId(android.view.Display.DEFAULT_DISPLAY).toBundle()) } catch (t: Throwable) { android.util.Log.w("AgentOS/Home", "move to the real screen", t) }
            return
        }
        enableEdgeToEdge()
        handleIntent(intent)
        setContent { AgentOsTheme { HomeScreen(shell, screen, startVoice, showWorkspace, homeReset.intValue) } }
        // Android 13+ shows no notifications until the app asks: without this, task progress,
        // results and approvals from background tasks never reach the owner.
        if (android.os.Build.VERSION.SDK_INT >= 33 && savedInstanceState == null &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
            notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
    }

    private val notificationPermission = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { }

    // Home shows the running tasks itself; the island and notification are for when the user is elsewhere.
    override fun onResume() {
        super.onResume(); isInForeground = true
        shell?.onResume(this)
        com.agentos.voice.WakeWord.sync(this)  // a microphone service may only start while Ubiq is in front
        com.agentos.control.Island.setHome(screen.value == Screen.HOME)
        if (screen.value == Screen.CHAT) com.agentos.control.Island.seen(AgentRuntime.focused.value.id)
    }
    override fun onPause() { super.onPause(); isInForeground = false; com.agentos.control.Island.setHome(false) }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val peek = intent?.getBooleanExtra(EXTRA_SHOW_WORKSPACE, false) == true
        intent?.getStringExtra(EXTRA_SESSION)?.let { id ->
            // Peek shows a task's screen over the chat it reports to, so closing it lands there, not in the task's own chat.
            if (!peek) AgentRuntime.focus(id)
            else AgentRuntime.session(id)?.parentId?.let { parent -> AgentRuntime.focus(parent); screen.value = Screen.CHAT }
        }
        if (peek) showWorkspace.value = intent?.getStringExtra(EXTRA_SESSION)
            ?: AgentRuntime.active().firstOrNull { it.desk?.owner == it.id }?.id ?: AgentRuntime.focused.value.id
        if (intent?.getBooleanExtra(EXTRA_OPEN_CHAT, false) == true) screen.value = Screen.CHAT
        when (intent?.action) {
            // Pressing Home always lands on the home page itself.
            Intent.ACTION_MAIN -> if (shell != null && intent.hasCategory(Intent.CATEGORY_HOME) && !intent.getBooleanExtra(EXTRA_OPEN_CHAT, false)) {
                screen.value = Screen.HOME; showWorkspace.value = null; homeReset.intValue++
            }
            Intent.ACTION_ASSIST, Intent.ACTION_VOICE_COMMAND -> { AgentRuntime.focusMain(); screen.value = Screen.CHAT; startVoice.value = true }
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)?.let {
                screen.value = Screen.CHAT
                AgentRuntime.toMain("The user shared this with you:\n$it")
            }
        }
    }

    companion object {
        const val EXTRA_SHOW_WORKSPACE = "show_workspace"
        const val EXTRA_OPEN_CHAT = "open_chat"
        /** Which session to show (for EXTRA_OPEN_CHAT / EXTRA_SHOW_WORKSPACE). */
        const val EXTRA_SESSION = "session"
        /** True while the app is resumed; the status overlay stays out of the way then. */
        @Volatile var isInForeground = false
            private set

        /** Back to Ubiq on the conversation (after a task that used the visible screen). */
        fun bringToFront(ctx: Context) {
            ctx.startActivity(Intent(ctx, HomeActivity::class.java).putExtra(EXTRA_OPEN_CHAT, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        }

        /** The assistant when the system sheet is unavailable: the chat, listening. */
        fun openAssistant(ctx: Context) {
            ctx.startActivity(Intent(ctx, HomeActivity::class.java).setAction(Intent.ACTION_ASSIST)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        }
    }
}

/** HOME only exists with a [HomeShell]. */
internal enum class Screen { HOME, CHAT, SESSIONS, SEARCH, SPACE }

/** Words searched for from the Search screen, and the chat opened from it: that chat marks them and steps through them. */
internal object ChatFind {
    data class Find(val sessionId: String, val query: String)
    val current = mutableStateOf<Find?>(null)
}

/** A message the search looks at: what the user and Ubiq said, as read (markdown stripped). */
private fun findable(it: ChatItem, q: String) =
    (it.role == Role.ASSISTANT || it.role == Role.USER) && com.agentos.agent.plainText(it.text).contains(q, ignoreCase = true)

@Composable
private fun HomeScreen(shell: HomeShell?, screen: androidx.compose.runtime.MutableState<Screen>, startVoice: androidx.compose.runtime.MutableState<Boolean>,
                       showWorkspace: androidx.compose.runtime.MutableState<String?>, homeReset: Int) {
    LaunchedEffect(screen.value) { if (HomeActivity.isInForeground) com.agentos.control.Island.setHome(screen.value == Screen.HOME) }
    val chat = { AgentRuntime.focusMain(); screen.value = Screen.CHAT }
    // Every screen sits on the plate: the phone itself is the control panel, so no wallpaper.
    Box(Modifier.fillMaxSize().plateBackground()) {
        when (screen.value) {
            Screen.HOME -> if (shell != null) shell.Content(ShellNav(
                // The bar, the mic and dragging right all go to the main conversation.
                chat = chat,
                voice = { chat(); startVoice.value = true },
                open = { id -> AgentRuntime.focus(id); screen.value = Screen.CHAT },
            ), homeReset) else LaunchedEffect(Unit) { screen.value = Screen.CHAT }
            Screen.CHAT -> {
                // Back from a side chat or a task leads to the main conversation; from there, home (or out of the app).
                val main = AgentRuntime.focused.collectAsStateWithLifecycle().value.isMain
                val back = { if (!main) AgentRuntime.focusMain() else if (shell != null) screen.value = Screen.HOME }
                androidx.activity.compose.BackHandler(enabled = !main || shell != null) { back() }
                AssistantTab(startVoice, onWatch = { id -> showWorkspace.value = id }, onBack = back,
                    onSessions = { screen.value = Screen.SESSIONS }, onSearch = { screen.value = Screen.SEARCH }, onSpace = { screen.value = Screen.SPACE })
            }
            Screen.SESSIONS, Screen.SEARCH -> {
                androidx.activity.compose.BackHandler { screen.value = Screen.CHAT }
                SessionsScreen(onBack = { screen.value = Screen.CHAT }, onOpen = { id -> AgentRuntime.focus(id); screen.value = Screen.CHAT },
                    searching = screen.value == Screen.SEARCH)
            }
            Screen.SPACE -> {
                androidx.activity.compose.BackHandler { screen.value = Screen.CHAT }
                SpaceScreen(onBack = { screen.value = Screen.CHAT }, onOpenChat = { id -> AgentRuntime.focus(id); screen.value = Screen.CHAT })
            }
        }
        // Over whatever shows (the chat dims behind it), never in its flow.
        SetupCard()
        // Drawn over everything (not a Dialog: a dialog window gave the content unbounded height).
        showWorkspace.value?.let { id -> AgentRuntime.session(id)?.let { WorkspaceViewer(it, onClose = { showWorkspace.value = null }) } ?: run { showWorkspace.value = null } }
    }
}

/**
 * The main conversation, then the side chats the user opened, then background tasks and job runs (running first).
 * Tap to open, long-press to delete (not the main conversation). With [searching] (the chat's search glyph) a field
 * opens focused and filters by title and message text; a hit inside a message shows that message as the preview.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SessionsScreen(onBack: () -> Unit, onOpen: (String) -> Unit, searching: Boolean = false) {
    val sessions by AgentRuntime.sessions.collectAsStateWithLifecycle()
    var toDelete by remember { mutableStateOf<com.agentos.agent.Session?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    val q = query.trim()
    fun shown(s: com.agentos.agent.Session) = s.isActive || s.items.value.isNotEmpty()
    val side = sessions.filter { it.isSideChat && shown(it) }
    val tasks = sessions.filter { !it.isMain && !it.isSideChat && shown(it) }.let { t -> t.filter { it.isActive } + t.filter { !it.isActive } }
    val all = sessions.filter { it.isMain } + side.filter { it.isActive } + side.filter { !it.isActive } + tasks
    // The newest message that matches, per chat (null: only the title matched, or no query).
    // Every matching message per chat: the newest is the preview, the count says how many more there are.
    val hits = if (q.isEmpty()) emptyMap() else all.associateWith { s -> s.items.value.filter { findable(it, q) } }
    val ordered = if (q.isEmpty()) all else all.filter { it.title.value.contains(q, ignoreCase = true) || !hits[it].isNullOrEmpty() }
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    if (searching) LaunchedEffect(Unit) { focus.requestFocus() }
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
        ScreenHeader(if (searching) "Search" else "Chats", onBack, Icons.AutoMirrored.Filled.ArrowBack) {
            BubbleIcon(Icons.Default.Add, "New side chat", { AgentRuntime.newSideChat(); onOpen(AgentRuntime.focused.value.id) },
                container = MaterialTheme.colorScheme.primaryContainer, tint = MaterialTheme.colorScheme.onPrimaryContainer)
        }
        if (searching) {
            OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
                .focusRequester(focus), placeholder = { Text("Search your chats") }, singleLine = true,
                shape = RoundedCornerShape(26.dp), colors = brandFieldColors(),
                leadingIcon = { GlyphIcon(Glyph.Search, size = 18.dp) },
                trailingIcon = if (query.isNotEmpty()) ({ BubbleIcon(Icons.Default.Close, "Clear search", { query = "" }, size = 32.dp) }) else null)
            if (q.isNotEmpty() && ordered.isEmpty()) Text("No chats mention \"$q\".", Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 4.dp)) {
            items(ordered, key = { it.id }) { s ->
                val status by s.status.collectAsStateWithLifecycle()
                val title by s.title.collectAsStateWithLifecycle()
                Surface(
                    Modifier.fillMaxWidth().padding(vertical = 5.dp).clip(PebbleShape).combinedClickable(onClick = {
                        // Opened from a search with hits in its messages: the chat opens on them, marked.
                        ChatFind.current.value = if (q.isNotEmpty() && !hits[s].isNullOrEmpty()) ChatFind.Find(s.id, q) else null
                        onOpen(s.id)
                    }, onLongClick = { if (!s.isMain) toDelete = s }),
                    shape = PebbleShape, color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
                ) {
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        StatusLed(status)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                val found = hits[s]?.size ?: 0
                                Text(when {
                                    found > 1 -> "$found matches"
                                    status is AgentStatus.Running -> "Working"; status is AgentStatus.WaitingForUser -> "Needs you"
                                    else -> android.text.format.DateUtils.getRelativeTimeSpanString(s.updated).toString() },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (status is AgentStatus.Idle && found <= 1) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary)
                            }
                            val last = (hits[s]?.lastOrNull() ?: s.items.value.lastOrNull { it.role == Role.ASSISTANT || it.role == Role.USER })?.text.orEmpty()
                            if (last.isNotBlank()) Text(snippet(com.agentos.agent.plainText(last), q), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
    toDelete?.let { s ->
        AlertDialog(onDismissRequest = { toDelete = null }, title = { Text("Delete this chat?") },
            text = { Text(if (s.isActive) "Its task is still running; deleting stops it." else "\"${s.title.value}\" will be removed.") },
            confirmButton = { TextButton(onClick = { AgentRuntime.delete(s.id); toDelete = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Cancel") } })
    }
}

/** A long message cut so the matched words are in view (the preview shows two lines). */
private fun snippet(text: String, q: String): String {
    val i = if (q.isEmpty()) -1 else text.indexOf(q, ignoreCase = true)
    return if (i <= 40) text else "…" + text.substring(i - 30).trimStart()
}

@Composable
private fun SetupCard() {
    val ctx = LocalContext.current
    // Re-check every second: the service binds asynchronously and settings change in other screens.
    // Only after 3 s missing: right after an update the service takes a moment to bind again.
    val missing by produceState(initialValue = false) {
        var since = 0L
        while (true) {
            val ok = Prefs.isConfigured(ctx) && ControlService.isRunning
            val now = System.currentTimeMillis()
            since = if (ok) 0L else if (since == 0L) now else since
            value = since != 0L && now - since >= 3000
            delay(1000)
        }
    }
    var later by rememberSaveable { mutableStateOf(false) }
    if (!missing || later) return
    val configured = Prefs.isConfigured(ctx)
    val control = ControlService.isRunning
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f))
        .pointerInput(Unit) { detectTapGestures { } }  // a tap outside the card does nothing to home behind it
        .navigationBarsPadding().padding(16.dp), contentAlignment = Alignment.BottomCenter) {
        Pebble(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Led(Ubiq.Yellow)
                Spacer(Modifier.width(12.dp))
                Text("A couple of things before we start", style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.height(8.dp))
            if (!configured) {
                Text("1. Add your OpenAI API key so the assistant can think.", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                PillButton("Open settings", { ctx.startActivity(Intent(ctx, SettingsActivity::class.java)) })
                Spacer(Modifier.height(8.dp))
            }
            if (!control) {
                Text("${if (configured) 1 else 2}. Turn on \"Ubiq Agent Control\" under Accessibility so it can operate apps.", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                PillButton("Open accessibility settings", { ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) })
                // Android 13+ greys out accessibility for apps installed from a downloaded APK ("Restricted
                // setting") until the owner allows it from the app's info screen.
                if (android.os.Build.VERSION.SDK_INT >= 33) {
                    Spacer(Modifier.height(10.dp))
                    Text("Switch greyed out, or a \"Restricted setting\" message? Open App info, tap ⋮ in the top corner, choose " +
                        "\"Allow restricted settings\", then come back and turn it on.", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    SoftPillButton("Open App info", {
                        ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.fromParts("package", ctx.packageName, null))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    })
                }
            }
            Spacer(Modifier.height(4.dp))
            TextKey("later", { later = true }, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * Full-screen live view of the hidden workspace display. Watch the agent, stop it, or take over:
 * in take-over mode your touches, Back/Home/Enter and typed text go to the workspace while the
 * agent waits; "Hand back" lets it continue from whatever you changed.
 */
@Composable
private fun WorkspaceViewer(session: com.agentos.agent.Session, onClose: () -> Unit) {
    val status by session.status.collectAsStateWithLifecycle()
    val ws = remember(session, status) { session.desk } ?: Workspace.desks.first()
    androidx.compose.runtime.key(ws.slot) { DeskViewer(session, ws, status, onClose) }
}

@Composable
private fun DeskViewer(session: com.agentos.agent.Session, desk: com.agentos.workspace.Desk, status: AgentStatus, onClose: () -> Unit) {
    val frame by desk.frame.collectAsStateWithLifecycle()
    val active by desk.active.collectAsStateWithLifecycle()
    val controlling by session.userControl.collectAsStateWithLifecycle()
    val hasDisplay by desk.hasDisplay.collectAsStateWithLifecycle()
    val title by session.title.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var typing by remember { mutableStateOf(false) }
    var typed by remember { mutableStateOf("") }
    DisposableEffect(Unit) { desk.addViewer(); onDispose { desk.removeViewer(); session.setUserControl(false) } }
    androidx.activity.compose.BackHandler { onClose() }
    // When a demo ends the live view steps aside, so the take ends on the answer.
    val demosFinished by session.demosFinished.collectAsStateWithLifecycle()
    val demosAtOpen = remember { demosFinished }
    LaunchedEffect(demosFinished) { if (demosFinished > demosAtOpen) onClose() }
    run {
        Surface(Modifier.fillMaxSize(), color = Brand.Night) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        FitText(when { controlling -> "$title · you are in control"; active -> "$title · live"; else -> "$title · idle" }, color = Color.White,
                            style = MaterialTheme.typography.titleLarge)
                        val note = (status as? AgentStatus.Running)?.note
                        if (note != null) Text(note, color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (active && !session.demoRunning) {
                        Button(onClick = { session.cancel(com.agentos.agent.Session.CANCEL_VIEWER) }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text("Stop") }
                        Spacer(Modifier.width(8.dp))
                    }
                    IconButton(onClick = onClose) { Icon(Icons.Default.Close, "Close", tint = Color.White) }
                }
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    val f = frame
                    if (f != null) {
                        var bounds by remember { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }
                        Image(f.asImageBitmap(), "Workspace", Modifier.fillMaxSize().padding(8.dp).clip(RoundedCornerShape(16.dp))
                            .onGloballyPositioned { bounds = it.boundsInParent() }, contentScale = ContentScale.Fit)
                        if (controlling) {
                            val fw = f.width.toFloat(); val fh = f.height.toFloat()
                            // Map a touch on the (letterboxed) image to display coordinates and forward it.
                            fun map(p: Offset): Offset? {
                                val b = bounds ?: return null
                                val scale = minOf(b.width / fw, b.height / fh)
                                val ox = b.left + (b.width - fw * scale) / 2f; val oy = b.top + (b.height - fh * scale) / 2f
                                val x = (p.x - ox) / scale; val y = (p.y - oy) / scale
                                return if (x < 0 || y < 0 || x > fw || y > fh) null else Offset(x, y)
                            }
                            Box(Modifier.matchParentSize().pointerInput(fw, fh) {
                                awaitEachGesture {
                                    val down = awaitFirstDown(requireUnconsumed = false)
                                    val downTime = android.os.SystemClock.uptimeMillis()
                                    val start = map(down.position)
                                    if (start != null) desk.touch(android.view.MotionEvent.ACTION_DOWN, start.x, start.y, downTime, downTime)
                                    down.consume()
                                    var last = start
                                    while (true) {
                                        val ev = awaitPointerEvent()
                                        val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                                        val now = android.os.SystemClock.uptimeMillis()
                                        val pos = map(ch.position) ?: last
                                        if (!ch.pressed) {
                                            if (start != null && pos != null) desk.touch(android.view.MotionEvent.ACTION_UP, pos.x, pos.y, downTime, now)
                                            ch.consume(); break
                                        }
                                        if (ch.positionChanged() && start != null && pos != null) { desk.touch(android.view.MotionEvent.ACTION_MOVE, pos.x, pos.y, downTime, now); last = pos }
                                        ch.consume()
                                    }
                                }
                            })
                        }
                    } else Text(if (desk.displayId >= 0) "Waiting for the first frame…" else "The workspace is not running. It starts with the next task.", color = Color.White.copy(alpha = 0.7f), textAlign = TextAlign.Center, modifier = Modifier.padding(32.dp))
                }
                if (controlling) {
                    if (typing) Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(typed, { typed = it }, Modifier.weight(1f), placeholder = { Text("Type into the focused field") }, singleLine = true,
                            colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White, focusedBorderColor = Color.White, unfocusedBorderColor = Color.Gray, focusedPlaceholderColor = Color.Gray, unfocusedPlaceholderColor = Color.Gray))
                        IconButton(onClick = { val t = typed; typed = ""; typing = false; scope.launch(Dispatchers.IO) { desk.typeText(t) } }) { Icon(Icons.Default.Send, "Type", tint = Color.White) }
                    }
                    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { scope.launch(Dispatchers.IO) { desk.back() } }) { Text("Back", color = Color.White) }
                        TextButton(onClick = { desk.home() }) { Text("Home", color = Color.White) }
                        TextButton(onClick = { typing = !typing }) { Text("Type", color = Color.White) }
                        TextButton(onClick = { scope.launch(Dispatchers.IO) { desk.enter() } }) { Text("Enter", color = Color.White) }
                        PillButton("Hand back", { session.setUserControl(false); typing = false })
                    }
                } else Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (hasDisplay) { PillButton("Take over", { session.setUserControl(true) }); Spacer(Modifier.width(12.dp)) }
                    Text(if (hasDisplay) "The agent's own screen; yours is unaffected. Take over to use it yourself, the agent waits."
                        else "The agent's own screen appears here once a background task starts.",
                        Modifier.weight(1f), color = Color.White.copy(alpha = 0.6f), style = MaterialTheme.typography.labelSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun AssistantTab(startVoice: androidx.compose.runtime.MutableState<Boolean>, onWatch: (String) -> Unit, onBack: () -> Unit, onSessions: () -> Unit, onSearch: () -> Unit, onSpace: () -> Unit) {
    val ctx = LocalContext.current
    val items by AgentRuntime.items.collectAsStateWithLifecycle()
    val status by AgentRuntime.status.collectAsStateWithLifecycle()
    val session by AgentRuntime.focused.collectAsStateWithLifecycle()
    val title by session.title.collectAsStateWithLifecycle()
    val running by AgentRuntime.sessions.collectAsStateWithLifecycle()
    val otherActive = running.count { it.isActive && it !== session }
    // Re-read when the status changes: the desk is acquired and released with each task.
    val desk = remember(session, status) { session.desk }
    val workspaceActive = desk != null && desk.owner == session.id
    val hasDisplay = desk != null
    var input by remember { mutableStateOf("") }
    var pending by remember { mutableStateOf<List<Attachment>>(emptyList()) }
    var attaching by remember { mutableStateOf(false) }
    var attachMenu by remember { mutableStateOf(false) }
    var listening by remember { mutableStateOf(false) }
    var partial by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    var voiceJob by remember { mutableStateOf<Job?>(null) }
    var voice by remember { mutableStateOf<VoiceInput?>(null) }
    val listState = rememberLazyListState()

    var replyTo by remember { mutableStateOf<ChatItem?>(null) }
    fun send(text: String, byVoice: Boolean = false) {
        if (text.isBlank() && pending.isEmpty()) return
        Speaker.stop()
        // A swiped reply tells the model which message it answers; the chat shows only the user's words.
        val quoted = replyTo?.let { "(Replying to your message: \"${com.agentos.agent.plainText(it.text).take(300)}\")\n$text" }
        AgentRuntime.send(text, pending, modelText = quoted, byVoice = byVoice)
        input = ""; pending = emptyList(); replyTo = null
    }

    fun addUris(uris: List<android.net.Uri>) {
        if (uris.isEmpty()) return
        attaching = true
        scope.launch {
            val loaded = withContext(Dispatchers.IO) {
                uris.mapNotNull { u -> try { Attachment.load(ctx, u) } catch (t: Throwable) { AgentRuntime.systemNote("Could not attach: ${t.message}"); null } }
            }
            pending = pending + loaded
            attaching = false
        }
    }
    val pickPhotos = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(5)) { addUris(it) }
    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { addUris(it) }
    // Camera button: shoot with the system camera, the photo lands in the pending attachments.
    var captureUri by remember { mutableStateOf<android.net.Uri?>(null) }
    val takePhoto = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok -> if (ok) captureUri?.let { addUris(listOf(it)) }; captureUri = null }
    fun openCamera() {
        val dir = java.io.File(ctx.cacheDir, "captures").apply { mkdirs() }
        val uri = androidx.core.content.FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", java.io.File(dir, "photo_${System.currentTimeMillis()}.jpg"))
        captureUri = uri
        try { takePhoto.launch(uri) } catch (t: Throwable) { captureUri = null; AgentRuntime.systemNote("No camera app available: ${t.message}") }
    }
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> if (ok) openCamera() else AgentRuntime.systemNote("Camera permission was denied.") }
    fun cameraTap() {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) openCamera()
        else cameraPermission.launch(Manifest.permission.CAMERA)
    }

    // Dictation runs until the mic is tapped again (or released, when held); then it is sent.
    fun startListening() {
        if (listening) return
        val vi = VoiceInput(ctx)
        if (!vi.available) { AgentRuntime.systemNote("Voice recognition is not available on this device."); return }
        Speaker.stop()
        com.agentos.agent.PiRuntime.prewarm(ctx)
        listening = true; partial = ""; voice = vi
        voiceJob = scope.launch {
            val result = try { vi.dictate(onText = { partial = it }) } finally { listening = false; voice = null }
            partial = ""
            if (!result.isNullOrBlank()) send(result, byVoice = true)
        }
    }
    fun stopListening() { voice?.stop() }

    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> if (ok) startListening() }
    fun micTap() {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) startListening()
        else micPermission.launch(Manifest.permission.RECORD_AUDIO)
    }

    LaunchedEffect(startVoice.value) { if (startVoice.value) { startVoice.value = false; micTap() } }
    // Leaving Ubiq ends dictation (and sends what was said): the mic should not stay open behind another app.
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val o = androidx.lifecycle.LifecycleEventObserver { _, e -> if (e == androidx.lifecycle.Lifecycle.Event.ON_STOP) voice?.stop() }
        lifecycle.addObserver(o); onDispose { lifecycle.removeObserver(o) }
    }
    // A search this chat was opened from; it ends when another chat is shown.
    val find = ChatFind.current.value?.takeIf { it.sessionId == session.id }?.query
    LaunchedEffect(session.id) { if (ChatFind.current.value?.sessionId != session.id) ChatFind.current.value = null }
    LaunchedEffect(items.size, status) { if (items.isNotEmpty() && find == null) listState.animateScrollToItem(items.size - 1) }
    // Reading the conversation clears its green light in the island.
    LaunchedEffect(session.id, items.size) { if (HomeActivity.isInForeground) com.agentos.control.Island.seen(session.id) }

    Column(Modifier.fillMaxSize().statusBarsPadding().imePadding()) {
        // The prototype's header: the thread's name with a caret (your chats), then search and notes.
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 10.dp, top = 2.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            // A side chat or a task leads back to the main conversation.
            // Just the arrow: the words "main" took the room the chat's own name needs.
            if (!session.isMain) { TextKey("‹", onBack, Modifier.semantics { contentDescription = "Back to the main chat" }); Spacer(Modifier.width(4.dp)) }
            Row(Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).clickable(onClick = onSessions).heightIn(min = 36.dp),
                verticalAlignment = Alignment.CenterVertically) {
                FitText((if (session.isSideChat && items.isEmpty()) "side chat" else title).lowercase(), Modifier.weight(1f, fill = false),
                    style = MaterialTheme.typography.titleLarge, minScale = 0.75f)
                Spacer(Modifier.width(7.dp))
                // The caret turns yellow while other chats are running or need you.
                val caret = if (otherActive > 0) Ubiq.Yellow else MaterialTheme.colorScheme.onBackground
                androidx.compose.foundation.Canvas(Modifier.size(10.dp, 6.dp)) {
                    drawPath(androidx.compose.ui.graphics.Path().apply { moveTo(0f, 0f); lineTo(size.width, 0f); lineTo(size.width / 2, size.height); close() }, caret)
                }
            }
            if (hasDisplay && !workspaceActive) BubbleIcon(Icons.Default.Visibility, "Agent workspace", { onWatch(session.id) })
            Spacer(Modifier.width(6.dp))
            // Live mode: a phone call with Ubiq, which works through this main conversation.
            BubbleIcon(Icons.Default.Call, "Call Ubiq", { Speaker.stop(); com.agentos.voice.LiveCallActivity.open(ctx) }, size = 34.dp)
            GlyphButton(Glyph.Search, "Search your chats", onSearch, size = 19.dp, target = 34.dp)
            TextKey("+", { AgentRuntime.newSideChat() }, Modifier.semantics { contentDescription = "New side chat" })
            Spacer(Modifier.width(8.dp))
            GlyphButton(Glyph.Notes, "Notes, memory, jobs and settings", onSpace, size = 19.dp, target = 34.dp)
        }
        // Background task cards are not drawn: the tray above shows a task while it runs, and its result or question comes as a message.
        val shown = items.filter { it.role != Role.ACTION && it.role != Role.TASK }
        // The matching messages, oldest first; the chat opens on the newest and the arrows step through them.
        val matches = if (find == null) emptyList() else shown.indices.filter { findable(shown[it], find) }
        var at by remember(session.id, find) { mutableIntStateOf(-1) }
        if (find != null && matches.isNotEmpty() && at !in matches.indices) at = matches.size - 1
        LaunchedEffect(find, at, matches.size) { matches.getOrNull(at)?.let { listState.animateScrollToItem(it) } }
        // Every question still open keeps its keys inside its message, however far back: this chat's own, and each
        // background task's (answered by tapping, from anywhere in the scroll; the input stays free for new messages).
        val ownAsk = (if (status is AgentStatus.WaitingForUser) shown.lastOrNull { it.role == Role.ASSISTANT && it.options.isNotEmpty() && it.taskId == null } else null)?.id
            ?: shown.lastOrNull { it.role == Role.ASSISTANT || it.role == Role.USER }?.takeIf { it.role == Role.ASSISTANT && it.options.isNotEmpty() && it.taskId == null }?.id
        val liveAsks = setOfNotNull(ownAsk) + shown.filter { it.role == Role.ASSISTANT && it.options.isNotEmpty() && it.taskId != null }
            .groupBy { it.taskId }.mapNotNull { (tid, l) -> l.last().id.takeIf { AgentRuntime.session(tid!!)?.status?.value is AgentStatus.WaitingForUser } }
        // A waiting row at the top scrolls back to its question.
        val findAsk: (String?) -> Unit = { tid ->
            val target = if (tid == null) ownAsk else shown.lastOrNull { it.taskId == tid && it.options.isNotEmpty() }?.id
            val i = shown.indexOfFirst { it.id == target }
            if (i >= 0) scope.launch { listState.animateScrollToItem(i) } else if (tid != null) AgentRuntime.focus(tid)
        }
        // Work in flight is pinned on top, out of the conversation's way: this chat's own, then its parallel tasks.
        // A spinner while running, a yellow dot while it waits on you; a row goes away when its task is done.
        val runTitle = if (session.demoRunning) session.lastDemo?.let { com.agentos.agent.Playbooks.byName(it)?.title } else null
        when (val st = status) {
            is AgentStatus.Running -> TaskRow(runTitle, st.note, running = true, onWatch = if (workspaceActive) ({ onWatch(session.id) }) else null)
            is AgentStatus.WaitingForUser -> TaskRow(runTitle ?: "ubiq", "waiting for your answer", running = false, waiting = true, onWatch = null,
                onOpen = { findAsk(null) })
            else -> {}
        }
        Tray(session, onWatch = onWatch, onFind = { findAsk(it) })
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState, contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 14.dp, end = 14.dp, top = 6.dp, bottom = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (items.isEmpty()) item { EmptyHint() }
            // Steps (ACTION) are not drawn: as rows they still took the list's spacing, a blank gap per step.
            items(shown, key = { it.id }) {
                val mark = if (find != null && findable(it, find)) find else null
                Bubble(it, live = it.id in liveAsks, onOption = { o -> session.answer(it, o) }, onReply = { r -> replyTo = r },
                    onDelete = { d -> if (replyTo?.id == d.id) replyTo = null; session.deleteItem(d.id) },
                    mark = mark, markCurrent = mark != null && shown.getOrNull(matches.getOrNull(at) ?: -1)?.id == it.id)
            }
        }
        if (find != null) FindBar(find, at, matches.size,
            onUp = { if (at > 0) at-- }, onDown = { if (at < matches.size - 1) at++ },
            onClose = { ChatFind.current.value = null; scope.launch { if (shown.isNotEmpty()) listState.animateScrollToItem(shown.size - 1) } })
        replyTo?.let { r ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(3.dp).height(30.dp).clip(RoundedCornerShape(2.dp)).background(Ubiq.Yellow))
                Spacer(Modifier.width(10.dp))
                Text(com.agentos.agent.plainText(r.text), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                BubbleIcon(Icons.Default.Close, "Cancel reply", { replyTo = null }, size = 36.dp)
            }
        }
        if (listening) {
            Text(if (partial.isBlank()) "listening… tap the mic to send" else partial, Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
        }
        if (pending.isNotEmpty() || attaching) {
            LazyRow(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(pending, key = { it.hashCode() }) { a -> PendingAttachment(a, onRemove = { pending = pending - a }) }
                if (attaching) item { Row(Modifier.height(56.dp), verticalAlignment = Alignment.CenterVertically) { StatusLed(AgentStatus.Running(0, "")); Spacer(Modifier.width(8.dp)); Text("Loading…", style = MaterialTheme.typography.bodySmall) } }
            }
        }
        // The composer is one recessed slot (the prototype's): attach, the text, the red mic, and a blue
        // send key once there is something to send. While Ubiq works and nothing is typed, stop.
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 12.dp)
            .slot(MaterialTheme.colorScheme.surfaceVariant, plate.plateEdge, 17.dp)
            .padding(start = 4.dp, end = 5.dp, top = 4.dp, bottom = 4.dp).heightIn(min = 34.dp), verticalAlignment = Alignment.CenterVertically) {
            // The + turns into an x while the attach row (above) is open.
            Box {
                BubbleIcon(if (attachMenu) Icons.Default.Close else Icons.Default.Add, if (attachMenu) "Close" else "Attach", { attachMenu = !attachMenu },
                    size = 30.dp, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                // Attach: a small Ubiq list floating over the chat, just above the + (not a system menu; user feedback 2026-11-09).
                if (attachMenu) {
                    val lift = with(androidx.compose.ui.platform.LocalDensity.current) { (30 + 16).dp.roundToPx() }
                    androidx.compose.ui.window.Popup(alignment = Alignment.BottomStart, offset = androidx.compose.ui.unit.IntOffset(0, -lift),
                        onDismissRequest = { attachMenu = false }, properties = androidx.compose.ui.window.PopupProperties(focusable = true)) {
                        AttachList(
                            onCamera = { attachMenu = false; cameraTap() },
                            onPhotos = { attachMenu = false; pickPhotos.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                            onFile = { attachMenu = false; pickFiles.launch(arrayOf("*/*")) })
                    }
                }
            }
            Box(Modifier.weight(1f).padding(vertical = 5.dp), contentAlignment = Alignment.CenterStart) {
                if (input.isEmpty()) Text(if (status is AgentStatus.WaitingForUser) "answer ubiq" else if (pending.isNotEmpty()) "say what to do with it"
                    else if (status is AgentStatus.Running) "add to the task" else "message ubiq",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                androidx.compose.foundation.text.BasicTextField(input, { input = it; if (listening) voiceJob?.cancel() }, Modifier.fillMaxWidth(), maxLines = 4,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onBackground),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(Ubiq.Blue))
            }
            MicButton(listening, onStart = { micTap() }, onStop = { stopListening() })
            if (input.isNotBlank() || pending.isNotEmpty()) {
                Spacer(Modifier.width(4.dp))
                Key("send", { send(input) }, color = KeyColor.Blue, compact = true)
            } else if (status is AgentStatus.Running) {
                TextKey("stop", { AgentRuntime.cancel() }, color = Ubiq.Red)
            }
        }
        Spacer(Modifier.navigationBarsPadding().height(0.dp))
    }
}

/** Above the input while a search is open in this chat: which match is shown, arrows to the older and newer ones, and close. */
@Composable
private fun FindBar(query: String, at: Int, count: Int, onUp: () -> Unit, onDown: () -> Unit, onClose: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 10.dp, top = 2.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        GlyphIcon(Glyph.Search, size = 16.dp)
        Spacer(Modifier.width(8.dp))
        Text("\u201c$query\u201d", Modifier.weight(1f, fill = false), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.width(8.dp))
        Text(if (count == 0) "no matches" else "${at + 1} of $count", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        // Up is older (higher in the chat), down is newer. At either end the arrow stays, greyed.
        BubbleIcon(Icons.Default.KeyboardArrowUp, "Older match", onUp, size = 34.dp,
            tint = if (at > 0) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.25f))
        BubbleIcon(Icons.Default.KeyboardArrowDown, "Newer match", onDown, size = 34.dp,
            tint = if (at < count - 1) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.25f))
        BubbleIcon(Icons.Default.Close, "Close search", onClose, size = 34.dp)
    }
}

@Composable
internal fun AttachList(onCamera: () -> Unit, onPhotos: () -> Unit, onFile: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Column(Modifier.width(184.dp).shadow(12.dp, shape, ambientColor = Color.Black.copy(alpha = 0.18f), spotColor = Color.Black.copy(alpha = 0.18f))
        .clip(shape).background(MaterialTheme.colorScheme.surface).border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape).padding(vertical = 4.dp)) {
        AttachOption("take a photo", Icons.Default.PhotoCamera, Ubiq.Red, onCamera)
        AttachOption("photos", Icons.Default.Image, Ubiq.Blue, onPhotos)
        AttachOption("file", Icons.Default.Description, Ubiq.YellowEdge, onFile)
    }
}

@Composable
private fun AttachOption(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, tint: Color, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(19.dp), tint = tint)
        Spacer(Modifier.width(12.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onBackground)
    }
}

/** A photo waiting to be sent: just the picture, with a small x on its corner. Other files keep their name. */
@Composable
internal fun PendingAttachment(a: Attachment, onRemove: () -> Unit) {
    val t = a.thumbnail ?: return AttachmentChip(a, onRemove)
    Box(Modifier.size(64.dp)) {
        Image(t.asImageBitmap(), "Photo to send", Modifier.padding(top = 6.dp, end = 6.dp).fillMaxSize().clip(RoundedCornerShape(12.dp)), contentScale = ContentScale.Crop)
        Box(Modifier.align(Alignment.TopEnd).size(22.dp).clip(CircleShape).background(Color(0xE616161A)).clickable(onClick = onRemove)
            .semantics { contentDescription = "Remove photo" }, contentAlignment = Alignment.Center) {
            Icon(Icons.Default.Close, null, Modifier.size(13.dp), tint = Color.White)
        }
    }
}

@Composable
private fun AttachmentChip(a: Attachment, onRemove: (() -> Unit)?) {
    Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f)) {
        Row(Modifier.padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
            val t = a.thumbnail
            if (t != null) Image(t.asImageBitmap(), a.name, Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)), contentScale = ContentScale.Crop)
            else Icon(Icons.Default.Description, null, Modifier.size(28.dp))
            Spacer(Modifier.width(6.dp))
            Text(a.name, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 140.dp))
            if (onRemove != null) IconButton(onClick = onRemove, Modifier.size(28.dp)) { Icon(Icons.Default.Close, "Remove", Modifier.size(16.dp)) }
        }
    }
}

@Composable
private fun EmptyHint() {
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 28.dp)) {
        Text("what can i do?", style = MaterialTheme.typography.headlineMedium)
        Text("Ask, or tell me what to do. I'll use your apps.", Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(14.dp))
        val lights = listOf(Ubiq.Yellow, Ubiq.Blue, Ubiq.Green, Ubiq.Red)
        listOfNotNull(
            "Text Alex that I'm 10 minutes late",
            "Order my usual coffee on the Starbucks app",
            com.agentos.agent.MiniAppPlugin.installed?.suggestion ?: "Every weekday at 8, brief me on my calendar and the weather",
            "Set an alarm for 6:30 tomorrow and play lo-fi on Spotify",
        ).forEachIndexed { i, s ->
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { AgentRuntime.send(s) }.padding(vertical = 11.dp, horizontal = 2.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Led(lights[i % lights.size], glow = false, size = 8.dp)
                Spacer(Modifier.width(12.dp))
                Text(s, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

/**
 * One message. Ubiq's words are plain text on the plate and yours sit on a raised cap. Swipe a
 * message right to reply to it, or long-press for Reply and Copy. The newest question shows its
 * answers as keys while it is still open ([live]).
 */
@OptIn(ExperimentalFoundationApi::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun Bubble(item: ChatItem, live: Boolean = false, onOption: (String) -> Unit = {}, onReply: (ChatItem) -> Unit = {},
                   onDelete: ((ChatItem) -> Unit)? = null, mark: String? = null, markCurrent: Boolean = false) {
    val markColor = Ubiq.Yellow.copy(alpha = if (markCurrent) 0.9f else 0.35f)
    when (item.role) {
        // Individual steps are not shown; the working indicator (and Watch) cover them.
        Role.ACTION -> {}
        Role.TASK -> {}
        // A note like "Call with Ubiq · 0:59": long press deletes it, like a message.
        Role.SYSTEM -> Box {
            var menu by remember { mutableStateOf(false) }
            Text(item.text, Modifier.fillMaxWidth().combinedClickable(onClick = {}, onLongClick = { if (onDelete != null) menu = true })
                .padding(horizontal = 24.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            DeleteMenu(menu, { menu = false }) { onDelete?.invoke(item) }
        }
        else -> {
            val user = item.role == Role.USER
            val ctx = LocalContext.current
            var menu by remember { mutableStateOf(false) }
            val drag = remember { androidx.compose.animation.core.Animatable(0f) }
            val scope = rememberCoroutineScope()
            val reach = with(androidx.compose.ui.platform.LocalDensity.current) { 72.dp.toPx() }
            Box(Modifier.fillMaxWidth()) {
                // The reply arrow appears behind the message as it is pulled right.
                if (drag.value > 4f) Icon(Icons.AutoMirrored.Filled.Reply, null, Modifier.align(Alignment.CenterStart).padding(start = 6.dp).size(20.dp)
                    .graphicsLayer { alpha = (drag.value / reach).coerceIn(0f, 1f) }, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.fillMaxWidth().offset { androidx.compose.ui.unit.IntOffset(drag.value.toInt(), 0) }
                    .pointerInput(item.id) {
                        detectHorizontalDragGestures(
                            onDragEnd = { val hit = drag.value >= reach * 0.8f; scope.launch { drag.animateTo(0f) }; if (hit) onReply(item) },
                            onDragCancel = { scope.launch { drag.animateTo(0f) } },
                        ) { ch, dx -> ch.consume(); scope.launch { drag.snapTo((drag.value + dx).coerceIn(0f, reach * 1.1f)) } }
                    }
                    .padding(bottom = if (user) 2.dp else 0.dp),
                    horizontalArrangement = if (user) Arrangement.End else Arrangement.Start, verticalAlignment = Alignment.Bottom) {
                    Column(horizontalAlignment = if (user) Alignment.End else Alignment.Start) {
                        if (item.attachments.isNotEmpty()) {
                            Row(Modifier.padding(bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                item.attachments.take(4).forEach { a ->
                                    val t = a.thumbnail
                                    // Ubiq's result screenshot: the whole screen, tall, and full size on a tap.
                                    if (t != null && !user && a.isImage) {
                                        var big by remember { mutableStateOf(false) }
                                        Image(t.asImageBitmap(), "Result screenshot", Modifier.width(132.dp).aspectRatio(t.width.toFloat() / t.height)
                                            .clip(RoundedCornerShape(14.dp)).clickable { big = true }, contentScale = ContentScale.Fit)
                                        if (big) ScreenshotViewer(a, onClose = { big = false })
                                    }
                                    else if (t != null) Image(t.asImageBitmap(), a.name, Modifier.size(96.dp).clip(RoundedCornerShape(12.dp)), contentScale = ContentScale.Crop)
                                    else AttachmentChip(a, onRemove = null)
                                }
                            }
                        }
                        if (item.text.isNotBlank()) Box {
                            val press = Modifier.combinedClickable(onClick = {}, onLongClick = { menu = true })
                            if (user) {
                                // Flat: a see-through ink tint (same contrast top to bottom), no moulded edge or shadow.
                                Text(Md.mark(androidx.compose.ui.text.AnnotatedString(item.text), mark, markColor), Modifier.widthIn(max = 236.dp).clip(RoundedCornerShape(13.dp)).background(inset)
                                    .then(press).padding(horizontal = 11.dp, vertical = 6.dp),
                                    style = chatText(), color = MaterialTheme.colorScheme.onBackground)
                            // The clip (for the press shape) gets 4dp of room each side: letters like "p" and "I" draw a little
                            // left of their box at the start of a line and were cut off. The offset keeps the text where it was.
                            } else MarkdownText(item.text, Modifier.offset(x = (-4).dp).clip(RoundedCornerShape(8.dp)).then(press).padding(horizontal = 4.dp), style = chatText(),
                                mark = mark, markColor = markColor)
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(text = { Text("Reply") }, leadingIcon = { Icon(Icons.AutoMirrored.Filled.Reply, null) }, onClick = { menu = false; onReply(item) })
                                DropdownMenuItem(text = { Text("Copy") }, leadingIcon = { Icon(Icons.Default.ContentCopy, null) }, onClick = {
                                    menu = false
                                    ctx.getSystemService(android.content.ClipboardManager::class.java)
                                        .setPrimaryClip(android.content.ClipData.newPlainText("message", com.agentos.agent.plainText(item.text)))
                                })
                                // Gone from the chat and from what Ubiq remembers of it (Session.deleteItem).
                                if (onDelete != null) DropdownMenuItem(text = { Text("Delete") }, leadingIcon = { Icon(Icons.Default.Delete, null) },
                                    onClick = { menu = false; onDelete(item) })
                            }
                        }
                        if (live) androidx.compose.foundation.layout.FlowRow(Modifier.padding(top = 8.dp, start = 2.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            item.options.forEachIndexed { i, o -> FlatChip(o, { onOption(o) }, first = i == 0) }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Above the input: background tasks and other conversations that are running or need you, so
 * nothing in flight is out of sight. Up to two show as rows; more fold into one line you can open.
 */
@Composable
private fun Tray(current: com.agentos.agent.Session, onWatch: (String) -> Unit, onFind: (String) -> Unit) {
    val sessions by AgentRuntime.sessions.collectAsStateWithLifecycle()
    val live = sessions.filter { it.isActive && it !== current }
    if (live.isEmpty()) return
    var open by remember { mutableStateOf(false) }
    val statuses = live.map { s -> androidx.compose.runtime.key(s.id) { s.status.collectAsStateWithLifecycle().value } }
    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 2.dp)) {
        if (live.size > 3 && !open) {
            val need = statuses.count { it is AgentStatus.WaitingForUser }; val run = live.size - need
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { open = true }.padding(vertical = 8.dp, horizontal = 2.dp),
                verticalAlignment = Alignment.CenterVertically) {
                if (need > 0) { Led(Ubiq.Yellow); Spacer(Modifier.width(8.dp)) }
                Text(listOfNotNull(if (need > 0) "$need need you" else null, if (run > 0) "$run running" else null).joinToString(", "),
                    Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                Text("show", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else live.zip(statuses).forEach { (s, st) ->
            val title by s.title.collectAsStateWithLifecycle()
            val waiting = st is AgentStatus.WaitingForUser
            val onDesk = s.desk?.let { it.owner == s.id } == true
            if (waiting) TaskRow(title, "waiting for your answer", running = false, waiting = true, onWatch = null,
                onOpen = { onFind(s.id) }, horizontalPad = 0.dp)
            else TaskRow(title, (st as? AgentStatus.Running)?.note.orEmpty(), running = st is AgentStatus.Running,
                // Watching stays in this chat: closing the view comes back here, not to the task's own chat.
                onWatch = if (onDesk) ({ onWatch(s.id) }) else null,
                // A demo take has no Stop on camera; one slip would end it.
                onStop = if (!s.demoRunning) ({ s.cancel() }) else null,
                onOpen = { AgentRuntime.focus(s.id) }, horizontalPad = 0.dp)
        }
    }
}

/**
 * One piece of work under way, on the input: a spinner, the task's name (none for this chat's own turn), the step it is
 * on now (the line rolls as steps change; nothing stays behind), and watch. Parallel tasks stack as more of these.
 */
@Composable
private fun TaskRow(title: String?, note: String, running: Boolean, onWatch: (() -> Unit)?, onStop: (() -> Unit)? = null,
                    onOpen: (() -> Unit)? = null, horizontalPad: androidx.compose.ui.unit.Dp = 14.dp, waiting: Boolean = false) {
    // One thin line: work in progress is not what the user came for, the result is. Spinner, "title · step", tiny keys.
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    val strong = MaterialTheme.colorScheme.onSurface
    Row(Modifier.fillMaxWidth().padding(horizontal = horizontalPad + 4.dp).heightIn(min = 22.dp)
        .then(if (onOpen != null) Modifier.clickable(onClick = onOpen) else Modifier), verticalAlignment = Alignment.CenterVertically) {
        if (running) androidx.compose.material3.CircularProgressIndicator(Modifier.size(9.dp), strokeWidth = 1.2.dp, color = dim)
        else Box(Modifier.padding(horizontal = 1.dp).size(7.dp).clip(RoundedCornerShape(50)).background(if (waiting) Ubiq.Yellow else Ubiq.Lit))
        Spacer(Modifier.width(8.dp))
        androidx.compose.animation.AnimatedContent(note, Modifier.weight(1f), label = "step",
            transitionSpec = { androidx.compose.animation.fadeIn() togetherWith androidx.compose.animation.fadeOut() }) { n ->
            Text(androidx.compose.ui.text.buildAnnotatedString {
                if (title != null) {
                    pushStyle(androidx.compose.ui.text.SpanStyle(fontWeight = FontWeight.SemiBold, color = strong))
                    append(title); pop()
                    if (n.isNotBlank()) append("  ·  ")
                }
                append(n)
            }, style = MaterialTheme.typography.labelSmall, color = dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (onWatch != null) Icon(Icons.Default.Visibility, "watch", Modifier.padding(start = 6.dp).size(26.dp).clip(RoundedCornerShape(50))
            .clickable(onClick = onWatch).padding(5.dp), tint = dim)
        if (onStop != null) Text("stop", Modifier.padding(start = 4.dp).clip(RoundedCornerShape(6.dp)).clickable(onClick = onStop)
            .padding(horizontal = 6.dp, vertical = 3.dp), style = MaterialTheme.typography.labelSmall, color = Ubiq.Red)
    }
}

/** The one-item long-press menu for chat rows that are not messages (task cards, call notes). */
@Composable
private fun DeleteMenu(expanded: Boolean, onDismiss: () -> Unit, onDelete: () -> Unit) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(text = { Text("Delete") }, leadingIcon = { Icon(Icons.Default.Delete, null) }, onClick = { onDismiss(); onDelete() })
    }
}

/** One LED for a task's state: white and breathing while it works, yellow when it needs you, green when done. */
@Composable
internal fun StatusLed(status: AgentStatus, modifier: Modifier = Modifier) {
    val breathe = androidx.compose.animation.core.rememberInfiniteTransition(label = "led")
    val a by breathe.animateFloat(0.35f, 1f, androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(900),
        androidx.compose.animation.core.RepeatMode.Reverse), label = "a")
    when (status) {
        is AgentStatus.Running -> Led(MaterialTheme.colorScheme.onSurface.copy(alpha = a), modifier, glow = false)
        is AgentStatus.WaitingForUser -> Led(Ubiq.Yellow, modifier)
        AgentStatus.Idle -> Led(Ubiq.Lit, modifier, glow = false)
    }
}

/** Message text: a size under bodyLarge, so a screen holds more of the conversation. */
@Composable
private fun chatText() = MaterialTheme.typography.bodyLarge.let { it.copy(fontSize = it.fontSize * 0.9f, lineHeight = it.lineHeight * 0.88f) }

/** An answer option in the chat: flat, a hairline outline; the suggested one filled in yellow. */
@Composable
private fun FlatChip(text: String, onClick: () -> Unit, first: Boolean) {
    val shape = RoundedCornerShape(10.dp)
    Text(text, Modifier.clip(shape)
        .then(if (first) Modifier.background(Ubiq.Yellow) else Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape))
        .clickable(onClick = onClick).semantics { role = androidx.compose.ui.semantics.Role.Button }
        .padding(horizontal = 12.dp, vertical = 7.dp),
        style = MaterialTheme.typography.labelLarge, color = if (first) Color(0xFF16161A) else MaterialTheme.colorScheme.onBackground)
}

/** A result screenshot at full size over the chat; a tap or Back closes it. */
@Composable
private fun ScreenshotViewer(a: Attachment, onClose: () -> Unit) {
    val bmp = remember(a) { android.graphics.BitmapFactory.decodeByteArray(a.bytes, 0, a.bytes.size) }
    androidx.compose.ui.window.Dialog(onDismissRequest = onClose, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.92f)).clickable(onClick = onClose), contentAlignment = Alignment.Center) {
            if (bmp != null) Image(bmp.asImageBitmap(), "Result screenshot", Modifier.fillMaxSize().padding(16.dp).clip(RoundedCornerShape(18.dp)), contentScale = ContentScale.Fit)
        }
    }
}
