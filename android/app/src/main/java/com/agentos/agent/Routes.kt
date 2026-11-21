package com.agentos.agent

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.agentos.control.ControlService
import com.agentos.control.ScreenSnapshot
import com.agentos.control.UiNode
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Replayable routes: how the agent reached a page in an app, recorded from what it actually did, so the next
 * "get me X from Y" is a replay in code instead of a model working through the screens again.
 *
 * - Recording ([record], from [AgentTools.execute]): every screen action of a turn, with the element it hit
 *   (label, resource id, class) taken from the screen the ids referred to, and whether the screen changed.
 * - Learning ([learn], when a turn ends well, on either engine): the steps after the last launch of the app the
 *   turn ended in, minus dead ends (actions that changed nothing, a step undone by back) and stopping before
 *   anything that left a receipt (send, pay, delete...), so a route only ever navigates. Text typed that came
 *   from the user's request becomes a slot the next replay fills in.
 * - Trust: a new route is a candidate; it shows to the model as "worked 1x". Each replay that reaches the end
 *   counts up; one that breaks counts down, and a route that breaks more than it works is dropped.
 * - Replay (`replay_route`): opens the app, then for each step waits for the same element (resource id and label)
 *   and acts on it through [AgentTools.execute], so every guard still applies. On the first mismatch it stops
 *   and returns the screen: the model carries on from there with navigate or taps.
 */
object Routes {
    private const val TAG = "AgentOS/Routes"
    private const val MAX_ROUTES = 200
    private const val MIN_STEPS = 2
    private const val FIND_MS = 4000L

    /** One recorded action. [label]/[resId]/[cls] describe the element; [args] are the tool's other arguments. */
    class Step(val tool: String, val pkg: String, val label: String, val resId: String, val cls: String, val args: JSONObject, val changed: Boolean, val receipt: Boolean, var slot: Int = -1) {
        fun json() = JSONObject().put("tool", tool).put("label", label).put("res", resId).put("cls", cls).put("args", args).put("slot", slot)
        fun words() = when (tool) {
            "type_text" -> "type " + (if (slot >= 0) "{value $slot}" else "\"${args.optString("text").take(30)}\"")
            "scroll" -> "scroll ${args.optString("direction")}"
            "press" -> "press ${args.optString("key")}"
            else -> "${if (tool == "long_press") "long-press" else "tap"} \"${label.ifBlank { resId.substringAfter('/') }.take(30)}\""
        }
        companion object {
            fun from(o: JSONObject) = Step(o.optString("tool"), "", o.optString("label"), o.optString("res"), o.optString("cls"), o.optJSONObject("args") ?: JSONObject(), true, false, o.optInt("slot", -1))
        }
    }

    class Route(val id: Int, val pkg: String, val app: String, var goal: String, val steps: List<Step>, val version: String, var ok: Int, var broke: Int, var last: Long) {
        fun json() = JSONObject().put("id", id).put("pkg", pkg).put("app", app).put("goal", goal).put("version", version).put("ok", ok).put("broke", broke).put("last", last)
            .put("steps", JSONArray().apply { steps.forEach { put(it.json()) } })
        fun slots() = steps.count { it.slot >= 0 }
        fun line() = "[r$id] $app: \"${goal.take(80)}\" (${steps.size} steps: ${steps.joinToString(", ") { it.words() }.take(160)}; worked ${ok}x" +
            (if (broke > 0) ", broke ${broke}x" else "") + (if (slots() > 0) "; values: ${slots()}" else "") + ")"
        /** The same path: same app and the same elements in the same order. */
        fun sameAs(o: Route) = pkg == o.pkg && steps.size == o.steps.size && steps.zip(o.steps).all { (a, b) -> a.tool == b.tool && a.label == b.label && a.resId == b.resId }
    }

