package com.agentos.agent

import com.agentos.control.ControlService
import com.agentos.control.ScreenSnapshot
import com.agentos.control.UiNode
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The turn's effect ledger. Every effectful tool call (a submit, a tap on Send / Post / Pay /
 * Delete / Confirm-like controls, a connector write) gets an op id and an entry before it runs,
 * and its state is decided by code from evidence, never from what the model says:
 * - done: the post-action screen shows it (the sent text outside the composer and the composer
 *   emptied; the confirming control gone) or the connector answered;
 * - failed: it visibly did not happen (the draft is still in the composer, an error notice, the
 *   tap did not register), so trying again is safe;
 * - unknown: no evidence either way. While any op is unknown, further writes are refused until
 *   the model looks at the screen and calls `reconcile_effect`.
 * An op that is done is not repeated in the same turn without a fresh answer from the user.
 * [CompletionCheck] compares the final summary against this ledger.
 */
class EffectLedger {
    enum class State { PENDING, DONE, FAILED, UNKNOWN }

    class Entry(val op: Int, val kind: String, val tool: String, val app: String, val where: String, val payload: String, val key: String) {
        @Volatile var state = State.PENDING
        @Volatile var evidence = ""
        fun line() = "op $op: $kind" + (if (payload.isNotBlank()) " \"${payload.take(60)}\"" else "") +
            (if (where.isNotBlank()) " ($where)" else "") + " - ${state.name.lowercase()}" + (if (evidence.isNotBlank()) ": $evidence" else "")
    }

    val entries = CopyOnWriteArrayList<Entry>()
    /** How many rows/items read-type tools gathered this turn (read_list rows, emails listed, ...), for count claims. */
    val gathered = CopyOnWriteArrayList<Int>()
    /** Any phone action (tap, type, scroll...) ran this turn; non-keyed claims ("I set a timer") accept that. */
    @Volatile var acted = false

    fun unknown() = entries.filter { it.state == State.UNKNOWN }
    fun op(id: Int) = entries.firstOrNull { it.op == id }
    fun done(kinds: Set<String>) = entries.filter { it.state == State.DONE && it.kind in kinds }

    internal fun open(kind: String, tool: String, app: String, where: String, payload: String): Entry {
        val key = sha("$kind|$app|${fold(where)}|${fold(payload)}")
        return Entry(entries.size + 1, kind, tool, app, where, payload, key).also { entries += it }
    }

    companion object {
        fun fold(s: String) = s.lowercase().replace(Regex("\\s+"), " ").trim()
        private fun sha(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).take(8).joinToString("") { "%02x".format(it) }
    }
}

/** What an effectful call is about to do, worked out from the call and the screen before it. */
internal class EffectIntent(val kind: String, val where: String, val payload: String, val node: UiNode?, val composer: String?)

object Effects {
    /** Control labels whose tap changes something outside the phone or can't be undone. */
    private val SEND = Regex("^(send|send now|reply|reply all|post|publish|share|tweet|comment|submit)\\b|\\bsend( message| sms| email)?$", RegexOption.IGNORE_CASE)
    private val PAY = Regex("\\b(pay|buy|purchase|place order|checkout|check out|transfer|book now|reserve)\\b", RegexOption.IGNORE_CASE)
    private val DELETE = Regex("^(delete|remove|trash|move to trash|discard|clear all|erase|uninstall|archive|unsubscribe)\\b", RegexOption.IGNORE_CASE)
    private val CONFIRM = Regex("^(confirm|ok, delete|yes, delete|accept|decline|book|reserve|done, send)\\b", RegexOption.IGNORE_CASE)
    private val SEARCHY = Regex("search|find|query|filter|url|address bar|omnibox|to:|recipient", RegexOption.IGNORE_CASE)
    private val FAILURE = Regex("\\b(not sent|failed|couldn'?t (send|be sent)|can'?t send|unable to|tap to retry|try again|not delivered|error)\\b", RegexOption.IGNORE_CASE)
    private val SENDING = Regex("\\b(sending|queued|waiting to send|pending)\\b", RegexOption.IGNORE_CASE)

