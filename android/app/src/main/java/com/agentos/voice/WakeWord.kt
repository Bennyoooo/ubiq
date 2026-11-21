package com.agentos.voice

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioRecordingConfiguration
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.agentos.data.Prefs
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * "Hey Ubiq": an always-on wake word, on the phone, with nothing sent anywhere until it is heard.
 *
 * sherpa-onnx's open-vocabulary keyword spotter (a 3.3M-parameter streaming zipformer, int8) listens to
 * 16 kHz mic audio on one thread; the phrase is a list of spellings in assets/wakeword/keywords.txt, so no
 * training was needed. Calibrated on synthetic speech (Piper, 120 LibriTTS speakers): 93% of single
 * "hey Ubiq" clips heard; in a 9-minute stream of chatter, pauses and 60 "hey Ubiq"s, 85–90% heard and no
 * false wakes; 4 of 360 near-homophones ("hey, you pick…", "hey you, big deal") taken for it.
 *
 * Energy gate: the model only runs while there is sound above the room's noise floor (plus 1.5 s after),
 * on a fresh stream that starts with the second of audio before the sound. In a quiet room that is close to
 * no CPU at all, and it helps recall too: the spotter misses phrases that start at some offsets into a long
 * stream (around 1.5 s and 3 s, found offline), and a fresh stream with a fixed pre-roll avoids them.
 *
 * The mic is shared politely: whenever anything else records (dictation, a live call, a phone call, the
 * camera), the spotter stops its own recording and starts again once the mic is free, so it never takes
 * audio from anyone. [WakeWordService] keeps it running in the background; a hit opens the assistant.
 */
object WakeWord {
    private const val TAG = "AgentOS.WakeWord"
    private const val RATE = 16000

    enum class State { Off, Listening, Yielding, Paused, Error }
    private val _state = MutableStateFlow(State.Off)
    val state: StateFlow<State> = _state

    /** Detection thresholds by sensitivity (0 = fewer false alarms … 2 = hears more). */
    private val THRESHOLDS = floatArrayOf(0.25f, 0.15f, 0.10f)

    @Volatile private var thread: Thread? = null
    @Volatile private var running = false
    /** Others are recording: our AudioRecord is stopped until they finish. */
    @Volatile private var othersRecording = false
    /** Screen off and the user only wants it while the screen is on. */
    @Volatile var paused = false
        set(v) { field = v; refreshState() }
    @Volatile private var ourSession = 0
    private val main = Handler(Looper.getMainLooper())
    private var spotter: KeywordSpotter? = null
    private var spotterSensitivity = -1
    private var callback: AudioManager.AudioRecordingCallback? = null

    var onWake: (() -> Unit)? = null

    @Volatile private var handsFreeUntil = 0L
    /** Called on a hit: the dictation that the assistant starts next ends by itself on a pause ([VoiceInput]). */
    fun markHandsFree() { handsFreeUntil = SystemClock.elapsedRealtime() + 4000 }
    fun takeHandsFree(): Boolean = (SystemClock.elapsedRealtime() < handsFreeUntil).also { handsFreeUntil = 0L }

    private fun refreshState() {
        if (!running) return
        _state.value = when { paused -> State.Paused; othersRecording -> State.Yielding; else -> State.Listening }
    }

