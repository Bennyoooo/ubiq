package com.agentos.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Dictation. With an OpenAI key it records and transcribes with gpt-transcribe ([CloudVoice.dictate]),
 * which gets app names and short commands right far more often. Without one it uses the platform
 * recognizer (on-device on Pixels).
 *
 * [dictate] keeps listening across pauses until [stop] is called: the recognizer ends a segment
 * after a moment of silence, so each segment's text is kept and a new one starts straight away.
 */
class VoiceInput(private val ctx: Context) {
    private var recognizer: SpeechRecognizer? = null
    @Volatile private var stopRequested = false

    val available: Boolean get() = SpeechRecognizer.isRecognitionAvailable(ctx) || com.agentos.data.Prefs.isConfigured(ctx)

    /**
     * Everything said until [stop], with the running transcript streamed to [onText]. Returns null
     * if recognition failed before anything was heard. Cancelling the coroutine discards it.
     */
    suspend fun dictate(onText: (String) -> Unit, onLevel: (Float) -> Unit = {}): String? {
        stopRequested = false
        // OpenAI transcription whenever a key is set, and on phones with no recognizer of their own (plain AOSP).
        if (com.agentos.data.Prefs.isConfigured(ctx) || !CloudVoice.hasSystemRecognizer(ctx))
            return CloudVoice.dictate(ctx, { stopRequested }, onText, onLevel)
        val r = if (SpeechRecognizer.isOnDeviceRecognitionAvailable(ctx))
            SpeechRecognizer.createOnDeviceSpeechRecognizer(ctx) else SpeechRecognizer.createSpeechRecognizer(ctx)
        recognizer = r
        val heard = StringBuilder()
        fun joined(more: String) = listOf(heard.toString(), more).filter { it.isNotBlank() }.joinToString(" ")
        try {
            var failures = 0
            while (true) {
                val seg = segment(r) { onText(joined(it)) }
                if (seg.text.isNotBlank()) { onText(joined(seg.text)); if (heard.isNotEmpty()) heard.append(' '); heard.append(seg.text); failures = 0 }
                if (stopRequested) break
                when (seg.error) {
                    null, SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {}
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY, SpeechRecognizer.ERROR_CLIENT -> { delay(250); if (++failures > 4) break }
                    else -> if (++failures > 1) break
                }
            }
        } finally {
            r.destroy(); recognizer = null
        }
        return heard.toString().ifBlank { null }
    }

    private class Segment(val text: String, val error: Int?)

    /** One recognizer run, until it decides the speaker paused (or [stop]). */
    private suspend fun segment(r: SpeechRecognizer, onPartial: (String) -> Unit): Segment =
        suspendCancellableCoroutine { cont ->
            var last = ""
            var finished = false
            fun done(s: Segment) { if (!finished) { finished = true; if (cont.isActive) cont.resume(s) } }
            r.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                // A stop with nothing final yet still keeps what was shown.
                override fun onError(error: Int) { done(Segment(last, error)) }
                override fun onResults(results: Bundle?) {
                    done(Segment(results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: last, null))
                }
                override fun onPartialResults(partialResults: Bundle?) {
                    partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.takeIf { it.isNotBlank() }?.let { last = it; onPartial(it) }
                }
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, ctx.packageName)
                // Honoured by some recognizers; the restart loop in dictate() covers the rest.
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 4000L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 4000L)
            }
            r.startListening(intent)
            cont.invokeOnCancellation { r.cancel() }
        }

    /** Ends dictation; [dictate] returns once the recognizer delivers the last words. */
    fun stop() { stopRequested = true; recognizer?.stopListening() }
}
