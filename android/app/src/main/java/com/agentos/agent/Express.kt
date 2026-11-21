package com.agentos.agent

import android.content.Context
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject

/**
 * The express lane: a message that is exactly one of the quick jobs ([QuickActions]: weather, directions, play)
 * is done in code, without the main model or the Pi harness. A small fast model reads the message (and the last
 * reply, for "and tomorrow?") into one action with its arguments, or "none"; code runs the tool and answers
 * from a template.
 *
 * Safe to skip the main model because every express action is read-only or easy to undo: nothing here
 * sends, pays or deletes. Anything unclear goes the normal way: the classifier says none, the tool would have
 * to ask (which app?), or it fails or can't confirm. Nothing has happened at that point except, at worst, an
 * app starting, and the main model hears what the quick try did.
 *
 * Only plain requests are considered: short, typed or spoken by the owner, no attachments, not about the
 * screen, not a job's run or a background task. A keyword gate keeps other messages from paying for a call.
 */
object Express {
    private const val TAG = "AgentOS/Express"
    private const val CLASSIFY_MS = 3500L
    private const val MAX_LEN = 160

    private val GATE = Regex("(?i)\\b(weather|forecast|rain\\w*|snow\\w*|temperature|degrees|umbrella|jacket|sunny|cold|hot|warm|humid|wind\\w*|" +
        "directions?|navigat\\w*|take me|drive|driving|walk|route|how (do i|to|can i) get|get me to|way to|commute|" +
        "play\\w*|put on|listen|resume|music|song|album|playlist|podcast|spotify|youtube music)\\b")

    class Match(val action: String, val args: JSONObject, val day: Int, val rainAsked: Boolean = false)
    private val RAIN = Regex("(?i)\\b(rain\\w*|umbrella|wet|showers?|drizzle|storm\\w*)\\b")

    private val INSTRUCTIONS = """
You route a phone assistant's messages. Decide if the user's message is exactly one of these simple requests, and fill in its arguments:
- weather: any question about the weather or what to wear for it. place: a named city/place or null for here. day: "today", "tomorrow", "week", or a weekday name.
- directions: get directions or navigate to one destination. destination: as the user said it ("home", "SFO", "Blue Bottle on Valencia"). mode: drive, walk, bike or transit (default drive). app only if named (Google Maps, Waze).
- play: play or resume music, a song, artist, album, playlist or podcast. query: what to play without "on Spotify" etc. ("" to resume); artist if named; kind: song, artist, album, playlist, podcast or any. app only if named.
Otherwise action is "none": anything else, several requests in one message, questions about these that need more than the action (e.g. "how long is the drive", "who sings this"), anything referring to the screen or earlier messages you can't resolve, or when unsure.
The previous reply is only context for follow-ups like "and tomorrow?". Unused fields are null.
""".trim()

    private val SCHEMA: JSONObject by lazy {
        fun s(desc: String? = null) = JSONObject().put("type", JSONArray().put("string").put("null")).apply { desc?.let { put("description", it) } }
        val props = JSONObject()
            .put("action", JSONObject().put("type", "string").put("enum", JSONArray(listOf("weather", "directions", "play", "none"))))
            .put("place", s()).put("day", s()).put("destination", s()).put("mode", s()).put("query", s()).put("artist", s()).put("kind", s()).put("app", s())
        JSONObject().put("type", "object").put("additionalProperties", false).put("properties", props)
            .put("required", JSONArray(props.keys().asSequence().toList()))
    }

    /** Cheap checks before any model call: is this a message the express lane may take at all? */
    fun eligible(ctx: Context, text: String): Boolean =
        com.agentos.data.Prefs.expressLane(ctx) && text.length <= MAX_LEN && GATE.containsMatchIn(text) && com.agentos.data.Prefs.apiKey(ctx).isNotBlank() && ModelRouter.online(ctx)

