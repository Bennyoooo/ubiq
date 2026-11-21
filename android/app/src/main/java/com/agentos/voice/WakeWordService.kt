package com.agentos.voice

import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import com.agentos.R
import com.agentos.data.Prefs
import com.agentos.ui.SettingsActivity

/**
 * Keeps "Hey Ubiq" ([WakeWord]) listening while Ubiq is in the background: a microphone foreground service
 * with a quiet notification (Android requires one, and shows the mic dot while it listens). With the screen
 * off it holds a partial wake lock so the CPU keeps hearing, unless the user chose screen-on only.
 *
 * A hit gives a short buzz (no sound: the phone may be next to someone asleep), wakes the screen and opens
 * the assistant listening, over whatever app is open and over the lock screen.
 */
class WakeWordService : Service() {
    private var wake: PowerManager.WakeLock? = null

    private val screen = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) = applyScreen(i.action != Intent.ACTION_SCREEN_OFF)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        alive = true
        registerReceiver(screen, IntentFilter().apply { addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SCREEN_OFF) })
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_PAUSE) { Prefs.setWakeWord(this, false); stopSelf(); return START_NOT_STICKY }
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Hey Ubiq", NotificationManager.IMPORTANCE_MIN).apply {
                description = "Shown while Ubiq listens for \"Hey Ubiq\""; setSound(null, null); setShowBadge(false)
            })
        val open = PendingIntent.getActivity(this, 0, Intent(this, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE)
        val off = PendingIntent.getService(this, 1, Intent(this, WakeWordService::class.java).setAction(ACTION_PAUSE), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle("Listening for \"Hey Ubiq\"")
            .setContentText("On the phone only. Nothing leaves it until you say it.")
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Turn off", off).build())
            .build()
        try {
            startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } catch (t: Throwable) {
            // Android only lets a microphone service start while Ubiq is in the foreground; the next time it is, sync() retries.
            Log.w("AgentOS.WakeWord", "not allowed to listen from the background yet", t); stopSelf(); return START_NOT_STICKY
        }
        WakeWord.onWake = { onWake() }
        WakeWord.start(this)
        applyScreen(getSystemService(PowerManager::class.java).isInteractive)
        return START_STICKY
    }

    private fun applyScreen(on: Boolean) {
        val listen = on || Prefs.wakeScreenOff(this)
        WakeWord.paused = !listen
        if (!on && listen) {
            if (wake == null) wake = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Ubiq:hey-ubiq").apply { setReferenceCounted(false) }
            wake?.acquire(8 * 60 * 60_000L)
        } else wake?.takeIf { it.isHeld }?.release()
    }

    private fun onWake() {
        if (LiveCall.active) return
        Log.i("AgentOS.WakeWord", "Hey Ubiq: opening the assistant")
        runCatching { getSystemService(Vibrator::class.java).vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK)) }
        val pm = getSystemService(PowerManager::class.java)
        if (!pm.isInteractive) {
            @Suppress("DEPRECATION")
            pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP, "Ubiq:hey-ubiq-wake").acquire(3000)
        }
        Speaker.stop()
        openAssistant(this, getSystemService(KeyguardManager::class.java).isKeyguardLocked)
    }

    override fun onDestroy() {
        unregisterReceiver(screen)
        WakeWord.stop(this)
        WakeWord.onWake = null
        wake?.takeIf { it.isHeld }?.release(); wake = null
        alive = false
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "hey_ubiq"
        private const val ID = 6200
        private const val CHANNEL_HEARD = "hey_ubiq_heard"
        private const val ID_HEARD = 6201
        private const val ACTION_PAUSE = "com.agentos.HEY_UBIQ_OFF"
        @Volatile var alive = false
            private set

        /**
         * The same entry as the accessibility shortcut: the sheet over the current app with what is on screen,
         * listening. Without the accessibility service, the bare assistant sheet; without the assistant role, Ubiq's
         * own assistant screen.
         */
        fun openAssistant(ctx: Context, locked: Boolean) {
            WakeWord.markHandsFree()
            val control = com.agentos.control.ControlService.instance
            if (control != null && !locked) { control.openSheetWithScreen(); return }
            if (com.agentos.assist.AgentVoiceService.open(null)) return
            if (control != null || com.agentos.ui.HomeActivity.isInForeground) { com.agentos.ui.HomeActivity.openAssistant(control ?: ctx); return }
            // Neither the assistant role nor the accessibility service: Android blocks a background activity start, so
            // ask with a heads-up notification (full screen over the lock screen where Android allows it).
            tapToTalk(ctx)
        }

        private fun tapToTalk(ctx: Context) {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL_HEARD) == null)
                nm.createNotificationChannel(NotificationChannel(CHANNEL_HEARD, "Hey Ubiq heard", NotificationManager.IMPORTANCE_HIGH).apply { setSound(null, null); enableVibration(false) })
            val open = PendingIntent.getActivity(ctx, 2, Intent(ctx, com.agentos.ui.HomeActivity::class.java).setAction(Intent.ACTION_ASSIST)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
            nm.notify(ID_HEARD, Notification.Builder(ctx, CHANNEL_HEARD)
                .setSmallIcon(R.drawable.ic_launcher_monochrome)
                .setContentTitle("Ubiq is here").setContentText("Tap to talk")
                .setCategory(Notification.CATEGORY_CALL)
                .setContentIntent(open).setFullScreenIntent(open, true)
                .setAutoCancel(true).setTimeoutAfter(10_000)
                .build())
        }
    }
}
