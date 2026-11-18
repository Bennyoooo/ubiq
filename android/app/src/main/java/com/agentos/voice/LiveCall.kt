package com.agentos.voice

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.util.Base64
import android.util.Log
import com.agentos.agent.AgentRuntime
import com.agentos.agent.AgentStatus
import com.agentos.agent.ChatItem
import com.agentos.agent.Role
import com.agentos.agent.plainText
import com.agentos.data.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.math.sqrt

/**
 * Live mode: a phone call with Ubiq. An OpenAI realtime voice model holds the conversation (it hears the
 * user, answers in a voice, and can be interrupted), and everything that needs the phone goes to the agent
 * through the `ubiq` tool, into the main conversation, exactly as if it had been typed in the chat. What the
 * agent posts there (its replies, its questions, the results of background tasks) comes back into the call as a
 * system message for the voice to tell the user. So the call is a front for the agent, not a second agent.
 *
 * Runs in [LiveCallService] (a microphone foreground service), so the call goes on with the screen off or locked.
 */
object LiveCall {
    private const val TAG = "AgentOS/LiveCall"
    private const val RATE = 24_000
    private val MODELS = listOf("gpt-realtime-2.1", "gpt-realtime-2.1-mini")

    enum class Phase { Connecting, Listening, Hearing, Thinking, Speaking, Ended }
    enum class Who { USER, UBIQ }
    data class Line(val who: Who, val text: String, val id: Long = System.nanoTime())

    private val _phase = MutableStateFlow(Phase.Ended)
    val phase: StateFlow<Phase> = _phase
    private val _lines = MutableStateFlow<List<Line>>(emptyList())
    /** What was said on this call, for the call screen. */
    val lines: StateFlow<List<Line>> = _lines
    /** The microphone's level while the user talks, Ubiq's while it speaks (0..1). */
    val level = MutableStateFlow(0f)
    private val _muted = MutableStateFlow(false)
    val muted: StateFlow<Boolean> = _muted
    private val _speaker = MutableStateFlow(true)
    val speaker: StateFlow<Boolean> = _speaker
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error
    @Volatile var startedAt = 0L; private set

    val active: Boolean get() = _phase.value != Phase.Ended

    private val http by lazy { OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(0, TimeUnit.SECONDS).pingInterval(20, TimeUnit.SECONDS).build() }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var app: Context
    @Volatile private var ws: WebSocket? = null
    /** Which socket is current: callbacks of an older one (or one that failed before newWebSocket returned) check it. */
    @Volatile private var socketGen = 0
    private var jobs = mutableListOf<Job>()
    private var player: Player? = null
    private var focus: AudioFocusRequest? = null

    // Conversation state, touched from the socket thread and the agent watcher.
    private val lock = Any()
    @Volatile private var ready = false
    @Volatile private var hearing = false
    private var responseActive = false
    private var responseId: String? = null
    private var responseSpoke = false
    /** A response the user talked over: its remaining audio is dropped. */
    private var droppedResponse: String? = null
    /** Tool calls that want a spoken reply once their response is done. */
    private var replyAfterDone = false
    private var hangUpAfterSpeech = false
    /** Agent messages waiting for the voice to be free. */
    private val notices = ArrayDeque<String>()
    /** Main-conversation items the call has already passed on or skipped (ids are nanoTime, so not ordered across boots). */
    private val seenItems = HashSet<Long>()

    // ---------- control ----------

    fun start(ctx: Context) {
        if (active) return
        app = ctx.applicationContext
        if (!Prefs.isConfigured(app)) { _error.value = "Live mode needs your OpenAI API key (Settings)."; return }
        _error.value = null; _lines.value = emptyList(); _muted.value = false
        _speaker.value = Prefs.liveSpeaker(app)
        _phase.value = Phase.Connecting
        startedAt = System.currentTimeMillis()
        synchronized(lock) {
            ready = false; hearing = false; responseActive = false; responseId = null; droppedResponse = null
            replyAfterDone = false; hangUpAfterSpeech = false; notices.clear()
            seenItems.clear(); AgentRuntime.main.items.value.forEach { seenItems.add(it.id) }
        }
        app.startForegroundService(Intent(app, LiveCallService::class.java))
        setUpAudio()
        player = Player()
        connect(0)
        jobs += scope.launch { watchAgent() }
        jobs += scope.launch { phaseTicker() }
        com.agentos.control.Island.refresh()
    }

