package com.agentos.assist

import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService

/**
 * The system-bound digital assistant. Holding the ASSISTANT role with this service (rather than
 * only an ACTION_ASSIST activity) is what makes the corner swipe and the power-button hold open
 * [AssistSession] as a sheet over the current app, with its screen content and screenshot.
 */
class AgentVoiceService : VoiceInteractionService() {
    /** The current app's screen, captured by the accessibility service for [open]. */
    class ScreenContext(val pkg: String, val text: String, val png: ByteArray?)

    override fun onReady() { super.onReady(); instance = this }
    override fun onShutdown() { instance = null; super.onShutdown() }

    companion object {
        @Volatile var instance: AgentVoiceService? = null
            private set

        @Volatile var pendingContext: ScreenContext? = null

        /**
         * Opens a fresh sheet over the current app (the nav-handle hold). The system does
         * not hand a self-opened assistant the screen, so it comes in [context] instead.
         */
        fun open(context: ScreenContext?): Boolean {
            val s = instance ?: return false
            pendingContext = context
            return try { s.showSession(Bundle().apply { putBoolean(ARG_FRESH, true) }, 0); true } catch (_: Throwable) { pendingContext = null; false }
        }
        const val ARG_FRESH = "agentos.fresh"

        /** Re-opens the sheet without capturing the screen again (e.g. to show a task's result). */
        fun reshow(args: Bundle = Bundle()): Boolean {
            val s = instance ?: return false
            return try { s.showSession(args, 0); true } catch (_: Throwable) { false }
        }
    }
}

class AgentSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = AssistSession(this)
}
