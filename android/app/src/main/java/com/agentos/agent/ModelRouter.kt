package com.agentos.agent

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.agentos.data.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.IOException

/**
 * Decides whether a request goes to the cloud model or to an on-device one, and makes the call.
 * Without an [OnDeviceModel] registered (this app ships none) every request goes to the cloud.
 */
object ModelRouter {
    enum class Engine { CLOUD, LOCAL }
    class Choice(val engine: Engine, val reason: String?)

    fun online(ctx: Context): Boolean = try {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }
        caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    } catch (_: Throwable) { true }

    /** Which engine a new turn should use. */
    suspend fun choose(ctx: Context): Choice {
        val local = OnDeviceModel.installed ?: return Choice(Engine.CLOUD, null)
        return choose(ctx, local)
    }

    private suspend fun choose(ctx: Context, local: OnDeviceModel): Choice = when (Prefs.localMode(ctx)) {
        "never" -> Choice(Engine.CLOUD, null)
        "always" -> when {
            !local.isReady(ctx) -> Choice(Engine.CLOUD, "the on-device model is not downloaded")
            // A hot or nearly empty phone runs the cloud model when it can (DeviceConditions); offline it has no choice.
            !DeviceConditions.localInferenceOk(ctx) && online(ctx) && Prefs.apiKey(ctx).isNotBlank() -> Choice(Engine.CLOUD, "the phone is hot or low on battery")
            else -> Choice(Engine.LOCAL, "on-device model selected in Settings")
        }
        else -> when {
            !local.isReady(ctx) -> Choice(Engine.CLOUD, null)
            !online(ctx) -> Choice(Engine.LOCAL, "no internet connection")
            Prefs.apiKey(ctx).isBlank() -> Choice(Engine.LOCAL, "no OpenAI API key")
            else -> Choice(Engine.CLOUD, null)
        }
    }

    /** True when a failed cloud call should be retried on the on-device model. */
    fun fallbackFor(ctx: Context, e: Throwable): Boolean {
        if (Prefs.localMode(ctx) != "auto" || OnDeviceModel.installed?.textReady != true) return false
        return when (e) {
            is OpenAi.ApiException -> e.status == 0 || e.status == 429 || e.status >= 500
            is IOException -> true
            else -> false
        }
    }

    suspend fun respond(ctx: Context, engine: Engine, model: String, instructions: String, input: JSONArray, tools: JSONArray?, previousResponseId: String?, effort: String?, maxOutputTokens: Int, store: Boolean): OpenAi.Result =
        if (engine == Engine.LOCAL) OnDeviceModel.installed!!.respond(ctx, instructions, input, tools, previousResponseId, maxOutputTokens)
        else withContext(Dispatchers.IO) { OpenAi.respond(ctx, model, instructions, input, tools, previousResponseId, effort, maxOutputTokens, store) }

    /** One-shot answer (mini apps' `ask`): cloud first, on-device when that is the policy or the cloud fails. */
    suspend fun ask(ctx: Context, system: String, prompt: String, imageDataUrl: String?, cloud: suspend () -> String): String {
        val local = OnDeviceModel.installed ?: return cloud()
        val mode = Prefs.localMode(ctx)
        if (mode == "always" && local.isReady(ctx)) return local.ask(ctx, system, prompt, imageDataUrl)
        if (mode == "auto" && local.isReady(ctx) && (!online(ctx) || Prefs.apiKey(ctx).isBlank())) return local.ask(ctx, system, prompt, imageDataUrl)
        return try { cloud() } catch (e: Exception) {
            if (fallbackFor(ctx, e)) local.ask(ctx, system, prompt, imageDataUrl) else throw e
        }
    }
}
