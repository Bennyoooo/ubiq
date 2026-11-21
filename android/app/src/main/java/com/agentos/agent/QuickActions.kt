package com.agentos.agent

import android.app.ActivityOptions
import android.app.SearchManager
import android.app.usage.UsageStatsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.view.Display
import com.agentos.control.ControlService
import com.agentos.notify.NotificationWatcher
import kotlinx.coroutines.delay
import org.json.JSONObject

/**
 * One-step tools for what people expect to be instant: the weather, directions, and playing something.
 * Each does the whole job in one call, without operating screens, so a request like "play Blinding Lights"
 * is one tool call (or, through the [Express] lane, no main-model call at all).
 *
 * Directions and music are for the user, not for a background desk: they start on the main screen
 * (or in the background, for music the app is already playing) and the turn ends without covering them.
 */
object QuickActions {
    private const val TAG = "AgentOS/Quick"
    val names = setOf("weather", "directions", "play_media")

    fun definitions(): List<org.json.JSONObject> = listOf(
        AgentTools.tool("weather", "The weather right now, the next hours and the coming days, here or in a named place. Answers at once without opening an app.",
            """{"properties":{"place":{"type":"string","description":"A city or place; omit for where the phone is"},"days":{"type":"integer","description":"1 to 7 days of forecast, default 3"}},"required":[]}"""),
        AgentTools.tool("directions", "Start turn-by-turn directions on the user's screen in their maps app (Google Maps or Waze), in one step. " +
            "Use for 'take me to', 'directions to', 'navigate to', 'how do I get to'. Give a full address when you know it (from memory or contacts); place names work too.",
            """{"properties":{"destination":{"type":"string"},"mode":{"type":"string","enum":["drive","walk","bike","transit"],"description":"Default drive"},"app":{"type":"string","description":"Only when the user named an app"}},"required":["destination"]}"""),
        AgentTools.tool("play_media", "Play a song, artist, album, playlist or podcast in the user's music app (Spotify, YouTube Music, ...) in one step, and report what is playing. " +
            "Empty query resumes what was playing.",
            """{"properties":{"query":{"type":"string","description":"What to play, e.g. 'Blinding Lights' or 'lo-fi beats'"},"artist":{"type":"string","description":"The artist, when the user named one"},"kind":{"type":"string","enum":["song","artist","album","playlist","podcast","any"],"description":"Default any"},"app":{"type":"string","description":"Only when the user named an app"}},"required":["query"]}"""),
    )

    suspend fun execute(ctx: Context, name: String, input: JSONObject, apps: InstalledApps, target: TaskTarget): ToolOutcome? = when (name) {
        "weather" -> try {
            ToolOutcome(com.agentos.connect.Forecast.get(ctx, input.optString("place").trim().ifEmpty { null }, input.optInt("days", 3)).text())
        } catch (t: Throwable) { ToolOutcome.error("Weather unavailable: ${t.message}") }
        "directions" -> directions(ctx, input, apps, target)
        "play_media" -> play(ctx, input, apps, target)
        else -> null
    }

    // ----- directions -----

    private const val MAPS = "com.google.android.apps.maps"
    private const val WAZE = "com.waze"