    suspend fun classify(ctx: Context, text: String, lastReply: String?): Match? {
        val prompt = (lastReply?.takeIf { it.isNotBlank() }?.let { "Previous reply: ${it.take(300)}\n" } ?: "") + "Message: $text"
        val r = withTimeoutOrNull(CLASSIFY_MS) {
            withContext(Dispatchers.IO) {
                try { OpenAi.decide(ctx, Navigator.MODEL, INSTRUCTIONS, prompt, SCHEMA, effort = "low", tier = "fast", maxOutputTokens = 300) }
                catch (c: kotlinx.coroutines.CancellationException) { throw c } catch (t: Throwable) { Log.w(TAG, "classify", t); null }
            }
        } ?: return null
        fun str(k: String) = r.optString(k).takeIf { !r.isNull(k) && it.isNotBlank() && it != "null" }
        return when (r.optString("action")) {
            "weather" -> {
                val day = when (val d = str("day")?.lowercase()) {
                    null, "today", "now" -> 0
                    "tomorrow" -> 1
                    "week", "this week" -> -1
                    else -> java.time.DayOfWeek.values().firstOrNull { it.name.equals(d, true) }?.let { dow ->
                        ((dow.value - java.time.LocalDate.now().dayOfWeek.value + 7) % 7).takeIf { it > 0 } ?: 7
                    }?.takeIf { it <= 6 } ?: 0
                }
                Match("weather", JSONObject().put("days", if (day < 0) 7 else maxOf(day + 1, 1)).apply { str("place")?.let { put("place", it) } }, day, RAIN.containsMatchIn(text))
            }
            "directions" -> str("destination")?.let { d ->
                Match("directions", JSONObject().put("destination", d).put("mode", str("mode")?.takeIf { it in setOf("drive", "walk", "bike", "transit") } ?: "drive")
                    .apply { str("app")?.let { put("app", it) } }, 0)
            }
            "play" -> Match("play_media", JSONObject().put("query", str("query") ?: "").put("kind", str("kind")?.takeIf { it in setOf("song", "artist", "album", "playlist", "podcast") } ?: "any")
                .apply { str("artist")?.let { put("artist", it) }; str("app")?.let { put("app", it) } }, 0)
            else -> null
        }
    }

    /** What the express try came to: [reply] when it handled the message, else [note] for the main model (or null if nothing ran). */
    class Outcome(val reply: String?, val note: String?)

    suspend fun run(ctx: Context, m: Match, target: TaskTarget): Outcome {
        val t0 = SystemClock.uptimeMillis()
        try {
            if (m.action == "weather") {
                val f = try { com.agentos.connect.Forecast.get(ctx, m.args.optString("place").ifBlank { null }, m.args.optInt("days", 1)) }
                    catch (t: Throwable) { return Outcome(null, null) }
                return Outcome(f.spoken(m.day, m.rainAsked), null)
            }
            val out = try { AgentTools.execute(ctx, m.action, m.args, AgentRuntime.apps, target) }
                catch (q: NeedUser) { return Outcome(null, null) }   // which app? the main model asks, with the conversation
            if (out.isError) return Outcome(null, if (target.handedOver != null) "(A quick ${m.action} try already ran: ${out.text.take(300)})" else null)
            return when (m.action) {
                "directions" -> {
                    val app = Regex("^(.+?) is (on the user's screen|starting)").find(out.text)?.groupValues?.get(1) ?: "your maps app"
                    Outcome("Starting ${modeWord(m.args.optString("mode"))}directions to ${m.args.optString("destination")} in $app.", null)
                }
                else -> {
                    val playing = Regex("^Playing (.+?) on (.+?) \\(").find(out.text)
                    if (playing != null) Outcome("Playing ${playing.groupValues[1].replace("\"", "")} on ${playing.groupValues[2]}.", null)
                    else Outcome(null, "(A quick play_media try already ran for this message: ${out.text.take(1200)})")
                }
            }
        } finally {
            Trace.time(target.session?.id, "express ${m.action}", SystemClock.uptimeMillis() - t0)
        }
    }

    private fun modeWord(mode: String) = when (mode) { "walk" -> "walking "; "bike" -> "cycling "; "transit" -> "transit "; else -> "" }
}
