package com.agentos.rules

import android.content.Context
import com.agentos.agent.InstalledApps
import com.agentos.agent.TaskTarget
import com.agentos.agent.ToolOutcome
import com.agentos.control.ScreenSnapshot
import com.agentos.policy.Check
import com.agentos.policy.Effect
import org.json.JSONObject

/**
 * The owner's rules, checked before each phone tool acts (hard floors, the Stop switch, per-app
 * full / ask / read / off). Two implementations share the rules in the `policy` module:
 * [InAppPolicy] enforces them in this process on a stock phone, and Ubiq Core enforces them
 * from its own process on the ROM ([com.agentos.core.CorePolicyGate]).
 */
interface PolicyGate {
    /** Shown in Settings: where the rules live. */
    val name: String

    /** Null = go ahead; otherwise the refusal for the model. May wait for the owner's answer when the app is set to "ask". */
    suspend fun check(c: Check, appLabel: String): ToolOutcome?

    /** Opens the owner's rules screen. */
    fun openRules(ctx: Context)

    companion object {
        /** Core when it is installed and reachable, the in-app rules otherwise. */
        fun current(): PolicyGate = if (com.agentos.core.CoreClient.available) com.agentos.core.CorePolicyGate else InAppPolicy

        /**
         * Checks phone tool [name] about to act in the app on screen (or, for open_app, the app
         * being opened). Null = go ahead.
         */
        suspend fun gate(ctx: Context, name: String, input: JSONObject, apps: InstalledApps, target: TaskTarget, look: () -> ScreenSnapshot): ToolOutcome? {
            if (!Effect.gated(name)) return null
            val snap = if (name == "open_app") null else look()
            val pkg = if (name == "open_app") apps.resolve(input.optString("name"))?.packageName else snap?.packageName
            // A face, fingerprint or screen-lock check (Ubiq's or any app's, e.g. a bank signing in) is the owner's alone:
            // the agent never taps Confirm or types a PIN there. It may read it, wait, or press back to cancel.
            if (snap != null && isBiometricPrompt(snap) && Effect.of(name, "") != Effect.READ && !(name == "press" && input.optString("key").equals("back", true)))
                return ToolOutcome.error("Your owner's face, fingerprint or screen-lock check is on screen. Only they can answer it: " +
                    "don't tap it. Wait for them (wait / ask_user), or press back to cancel.")
            if (pkg.isNullOrBlank()) return null
            // The agent's own screens skip the per-app rules, except the owner's rules and approval screens.
            if (pkg == ctx.packageName && !OwnerScreens.showing) return null
            val node = when (name) {
                "tap", "long_press", "type_text" -> snap?.find(input.optInt("id", -1))
                "tap_xy" -> snap?.nodes?.filter { it.bounds.contains(input.optInt("x"), input.optInt("y")) }?.minByOrNull { it.bounds.width() * it.bounds.height() }
                else -> null
            }
            val label = node?.let { it.text.ifEmpty { it.desc } } ?: if (name == "press") input.optString("key") else ""
            val check = Check(
                pkg = pkg, effect = Effect.of(name, label, submits = input.optBoolean("submit")),
                origin = if (target.guarded) "job" else "agent", task = target.session?.id ?: "",
                label = label, text = input.optString("text"), screen = snap?.render()?.take(3000) ?: "",
            )
            val appLabel = apps.all().firstOrNull { it.packageName == pkg }?.label ?: pkg
            return current().check(check, appLabel)
        }
    }
}

/** SystemUI's biometric prompt (the system face / fingerprint / screen-lock sheet any app can open). */
internal fun isBiometricPrompt(snap: ScreenSnapshot): Boolean {
    if (snap.packageName != "com.android.systemui") return false
    if (snap.nodes.any { it.resId.contains("biometric", true) || it.resId.contains("auth_credential", true) }) return true
    val words = snap.nodes.joinToString(" ") { it.text + " " + it.desc }
    return BIOMETRIC_WORDS.containsMatchIn(words)
}

private val BIOMETRIC_WORDS = Regex("\\b(use (your )?(face|fingerprint|screen lock|pin|pattern|password)|face (recogni[sz]ed|unlock)|touch the fingerprint sensor|press confirm|tap confirm)\\b", RegexOption.IGNORE_CASE)

/** Tracks whether one of the owner's own screens ([RulesActivity], [OwnerApprovalActivity]) is open, so the agent cannot act on them. */
object OwnerScreens {
    private val open = java.util.concurrent.atomic.AtomicInteger(0)
    val showing: Boolean get() = open.get() > 0
    internal fun shown() { open.incrementAndGet() }
    internal fun hidden() { open.updateAndGet { maxOf(0, it - 1) } }
}
