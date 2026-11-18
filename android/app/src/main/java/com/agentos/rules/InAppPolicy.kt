package com.agentos.rules

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.telecom.TelecomManager
import android.telephony.TelephonyManager
import android.util.Log
import com.agentos.R
import com.agentos.agent.ToolOutcome
import com.agentos.policy.Check
import com.agentos.policy.Decision
import com.agentos.policy.Floors
import com.agentos.policy.PolicyEngine
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/**
 * The owner's rules on a stock phone, enforced in this process with the same [PolicyEngine] Ubiq
 * Core runs on the ROM. Stored in device-protected storage; the owner edits them in [RulesActivity]
 * and answers "ask" apps in [OwnerApprovalActivity]. The agent can only make a rule stricter.
 */
object InAppPolicy : PolicyGate {
    private const val TAG = "AgentOS/Rules"
    private const val CHANNEL_STOP = "owner"
    private const val CHANNEL_APPROVALS = "approvals"
    private const val ID_STOP = 7101
    private const val ID_APPROVAL = 7102

    override val name = "Ubiq (this phone)"

    private lateinit var app: Context
    private val prefs by lazy { app.createDeviceProtectedStorageContext().getSharedPreferences("policy", Context.MODE_PRIVATE) }
    @Volatile private var engineOrNull: PolicyEngine? = null
    private val engine: PolicyEngine get() = engineOrNull ?: error("InAppPolicy.init was not called")

    val stopped = MutableStateFlow(false)
    val rules = MutableStateFlow<Map<String, String>>(emptyMap())

    private val host = object : PolicyEngine.Host {
        override fun loadStopped() = prefs.getBoolean("stopped", false)
        override fun saveStopped(stopped: Boolean) { prefs.edit().putBoolean("stopped", stopped).apply() }
        override fun loadRules(): Map<String, String> = try {
            val o = JSONObject(prefs.getString("rules", "{}")!!)
            o.keys().asSequence().associateWith { o.getString(it) }
        } catch (_: Throwable) { emptyMap() }
        override fun saveRules(rules: Map<String, String>) { prefs.edit().putString("rules", JSONObject(rules as Map<*, *>).toString()).apply() }
        override fun stopChanged(stopped: Boolean) { this@InAppPolicy.stopped.value = stopped; if (stopped) showStopped() else hideStopped() }
        override fun rulesChanged(rules: Map<String, String>) { this@InAppPolicy.rules.value = rules }
        override fun askOwner(p: PolicyEngine.Pending) = showApproval(p)
        override fun approvalClosed(id: String) { nm().cancel(id, ID_APPROVAL) }
        override fun label(pkg: String) = this@InAppPolicy.label(pkg)
    }

    fun init(ctx: Context) {
        app = ctx.applicationContext
        val env = object : Floors.Env {
            override fun isDialer(pkg: String) =
                try { app.getSystemService(TelecomManager::class.java)?.defaultDialerPackage == pkg } catch (_: Throwable) { false }
            override fun isEmergencyNumber(number: String) =
                try { app.getSystemService(TelephonyManager::class.java)?.isEmergencyNumber(number) == true } catch (_: Throwable) { false }
        }
        val self = app.packageName
        val floors = Floors(env, setOf(self, com.agentos.core.api.CoreContract.PACKAGE)) { it.pkg == self && OwnerScreens.showing }
        engineOrNull = PolicyEngine(floors, host, selfPackage = self, clock = SystemClock::elapsedRealtime).also {
            stopped.value = it.stopped
            rules.value = it.rules
            if (it.stopped) showStopped()
        }
    }

    // ---------- PolicyGate ----------

    override suspend fun check(c: Check, appLabel: String): ToolOutcome? {
        val d = engine.check(c)
        if (d.state == Decision.APPROVAL_REQUIRED) {
            val id = d.approvalId.orEmpty()
            val status = withTimeoutOrNull(3 * 60_000L) {
                var s = engine.approvalStatus(id)
                while (s == "pending") { delay(500); s = engine.approvalStatus(id) }
                s
            } ?: "timeout"
            // The approval dialog took focus on the main screen; a snapshot taken as it closes comes back empty.
            if (status == "approved") { delay(1200); return null }
            return ToolOutcome.error("The owner did not approve this action in $appLabel ($status). Do not retry it; tell the user.")
        }
        if (d.allowed) return null
        return ToolOutcome.error("Not allowed by the owner's rules: ${d.reason}")
    }

    override fun openRules(ctx: Context) {
        ctx.startActivity(Intent(ctx, RulesActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    // ---------- for the owner's screens and the agent's tools ----------

    fun rule(pkg: String) = engine.rule(pkg)
    fun setRule(pkg: String, rule: String, byOwner: Boolean): String? = engine.setRule(pkg, rule, byOwner)
    fun stop(reason: String) { engine.stop(); Log.i(TAG, "stopped: $reason") }
    /** Only from the owner's own screens or the notification. */
    fun resume() { engine.resume(); Log.i(TAG, "resumed by owner") }
    fun pending(id: String): PolicyEngine.Pending? = engine.pending[id]
    fun answer(id: String, answer: String) = engine.answer(id, answer)
    val protectedPackages: Set<String> get() = setOf(app.packageName, com.agentos.core.api.CoreContract.PACKAGE)

    fun label(pkg: String): String = try {
        app.packageManager.getApplicationLabel(app.packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (_: Throwable) { pkg }

    // ---------- notices ----------

    private fun nm() = app.getSystemService(NotificationManager::class.java)

    private fun showStopped() {
        nm().createNotificationChannel(NotificationChannel(CHANNEL_STOP, "Agent control", NotificationManager.IMPORTANCE_DEFAULT).apply { setSound(null, null) })
        val resume = PendingIntent.getBroadcast(app, 0, Intent(app, ResumeReceiver::class.java), PendingIntent.FLAG_IMMUTABLE)
        val rules = PendingIntent.getActivity(app, 1, Intent(app, RulesActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        nm().notify(ID_STOP, Notification.Builder(app, CHANNEL_STOP)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Ubiq is stopped")
            .setContentText("The agent cannot use your apps until you resume it.")
            .setOngoing(true).setContentIntent(rules)
            .addAction(Notification.Action.Builder(null, "Resume", resume).build())
            .build())
    }

    private fun hideStopped() = nm().cancel(ID_STOP)

    private fun showApproval(p: PolicyEngine.Pending) {
        // The dialog does not show over the lock screen: a notification makes sure the owner sees it.
        nm().createNotificationChannel(NotificationChannel(CHANNEL_APPROVALS, "Approvals", NotificationManager.IMPORTANCE_HIGH))
        val intent = Intent(app, OwnerApprovalActivity::class.java).putExtra("id", p.id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val open = PendingIntent.getActivity(app, p.id.hashCode(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        nm().notify(p.id, ID_APPROVAL, Notification.Builder(app, CHANNEL_APPROVALS).setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Ubiq asks to act in ${label(p.check.pkg)}").setContentText(p.check.label.ifBlank { p.check.effect }.take(120))
            .setContentIntent(open).setAutoCancel(true).setCategory(Notification.CATEGORY_REMINDER).build())
        try { (com.agentos.control.ControlService.instance ?: app).startActivity(intent) } catch (t: Throwable) { Log.w(TAG, "approval activity", t) }
    }

    /** Resume on the stop notification. Not exported: only the notification's PendingIntent reaches it. */
    class ResumeReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = resume()
    }
}
