package com.agentos.agent

import android.content.Context
import android.util.Log
import com.agentos.control.ControlService
import com.agentos.data.Prefs
import com.agentos.voice.Speaker
import com.agentos.workspace.Workspace
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/**
 * One conversation with the agent, and the task it may be running. Several sessions can run at
 * once (see [AgentRuntime]): each background session gets its own hidden workspace ("desk"),
 * apps that only allow one window are leased to one session at a time ([AppLeases]), and a session
 * pauses while the user is in the app it was using and continues when they leave it.
 *
 * Conversation state lives server-side (OpenAI Responses API, previous_response_id); each call
 * only sends the new input items. When the chain gets long it restarts with a short recap.
 */
class Session(val id: String, title: String, val created: Long) {
    private val ctx: Context get() = AgentRuntime.ctx
    private val scope get() = AgentRuntime.scope
    private val apps get() = AgentRuntime.apps

    val title = MutableStateFlow(title)
    @Volatile var updated = created
        internal set
    /** A short tag for sessions started by something other than the user (e.g. "rule-3"). */
    @Volatile var origin: String? = null
    /** For a background task (start_task): the conversation that started it, which gets its result. */
    @Volatile var parentId: String? = null
    /** A quiet background task (a mini app's request): its parent hears the result but shows only its questions. */
    @Volatile var quiet = false
    /** The one ongoing conversation (see [AgentRuntime.main]). */
    val isMain get() = id == AgentRuntime.MAIN_ID
    /** A conversation the user opened in the app, beside the main one (not a background task or a job's run). */
    val isSideChat get() = !isMain && parentId == null
    /** Results of this conversation's background tasks that the model has not heard yet (read at the next turn). */
    private val taskReports = java.util.concurrent.ConcurrentLinkedQueue<String>()
    /** How far this conversation's model has read each other conversation (its last item's id), see [elsewhere]. */
    internal val seen = java.util.concurrent.ConcurrentHashMap<String, Long>()
    /** The main conversation hears side chats active after this time (older ones it never saw, so they stay out). */
    @Volatile internal var seenSince = System.currentTimeMillis()

    private val _items = MutableStateFlow<List<ChatItem>>(emptyList())
    val items: StateFlow<List<ChatItem>> = _items
    private val _status = MutableStateFlow<AgentStatus>(AgentStatus.Idle)
    val status: StateFlow<AgentStatus> = _status

    internal var previousResponseId: String? = null
    /** function_call items from the last response that have not received an output yet (e.g. after `done`). */
    private var pendingOutputs = JSONArray()
    private var chainTurns = 0
    internal var recap = ""
    private var job: Job? = null
    private var pendingAnswer: CompletableDeferred<JSONObject>? = null
    /** Photos and files sent with the answer to a Pi ask_user; [takeAnswerFiles] hands them to the tool result. */
    @Volatile private var answerFiles: List<Attachment> = emptyList()
    internal fun takeAnswerFiles(): List<Attachment> = answerFiles.also { answerFiles = emptyList() }
    /** The answer keys of the question this session is waiting on, if it asked with keys. */
    @Volatile private var pendingOptions: List<String> = emptyList()
    /** Guards who takes [pendingAnswer]: the user's answer, or the hold running out ([askFromHarness]). Never both. */
    private val askLock = Any()
    /**
     * A question the agent stopped holding its turn for (docs: "the question is durable, the hold is not"). The question
     * stays open in the chat with no timer; what ended is the hold on the live state (desk, app, slot). The next thing
     * the user says here starts a new turn that is told what was asked, when, and that its screen was released.
     */
    private class Parked(val question: String, val askedAt: Long, val where: String?, val hadScreen: Boolean, val guarded: Boolean)
    @Volatile private var parked: Parked? = null
    /** The running harness turn parked its question: it says and does nothing more. */
    @Volatile private var parkedThisTurn = false
    /** Messages the user (or a rule) sent while a task was running; the agent reads them at its next step. */
    private val lateInputs = java.util.concurrent.ConcurrentLinkedQueue<String>()
    /** Messages with photos or files sent while a turn ran: they start the next turn. */
    private val queuedFiles = java.util.concurrent.ConcurrentLinkedQueue<Pair<String, List<Attachment>>>()
    /** The running turn's target (desk, slot and leases), released when the turn ends. */
    @Volatile private var target: TaskTarget? = null

    /** One-time codes and passwords seen on screen, as tokens (see [Secrets]); kept in memory only. */
    val secrets = Secrets()
    /** The user's own words in this session, and the tool and screen text the model saw: memory provenance compares them. */
    private val owner = StringBuilder()
    private val untrusted = StringBuilder()
    fun ownerText(): String = synchronized(owner) { owner.toString() }
    fun untrustedText(): String = synchronized(untrusted) { untrusted.toString() }
    /** What `recall` returned in this session (docs/MEMORY.md): observed values an action may use only with the owner's OK. */
    private val observedText = StringBuilder()
    fun observedText(): String = synchronized(observedText) { observedText.toString() }
    internal fun observed(text: String) {
        synchronized(observedText) { observedText.append(text).append('\n'); if (observedText.length > 100_000) observedText.delete(0, observedText.length - 80_000) }
        heard(text)
    }
    private fun heard(text: String) = synchronized(untrusted) {
        untrusted.append(text).append('\n')
        if (untrusted.length > 200_000) untrusted.delete(0, untrusted.length - 150_000)
    }
    /** The last turn in numbers (steps, tool calls, seconds), for the log and DEBUG_DUMP. */
    @Volatile var lastStats = ""
        private set
    private var turnStartedAt = 0L
    private var turnSteps = 0
    private var turnTools = 0

    /** True while the user drives this session's desk by hand from the viewer; the agent waits. */
    private val _userControl = MutableStateFlow(false)
    val userControl: StateFlow<Boolean> = _userControl
    @Volatile private var intervened = false
    fun setUserControl(on: Boolean) {
        if (on) intervened = true
        _userControl.value = on
        com.agentos.workspace.Workspace.deskFor(id)?.liveTouch = on
    }
    private suspend fun awaitAgentTurn(step: Int) {
        if (!_userControl.value) return
        val before = _status.value
        setStatus(AgentStatus.Running(step, "Paused: you are in control of the workspace"))
        _userControl.first { !it }
        if (_status.value is AgentStatus.Running) setStatus(before)
    }

    private fun setStatus(st: AgentStatus) {
        val was = _status.value
        _status.value = st
        // Finished while you were elsewhere: the island turns green until you open the conversation.
        if (st is AgentStatus.Idle && was !is AgentStatus.Idle && !onScreenNow()) com.agentos.control.Island.markUnseen(parentId ?: id)
        AgentRuntime.changed(this)
    }

    /** A wait inside a turn (for a run slot, an app another session holds, or the user leaving an app). */
    internal fun showWaiting(note: String?, restore: AgentStatus? = null) {
        val cur = _status.value
        if (note != null) setStatus(AgentStatus.Running((cur as? AgentStatus.Running)?.step ?: 0, note))
        else if (restore != null && cur is AgentStatus.Running) setStatus(restore)
        AgentRuntime.refreshOverlay()
    }

    /** The desk this session works on (while running in the background, or the one it used last). */
    val desk: com.agentos.workspace.Desk? get() = com.agentos.workspace.Workspace.deskFor(id)

    val isActive: Boolean get() = _status.value !is AgentStatus.Idle
    /** The current (or last) turn works on the visible screen rather than a hidden desk. */
    @Volatile var runsOnScreen = false
        private set

    val isBusy: Boolean get() = _status.value is AgentStatus.Running

    /**
     * The assistant sheet (assist/AssistSession) while it is open. A task started from it with
     * screen context runs on the app the user is looking at: the sheet hides before the agent
     * touches the screen, and comes back for questions and the result.
     */
    @Volatile var assistUi: AgentRuntime.AssistUi? = null
    /** True while the running turn came from the assistant sheet and works on the visible app. */
    @Volatile private var turnFromAssist = false
    /** This turn's message was spoken (dictation, the assistant's mic): express replies are read out only then. */
    @Volatile private var turnByVoice = false
    /** What the express lane did since the model last heard (it bypasses the model), for the next turn's message. */
    private val expressNotes = java.util.concurrent.ConcurrentLinkedQueue<String>()
    @Volatile private var assistHidden = false

    /**
     * [modelText] is what the model receives when it differs from what the chat shows (e.g. the
     * screen's text added by the assistant sheet). [onScreen] runs the task on the visible screen
     * even in background mode, because it is about the app the user is in.
     */
    fun send(text: String, attachments: List<Attachment> = emptyList(), modelText: String? = null, onScreen: Boolean = false, fromAssist: Boolean = false, byVoice: Boolean = false) {
        val t = text.trim()
        if (t.isEmpty() && attachments.isEmpty()) return
        // Only the user's own words pick a demo, never screen text the assistant sheet adds for the model.
        val demo = if (Prefs.demoPlaybooks(ctx)) Playbooks.match(t, lastDemo) else null
        if (demo != null) {
            if (demoRunning && demo.name == lastDemo) return // Said twice: the take is already playing.
            // Another demo while one plays here: it runs alongside as a task of this chat (a desk each, up to three).
            if (demoRunning && isMain) { startParallel(t, demo); return }
            // A demo always plays, even if this chat was waiting on an answer or still working.
            if (isActive) cancel(CANCEL_NEW_DEMO, quiet = true)
        }
        _items.update { it + ChatItem(Role.USER, t, attachments = attachments) }
        Trace.log(id, "USER", secrets.redact(t) + attachments.joinToString("") { "\n[attached ${it.name}, ${it.mime}]" })
        synchronized(owner) { owner.append(t).append('\n') }
        if (title.value.isBlank() || title.value == NEW_TITLE) title.value = t.lineSequence().first().take(48)
        AgentRuntime.changed(this)
        val forModel = modelText?.trim()?.ifEmpty { null } ?: t
        // A key of a background task's question, tapped here in its parent chat: it answers that task directly,
        // the same words every time, instead of the model passing it on.
        if (attachments.isEmpty() && pendingAnswer == null) {
            AgentRuntime.sessions.value.firstOrNull { c -> c.parentId == id && (c.pendingAnswer != null || c.parked != null) && c.pendingOptions.any { it.equals(t, true) } }
                ?.let { child -> child.send(t); return }
        }
        synchronized(askLock) { pendingAnswer.also { pendingAnswer = null } }?.let { d ->
            // "Yes" to "Should I remember 1-800-555-0199?" makes the question's words the user's own (memory provenance).
            (_status.value as? AgentStatus.WaitingForUser)?.question?.takeIf { YES.containsMatchIn(t) }?.let { q -> synchronized(owner) { owner.append(q).append('\n') } }
            setStatus(AgentStatus.Running(0, WORKING))
            parentId?.let { AgentRuntime.session(it) }?.clearNotice(41)
            clearNotice(41)
            answerFiles = attachments
            d.complete(OpenAi.userMessage(forModel, attachments))
            return
        }
        if (isBusy) {
            // The agent picks this up at its next step instead of making the user wait or stop it.
            // Photos and files cannot join a running step: they go with the next turn.
            if (attachments.isEmpty()) lateInputs.add(forModel) else queuedFiles.add(forModel to attachments)
            return
        }
        synchronized(news) {
            turnFromAssist = fromAssist; assistHidden = false; turnGuarded = false; turnByVoice = byVoice
            setStatus(AgentStatus.Running(0, WORKING))
            // Only the owner's own plain words can take the express lane (not a demo, a reply to a quote, nor a request about the screen).
            // An answer to a parked question is never an express request, and a "yes" makes the question's words the user's own.
            parked?.question?.takeIf { YES.containsMatchIn(t) }?.let { q -> synchronized(owner) { owner.append(q).append('\n') } }
            val plain = demo == null && modelText == null && attachments.isEmpty() && !onScreen && parked == null
            job = scope.launch { if (!(plain && tryExpress(t))) runTurn(forModel, attachments, onScreen, demo = demo) }
        }
    }

