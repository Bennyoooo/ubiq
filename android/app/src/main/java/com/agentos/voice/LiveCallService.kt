package com.agentos.voice

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Person
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import com.agentos.R

/**
 * Keeps a live call going while the screen is off, locked or showing another app: a microphone foreground
 * service with an ongoing-call notification (Android's call chip in the status bar), which reopens the call
 * screen when tapped and has a hang-up button.
 */
class LiveCallService : Service() {
    private var wake: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Calls with Ubiq", NotificationManager.IMPORTANCE_DEFAULT).apply { setSound(null, null); setShowBadge(false) })
        val open = PendingIntent.getActivity(this, 0, Intent(this, LiveCallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE)
        val end = PendingIntent.getBroadcast(this, 1, Intent(this, HangUpReceiver::class.java), PendingIntent.FLAG_IMMUTABLE)
        val ubiq = Person.Builder().setName("Ubiq").setImportant(true).build()
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentIntent(open)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_CALL)
            .setUsesChronometer(true).setWhen(LiveCall.startedAt.takeIf { it > 0 } ?: System.currentTimeMillis())
            .setStyle(Notification.CallStyle.forOngoingCall(ubiq, end))
            .build()
        startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        if (wake == null) wake = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Ubiq:live-call").apply { acquire(60 * 60_000L) }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        wake?.takeIf { it.isHeld }?.release(); wake = null
        // The system stopped us (or the app was swiped away): the call cannot go on without the service.
        LiveCall.hangUp()
        super.onDestroy()
    }

    class HangUpReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) { LiveCall.hangUp() }
    }

    companion object {
        private const val CHANNEL = "live_call"
        private const val ID = 6100
    }
}
