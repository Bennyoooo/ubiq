package com.agentos.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import com.agentos.data.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Text-to-speech for assistant replies: an OpenAI voice (CloudVoice) when an API key is set, the
 * phone's own engine when it isn't or OpenAI can't be reached.
 */
object Speaker {
    private var tts: TextToSpeech? = null
    @Volatile private var ready = false
    private lateinit var app: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var cloud: Job? = null

    fun init(ctx: Context) {
        app = ctx.applicationContext
        tts = TextToSpeech(ctx.applicationContext) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (ready) tts?.language = Locale.getDefault()
        }
    }

    fun maybeSpeak(ctx: Context, text: String) {
        // On a call the call's own voice tells the user (voice/LiveCall).
        if (!Prefs.speakReplies(ctx) || LiveCall.active) return
        speak(text)
    }

    fun speak(text: String, voice: String? = null) {
        val clean = text.replace(Regex("[*_`#>]"), "").take(1500)
        stop()
        if (!Prefs.isConfigured(app)) { system(clean); return }
        cloud = scope.launch { if (!CloudVoice.speak(app, clean, voice ?: Prefs.voice(app))) system(clean) }
    }

    private fun system(text: String) {
        if (ready) tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "agentos-${System.nanoTime()}")
    }

    fun stop() { cloud?.cancel(); cloud = null; tts?.stop() }
}