    /** Starts a job's task (scheduled or triggered) in this (new) session. */
    /** The user asked for [demo] while another take plays in this chat: say so here and run it as a parallel task. */
    private fun startParallel(text: String, demo: Playbooks.Playbook) {
        _items.update { it + ChatItem(Role.USER, text) }
        synchronized(owner) { owner.append(text).append('\n') }
        AgentRuntime.changed(this)
        val running = AgentRuntime.tasksOf(this).count { it.isActive }
        if (running + 1 >= com.agentos.workspace.Workspace.MAX_DESKS) {
            post(Role.ASSISTANT, "I'm already running ${running + 1} tasks. Ask me again when one finishes.")
            return
        }
        post(Role.ASSISTANT, "On it. I'll do that in parallel.")
        AgentRuntime.startDemoTask(this, demo, text)
    }

    /** A demo take as a background task of [parent] (see [startParallel]): the user's words, then the playbook. */
    internal fun beginDemoTask(text: String, demo: Playbooks.Playbook) {
        _items.update { it + ChatItem(Role.USER, text) }
        synchronized(owner) { owner.append(text).append('\n') }
        turnFromAssist = false; assistHidden = false; turnGuarded = false
        setStatus(AgentStatus.Running(0, demo.title))
        job = scope.launch { runTurn(text, emptyList(), onScreen = false, demo = demo) }
    }

    fun startAutomated(label: String, prompt: String, note: String, demo: Playbooks.Playbook? = if (Prefs.demoPlaybooks(ctx)) Playbooks.match(prompt) else null) {
        post(Role.SYSTEM, note)
        synchronized(owner) { owner.append(prompt).append('\n') }
        turnFromAssist = false; assistHidden = false; turnGuarded = true
        setStatus(AgentStatus.Running(0, label))
        job = scope.launch { runTurn(AUTOMATED_PREAMBLE + prompt, emptyList(), onScreen = false, guarded = true, demo = demo) }
    }

    /** Runs [goal] as a background task of [parent]. The owner's words in that chat carry over for provenance; the goal is the model's own. */
    internal fun beginTask(goal: String, parent: Session) {
        // The goal is the model's words, not the user's: shown as a note, not as a user bubble.
        val from = parent.title.value.let { if (it.length > 40) it.take(39).trimEnd() + "…" else it }
        post(Role.SYSTEM, "Background task from \"$from\"\n$goal")
        synchronized(owner) { owner.append(parent.ownerText()) }
        turnFromAssist = false; assistHidden = false; turnGuarded = false
        setStatus(AgentStatus.Running(0, WORKING))
        job = scope.launch { runTurn(TASK_PREAMBLE + goal, emptyList(), onScreen = false) }
    }

    /** A background task of this conversation ended: this conversation's model tells the user (see [relay]). */
    internal fun taskFinished(child: Session, result: String) {
        val text = result.ifBlank { "Finished." }
        if (child.quiet) { taskReports.add("(Background task \"${child.title.value}\" finished; its result went to the mini app that asked: $text)"); return }
        // A scripted take reports as it is, the same words every time; anything else goes through this chat's model.
        if (child.lastDemo != null) {
            taskReports.add("(Background task \"${child.title.value}\" finished, and this chat already showed the user its result: $text)")
            post(Role.ASSISTANT, "${child.title.value}: $text", attachments = listOfNotNull(child.lastShot))
        } else relay("(Background task \"${child.title.value}\" (id ${child.id}) finished. Its result: $text)", "${child.title.value}: $text",
            attachments = listOfNotNull(child.lastShot))
        if (!child.onScreenNow()) notifyUser(child.title.value.take(40), text, 43)
    }

    /** A background task of this conversation needs the user: the question is asked here, where they can answer it (see [latestUserWords]). */
    internal fun taskAsks(child: Session, question: String, options: List<String> = emptyList()) {
        if (child.lastDemo != null) post(Role.ASSISTANT, "${child.title.value} needs your answer: $question", options, taskId = child.id)
        else {
            val choices = if (options.isEmpty()) "" else " Choices: ${options.joinToString(" / ")}."
            relay("(Background task \"${child.title.value}\" (id ${child.id}) is waiting for the user's answer: $question$choices)",
                "${child.title.value} needs your answer: $question", options, taskId = child.id)
        }
        if (!child.onScreenNow()) notifyUser("${child.title.value.take(40)}: needs your answer", question, 41, options)
    }

    /** News from a background task that is still working ([AgentTools] update_user). */
    internal fun taskNews(child: Session, news: String) =
        relay("(Background task \"${child.title.value}\" (id ${child.id}) has news and is still working: $news)", "${child.title.value}: $news")

    /** update_user is for news, not a running commentary: a few per task. */
    internal fun mayUpdate() = updatesSent.incrementAndGet() <= MAX_UPDATES
    private val updatesSent = java.util.concurrent.atomic.AtomicInteger()

    /**
     * What a background task has for the user goes through this conversation's model, which tells them in its own words
     * (the user hears one voice, not the task's report). Idle: a turn starts with it. Busy: it waits for the turn to end.
     * Waiting for the user's answer to something else: shown as it is, and the model hears it next turn.
     * If the model's turn says nothing, [fallback] is shown instead.
     */
    private fun relay(note: String, fallback: String, options: List<String> = emptyList(), attachments: List<Attachment> = emptyList(), taskId: String? = null) {
        heard(note)
        val item = News(note, fallback, options, attachments, taskId)
        synchronized(news) {
            if (_status.value is AgentStatus.Running) { news.add(item); return }
            if (_status.value is AgentStatus.Idle && pendingAnswer == null) { startRelay(listOf(item), ""); return }
        }
        taskReports.add("$note (The chat showed this to the user as it was.)")
        post(Role.ASSISTANT, fallback, options, attachments = attachments, taskId = taskId)
    }

    /** A turn that passes on [items], with any messages the user queued meanwhile ([userText]). */
    private fun startRelay(items: List<News>, userText: String, files: List<Attachment> = emptyList()) {
        relayFallback = items.joinToString("\n\n") { it.fallback }
        val asking = items.lastOrNull { it.options.isNotEmpty() }
        relayOptions = asking?.options.orEmpty()
        relayTaskId = asking?.taskId
        // The task's result screenshot: with the fallback, or after the model's words when it spoke.
        relayFiles = items.flatMap { it.attachments }
        relayMark = assistantPosts
        turnFromAssist = false; assistHidden = false; turnGuarded = false
        setStatus(AgentStatus.Running(0, WORKING))
        val text = items.joinToString("\n") { it.note } + "\n" + (if (userText.isBlank()) RELAY_ASK else "(Then the user's message:)\n$userText")
        job = scope.launch { runTurn(text, files, asked = userText) }
    }

    private class News(val note: String, val fallback: String, val options: List<String>, val attachments: List<Attachment> = emptyList(), val taskId: String? = null)
    private val news = java.util.concurrent.ConcurrentLinkedQueue<News>()
    /** The running turn passes on task news: what to show if it says nothing, and the buttons for a task's question. */
    @Volatile private var relayFallback: String? = null
    @Volatile private var relayOptions: List<String> = emptyList()
    @Volatile private var relayTaskId: String? = null
    @Volatile private var relayFiles: List<Attachment> = emptyList()
    @Volatile private var relayMark = 0
    @Volatile private var assistantPosts = 0

    private fun onScreenNow() = com.agentos.ui.HomeActivity.isInForeground && AgentRuntime.focused.value === this

    /** The user's Stop on a background task: their card shows it, so only the model still has to hear. */
    internal fun taskStopped(child: Session) {
        taskReports.add("(Background task \"${child.title.value}\" was stopped before finishing.)")
    }

    /**
     * The main conversation and side chats see each other (docs/UX_SPEC.md): what was said in the other ones since this
     * model last looked, in front of the next message. Main hears its side chats; a side chat hears main (from its
     * last few items, on its first turn).
     */
    private fun elsewhere(): String {
        if (!isMain && !isSideChat) return ""
        val others = if (isMain) AgentRuntime.sessions.value.filter { it.isSideChat && it !== this && (seen.containsKey(it.id) || it.updated > seenSince) }.take(3)
            else listOf(AgentRuntime.main).filter { it !== this }
        seenSince = System.currentTimeMillis()
        return buildString {
            for (o in others) {
                val all = o.items.value
                val from = seen[o.id]?.let { id -> all.indexOfLast { it.id == id }.takeIf { it >= 0 }?.plus(1) } ?: (all.size - SEEN_FIRST).coerceAtLeast(0)
                all.lastOrNull()?.let { seen[o.id] = it.id }
                val new = all.drop(from).filter { (it.role == Role.USER || it.role == Role.ASSISTANT) && it.text.isNotBlank() }.takeLast(SEEN_MAX)
                if (new.isEmpty()) continue
                // The user's words there are theirs here too (memory provenance); the rest is only background.
                new.filter { it.role == Role.USER }.forEach { u -> synchronized(owner) { owner.append(u.text).append('\n') } }
                val where = if (o.isMain) "the main conversation" else "the side chat \"${o.title.value.take(40)}\""
                append("(Meanwhile in $where, for context; the user may refer to it:\n")
                new.forEach { append(if (it.role == Role.USER) "user: " else "you: ").append(it.text.take(300).replace('\n', ' ')).append('\n') }
                append(")\n")
            }
        }
    }

