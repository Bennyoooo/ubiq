package com.agentos.notify

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

/**
 * Notification access: records what arrives (for `read_notifications` and digests) and fires
 * trigger jobs (see jobs/Jobs), and holds back notifications during focus (see [Focus]).
 */
class NotificationWatcher : NotificationListenerService() {
    override fun onListenerConnected() {
        connected = true
        instance = this
        NotificationStore.init(this)
        com.agentos.jobs.Jobs.init(this)
        Focus.init(this)
        try { activeNotifications?.forEach { record(it, fire = false) } } catch (_: Throwable) {}
    }

    override fun onListenerDisconnected() { connected = false; instance = null }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        // Focus may hold it back (snooze it); it is still recorded, so the agent and the summary can see it.
        try { Focus.onPosted(this, sbn) } catch (t: Throwable) { Log.w("AgentOS/Notify", "focus failed", t) }
        record(sbn, fire = true)
    }

    private fun record(sbn: StatusBarNotification, fire: Boolean) {
        try {
            if (sbn.packageName == packageName) return
            val n = sbn.notification ?: return
            if (sbn.isOngoing || n.flags and Notification.FLAG_GROUP_SUMMARY != 0 || n.flags and Notification.FLAG_FOREGROUND_SERVICE != 0) return
            val ex = n.extras
            val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty().trim()
            val text = (ex.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: ex.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty().trim()
            if (title.isEmpty() && text.isEmpty()) return
            val app = try { packageManager.getApplicationLabel(packageManager.getApplicationInfo(sbn.packageName, 0)).toString() } catch (_: Throwable) { sbn.packageName }
            val added = NotificationStore.add(sbn.key, sbn.packageName, app, title, text, sbn.postTime, n.contentIntent) ?: return
            if (com.agentos.memory.Lifelog.enabled(this, com.agentos.memory.Lifelog.Kind.NOTIFICATION) && !com.agentos.memory.Lifelog.excluded(this, sbn.packageName))
                com.agentos.memory.Lifelog.clean("$app: $title ${text}".take(1000))?.let { com.agentos.memory.Lifelog.add(com.agentos.memory.Lifelog.Kind.NOTIFICATION, sbn.packageName, it, sbn.postTime) }
            if (fire) { com.agentos.jobs.Jobs.onNotification(this, added) }
        } catch (t: Throwable) { Log.w("AgentOS/Notify", "record failed", t) }
    }

    companion object {
        @Volatile var connected = false
            private set
        /** The bound listener, for reading what is on the phone right now ([Unreplied]). */
        @Volatile var instance: NotificationWatcher? = null
            private set

        fun hasAccess(ctx: Context): Boolean {
            val flat = Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners") ?: return false
            val me = ComponentName(ctx, NotificationWatcher::class.java)
            return flat.split(':').any { ComponentName.unflattenFromString(it) == me }
        }
    }
}