    /** Connector tools that write, and their kind. */
    private val CONNECTOR_KINDS = mapOf(
        "gmail_send" to "send", "calendar_add_event" to "create", "calendar_delete_event" to "delete",
        "set_alarm" to "create", "set_timer" to "create",
        "add_job" to "create", "update_job" to "save", "delete_job" to "delete", "space_save" to "save", "space_update" to "save", "space_delete" to "delete",
    )
    /** Read tools whose results count as gathered items (one item per top-level line). */
    private val GATHER = setOf("gmail_search", "calendar_events", "contacts_search", "read_notifications", "unreplied_messages", "space_search", "list_jobs")
    /** A request to delete or clear many things at once. */
    private val BULK = Regex("\\b(all|every|everything|entire|whole)\\b[^.?!]{0,40}\\b(delete|remove|clear|erase|wipe|trash)|\\b(delete|remove|clear|erase|wipe|trash)\\b[^.?!]{0,40}\\b(all|every|everything|entire|whole)\\b|\\b(factory reset|wipe (the |my )?phone)\\b", RegexOption.IGNORE_CASE)
    private val ACTIONS = setOf("tap", "tap_xy", "long_press", "type_text", "scroll", "swipe", "press", "open_app", "open_notification", "show_user", "directions", "play_media", "replay_route")

    /**
     * Runs one tool call through the ledger: refuses writes while an op is unknown, records an
     * entry for effectful calls, and appends the op's receipt to the result.
     */
    suspend fun track(ctx: android.content.Context, name: String, input: JSONObject, target: TaskTarget, run: suspend () -> ToolOutcome): ToolOutcome {
        val ledger = target.effects
        if (name == "reconcile_effect") return reconcile(input, target)
        val svc = ControlService.instance
        val before = if (name == "tap" || name == "type_text") svc?.let { runCatching { it.snapshot(target.displayId, target.maxNodes) }.getOrNull() } else null
        observedUse(name, input, target)?.let { return it }
        val intent = intentOf(name, input, before, target)
        if (intent != null) {
            ledger.unknown().takeIf { it.isNotEmpty() }?.let { u ->
                return ToolOutcome.error("Blocked: an earlier action's result is unconfirmed (${u.joinToString("; ") { it.line() }}). " +
                    "Look at the screen (get_screen), then call reconcile_effect for it: outcome=done with the text on screen that shows it happened, or failed if it clearly did not. No other writes until then.")
            }
            // Deleting in bulk ("delete all my contacts") cannot be undone: the user confirms with ask_user first, even when asked directly.
            if (intent.kind == "delete" && BULK.containsMatchIn(target.request) && target.approvals == 0)
                return ToolOutcome.error("Blocked: the user asked to delete in bulk, which can't be undone. Call ask_user first and say exactly what will be deleted " +
                    "(how many items, from which app); go ahead only after they confirm.")
            val e = ledger.open(intent.kind, name, before?.packageName ?: target.lastPackage.orEmpty(), intent.where, intent.payload)
            ledger.entries.firstOrNull { it !== e && it.key == e.key && it.state == EffectLedger.State.DONE && it.kind in setOf("send", "pay") }?.let { prior ->
                if (target.approvals > 0) target.approvals-- else {
                    ledger.entries.remove(e)
                    return ToolOutcome.error("Blocked: this is a repeat of op ${prior.op}, which is already done (${prior.evidence}). Don't do it twice; " +
                        "if the user really wants it again, ask_user first.")
                }
            }
            val out = try { run() } catch (t: Throwable) {
                e.state = if (t is java.io.IOException) EffectLedger.State.UNKNOWN else EffectLedger.State.FAILED
                e.evidence = "the call threw ${t.javaClass.simpleName}"
                throw t
            }
            judge(e, intent, out, name, target, before)
            if (e.state == EffectLedger.State.UNKNOWN && svc != null && name in ACTIONS) {
                // Sends often show a moment later ("Sending..." -> the bubble): look once more.
                delay(1500); svc.waitForIdle()
                runCatching { svc.snapshot(target.displayId, target.maxNodes) }.getOrNull()?.let { judgeScreen(e, intent, before, it) }
            }
            return ToolOutcome(out.text + "\n\nReceipt: " + e.line() +
                if (e.state == EffectLedger.State.UNKNOWN) "\nThe result is unconfirmed: check the screen and call reconcile_effect before anything else that writes." else "",
                out.imagePng, out.isError)
        }
        val out = run()
        if (name in ACTIONS && !out.isError) ledger.acted = true
        if (name in GATHER && !out.isError) ledger.gathered += out.text.lines().count { it.isNotBlank() && !it[0].isWhitespace() && !it.trimEnd().endsWith(":") }
        return out
    }