    fun hangUp() {
        if (!active) return
        val secs = (System.currentTimeMillis() - startedAt) / 1000
        _phase.value = Phase.Ended
        socketGen++
        ws?.close(1000, null); ws = null
        jobs.forEach { it.cancel() }; jobs.clear()
        player?.release(); player = null
        tearDownAudio()
        com.agentos.control.Island.refresh()
        buzz(longArrayOf(0, 60, 90, 60))  // two short buzzes: the call is over
        level.value = 0f
        if (::app.isInitialized) {
            app.stopService(Intent(app, LiveCallService::class.java))
            if (secs >= 3) AgentRuntime.main.systemNote("Call with Ubiq · ${secs / 60}:${"%02d".format(secs % 60)}")
        }
    }

    /** Felt, not heard: the phone stays quiet apart from Ubiq's voice. */
    private fun buzz(pattern: LongArray) {
        if (!::app.isInitialized) return
        runCatching {
            app.getSystemService(android.os.VibratorManager::class.java).defaultVibrator
                .vibrate(android.os.VibrationEffect.createWaveform(pattern, -1))
        }
    }

    fun toggleMute() { _muted.value = !_muted.value }

    fun toggleSpeaker() {
        _speaker.value = !_speaker.value
        Prefs.setLiveSpeaker(app, _speaker.value)
        route()
    }

    /** Debug and tests: [text] as if the user had said it. */
    fun sayAsUser(text: String) {
        if (!ready) return
        addLine(Who.USER, text)
        send(JSONObject().put("type", "conversation.item.create").put("item", message("user", "input_text", text)))
        createResponse()
    }

    // ---------- the realtime session ----------