    suspend fun directions(ctx: Context, input: JSONObject, apps: InstalledApps, target: TaskTarget): ToolOutcome {
        val dest = input.optString("destination").trim().ifEmpty { return ToolOutcome.error("destination is required") }
        val mode = input.optString("mode").ifBlank { "drive" }
        var app = DefaultApps.pick(ctx, DefaultApps.Kind.MAPS, input.optString("app"), apps).orAsk()
        var note = ""
        // Waze only drives: walking, cycling and transit go to Google Maps when it is there.
        if (app.pkg == WAZE && mode != "drive") {
            val maps = DefaultApps.installed(ctx, DefaultApps.Kind.MAPS).firstOrNull { it.pkg == MAPS }
            if (maps != null) { app = maps; note = " (Waze only does driving, so I used Google Maps)" } else note = " (Waze only does driving directions)"
        }
        val e = Uri.encode(dest)
        val uri = when {
            app.pkg == WAZE -> "https://waze.com/ul?q=$e&navigate=yes"
            mode == "transit" -> "https://www.google.com/maps/dir/?api=1&destination=$e&travelmode=transit"
            app.pkg == MAPS -> "google.navigation:q=$e&mode=" + when (mode) { "walk" -> "w"; "bike" -> "b"; else -> "d" }
            else -> "geo:0,0?q=$e"
        }
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri)).setPackage(app.pkg)
        if (ctx.packageManager.resolveActivity(intent, 0) == null) intent.setPackage(null).data = Uri.parse("geo:0,0?q=$e")
        if (!showToUser(ctx, intent, target)) return ToolOutcome.error("Could not open ${app.label}.")
        DefaultApps.used(ctx, app.pkg)
        val showing = onMainScreen(app.pkg)
        return ToolOutcome("${app.label} is " + (if (showing) "on the user's screen" else "starting") + " with ${modeWords(mode)} directions to \"$dest\"$note. " +
            "It's theirs now: don't act on it. Finish with one short sentence.")
    }

    private fun modeWords(mode: String) = when (mode) { "walk" -> "walking"; "bike" -> "cycling"; "transit" -> "transit"; else -> "driving" }

    // ----- music -----

    suspend fun play(ctx: Context, input: JSONObject, apps: InstalledApps, target: TaskTarget): ToolOutcome {
        val query = input.optString("query").trim()
        val artist = input.optString("artist").trim()
        val kind = input.optString("kind").ifBlank { "any" }
        val app = DefaultApps.pick(ctx, DefaultApps.Kind.MUSIC, input.optString("app"), apps).orAsk()
        val full = listOf(query, artist).filter { it.isNotEmpty() }.joinToString(" ")
        val extras = Bundle().apply {
            putString(SearchManager.QUERY, full)
            putString(MediaStore.EXTRA_MEDIA_FOCUS, focus(kind))
            when (kind) {
                "song" -> { putString(MediaStore.EXTRA_MEDIA_TITLE, query); if (artist.isNotEmpty()) putString(MediaStore.EXTRA_MEDIA_ARTIST, artist) }
                "artist" -> putString(MediaStore.EXTRA_MEDIA_ARTIST, artist.ifEmpty { query })
                "album" -> { putString(MediaStore.EXTRA_MEDIA_ALBUM, query); if (artist.isNotEmpty()) putString(MediaStore.EXTRA_MEDIA_ARTIST, artist) }
                "playlist" -> putString("android.intent.extra.playlist", query)
            }
        }
        val before = controller(ctx, app.pkg)?.metadata?.description?.title?.toString()
        // 1. The app already has a media session: ask it directly, nothing comes on screen.
        var how = "in the background"
        val c = controller(ctx, app.pkg)
        if (c != null) {
            if (full.isEmpty()) c.transportControls.play() else c.transportControls.playFromSearch(full, extras)
        } else {
            // 2. The standard voice-search intent most music apps answer by starting playback.
            val intent = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).setPackage(app.pkg).putExtras(extras)
            if (ctx.packageManager.resolveActivity(intent, 0) != null) {
                if (!showToUser(ctx, intent, target)) return ToolOutcome.error("Could not start ${app.label}.")
                how = "on the user's screen"
            } else {
                // 3. A search deep link: the app opens on its results and someone has to tap play.
                val link = searchLink(app.pkg, full) ?: return ToolOutcome.error("${app.label} can't be asked to play something directly. Open it and search for \"$full\" with the screen tools.")
                if (!showToUser(ctx, Intent(Intent.ACTION_VIEW, Uri.parse(link)).setPackage(app.pkg), target)) return ToolOutcome.error("Could not open ${app.label}.")
                DefaultApps.used(ctx, app.pkg)
                return ToolOutcome("${app.label} is open on the user's screen with search results for \"$full\", but nothing plays yet: tap the top result's play control with the screen tools (the app is on the main screen, display 0).")
            }
        }
        DefaultApps.used(ctx, app.pkg)
        // Evidence: the app's own session says it is playing, and what.
        repeat(16) {
            delay(250)
            val now = controller(ctx, app.pkg) ?: return@repeat
            val st = now.playbackState?.state
            val title = now.metadata?.description?.title?.toString()
            if (st == PlaybackState.STATE_PLAYING && (full.isEmpty() || title != before || it >= 8)) {
                val sub = now.metadata?.description?.subtitle?.toString()?.takeIf { s -> s.isNotBlank() }
                return ToolOutcome("Playing " + (title?.let { t -> "\"$t\"" + (sub?.let { s -> " by $s" } ?: "") } ?: "\"$full\"") + " on ${app.label} ($how). " +
                    "Finish with one short sentence; if it isn't what the user asked for, say what is playing.")
            }
        }
        val screen = if (how != "in the background") ControlService.instance?.snapshot(Display.DEFAULT_DISPLAY, 80)?.render() else null
        return ToolOutcome("Asked ${app.label} to play \"$full\" ($how), but it hasn't confirmed playback yet." +
            (screen?.let { "\nIf a play button is showing, tap it.\n\nUser's screen now:\n$it" } ?: " Check with play_media again or open ${app.label}."))
    }

    private fun focus(kind: String) = when (kind) {
        "song" -> MediaStore.Audio.Media.ENTRY_CONTENT_TYPE
        "artist" -> MediaStore.Audio.Artists.ENTRY_CONTENT_TYPE
        "album" -> MediaStore.Audio.Albums.ENTRY_CONTENT_TYPE
        "playlist" -> "vnd.android.cursor.item/playlist"
        "podcast" -> "vnd.android.cursor.item/podcast"
        else -> "vnd.android.cursor.item/*"
    }

    private fun searchLink(pkg: String, q: String): String? {
        val e = Uri.encode(q)
        return when (pkg) {
            "com.spotify.music" -> "spotify:search:$e"
            "com.google.android.apps.youtube.music" -> "https://music.youtube.com/search?q=$e"
            "com.google.android.youtube" -> "https://www.youtube.com/results?search_query=$e"
            "com.soundcloud.android" -> "soundcloud://search?q=$e"
            else -> null
        }
    }

    /** [pkg]'s media session, when notification access lets us see sessions. */
    private fun controller(ctx: Context, pkg: String): MediaController? = try {
        if (!NotificationWatcher.hasAccess(ctx)) null
        else ctx.getSystemService(MediaSessionManager::class.java)
            .getActiveSessions(ComponentName(ctx, NotificationWatcher::class.java)).firstOrNull { it.packageName == pkg }
    } catch (t: Throwable) { Log.w(TAG, "sessions", t); null }

    // ----- shared -----

    /** Starts [intent] on the user's screen and marks the turn as handed over, so nothing of ours covers it. */
    private suspend fun showToUser(ctx: Context, intent: Intent, target: TaskTarget): Boolean {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pkg = intent.`package` ?: ctx.packageManager.resolveActivity(intent, 0)?.activityInfo?.packageName
        return try {
            ctx.startActivity(intent, ActivityOptions.makeBasic().setLaunchDisplayId(Display.DEFAULT_DISPLAY).toBundle())
            target.handedOver = pkg ?: "?"
            target.effects.acted = true
            target.session?.assistUi?.hideForAction()
            Trace.log(target.session?.id ?: "", "quick", "started ${intent.data ?: intent.action} in ${pkg ?: "?"}")
            true
        } catch (t: Throwable) { Log.w(TAG, "start", t); false }
    }

    private suspend fun onMainScreen(pkg: String): Boolean {
        val svc = ControlService.instance ?: return false
        repeat(8) { delay(250); if (runCatching { svc.snapshot(Display.DEFAULT_DISPLAY, 5).packageName }.getOrNull() == pkg) return true }
        return false
    }

    private fun DefaultApps.Pick.orAsk(): DefaultApps.App = when (this) {
        is DefaultApps.Pick.Use -> app
        is DefaultApps.Pick.Ask -> throw NeedUser(question, options)
    }
}