    /** The background tasks still under way, for the model's view of the next message: it knows without having to ask. */
    private fun liveTasks(): String {
        val live = AgentRuntime.tasksOf(this).filter { it.isActive }
        return if (live.isEmpty()) "" else "(Your background tasks right now:\n${live.joinToString("\n") { it.taskLine() }})\n"
    }

    /** One line on this background task for the conversation that started it: id, state, last steps, last words. */
    internal fun taskLine(): String {
        val st = when (val v = _status.value) {
            is AgentStatus.Running -> "working (${v.note})"
            is AgentStatus.WaitingForUser -> "waiting for the user's answer to: ${v.question}"
            AgentStatus.Idle -> "ended"
        }
        val all = _items.value
        val steps = all.filter { it.role == Role.ACTION }.takeLast(3).joinToString("; ") { it.text }
        val last = all.lastOrNull { it.role == Role.ASSISTANT }?.text
        return "\"${title.value}\" (id ${id}): $st" + (if (steps.isNotBlank()) "\n  last steps: $steps" else "") + (last?.let { "\n  last said: ${it.take(400)}" } ?: "")
    }

    /**
     * For pass_to_task: what the user last typed here, once. Only the user's own words reach a task, because an answer
     * counts as approval there, and the model has read that task's screens: a page must not get to answer its own question.
     */
    internal fun latestUserWords(): String? {
        val last = _items.value.lastOrNull { it.role == Role.USER } ?: return null
        if (last.id == passedToTask || last.text.isBlank()) return null
        passedToTask = last.id
        return last.text
    }
    @Volatile private var passedToTask = 0L

    /** The user's [words], typed in the conversation that started this background task: an answer if it is waiting, else more work. */
    internal fun passFromConversation(words: String, parent: Session) {
        post(Role.SYSTEM, "From \"${parent.title.value.take(40)}\": $words")
        synchronized(owner) { owner.append(words).append('\n') }
        synchronized(askLock) { pendingAnswer.also { pendingAnswer = null } }?.let { d ->
            setStatus(AgentStatus.Running(0, WORKING))
            parent.clearNotice(41)
            d.complete(OpenAi.userMessage(words, emptyList()))
            return
        }
        if (isBusy) { lateInputs.add(words); return }
        turnFromAssist = false; assistHidden = false; turnGuarded = false
        job = scope.launch { runTurn(words) }
    }

    /** Saved as running, but the process died since: nothing is coming, so say so instead of leaving the task looking finished. */
    internal fun interrupted() {
        post(Role.SYSTEM, "Stopped.")
        // Restoring at startup is no time for a turn: the note is shown as it is, and the model hears it next turn.
        parentId?.let { AgentRuntime.session(it) }?.let { p ->
            val text = "${title.value}: it stopped before finishing, because the app restarted."
            p.taskReports.add("($text The chat showed this to the user.)")
            p.post(Role.ASSISTANT, text)
        }
    }

    /** Reports the model has not heard, kept across a restart with the chat they belong to. */
    internal fun unheardReports(): List<String> = taskReports.toList()
    internal fun restoreReports(reports: List<String>) { taskReports.addAll(reports) }

    /** The task results not yet told to the model, as a prefix for the next message. */
    private fun drainTaskReports(): String = buildString { while (true) { val r = taskReports.poll() ?: break; append(r).append('\n') } }

    private val SEEN_FIRST = 8
    private val SEEN_MAX = 8

    private val TASK_PREAMBLE = "(This is a background task the user's conversation handed to you. Work on it by yourself on your own screen; the user is " +
        "doing other things. Ask the user only if you are truly blocked. Use update_user only for news that shouldn't wait for the end. Finish with the result in one or two " +
        "sentences: it goes back to that conversation, which tells the user.)\n\n"

    /** Another matching notification for a rule task that is already under way. */
    fun addLateInput(text: String) { lateInputs.add(text) }

    @Volatile private var turnGuarded = false

    private val AUTOMATED_PREAMBLE = "(This task was started automatically by one of the user's jobs (scheduled or triggered); the user did not just ask for it " +
        "and may not be looking. Work in the background. Anything that sends, replies, posts, pays, deletes or shares needs the user's OK: call ask_user " +
        "with exactly what you will do first. If the rule only asks to tell the user something, just call done with it.)\n\n"

