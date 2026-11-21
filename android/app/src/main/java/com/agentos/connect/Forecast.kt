package com.agentos.connect

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.resume

/**
 * The `weather` tool's forecast (agent/QuickActions, agent/Express): now, the next hours and up to 7 days at a named
 * place (geocoded) or the phone's location. Open-Meteo first (free, no key, rain as a % chance); when it is slow or
 * down, MET Norway's locationforecast (free, no key, rain as an amount). A reading is kept 15 minutes per place.
 */
object Forecast {
    private const val TAG = "AgentOS/Forecast"
    private const val CACHE_MS = 15 * 60_000L
    private val cache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, Report>>()

    fun allowed(ctx: Context) = ctx.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** [rain] is a % chance and [mm] an amount; a source gives one of them, the other is -1. */
    class Hour(val time: String, val temp: Int, val words: String, val rain: Int, val mm: Double = -1.0)
    class Day(val name: String, val words: String, val high: Int, val low: Int, val rain: Int, val mm: Double = -1.0)
    class Report(val where: String?, val temp: Int, val feels: Int, val words: String, val wind: Int, val unit: String, val windUnit: String, val hours: List<Hour>, val days: List<Day>) {
        private val inches get() = unit == "°F"
        private fun amount(mm: Double) = if (inches) "%.1f in".format(java.util.Locale.US, mm / 25.4).replace("0.0 in", "under 0.1 in") else "${Math.round(mm)} mm"
        private fun wet(h: Hour) = h.rain >= 50 || h.mm >= 0.5

        /** The rain part of a day's line: a chance or an amount, said when it matters or when [asked]. */
        private fun rain(d: Day, asked: Boolean, threshold: Int = 30): String = when {
            d.rain >= 0 -> if (d.rain >= threshold || asked) ", ${if (d.rain == 0) "no" else "${d.rain}%"} chance of rain" else ""
            d.mm >= 0 -> when { d.mm >= 1.0 -> ", about ${amount(d.mm)} of rain"; asked -> if (d.mm > 0.1) ", a little rain" else ", no rain expected"; else -> "" }
            else -> ""
        }

        /** Everything, for the model. */
        fun text() = buildString {
            append(where?.let { "$it now: " } ?: "Now: ").append("$temp$unit, $words (" + (if (feels != temp) "feels like $feels$unit, " else "") + "wind $wind $windUnit)\n")
            append("Next hours: ").append(hours.joinToString("; ") { "${it.time} ${it.temp}$unit ${it.words}" + when {
                it.rain >= 20 -> " (${it.rain}% rain)"; it.mm >= 0.2 -> " (${amount(it.mm)} rain)"; else -> "" } }).append('\n')
            days.forEach { d -> append("${d.name}: ${d.words}, ${d.high}$unit / ${d.low}$unit").append(rain(d, false, 20)).append('\n') }
        }.trim()

        /** One or two sentences to say: [day] 0 is today (with now), 1 tomorrow, -1 the week. */
        fun spoken(day: Int, rainAsked: Boolean = false): String {
            val at = where?.let { " in $it" } ?: ""
            return when {
                day < 0 -> "This week$at: " + days.joinToString("; ") { "${it.name.take(3)} ${it.words}, ${it.high}°/${it.low}°" } + "."
                day == 0 || days.size <= day -> {
                    val d = days.firstOrNull()
                    "It's $temp$unit and $words$at" + (if (kotlin.math.abs(feels - temp) >= 4) ", feels like $feels$unit" else "") + "." +
                        (d?.let { " Today: high ${it.high}°, low ${it.low}°${rain(it, rainAsked)}." } ?: "") +
                        (hours.firstOrNull { wet(it) }?.takeIf { d == null || (d.rain in 0..49) || (d.rain < 0 && d.mm < 1.0) }?.let { " Rain likely around ${it.time}." } ?: "")
                }
                else -> days[day].let { "${it.name}$at: ${it.words}, high ${it.high}$unit, low ${it.low}$unit${rain(it, rainAsked)}." }
            }
        }
    }

