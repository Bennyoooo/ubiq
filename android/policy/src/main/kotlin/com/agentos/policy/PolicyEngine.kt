package com.agentos.policy

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * The owner's standing policy, checked before anything the agent does. Order:
 *  1. hard floors ([Floors]) - no rule or origin overrides them;
 *  2. the owner's Stop switch;
 *  3. per-app rules full > ask > read > off. "ask" asks the owner through [Host.askOwner]; an
 *     approval holds once or for the task, for [APPROVAL_TTL_MS].
 * The agent may make a rule stricter, never looser; loosening happens only in the owner's own UI.
 *
 * Plain Kotlin: the host (the app on a stock phone, Ubiq Core on the ROM) stores the state,
 * shows the notices and the approval dialog, and labels apps.
 */
class PolicyEngine(
    private val floors: Floors,
    private val host: Host,
    /** Actions in this package skip the per-app rules (the agent app's own screens); floors still apply. */
    private val selfPackage: String? = null,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    interface Host {
        fun loadStopped(): Boolean
        fun saveStopped(stopped: Boolean)
        fun loadRules(): Map<String, String>
        fun saveRules(rules: Map<String, String>)
        /** The Stop switch changed. */
        fun stopChanged(stopped: Boolean) {}
        /** Rules changed (for UIs that watch them). */
        fun rulesChanged(rules: Map<String, String>) {}
        /** Show the owner [p] (dialog and/or notification); answer with [PolicyEngine.answer]. */
        fun askOwner(p: Pending)
        /** The question [id] is settled; take its notification down. */
        fun approvalClosed(id: String) {}
        fun label(pkg: String): String = pkg
    }

    @Volatile var stopped: Boolean = host.loadStopped()
        private set
    @Volatile var rules: Map<String, String> = host.loadRules().filterValues { it in RULES }
        private set

    // ---------- Stop ----------

    fun stop() {
        stopped = true
        host.saveStopped(true)
        approvals.clear()
        host.stopChanged(true)
    }

    /** Only the owner resumes, from their own UI. */
    fun resume() {
        stopped = false
        host.saveStopped(false)
        host.stopChanged(false)
    }

    // ---------- rules ----------

    fun rule(pkg: String): String = rules[pkg] ?: DEFAULT_RULE

    /** Sets [pkg]'s rule. [byOwner] is true only for the owner's UI; anyone else may only tighten. Null on success, else why not. */
    fun setRule(pkg: String, rule: String, byOwner: Boolean): String? {
        if (rule !in RULES) return "rule must be one of $RULES"
        if (pkg in floors.protectedPackages) return "the rule for $pkg is fixed"
        if (!byOwner && strictness(rule) < strictness(rule(pkg))) return "the agent may only make rules stricter (${rule(pkg)} -> $rule is looser); the owner can change it in Ubiq Settings > Agent rules"
        val m = rules.toMutableMap()
        if (rule == DEFAULT_RULE) m.remove(pkg) else m[pkg] = rule
        rules = m
        host.saveRules(m)
        host.rulesChanged(m)
        if (strictness(rule) > strictness(ASK)) approvals.keys.removeAll { it.pkg == pkg }
        return null
    }

    // ---------- check ----------

    fun check(c: Check): Decision {
        floors.violation(c)?.let { return Decision(Decision.REFUSED, "hard floor: $it") }
        // Whatever origin the caller names, the stop holds for all of them.
        if (stopped) return Decision(Decision.REFUSED, "Ubiq is stopped by the owner. Stop working and tell the user; they can resume from the notification.")
        if (selfPackage != null && c.pkg == selfPackage) return Decision(Decision.DONE, "Ubiq itself")
        return when (rule(c.pkg)) {
            OFF -> Decision(Decision.REFUSED, "the owner has turned the agent off for ${host.label(c.pkg)}")
            READ -> if (c.effect == Effect.READ) Decision(Decision.DONE, "read allowed")
                else Decision(Decision.REFUSED, "the owner allows the agent only to look at ${host.label(c.pkg)}, not act in it")
            ASK -> if (c.effect == Effect.READ) Decision(Decision.DONE, "read allowed") else askOwner(c)
            else -> Decision(Decision.DONE, "allowed")
        }
    }

    // ---------- approvals ----------

    private data class Grant(val pkg: String, val task: String?) // task null = approved once
    private val approvals = ConcurrentHashMap<Grant, Long>()      // -> expiry

    class Pending(val id: String, val check: Check, val at: Long) { @Volatile var answer: String? = null }
    val pending = ConcurrentHashMap<String, Pending>()

    private fun askOwner(c: Check): Decision {
        val now = clock()
        approvals.entries.removeIf { it.value < now }
        approvals[Grant(c.pkg, c.task)]?.let { return Decision(Decision.DONE, "approved by the owner for this task") }
        approvals.remove(Grant(c.pkg, null))?.let { return Decision(Decision.DONE, "approved by the owner once") }
        // Re-use the open request for this app and task, so polling does not stack dialogs.
        val open = pending.values.firstOrNull { it.check.pkg == c.pkg && it.check.task == c.task && it.answer == null && now - it.at < APPROVAL_TTL_MS }
        val p = open ?: Pending(UUID.randomUUID().toString().take(12), c, now).also { pending[it.id] = it; host.askOwner(it) }
        return Decision(Decision.APPROVAL_REQUIRED, "waiting for the owner to approve ${c.effect} in ${host.label(c.pkg)}", p.id)
    }

    /** "pending" | "approved" | "denied" | "expired" | "unknown". */
    fun approvalStatus(id: String): String {
        val p = pending[id] ?: return "unknown"
        p.answer?.let {
            // The caller goes ahead on "approved": that uses up an approve-once grant.
            pending.remove(id); approvals.remove(Grant(p.check.pkg, null)); host.approvalClosed(id)
            return it
        }
        if (clock() - p.at > APPROVAL_TTL_MS) { pending.remove(id); host.approvalClosed(id); return "expired" }
        return "pending"
    }

    /** The owner's answer to [id]: "once", "task" or "deny". */
    fun answer(id: String, answer: String) {
        val p = pending[id] ?: return
        val exp = clock() + APPROVAL_TTL_MS
        when (answer) {
            "once" -> approvals[Grant(p.check.pkg, null)] = exp
            "task" -> approvals[Grant(p.check.pkg, p.check.task)] = exp
        }
        p.answer = if (answer == "deny") "denied" else "approved"
        host.approvalClosed(id)
    }

    companion object {
        const val APPROVAL_TTL_MS = 10 * 60 * 1000L
        const val FULL = "full"
        const val ASK = "ask"
        const val READ = "read"
        const val OFF = "off"
        const val DEFAULT_RULE = FULL
        /** App rules, loosest first. */
        val RULES = listOf(FULL, ASK, READ, OFF)
        const val ORDER = "hard floors > owner stop > per-app rules (full > ask > read > off)"

        fun strictness(rule: String) = RULES.indexOf(rule)
    }
}