    fun start(ctx: Context) {
        if (running) return
        val app = ctx.applicationContext
        running = true
        val am = app.getSystemService(AudioManager::class.java)
        callback = object : AudioManager.AudioRecordingCallback() {
            override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>) {
                val others = configs.any { it.clientAudioSessionId != ourSession || ourSession == 0 }
                if (others != othersRecording) { othersRecording = others; refreshState(); Log.i(TAG, if (others) "mic in use elsewhere: yielding" else "mic free again") }
            }
        }.also { am.registerAudioRecordingCallback(it, main) }
        othersRecording = am.activeRecordingConfigurations.isNotEmpty()
        refreshState()
        thread = Thread({ loop(app) }, "wake-word").apply { priority = Thread.NORM_PRIORITY; start() }
    }

    fun stop(ctx: Context) {
        if (!running) return
        running = false
        callback?.let { ctx.applicationContext.getSystemService(AudioManager::class.java).unregisterAudioRecordingCallback(it) }
        callback = null
        thread?.interrupt(); thread = null
        _state.value = State.Off
    }

    private fun spotter(ctx: Context): KeywordSpotter {
        val s = Prefs.wakeSensitivity(ctx).coerceIn(0, 2)
        spotter?.takeIf { spotterSensitivity == s }?.let { return it }
        spotter?.release()
        val cfg = KeywordSpotterConfig(
            featConfig = FeatureConfig(sampleRate = RATE, featureDim = 80),
            modelConfig = OnlineModelConfig(
                transducer = OnlineTransducerModelConfig(encoder = "wakeword/encoder.onnx", decoder = "wakeword/decoder.onnx", joiner = "wakeword/joiner.onnx"),
                tokens = "wakeword/tokens.txt", modelType = "zipformer2", numThreads = 1,
            ),
            maxActivePaths = 4,
            keywordsFile = "wakeword/keywords.txt",
            keywordsScore = 1.5f,
            keywordsThreshold = THRESHOLDS[s],
        )
        return KeywordSpotter(ctx.assets, cfg).also { spotter = it; spotterSensitivity = s }
    }

    @SuppressLint("MissingPermission")
    private fun loop(ctx: Context) {
        val kws = try { spotter(ctx) } catch (t: Throwable) {
            Log.e(TAG, "could not load the wake word model", t); _state.value = State.Error; running = false; return
        }
        val chunk = ShortArray(RATE / 10)   // 100 ms
        val floats = FloatArray(chunk.size)
        var lastHit = 0L
        while (running) {
            if (paused || othersRecording) { sleep(300); continue }
            val minBuf = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val rec = try {
                AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, RATE))
            } catch (t: Throwable) { Log.w(TAG, "AudioRecord", t); null }
            if (rec == null || rec.state != AudioRecord.STATE_INITIALIZED) { rec?.release(); _state.value = State.Error; sleep(5000); refreshState(); continue }
            ourSession = rec.audioSessionId
            var stream: OnlineStream? = null
            val preRoll = ArrayDeque<FloatArray>()
            var floor = -1.0
            var openFor = 0
            try {
                rec.startRecording()
                Log.i(TAG, "listening (sensitivity ${spotterSensitivity})")
                while (running && !paused && !othersRecording) {
                    val n = rec.read(chunk, 0, chunk.size)
                    if (n <= 0) { if (n < 0) break else continue }
                    var sum = 0.0
                    for (i in 0 until n) { val f = chunk[i] / 32768f; floats[i] = f; sum += f * f }
                    val samples = floats.copyOf(n)
                    // The noise floor follows quiet at once and noise slowly (about +3% a second).
                    val rms = Math.sqrt(sum / n)
                    floor = if (floor < 0 || rms < floor) rms else floor * 1.003
                    if (rms > maxOf(floor * 3.0, 0.002)) openFor = 15
                    else if (openFor > 0) openFor--
                    if (openFor == 0) {
                        if (stream != null) { stream.release(); stream = null }
                        preRoll.addLast(samples); if (preRoll.size > 10) preRoll.removeFirst()
                        continue
                    }
                    val st = stream ?: kws.createStream().also { s -> preRoll.forEach { s.acceptWaveform(it, RATE) }; preRoll.clear(); stream = s }
                    st.acceptWaveform(samples, RATE)
                    while (kws.isReady(st)) {
                        kws.decode(st)
                        val hit = kws.getResult(st).keyword
                        if (hit.isNotEmpty()) {
                            kws.reset(st)
                            val now = SystemClock.elapsedRealtime()
                            if (now - lastHit > 2500) { lastHit = now; Log.i(TAG, "heard \"$hit\""); main.post { onWake?.invoke() } }
                        }
                    }
                    if (spotterSensitivity != Prefs.wakeSensitivity(ctx).coerceIn(0, 2)) break
                }
            } catch (t: Throwable) {
                Log.w(TAG, "listening stopped", t); sleep(1000)
            } finally {
                stream?.release()
                runCatching { rec.stop() }; rec.release(); ourSession = 0
            }
            if (running && spotterSensitivity != Prefs.wakeSensitivity(ctx).coerceIn(0, 2)) {
                try { spotter(ctx) } catch (t: Throwable) { Log.e(TAG, "reload", t); _state.value = State.Error; running = false }
            }
        }
    }

    /** Runs a 16 kHz mono PCM16 WAV through the spotter and returns how many times it heard the phrase (debug). */
    fun scanWav(ctx: Context, path: String): Int {
        val bytes = java.io.File(path).readBytes()
        var off = 12; var data = -1; var len = 0
        while (off + 8 <= bytes.size) {
            val id = String(bytes, off, 4); val sz = java.nio.ByteBuffer.wrap(bytes, off + 4, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).int
            if (id == "data") { data = off + 8; len = minOf(sz, bytes.size - data); break }
            off += 8 + sz
        }
        require(data > 0) { "no data chunk" }
        val bb = java.nio.ByteBuffer.wrap(bytes, data, len).order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val pcm = FloatArray(bb.remaining()) { bb.get() / 32768f }
        val kws = KeywordSpotter(ctx.assets, KeywordSpotterConfig(
            featConfig = FeatureConfig(sampleRate = RATE, featureDim = 80),
            modelConfig = OnlineModelConfig(
                transducer = OnlineTransducerModelConfig(encoder = "wakeword/encoder.onnx", decoder = "wakeword/decoder.onnx", joiner = "wakeword/joiner.onnx"),
                tokens = "wakeword/tokens.txt", modelType = "zipformer2", numThreads = 1),
            keywordsFile = "wakeword/keywords.txt", keywordsScore = 1.5f,
            keywordsThreshold = THRESHOLDS[Prefs.wakeSensitivity(ctx).coerceIn(0, 2)]))
        val s = kws.createStream(); var hits = 0
        val padded = FloatArray(RATE / 2) + pcm + FloatArray(RATE)
        var i = 0
        while (i < padded.size) {
            s.acceptWaveform(padded.copyOfRange(i, minOf(i + 1600, padded.size)), RATE); i += 1600
            while (kws.isReady(s)) { kws.decode(s); if (kws.getResult(s).keyword.isNotEmpty()) { hits++; kws.reset(s) } }
        }
        s.release(); kws.release()
        return hits
    }

    private fun sleep(ms: Long) = try { Thread.sleep(ms) } catch (_: InterruptedException) {}

    /** Applies the setting: starts or stops [WakeWordService]. Call from a foreground context when turning it on. */
    fun sync(ctx: Context) {
        val app = ctx.applicationContext
        val want = Prefs.wakeWord(app) && app.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val i = Intent(app, WakeWordService::class.java)
        if (want) {
            try { app.startForegroundService(i) } catch (t: Throwable) { Log.w(TAG, "could not start the wake word service from here", t) }
        } else if (running || WakeWordService.alive) app.stopService(i)
    }
}