    /** A forecast at [place] (geocoded) or the phone's location, [days] days ahead. Throws with a reason the model can read. */
    suspend fun get(ctx: Context, place: String?, days: Int): Report = withContext(Dispatchers.IO) {
        val (lat, lon, where) = if (!place.isNullOrBlank()) {
            @Suppress("DEPRECATION")
            val a = runCatching { android.location.Geocoder(ctx).getFromLocationName(place, 1)?.firstOrNull() }.getOrNull()
                ?: throw IllegalArgumentException("Couldn't find a place called \"$place\".")
            Triple(a.latitude, a.longitude, a.locality ?: a.featureName ?: place)
        } else {
            if (!allowed(ctx)) throw IllegalStateException("Location permission is off, so I don't know where you are. Name a city, or allow location for Ubiq.")
            val loc = location(ctx) ?: throw IllegalStateException("The phone couldn't get its location right now. Name a city instead.")
            Triple(loc.latitude, loc.longitude, null)
        }
        val n = days.coerceIn(1, 7)
        val f = java.util.Locale.getDefault().country in setOf("US", "LR", "MM")
        val key = "%.2f,%.2f,%d,%b".format(java.util.Locale.US, lat, lon, n, f)
        cache[key]?.let { (at, r) -> if (System.currentTimeMillis() - at < CACHE_MS) return@withContext r }
        val r = try { openMeteo(lat, lon, n, f, where) } catch (t: Throwable) {
            android.util.Log.w(TAG, "open-meteo failed (${t.message}), trying MET Norway")
            try { metNorway(lat, lon, n, f, where) } catch (t2: Throwable) {
                throw java.io.IOException("both weather services failed (Open-Meteo: ${t.message}; MET Norway: ${t2.message})")
            }
        }
        cache[key] = System.currentTimeMillis() to r
        r
    }

