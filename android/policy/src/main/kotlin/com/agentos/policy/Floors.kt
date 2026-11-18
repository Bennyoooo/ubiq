package com.agentos.policy

/**
 * Hard floors: things the agent may never do, whatever the rules, origin or approvals say.
 * Screen-based floors are best effort: they look at the target element's label, the typed text
 * and an excerpt of the screen.
 *
 * [ownerOnly] answers whether an action targets one of the owner's own screens (approvals, rules);
 * [protectedPackages] are Ubiq's own packages, which the agent may never disable or remove.
 */
class Floors(
    private val env: Env,
    val protectedPackages: Set<String>,
    private val ownerOnly: (Check) -> Boolean,
) {
    /** What the floors need from the phone. */
    interface Env {
        /** True when [pkg] is a phone (dialer) app. */
        fun isDialer(pkg: String): Boolean
        /** The SIM's or the system's own emergency number check; false when unknown. */
        fun isEmergencyNumber(number: String): Boolean
    }

    fun violation(c: Check): String? {
        if (c.effect == Effect.READ) return null
        if (ownerOnly(c)) return "the owner's own Ubiq screens (approvals, rules) are for the owner only"
        val label = c.label
        if (c.pkg in SYSTEM_SETTINGS) {
            if (RESET.containsMatchIn(label) || RESET.containsMatchIn(c.text)) return "factory reset and erasing the phone are never allowed"
            if (REMOVE.containsMatchIn(label) && (AGENTOS.containsMatchIn(c.screen) || AGENTOS.containsMatchIn(label)))
                return "disabling, stopping or uninstalling Ubiq or Ubiq Core is never allowed"
        }
        val dialer = c.pkg in DIALERS || env.isDialer(c.pkg)
        if (dialer || label.contains("call", true)) {
            // The number being dialled sits on the dialer's screen when its call button is pressed.
            for (s in listOf(c.text, label, if (dialer && label.contains("call", true)) c.screen else "")) {
                emergencyNumberIn(s)?.let { return "calling emergency numbers ($it) is never allowed for the agent" }
            }
        }
        return null
    }

    fun emergencyNumberIn(s: String): String? {
        if (s.isBlank()) return null
        for (m in DIGITS.findAll(s)) {
            val n = m.value.filter { it.isDigit() || it == '+' }
            if (n.isEmpty()) continue
            if (n in EMERGENCY) return n
            if (try { env.isEmergencyNumber(n) } catch (_: Throwable) { false }) return n
        }
        return null
    }

    companion object {
        val SYSTEM_SETTINGS = setOf(
            "com.android.settings", "com.android.packageinstaller", "com.google.android.packageinstaller",
            "com.android.permissioncontroller", "com.google.android.permissioncontroller", "com.android.vending",
        )
        val DIALERS = setOf("com.android.dialer", "com.google.android.dialer", "org.fossify.phone", "com.android.server.telecom", "com.android.phone")
        private val RESET = Regex("""\b(factory reset|factory data reset|erase all( data)?|reset (phone|device|tablet)|erase (esim|e-sim|sim)s?|delete all data)\b""", RegexOption.IGNORE_CASE)
        private val REMOVE = Regex("""\b(uninstall|disable|force stop|clear storage|clear data|clear cache|deactivate|remove|turn off)\b""", RegexOption.IGNORE_CASE)
        private val AGENTOS = Regex("""Ubiq|AgentOS""", RegexOption.IGNORE_CASE)
        // Common ones everywhere; the SIM's own list comes from Env.isEmergencyNumber.
        private val EMERGENCY = setOf("911", "112", "999", "000", "110", "119", "118", "100", "101", "102", "108", "122", "190", "193", "1122")
        private val DIGITS = Regex("""(?<![\d*#+])\+?[\d\s\-().]{2,12}(?![\d*#])""")

        /** Settings the agent may never write (security, provisioning, Ubiq's own wiring). */
        private val SETTINGS_DENY = listOf(
            Regex("""^(adb_enabled|adb_wifi_enabled|development_settings_enabled|package_verifier_.*|verifier_.*|install_non_market_apps|hidden_api_.*)$"""),
            Regex("""^(device_provisioned|user_setup_complete|secure_frp_mode|.*frp.*|lockscreen\..*|lock_screen_.*|lock_pattern_.*|trust_agents_initialized)$"""),
            Regex("""^(enabled_accessibility_services|accessibility_enabled|enabled_notification_listeners|enabled_notification_assistant|voice_interaction_service|assistant|voice_recognition_service)$"""),
            Regex("""^(default_input_method|enabled_input_methods|selected_input_method_subtype|credential_service.*|autofill_service)$"""),
            Regex("""^(airplane_mode_on|bluetooth_address|mobile_data.*|data_roaming.*|wifi_.*password.*|device_name)$"""),
            Regex("""^(force_.*|policy_control|always_finish_activities|debug_app|wait_for_debugger|stay_on_while_plugged_in)$"""),
            Regex("""^(emergency_.*|.*_emergency_.*|ecall.*)$"""),
        )

        fun settingDenied(key: String): Boolean = SETTINGS_DENY.any { it.matches(key) }
    }
}
