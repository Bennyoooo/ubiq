package com.agentos.pay

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.hardware.biometrics.BiometricManager.Authenticators
import android.hardware.biometrics.BiometricPrompt
import android.os.Bundle
import android.os.CancellationSignal
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * Every payment the agent makes needs the user first: a tap on Approve after reading the request, then their
 * fingerprint, face plus Confirm, or screen lock ([ApprovalActivity]). The agent calls approve_payment with the
 * amount and merchant; only an approval lets the next "Pay" / "Place order" tap through.
 */
object PaymentApproval {
    private const val TAG = "AgentOS/Pay"
    private val pending = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()

    /** Words on the button that actually spends money (or opens the wallet's pay sheet). */
    val PAY_BUTTON = Regex("^\\s*(pay|pay now|pay \\S*\\d|buy now|purchase|place (your )?order|complete (purchase|order|payment)|confirm (and|&) pay|submit order|order now|book now|(request|confirm) (uber\\w*|lyft|ride|pickup|comfort|black|xl|green|pool|share))\\b|\\b(google pay|g ?pay|buy with)\\b", RegexOption.IGNORE_CASE)

    /** Shows the prompt and waits (up to 3 minutes) for the user. */
    suspend fun request(ctx: Context, amount: String, merchant: String, what: String): Boolean {
        val id = System.nanoTime().toString()
        val d = CompletableDeferred<Boolean>()
        pending[id] = d
        try {
            val i = Intent(ctx, ApprovalActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
                .putExtra("id", id).putExtra("amount", amount).putExtra("merchant", merchant).putExtra("what", what)
            // On the user's screen: started from a take, the focused display is the hidden desk.
            val o = android.app.ActivityOptions.makeBasic().setLaunchDisplayId(android.view.Display.DEFAULT_DISPLAY)
            (com.agentos.control.ControlService.instance ?: ctx).startActivity(i, o.toBundle())
        } catch (t: Throwable) {
            Log.w(TAG, "could not show the approval", t); pending.remove(id); return false
        }
        return withTimeoutOrNull(180_000) { d.await() } ?: false.also { pending.remove(id) }
    }

    internal fun answer(id: String?, ok: Boolean) { id?.let { pending.remove(it)?.complete(ok) } }

    /**
     * Two steps, so a face in front of the phone can't approve on its own (Pixel face unlock is a strong biometric and
     * would pass the system prompt the moment it opened): first the whole request on screen with Approve and Deny,
     * armed after a moment so a stray touch doesn't count; only a tap on Approve opens the system prompt, which also
     * asks for a Confirm tap after a face match. Shown as an owner screen, so the agent can't tap it (OwnerScreens).
     */
    class ApprovalActivity : Activity() {
        private var answered = false
        private val cancel = CancellationSignal()
        private var dialog: android.app.AlertDialog? = null

        private fun done(ok: Boolean) {
            if (!answered) { answered = true; answer(intent.getStringExtra("id"), ok) }
            dialog?.dismiss()
            finish()
        }

        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
            val amount = intent.getStringExtra("amount").orEmpty()
            val merchant = intent.getStringExtra("merchant").orEmpty()
            val what = intent.getStringExtra("what").orEmpty().trim()
            val auth = Authenticators.BIOMETRIC_STRONG or Authenticators.DEVICE_CREDENTIAL
            val canVerify = getSystemService(android.hardware.biometrics.BiometricManager::class.java).canAuthenticate(auth) ==
                android.hardware.biometrics.BiometricManager.BIOMETRIC_SUCCESS
            val message = buildString {
                append("Ubiq wants to pay $merchant.")
                if (what.isNotBlank()) append("\n\n").append(what)
                append("\n\n").append(if (canVerify) "After Approve, confirm with your fingerprint, or your face and a tap on Confirm."
                    else "Set up a screen lock so payments need your fingerprint or face.")
            }
            dialog = android.app.AlertDialog.Builder(this)
                .setTitle("Approve payment: $amount")
                .setMessage(message)
                .setPositiveButton("Approve", null)
                .setNegativeButton("Deny") { _, _ -> done(false) }
                .setOnCancelListener { done(false) }
                .create().also { d ->
                    d.setCanceledOnTouchOutside(false)
                    d.setOnShowListener { _ ->
                        d.window?.decorView?.filterTouchesWhenObscured = true
                        val approve = d.getButton(android.content.DialogInterface.BUTTON_POSITIVE)
                        // Armed after a moment: a touch already under way when this popped up doesn't approve.
                        approve.isEnabled = false
                        approve.postDelayed({ approve.isEnabled = true }, 1200)
                        approve.setOnClickListener {
                            if (!canVerify) { done(true); return@setOnClickListener }
                            d.hide()
                            verify(amount, merchant, what, auth)
                        }
                    }
                    d.show()
                }
        }

        private fun verify(amount: String, merchant: String, what: String, auth: Int) {
            val prompt = BiometricPrompt.Builder(this)
                .setTitle("Approve payment: $amount")
                .setSubtitle("Ubiq wants to pay $merchant")
                .apply { if (what.isNotBlank()) setDescription(what) }
                .setAllowedAuthenticators(auth)
                // A face match alone doesn't approve: the user taps Confirm too.
                .setConfirmationRequired(true)
                .build()
            prompt.authenticate(cancel, mainExecutor, object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) = done(true)
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) = done(false)
            })
        }

        override fun onResume() { super.onResume(); com.agentos.rules.OwnerScreens.shown() }
        override fun onPause() { com.agentos.rules.OwnerScreens.hidden(); super.onPause() }

        override fun onDestroy() {
            if (!answered) { answered = true; answer(intent.getStringExtra("id"), false) }
            cancel.cancel()
            dialog?.dismiss()
            super.onDestroy()
        }
    }
}