    private fun fetch(url: String, timeoutMs: Int): JSONObject {
        val con = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = timeoutMs; readTimeout = timeoutMs
            // MET Norway refuses requests without an identifying User-Agent.
            setRequestProperty("User-Agent", "UbiqAssistant/0.1 (Android)")
        }
        try {
            if (con.responseCode !in 200..299) throw java.io.IOException("HTTP ${con.responseCode}")
            return JSONObject(con.inputStream.bufferedReader().readText())
        } finally { con.disconnect() }
    }

    private fun r(d: Double) = Math.round(d).toInt()
    private fun dayName(i: Int, date: java.time.LocalDate?, raw: String) =
        when (i) { 0 -> "Today"; 1 -> "Tomorrow"; else -> date?.dayOfWeek?.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.US) ?: raw }

    private fun openMeteo(lat: Double, lon: Double, n: Int, f: Boolean, where: String?): Report {
        val url = "https://api.open-meteo.com/v1/forecast?latitude=%.3f&longitude=%.3f".format(java.util.Locale.US, lat, lon) +
            "&current=temperature_2m,apparent_temperature,weather_code,wind_speed_10m" +
            "&hourly=temperature_2m,precipitation_probability,weather_code&forecast_hours=12" +
            "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max&forecast_days=$n&timezone=auto" +
            (if (f) "&temperature_unit=fahrenheit&wind_speed_unit=mph" else "")
        val o = fetch(url, 3000)
        val cur = o.getJSONObject("current")
        val h = o.getJSONObject("hourly")
        val ht = h.getJSONArray("time"); val hp = h.getJSONArray("precipitation_probability"); val htemp = h.getJSONArray("temperature_2m"); val hc = h.getJSONArray("weather_code")
        val hours = (0 until ht.length() step 3).map { i -> Hour(ht.getString(i).substringAfter('T'), r(htemp.getDouble(i)), words(hc.getInt(i)), hp.optInt(i, 0)) }
        val d = o.getJSONObject("daily")
        val dt = d.getJSONArray("time")
        val list = (0 until dt.length()).map { i ->
            Day(dayName(i, runCatching { java.time.LocalDate.parse(dt.getString(i)) }.getOrNull(), dt.getString(i)),
                words(d.getJSONArray("weather_code").getInt(i)), r(d.getJSONArray("temperature_2m_max").getDouble(i)), r(d.getJSONArray("temperature_2m_min").getDouble(i)),
                d.getJSONArray("precipitation_probability_max").optInt(i, 0))
        }
        return Report(where, r(cur.getDouble("temperature_2m")), r(cur.getDouble("apparent_temperature")), words(cur.getInt("weather_code")), r(cur.getDouble("wind_speed_10m")),
            if (f) "°F" else "°C", if (f) "mph" else "km/h", hours, list)
    }

    /**
     * MET Norway's compact forecast: UTC entries, hourly for about two days and 6-hourly after, each with the coming
     * 1 / 6 / 12 hours' symbol and rain amount. Times are shown in the phone's time zone.
     */
    private fun metNorway(lat: Double, lon: Double, n: Int, f: Boolean, where: String?): Report =
        fromMet(fetch("https://api.met.no/weatherapi/locationforecast/2.0/compact?lat=%.3f&lon=%.3f".format(java.util.Locale.US, lat, lon), 6000),
            n, f, where, java.time.ZoneId.systemDefault(), java.time.Instant.now())

    internal fun fromMet(o: JSONObject, n: Int, f: Boolean, where: String?, zone: java.time.ZoneId, nowAt: java.time.Instant): Report {
        val ts = o.getJSONObject("properties").getJSONArray("timeseries")
        class E(val at: java.time.ZonedDateTime, val c: Double, val wind: Double, val sym1: String?, val mm1: Double?, val sym6: String?, val mm6: Double?)
        val es = (0 until ts.length()).map { i ->
            val e = ts.getJSONObject(i); val data = e.getJSONObject("data")
            val det = data.getJSONObject("instant").getJSONObject("details")
            fun next(k: String) = data.optJSONObject(k)
            E(java.time.Instant.parse(e.getString("time")).atZone(zone), det.getDouble("air_temperature"), det.optDouble("wind_speed", 0.0),
                next("next_1_hours")?.optJSONObject("summary")?.optString("symbol_code"), next("next_1_hours")?.optJSONObject("details")?.optDouble("precipitation_amount"),
                (next("next_6_hours") ?: next("next_12_hours"))?.optJSONObject("summary")?.optString("symbol_code"), next("next_6_hours")?.optJSONObject("details")?.optDouble("precipitation_amount"))
        }.filter { it.at.isAfter(nowAt.atZone(zone).minusHours(1)) }
        if (es.isEmpty()) throw java.io.IOException("no forecast entries")
        fun t(c: Double) = r(if (f) c * 9 / 5 + 32 else c)
        val now = es.first()
        val hours = es.take(12).filterIndexed { i, _ -> i % 3 == 0 }.map { e ->
            Hour("%02d:00".format(e.at.hour), t(e.c), metWords(e.sym1 ?: e.sym6), -1, e.mm1 ?: -1.0)
        }
        val today = nowAt.atZone(zone).toLocalDate()
        val list = (0 until n).mapNotNull { i ->
            val date = today.plusDays(i.toLong())
            val day = es.filter { it.at.toLocalDate() == date }.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val noon = day.minByOrNull { kotlin.math.abs(it.at.hour - 12) }!!
            val mm = day.sumOf { it.mm1 ?: if (it.at.hour % 6 == 0) (it.mm6 ?: 0.0) else 0.0 }
            Day(dayName(i, date, date.toString()), metWords(noon.sym6 ?: noon.sym1), t(day.maxOf { it.c }), t(day.minOf { it.c }), -1, mm)
        }
        return Report(where, t(now.c), t(now.c), metWords(now.sym1 ?: now.sym6), r(now.wind * if (f) 2.237 else 3.6),
            if (f) "°F" else "°C", if (f) "mph" else "km/h", hours, list)
    }

    /** MET Norway symbol codes ("lightrainshowers_day") in the same words as [words]. */
    fun metWords(code: String?): String {
        val c = code.orEmpty().substringBefore('_')
        return when {
            c.isEmpty() -> "unknown"
            c == "clearsky" -> "clear"; c == "fair" -> "mostly clear"; c == "partlycloudy" -> "partly cloudy"; c == "cloudy" -> "cloudy"; c == "fog" -> "fog"
            "thunder" in c -> "storms"; "sleet" in c -> "sleet"; "snowshowers" in c -> "snow showers"; "snow" in c -> "snow"
            "rainshowers" in c -> "showers"; "rain" in c -> "rain"
            else -> c
        }
    }

    private suspend fun location(ctx: Context): Location? {
        val lm = ctx.getSystemService(LocationManager::class.java) ?: return null
        try {
            val last = lm.getProviders(true).mapNotNull { lm.getLastKnownLocation(it) }.maxByOrNull { it.time }
            if (last != null && System.currentTimeMillis() - last.time < 6 * 3600_000L) return last
            if (Build.VERSION.SDK_INT >= 30) {
                val provider = listOf(LocationManager.FUSED_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
                    .firstOrNull { it in lm.getProviders(true) }
                if (provider != null) withTimeoutOrNull(10_000) {
                    suspendCancellableCoroutine<Location?> { c ->
                        val cancel = android.os.CancellationSignal()
                        c.invokeOnCancellation { cancel.cancel() }
                        lm.getCurrentLocation(provider, cancel, ctx.mainExecutor) { c.resume(it) }
                    }
                }?.let { return it }
            }
            return last
        } catch (t: SecurityException) { return null }
    }

    /** WMO weather codes, in lowercase like the rest of the system labels. */
    fun words(code: Int) = when (code) {
        0 -> "clear"; 1 -> "mostly clear"; 2 -> "partly cloudy"; 3 -> "cloudy"
        45, 48 -> "fog"
        51, 53, 55, 56, 57 -> "drizzle"
        61, 63, 65, 66, 67 -> "rain"
        71, 73, 75, 77 -> "snow"
        80, 81, 82 -> "showers"
        85, 86 -> "snow showers"
        95, 96, 99 -> "storms"
        else -> "unknown"
    }
}
