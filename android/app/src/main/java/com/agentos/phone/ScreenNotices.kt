package com.agentos.phone

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Person
import android.app.RemoteInput
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import com.agentos.R

/**
 * Call screening's notifications: the call Ubiq is on (a screening-call notification: pick up / hang up),
 * Ubiq asking the owner to decide (rings, full screen over the lock screen, with a reply box), and what came
 * of a call the owner didn't take.
 */
object ScreenNotices {
    const val LIVE = 4700
    private const val ASK = 4701
    private const val RESULT = 4702
    private const val SILENCED = 4703
    private const val CH_LIVE = "screen_live"
    private const val CH_ASK = "screen_ask"
    private const val CH_RESULT = "screen_result"
    const val KEY_REPLY = "owner_reply"

    private fun nm(ctx: Context) = ctx.getSystemService(NotificationManager::class.java)

    private fun channels(ctx: Context) {
        val nm = nm(ctx)
        if (nm.getNotificationChannel(CH_LIVE) == null)
            nm.createNotificationChannel(NotificationChannel(CH_LIVE, "Ubiq answering a call", NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) })
        if (nm.getNotificationChannel(CH_ASK) == null)
            nm.createNotificationChannel(NotificationChannel(CH_ASK, "Ubiq asks if you'll take a call", NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_COMMUNICATION_INSTANT).build())
                enableVibration(true)
            })
        if (nm.getNotificationChannel(CH_RESULT) == null)
            nm.createNotificationChannel(NotificationChannel(CH_RESULT, "Calls Ubiq screened", NotificationManager.IMPORTANCE_DEFAULT))
    }

    private fun screen(ctx: Context, code: Int) = PendingIntent.getActivity(ctx, code,
        Intent(ctx, CallScreenActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun action(ctx: Context, what: String, mutable: Boolean = false) = PendingIntent.getBroadcast(ctx, what.hashCode(),
        Intent(ctx, Receiver::class.java).putExtra("what", what),
        (if (mutable) PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_IMMUTABLE) or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun caller(l: CallScreener.Live) = Person.Builder().setName(l.who).setImportant(true).build()

    /** The ongoing notification while Ubiq is on the call. */
    fun buildLive(ctx: Context, forService: Boolean = false): Notification {
        channels(ctx)
        val l = CallScreener.live.value
        val text = when {
            l == null -> "Ubiq is answering a call"
            l.reason.isNotBlank() -> l.reason
            else -> l.lines.lastOrNull { it.who != CallScreener.Who.NOTE }?.let { (if (it.who == CallScreener.Who.UBIQ) "Ubiq: " else "Caller: ") + it.text } ?: l.phase
        }
        val b = Notification.Builder(ctx, CH_LIVE)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(if (l == null) "Ubiq is screening a call" else "Ubiq is screening ${l.who}")
            .setContentText(text)
            .setCategory(Notification.CATEGORY_CALL).setOngoing(true).setOnlyAlertOnce(true).setGroup("ubiq_screen_live")
            .setContentIntent(screen(ctx, 70)).setUsesChronometer(true).setWhen(l?.started ?: System.currentTimeMillis())
        // Android posts a call-style notification only for a foreground service; until that is up, a plain one.
        if (l != null && (ScreenCallService.running || forService)) b.setStyle(Notification.CallStyle.forScreeningCall(caller(l), action(ctx, "hangup"), action(ctx, "pickup")))
        else if (l != null) b.addAction(Notification.Action.Builder(null, "Pick up", action(ctx, "pickup")).build())
            .addAction(Notification.Action.Builder(null, "Hang up", action(ctx, "hangup")).build())
        return b.build()
    }

    fun live(ctx: Context) {
        runCatching { nm(ctx).notify(LIVE, buildLive(ctx)) }.onFailure { Log.w("AgentOS/Screener", "live notice: $it") }
    }

    /** Ubiq knows who is calling and why: the owner decides. Rings, and shows the call screen over the lock screen. */
    fun ask(ctx: Context) {
        val l = CallScreener.live.value ?: return
        channels(ctx)
        val reply = Notification.Action.Builder(null, "Tell them…", action(ctx, "reply", mutable = true))
            .addRemoteInput(RemoteInput.Builder(KEY_REPLY).setLabel("e.g. text me instead").build()).build()
        val n = Notification.Builder(ctx, CH_ASK)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle((if (l.urgent) "Urgent · " else "") + "${l.who} is on the line")
            .setContentText(l.reason.ifBlank { "Take the call?" })
            .setStyle(Notification.BigTextStyle().bigText(l.reason.ifBlank { "Take the call?" } + if (l.number.isNotBlank() && l.caller.isNotBlank()) "\n${l.number}" else ""))
            .setCategory(Notification.CATEGORY_CALL).setGroup("ubiq_screen_ask")
            .setContentIntent(screen(ctx, 71)).setFullScreenIntent(screen(ctx, 72), true)
            .setTimeoutAfter(Prefs.waitMillis(ctx))
            .addAction(Notification.Action.Builder(null, "Pick up", action(ctx, "pickup")).build())
            .addAction(Notification.Action.Builder(null, "Decline", action(ctx, "decline")).build())
            .addAction(reply)
            .build()
        nm(ctx).notify(ASK, n)
        live(ctx)
    }

    fun clearAsk(ctx: Context) = nm(ctx).cancel(ASK)

    fun clear(ctx: Context) { nm(ctx).cancel(ASK); nm(ctx).cancel(LIVE) }

    /** A call the owner didn't take: who, why, and the message. */
    fun result(ctx: Context, l: CallScreener.Live, outcome: String, message: String, callback: String) {
        channels(ctx)
        val body = listOf(l.reason, message.takeIf { it.isNotBlank() }?.let { "Message: $it" }, callback.takeIf { it.isNotBlank() }?.let { "Call back: $it" },
            outcome.replaceFirstChar { it.uppercase() }).filter { !it.isNullOrBlank() }.joinToString("\n")
        val n = Notification.Builder(ctx, CH_RESULT)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle("Ubiq screened ${l.who}")
            .setContentText(message.ifBlank { l.reason.ifBlank { outcome } })
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setContentIntent(PendingIntent.getActivity(ctx, 73, Intent(ctx, com.agentos.ui.HomeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE))
            .setAutoCancel(true).build()
        nm(ctx).notify(RESULT + (l.started % 1000).toInt(), n)
    }

    /** Ubiq can't talk on calls on this phone: the unknown caller rings silently instead. */
    fun silenced(ctx: Context, number: String) {
        channels(ctx)
        val n = Notification.Builder(ctx, CH_RESULT)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle("Unknown caller, ringing silently")
            .setContentText(number.ifBlank { "Withheld number" } + " · not in your contacts")
            .setTimeoutAfter(60_000).setAutoCancel(true).build()
        nm(ctx).notify(SILENCED, n)
    }

    private object Prefs { fun waitMillis(ctx: Context) = com.agentos.data.Prefs.screenWaitSeconds(ctx).coerceIn(10, 120) * 1000L }

    class Receiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.getStringExtra("what")) {
                "pickup" -> CallScreener.pickUp()
                "decline" -> CallScreener.decline()
                "hangup" -> CallScreener.hangUp()
                "reply" -> RemoteInput.getResultsFromIntent(intent)?.getCharSequence(KEY_REPLY)?.toString()?.let { CallScreener.ownerSays(it) }
            }
            clearAsk(context)
        }
    }
}

/**
 * Keeps the app alive while Ubiq is on a call (the screen is usually off and the app in the background), with
 * the screening-call notification. A phone-call foreground service: Ubiq is managing a call for the owner.
 */
class ScreenCallService : Service() {
    private var wake: PowerManager.WakeLock? = null
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            startForeground(ScreenNotices.LIVE, ScreenNotices.buildLive(this, forService = true), ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL)
            running = true
        } catch (t: Throwable) { Log.w("AgentOS/Screener", "foreground: $t"); stopSelf(); return START_NOT_STICKY }
        if (wake == null) wake = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Ubiq:screen-call").apply { acquire(10 * 60_000L) }
        return START_NOT_STICKY
    }

    override fun onDestroy() { running = false; wake?.takeIf { it.isHeld }?.release(); wake = null; super.onDestroy() }

    companion object {
        @Volatile var running = false; private set
        fun start(ctx: Context) {
            runCatching { ctx.startForegroundService(Intent(ctx, ScreenCallService::class.java)) }.onFailure { Log.w("AgentOS/Screener", "service: $it") }
        }
        fun stop(ctx: Context) { ctx.stopService(Intent(ctx, ScreenCallService::class.java)) }
    }
}
