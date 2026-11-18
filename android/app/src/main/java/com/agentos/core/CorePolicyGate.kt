package com.agentos.core

import android.content.Context
import android.content.Intent
import com.agentos.agent.ToolOutcome
import com.agentos.core.api.CoreContract
import com.agentos.policy.Check
import com.agentos.rules.PolicyGate
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/**
 * The owner's rules on the ROM: Ubiq Core checks them from its own process (policy.check), so
 * the agent app cannot change them. Waits for the owner's answer when the app is set to "ask".
 */
object CorePolicyGate : PolicyGate {
    override val name = "Ubiq Core"

    override suspend fun check(c: Check, appLabel: String): ToolOutcome? {
        val args = JSONObject().put("pkg", c.pkg).put("effect", c.effect).put("label", c.label).put("text", c.text).put("screen", c.screen)
        val r = CoreClient.call("policy.check", args, c.origin, c.task) ?: return null
        if (r.optString("state") == CoreContract.APPROVAL_REQUIRED) {
            val id = r.optJSONObject("result")?.optString("approvalId").orEmpty()
            val status = withTimeoutOrNull(3 * 60_000L) {
                while (true) {
                    delay(700)
                    val s = CoreClient.call("policy.approval_status", JSONObject().put("approvalId", id), c.origin, c.task)
                        ?.optJSONObject("result")?.optString("status") ?: "unknown"
                    if (s != "pending") return@withTimeoutOrNull s
                }
                @Suppress("UNREACHABLE_CODE") "pending"
            } ?: "timeout"
            // The approval dialog took focus on the main screen; accessibility re-reads every display's
            // windows when it closes, and a snapshot taken in that moment comes back empty.
            if (status == "approved") { delay(1200); return null }
            return ToolOutcome.error("The owner did not approve this action in $appLabel ($status). Do not retry it; tell the user.")
        }
        if (r.optBoolean("ok")) return null
        val why = r.optJSONObject("result")?.optString("reason")?.ifBlank { null } ?: r.optString("result")
        return ToolOutcome.error("Not allowed by the owner's rules (Ubiq Core): $why")
    }

    override fun openRules(ctx: Context) {
        ctx.startActivity(Intent(CoreContract.ACTION_RULES).setPackage(CoreContract.PACKAGE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
