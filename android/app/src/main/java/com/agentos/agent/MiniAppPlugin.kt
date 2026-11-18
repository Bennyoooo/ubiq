package com.agentos.agent

import android.content.Context
import androidx.compose.runtime.Composable
import org.json.JSONObject
import java.util.ServiceLoader

/**
 * Small screens the agent builds for the user. This app ships none: a build can add them by
 * registering an implementation in `META-INF/services/com.agentos.agent.MiniAppPlugin`. When none
 * is registered, [installed] is null and the agent has no mini-app tools, prompt or screens.
 */
interface MiniAppPlugin {
    fun init(ctx: Context)

    /** Its tools, added to the model's tool list. */
    fun definitions(): List<JSONObject>

    /** Runs one of its tools, or returns null when [name] is not one of them. */
    suspend fun execute(ctx: Context, name: String, input: JSONObject, target: TaskTarget): ToolOutcome?

    /** The mini apps the user has, one per line (for the prompt). */
    fun describe(): String

    /** Its section of the system prompt, given [describe]'s list. */
    fun prompt(miniApps: String): String

    /** An example request for the empty chat. */
    val suggestion: String

    /** A job that belongs to mini app [appId] produced [text]. */
    fun recordResult(appId: String, text: String)

    /** The list of the user's mini apps. */
    @Composable fun Screen(onBack: () -> Unit, onAskAssistant: () -> Unit)

    /** Debug builds: `adb shell am broadcast -a com.agentos.DEBUG_MINIAPP ...` with these extras. */
    fun debug(ctx: Context, extras: android.os.Bundle) {}

    companion object {
        /** The registered mini apps, or null (none in this build). */
        val installed: MiniAppPlugin? by lazy {
            try { ServiceLoader.load(MiniAppPlugin::class.java, MiniAppPlugin::class.java.classLoader).firstOrNull() }
            catch (t: Throwable) { android.util.Log.w("AgentOS/MiniApps", "load", t); null }
        }
    }
}
