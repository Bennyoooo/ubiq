package com.agentos.agent

import android.content.Context
import android.util.Log
import com.agentos.control.ControlService
import com.agentos.workspace.Workspace
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The agent's sessions (docs/UX_SPEC.md, Conversations). There is one [main] conversation, never reset or deleted:
 * every entry point (the home bar, power hold, the corner swipe, the nav handle, share, notifications, jobs and mini
 * apps) goes to it. The only other conversations are side chats the user opens explicitly in the app
 * ([newSideChat]), and the background tasks a conversation starts, which report back into it.
 * Up to [MAX_RUNNING] sessions can be working on the phone at once, each on its own hidden desk.
 * Sessions are saved to disk, so the history survives restarts.
 *
 * The chat screen talks to the *focused* session through the members below ([items], [status],
 * [send], ...); entry points use [toMain].
 */
@OptIn(ExperimentalCoroutinesApi::class)
object AgentRuntime {
    private const val TAG = "AgentOS/Runtime"
    const val MAX_RUNNING = 3
    private const val MAX_SAVED = 60
    const val MAIN_ID = "main"
    private const val MAIN_TITLE = "Ubiq"
    /** Items kept on disk: the main conversation keeps (nearly) everything, other sessions their recent part. */
    private const val MAIN_KEPT = 5000
    private const val KEPT = 300

    internal lateinit var ctx: Context
    internal val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    lateinit var apps: InstalledApps
        private set
    /** Phone-using turns in flight; a 4th waits for a slot. */
    internal val slots = Semaphore(MAX_RUNNING)

    private val _sessions = MutableStateFlow<List<Session>>(emptyList())
    /** All sessions, most recently active first. */
    val sessions: StateFlow<List<Session>> = _sessions
    private val _focused = MutableStateFlow(Session(MAIN_ID, MAIN_TITLE, 0))
    val focused: StateFlow<Session> = _focused
    private lateinit var dir: File

    val items: StateFlow<List<ChatItem>> = _focused.flatMapLatest { it.items }.stateIn(scope, SharingStarted.Eagerly, emptyList())
    val status: StateFlow<AgentStatus> = _focused.flatMapLatest { it.status }.stateIn(scope, SharingStarted.Eagerly, AgentStatus.Idle)
    val userControl: StateFlow<Boolean> = _focused.flatMapLatest { it.userControl }.stateIn(scope, SharingStarted.Eagerly, false)

    /** The assistant sheet; it works on its own session (see assist/AssistSession). */
    interface AssistUi {
        suspend fun hideForAction()
        fun showAgain()
    }

    fun init(context: Context) {
        ctx = context.applicationContext
        apps = InstalledApps(ctx)
        scope.launch { apps.refresh() }
        dir = File(ctx.filesDir, "sessions").apply { mkdirs() }
        Trace.init(ctx)
        val loaded = dir.listFiles { f -> f.name.endsWith(".json") }.orEmpty().mapNotNull { load(it) }.sortedByDescending { it.updated }
        // Chats from before the main conversation existed stay in the list as side chats.
        val main = loaded.firstOrNull { it.id == MAIN_ID } ?: Session(MAIN_ID, MAIN_TITLE, System.currentTimeMillis())
        main.title.value = MAIN_TITLE
        _sessions.value = listOf(main) + (loaded - main)
        _focused.value = main
        TaskNotifications.clearStale(ctx)
        cutOff.forEach { it.interrupted() }
        cutOff.clear()
    }

    /** The one ongoing conversation every entry point goes to. */
    val main: Session get() = session(MAIN_ID) ?: _focused.value

    /** An entry point (home bar, assistant sheet, share, ...): the main conversation, shown if the app opens. */
    fun toMain(text: String, attachments: List<Attachment> = emptyList(), modelText: String? = null, onScreen: Boolean = false, fromAssist: Boolean = false, byVoice: Boolean = false) {
        focusMain()
        main.send(text, attachments, modelText, onScreen, fromAssist, byVoice)
    }