    private val SKIP = setOf("navigate", "replay_route", "get_screen", "screenshot", "wait", "read_list", "show_user")
    private val RECORDED = setOf("tap", "long_press", "type_text", "scroll", "press", "open_app", "open_link")

    @Volatile private var loaded = false
    private val routes = ArrayList<Route>()
    private lateinit var file: File

    fun definitions(): List<JSONObject> = listOf(
        AgentTools.tool("replay_route", "Replay a saved route (listed under Saved routes) to reach that page in a second or two: it opens the app and repeats the taps that worked before, checking each screen. " +
            "values fill the route's typed text in order (e.g. a search term). If a step no longer matches, it stops and returns the screen so you can finish with navigate or taps.",
            """{"properties":{"id":{"type":"string","description":"The route id, e.g. r12"},"values":{"type":"array","items":{"type":"string"}}},"required":["id"]}"""),
    )

    // ----- recording -----

    /** Screen identity for "did that change anything": the app and the first labels on screen. */
    fun signature(s: ScreenSnapshot?): String = s?.let { it.packageName + "|" + it.nodes.take(40).joinToString("|") { n -> n.text.ifEmpty { n.desc }.take(24) + n.resId } }.orEmpty()

    /** Called by [AgentTools.execute] around each tool call. */
    fun record(ctx: Context, name: String, input: JSONObject, target: TaskTarget, before: ScreenSnapshot?, sigBefore: String, out: ToolOutcome) {
        if (target.replaying || name !in RECORDED || out.isError) return
        val node: UiNode? = if (input.has("id")) before?.find(input.optInt("id", -1)) else null
        if (name in setOf("tap", "long_press") && node == null) return
        val args = JSONObject(input.toString()).apply { remove("id") }
        val pkg = when (name) {
            "open_app" -> AgentRuntime.apps.resolve(input.optString("name"))?.packageName.orEmpty()
            "open_link" -> target.lastSnap?.packageName.orEmpty()
            else -> before?.packageName.orEmpty()
        }
        val step = Step(name, pkg, node?.let { before!!.labelOf(it) }.orEmpty().take(80), node?.resId.orEmpty(), node?.className?.substringAfterLast('.').orEmpty(),
            args, changed = signature(target.lastSnap) != sigBefore, receipt = out.text.contains("\n\nReceipt: "))
        synchronized(target.routeLog) { target.routeLog += step }
    }

    // ----- learning -----

    /** The turn ended well: keep the path to the app it ended in, if it took real steps. */
    fun learn(ctx: Context, target: TaskTarget) {
        val log = synchronized(target.routeLog) { target.routeLog.toList() }
        if (log.isEmpty()) return
        val endPkg = target.handedOver ?: target.lastSnap?.packageName ?: return
        if (endPkg.isBlank() || endPkg == ctx.packageName || endPkg == "?") return
        val steps = compile(log, endPkg, target.request) ?: return
        ensure(ctx)
        val label = AgentRuntime.apps.all().firstOrNull { it.packageName == endPkg }?.label ?: endPkg
        val r = Route((routes.maxOfOrNull { it.id } ?: 0) + 1, endPkg, label, target.request.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty().trim().take(160),
            steps, version(ctx, endPkg), ok = 1, broke = 0, last = System.currentTimeMillis())
        synchronized(routes) {
            val same = routes.firstOrNull { it.sameAs(r) }
            if (same != null) { same.ok++; same.last = r.last; if (r.goal.length in 4..same.goal.length) same.goal = r.goal }
            else { routes += r; if (routes.size > MAX_ROUTES) routes.remove(routes.minByOrNull { it.last }) }
        }
        save()
        Log.i(TAG, "learned ${label}: ${steps.joinToString(", ") { it.words() }}")
        target.session?.let { Trace.log(it.id, "route", "learned $label: ${steps.joinToString(", ") { it.words() }}") }
    }