/**
 * Which app does a kind of job (maps, music). The rule the user chose: the one they named; else the only one
 * installed; else, of several, the one used last; with none used yet, or none installed, ask.
 */
object DefaultApps {
    class App(val pkg: String, val label: String)
    sealed class Pick {
        class Use(val app: App) : Pick()
        class Ask(val question: String, val options: List<String>) : Pick()
    }

    enum class Kind(val noun: String, val known: List<String>) {
        MAPS("directions", listOf("com.google.android.apps.maps", "com.waze")),
        MUSIC("music", listOf("com.spotify.music", "com.google.android.apps.youtube.music", "com.amazon.mp3", "com.apple.android.music",
            "com.pandora.android", "com.soundcloud.android", "deezer.android.app", "com.aspiro.tidal")),
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("agentos_default_apps", Context.MODE_PRIVATE)

    fun installed(ctx: Context, kind: Kind): List<App> = kind.known.mapNotNull { p ->
        try { App(p, ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(p, 0)).toString()) } catch (_: Throwable) { null }
    }

    /** This app started [pkg] for the user: it counts as their latest use. */
    fun used(ctx: Context, pkg: String) = prefs(ctx).edit().putLong(pkg, System.currentTimeMillis()).apply()

    fun pick(ctx: Context, kind: Kind, named: String?, apps: InstalledApps): Pick {
        val have = installed(ctx, kind)
        val n = named?.trim().orEmpty()
        if (n.isNotEmpty()) {
            val app = have.firstOrNull { it.label.equals(n, true) || it.pkg == n } ?: have.firstOrNull { it.label.contains(n, true) }
                ?: apps.resolve(n)?.let { a -> App(a.packageName, a.label) }
            return if (app != null) Pick.Use(app)
            else Pick.Ask("${n.replaceFirstChar { it.uppercase() }} isn't installed. Which app should I use for ${kind.noun}?", have.map { it.label }.ifEmpty { listOf("Never mind") })
        }
        if (have.isEmpty()) return Pick.Ask(
            if (kind == Kind.MAPS) "You don't have Google Maps or Waze. Should I open the directions in the browser instead?" else "You don't have a music app I can play from. Which one do you use?",
            if (kind == Kind.MAPS) listOf("Use the browser", "Never mind") else listOf("Spotify", "YouTube Music", "Never mind"))
        if (have.size == 1) return Pick.Use(have[0])
        val last = lastUsed(ctx, have.map { it.pkg })
        val best = have.maxByOrNull { last[it.pkg] ?: 0L }!!
        if ((last[best.pkg] ?: 0L) == 0L) return Pick.Ask("Which app should I use for ${kind.noun}?", have.map { it.label }.take(4))
        return Pick.Use(best)
    }