    fun focusMain() { _focused.value = main }

    // ---------- the focused session (what the chat screen shows) ----------

    val isBusy: Boolean get() = _focused.value.isBusy
    fun send(text: String, attachments: List<Attachment> = emptyList(), modelText: String? = null, onScreen: Boolean = false, fromAssist: Boolean = false, byVoice: Boolean = false) =
        _focused.value.send(text, attachments, modelText, onScreen, fromAssist, byVoice)
    fun cancel() = _focused.value.cancel()
    fun systemNote(text: String) = _focused.value.systemNote(text)
    fun setUserControl(on: Boolean) = _focused.value.setUserControl(on)

    /** A side chat, opened only by the user in the app; an empty one is reused rather than piling up. */
    fun newSideChat(): Session {
        val cur = _focused.value
        if (cur.isSideChat && cur.items.value.isEmpty() && !cur.isActive) return cur
        return create()
    }

    // ---------- sessions ----------

    fun session(id: String): Session? = _sessions.value.firstOrNull { it.id == id }

    fun create(title: String = Session.NEW_TITLE, focus: Boolean = true): Session {
        val s = Session(java.util.UUID.randomUUID().toString().take(8), title, System.currentTimeMillis())
        _sessions.value = listOf(s) + _sessions.value
        if (focus) _focused.value = s
        return s
    }

    /**
     * A background task for [parent] (the start_task tool): a new session that works on its own desk while the
     * conversation stays free, shown in [parent] as a task card, and reporting back to it when it ends.
     * A [quiet] task (a mini app's request) posts into [parent] only when it needs the user.
     */
    fun startTask(parent: Session, title: String, goal: String, quiet: Boolean = false): Session {
        val child = create(title.take(48).ifBlank { "Task" }, focus = false)
        child.parentId = parent.id
        child.quiet = quiet
        child.beginTask(goal, parent)
        return child
    }

    /** A demo take running alongside the one in [parent], on its own desk, reporting back to [parent]. */
    fun startDemoTask(parent: Session, demo: Playbooks.Playbook, text: String): Session {
        val child = create(demo.title, focus = false)
        child.parentId = parent.id
        child.beginDemoTask(text, demo)
        return child
    }

    /** Background tasks [parent] started, newest last. */
    fun tasksOf(parent: Session): List<Session> = _sessions.value.filter { it.parentId == parent.id }.sortedBy { it.created }

    fun focus(id: String) { session(id)?.let { _focused.value = it } }

    /**
     * Before filming: the main conversation starts over with a few everyday exchanges (so it is not empty), and the
     * tasks it started are deleted. Side chats the user made stay.
     */
    fun resetMain() = scope.launch {
        _sessions.value.filter { it.parentId == MAIN_ID }.forEach { delete(it.id) }
        main.cancel(Session.CANCEL_DELETED, quiet = true)
        kotlinx.coroutines.delay(1500)  // a cancelled turn's last words land before the reset, not after it
        val t = System.nanoTime()
        val seed = listOf(
            Role.USER to "What's the weather like this weekend?",
            Role.ASSISTANT to "Mostly sunny. Saturday 66° and clear, Sunday a little cooler at 61° with clouds in the afternoon.",
            Role.USER to "Any good brunch spots near me that take reservations?",
            Role.ASSISTANT to "Three nearby with open tables Sunday morning: Sweet Maple (10:30am), Kitchen Story (11am) and Plow (11:15am). Want me to book one?",
            Role.USER to "Not yet, I'll decide later",
            Role.ASSISTANT to "Sounds good. Just ask when you're ready.",
            Role.USER to "How long would it take to get to SFO tomorrow morning?",
            Role.ASSISTANT to "About 30 minutes by car if you leave around 6am, longer after 7 when traffic picks up.",
        ).mapIndexed { i, (r, text) -> ChatItem(r, text, id = t + i) }
        main.resetTo(seed)
        focusMain()
    }

