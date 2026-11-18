package com.agentos.agent

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.PowerManager

/**
 * The phone's energy, network, heat and memory, and the harness's policy on them (docs/AGENT_DESIGN.md, section 8).
 * Policy lives here, in code; the model gets at most one line ([note]) and only when something is unusual.
 *
 * - An on-device model (when a build adds one) rests when the phone is hot, or low and unplugged ([localInferenceOk]).
 * - Deferrable work (digests) waits when the battery is low or saver is on ([deferrable]).
 * - Tasks run one at a time while memory is short ([lowMemory], see [TaskTarget.resolve]).
 */
object DeviceConditions {
    class Now(
        val battery: Int, val charging: Boolean, val powerSave: Boolean,
        /** PowerManager.THERMAL_STATUS_*: 0 none ... 3 severe, 4 critical. */
        val thermal: Int,
        val network: String, val metered: Boolean, val validated: Boolean, val downKbps: Int,
        val lowMemory: Boolean, val availMb: Long,
    ) {
        val lowBattery get() = battery in 0..15 && !charging
        val hot get() = thermal >= PowerManager.THERMAL_STATUS_SEVERE
        val online get() = network != "none" && validated
        /** A slow or flaky link: keep requests small, prefer the screen as text to screenshots. */
        val weakLink get() = online && downKbps in 1..1500
    }

    fun read(ctx: Context): Now {
        val bat = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = bat?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = bat?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val plugged = (bat?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
        val pm = ctx.getSystemService(PowerManager::class.java)
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val caps = try { cm.activeNetwork?.let { cm.getNetworkCapabilities(it) } } catch (_: Throwable) { null }
        val net = when {
            caps == null -> "none"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            else -> "other"
        }
        val mem = ActivityManager.MemoryInfo().also { ctx.getSystemService(ActivityManager::class.java).getMemoryInfo(it) }
        return Now(
            battery = if (level >= 0 && scale > 0) level * 100 / scale else -1, charging = plugged, powerSave = pm.isPowerSaveMode,
            thermal = pm.currentThermalStatus,
            network = net, metered = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == false,
            validated = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true,
            downKbps = caps?.linkDownstreamBandwidthKbps ?: 0,
            lowMemory = mem.lowMemory, availMb = mem.availMem / (1024 * 1024),
        )
    }

    fun localInferenceOk(ctx: Context): Boolean = read(ctx).let { !it.hot && !it.lowBattery }
    fun deferrable(ctx: Context): Boolean = read(ctx).let { it.lowBattery || it.powerSave }
    fun lowMemory(ctx: Context): Boolean = read(ctx).lowMemory

    /** One line for the model when something should change its plans ("" when all is normal, so the prompt stays cached). */
    fun note(ctx: Context): String {
        val n = try { read(ctx) } catch (_: Throwable) { return "" }
        val parts = buildList {
            if (n.lowBattery) add("battery ${n.battery}%, not charging")
            else if (n.powerSave) add("battery saver on")
            if (n.hot) add("the phone is hot")
            if (!n.online) add("no internet")
            else if (n.weakLink) add("slow connection")
            else if (n.metered && n.network == "cellular") add("on mobile data")
            if (n.lowMemory) add("low on memory")
        }
        return if (parts.isEmpty()) "" else "(Phone: ${parts.joinToString("; ")}. Prefer short, light ways to do this.)\n"
    }

    /** For Settings: the conditions in plain words. */
    fun summary(ctx: Context): String {
        val n = read(ctx)
        val net = when (n.network) { "wifi" -> "Wi-Fi"; "cellular" -> if (n.metered) "mobile data" else "cellular"; "none" -> "offline"; else -> n.network }
        val heat = when { n.hot -> "hot"; n.thermal >= PowerManager.THERMAL_STATUS_MODERATE -> "warm"; else -> "normal temperature" }
        return "Battery ${n.battery}%${if (n.charging) " (charging)" else ""} · $net · $heat · ${"%.1f".format(n.availMb / 1024.0)} GB memory free"
    }
}