    /**
     * The route in [log] to [endPkg]: the steps after the app's last launch, without dead ends, stopping before
     * anything that left a receipt. Typed text found in [request] becomes a numbered slot. Null if it's no route.
     */
    internal fun compile(log: List<Step>, endPkg: String, request: String): List<Step>? {
        val start = log.indexOfLast { (it.tool == "open_app" || it.tool == "open_link") && it.pkg == endPkg }
        if (start < 0) return null
        if (log[start].tool == "open_link") return null    // a deep link already gets there in one step
        val steps = ArrayList<Step>()
        for (s in log.drop(start + 1)) {
            if (s.receipt) break                            // navigation only: never replay a send, pay or delete
            if (s.pkg.isNotEmpty() && s.pkg != endPkg) return null  // wandered into another app: not a clean route
            if (s.tool == "press" && s.args.optString("key") == "back") { if (steps.isNotEmpty()) steps.removeAt(steps.size - 1); continue }
            if (s.tool == "press" || s.tool == "open_app" || s.tool == "open_link") continue
            if (!s.changed && s.tool != "type_text") continue
            steps += s
        }
        if (steps.size < MIN_STEPS) return null
        var slot = 0
        val req = request.lowercase()
        steps.filter { it.tool == "type_text" }.forEach { s -> val t = s.args.optString("text").trim().lowercase(); if (t.length >= 2 && req.contains(t)) s.slot = slot++ }
        return steps
    }

    // ----- for the prompt -----

    /** The saved routes that could help with [request]: the app is named, or the goal shares words with it. */
    fun forPrompt(ctx: Context, request: String): String {
        ensure(ctx)
        val words = request.lowercase().split(Regex("\\W+")).filter { it.length > 2 }.toSet()
        val scored = synchronized(routes) { routes.toList() }.filter { it.ok > it.broke }.map { r ->
            val app = r.app.lowercase().split(' ').any { it.length > 2 && it in words }
            val overlap = r.goal.lowercase().split(Regex("\\W+")).count { it.length > 2 && it in words }
            r to (if (app) 3 else 0) + overlap
        }.filter { it.second >= 2 }.sortedByDescending { it.second }.take(8)
        return scored.joinToString("\n") { it.first.line() }
    }

    fun all(ctx: Context): List<Route> { ensure(ctx); return synchronized(routes) { routes.toList() } }
    fun find(ctx: Context, id: String): Route? { ensure(ctx); val n = id.trim().removePrefix("r").toIntOrNull() ?: return null; return synchronized(routes) { routes.firstOrNull { it.id == n } } }

    // ----- replay -----

    suspend fun replay(ctx: Context, input: JSONObject, apps: InstalledApps, target: TaskTarget): ToolOutcome {
        val r = find(ctx, input.optString("id")) ?: return ToolOutcome.error("No saved route ${input.optString("id")}.")
        val values = input.optJSONArray("values")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty()
        val t0 = SystemClock.uptimeMillis()
        val svc = ControlService.instance ?: return ToolOutcome.error("Phone control is unavailable.")
        target.replaying = true
        try {
            val open = AgentTools.execute(ctx, "open_app", JSONObject().put("name", r.pkg), apps, target)
            if (open.isError) return open
            for ((i, s) in r.steps.withIndex()) {
                val args = JSONObject(s.args.toString())
                if (s.tool == "type_text" && s.slot >= 0) values.getOrNull(s.slot)?.let { args.put("text", it) }
                if (s.label.isNotEmpty() || s.resId.isNotEmpty()) {
                    val node = await(svc, target, s) ?: return broke(ctx, r, i, target, t0, "\"${s.label.ifBlank { s.resId }}\" isn't on the screen")
                    args.put("id", node.id)
                } else if (s.tool == "type_text") {
                    // Typed into whatever had focus: give the screen a moment.
                    delay(300)
                }
                val out = AgentTools.execute(ctx, s.tool, args, apps, target)
                if (out.isError) return broke(ctx, r, i, target, t0, out.text.lineSequence().first().take(160))
            }
            synchronized(routes) { r.ok++; r.last = System.currentTimeMillis() }
            save()
            val secs = "%.1f".format((SystemClock.uptimeMillis() - t0) / 1000.0)
            val snap = svc.snapshot(target.displayId, target.maxNodes).also { target.lastSnap = it; target.lastPackage = it.packageName }
            target.session?.let { Trace.log(it.id, "route", "replayed r${r.id} in $secs s") }
            return ToolOutcome("Route r${r.id} replayed (${r.steps.size} steps, $secs s). Check that the screen shows what the user asked for.\n\nScreen now:\n" + snap.render())
        } finally { target.replaying = false }
    }