    /** Writes whose values are checked against what `recall` returned (docs/MEMORY.md 4). */
    private val USES_VALUES = setOf("type_text", "gmail_send", "calendar_add_event", "add_job", "update_job", "space_save", "space_update")

    /** An observed value (from `recall`) used in an action needs the owner's OK first: one ask_user answer covers one call. */
    private fun observedUse(name: String, input: JSONObject, target: TaskTarget): ToolOutcome? {
        if (name !in USES_VALUES) return null
        val s = target.session ?: return null
        val value = if (name == "type_text") target.secrets.resolve(input.optString("text")) else input.toString()
        val foreign = com.agentos.agent.Provenance.fromObserved(value, s.ownerText(), s.observedText())
        if (foreign.isEmpty()) return null
        if (target.approvals > 0) { target.approvals--; return null }
        return ToolOutcome.error("Blocked: ${foreign.take(3).joinToString { "\"$it\"" }} came from recall (what you observed on the phone), not from the user. " +
            "Call ask_user to check it with them first (say the value and what you will do with it), then try again.")
    }

    /** The effect a call would have, or null for reads, navigation and plain typing. */
    private fun intentOf(name: String, input: JSONObject, before: ScreenSnapshot?, target: TaskTarget): EffectIntent? {
        CONNECTOR_KINDS[name]?.let { kind ->
            if (name == "space_save" && input.optString("kind") == "memory") return null  // memories are checked by provenance instead
            val payload = when (name) {
                "gmail_send" -> "to ${input.optString("to")}: ${input.optString("subject")} ${input.optString("body").take(80)}"
                "calendar_add_event" -> "${input.optString("title")} ${input.optString("start")}"
                else -> input.optString("title").ifBlank { input.optString("text").ifBlank { input.optString("id") } }
            }
            return EffectIntent(kind, name.substringBefore('_'), payload, null, null)
        }
        if (before == null) return null
        val title = screenTitle(before)
        val composer = before.nodes.firstOrNull { it.editable && it.value.isNotBlank() && !looksSearch(it) }
        when (name) {
            "type_text" -> {
                if (!input.optBoolean("submit", false)) return null
                val id = input.optInt("id", -1)
                val field = (if (id > 0) before.find(id) else null) ?: before.nodes.firstOrNull { it.editable && it.focused }
                if (field != null && looksSearch(field)) return null
                if (field == null && SEARCHY.containsMatchIn(input.optString("text"))) return null
                val text = target.secrets.resolve(input.optString("text"))
                return EffectIntent("submit", title, text, field, text)
            }
            "tap" -> {
                val n = before.find(input.optInt("id", -1)) ?: return null
                val label = before.labelOf(n)
                if (label.isEmpty() || label.length > 40) return null
                val kind = when {
                    SEND.containsMatchIn(label) -> if (composer != null) "send" else return null  // "Reply" / "Share" with no draft just opens a composer
                    PAY.containsMatchIn(label) -> "pay"
                    // A keypad's backspace (timer, dialer) is labelled Delete too.
                    DELETE.containsMatchIn(label) -> if (keypad(before)) return null else "delete"
                    CONFIRM.containsMatchIn(label) -> "confirm"
                    else -> return null
                }
                return EffectIntent(kind, listOf(title, label).filter { it.isNotBlank() }.joinToString(" / "), composer?.value.orEmpty(), n, composer?.value)
            }
        }
        return null
    }

    private fun keypad(s: ScreenSnapshot) = s.nodes.count { it.clickable && it.text.trim().length == 1 && it.text.trim()[0].isDigit() } >= 8

    private fun looksSearch(n: UiNode) = SEARCHY.containsMatchIn(n.resId + " " + n.desc + " " + (if (n.showingHint) n.text else "") + " " + n.className) ||
        n.node.hintText?.let { SEARCHY.containsMatchIn(it) } == true

    /** The chat / screen name: the first plain text near the top (a toolbar title). */
    private fun screenTitle(s: ScreenSnapshot): String = s.nodes.firstOrNull { it.text.isNotBlank() && !it.editable && !it.clickable && it.bounds.top < s.screenH * 0.18 && it.text.length < 60 }?.text.orEmpty()

    private fun judge(e: EffectLedger.Entry, intent: EffectIntent, out: ToolOutcome, name: String, target: TaskTarget, before: ScreenSnapshot?) {
        if (name !in ACTIONS) {
            // A connector: its answer is the evidence.
            if (!out.isError) { e.state = EffectLedger.State.DONE; e.evidence = "${e.tool} answered: ${out.text.lineSequence().first().take(80)}" }
            else if (Regex("timeout|timed out|unable to resolve|connection|socket|reset", RegexOption.IGNORE_CASE).containsMatchIn(out.text)) {
                e.state = EffectLedger.State.UNKNOWN; e.evidence = "the request may not have reached the server: ${out.text.take(80)}"
            } else { e.state = EffectLedger.State.FAILED; e.evidence = out.text.take(100) }
            return
        }
        target.effects.acted = true
        if (out.isError || out.text.contains("did not register") || out.text.contains("Gesture failed")) {
            e.state = EffectLedger.State.FAILED; e.evidence = out.text.lineSequence().first().take(100); return
        }
        val svc = ControlService.instance ?: run { e.state = EffectLedger.State.UNKNOWN; e.evidence = "no screen to check"; return }
        judgeScreen(e, intent, before, svc.snapshot(target.displayId, target.maxNodes))
    }

    /** Decides done / failed / unknown from the screen after the action. */
    internal fun judgeScreen(e: EffectLedger.Entry, intent: EffectIntent, before: ScreenSnapshot?, after: ScreenSnapshot) {
        val payload = intent.composer?.let { EffectLedger.fold(it) }.orEmpty()
        val texts = after.nodes.filter { !it.editable }.map { EffectLedger.fold(it.text.ifEmpty { it.desc }) }
        val composerNow = after.nodes.filter { it.editable && !looksSearch(it) }.map { EffectLedger.fold(it.value) }
        val failure = after.nodes.firstOrNull { !it.editable && it.text.length < 80 && FAILURE.containsMatchIn(it.text.ifEmpty { it.desc }) }
        if (payload.isNotEmpty() && (e.kind == "send" || e.kind == "submit")) {
            val stillDrafted = composerNow.any { it == payload || (payload.length > 12 && it.contains(payload)) }
            val shown = texts.any { it == payload || (payload.length >= 6 && it.contains(payload.take(120))) }
            when {
                failure != null -> { e.state = EffectLedger.State.FAILED; e.evidence = "the app shows \"${failure.text.ifEmpty { failure.desc }.take(60)}\"" }
                stillDrafted -> { e.state = EffectLedger.State.FAILED; e.evidence = "the text is still in the composer (not sent)" }
                shown && after.nodes.any { SENDING.containsMatchIn(it.text.ifEmpty { it.desc }) && it.text.length < 40 } ->
                    { e.state = EffectLedger.State.UNKNOWN; e.evidence = "the text is shown but still marked as sending" }
                shown -> { e.state = EffectLedger.State.DONE; e.evidence = "composer cleared and the sent text is visible on screen" }
                else -> { e.state = EffectLedger.State.UNKNOWN; e.evidence = "the composer cleared but the text is not visible" }
            }
            return
        }
        // Delete / pay / confirm: the control is gone or the screen moved on, without an error notice.
        val n = intent.node
        val label = n?.let { EffectLedger.fold(before?.labelOf(it) ?: it.text.ifEmpty { it.desc }) }.orEmpty()
        val stillThere = n != null && after.nodes.any { it.bounds == n.bounds && EffectLedger.fold(after.labelOf(it)) == label }
        val sameScreen = before != null && before.render() == after.render()
        when {
            failure != null -> { e.state = EffectLedger.State.FAILED; e.evidence = "the app shows \"${failure.text.ifEmpty { failure.desc }.take(60)}\"" }
            !stillThere && !sameScreen -> { e.state = EffectLedger.State.DONE; e.evidence = "\"${label.take(30)}\" is gone and the screen changed" }
            else -> { e.state = EffectLedger.State.UNKNOWN; e.evidence = "the screen did not visibly change after tapping \"${label.take(30)}\"" }
        }
    }

    /** reconcile_effect: the model settles an unknown op after looking at the screen; code checks what it can. */
    private fun reconcile(input: JSONObject, target: TaskTarget): ToolOutcome {
        val e = target.effects.op(input.optInt("op_id", -1)) ?: return ToolOutcome.error("No op with that id. Ops this turn: " +
            target.effects.entries.joinToString("; ") { it.line() }.ifBlank { "none" })
        val outcome = input.optString("outcome")
        val quote = input.optString("evidence").trim()
        when (outcome) {
            "done" -> if (e.tool !in ACTIONS) {
                // A connector write has no screen: the evidence must come from a later read's result (e.g. gmail_search finding the sent email).
                val seen = EffectLedger.fold(target.session?.untrustedText()?.takeLast(20_000).orEmpty())
                if (quote.length < 8 || !seen.contains(EffectLedger.fold(quote))) return ToolOutcome.error(
                    "Not reconciled: for a ${e.tool} op, quote text from a later tool result that shows it happened (search for it first). If it did not happen, use outcome=failed.")
                e.state = EffectLedger.State.DONE; e.evidence = "reconciled: a later result shows \"${quote.take(60)}\""
            } else {
                val svc = ControlService.instance ?: return ToolOutcome.error("Cannot read the screen to check.")
                val screen = svc.snapshot(target.displayId, target.maxNodes)
                val all = screen.nodes.joinToString("\n") { EffectLedger.fold(it.text + " " + it.desc) }
                if (quote.length < 3 || !all.contains(EffectLedger.fold(target.secrets.resolve(quote)))) return ToolOutcome.error(
                    "Not reconciled: evidence must be text visible on the current screen that shows op ${e.op} happened (e.g. the sent message, " +
                        "a 'Sent' / 'Deleted' notice). \"${quote.take(60)}\" is not on screen. If it did not happen, use outcome=failed.")
                e.state = EffectLedger.State.DONE; e.evidence = "reconciled: \"${quote.take(60)}\" is on screen"
            }
            "failed" -> { e.state = EffectLedger.State.FAILED; e.evidence = "reconciled as not done" + (if (quote.isNotBlank()) ": ${quote.take(80)}" else "") }
            else -> return ToolOutcome.error("outcome must be done or failed")
        }
        return ToolOutcome("Receipt: " + e.line())
    }
}

