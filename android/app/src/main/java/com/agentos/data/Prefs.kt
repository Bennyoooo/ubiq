package com.agentos.data

import android.content.Context
import android.content.SharedPreferences

/** Small typed wrapper over private SharedPreferences. API key stays inside the app sandbox. */
object Prefs {
    const val DEFAULT_MODEL = "gpt-6.1-sol"
    const val DEFAULT_EFFORT = "medium"

    private fun sp(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences("agentos_prefs", Context.MODE_PRIVATE)

    fun apiKey(ctx: Context): String = sp(ctx).getString("api_key", "") ?: ""
    fun setApiKey(ctx: Context, v: String) = sp(ctx).edit().putString("api_key", v.trim()).apply()

    fun model(ctx: Context): String = sp(ctx).getString("model", DEFAULT_MODEL) ?: DEFAULT_MODEL
    fun setModel(ctx: Context, v: String) = sp(ctx).edit().putString("model", v.trim()).apply()

    fun effort(ctx: Context): String = sp(ctx).getString("effort", DEFAULT_EFFORT) ?: DEFAULT_EFFORT
    fun setEffort(ctx: Context, v: String) = sp(ctx).edit().putString("effort", v).apply()

    /** OpenAI service tier for the main model: "" (the account default), "fast", "flex". */
    fun serviceTier(ctx: Context): String = sp(ctx).getString("service_tier", "") ?: ""
    fun setServiceTier(ctx: Context, v: String) = sp(ctx).edit().putString("service_tier", v.trim()).apply()

    /** Offer the main model the cheap `navigate` tool (agent/Navigator). */
    fun navigator(ctx: Context): Boolean = sp(ctx).getBoolean("navigator", true)
    fun setNavigator(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("navigator", v).apply()
    /** The express lane (agent/Express): weather, directions and play handled without the main model. */
    fun expressLane(ctx: Context): Boolean = sp(ctx).getBoolean("express", true)
    fun setExpressLane(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("express", v).apply()

    fun speakReplies(ctx: Context): Boolean = sp(ctx).getBoolean("speak", true)
    fun setSpeakReplies(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("speak", v).apply()

    /** OpenAI voice for spoken replies (CloudVoice.VOICES). */
    fun voice(ctx: Context): String = sp(ctx).getString("voice", com.agentos.voice.CloudVoice.DEFAULT_VOICE) ?: com.agentos.voice.CloudVoice.DEFAULT_VOICE
    fun setVoice(ctx: Context, v: String) = sp(ctx).edit().putString("voice", v).apply()

    /** Live mode (voice/LiveCall): the realtime model (blank = gpt-realtime-2.1, then -mini), and loudspeaker vs earpiece. */
    fun liveModel(ctx: Context): String = sp(ctx).getString("live_model", "") ?: ""
    fun liveSpeaker(ctx: Context): Boolean = sp(ctx).getBoolean("live_speaker", true)
    fun setLiveSpeaker(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("live_speaker", v).apply()

    /** Call screening (phone/CallScreening): Ubiq answers callers who are not in the contacts first. The owner's rules, and the name Ubiq gives callers. */
    fun screenCalls(ctx: Context): Boolean = sp(ctx).getBoolean("screen_calls", false)
    fun setScreenCalls(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("screen_calls", v).apply()
    fun screenRules(ctx: Context): String = sp(ctx).getString("screen_rules", "") ?: ""
    fun setScreenRules(ctx: Context, v: String) = sp(ctx).edit().putString("screen_rules", v).apply()
    fun ownerName(ctx: Context): String = sp(ctx).getString("owner_name", "") ?: ""
    fun setOwnerName(ctx: Context, v: String) = sp(ctx).edit().putString("owner_name", v.trim()).apply()
    /** Seconds the owner has to decide before Ubiq offers the caller to take a message. */
    fun screenWaitSeconds(ctx: Context): Int = sp(ctx).getInt("screen_wait", 30)
    /** "Hey Ubiq" (voice/WakeWord): on/off, also with the screen off, and sensitivity 0 (strict) … 2 (hears more). */
    fun wakeWord(ctx: Context): Boolean = sp(ctx).getBoolean("wake_word", false)
    fun setWakeWord(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("wake_word", v).apply()
    fun wakeScreenOff(ctx: Context): Boolean = sp(ctx).getBoolean("wake_screen_off", true)
    fun setWakeScreenOff(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("wake_screen_off", v).apply()
    fun wakeSensitivity(ctx: Context): Int = sp(ctx).getInt("wake_sensitivity", 1)
    fun setWakeSensitivity(ctx: Context, v: Int) = sp(ctx).edit().putInt("wake_sensitivity", v).apply()

    fun useScreenshots(ctx: Context): Boolean = sp(ctx).getBoolean("screenshots", true)
    fun setUseScreenshots(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("screenshots", v).apply()

    /** Run phone tasks on the hidden workspace display (needs Shizuku) instead of the visible screen. */
    /** Scripted playbooks (agent/Playbooks.kt; only builds that add some) answer the requests they match instead of the model. */
    fun demoPlaybooks(ctx: Context): Boolean = sp(ctx).getBoolean("demo_playbooks", true)
    fun setDemoPlaybooks(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("demo_playbooks", v).apply()

    /** Personal context (docs/MEMORY.md): record screens, notifications and places on the phone for the agent. Off until the owner turns it on. */
    fun personalContext(ctx: Context): Boolean = sp(ctx).getBoolean("personal_context", false)
    fun setPersonalContext(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("personal_context", v).apply()
    /** Which sources personal context records (`screens`, `notifications`, `places`), each on by default once it is on. */
    fun recordSource(ctx: Context, source: String): Boolean = sp(ctx).getBoolean("record_$source", true)
    fun setRecordSource(ctx: Context, source: String, v: Boolean) = sp(ctx).edit().putBoolean("record_$source", v).apply()
    /** Whether `recall` may search the timeline (conversations are always searchable). */
    fun timelineSearchable(ctx: Context): Boolean = sp(ctx).getBoolean("timeline_searchable", true)
    fun setTimelineSearchable(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("timeline_searchable", v).apply()
    /** Days the raw timeline is kept. */
    fun timelineDays(ctx: Context): Int = sp(ctx).getInt("timeline_days", 90)
    fun setTimelineDays(ctx: Context, v: Int) = sp(ctx).edit().putInt("timeline_days", v).apply()
    /** Apps the owner keeps out of the timeline; null until they change the default list. */
    fun timelineExcluded(ctx: Context): Set<String>? = sp(ctx).getStringSet("timeline_excluded", null)
    fun setTimelineExcluded(ctx: Context, v: Set<String>) = sp(ctx).edit().putStringSet("timeline_excluded", v).apply()

    /** Reply suggestions over the keyboard in messaging, email and social apps (control/Autocomplete). */
    fun autocomplete(ctx: Context): Boolean = sp(ctx).getBoolean("autocomplete", true)
    fun setAutocomplete(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("autocomplete", v).apply()

    /** Focus mode while it is on (notify/Focus), as JSON; null when off. */
    fun focusState(ctx: Context): String? = sp(ctx).getString("focus_state", null)
    fun setFocusState(ctx: Context, v: String?) = sp(ctx).edit().apply { if (v == null) remove("focus_state") else putString("focus_state", v) }.apply()

    fun backgroundMode(ctx: Context): Boolean = sp(ctx).getBoolean("background", true)
    fun setBackgroundMode(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("background", v).apply()

    fun maxSteps(ctx: Context): Int = sp(ctx).getInt("max_steps", 40)
    fun setMaxSteps(ctx: Context, v: Int) = sp(ctx).edit().putInt("max_steps", v).apply()

    /** When to use an on-device model, if the build has one ([com.agentos.agent.OnDeviceModel]): "auto" (cloud first, on-device when offline or the API fails), "always", "never". */
    fun localMode(ctx: Context): String = sp(ctx).getString("local_mode", "auto") ?: "auto"
    fun setLocalMode(ctx: Context, v: String) = sp(ctx).edit().putString("local_mode", v).apply()

    /** Apps pinned to the home screen (package names, in order). Null until the user changes them. */
    fun homeApps(ctx: Context): List<String>? = sp(ctx).getString("home_apps", null)?.split(',')?.filter { it.isNotBlank() }
    fun setHomeApps(ctx: Context, v: List<String>) = sp(ctx).edit().putString("home_apps", v.joinToString(",")).apply()

    /**
     * Which harness runs the agent's turns: "pi" (default; the Pi harness in
     * harness/, on this phone or at [harnessUrl]; it calls the phone's tools back through ToolHost) or "builtin"
     * (the Kotlin loop in Session). See docs/AGENT_DESIGN.md.
     */
    fun engine(ctx: Context): String = sp(ctx).getString("engine", "pi") ?: "pi"
    fun setEngine(ctx: Context, v: String) = sp(ctx).edit().putString("engine", v).apply()

    /**
     * A Pi harness somewhere else, for development: blank (the default) runs Pi on this phone (agent/PiRuntime).
     * scripts/pi-dev.sh sets http://127.0.0.1:8788, which `adb reverse` carries to the Mac.
     */
    fun harnessUrl(ctx: Context): String = sp(ctx).getString("harness_url", "") ?: ""
    fun setHarnessUrl(ctx: Context, v: String) = sp(ctx).edit().putString("harness_url", v.trim()).apply()

    /**
     * The shared secret between the phone and the harness, made on first use. Both directions check it: other apps on
     * the phone can reach 127.0.0.1 too. Also written to files/harness_token so scripts/pi-dev.sh can read it with run-as.
     */
    fun harnessToken(ctx: Context): String {
        sp(ctx).getString("harness_token", null)?.let { return it }
        val bytes = ByteArray(24).also { java.security.SecureRandom().nextBytes(it) }
        val t = bytes.joinToString("") { "%02x".format(it) }
        sp(ctx).edit().putString("harness_token", t).apply()
        try { java.io.File(ctx.filesDir, "harness_token").writeText(t) } catch (_: Throwable) {}
        return t
    }

    fun isConfigured(ctx: Context) = apiKey(ctx).startsWith("sk-")
}