    /** A heads-up on the phone when the agent needs the user or finished an automated task while they were elsewhere. */
    private fun notifyUser(title: String, text: String, kind: Int, options: List<String> = emptyList()) {
        val id = noticeId(kind)
        // Foreground on another chat still counts as elsewhere.
        if (onScreenNow()) return
        val nm = ctx.getSystemService(android.app.NotificationManager::class.java)
        nm.createNotificationChannel(android.app.NotificationChannel("agent", "Assistant", android.app.NotificationManager.IMPORTANCE_HIGH))
        // With the island running, a question drops as its card; the notification only waits quietly in the shade.
        val card = kind == 41 && ControlService.isRunning
        if (card) nm.createNotificationChannel(android.app.NotificationChannel("agent_quiet", "Assistant (shown on the island)", android.app.NotificationManager.IMPORTANCE_LOW))
        val open = android.app.PendingIntent.getActivity(ctx, id, android.content.Intent(ctx, com.agentos.ui.HomeActivity::class.java)
            .putExtra(com.agentos.ui.HomeActivity.EXTRA_OPEN_CHAT, true).putExtra(com.agentos.ui.HomeActivity.EXTRA_SESSION, this.id)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT)
        val b = android.app.Notification.Builder(ctx, if (card) "agent_quiet" else "agent").setSmallIcon(com.agentos.R.drawable.ic_launcher_monochrome)
            .setContentTitle(title).setContentText(text).setStyle(android.app.Notification.BigTextStyle().bigText(text))
            .setContentIntent(open).setAutoCancel(true)
        // The same answers as in the chat. Android shows three buttons, so longer lists open the conversation instead.
        if (options.size in 1..3) options.forEachIndexed { i, o ->
            val pi = android.app.PendingIntent.getBroadcast(ctx, id * 10 + i, android.content.Intent(ctx, AnswerReceiver::class.java)
                .putExtra(AnswerReceiver.EXTRA_SESSION, this.id).putExtra(AnswerReceiver.EXTRA_TEXT, o).putExtra(AnswerReceiver.EXTRA_NOTICE, id),
                android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT)
            b.addAction(android.app.Notification.Action.Builder(null, o, pi).build())
        }
        nm.notify(id, b.build())
        if (card) com.agentos.control.IslandCard.drop(com.agentos.control.IslandCard.Card(
            source = "ubiq · ${this.title.value.take(40)}", title = "", text = text,
            keys = options.map { o -> o to { send(o); clearNotice(41) } },
            onOpen = { ctx.startActivity(android.content.Intent(ctx, com.agentos.ui.HomeActivity::class.java)
                .putExtra(com.agentos.ui.HomeActivity.EXTRA_OPEN_CHAT, true).putExtra(com.agentos.ui.HomeActivity.EXTRA_SESSION, this.id)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)) },
            showMillis = 8_000, key = "ubiq:${this.id}"))
    }

    private fun noticeId(kind: Int) = kind * 1000 + (this.id.hashCode() and 0x3ff)

    /** A heads-up that no longer applies (a task's question has been answered). */
    private fun clearNotice(kind: Int) {
        ctx.getSystemService(android.app.NotificationManager::class.java).cancel(noticeId(kind))
        if (kind == 41) com.agentos.control.IslandCard.hideIf("ubiq:$id")
    }

        /**
     * Stops the session's turn. [by] says who asked, in the log and in the chat when it wasn't the chat's own Stop.
     * During a demo only the chat's Stop and the notification's Stop count; the Stop buttons next to Peek and Watch
     * are hidden then, and a Stop right after sending is a double tap on the button that was Send a moment ago.
     */
    fun cancel(by: String = CANCEL_CHAT, quiet: Boolean = false) {
        Log.i(TAG, "$id: cancel by $by (active=$isActive, demo=$demoRunning)")
        if (demoRunning && by == CANCEL_CHAT && android.os.SystemClock.uptimeMillis() - turnStartedAt < DEMO_STOP_GUARD_MS) { Log.i(TAG, "$id: ignored, just after sending"); return }
        if (harnessRequest != null) scope.launch { Harness.cancel(ctx, id) }
        setUserControl(false)
        synchronized(askLock) { pendingAnswer.also { pendingAnswer = null } }?.cancel()
        parked = null; pendingOptions = emptyList()
        job?.cancel()
        job = null
        lateInputs.clear(); queuedFiles.clear()
        target?.close(); target = null
        if (_status.value !is AgentStatus.Idle) {
            if (!quiet) post(Role.SYSTEM, if (by == CANCEL_CHAT) "Stopped." else "Stopped ($by).")
            parentId?.let { AgentRuntime.session(it) }?.taskStopped(this)
        }
        setStatus(AgentStatus.Idle)
        AgentRuntime.refreshOverlay()
        turnGuarded = false
    }

    fun systemNote(text: String) = post(Role.SYSTEM, text)

    /**
     * A key tapped under a question in this chat, however far back it is: a task's question goes to that task,
     * this chat's own to this chat. The answer shows here as the user's message either way.
     */
    fun answer(item: ChatItem, option: String) {
        val child = item.taskId?.let { AgentRuntime.session(it) }
        if (child == null) { send(option); return }
        _items.update { it + ChatItem(Role.USER, option) }
        AgentRuntime.changed(this)
        child.send(option)
    }

    /** The card for a background task this conversation started. */
    internal fun addTaskCard(child: Session) {
        _items.update { it + ChatItem(Role.TASK, child.title.value, taskId = child.id) }
        AgentRuntime.changed(this)
    }

    private fun post(role: Role, text: String, options: List<String> = emptyList(), attachments: List<Attachment> = emptyList(), taskId: String? = null) {
        Trace.log(id, role.name, text)
        // A turn passing on a task's question: its reply gets the question's buttons, and answers go to that task.
        val relaying = role == Role.ASSISTANT && options.isEmpty() && relayFallback != null
        val opts = if (relaying) relayOptions.also { relayOptions = emptyList() } else options
        val task = taskId ?: if (relaying && opts.isNotEmpty()) relayTaskId else null
        if (role == Role.ASSISTANT) assistantPosts++
        _items.update { it + ChatItem(role, text, options = opts, attachments = attachments, taskId = task) }
        AgentRuntime.changed(this)
    }

    /** Starts this conversation over with [items] (a demo reset): no model chain, no recap, nothing running. */
    internal fun resetTo(items: List<ChatItem>) {
        cancel(CANCEL_DELETED, quiet = true)
        _items.value = items
        previousResponseId = null; recap = ""; chainTurns = 0; pendingOutputs = JSONArray(); lastDemo = null
        AgentRuntime.saveNow(this)
    }

    /** Restores a saved conversation (see [AgentRuntime] persistence). */
    internal fun restore(items: List<ChatItem>) { _items.value = items }

    /**
     * The model's copy of the conversation (the Responses chain, or the Pi session) still holds a message the user
     * deleted: the next turn starts both over from what is left in the chat. Saved, so a restart doesn't undo it.
     */
    @Volatile internal var forget = false
    /** Set with [forget] for the Pi engine: its next turn opens a new Pi session. Cleared once the harness has it. */
    @Volatile internal var forgetPi = false

    /** The user deleted a message: it leaves the chat, and the model forgets it from the next turn on ([forget]). */
    fun deleteItem(itemId: Long) {
        val gone = _items.value.firstOrNull { it.id == itemId } ?: return
        _items.update { l -> l.filter { it.id != itemId } }
        // A chat named after the deleted message takes its name from the next one the user wrote (or starts over).
        if (!isMain && gone.role == Role.USER && title.value == gone.text.lineSequence().first().take(48))
            title.value = _items.value.firstOrNull { it.role == Role.USER && it.text.isNotBlank() }?.text?.lineSequence()?.first()?.take(48) ?: NEW_TITLE
        forget = true; forgetPi = true
        AgentRuntime.changed(this)
        AgentRuntime.saveNow(this)
    }

    /** What is left of the conversation, for a model starting over: its recent words, oldest first. */
    private fun recapOfItems(n: Int = 8): String = _items.value.filter { it.role != Role.ACTION && it.text.isNotBlank() }.takeLast(n)
        .joinToString("\n") { "${it.role.name.lowercase()}: ${it.text.take(300)}" }

    private fun applyForget() {
        if (!forget) return
        forget = false
        recap = recapOfItems(12)
        previousResponseId = null; chainTurns = 0; pendingOutputs = JSONArray()
    }

    private fun maybeResetChain() {
        if (chainTurns > MAX_TURNS_PER_CHAIN && previousResponseId != null) {
            recap = _items.value.takeLast(8).joinToString("\n") { "${it.role.name.lowercase()}: ${it.text.take(300)}" }
            previousResponseId = null; chainTurns = 0; pendingOutputs = JSONArray()
        }
    }

    /**
     * The express lane ([Express]): a plain weather / directions / play request is done without the main model.
     * Returns false, with nothing said, when the message is not one of those or the quick try could not finish it;
     * then the normal turn runs (and hears what the quick try did).
     */
    private suspend fun tryExpress(text: String): Boolean {
        if (parentId != null || taskReports.isNotEmpty() || !Express.eligible(ctx, text)) return false
        val start = android.os.SystemClock.uptimeMillis()
        // If this turns out not to be express, the harness is warm by the time the normal turn needs it.
        PiRuntime.prewarm(ctx)
        val m = Express.classify(ctx, text, _items.value.lastOrNull { it.role == Role.ASSISTANT }?.text)
        Trace.time(id, "express classify (${m?.action ?: "none"})", android.os.SystemClock.uptimeMillis() - start)
        if (m == null) return false
        turnStartedAt = start; turnSteps = 1; turnTools = 1
        val t = TaskTarget(false, this).also { it.request = text }
        target = t; runsOnScreen = true
        post(Role.ACTION, describeCall(m.action, m.args))
        val o = try { Express.run(ctx, m, t) } catch (c: kotlinx.coroutines.CancellationException) { throw c } catch (e: Throwable) { Log.w(TAG, "express", e); Express.Outcome(null, null) }
        if (o.reply == null) {
            o.note?.let { expressNotes.add(it) }
            target = null; t.close()
            return false
        }
        heard(o.reply)
        expressNotes.add("(Answered at once without you, earlier: the user said \"${text.take(200)}\" and the reply was \"${o.reply.take(300)}\".)")
        Trace.log(id, "express", "${m.action} ${m.args} -> ${o.reply}")
        Trace.time(id, "express total", android.os.SystemClock.uptimeMillis() - start)
        finish(o.reply, false, speak = if (turnByVoice) o.reply else null)
        return true
    }

    private suspend fun runTurn(requestText: String, attachments: List<Attachment> = emptyList(), onScreen: Boolean = false, guarded: Boolean = false,
                                asked: String = requestText, demo: Playbooks.Playbook? = null) {
        // Results of background tasks that ended since the last turn go in front of the message (the model's view only).
        val reports = drainTaskReports()
        if (reports.isNotEmpty()) heard(reports)
        val express = generateSequence { expressNotes.poll() }.joinToString("\n").let { if (it.isEmpty()) "" else it + "\n" }
        val routes = Routes.forPrompt(ctx, requestText).let { if (it.isEmpty()) "" else "(Saved routes that may fit; replay_route reaches the page in one call:\n$it)\n" }
        // The user's words after a parked question: the model hears what it asked, how long ago, and that its screen is gone.
        val resumed = if (asked.isNotBlank() && demo == null) parked?.also { parked = null; pendingOptions = emptyList() } else null
        if (resumed != null) {
            clearNotice(41); parentId?.let { AgentRuntime.session(it) }?.clearNotice(41)
            if (resumed.guarded) turnGuarded = true
            Trace.log(id, "resume", "parked question answered after ${(System.currentTimeMillis() - resumed.askedAt) / 1000} s")
        }
        val userText = reports + express + elsewhere() + liveTasks() + DeviceConditions.note(ctx) + routes + (resumed?.let { resumeNote(it) } ?: "") + requestText
        val svc = ControlService.instance
        openedMiniApp = false
        applyForget()
        maybeResetChain()
        setStatus(AgentStatus.Running(0, WORKING))
        turnStartedAt = android.os.SystemClock.uptimeMillis(); turnSteps = 0; turnTools = 0
        val model = Prefs.model(ctx)
        val effort = Prefs.effort(ctx)
        val maxSteps = Prefs.maxSteps(ctx)
        // A demo always plays on the agent's own screen, so Watch works and the user's screen stays theirs.
        val background = Prefs.backgroundMode(ctx) && com.agentos.workspace.Workspace.isReady && (!onScreen || demo != null)
        // A job's question answered after its hold ended: the new turn is as guarded as the one that asked, with no approval
        // carried over (the screen that approval was about is gone), so it asks again before anything sensitive.
        val target = TaskTarget(background, this).also { it.guarded = guarded || resumed?.guarded == true; it.request = asked }
        runsOnScreen = !background
        this.target = target
        if (demo != null) return runDemo(demo, target)
        // Which brain: the cloud model, or the on-device one (offline, no key, or by choice).
        var engine = try { ModelRouter.choose(ctx) } catch (t: Throwable) { Log.w(TAG, "router", t); ModelRouter.Choice(ModelRouter.Engine.CLOUD, null) }
        // Pi runs the cloud turns. On-device turns (when a build adds an on-device model) stay on this loop.
        if (engine.engine == ModelRouter.Engine.CLOUD && PiRuntime.enabled(ctx)) return runHarnessTurn(userText, asked, attachments, target, background)
        if (engine.engine == ModelRouter.Engine.LOCAL) { post(Role.SYSTEM, "Using the on-device model (${engine.reason})."); target.maxNodes = OnDeviceModel.installed?.maxNodes ?: target.maxNodes }
        else if (engine.reason != null) post(Role.SYSTEM, "Using the cloud model: ${engine.reason}.")
        // A chain belongs to one engine; switching engines starts a fresh one (with the recap).
        val localChain = previousResponseId?.startsWith("local:") == true
        if (previousResponseId != null && localChain != (engine.engine == ModelRouter.Engine.LOCAL)) {
            recap = _items.value.takeLast(8).joinToString("\n") { "${it.role.name.lowercase()}: ${it.text.take(300)}" }
            previousResponseId = null; chainTurns = 0; pendingOutputs = JSONArray()
        }
        val firstText = if (previousResponseId == null && recap.isNotBlank())
            "(Earlier in our conversation:\n$recap)\n\n$userText" else userText
        var input = JSONArray()
        // The Responses API insists every function_call gets an output before the chain continues.
        if (previousResponseId != null) for (i in 0 until pendingOutputs.length()) input.put(pendingOutputs.get(i))
        pendingOutputs = JSONArray()
        input.put(OpenAi.userMessage(firstText, attachments))

        // Only the memories that matter for this request (the cheap model picks when there are many).
        val space = if (engine.engine == ModelRouter.Engine.CLOUD) Memory.select(ctx, userText) else com.agentos.space.Space.forPrompt()
        val miniAppList = MiniAppPlugin.installed?.describe().orEmpty()
        val local = OnDeviceModel.installed
        fun instructionsFor(e: ModelRouter.Engine) =
            if (e == ModelRouter.Engine.LOCAL && local != null) local.instructions(apps.describe(), miniAppList, svc != null, background)
            else SystemPrompt.build(apps.describe(), miniAppList, svc != null, background,
                connectors = com.agentos.connect.Connectors.describe(ctx), space = space, navigator = Prefs.navigator(ctx), onUserScreen = turnFromAssist && !background)
        fun toolsFor(e: ModelRouter.Engine) = JSONArray().apply { AgentTools.definitions(ctx).forEach { if (e == ModelRouter.Engine.CLOUD || local == null || it.optString("name") in local.toolNames) put(it) } }
        var instructions = instructionsFor(engine.engine)
        var tools = toolsFor(engine.engine)
        var lastToolText: String? = null
        // What the user asked for in this turn (for the completion check), and whether it already corrected once.
        // A turn passing on task news checks only what the user asked ([asked]): the task's own turn was checked against its receipts.
        val request = StringBuilder(asked)
        var corrected = false
        var phoneActions = 0
        var backs = 0

        /** The turn ended well: maybe review memory (forking the finished chain), then report. */
        suspend fun wrapUp(summary: String?, usedPhone: Boolean, speak: String? = summary) {
            if (engine.engine == ModelRouter.Engine.CLOUD) {
                val nav = target.navSteps
                val hard = target.navConsults > 0 || target.noChange >= 2 || phoneActions + nav >= 10 || backs >= 2
                val app = target.lastPackage?.takeIf { hard && it != ctx.packageName }?.let { pkg -> apps.all().firstOrNull { it.packageName == pkg }?.label ?: pkg }
                val rid = previousResponseId
                val pending = JSONArray(pendingOutputs.toString())
                if (rid != null) scope.launch { Memory.review(ctx, this@Session, rid, pending, request.toString(), app, effort) }
            }
            try { Routes.learn(ctx, target) } catch (t: Throwable) { Log.w(TAG, "routes", t) }
            finish(summary, usedPhone, speak)
        }

        try {
            var step = 0
            var usedPhone = false
            while (true) {
                step++
                turnSteps = step
                if (step > maxSteps) {
                    finish("I stopped after $maxSteps steps without finishing. Tell me how you'd like to continue.", usedPhone)
                    return
                }
                if (step > 1) pendingOutputs = JSONArray()
                awaitAgentTurn(step)
                if (intervened) {
                    intervened = false
                    input.put(OpenAi.userText("(The user took over the workspace for a moment and operated it by hand. The screen may have changed: read it before continuing.)"))
                }
                while (true) { val late = lateInputs.poll() ?: break; request.append('\n').append(late); input.put(OpenAi.userText("(The user added while you were working:) $late")) }
                val r = try {
                    try {
                        ModelRouter.respond(ctx, engine.engine, model, instructions, input, tools, previousResponseId, effort, 6000, store = true)
                    } catch (e: OpenAi.ApiException) {
                        // A stale/unknown previous_response_id: start a fresh chain with the same input once.
                        if (previousResponseId != null && e.status in listOf(400, 404) && (e.message?.contains("previous_response", true) == true)) {
                            previousResponseId = null; chainTurns = 0
                            ModelRouter.respond(ctx, engine.engine, model, instructions, input, tools, null, effort, 6000, store = true)
                        } else if (e.message?.contains("mcp", true) == true && (0 until tools.length()).any { tools.getJSONObject(it).optString("type") == "mcp" }) {
                            // One of the user's MCP servers is down or misconfigured: carry on without them.
                            post(Role.SYSTEM, "Couldn't use your MCP servers this time: ${e.message?.take(160)}")
                            tools = JSONArray().apply { for (i in 0 until tools.length()) tools.getJSONObject(i).takeIf { it.optString("type") != "mcp" }?.let { put(it) } }
                            ModelRouter.respond(ctx, engine.engine, model, instructions, input, tools, previousResponseId, effort, 6000, store = true)
                        } else throw e
                    }
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException || engine.engine == ModelRouter.Engine.LOCAL || !ModelRouter.fallbackFor(ctx, e)) throw e
                    // The cloud is unreachable: carry on with the on-device model from the current state.
                    Log.w(TAG, "cloud failed (${e.message?.take(80)}), falling back to the on-device model")
                    post(Role.SYSTEM, "Cloud model unreachable; continuing on the on-device model.")
                    engine = ModelRouter.Choice(ModelRouter.Engine.LOCAL, "cloud unreachable"); target.maxNodes = OnDeviceModel.installed?.maxNodes ?: target.maxNodes
                    instructions = instructionsFor(engine.engine); tools = toolsFor(engine.engine)
                    previousResponseId = null; chainTurns = 0; pendingOutputs = JSONArray()
                    input = JSONArray().put(OpenAi.userText(firstText + (lastToolText?.let { "\n\n(You were already working on this with another model, which lost its connection. The most recent screen/result was:\n${it.take(3000)}\nContinue from there.)" } ?: "")))
                    ModelRouter.respond(ctx, engine.engine, model, instructions, input, tools, null, effort, 6000, store = true)
                }
                previousResponseId = r.id
                chainTurns++
                pendingOutputs = JSONArray().apply {
                    r.functionCalls.forEach { put(OpenAi.functionOutput(it.callId, "(the task ended before a result was recorded)")) }
                }

                // A plain reply is shown after the completion check below.
                if (r.text.isNotBlank() && r.functionCalls.isNotEmpty()) post(Role.ASSISTANT, r.text)

                if (r.functionCalls.isEmpty()) {
                    if (r.status == "incomplete") post(Role.SYSTEM, "(reply was cut off: ${r.incompleteReason ?: "limit"})")
                    // A plain reply can claim things too ("Sent!"): same check as done, one correction.
                    val issue = if (!corrected) CompletionCheck.check(r.text, request.toString(), target.effects) else null
                    if (issue != null) {
                        corrected = true
                        Log.i(TAG, "completion check: $issue")
                        input = JSONArray().put(OpenAi.userText("(System) $issue"))
                        continue
                    }
                    if (r.text.isNotBlank()) post(Role.ASSISTANT, r.text)
                    wrapUp(null, usedPhone, speak = r.text)
                    return
                }

                input = JSONArray()
                var paused = false
                for ((idx, fc) in r.functionCalls.withIndex()) {
                    awaitAgentTurn(step)
                    if (fc.name == "open_mini_app") openedMiniApp = true
                    val label = describeCall(fc.name, fc.arguments)
                    // Kept in the log (not shown in the chat); the status just says it is working.
                    post(Role.ACTION, label)
                    setStatus(AgentStatus.Running(step, WORKING))
                    AgentRuntime.refreshOverlay()
                    val phoneTool = fc.name !in setOf("done", "ask_user", "create_mini_app", "update_mini_app", "open_mini_app", "get_mini_app_logs",
                        "read_notifications", "summarize_notifications", "start_task", "task_status", "pass_to_task", "update_user", "weather") &&
                        fc.name !in com.agentos.connect.Connectors.names && fc.name !in com.agentos.jobs.JobTools.names && fc.name !in com.agentos.memory.Recall.names
                    if (phoneTool) usedPhone = !target.workspace
                    turnTools++
                    if (phoneTool && fc.name !in setOf("get_screen", "screenshot", "navigate", "read_list", "reconcile_effect")) phoneActions++
                    if (fc.name == "press" && fc.arguments.optString("key") == "back") backs++
                    // The assistant sheet covers the app the task is about: get it out of the way first.
                    if (phoneTool && turnFromAssist && !target.workspace && !assistHidden) {
                        assistHidden = true
                        assistUi?.hideForAction()
                    }

                    val outcome: ToolOutcome? = try {
                        AgentTools.execute(ctx, fc.name, fc.arguments, apps, target)
                    } catch (d: TaskDone) {
                        // The deterministic completion check: claims vs. this turn's receipts; one corrective step.
                        val issue = if (!corrected) CompletionCheck.check(d.summary, request.toString(), target.effects) else null
                        if (issue == null) { wrapUp(d.summary, usedPhone); return }
                        corrected = true
                        Log.i(TAG, "completion check: $issue")
                        for (later in r.functionCalls.drop(idx + 1)) input.put(OpenAi.functionOutput(later.callId, "(not executed)"))
                        ToolOutcome.error(issue)
                    } catch (q: NeedUser) {
                        // Every call needs an output; close this one and any later ones, then wait for the user.
                        input.put(OpenAi.functionOutput(fc.callId, "(waiting for the user's answer)"))
                        for (later in r.functionCalls.drop(idx + 1))
                            input.put(OpenAi.functionOutput(later.callId, "(not executed: waiting for the user)"))
                        post(Role.ASSISTANT, q.question, q.options)
                        Speaker.maybeSpeak(ctx, q.question)
                        AgentRuntime.refreshOverlay()
                        if (turnFromAssist && assistHidden) { assistHidden = false; withContext(Dispatchers.Main) { com.agentos.assist.AgentVoiceService.reshow() } }
                        else if (!turnFromAssist) announceQuestion(q.question, q.options)
                        val deferred = CompletableDeferred<JSONObject>()
                        pendingAnswer = deferred
                        setStatus(AgentStatus.WaitingForUser(q.question))
                        val answer = deferred.await()
                        answer.optJSONArray("content")?.optJSONObject(0)?.optString("text")?.let { request.append('\n').append(it) }
                        input.put(answer)
                        target.approvals++
                        paused = true
                        null
                    } catch (c: kotlinx.coroutines.CancellationException) {
                        throw c
                    } catch (t: Throwable) {
                        Log.w(TAG, "tool ${fc.name} failed", t)
                        ToolOutcome.error("Tool error: ${t.message}")
                    }
                    if (paused || outcome == null) break

                    val raw = (if (outcome.isError) "ERROR: " else "") + outcome.text
                    // One-time codes and passwords never reach the model: they become tokens type_text can resolve.
                    val text = secrets.redact(raw)
                    val hasSecret = text != raw
                    Trace.log(id, "result", "${fc.name}: $text")
                    heard(text)
                    lastToolText = text
                    input.put(OpenAi.functionOutput(fc.callId, text + if (hasSecret && outcome.imagePng != null) "\n(Screenshot withheld: the screen shows a code or password.)" else ""))
                    if (outcome.imagePng != null && !hasSecret && engine.engine == ModelRouter.Engine.CLOUD && AgentTools.screenshotsEnabled(ctx)) {
                        input.put(OpenAi.userImage(outcome.imagePng, "Screenshot from the ${fc.name} call above."))
                    }
                    if (outcome.isError && fc.name == "done") break
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: OpenAi.ApiException) {
            Log.w(TAG, "API error ${e.status}: ${e.message}")
            val msg = when (e.status) {
                0 -> e.message ?: "No API key configured."
                401 -> "Your OpenAI API key was rejected. Check it in Ubiq Settings."
                429 -> "OpenAI is rate-limiting this key right now (or the quota is used up). Try again in a moment."
                in 500..599 -> "OpenAI's API is having trouble (HTTP ${e.status}). Try again in a moment."
                else -> "API error ${e.status}: ${e.message?.take(200)}"
            }
            finish(msg, false)
        } catch (e: IOException) {
            Log.w(TAG, "network error", e)
            finish("I couldn't reach OpenAI: ${e.message?.take(120)}. Check the connection and try again" + (if (OnDeviceModel.installed?.textReady == false) ", or download the on-device model in Settings for offline use." else "."), false)
        } catch (e: Throwable) {
            Log.e(TAG, "turn failed", e)
            finish("Something went wrong: ${e.message?.take(300)}", false)
        }
    }

    /** The running harness turn's request (the user's words, for the completion check); null when no harness turn runs. */
    @Volatile private var harnessRequest: StringBuilder? = null
    @Volatile private var harnessUsedPhone = false
    /** The running harness turn's "tool" events read from its stream so far (ToolHost waits on it to keep the chat in order). */
    internal val harnessToolEvents = java.util.concurrent.atomic.AtomicInteger()

    /**
     * A turn run by the Pi harness (Prefs.engine "pi", docs/AGENT_DESIGN.md): Pi runs the loop and calls the phone's
     * tools back through [ToolHost], bound to this turn's [target]; the phone keeps the guards, the completion check
     * and the UI. Messages sent meanwhile steer the running turn.
     */
    private suspend fun runHarnessTurn(userText: String, requestText: String, attachments: List<Attachment>, target: TaskTarget, background: Boolean) {
        val request = StringBuilder(requestText)
        harnessRequest = request; harnessUsedPhone = false; parkedThisTurn = false
        // Picking memories (a small model call) runs while the harness starts, not before it.
        val space = scope.async {
            val t = android.os.SystemClock.uptimeMillis()
            Memory.select(ctx, requestText).also { Trace.time(id, "memory select", android.os.SystemClock.uptimeMillis() - t) }
        }
        val steering = scope.launch {
            while (true) {
                val late = lateInputs.poll()
                if (late == null) { kotlinx.coroutines.delay(400); continue }
                request.append('\n').append(late)
                Harness.steer(ctx, id, "(The user added while you were working:) $late")
            }
        }
        try {
            ToolHost.ensureStarted(ctx)
            ToolHost.bind(this, target)
            harnessToolEvents.set(0)
            // A deleted message: Pi starts a new session, told only what is still in the chat.
            val fresh = forgetPi
            val earlier = if (fresh) recapOfItems(12).let { r -> if (r.isBlank()) "" else "(Earlier in our conversation:\n$r)\n\n" } else ""
            val (fileNote, images) = Attachment.forHarness(attachments)
            var text = "(${SystemPrompt.now()})\n$earlier$userText$fileNote"
            var files = images
            var corrected = false
            var first = true
            val tPi = android.os.SystemClock.uptimeMillis()
            PiRuntime.use(ctx) {
                Trace.time(id, "pi ready", android.os.SystemClock.uptimeMillis() - tPi)
                val instructions = SystemPrompt.build(apps.describe(), MiniAppPlugin.installed?.describe().orEmpty(), ControlService.instance != null, background,
                    connectors = com.agentos.connect.Connectors.describe(ctx), space = space.await(), navigator = Prefs.navigator(ctx), harness = true,
                    onUserScreen = turnFromAssist && !background)
                while (true) {
                    val tModel = android.os.SystemClock.uptimeMillis()
                    var firstEvent = true
                    val r = Harness.turn(ctx, id, text, files, instructions, fresh = fresh && first) { ev ->
                        val type = ev.optString("type")
                        if (firstEvent && (type == "text" || type == "tool")) { firstEvent = false; Trace.time(id, "first $type", android.os.SystemClock.uptimeMillis() - tModel) }
                        when (type) {
                            // After a park the question is the last word: the model's "I'll wait" is not shown.
                            "text" -> ev.optString("text").takeIf { it.isNotBlank() && !parkedThisTurn }?.let { post(Role.ASSISTANT, it) }
                            "tool" -> harnessToolEvents.incrementAndGet()
                        }
                    }
                    Trace.time(id, "harness turn", android.os.SystemClock.uptimeMillis() - tModel)
                    if (first && fresh) forgetPi = false
                    first = false
                    // The question's hold ended: the turn ends here with the question still open (see [askFromHarness]).
                    if (parkedThisTurn) { finish(null, false, speak = null); return@use }
                    if (r.error != null) { finish("The Pi harness stopped: ${r.error.take(300)}", harnessUsedPhone); return@use }
                    // The same deterministic check as the built-in loop: claims vs. this turn's receipts, one correction.
                    val issue = if (!corrected) CompletionCheck.check(r.text, request.toString(), target.effects) else null
                    if (issue != null) {
                        corrected = true
                        Log.i(TAG, "completion check: $issue")
                        text = "(System) $issue"; files = emptyList()
                        continue
                    }
                    if (r.text.isNotBlank()) post(Role.ASSISTANT, r.text)
                    try { Routes.learn(ctx, target) } catch (t: Throwable) { Log.w(TAG, "routes", t) }
                    finish(null, harnessUsedPhone, speak = r.text)
                    return@use
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Harness.Unreachable) {
            // Stop drops the turn's connection; that is the user's Stop, not a broken harness.
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            finish("${e.message} ${if (PiRuntime.local(ctx)) "Switch" else "Start it with scripts/pi-dev.sh, or switch"} Settings > Brain > Engine back to Built-in.", false)
        } catch (e: Throwable) {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            Log.w(TAG, "harness turn failed", e)
            finish("Lost the Pi harness: ${e.message?.take(200)}", harnessUsedPhone)
        } finally {
            steering.cancel()
            space.cancel()
            ToolHost.unbind(id)
            harnessRequest = null
            // A question belongs to its turn. If the harness gave up on ask_user (its tool call timed out) and ended
            // the turn, the question must not stay open: the next message would go to it as an answer nobody reads,
            // and the chat would say "Working" with no turn running.
            synchronized(askLock) { pendingAnswer.also { pendingAnswer = null } }?.cancel()
            if (parked == null) pendingOptions = emptyList()
        }
    }

    /** Before each harness tool call: honor Take over, log it, keep the status and counters. Returns a refusal when over budget. */
    internal suspend fun beforeHarnessTool(name: String, args: JSONObject): String? {
        Trace.log(id, "call", "$name $args")
        awaitAgentTurn(turnSteps)
        if (parkedThisTurn) return "Not run: your question is still waiting for the user. End your turn now; their answer will come as a new message."
        if (name == "open_mini_app") openedMiniApp = true
        turnSteps++; turnTools++
        if (turnTools > Prefs.maxSteps(ctx) * 2) return "Step budget reached (${Prefs.maxSteps(ctx) * 2} tool calls). Stop now and tell the user where you got to."
        post(Role.ACTION, describeCall(name, args))
        setStatus(AgentStatus.Running(turnSteps, WORKING))
        AgentRuntime.refreshOverlay()
        val phoneTool = name !in setOf("ask_user", "create_mini_app", "update_mini_app", "open_mini_app", "get_mini_app_logs", "read_notifications", "summarize_notifications",
            "start_task", "task_status", "pass_to_task", "update_user", "weather") &&
            name !in com.agentos.connect.Connectors.names && name !in com.agentos.jobs.JobTools.names && name !in com.agentos.memory.Recall.names
        if (phoneTool) harnessUsedPhone = target?.workspace != true
        if (phoneTool && turnFromAssist && target?.workspace != true && !assistHidden) { assistHidden = true; assistUi?.hideForAction() }
        return null
    }

    /** After each harness tool call: remember what the model saw (memory provenance). Returns the text with any pending note. */
    internal fun afterHarnessTool(text: String): String {
        Trace.log(id, "result", text)
        heard(text)
        if (!intervened) return text
        intervened = false
        return "(The user took over the workspace for a moment and operated it by hand. The screen may have changed: read it before continuing.)\n$text"
    }

    /** A question the user is not looking at: a background task's goes to its conversation, anything else is a heads-up. */
    private fun announceQuestion(question: String, options: List<String> = emptyList()) {
        val parent = parentId?.let { AgentRuntime.session(it) }
        if (parent != null) parent.taskAsks(this, question, options) else notifyUser("${title.value.take(40)}: needs your answer", question, 41, options)
    }

    /** `ask_user` from the harness: the same question flow as the built-in loop; returns the user's answer. */
    /** What ask_user comes back with: the user's [text], or, when not [answered], the note that the question was parked. */
    internal class Asked(val text: String, val answered: Boolean)

    internal suspend fun askFromHarness(question: String, options: List<String> = emptyList(), scripted: Boolean = false): Asked {
        post(Role.ASSISTANT, question, options)
        Speaker.maybeSpeak(ctx, question)
        AgentRuntime.refreshOverlay()
        if (turnFromAssist && assistHidden) { assistHidden = false; withContext(Dispatchers.Main) { com.agentos.assist.AgentVoiceService.reshow() } }
        else if (!turnFromAssist) announceQuestion(question, options)
        val t = target
        // Something live to keep: a desk with an app on it, or the visible screen this turn has been working on.
        val live = t != null && (t.workspace || harnessUsedPhone)
        val before = deskScreen(t)
        val askedAt = System.currentTimeMillis()
        val deferred = CompletableDeferred<JSONObject>()
        answerFiles = emptyList()
        synchronized(askLock) { pendingAnswer = deferred }
        pendingOptions = options
        setStatus(AgentStatus.WaitingForUser(question))
        // The hold: the turn stays open for the answer, with its screen, for a while. A turn with no screen holds too:
        // an answer inside the hold is what approves a job's next sensitive step (a connector send has no screen), and
        // ending at once would make such a task ask, park and ask again without end.
        // A scripted take waits as long as it takes: it has no model to tell about a park.
        var reply = if (scripted) deferred.await() else kotlinx.coroutines.withTimeoutOrNull(HOLD_MS) { deferred.await() }
        if (reply == null) {
            val park = synchronized(askLock) {
                if (pendingAnswer !== deferred) false   // the answer (or a Stop) took it in the same instant
                else {
                    pendingAnswer = null
                    parked = Parked(question, askedAt, before?.second ?: t?.lastPackage?.let { appLabel(it) }, live, t?.guarded == true)
                    true
                }
            }
            if (park) {
                parkedThisTurn = true
                Trace.log(id, "park", "no answer in ${HOLD_MS / 1000} s" + if (live) "; releasing the screen" else "")
                return Asked("No answer yet. The question stays open in the chat, and the user's answer will arrive as a new message" +
                    (if (live) "; your screen is released until then" else "") + ". End your turn now: say nothing more and call no more tools.", false)
            }
            reply = deferred.await()
        }
        pendingOptions = emptyList()
        val answer = reply.optJSONArray("content")?.optJSONObject(0)?.optString("text") ?: ""
        harnessRequest?.append('\n')?.append(answer)
        // A quick answer is about the screen the question was asked on. A slow one is checked: a page can time out,
        // sign out or change while it waits, and an approval is only good for the screen it was given for.
        val waited = System.currentTimeMillis() - askedAt
        val changed = waited > TRUST_MS && before != null && deskScreen(t)?.let { it.first != before.first } == true
        if (!changed) t?.let { it.approvals++ }
        Trace.log(id, "answer", "after ${waited / 1000} s, screen ${if (before == null) "not checked" else if (changed) "changed" else "unchanged"}")
        return Asked(when {
            scripted -> answer
            changed -> "$answer\n(They took ${ago(waited)} to answer, and your screen changed while you waited. Read it again before acting on the answer: if the page, " +
                "sign-in, cart or price is not as it was, redo what is needed, and ask again before anything sensitive.)"
            waited > TRUST_MS -> "$answer\n(They took ${ago(waited)} to answer.)"
            else -> answer
        }, true)
    }

    /** The desk's screen as a signature and the app's name, to tell on resume whether it is still the screen the question was asked on. */
    private fun deskScreen(t: TaskTarget?): Pair<String, String>? {
        val d = t?.desk ?: return null
        val svc = ControlService.instance ?: return null
        return try { svc.snapshot(d.displayId, 60).let { Routes.signature(it) to appLabel(it.packageName) } } catch (e: Throwable) { Log.w(TAG, "desk screen", e); null }
    }
    private fun appLabel(pkg: String) = apps.all().firstOrNull { it.packageName == pkg }?.label ?: pkg
    private fun ago(ms: Long) = when {
        ms < 90_000 -> "${ms / 1000} seconds"
        ms < 90 * 60_000 -> "${ms / 60_000} minutes"
        ms < 36 * 3_600_000L -> "${ms / 3_600_000} hours"
        else -> "${ms / 86_400_000} days"
    }
    private fun resumeNote(p: Parked): String {
        val since = ago(System.currentTimeMillis() - p.askedAt)
        return "(You asked the user $since ago: \"${p.question.take(400)}\" Their next message follows; it is usually the answer, but it may be about something else. " +
            (if (p.hadScreen) "You stopped waiting and released your screen" + (p.where?.let { " (you were in $it)" } ?: "") + ", so nothing you had open is sure to be there: " +
                "look at where you are before acting, redo what is needed if the page, sign-in, cart or price is not as it was, and ask again before anything sensitive.)\n"
            else ")\n")
    }

    /** This turn opened a mini app on the user's screen: it is only wrapping up now, so the Stop chip stays off the app. */
    @Volatile var openedMiniApp = false
        private set

    /** True while a scripted demo plays: the Stop chip and the Stop buttons near Peek and Watch stay away. */
    @Volatile var demoRunning = false
        private set
    /** The playbook this chat played last ("keep doing this" follows the diet log). */
    @Volatile var lastDemo: String? = null
        private set
    /** Counts finished demos, so the live view can close and show the answer. */
    private val _demosFinished = MutableStateFlow(0)
    val demosFinished: StateFlow<Int> = _demosFinished

    /**
     * Plays a scripted demo ([Playbooks]) on the turn's screen instead of asking the model. Whatever goes wrong in a step,
     * the take still ends with its prepared answer; only a Stop ends it early.
     */
    private suspend fun runDemo(p: Playbooks.Playbook, target: TaskTarget) {
        Log.i(TAG, "demo playbook: ${p.name}")
        demoRunning = true
        target.scripted = true
        AgentRuntime.refreshOverlay()
        val awake = Playbooks.ScreenOn(ctx).also { it.hold() }
        var step = 0
        var answer = ""
        // The last answer that was one of an Ask's keys: what [Playbooks.Step.whenAnswer] steps look at.
        var choice = ""
        var reply: String? = null
        // What the take ended up telling the user, for this chat's model (null when it was stopped).
        var answered: String? = null
        var show: String? = null
        // A prepared mini app went up on the user's screen: the answer goes over it.
        var shownApp = false
        // Screens kept by [Playbooks.Step.Read], for a step that reads them.
        val reads = ArrayList<String>()
        suspend fun tool(name: String, args: JSONObject, log: Boolean = true): ToolOutcome? {
            if (log) post(Role.ACTION, describeCall(name, args))
            return try { AgentTools.execute(ctx, name, args, apps, target) } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.w(TAG, "demo $name", e); null
            }
        }
        try {
            // No phone control means no apps would open: say so rather than announce work nobody saw happen.
            var waited = 0
            while (ControlService.instance == null && waited < 5000) { kotlinx.coroutines.delay(250); waited += 250 }
            if (ControlService.instance == null) {
                Log.w(TAG, "demo ${p.name}: no phone control")
                demoRunning = false
                answered = "I can't control the phone right now, so I didn't do this. Turn Ubiq back on in Settings > Accessibility, then ask me again."
                finish(answered, usedPhone = false)
                AgentRuntime.saveNow(this)
                return
            }
            if (turnFromAssist && !assistHidden) { assistHidden = true; assistUi?.hideForAction() }
            // The last take's answer card must not sit over this one.
            ControlService.instance?.dismissAnswer()
            try {
                lastDemo = p.name
                steps@ for (s in p.steps) {
                    awake.hold()
                    if (s.ifAnswer?.containsMatchIn(choice) == false) continue
                    if (s.ifAsked?.containsMatchIn(target.request ?: "") == false) continue
                    when (s) {
                        is Playbooks.Step.Note -> { setStatus(AgentStatus.Running(++step, s.text)); AgentRuntime.refreshOverlay() }
                        is Playbooks.Step.Open -> tool("open_app", JSONObject().put("name", s.app))
                        is Playbooks.Step.Tap -> for (attempt in 0 until s.tries) {
                            val screen = tool("get_screen", JSONObject(), log = false)?.text
                            if (screen != null && s.unless != null && Playbooks.find(screen, s.unless) != null) break
                            if (screen != null && s.onlyIf != null && Playbooks.find(screen, s.onlyIf) == null) break
                            val id = screen?.let { Playbooks.find(it, s.text) }
                            if (id != null) { tool("tap", JSONObject().put("id", id)); break }
                            if (attempt < s.tries - 1) kotlinx.coroutines.delay(1200)
                        }
                        is Playbooks.Step.Scroll -> if (s.until == null && s.unless == null) tool("scroll", JSONObject().put("direction", s.direction)) else for (i in 0 until (if (s.until == null) 1 else 4)) {
                            val screen = tool("get_screen", JSONObject(), log = false)?.text
                            if (screen != null && s.unless != null && Playbooks.find(screen, s.unless) != null) break
                            if (screen != null && s.until != null && Playbooks.find(screen, s.until) != null) break
                            tool("scroll", JSONObject().put("direction", s.direction)); kotlinx.coroutines.delay(700)
                        }
                        is Playbooks.Step.Shot -> pendingShot = resultShot(target)
                        is Playbooks.Step.Wait -> kotlinx.coroutines.delay((s.seconds * 1000).toLong())
                        is Playbooks.Step.Ask -> {
                            val before = _status.value
                            answer = askFromHarness(s.question, s.options, scripted = true).text
                            if (s.options.isNotEmpty()) choice = s.options.firstOrNull { it.equals(answer.trim(), true) } ?: answer
                            setStatus(before)
                            if (s.stopIfNo && Playbooks.NO.containsMatchIn(answer)) break@steps
                        }
                        is Playbooks.Step.Run -> {
                            if (s.unless != null && tool("get_screen", JSONObject(), log = false)?.text?.let { Playbooks.find(it, s.unless) } != null) continue@steps
                            if (s.onlyIf != null && tool("get_screen", JSONObject(), log = false)?.text?.let { Playbooks.find(it, s.onlyIf) } == null) continue@steps
                            val args = JSONObject(s.args.mapValues { (_, v) -> if (v is List<*>) org.json.JSONArray(v) else v })
                            // A rule the last take already made is not made twice.
                            val again = s.tool == "add_job" && com.agentos.jobs.Jobs.jobs.value.any { it.title == args.optString("title") }
                            if (!again) tool(s.tool, args)
                        }
                        is Playbooks.Step.Approve -> {
                            val ok = com.agentos.pay.PaymentApproval.request(ctx, s.amount, s.merchant, s.what)
                            if (!ok) { answer = "no"; break@steps }
                        }
                        is Playbooks.Step.Pass -> com.agentos.ui.Passes.show(ctx, s.title, s.detail, s.code, s.format, s.wake)
                        is Playbooks.Step.Send -> {
                            val text = if (s.useAnswer) answer.trim() else s.text
                            val screen = tool("get_screen", JSONObject(), log = false)?.text
                            val field = screen?.let { Playbooks.editable(it) }
                            tool("type_text", JSONObject().put("text", text).apply { field?.let { put("id", it) } })
                            if (Playbooks.sendsLive(ctx)) {
                                val after = tool("get_screen", JSONObject(), log = false)?.text
                                after?.let { Playbooks.find(it, s.button) }?.let { tool("tap", JSONObject().put("id", it)) }
                            } else Log.i(TAG, "demo ${p.name}: sends are dry, typed only")
                        }
                        is Playbooks.Step.Custom -> s.action(ctx, reads, target.request ?: "")?.let { o ->
                            o.reply?.let { reply = it }
                            if (o.shown) shownApp = true
                        }
                        is Playbooks.Step.Read -> tool("get_screen", JSONObject(), log = false)?.text?.let { reads += it }
                        is Playbooks.Step.Reply -> reply = if (s.ifNo != null && Playbooks.NO.containsMatchIn(answer)) s.ifNo else s.text
                        is Playbooks.Step.Show -> show = s.pkg
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (t: Throwable) {
                Log.e(TAG, "demo ${p.name} step failed", t)
            }
            // A take that stopped on a no (Cancel, Not now, a failed approval) never reached its Reply: say the no version.
            val text = reply ?: p.steps.filterIsInstance<Playbooks.Step.Reply>().lastOrNull()?.let { r ->
                if (r.ifNo != null && Playbooks.NO.containsMatchIn(answer)) r.ifNo else r.text
            }
            // The answer must be on screen at the end: over the app the take ends on, or over wherever the user went.
            val overApp = show != null || shownApp || (!turnFromAssist && !com.agentos.ui.HomeActivity.isInForeground)
            if (overApp) assistHidden = false
            // The result as a picture in the chat: the agent's screen as the take left it (not when a mini app is the result).
            // A no (cancelled payment, Not now) leaves nothing worth a picture: the app is mid-way, often under a popup.
            val saidNo = Playbooks.NO.containsMatchIn(answer)
            if (saidNo) skipShot = true
            else if (!shownApp && p.answerCard && pendingShot == null) pendingShot = resultShot(target)
            // Hand the desk's app to the main screen as it is (the page the steps left it on). A launch
            // intent would bring its task forward on the desk instead, so it is only the fallback.
            show?.let { pkg ->
                val moved = tool("show_user", JSONObject(), log = false)
                if (moved == null || moved.isError) {
                    Log.w(TAG, "demo ${p.name}: show_user failed (${moved?.text}), launching $pkg")
                    withContext(Dispatchers.Main) { Playbooks.showOnScreen(ctx, pkg) }
                }
            }
            demoRunning = false
            answered = text ?: ""
            finish(text, usedPhone = false)
            AgentRuntime.saveNow(this)
            _demosFinished.value++
            if (overApp && text != null && p.answerCard) ControlService.instance?.showAnswer(text)
        } finally {
            demoRunning = false
            awake.release()
            // The model never saw this turn: tell it at the next one what the user asked and what the script did instead.
            taskReports.add("(Demo playbooks are on (Ubiq's Settings > Demo playbooks), so the scripted \"${p.name}\" demo answered the user's message " +
                "instead of you: \"${target.request.orEmpty().take(4000)}\". " + (answered?.let { "It replied: \"$it\")" } ?: "The user stopped it.)"))
        }
    }

    private fun describeCall(name: String, input: JSONObject): String = when (name) {
        "tap" -> "Tap element ${input.optInt("id")}"
        "long_press" -> "Long-press element ${input.optInt("id")}"
        "tap_xy" -> "Tap (${input.optInt("x")}, ${input.optInt("y")})"
        "type_text" -> "Type \"${input.optString("text").take(40)}\""
        "scroll" -> "Scroll ${input.optString("direction")}"
        "swipe" -> "Swipe"
        "press" -> "Press ${input.optString("key")}"
        "open_app" -> "Open ${input.optString("name")}"
        "wait" -> "Wait ${input.optDouble("seconds", 1.0)}s"
        "get_screen" -> "Read screen"
        "screenshot" -> "Look at screenshot"
        "create_mini_app" -> "Build mini app \"${input.optString("name")}\""
        "update_mini_app" -> "Update mini app \"${input.optString("name")}\""
        "open_mini_app" -> "Open mini app \"${input.optString("name")}\""
        "get_mini_app_logs" -> "Check mini app logs"
        "ask_user" -> "Ask you a question"
        "read_notifications" -> "Read notifications" + input.optString("app").let { if (it.isBlank()) "" else " from $it" }
        "open_notification" -> "Open notification ${input.optInt("id")}"
        "add_job" -> "Add job: ${input.optString("title")}"
        "list_jobs" -> "List jobs"
        "update_job" -> "Update job ${input.optInt("id")}"
        "delete_job" -> "Delete job ${input.optInt("id")}"
        "run_job_now" -> "Run job ${input.optInt("id")}"
        "job_result" -> "Read job ${input.optInt("id")}'s last run"
        "summarize_notifications" -> "Summarize ${input.optString("app")} notifications"
        "navigate" -> "Navigate: ${input.optString("goal").take(60)}"
        "start_task" -> "Start background task: ${input.optString("title").take(48)}"
        "task_status" -> "Check background tasks"
        "pass_to_task" -> "Pass your message to the task"
        "update_user" -> "Send news to the conversation"
        "show_user" -> "Put it on your screen"
        "weather" -> "Check the weather" + input.optString("place").let { if (it.isBlank()) "" else " in $it" }
        "directions" -> "Directions to ${input.optString("destination").take(48)}"
        "play_media" -> "Play ${input.optString("query").ifBlank { "music" }.take(48)}"
        "replay_route" -> "Follow saved route ${input.optString("id")}"
        "read_list" -> "Read list"
        "reconcile_effect" -> "Check op ${input.optInt("op_id")}"
        "done" -> "Finish"
        else -> name
    }

    /** Set by a demo before [finish]: the screenshot it took before handing its app to the user. */
    private var pendingShot: Attachment? = null
    /** Set by a demo that ended on a no: the turn finishes without a result screenshot. */
    private var skipShot = false
    /** The screenshot that went with this session's last answer, for the parent chat's copy of it. */
    @Volatile internal var lastShot: Attachment? = null
        private set

    /** The agent's own screen (its desk) as it is now, for the chat; null on the user's screen or when it has no frame. */
    private suspend fun resultShot(t: TaskTarget?): Attachment? {
        val desk = t?.desk ?: return null
        return try {
            withContext(Dispatchers.Default) {
                desk.screenshotPng(1600)?.let { png -> android.graphics.BitmapFactory.decodeByteArray(png, 0, png.size) }
                    ?.let { Attachment.fromBitmap("result.jpg", it, thumbDim = 900) }
            }
        } catch (e: Throwable) { Log.w(TAG, "result shot", e); null }
    }

    private suspend fun finish(summary: String?, usedPhone: Boolean, speak: String? = summary) {
        Trace.log(id, "finish", summary ?: "ok")
        // show_user put an app in front of the user: nothing of ours may cover it now.
        val handedOver = target?.handedOver != null
        target?.let { t ->
            val secs = (android.os.SystemClock.uptimeMillis() - turnStartedAt) / 1000.0
            lastStats = "steps=$turnSteps tools=$turnTools nav_steps=${t.navSteps} consults=${t.navConsults} secs=${"%.1f".format(secs)} " +
                "model=${Prefs.model(ctx)} effort=${Prefs.effort(ctx)} tier=${Prefs.serviceTier(ctx).ifBlank { "default" }} navigator=${Prefs.navigator(ctx)} " +
                "ops=[${secrets.redact(t.effects.entries.joinToString("; ") { it.line() })}]"
            Log.i("AgentOS/Stats", "${id}: $lastStats")
        }
        // A finished task shows what it ended on: a screenshot of the agent's screen goes with the answer.
        val shot = if (skipShot) null else pendingShot ?: if (usedPhone) resultShot(target) else null
        pendingShot = null
        skipShot = false
        lastShot = shot
        target?.close(); target = null
        // A turn that was to pass on task news but said nothing (or failed): the news is shown as it came.
        val unsaid = relayFallback?.takeIf { assistantPosts == relayMark }
        val unsaidOptions = relayOptions
        val unsaidFiles = relayFiles
        relayFallback = null; relayOptions = emptyList(); relayFiles = emptyList()
        if (unsaid != null) post(Role.ASSISTANT, unsaid, unsaidOptions, attachments = unsaidFiles, taskId = relayTaskId.takeIf { unsaidOptions.isNotEmpty() })
        else if (unsaidFiles.isNotEmpty()) post(Role.ASSISTANT, "", attachments = unsaidFiles)
        relayTaskId = null
        if (summary != null) post(Role.ASSISTANT, summary, attachments = listOfNotNull(shot))
        else if (shot != null) post(Role.ASSISTANT, "", attachments = listOf(shot))
        // A background task that parked its question still needs the user: its card and the island keep saying so. A
        // conversation's parked question is simply its last message.
        val waitingOn = parked?.takeIf { parentId != null }
        setStatus(if (waitingOn != null) AgentStatus.WaitingForUser(waitingOn.question) else AgentStatus.Idle)
        if (waitingOn == null) parentId?.let { AgentRuntime.session(it) }?.taskFinished(this, summary ?: speak ?: "")
        AgentRuntime.refreshOverlay()
        if (!speak.isNullOrBlank()) Speaker.maybeSpeak(ctx, speak)
        val fromAssist = turnFromAssist
        turnFromAssist = false
        // A job's run belongs to the main conversation, which posts and notifies its result (taskFinished).
        if (turnGuarded && summary != null && parentId == null) notifyUser(title.value.take(40), summary, 42)
        turnGuarded = false
        if (handedOver) {
            assistHidden = false
        } else if (fromAssist) {
            // Stay in the app the user asked about; the sheet comes back with the answer.
            if (assistHidden) withContext(Dispatchers.Main) { com.agentos.assist.AgentVoiceService.reshow() }
            assistHidden = false
        } else if (usedPhone && parentId == null && !com.agentos.voice.LiveCall.active) withContext(Dispatchers.Main) { AgentRuntime.focus(id); com.agentos.ui.HomeActivity.bringToFront(ctx) }
        // A background task never takes the screen when it ends: its card in the parent chat updates instead.
        // Messages queued after the agent's last step are not dropped: they become the next turn, with any task news.
        synchronized(news) {
            if (_status.value is AgentStatus.Running) return
            // A parked task waits for the user's words only; what they sent in the moment it parked is that answer.
            if (_status.value is AgentStatus.WaitingForUser && lateInputs.isEmpty() && queuedFiles.isEmpty()) return
            val queued = generateSequence { lateInputs.poll() }.toList()
            val files = generateSequence { queuedFiles.poll() }.toList()
            val told = generateSequence { news.poll() }.toList()
            val text = (queued + files.map { it.first }).filter { it.isNotBlank() }.joinToString("\n")
            if (told.isNotEmpty()) startRelay(told, text, files.flatMap { it.second })
            else if (queued.isNotEmpty() || files.isNotEmpty()) {
                turnFromAssist = false; assistHidden = false; turnGuarded = false
                setStatus(AgentStatus.Running(0, WORKING))
                job = scope.launch { runTurn(text, files.flatMap { it.second }) }
            }
        }
    }

    companion object {
        private const val TAG = "AgentOS/Session"
        private const val MAX_TURNS_PER_CHAIN = 60
        private const val MAX_UPDATES = 3
        private const val RELAY_ASK = "(The user hasn't seen this yet. Tell them what matters, briefly and in your own words.)"
        const val NEW_TITLE = "New chat"
        const val WORKING = "Working…"
        /** Who stopped a turn (see [cancel]). */
        const val CANCEL_CHAT = "chat"
        const val CANCEL_HOME = "home screen"
        const val CANCEL_VIEWER = "live view"
        const val CANCEL_SHEET = "assistant sheet"
        const val CANCEL_CHIP = "Stop chip"
        const val CANCEL_NOTIFICATION = "notification"
        const val CANCEL_DELETED = "chat deleted"
        private const val CANCEL_NEW_DEMO = "new demo"
        private const val DEMO_STOP_GUARD_MS = 1500L
        /** How long a question keeps its turn's screen. Under 5 minutes: the harness's tool call is cut at about 300 s. */
        private const val HOLD_MS = 285_000L
        /** An answer this quick is about the screen as it was; a slower one is checked against it. */
        private const val TRUST_MS = 30_000L
        private val YES = Regex("^\\W*(yes|yeah|yep|sure|ok|okay|please|do it|go ahead|confirm|correct|right)\\b", RegexOption.IGNORE_CASE)
    }
}
