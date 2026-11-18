package com.agentos.agent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.agentos.ui.HomeActivity
import java.util.concurrent.ConcurrentHashMap

/**
 * One ongoing notification per running session ("Working on: ..."), so a task the user can't
 * see is still in the shade and on the lock screen, with Stop, Peek for a task on the hidden
 * workspace, and Answer when the agent asked a question (opens that chat). On Android 16 it asks to be a promoted ongoing notification (the
 * status-bar chip). Removed when the session goes idle.
 */
object TaskNotifications {
    private const val CHANNEL = "tasks"
    private const val ACTION_STOP = "com.agentos.TASK_STOP"
    private const val EXTRA_ID = "session"
    private const val PEEK_REQUEST = 0x1000
    /** What each session's notification shows now, to skip reposting the same thing. */
    private val shown = ConcurrentHashMap<String, String>()

    private fun nid(sessionId: String) = 7000 + (sessionId.hashCode() and 0x3ff)

    /** At start: notifications of tasks from a previous process are stale (those tasks are gone). */
    fun clearStale(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        try { nm.activeNotifications.filter { it.notification.channelId == CHANNEL }.forEach { nm.cancel(it.id) } } catch (_: Throwable) {}
    }

    /** Called whenever a session changes (see AgentRuntime.changed); cheap when nothing visible changed. */
    fun update(ctx: Context, s: Session) {
        if (s.origin == "debug") return
        val st = s.status.value
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        if (st is AgentStatus.Idle) {
            if (shown.remove(s.id) != null) nm.cancel(nid(s.id))
            return
        }
        val title = s.title.value.ifBlank { Session.NEW_TITLE }.take(60)
        val (line, asking) = when (st) {
            is AgentStatus.Running -> (if (st.step > 0) "${st.note} · step ${st.step}" else st.note) to false
            is AgentStatus.WaitingForUser -> "Needs your answer: ${st.question}" to true
            else -> "" to false
        }
        val canPeek = st is AgentStatus.Running && !s.runsOnScreen
        val key = "$title|$line|$asking|$canPeek"
        if (shown[s.id] == key) return
        shown[s.id] = key
        if (nm.getNotificationChannel(CHANNEL) == null)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Running tasks", NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) })
        val open = PendingIntent.getActivity(ctx, nid(s.id), Intent(ctx, HomeActivity::class.java)
            // A task's notification opens the conversation it reports to, where its card is.
            .putExtra(HomeActivity.EXTRA_OPEN_CHAT, true).putExtra(HomeActivity.EXTRA_SESSION, s.parentId ?: s.id)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getBroadcast(ctx, nid(s.id), Intent(ctx, StopReceiver::class.java).setAction(ACTION_STOP).putExtra(EXTRA_ID, s.id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val b = Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(com.agentos.R.drawable.ic_launcher_monochrome)
            .setContentTitle("Working on: $title")
            .setContentText(line)
            .setStyle(Notification.BigTextStyle().bigText(line))
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(if (asking) Notification.CATEGORY_REMINDER else Notification.CATEGORY_PROGRESS)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
        if (!asking) b.setProgress(0, 0, true)
        if (asking) b.addAction(Notification.Action.Builder(null, "Answer", open).build())
        if (canPeek) {
            // Its own request code: PendingIntents that differ only in extras would otherwise share `open`.
            val peek = PendingIntent.getActivity(ctx, nid(s.id) + PEEK_REQUEST, Intent(ctx, HomeActivity::class.java)
                .putExtra(HomeActivity.EXTRA_SHOW_WORKSPACE, true).putExtra(HomeActivity.EXTRA_SESSION, s.id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            b.addAction(Notification.Action.Builder(null, "Peek", peek).build())
        }
        b.addAction(Notification.Action.Builder(null, "Stop", stop).build())
        // Not promoted to a status bar chip: the island's light already says working / needs you (user feedback 2026-11-08).
        try { nm.notify(nid(s.id), b.build()) } catch (t: Throwable) { android.util.Log.w("AgentOS/TaskNotif", "notify", t) }
    }

    /** The notification's Stop button. */
    class StopReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION_STOP) return
            intent.getStringExtra(EXTRA_ID)?.let { AgentRuntime.session(it) }?.cancel(Session.CANCEL_NOTIFICATION)
        }
    }
}
