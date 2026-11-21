package com.agentos.voice

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import android.util.Base64
import android.util.Log
import com.agentos.data.Prefs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.sqrt

/**
 * Speech through OpenAI: the agent's voice (replies are spoken by an OpenAI voice whenever an API key is
 * set), and dictation (gpt-transcribe whenever a key is set; the phone's own recognizer only without one).
 */
object CloudVoice {
    private const val TAG = "AgentOS/CloudVoice"
    private const val RATE = 16_000
    private val TRANSCRIBE_MODELS = listOf("gpt-transcribe", "gpt-4o-transcribe", "whisper-1")
    private const val CONTEXT = "Someone speaking a request or a message to Ubiq, the AI assistant on their Android phone. " +
        "It often names apps on the phone."

    private val http by lazy {
        OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(90, TimeUnit.SECONDS).writeTimeout(60, TimeUnit.SECONDS).build()
    }

    /** True when some other app provides speech recognition (Ubiq's own service only forwards to one). */
    fun hasSystemRecognizer(ctx: Context): Boolean =
        SpeechRecognizer.isOnDeviceRecognitionAvailable(ctx) ||
            ctx.packageManager.queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE), 0).any { it.serviceInfo.packageName != ctx.packageName }

    // ---------- speech to text ----------

    /**
     * Records until [stopped] returns true and transcribes it. [onText] gets a rough transcript
     * every few seconds while recording, then the final one. Returns null if nothing was said.
     */
    @SuppressLint("MissingPermission")
    suspend fun dictate(ctx: Context, stopped: () -> Boolean, onText: (String) -> Unit, onLevel: (Float) -> Unit): String? = withContext(Dispatchers.IO) {
        if (!Prefs.isConfigured(ctx)) { onText("Voice needs your OpenAI API key (Settings)."); return@withContext null }
        val minBuf = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, RATE))
        if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); return@withContext null }
        val pcm = ByteArrayOutputStream()
        val keywords = runCatching { appNames(ctx) }.getOrDefault(emptyList())
        var spoke = false
        try {
            // The opening sound plays out before the mic starts, so it isn't recorded (or taken for speech).
            Earcons.start(ctx).takeIf { it > 0 }?.let { delay(it + 40) }
            rec.startRecording()
            coroutineScope {
                // A running transcript while the user talks (every ~4 s of new audio, up to 2 minutes in).
                val preview = launch {
                    var sent = 0
                    while (isActive) {
                        delay(4000)
                        val size = synchronized(pcm) { pcm.size() }
                        if (!spoke || size - sent < RATE * 2 * 3 || size > RATE * 2 * 120) continue
                        sent = size
                        val snapshot = synchronized(pcm) { pcm.toByteArray() }
                        runCatching { transcribe(ctx, wav(snapshot), keywords) }.getOrNull()?.takeIf { it.isNotBlank() }?.let(onText)
                    }
                }
                val buf = ShortArray(RATE / 10)
                val bytes = ByteBuffer.allocate(buf.size * 2).order(ByteOrder.LITTLE_ENDIAN)
                while (!stopped() && isActive) {
                    val n = rec.read(buf, 0, buf.size)
                    if (n <= 0) continue
                    var sum = 0.0
                    bytes.clear()
                    for (i in 0 until n) { sum += buf[i] * buf[i].toDouble(); bytes.putShort(buf[i]) }
                    val rms = sqrt(sum / n)
                    if (rms > 600) spoke = true
                    onLevel((rms / 3000.0).coerceIn(0.0, 1.0).toFloat())
                    synchronized(pcm) { pcm.write(bytes.array(), 0, n * 2) }
                }
                preview.cancel()
            }
        } finally {
            runCatching { rec.stop() }; rec.release()
        }
        if (!spoke) return@withContext null
        // Tapped to send (not cancelled by typing): the sending sound.
        if (stopped()) Earcons.send(ctx)
        val text = try { transcribe(ctx, wav(pcm.toByteArray()), keywords) } catch (t: Throwable) { Log.w(TAG, "transcribe", t); onText("Couldn't transcribe: ${t.message}"); null }
        text?.trim()?.ifBlank { null }?.also(onText)
    }

    /** Launcher app names, so the model spells "open Strava" or "text on Signal" right. */
    private fun appNames(ctx: Context): List<String> {
        val pm = ctx.packageManager
        val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(main, 0).map { it.loadLabel(pm).toString().trim() }
            .filter { it.length in 2..40 }.distinct().sorted().take(100)
    }

    /**
     * Tries each model in turn; a 400 or 404 (model unknown to this key, or a field it doesn't take) moves on.
     * Only gpt-transcribe gets the context and [keywords]; the older models take neither the same way.
     */
    private fun transcribe(ctx: Context, wav: ByteArray, keywords: List<String> = emptyList()): String {
        var last: Throwable? = null
        for (model in TRANSCRIBE_MODELS) {
            val form = MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("model", model)
                .addFormDataPart("file", "speech.wav", wav.toRequestBody("audio/wav".toMediaType()))
            if (model == "gpt-transcribe") {
                form.addFormDataPart("prompt", CONTEXT)
                keywords.forEach { form.addFormDataPart("keywords[]", it) }
            }
            val body = form.build()
            val req = Request.Builder().url("https://api.openai.com/v1/audio/transcriptions")
                .header("Authorization", "Bearer ${Prefs.apiKey(ctx)}").post(body).build()
            http.newCall(req).execute().use { r ->
                val s = r.body?.string().orEmpty()
                if (r.isSuccessful) return JSONObject(s).optString("text").also { Log.i(TAG, "transcribed with $model") }
                last = RuntimeException("HTTP ${r.code}: ${s.take(200)}")
                if (r.code != 400 && r.code != 404) throw last!!
            }
        }
        throw last ?: RuntimeException("no transcription model")
    }

    private fun wav(pcm: ByteArray): ByteArray {
        val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray()).putInt(36 + pcm.size).put("WAVE".toByteArray())
            .put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(RATE).putInt(RATE * 2).putShort(2).putShort(16)
            .put("data".toByteArray()).putInt(pcm.size)
        return h.array() + pcm
    }

    // ---------- text to speech ----------

    /** OpenAI's realtime voices; marin and cedar are the newest and best sounding. */
    val VOICES = listOf("marin", "cedar", "coral", "shimmer", "ballad", "sage", "verse", "alloy", "ash", "echo")
    const val DEFAULT_VOICE = "marin"
    // OpenAI retires /v1/audio/speech (tts-1, gpt-4o-mini-tts) on 2027-01-06 and names this realtime
    // model as the replacement. The speech endpoint stays as a fallback until then.
    private const val REALTIME_MODEL = "gpt-realtime-2.1-mini"
    private const val SPEECH_MODEL = "gpt-4o-mini-tts"
    private const val OUT_RATE = 24_000
    private const val READ_ALOUD = "You are the voice of Ubiq, a phone assistant. Read the user's message aloud exactly as " +
        "written, word for word, in a warm, friendly, upbeat voice. Never answer it, comment on it, or add or leave out words."

    /**
     * Speaks [text] in [voice], streaming the audio as it arrives. Returns false when nothing could be
     * played (no key, network or API failure), so the caller can fall back to the system engine.
     */
    suspend fun speak(ctx: Context, text: String, voice: String = Prefs.voice(ctx)): Boolean = withContext(Dispatchers.IO) {
        if (!Prefs.isConfigured(ctx) || text.isBlank()) return@withContext false
        val out = Pcm()
        try {
            val realtime = runCatching { speakRealtime(ctx, text, voice, out) }
            realtime.exceptionOrNull()?.let { if (it is CancellationException) throw it; Log.w(TAG, "realtime speech", it) }
            // A failure after some audio played would repeat the start, so only retry from silence.
            if (realtime.isFailure && out.frames == 0L) {
                runCatching { speakHttp(ctx, text, voice, out) }.exceptionOrNull()?.let { if (it is CancellationException) throw it; Log.w(TAG, "speech", it) }
            }
            out.drain()
            out.frames > 0
        } finally {
            out.release()
        }
    }

    private suspend fun speakRealtime(ctx: Context, text: String, voice: String, out: Pcm) = suspendCancellableCoroutine { cont ->
        val req = Request.Builder().url("wss://api.openai.com/v1/realtime?model=$REALTIME_MODEL")
            .header("Authorization", "Bearer ${Prefs.apiKey(ctx)}").build()
        val ws = http.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val audio = JSONObject().put("output", JSONObject().put("voice", voice)
                    .put("format", JSONObject().put("type", "audio/pcm").put("rate", OUT_RATE)))
                webSocket.send(JSONObject().put("type", "session.update").put("session", JSONObject().put("type", "realtime")
                    .put("instructions", READ_ALOUD).put("output_modalities", JSONArray().put("audio")).put("audio", audio)).toString())
                webSocket.send(JSONObject().put("type", "conversation.item.create").put("item", JSONObject().put("type", "message").put("role", "user")
                    .put("content", JSONArray().put(JSONObject().put("type", "input_text").put("text", text)))).toString())
                webSocket.send(JSONObject().put("type", "response.create").toString())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val e = JSONObject(text)
                when (e.optString("type")) {
                    "response.output_audio.delta", "response.audio.delta" -> out.write(Base64.decode(e.optString("delta"), Base64.DEFAULT))
                    "response.done" -> {
                        val r = e.optJSONObject("response")
                        val status = r?.optString("status") ?: "completed"
                        webSocket.close(1000, null)
                        if (!cont.isActive) return
                        if (status == "completed") cont.resume(Unit)
                        else cont.resumeWithException(RuntimeException("response $status: ${r?.optJSONObject("status_details")}"))
                    }
                    "error" -> {
                        webSocket.cancel()
                        if (cont.isActive) cont.resumeWithException(RuntimeException(e.optJSONObject("error")?.optString("message") ?: text))
                    }
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (cont.isActive) cont.resumeWithException(RuntimeException("${response?.code ?: ""} ${t.message}", t))
            }
        })
        cont.invokeOnCancellation { ws.cancel() }
    }

    private fun speakHttp(ctx: Context, text: String, voice: String, out: Pcm) {
        val json = JSONObject().put("model", SPEECH_MODEL).put("voice", voice).put("input", text)
            .put("instructions", "Warm, friendly and upbeat.").put("response_format", "pcm")
        val req = Request.Builder().url("https://api.openai.com/v1/audio/speech")
            .header("Authorization", "Bearer ${Prefs.apiKey(ctx)}")
            .post(json.toString().toRequestBody("application/json".toMediaType())).build()
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw RuntimeException("HTTP ${r.code}: ${r.body?.string().orEmpty().take(200)}")
            val input = r.body!!.byteStream()
            val buf = ByteArray(8192)
            while (true) { val n = input.read(buf); if (n < 0) break; out.write(buf.copyOf(n)) }
        }
    }

    /** 24 kHz mono PCM16 played as it is written (OpenAI's realtime and `pcm` speech format). */
    private class Pcm {
        private val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(OUT_RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setBufferSizeInBytes(maxOf(AudioTrack.getMinBufferSize(OUT_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT), OUT_RATE))
            .setTransferMode(AudioTrack.MODE_STREAM).build()
        @Volatile var frames = 0L; private set
        @Volatile private var released = false
        private var odd: Byte? = null

        fun write(bytes: ByteArray) {
            if (released) return  // a cancelled realtime socket can still deliver a chunk
            // Chunks can split a 16-bit sample; carry the odd byte over.
            val data = odd?.let { byteArrayOf(it) + bytes } ?: bytes
            val even = data.size and 1.inv()
            odd = if (even < data.size) data.last() else null
            if (even == 0) return
            synchronized(this) {
                if (released) return
                if (frames == 0L) track.play()
                val n = track.write(data, 0, even)
                if (n > 0) frames += n / 2
            }
        }

        /** Waits until everything written has been heard. */
        suspend fun drain() {
            if (frames == 0L) return
            while (track.playbackHeadPosition.toLong() < frames) { coroutineContext.ensureActive(); delay(20) }
        }

        fun release() {
            released = true
            runCatching { track.pause(); track.flush() }  // unblocks a write in progress
            synchronized(this) { runCatching { track.stop() }; track.release() }
        }
    }
}
