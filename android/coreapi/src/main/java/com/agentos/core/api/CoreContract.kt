package com.agentos.core.api

/** Names shared by Ubiq Core and its one client, the Ubiq app. */
object CoreContract {
    const val VERSION = 2
    const val PACKAGE = "com.agentos.core"
    const val CLIENT_PACKAGE = "ai.ubiq.app"
    /** signature|privileged|knownSigner, defined by Core; the Ubiq app requests it. */
    const val PERMISSION_CONTROL = "com.agentos.core.permission.CONTROL"
    /** Bind action of Core's service. */
    const val ACTION_BIND = "com.agentos.core.BIND"
    /** Opens Core's owner rules screen (Stop switch, per-app rules). */
    const val ACTION_RULES = "com.agentos.core.RULES"

    // Reply states.
    const val DONE = "done"
    const val FAILED = "failed"
    const val UNKNOWN = "unknown"
    const val REFUSED = "refused"
    const val APPROVAL_REQUIRED = "approval_required"

    // App rules, loosest first.
    val RULES = listOf("full", "ask", "read", "off")
}