/**
 * The deterministic check before a turn's final reply: does what the summary claims ("sent",
 * "deleted", "booked", "here are your 10 emails") match this turn's receipts? No model is
 * involved. It is conservative: a claim kind the user did not ask for, a negated, hedged or
 * future sentence, or a question is never flagged. A mismatch gives the model one corrective step.
 */
object CompletionCheck {
    private class Family(val name: String, val request: Regex, val claim: Regex, val kinds: Set<String>)

    private fun rx(s: String) = Regex(s, RegexOption.IGNORE_CASE)
    private val FAMILIES = listOf(
        Family("send", rx("\\b(send|reply|respond|post|share|forward|tell|dm|tweet|comment)\\b|^\\W*(text|message|e-?mail)\\b|\\b(text|message|e-?mail) (him|her|them|mom|dad|[a-z]+ (that|to|saying|about))\\b"),
            rx("\\b(sent|texted|messaged|e-?mailed|replied|responded|posted|shared|forwarded|commented|tweeted)\\b(?! by| from| to you)"), setOf("send", "submit")),
        Family("delete", rx("\\b(delete|remove|trash|archive|clear|erase|unsubscribe|cancel)\\b"),
            rx("\\b(deleted|removed|trashed|archived|cleared|erased|unsubscribed|cancell?ed)\\b"), setOf("delete", "confirm")),
        Family("pay", rx("\\b(buy|order|purchase|pay|check ?out)\\b"),
            rx("\\b(bought|ordered|purchased|paid|placed (the|your|an?) order|checked out)\\b"), setOf("pay", "confirm")),
        Family("book", rx("\\b(book|reserve|schedule)\\b"), rx("\\b(booked|reserved)\\b"), setOf("pay", "confirm", "create", "submit")),
    )
    /** Claims of a change that need no keyed receipt: any write or phone action this turn satisfies them. */
    private val CHANGE_REQUEST = rx("\\b(set|save|create|add|make|start|turn (on|off)|switch|enable|disable|change|update|rename|move|schedule|remind|note)\\b")
    private val CHANGE_CLAIM = rx("\\b(i('ve| have)? (set|saved|created|added|made|started|turned|switched|enabled|disabled|changed|updated|renamed|moved|scheduled)|(is|has been|was) (now )?(set|saved|created|added|started|turned on|turned off|enabled|disabled|scheduled))\\b")
    private val HEDGE = rx("\\b(not|n't|never|unable|failed|couldn|can't|cannot|wasn|didn|haven|won't|without|unconfirmed|unsure|if you|want me|should i|shall i|would you|do you|will|i'll|going to|ready to|about to|draft|before)\\b")
    private val NUM = mapOf("one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10,
        "eleven" to 11, "twelve" to 12, "fifteen" to 15, "twenty" to 20, "thirty" to 30)
    private val COUNT = rx("\\b(here (are|is)|these are|i (found|read|checked|went through|looked at|listed|got)|there (are|were)|you have)\\s+(?:your |the |my |)(?:(?:latest|last|recent|newest|top|first|unread|new)\\s+)?(?<n>\\d{1,3}|${NUM.keys.joinToString("|")})\\s+(?:\\w+\\s+){0,2}?(?<noun>e-?mails?|messages?|chats?|texts?|notifications?|items?|results?|events?|contacts?|rows?|entries|files?|photos?|songs?|orders?|meetings?|appointments?|alarms?|notes?|jobs?)\\b")

