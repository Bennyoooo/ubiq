package com.agentos.agent

import android.content.Context
import androidx.compose.runtime.Composable
import org.json.JSONArray
import java.util.ServiceLoader

/**
 * A model that runs on the phone itself, used when the phone is offline, the cloud fails, or the
 * owner asks for it. This app is cloud only: it ships no on-device model. A build can add one by
 * registering an implementation in `META-INF/services/com.agentos.agent.OnDeviceModel`; when none is
 * registered, [installed] is null and every request goes to the cloud model.
 */
interface OnDeviceModel {
    /** Last known readiness of the text model, for callers that cannot suspend. */
    val textReady: Boolean
    suspend fun isReady(ctx: Context): Boolean

    /** Same contract as [OpenAi.respond]. Response ids start with `local:` so a chain's engine is known. */
    suspend fun respond(ctx: Context, instructions: String, input: JSONArray, tools: JSONArray?, previousResponseId: String?, maxOutputTokens: Int): OpenAi.Result

    /** One-shot answer, optionally about an image (mini apps' `ask`, notification digests). */
    suspend fun ask(ctx: Context, system: String, prompt: String, imageDataUrl: String?): String

    /** Frees the model's memory (memory pressure). */
    suspend fun unloadAll()

    /** The system prompt for a turn on this model (its context is usually far smaller than the cloud model's). */
    fun instructions(installedApps: String, miniApps: String, controlAvailable: Boolean, background: Boolean): String

    /** The tools this model gets, by name. */
    val toolNames: Set<String>

    /** How many screen elements to list for this model. */
    val maxNodes: Int

    /** Its section in Settings: downloads, and when to use it. */
    @Composable fun Settings()

    /** Debug builds: `adb shell am broadcast -a com.agentos.DEBUG_LOCAL --es action ...`. */
    suspend fun debug(ctx: Context, action: String, text: String?): String = "no debug actions"

    companion object {
        /** The registered on-device model, or null (cloud only). */
        val installed: OnDeviceModel? by lazy {
            try { ServiceLoader.load(OnDeviceModel::class.java, OnDeviceModel::class.java.classLoader).firstOrNull() }
            catch (t: Throwable) { android.util.Log.w("AgentOS/OnDevice", "load", t); null }
        }
    }
}
