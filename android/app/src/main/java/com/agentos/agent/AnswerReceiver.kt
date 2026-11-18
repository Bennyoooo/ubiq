package com.agentos.agent

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** A tapped answer on a question's notification: sent to that conversation as if typed there. */
class AnswerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val text = intent.getStringExtra(EXTRA_TEXT) ?: return
        val session = intent.getStringExtra(EXTRA_SESSION)?.let { AgentRuntime.session(it) } ?: return
        session.send(text)
        context.getSystemService(android.app.NotificationManager::class.java).cancel(intent.getIntExtra(EXTRA_NOTICE, 0))
    }

    companion object {
        const val EXTRA_SESSION = "session"
        const val EXTRA_TEXT = "text"
        const val EXTRA_NOTICE = "notice"
    }
}
