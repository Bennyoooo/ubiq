package com.agentos.policy

/** One thing the agent is about to do, as the policy sees it. */
class Check(
    val pkg: String,
    val effect: String,      // Effect.READ | WRITE | DESTRUCTIVE | EXTERNAL
    val origin: String,      // owner | agent | job | trigger
    val task: String,
    val label: String = "",  // what is being acted on (button text), if known
    val text: String = "",   // text being typed, if any
    val screen: String = "", // screen text excerpt, for floors that depend on context
)

/** The policy's answer. [state] uses the same strings as Core's reply states. */
class Decision(val state: String, val reason: String, val approvalId: String? = null) {
    val allowed get() = state == DONE

    companion object {
        const val DONE = "done"
        const val REFUSED = "refused"
        const val APPROVAL_REQUIRED = "approval_required"
    }
}

/** How much an action can change. */
object Effect {
    const val READ = "read"
    const val WRITE = "write"
    const val DESTRUCTIVE = "destructive"
    const val EXTERNAL = "external"

    /** Phone tools that only look. */
    val READ_TOOLS = setOf("get_screen", "screenshot", "open_app", "open_notification")
    /** Phone tools that act on the screen. */
    val WRITE_TOOLS = setOf("tap", "long_press", "tap_xy", "type_text", "scroll", "swipe", "press")

    private val EXTERNAL_WORDS = Regex("\\b(send|reply|post|publish|share|pay|buy|purchase|order|checkout|transfer|call)\\b", RegexOption.IGNORE_CASE)
    private val DESTRUCTIVE_WORDS = Regex("\\b(delete|remove|erase|reset|uninstall|clear|discard)\\b", RegexOption.IGNORE_CASE)

    fun gated(tool: String) = tool in READ_TOOLS || tool in WRITE_TOOLS

    /** The effect of phone tool [tool] acting on an element labelled [label]; [submits] when typing also sends. */
    fun of(tool: String, label: String, submits: Boolean = false): String = when {
        tool in READ_TOOLS -> READ
        DESTRUCTIVE_WORDS.containsMatchIn(label) -> DESTRUCTIVE
        EXTERNAL_WORDS.containsMatchIn(label) || (tool == "type_text" && submits) -> EXTERNAL
        else -> WRITE
    }
}