    @Volatile private var askedForUsage = false

    /** Usage access is an app op the user would grant in Settings; with the Shizuku shell we can set it once ourselves. */
    private fun ensureUsageAccess(ctx: Context) {
        if (askedForUsage) return
        askedForUsage = true
        val ops = ctx.getSystemService(android.app.AppOpsManager::class.java)
        val mode = runCatching { ops.unsafeCheckOpNoThrow(android.app.AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), ctx.packageName) }.getOrNull()
        if (mode == android.app.AppOpsManager.MODE_ALLOWED) return
        runCatching { com.agentos.workspace.Workspace.shell?.exec("appops set ${ctx.packageName} GET_USAGE_STATS allow") }
    }

    /** When each of [pkgs] was last used: the system's usage stats (when allowed) or our own launches, whichever is later. */
    fun lastUsed(ctx: Context, pkgs: List<String>): Map<String, Long> {
        val ours = pkgs.associateWith { prefs(ctx).getLong(it, 0L) }
        ensureUsageAccess(ctx)
        val sys = try {
            val um = ctx.getSystemService(UsageStatsManager::class.java)
            val now = System.currentTimeMillis()
            um.queryAndAggregateUsageStats(now - 60L * 86_400_000, now).filterKeys { it in pkgs }
                .mapValues { (_, s) -> maxOf(s.lastTimeUsed, if (android.os.Build.VERSION.SDK_INT >= 29) maxOf(s.lastTimeVisible, s.lastTimeForegroundServiceUsed) else 0L) }
        } catch (_: Throwable) { emptyMap() }   // no usage access: an empty map, not an error
        return pkgs.associateWith { maxOf(ours[it] ?: 0L, sys[it] ?: 0L) }
    }
}