    private fun connect(modelIndex: Int) {
        val model = Prefs.liveModel(app).ifBlank { null }?.takeIf { modelIndex == 0 } ?: MODELS.getOrElse(modelIndex) { MODELS.last() }
        val req = Request.Builder().url("wss://api.openai.com/v1/realtime?model=$model")
            .header("Authorization", "Bearer ${Prefs.apiKey(app)}").build()
        // The socket is ours from before newWebSocket returns: a fast failure (no network) must still end the call.
        val mine = ++socketGen
        ws = http.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "connected ($model)")
                webSocket.send(JSONObject().put("type", "session.update").put("session", sessionConfig()).toString())
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                if (mine != socketGen) return
                try { onEvent(JSONObject(text)) } catch (t: Throwable) { Log.w(TAG, "event", t) }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (mine != socketGen || !active) return
                Log.w(TAG, "socket failed (${response?.code})", t)
                // A model this key doesn't have: try the next one before giving up.
                if (!ready && response?.code in setOf(400, 403, 404) && modelIndex + 1 < MODELS.size) { connect(modelIndex + 1); return }
                _error.value = if (ready) "The call dropped." else if (response == null) "Couldn't connect. Check your internet." else "Couldn't reach Ubiq's voice (HTTP ${response.code})."
                hangUp()
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (mine == socketGen && active) { _error.value = "The call ended ($reason)."; hangUp() }
            }
        })
    }

    private fun sessionConfig(): JSONObject {
        val input = JSONObject()
            .put("format", JSONObject().put("type", "audio/pcm").put("rate", RATE))
            .put("turn_detection", JSONObject().put("type", "semantic_vad").put("eagerness", "auto").put("create_response", true).put("interrupt_response", true))
            .put("transcription", JSONObject().put("model", "gpt-transcribe"))
            .put("noise_reduction", JSONObject().put("type", if (_speaker.value) "far_field" else "near_field"))
        val output = JSONObject().put("format", JSONObject().put("type", "audio/pcm").put("rate", RATE)).put("voice", Prefs.voice(app))
        return JSONObject().put("type", "realtime").put("instructions", instructions())
            .put("output_modalities", JSONArray().put("audio"))
            .put("audio", JSONObject().put("input", input).put("output", output))
            .put("tools", tools()).put("tool_choice", "auto")
    }

    private fun instructions(): String {
        val now = java.text.SimpleDateFormat("EEEE d MMMM yyyy, HH:mm", java.util.Locale.getDefault()).format(java.util.Date())
        val recent = AgentRuntime.main.items.value.filter { it.role == Role.USER || it.role == Role.ASSISTANT }.takeLast(12)
            .joinToString("\n") { "${if (it.role == Role.USER) "user" else "ubiq"}: ${plainText(it.text).replace('\n', ' ').take(400)}" }
        val running = runningLine()
        return """
            You are Ubiq, the AI that runs the user's Android phone, and you are on a voice call with them. Talk like a
            warm, quick, capable friend on the phone: short spoken sentences, usually one or two, no lists, no markdown,
            no reading out links or long numbers unless asked. Match the user's language.

            You cannot touch the phone yourself. Ubiq's agent can: it uses the apps in the background (messages, calls,
            calendar, alarms, settings, maps, shopping, email, the web, the user's notes and memory). Call the `ubiq` tool
            for anything that needs the phone, the user's own information, or something current. Pass the request in the
            user's words with every detail they gave. Before calling it, say a few words like "On it" so the line isn't
            silent. Use `ubiq` as well to pass on the user's answer when the agent asked a question, or to change what a
            running task is doing. Answer small talk and general knowledge yourself.

            The agent reports back with system messages that start with "Ubiq agent:". Tell the user what it says, briefly
            and in your own words. Never say something is done until such a message says it is. If the agent asks a
            question, ask the user and pass the answer on with `ubiq`. While it works, keep talking normally; use
            `ubiq_status` if the user asks how it's going and `stop_task` if they want it stopped. When the user says
            goodbye or wants to hang up, say a short goodbye and call `end_call`.

            To the user you and the agent are one Ubiq: say "I" ("I'm setting it now", "done, it's set"), never mention an
            agent, a tool, a system message or passing things along.

            It is $now.${if (running.isNotEmpty()) "\nRight now the agent is: $running" else ""}
            ${if (recent.isNotEmpty()) "Earlier in your text chat with the user (background only; most recent last). Bring it up only if the user does, or if a task from it reports back:\n$recent" else ""}
        """.trimIndent()
    }

    private fun tools(): JSONArray {
        fun fn(name: String, desc: String, props: JSONObject = JSONObject(), required: List<String> = emptyList()) =
            JSONObject().put("type", "function").put("name", name).put("description", desc)
                .put("parameters", JSONObject().put("type", "object").put("properties", props).put("required", JSONArray(required)))
        return JSONArray()
            .put(fn("ubiq", "Hand a request to Ubiq's phone agent, which uses the apps in the background and reports back. Also passes the user's answer to a question the agent asked, or a change to a running task.",
                JSONObject().put("request", JSONObject().put("type", "string").put("description", "What the user wants, in their words, with every detail from the call.")), listOf("request")))
            .put(fn("ubiq_status", "What the phone agent and its background tasks are doing right now."))
            .put(fn("stop_task", "Stop what the phone agent is doing, including its background tasks."))
            .put(fn("end_call", "Hang up, after your goodbye has been heard."))
    }

    private fun onEvent(e: JSONObject) {
        when (e.optString("type")) {
            "session.updated" -> if (!ready) {
                ready = true
                Log.i(TAG, "session ready")
                buzz(longArrayOf(0, 40))  // one short buzz: connected
                jobs += scope.launch { micLoop() }
                // Greet first, like someone answering the phone.
                send(JSONObject().put("type", "conversation.item.create").put("item", message("system", "input_text", "The user just called you. Answer like picking up the phone: a short greeting, nothing else. Don't act on anything from the earlier chat unless they bring it up.")))
                createResponse()
            }
            "input_audio_buffer.speech_started" -> {
                Log.i(TAG, "user speaking")
                hearing = true
                // Barge-in: stop talking, and tell the server how much of the answer was actually heard.
                val cut = player?.interrupt()
                synchronized(lock) { if (responseActive) droppedResponse = responseId }
                if (cut != null && cut.second > 0) send(JSONObject().put("type", "conversation.item.truncate")
                    .put("item_id", cut.first).put("content_index", 0).put("audio_end_ms", cut.second))
            }
            "input_audio_buffer.speech_stopped" -> { hearing = false; Log.i(TAG, "user stopped") }
            "conversation.item.input_audio_transcription.completed" -> e.optString("transcript").trim().takeIf { it.isNotEmpty() }?.let { addLine(Who.USER, it) }
            "response.created" -> synchronized(lock) {
                responseActive = true; responseSpoke = false
                responseId = e.optJSONObject("response")?.optString("id")
            }
            "response.output_audio.delta" -> {
                val rid = e.optString("response_id")
                if (rid == droppedResponse) return
                responseSpoke = true
                player?.write(e.optString("item_id"), Base64.decode(e.optString("delta"), Base64.DEFAULT))
            }
            "response.output_audio_transcript.done" -> e.optString("transcript").trim().takeIf { it.isNotEmpty() }?.let { addLine(Who.UBIQ, it) }
            "response.function_call_arguments.done" -> onTool(e.optString("name"), e.optString("call_id"), e.optString("arguments"))
            "response.done" -> {
                val next = synchronized(lock) {
                    responseActive = false
                    val reply = replyAfterDone; replyAfterDone = false
                    val pending = notices.toList(); notices.clear()
                    reply to pending
                }
                for (n in next.second) send(JSONObject().put("type", "conversation.item.create").put("item", message("system", "input_text", n)))
                if (next.first || next.second.isNotEmpty()) createResponse()
                if (hangUpAfterSpeech) scope.launch { player?.drain(); delay(300); hangUp() }
            }
            "error" -> {
                val err = e.optJSONObject("error")
                val code = err?.optString("code").orEmpty()
                Log.w(TAG, "server error $code: ${err?.optString("message")}")
                // Harmless races: cancelling or truncating something that already ended, a response already running.
                if (code.contains("active_response") || code.contains("cancel") || code.contains("truncate") || code.contains("item")) return
                if (!ready) { _error.value = err?.optString("message") ?: "Live mode failed."; hangUp() }
            }
        }
    }

    private fun onTool(name: String, callId: String, arguments: String) {
        val args = runCatching { JSONObject(arguments) }.getOrDefault(JSONObject())
        Log.i(TAG, "tool $name")
        var speak = true
        val output = when (name) {
            "ubiq" -> {
                val request = args.optString("request").trim()
                if (request.isEmpty()) "Nothing to pass on: say what the user wants."
                else {
                    val main = AgentRuntime.main
                    val was = main.status.value
                    scope.launch(Dispatchers.Main) { AgentRuntime.toMain(request) }
                    // It said something like "on it" already: no need to talk again until the agent reports.
                    speak = !responseSpoke
                    when (was) {
                        is AgentStatus.WaitingForUser -> "Answer received; the task carries on and will report back."
                        is AgentStatus.Running -> "Added to the task in progress; it will report back."
                        else -> "Started; it will report back in a system message. Don't say it's done before that."
                    }
                }
            }
            "ubiq_status" -> runningLine().ifEmpty { "Nothing is running right now." }
            "stop_task" -> {
                val stopped = AgentRuntime.active().filter { it.isMain || it.parentId == AgentRuntime.MAIN_ID }
                scope.launch(Dispatchers.Main) { stopped.forEach { it.cancel() } }
                if (stopped.isEmpty()) "Nothing was running." else "Stopped ${stopped.size} task${if (stopped.size > 1) "s" else ""}."
            }
            "end_call" -> { hangUpAfterSpeech = true; speak = false; "Hanging up." }
            else -> "Unknown tool."
        }
        send(JSONObject().put("type", "conversation.item.create").put("item",
            JSONObject().put("type", "function_call_output").put("call_id", callId).put("output", output)))
        if (speak) synchronized(lock) { replyAfterDone = true }
    }

    private fun runningLine(): String = AgentRuntime.active().filter { it.isMain || it.parentId == AgentRuntime.MAIN_ID }.joinToString("; ") { s ->
        val st = s.status.value
        val what = if (s.isMain) "the current request" else "background task \"${s.title.value}\""
        when (st) {
            is AgentStatus.Running -> "$what: ${st.note}"
            is AgentStatus.WaitingForUser -> "$what is waiting for the user's answer to: ${st.question}"
            else -> what
        }
    }

    /** Passes on what the agent posts in the main conversation while the call is on. */
    private suspend fun watchAgent() {
        AgentRuntime.main.items.collect { items ->
            val fresh = synchronized(lock) {
                items.filter { seenItems.add(it.id) && it.role == Role.ASSISTANT }
            }
            fresh.forEach { notice(it) }
        }
    }

    private fun notice(item: ChatItem) {
        if (!ready) return
        val text = plainText(item.text).take(1500)
        val note = "Ubiq agent: $text" + if (item.options.isNotEmpty()) "\n(It is asking the user. Choices: ${item.options.joinToString(" / ")}.)" else ""
        val now = synchronized(lock) {
            if (responseActive || hearing) { notices.addLast(note); false } else { responseActive = true; true }
        }
        Log.i(TAG, "agent said (${if (now) "telling the user" else "queued"}): ${text.take(80)}")
        if (now) {
            send(JSONObject().put("type", "conversation.item.create").put("item", message("system", "input_text", note)))
            send(JSONObject().put("type", "response.create"))
        }
    }

    private fun createResponse() { send(JSONObject().put("type", "response.create")) }

    private fun message(role: String, kind: String, text: String) = JSONObject().put("type", "message").put("role", role)
        .put("content", JSONArray().put(JSONObject().put("type", kind).put("text", text)))

    private fun send(o: JSONObject) { ws?.send(o.toString()) }

    private fun addLine(who: Who, text: String) { _lines.update { (it + Line(who, text)).takeLast(40) } }

    private suspend fun phaseTicker() {
        while (scope.isActive && active) {
            if (ready) {
                val p = player
                _phase.value = when {
                    hearing -> Phase.Hearing
                    p != null && p.playing -> Phase.Speaking
                    synchronized(lock) { responseActive } -> Phase.Thinking
                    else -> Phase.Listening
                }
                if (p != null && p.playing) level.value = p.level
            }
            delay(60)
        }
    }

    // ---------- audio ----------

    @SuppressLint("MissingPermission")
    private suspend fun micLoop() {
        val minBuf = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        // VOICE_COMMUNICATION turns on the phone's echo canceller, so Ubiq doesn't hear itself on speaker.
        val rec = AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, RATE / 5 * 2))
        if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); _error.value = "The microphone is busy."; hangUp(); return }
        val aec = if (AcousticEchoCanceler.isAvailable()) runCatching { AcousticEchoCanceler.create(rec.audioSessionId)?.apply { enabled = true } }.getOrNull() else null
        val ns = if (NoiseSuppressor.isAvailable()) runCatching { NoiseSuppressor.create(rec.audioSessionId)?.apply { enabled = true } }.getOrNull() else null
        val buf = ShortArray(RATE / 25)  // 40 ms
        val bytes = ByteBuffer.allocate(buf.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        try {
            rec.startRecording()
            while (scope.isActive && active) {
                val n = rec.read(buf, 0, buf.size)
                if (n <= 0) continue
                if (_muted.value) { if (player?.playing != true) level.value = 0f; continue }
                var sum = 0.0
                bytes.clear()
                for (i in 0 until n) { sum += buf[i] * buf[i].toDouble(); bytes.putShort(buf[i]) }
                if (player?.playing != true) level.value = (sqrt(sum / n) / 2500.0).coerceIn(0.0, 1.0).toFloat()
                send(JSONObject().put("type", "input_audio_buffer.append").put("audio", Base64.encodeToString(bytes.array(), 0, n * 2, Base64.NO_WRAP)))
            }
        } finally {
            runCatching { rec.stop() }; aec?.release(); ns?.release(); rec.release()
        }
    }

    private fun setUpAudio() {
        val am = app.getSystemService(AudioManager::class.java)
        val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
        focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT).setAudioAttributes(attrs).build().also { am.requestAudioFocus(it) }
        am.mode = AudioManager.MODE_IN_COMMUNICATION
        route()
    }

    /** A headset when one is connected, otherwise the loudspeaker or the earpiece (the speaker key). */
    private fun route() {
        val am = app.getSystemService(AudioManager::class.java)
        val devices = am.availableCommunicationDevices
        val headset = devices.firstOrNull { it.type in HEADSETS }
        val want = headset ?: devices.firstOrNull { it.type == if (_speaker.value) AudioDeviceInfo.TYPE_BUILTIN_SPEAKER else AudioDeviceInfo.TYPE_BUILTIN_EARPIECE }
        if (want != null) am.setCommunicationDevice(want) else am.clearCommunicationDevice()
    }

    /** True when the voice comes out of the earpiece (the call screen then turns off at the ear). */
    fun onEarpiece(ctx: Context): Boolean {
        return ctx.getSystemService(AudioManager::class.java).communicationDevice?.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
    }

    private fun tearDownAudio() {
        val am = app.getSystemService(AudioManager::class.java)
        am.clearCommunicationDevice()
        am.mode = AudioManager.MODE_NORMAL
        focus?.let { am.abandonAudioFocusRequest(it) }; focus = null
    }

    private val HEADSETS = setOf(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_USB_HEADSET)

    /**
     * Ubiq's voice: 24 kHz PCM16 written on its own thread, so a full buffer never holds up the socket (which must
     * keep reading to hear the user start talking). Knows which item is playing and how much of it was heard.
     */
    private class Player {
        private val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setBufferSizeInBytes(maxOf(AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT), RATE))
            .setTransferMode(AudioTrack.MODE_STREAM).build()
        private class Chunk(val item: String, val pcm: ByteArray, val gen: Int)
        private val queue = LinkedBlockingQueue<Chunk>()
        @Volatile private var released = false
        /** Bumped by [interrupt]: chunks of an older generation are thrown away. */
        @Volatile private var gen = 0
        private var odd: Byte? = null
        // Frames handed to the track since the last flush, and where the current item started.
        @Volatile private var written = 0L
        @Volatile private var item: String? = null
        @Volatile private var itemStart = 0L
        @Volatile var level = 0f; private set

        private val thread = Thread({
            track.play()
            while (!released) {
                val c = try { queue.take() } catch (_: InterruptedException) { break }
                if (c.gen != gen || released) continue
                if (c.item != item) { item = c.item; itemStart = written }
                var sum = 0.0
                val bb = ByteBuffer.wrap(c.pcm).order(ByteOrder.LITTLE_ENDIAN)
                val n = c.pcm.size / 2
                for (i in 0 until n) { val s = bb.getShort(i * 2).toDouble(); sum += s * s }
                if (n > 0) level = (sqrt(sum / n) / 4000.0).coerceIn(0.0, 1.0).toFloat()
                val w = track.write(c.pcm, 0, c.pcm.size)
                if (w > 0 && c.gen == gen) written += w / 2
            }
        }, "ubiq-live-player").apply { start() }

        val playing: Boolean get() = !released && (queue.isNotEmpty() || head() < written)

        private fun head(): Long = runCatching { track.playbackHeadPosition.toLong() and 0xffffffffL }.getOrDefault(written)

        fun write(itemId: String, bytes: ByteArray) {
            if (released) return
            val data = odd?.let { byteArrayOf(it) + bytes } ?: bytes
            val even = data.size and 1.inv()
            odd = if (even < data.size) data.last() else null
            if (even > 0) queue.put(Chunk(itemId, if (even == data.size) data else data.copyOf(even), gen))
        }

        /** Stops the voice at once. Returns the item that was playing and how many ms of it were heard. */
        fun interrupt(): Pair<String, Int>? {
            if (!playing) return null
            val heard = item?.let { it to ((head() - itemStart).coerceAtLeast(0) * 1000 / RATE).toInt() }
            gen++
            queue.clear(); odd = null
            runCatching { track.pause(); track.flush(); track.stop(); track.play() }
            written = 0; itemStart = 0; item = null; level = 0f
            return heard
        }

        suspend fun drain() { while (playing) delay(40) }

        fun release() {
            released = true
            thread.interrupt()
            queue.clear()
            runCatching { track.pause(); track.flush(); track.stop() }
            track.release()
        }
    }
}
