package com.agentos.memory

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.location.LocationRequest
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Places for the timeline (docs/MEMORY.md): one balanced-power fix every [EVERY_MS], plus the fixes other apps get
 * anyway (passive). Staying [STAY_MS] or more within [RADIUS_M] is a visit, stored once with its name (reverse
 * geocoding) and extended while the stay goes on. Raw fixes are not kept.
 */
object Places {
    private const val TAG = "AgentOS/Places"
    private const val EVERY_MS = 10 * 60_000L
    private const val STAY_MS = 10 * 60_000L
    private const val RADIUS_M = 200f

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var app: Context
    /** The stay under way: where, since when, last seen, and its event once it counts as a visit. */
    private class Stay(val loc: Location, val since: Long) { var last = since; var event: Long? = null; var name = "" }
    private var stay: Stay? = null
    /** Where the owner is now, for the agent's "moment" (docs/MEMORY.md 3.3). */
    @Volatile var now: String? = null
        private set

    /** [now] for the prompt, only while places are recorded and searchable (Settings). */
    fun shared(): String? = now?.takeIf { ::app.isInitialized && Lifelog.enabled(app, Lifelog.Kind.PLACE) && com.agentos.data.Prefs.timelineSearchable(app) }

    /** Delete all: the place now goes too. */
    fun forget() { now = null; synchronized(this) { stay = null } }

    fun allowed(ctx: Context) = ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        ctx.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun start(ctx: Context) {
        if (::app.isInitialized) return
        app = ctx.applicationContext
        val lm = app.getSystemService(LocationManager::class.java) ?: return
        try {
            if (allowed(app)) lm.requestLocationUpdates(LocationManager.PASSIVE_PROVIDER, 60_000L, 50f, LocationListener { on(it) }, Looper.getMainLooper())
        } catch (t: Throwable) { Log.w(TAG, "passive", t) }
        scope.launch {
            while (true) {
                if (Lifelog.enabled(app, Lifelog.Kind.PLACE) && allowed(app)) try { fix(lm)?.let { on(it) } } catch (t: Throwable) { Log.w(TAG, "fix", t) }
                delay(EVERY_MS)
            }
        }
    }

    /** A balanced fix; when there is none (no Wi-Fi or cell location), a recent last-known one, else one GPS fix. */
    private suspend fun fix(lm: LocationManager): Location? {
        val enabled = lm.getProviders(true)
        val provider = listOf(LocationManager.FUSED_PROVIDER, LocationManager.NETWORK_PROVIDER).firstOrNull { it in enabled }
        provider?.let { current(lm, it, LocationRequest.QUALITY_BALANCED_POWER_ACCURACY) }?.let { return it }
        enabled.mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull { it.time }
            ?.takeIf { System.currentTimeMillis() - it.time < EVERY_MS }?.let { return it }
        return if (LocationManager.GPS_PROVIDER in enabled) current(lm, LocationManager.GPS_PROVIDER, LocationRequest.QUALITY_HIGH_ACCURACY) else null
    }

    private suspend fun current(lm: LocationManager, provider: String, quality: Int): Location? {
        val req = LocationRequest.Builder(0).setQuality(quality).setDurationMillis(30_000).build()
        return withTimeoutOrNull(30_000) {
            suspendCancellableCoroutine { c ->
                val cancel = android.os.CancellationSignal()
                c.invokeOnCancellation { cancel.cancel() }
                lm.getCurrentLocation(provider, req, cancel, app.mainExecutor) { c.resume(it) }
            }
        }
    }

    @Synchronized private fun on(loc: Location) {
        if (!Lifelog.enabled(app, Lifelog.Kind.PLACE) || loc.accuracy > 500) return
        val t = maxOf(loc.time, System.currentTimeMillis() - 60_000)
        val s = stay
        if (s != null && s.loc.distanceTo(loc) <= RADIUS_M + loc.accuracy / 2) {
            s.last = t
            val id = s.event
            if (id != null) Lifelog.extend(id, t)
            else if (s.last - s.since >= STAY_MS) visit(s)
            return
        }
        stay = Stay(loc, t)
    }

    private fun visit(s: Stay) {
        val id = Lifelog.add(Lifelog.Kind.PLACE, "", "Stayed near %.4f, %.4f".format(java.util.Locale.US, s.loc.latitude, s.loc.longitude), s.since, s.loc.latitude, s.loc.longitude) ?: return
        Lifelog.extend(id, s.last)
        s.event = id
        scope.launch {
            val name = label(s.loc) ?: return@launch
            s.name = name
            now = name
            Lifelog.rename(id, "At $name")
        }
    }

    @Suppress("DEPRECATION")
    private fun label(loc: Location): String? = try {
        android.location.Geocoder(app).getFromLocation(loc.latitude, loc.longitude, 1)?.firstOrNull()?.let { a ->
            listOfNotNull(a.featureName?.takeIf { f -> f != a.subThoroughfare }, a.getAddressLine(0)).distinct().joinToString(", ").ifBlank { null }
        }
    } catch (t: Throwable) { Log.w(TAG, "geocode", t); null }
}