    fun delete(id: String) {
        if (id == MAIN_ID) return
        val s = session(id) ?: return
        s.cancel(Session.CANCEL_DELETED)
        Workspace.deskFor(id)?.let { if (it.owner == null) it.clear() }
        _sessions.value = _sessions.value - s
        File(dir, "$id.json").delete()
        images(id).deleteRecursively()
        Trace.delete(id)
        if (_focused.value === s) focusMain()
    }

    /** Sessions with a task running or waiting for the user. */
    fun active(): List<Session> = _sessions.value.filter { it.isActive }

    // ---------- jobs ----------

    private val ruleSessions = java.util.concurrent.ConcurrentHashMap<String, Session>()

    /**
     * Runs a job's task (see jobs/Jobs) as a background task of the main conversation, which hears its result and
     * shows it when it ends, and returns it. If that job's last run is still under way, [extra] (another matching
     * notification) is handed to it instead, or, for a scheduled run, nothing starts and this returns null.
     */
    fun runAutomated(key: String, label: String, prompt: String, extra: String?, note: String, demo: Playbooks.Playbook? = null): Session? {
        if (!::ctx.isInitialized) return null
        ruleSessions[key]?.takeIf { it.isActive }?.let { s ->
            if (extra == null) return null
            s.addLateInput("Another matching notification: $extra"); return s
        }
        val s = create(label, focus = false).also { it.origin = key; it.parentId = MAIN_ID }
        ruleSessions[key] = s
        if (demo != null) s.startAutomated(label, prompt, note, demo) else s.startAutomated(label, prompt, note)
        return s
    }

    // ---------- bookkeeping ----------

    private val saveJobs = java.util.concurrent.ConcurrentHashMap<String, Job>()

    /** A session changed (items or status): reorder the list and save it soon. */
    internal fun changed(s: Session) {
        s.updated = System.currentTimeMillis()
        if (::ctx.isInitialized) TaskNotifications.update(ctx, s)
        com.agentos.control.Island.refresh()
        if (!::dir.isInitialized) return
        if (_sessions.value.firstOrNull() !== s && s in _sessions.value) _sessions.value = listOf(s) + (_sessions.value - s)
        else _sessions.value = _sessions.value.toList()
        saveJobs[s.id]?.cancel()
        saveJobs[s.id] = scope.launch { delay(400); save(s); prune() }
    }

    /** Saves right away instead of after the debounce in [changed]. */
    internal fun saveNow(s: Session) { saveJobs.remove(s.id)?.cancel(); save(s) }

    private fun save(s: Session) {
        // An empty chat is saved only when the user deleted its messages, so they don't come back after a restart.
        if (s.items.value.isEmpty() && !s.forget && !s.forgetPi) return
        val kept = s.items.value.takeLast(if (s.isMain) MAIN_KEPT else KEPT)
        saveImages(s.id, kept)
        val o = JSONObject().put("id", s.id).put("title", s.title.value).put("created", s.created).put("updated", s.updated)
            .put("previousResponseId", s.previousResponseId ?: "").put("recap", s.recap).put("parent", s.parentId ?: "").put("quiet", s.quiet)
            .put("forget", s.forget).put("forgetPi", s.forgetPi)
            .put("seen", JSONObject(s.seen.mapValues { it.value })).put("seenSince", s.seenSince)
            .put("running", s.parentId != null && s.isActive).put("reports", JSONArray(s.unheardReports()))
            .put("items", JSONArray().apply {
                kept.forEach { put(JSONObject().put("r", it.role.name).put("t", it.text).put("i", it.id).put("k", it.taskId ?: "")
                    .put("a", JSONArray().apply { it.attachments.forEach { a -> put(a.name) } })) }
            })
        try { File(dir, "${s.id}.json").writeText(o.toString()) } catch (t: Throwable) { Log.w(TAG, "save ${s.id}", t) }
    }

    /** A chat's images (photos the user attached, result screenshots), so they come back as images after a restart. */
    private fun images(id: String) = File(dir, "$id.images")

