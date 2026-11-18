package com.agentos.phone

import android.Manifest
import android.app.role.RoleManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.CallLog
import android.provider.ContactsContract
import android.telecom.Call
import android.telecom.CallScreeningService
import android.telecom.Connection
import android.telecom.TelecomManager
import android.util.Log
import com.agentos.core.CoreClient
import com.agentos.data.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/**
 * Call screening, a mode the owner turns on in Settings. Callers in the contacts (or numbers the owner
 * called lately) ring as usual. Anyone else, Ubiq answers first: it asks who is calling and what it's about,
 * follows the owner's own rules (a prompt in Settings), and passes it on so the owner decides whether to take
 * the call ([CallScreener]). Talking on a call needs Ubiq Core on the ROM (call audio interception); on a
 * phone without it, unknown callers ring silently with a notice instead.
 */
object CallScreening {
    private const val TAG = "AgentOS/Screening"

    enum class Decision { ALLOW, SCREEN, SILENCE }

    @Volatile private var talkCache: Pair<Long, Boolean>? = null

    fun enabled(ctx: Context) = Prefs.screenCalls(ctx)

    fun roleHeld(ctx: Context): Boolean =
        ctx.getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_CALL_SCREENING)

    /** Ubiq can hold a conversation on a call: Core can intercept call audio, and there is an OpenAI key. */
    suspend fun canTalk(ctx: Context): Boolean {
        if (!Prefs.isConfigured(ctx)) return false
        if (CallScreener.fakeAudio) return true
        talkCache?.let { (at, v) -> if (System.currentTimeMillis() - at < 10 * 60_000L) return v }
        val v = CoreClient.call("call.status", origin = "agent", task = "screen")?.optJSONObject("result")?.optBoolean("interceptable") == true
        talkCache = System.currentTimeMillis() to v
        return v
    }

    /** What Core says about calls on this phone (for Settings), or null without Core. */
    suspend fun status(): JSONObject? = CoreClient.call("call.status", origin = "agent", task = "screen")?.optJSONObject("result")
        ?.also { talkCache = System.currentTimeMillis() to it.optBoolean("interceptable") }

    /** The contact's name for [number], or null. */
    fun contactName(ctx: Context, number: String): String? {
        if (number.isBlank() || ctx.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return null
        return try {
            ctx.contentResolver.query(Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number)),
                arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null }
        } catch (t: Throwable) { null }
    }

    /** Someone the owner knows: a contact, or a number they called in the last 60 days. */
    fun known(ctx: Context, number: String): Boolean {
        if (number.isBlank()) return false
        if (contactName(ctx, number) != null) return true
        if (ctx.checkSelfPermission(Manifest.permission.READ_CALL_LOG) != PackageManager.PERMISSION_GRANTED) return false
        return try {
            val since = System.currentTimeMillis() - 60L * 86_400_000L
            ctx.contentResolver.query(Uri.withAppendedPath(CallLog.Calls.CONTENT_FILTER_URI, Uri.encode(number)), arrayOf(CallLog.Calls._ID),
                "${CallLog.Calls.TYPE} = ? AND ${CallLog.Calls.DATE} > ?", arrayOf(CallLog.Calls.OUTGOING_TYPE.toString(), since.toString()), null)
                ?.use { it.count > 0 } == true
        } catch (t: Throwable) { false }
    }

    /**
     * What to do with an incoming call. Pure, for tests. [busy]: the owner is already on a call, or Ubiq is
     * screening another one (answering would put that call on hold), so it rings as call waiting.
     */
    internal fun decide(enabled: Boolean, known: Boolean, busy: Boolean, canTalk: Boolean): Decision = when {
        !enabled || known || busy -> Decision.ALLOW
        canTalk -> Decision.SCREEN
        else -> Decision.SILENCE
    }

    internal fun log(msg: String) = Log.i(TAG, msg)
}

/**
 * The call screening role (android.app.role.CALL_SCREENING): Android asks this service about every
 * incoming call before it rings, and gives it about five seconds to answer.
 */
class UbiqScreeningService : CallScreeningService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onScreenCall(details: Call.Details) {
        if (details.callDirection != Call.Details.DIRECTION_INCOMING) { respondToCall(details, CallResponse.Builder().build()); return }
        val ctx = applicationContext
        val number = details.handle?.schemeSpecificPart.orEmpty()
        val spoofed = details.callerNumberVerificationStatus == Connection.VERIFICATION_STATUS_FAILED
        scope.launch {
            val enabled = CallScreening.enabled(ctx)
            val known = enabled && CallScreening.known(ctx, number)
            @Suppress("MissingPermission")
            val busy = CallScreener.live.value != null || runCatching { ctx.getSystemService(TelecomManager::class.java).isInCall }.getOrDefault(false)
            val canTalk = enabled && !known && (withTimeoutOrNull(2500) { CallScreening.canTalk(ctx) } ?: false)
            val d = CallScreening.decide(enabled, known, busy, canTalk)
            CallScreening.log("incoming ${if (number.isBlank()) "(withheld)" else "…" + number.takeLast(4)}: $d${if (spoofed) " (caller ID failed verification)" else ""}")
            val r = CallResponse.Builder()
            // Screened calls don't ring: Ubiq picks up, and the owner hears about it only if it's worth it.
            if (d != CallScreening.Decision.ALLOW) r.setSilenceCall(true)
            respondToCall(details, r.build())
            when (d) {
                CallScreening.Decision.SCREEN -> CallScreener.screen(ctx, number, spoofed)
                CallScreening.Decision.SILENCE -> ScreenNotices.silenced(ctx, number)
                CallScreening.Decision.ALLOW -> {}
            }
        }
    }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
