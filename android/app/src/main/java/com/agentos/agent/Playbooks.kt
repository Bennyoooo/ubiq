package com.agentos.agent

import android.content.Context
import android.content.Intent
import android.view.Display
import java.util.ServiceLoader

/**
 * Scripted playbooks (Settings > Demo playbooks). A request that matches one plays a fixed sequence
 * on the agent's screen instead of asking the model: open the apps, tap through them, stay on each
 * long enough to show it with Watch, and end with a prepared answer. For filming the same take
 * every time.
 *
 * This build ships none: playbooks come from a [Provider] on the classpath (META-INF/services),
 * which a private build can add. Without one, [available] is false and Settings hides the switch.
 */
object Playbooks {
    sealed class Step {
        /** Set by [whenAnswer]: the step runs only when the user's last answer matches. */
        var ifAnswer: Regex? = null
            private set
        fun whenAnswer(pattern: String): Step = apply { ifAnswer = Regex(pattern, RegexOption.IGNORE_CASE) }
        /** Set by [whenAsked]: the step runs only when the user's request matches (one playbook, an optional part). */
        var ifAsked: Regex? = null
            private set
        fun whenAsked(pattern: String): Step = apply { ifAsked = Regex(pattern, RegexOption.IGNORE_CASE) }

        /** The status line on the island and in the chat. */
        class Note(val text: String) : Step()
        class Open(val app: String) : Step()
        /**
         * Taps the first element showing [text], looking [tries] times; skipped when the screen never shows it.
         * [unless] and [onlyIf] name another label on the screen, so a take works from wherever the last one left the app.
         */
        class Tap(val text: String, val tries: Int = 3, val unless: String? = null, val onlyIf: String? = null) : Step()
        /** Scrolls once, or with [until] up to 4 times until that label is on screen (none when it already is); none at all while [unless] shows. */
        class Scroll(val direction: String = "down", val until: String? = null, val unless: String? = null) : Step()
        class Wait(val seconds: Double) : Step()
        /**
         * Asks the user and waits; [options] become answer keys (on the island card when the take runs in the
         * background). The answer picks the [Reply] text and the [whenAnswer] steps; [stopIfNo] ends the steps on a no.
         */
        class Ask(val question: String, val options: List<String> = emptyList(), val stopIfNo: Boolean = false) : Step()
        /** The final answer; [ifNo] when the user said no to the [Ask]. */
        class Reply(val text: String, val ifNo: String? = null) : Step()
        /** After the answer, brings this app (as the agent left it) to the user's screen. */
        class Show(val pkg: String) : Step()
        /** Any agent tool with these arguments (open_link, type_text, add_job...); skipped when the screen already shows [unless]. */
        class Run(val tool: String, val args: Map<String, Any?>, val unless: String? = null, val onlyIf: String? = null) : Step()
        /** The face or fingerprint prompt before money moves. Nothing is paid; a no ends the steps like [Ask.stopIfNo]. */
        class Approve(val amount: String, val merchant: String, val what: String) : Step()
        /** Puts a pass (QR, AZTEC or PDF417 [format]) on the lock screen; [wake] turns the screen on for it. */
        class Pass(val title: String, val detail: String, val code: String, val format: String = "QR", val wake: Boolean = false) : Step()
        /**
         * Types [text] (or, with [useAnswer], the user's last answer) in the focused field, then taps [button]. While
         * sends are dry ([sendsLive] off, the default) it only types: prep and tests never send a real message.
         */
        class Send(val text: String = "", val button: String = "Send", val useAnswer: Boolean = false) : Step()
        /** Keeps the text of the agent's screen as it is now, for a later step that reads it ([Custom]). */
        class Read : Step()
        /** The agent's screen as it is now becomes the answer's screenshot (kept even when a mini app opens after it). */
        class Shot : Step()
        /**
         * A step the playbook's provider runs itself, given the screens kept by [Read] and the user's request.
         * Its [Outcome] can set the answer, or say it opened something on the user's screen.
         */
        class Custom(val name: String, val action: suspend (ctx: Context, reads: List<String>, request: String) -> Outcome?) : Step()
    }

    /** What a [Step.Custom] did: the answer it wrote ([reply]), and whether it put something on the user's screen ([shown]). */
    class Outcome(val reply: String? = null, val shown: Boolean = false)

    /**
     * [title] names the background task when a trigger or job starts it. [after] limits the match to a chat whose
     * last playbook was that one ("keep doing this"). [answerCard] false keeps the screen clear at the end (a QR code).
     */
    class Playbook(val name: String, val match: Regex, val steps: List<Step>, val title: String = name, val after: String? = null, val answerCard: Boolean = true)

    /** Supplies playbooks. Register an implementation in META-INF/services/com.agentos.agent.Playbooks$Provider. */
    interface Provider {
        fun playbooks(): List<Playbook>
    }