    private fun saveImages(id: String, kept: List<ChatItem>) {
        val d = images(id)
        val names = HashSet<String>()
        try {
            kept.forEach { item ->
                item.attachments.forEachIndexed { i, a ->
                    if (!a.isImage) return@forEachIndexed
                    val f = File(d, "${item.id}-$i").also { names += it.name }
                    if (!f.exists()) { d.mkdirs(); f.writeBytes(a.bytes) }
                }
            }
            d.listFiles()?.filter { it.name !in names }?.forEach { it.delete() }
        } catch (t: Throwable) { Log.w(TAG, "save images $id", t) }
    }

    /** Background tasks that were running when the process died (see [load]). */
    private val cutOff = mutableListOf<Session>()

    private fun load(f: File): Session? = try {
        val o = JSONObject(f.readText())
        Session(o.getString("id"), o.optString("title", Session.NEW_TITLE), o.optLong("created")).also { s ->
            s.updated = o.optLong("updated", s.created)
            s.previousResponseId = o.optString("previousResponseId").ifBlank { null }
            s.recap = o.optString("recap")
            s.parentId = o.optString("parent").ifBlank { null }
            s.quiet = o.optBoolean("quiet")
            s.forget = o.optBoolean("forget"); s.forgetPi = o.optBoolean("forgetPi")
            o.optJSONObject("seen")?.let { m -> m.keys().forEach { k -> s.seen[k] = m.getLong(k) } }
            if (o.has("seenSince")) s.seenSince = o.getLong("seenSince")
            if (o.optBoolean("running")) cutOff.add(s)
            s.restoreReports(o.optJSONArray("reports")?.let { r -> (0 until r.length()).map { r.getString(it) } }.orEmpty())
            val a = o.optJSONArray("items") ?: JSONArray()
            s.restore((0 until a.length()).map { a.getJSONObject(it) }.map { j ->
                val names = j.optJSONArray("a")?.let { arr -> (0 until arr.length()).map { arr.getString(it) } }.orEmpty()
                val role = Role.valueOf(j.optString("r", "SYSTEM"))
                val itemId = j.optLong("i", System.nanoTime())
                val files = names.mapIndexed { i, n -> n to File(images(s.id), "$itemId-$i") }
                val shown = files.filter { it.second.isFile }.map { (n, f) -> Attachment.saved(n, f, if (role == Role.USER) 256 else 900) }
                val missing = files.filterNot { it.second.isFile }.map { it.first }
                val text = j.optString("t") + if (missing.isNotEmpty()) "\n(attached: ${missing.joinToString()})" else ""
                ChatItem(role, text, itemId, attachments = shown, taskId = j.optString("k").ifBlank { null })
            })
        }
    } catch (t: Throwable) { Log.w(TAG, "load ${f.name}", t); null }

    private fun prune() {
        val old = _sessions.value.filter { !it.isActive && !it.isMain && it !== _focused.value }.drop(MAX_SAVED)
        old.forEach { File(dir, "${it.id}.json").delete(); images(it.id).deleteRecursively(); Trace.delete(it.id) }
        if (old.isNotEmpty()) _sessions.value = _sessions.value - old.toSet()
    }

    @Volatile private var overlayLead: String? = null

    /** The Stop chip: stops the task driving the visible screen. */
    fun stopOverlayLead() { overlayLead?.let { session(it) }?.cancel(Session.CANCEL_CHIP) ?: _focused.value.cancel(Session.CANCEL_CHIP) }

    /** The Stop chip shows only while a task drives the visible screen; background tasks stay out of the way. */
    fun refreshOverlay() {
        val svc = ControlService.instance ?: return
        // A demo stays off camera, and a mini app the user asked to see is not something to stop.
        val onScreen = active().firstOrNull { it.status.value is AgentStatus.Running && it.runsOnScreen && !it.demoRunning && !it.openedMiniApp }
        if (onScreen == null) { svc.hideStatus(); return }
        overlayLead = onScreen.id
        svc.showStatus()
    }
}