    /** A correction for the model, or null when the summary is consistent with the receipts. */
    fun check(summary: String, request: String, ledger: EffectLedger): String? {
        val sentences = summary.split(Regex("(?<=[.!?\\n])\\s+")).map { it.trim() }.filter { it.isNotEmpty() && !it.endsWith("?") && !HEDGE.containsMatchIn(it) }
        val problems = ArrayList<String>()
        for (f in FAMILIES) {
            if (!f.request.containsMatchIn(request)) continue
            val claim = sentences.firstOrNull { f.claim.containsMatchIn(it) } ?: continue
            val word = f.claim.find(claim)!!.value
            if (ledger.done(f.kinds).isNotEmpty()) continue
            val open = ledger.entries.filter { it.kind in f.kinds }
            problems += "your reply says \"$word\", but this turn has no completed ${f.name} receipt" +
                (if (open.isEmpty()) " (no such action was taken)" else ": " + open.joinToString("; ") { it.line() })
        }
        if (CHANGE_REQUEST.containsMatchIn(request)) sentences.firstOrNull { CHANGE_CLAIM.containsMatchIn(it) }?.let { claim ->
            if (ledger.entries.none { it.state == EffectLedger.State.DONE } && !ledger.acted)
                problems += "your reply says \"${CHANGE_CLAIM.find(claim)!!.value}\", but no write and no phone action ran this turn"
        }
        if (ledger.gathered.isNotEmpty()) sentences.forEach { s ->
            COUNT.find(s)?.let { m ->
                val n = m.groups["n"]!!.value.lowercase().let { it.toIntOrNull() ?: NUM[it] } ?: return@let
                val max = ledger.gathered.max()
                if (n > max) problems += "your reply counts $n ${m.groups["noun"]!!.value}, but the most this turn's reads returned is $max"
            }
        }
        ledger.unknown().takeIf { it.isNotEmpty() }?.let { u -> problems += "these results are still unconfirmed: " + u.joinToString("; ") { it.line() } }
        if (problems.isEmpty()) return null
        return "Completion check (automatic, from this turn's receipts): " + problems.joinToString("; ") + ". " +
            "Fix this now: if the action did not happen, finish it or say plainly that it did not; if it did, confirm it on screen " +
            "(reconcile_effect for unconfirmed ops). Then call done again with a summary that matches what actually happened."
    }
}