    /** Waits for the step's element: same resource id and label first, then the label, then a unique resource id. */
    private suspend fun await(svc: ControlService, target: TaskTarget, s: Step): UiNode? {
        val end = SystemClock.uptimeMillis() + FIND_MS
        while (true) {
            svc.waitForIdle()
            val snap = svc.snapshot(target.displayId, target.maxNodes)
            target.lastSnap = snap; target.lastPackage = snap.packageName
            val byLabel = snap.nodes.filter { s.label.isNotEmpty() && snap.labelOf(it).equals(s.label, true) }
            val hit = byLabel.firstOrNull { s.resId.isEmpty() || it.resId == s.resId }
                ?: byLabel.firstOrNull { it.className.endsWith(s.cls) }
                ?: byLabel.firstOrNull()
                ?: snap.nodes.filter { s.resId.isNotEmpty() && it.resId == s.resId && (s.label.isEmpty() || snap.labelOf(it).isEmpty()) }.singleOrNull()
            if (hit != null) return hit
            if (SystemClock.uptimeMillis() > end) return null
            delay(250)
        }
    }

    private fun broke(ctx: Context, r: Route, step: Int, target: TaskTarget, t0: Long, why: String): ToolOutcome {
        synchronized(routes) {
            r.broke++
            if (r.broke > r.ok) routes.remove(r)
        }
        save()
        target.session?.let { Trace.log(it.id, "route", "r${r.id} broke at step ${step + 1}: $why") }
        val snap = ControlService.instance?.snapshot(target.displayId, target.maxNodes)?.also { target.lastSnap = it }
        return ToolOutcome("Route r${r.id} stopped at step ${step + 1} of ${r.steps.size} (${r.steps[step].words()}): $why. " +
            "The app may have changed. Carry on from this screen with navigate or taps.\n\nScreen now:\n" + (snap?.render() ?: "(unavailable)"), isError = false)
    }

    // ----- storage -----

    private fun version(ctx: Context, pkg: String) = try { ctx.packageManager.getPackageInfo(pkg, 0).versionName.orEmpty() } catch (_: Throwable) { "" }

    @Synchronized private fun ensure(ctx: Context) {
        if (loaded) return
        file = File(ctx.applicationContext.filesDir, "routes.json")
        try {
            if (file.exists()) {
                val a = JSONArray(file.readText())
                for (i in 0 until a.length()) {
                    val o = a.getJSONObject(i)
                    val st = o.getJSONArray("steps")
                    routes += Route(o.getInt("id"), o.getString("pkg"), o.optString("app"), o.optString("goal"), (0 until st.length()).map { Step.from(st.getJSONObject(it)) },
                        o.optString("version"), o.optInt("ok"), o.optInt("broke"), o.optLong("last"))
                }
            }
        } catch (t: Throwable) { Log.w(TAG, "load", t) }
        loaded = true
    }

    private fun save() {
        if (!loaded) return
        val text = synchronized(routes) { JSONArray().apply { routes.forEach { put(it.json()) } }.toString() }
        try { File(file.path + ".tmp").apply { writeText(text); renameTo(file) } } catch (t: Throwable) { Log.w(TAG, "save", t) }
    }
}
