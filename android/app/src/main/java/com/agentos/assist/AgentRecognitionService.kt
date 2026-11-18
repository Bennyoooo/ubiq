package com.agentos.assist

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import android.util.Log

/**
 * A voice-interaction service must name a recognition service, and Android makes it the phone's
 * default speech recognizer. Ubiq has no recognizer of its own, so this one forwards every
 * request to the recognizer that was there before (Google's on a Pixel): apps that ask the system
 * for speech recognition keep getting the same engine.
 */
class AgentRecognitionService : RecognitionService() {
    private var delegate: SpeechRecognizer? = null

    override fun onStartListening(intent: Intent, cb: Callback) {
        delegate?.destroy(); delegate = null
        val target = target()
        if (target == null) { safe { cb.error(SpeechRecognizer.ERROR_CLIENT) }; return }
        val r = SpeechRecognizer.createSpeechRecognizer(this, target)
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = safe { cb.readyForSpeech(params ?: Bundle()) }
            override fun onBeginningOfSpeech() = safe { cb.beginningOfSpeech() }
            override fun onRmsChanged(rmsdB: Float) = safe { cb.rmsChanged(rmsdB) }
            override fun onBufferReceived(buffer: ByteArray?) = safe { if (buffer != null) cb.bufferReceived(buffer) }
            override fun onEndOfSpeech() = safe { cb.endOfSpeech() }
            override fun onError(error: Int) = safe { cb.error(error) }
            override fun onResults(results: Bundle?) = safe { cb.results(results ?: Bundle()) }
            override fun onPartialResults(partialResults: Bundle?) = safe { cb.partialResults(partialResults ?: Bundle()) }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        delegate = r
        r.startListening(intent)
    }

    override fun onStopListening(cb: Callback) { delegate?.stopListening() }
    override fun onCancel(cb: Callback) { delegate?.cancel() }
    override fun onDestroy() { delegate?.destroy(); delegate = null; super.onDestroy() }

    private fun target(): ComponentName? {
        val found = packageManager.queryIntentServices(Intent(SERVICE_INTERFACE), 0)
            .map { ComponentName(it.serviceInfo.packageName, it.serviceInfo.name) }
            .filter { it.packageName != packageName }
        return found.firstOrNull { it.packageName == "com.google.android.tts" }
            ?: found.firstOrNull { it.packageName == "com.google.android.googlequicksearchbox" }
            ?: found.firstOrNull()
    }

    private inline fun safe(block: () -> Unit) { try { block() } catch (t: Throwable) { Log.w("AgentOS/Recognizer", "callback failed", t) } }
}