    private val all: List<Playbook> by lazy {
        try {
            ServiceLoader.load(Provider::class.java, Provider::class.java.classLoader).flatMap { it.playbooks() }
        } catch (_: Throwable) { emptyList() }
    }

    /** True when this build has any playbooks. */
    val available: Boolean get() = all.isNotEmpty()

    /** [last] is the playbook this chat played last, for the ones that only follow it. */
    fun match(text: String, last: String? = null): Playbook? = matchIn(all, text, last)

    /** [match] over [list] (tests). */
    fun matchIn(list: List<Playbook>, text: String, last: String? = null): Playbook? {
        // Text shared into the app arrives with this prefix; anchored phrases ("End focus") must still match.
        val t = text.removePrefix("The user shared this with you:").trim()
        // A take is asked for in a sentence or two; a long message (pasted notes, a workout log that mentions
        // "Mexico trip") is about something else and goes to the model.
        if (t.length > MAX_REQUEST) return null
        return list.firstOrNull { (it.after == null || it.after == last) && it.match.containsMatchIn(t) }
    }

    private const val MAX_REQUEST = 200

    fun byName(name: String): Playbook? = all.firstOrNull { it.name.equals(name, true) }

    /**
     * Whether [Step.Send] really taps Send. Off by default, so prep and tests only type. A private build can turn it on
     * for filming (the demo trigger: `--es sends live`, and back off with `--es sends dry`).
     */
    fun sendsLive(ctx: Context): Boolean = ctx.getSharedPreferences("demo", Context.MODE_PRIVATE).getBoolean("sends_live", false)
    fun setSendsLive(ctx: Context, on: Boolean) = ctx.getSharedPreferences("demo", Context.MODE_PRIVATE).edit().putBoolean("sends_live", on).apply()

    val NO = Regex("(?i)^\\W*(no|nope|nah|personal|not|cancel|skip)\\b")

    /** The id of the reply box in a get_screen listing: a focused editable field first, else the last one (chat boxes sit at the bottom). */
    fun editable(screen: String): Int? {
        val rows = screen.lineSequence().mapNotNull { l -> Regex("^\\[(\\d+)] .*\\((?:[^)]*\\b)?editable\\b([^)]*)\\)").find(l)?.let { it.groupValues[1].toInt() to l } }
            .filter { (_, l) -> !l.contains("password") }.toList()
        return (rows.firstOrNull { (_, l) -> Regex("\\(([^)]*\\b)?focused\\b").containsMatchIn(l) } ?: rows.lastOrNull())?.first
    }

    /** The id of the element in a get_screen listing that shows [needle]: an exact label first, else a short one starting with it (not a chat row that mentions it). */
    fun find(screen: String, needle: String): Int? {
        val rows = screen.lineSequence().mapNotNull { l -> Regex("^\\[(\\d+)] (.*)").find(l)?.let { it.groupValues[1].toInt() to it.groupValues[2] } }.toList()
        val labels = rows.map { (id, rest) -> id to Regex("(?:\"|desc=\")([^\"]*)\"").findAll(rest).map { it.groupValues[1].trim() }.toList() }
        return labels.firstOrNull { (_, ls) -> ls.any { it.equals(needle, true) } }?.first
            ?: labels.firstOrNull { (_, ls) -> ls.any { it.startsWith(needle, true) && it.length <= needle.length + 12 } }?.first
    }

    /**
     * Keeps the phone's screen on and bright for a take: on some phones the agent's hidden screens sleep with the real
     * one. [hold] is called before every step, so a screen that went off mid-take (the power button) comes back.
     */
    class ScreenOn(ctx: Context) {
        private val power = ctx.getSystemService(android.os.PowerManager::class.java)
        @Suppress("DEPRECATION")
        private val lock = power.newWakeLock(android.os.PowerManager.SCREEN_BRIGHT_WAKE_LOCK or android.os.PowerManager.ACQUIRE_CAUSES_WAKEUP or android.os.PowerManager.ON_AFTER_RELEASE, "ubiq:playbook")
            .apply { setReferenceCounted(false) }
        fun hold() {
            if (lock.isHeld && !power.isInteractive) lock.release()
            if (!lock.isHeld) lock.acquire(10 * 60 * 1000L)
        }
        fun release() { if (lock.isHeld) lock.release() }
    }

    /** Opens [pkg] on the user's screen; an app the agent already has open comes with the screen it left. */
    fun showOnScreen(ctx: Context, pkg: String) {
        val i = ctx.packageManager.getLaunchIntentForPackage(pkg)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) ?: return
        val o = android.app.ActivityOptions.makeBasic().setLaunchDisplayId(Display.DEFAULT_DISPLAY)
        try { ctx.startActivity(i, o.toBundle()) } catch (_: Throwable) {}
    }
}
