package com.agentos.jobs

import android.content.Context
import com.agentos.agent.AgentTools
import com.agentos.agent.TaskTarget
import com.agentos.agent.ToolOutcome
import com.agentos.connect.DeviceData
import org.json.JSONObject

/** The agent's tools for jobs (Space > Jobs). */
object JobTools {
    private val JOB_FIELDS = """
        "title":{"type":"string","description":"Short name shown in Space > Jobs"},
        "instruction":{"type":"string","description":"What to do each run, in the user's words (action=task)"},
        "action":{"type":"string","enum":["task","digest"],"description":"task (default): run the instruction as a background task. digest: summarize app's notifications since the last run"},
        "at":{"type":"string","description":"once: when, local time YYYY-MM-DD HH:MM. recurring: optional first day / start"},
        "repeat":{"type":"string","enum":["hourly","daily","weekdays","weekly","monthly"]},
        "interval":{"type":"integer","description":"Every N hours/days/weeks/months, default 1"},
        "time":{"type":"string","description":"recurring (not hourly): 24h HH:MM"},
        "days":{"type":"string","description":"weekly: e.g. mon,thu"},
        "until":{"type":"string","description":"recurring: last date YYYY-MM-DD, optional"},
        "max_runs":{"type":"integer","description":"recurring: stop after this many runs, optional"},
        "app":{"type":"string","description":"trigger: the app whose notifications start it; digest: the app to summarize"},
        "keywords":{"type":"string","description":"trigger: only notifications containing one of these comma-separated words, optional"}""" + com.agentos.agent.AgentTools.ON_DEVICE_FIELD

    private val defs = listOf(
        AgentTools.tool("add_job", "Create a job the user asked for. type=once: run at a time. type=recurring: run on a schedule. " +
            "type=trigger: run when a matching notification arrives. Not for plain reminders (set those in Clock or Calendar).",
            """{"properties":{"type":{"type":"string","enum":["once","recurring","trigger"]},$JOB_FIELDS},"required":["type","title"]}"""),
        AgentTools.tool("list_jobs", "List the user's jobs with their schedule, next run, last run and status.", """{"properties":{},"required":[]}"""),
        AgentTools.tool("update_job", "Change a job by its [number]: any field of add_job, or enabled=false to pause / true to resume.",
            """{"properties":{"id":{"type":"integer"},"enabled":{"type":"boolean"},$JOB_FIELDS},"required":["id"]}"""),
        AgentTools.tool("delete_job", "Delete a job by its [number].", """{"properties":{"id":{"type":"integer"}},"required":["id"]}"""),
        AgentTools.tool("run_job_now", "Run a job once right now (does not change its schedule).", """{"properties":{"id":{"type":"integer"}},"required":["id"]}"""),
        AgentTools.tool("job_result", "Read the chat of a job's last run (what it did and reported).", """{"properties":{"id":{"type":"integer"}},"required":["id"]}"""),
    )
    val names: Set<String> = defs.map { it.getString("name") }.toSet()
    fun definitions(): List<JSONObject> = defs

    suspend fun execute(ctx: Context, name: String, input: JSONObject, target: TaskTarget): ToolOutcome? {
        if (name !in names) return null
        Jobs.init(ctx)
        // A job's own run (or a notification it read) must not be able to set up or change jobs.
        if (target.guarded && name != "list_jobs" && name != "job_result") return ToolOutcome.error("Only the user can change jobs. Mention it in your report instead.")
        val id = input.optInt("id", -1)
        return when (name) {
            "add_job" -> {
                val kind = when (input.optString("type")) { "once" -> Jobs.Kind.ONCE; "recurring" -> Jobs.Kind.RECURRING; "trigger" -> Jobs.Kind.TRIGGER
                    else -> return ToolOutcome.error("type must be once, recurring or trigger.") }
                val j = apply(Jobs.Job(0, kind, "", ""), input) ?: return ToolOutcome.error(timeError)
                Jobs.validate(j)?.let { return ToolOutcome.error(it) }
                val added = Jobs.add(j)
                ToolOutcome("Job added: ${added.describe()}" + accessNote(ctx, added))
            }
            "list_jobs" -> ToolOutcome(Jobs.jobs.value.ifEmpty { null }?.joinToString("\n") { it.describe() } ?: "No jobs yet.")
            "update_job" -> {
                val old = Jobs.get(id) ?: return ToolOutcome.error("No job with that number. Call list_jobs.")
                var j = apply(old, input) ?: return ToolOutcome.error(timeError)
                if (input.has("enabled")) j = j.copy(enabled = input.optBoolean("enabled"))
                if (j.enabled && old.finished) j = j.copy(runs = 0)
                Jobs.validate(j)?.takeIf { j.enabled }?.let { return ToolOutcome.error(it) }
                ToolOutcome("Job updated: ${Jobs.replace(j).describe()}")
            }
            "delete_job" -> if (Jobs.delete(id)) ToolOutcome("Job deleted.") else ToolOutcome.error("No job with that number.")
            "run_job_now" -> {
                val j = Jobs.get(id) ?: return ToolOutcome.error("No job with that number.")
                Jobs.run(j); ToolOutcome("Started \"${j.title}\". It runs in the background; its result shows in Space > Jobs.")
            }
            "job_result" -> {
                val j = Jobs.get(id) ?: return ToolOutcome.error("No job with that number.")
                val s = j.lastSession?.let { com.agentos.agent.AgentRuntime.session(it) }
                if (j.action == "digest") return ToolOutcome(com.agentos.notify.NotifyRules.digests.value.firstOrNull { it.ruleId == j.id }?.text ?: "No digest yet.")
                if (s == null) ToolOutcome("No run recorded" + if (j.lastNote.isNotBlank()) " (${j.lastNote})." else ".")
                else ToolOutcome("Last run ${Jobs.fmt(j.lastRun)}, status ${s.status.value}:\n" +
                    s.items.value.takeLast(12).joinToString("\n") { "${it.role.name.lowercase()}: ${it.text.take(400)}" })
            }
            else -> null
        }
    }

    private const val timeError = "Times must be local, as YYYY-MM-DD HH:MM (at) and YYYY-MM-DD (until)."

    /** [base] with the fields given in [input]; null if a date does not parse. */
    private fun apply(base: Jobs.Job, i: JSONObject): Jobs.Job? {
        fun str(k: String, cur: String) = if (i.has(k)) i.optString(k).trim() else cur
        val at = if (i.has("at") && i.optString("at").isNotBlank()) DeviceData.parseTime(i.optString("at")) ?: return null else base.at
        val until = if (i.has("until") && i.optString("until").isNotBlank()) DeviceData.parseTime(i.optString("until")) ?: return null else base.until
        val action = str("action", base.action).ifBlank { "task" }
        return base.copy(
            title = str("title", base.title), instruction = str("instruction", base.instruction), action = action,
            at = at, repeat = str("repeat", base.repeat), interval = if (i.has("interval")) i.optInt("interval", 1).coerceIn(1, 365) else base.interval,
            time = str("time", base.time).let { if (Regex("^\\d:\\d\\d$").matches(it)) "0$it" else it },
            days = str("days", base.days).lowercase(), until = until,
            maxRuns = if (i.has("max_runs")) i.optInt("max_runs").coerceAtLeast(0) else base.maxRuns,
            app = str("app", base.app), keywords = str("keywords", base.keywords),
            onDevice = if (i.has("on_device")) i.optBoolean("on_device") else base.onDevice,
        ).let { if (it.kind == Jobs.Kind.RECURRING && it.repeat.isBlank() && action == "digest") it.copy(repeat = "daily") else it }
    }

    private fun accessNote(ctx: Context, j: Jobs.Job): String =
        if ((j.kind == Jobs.Kind.TRIGGER || j.action == "digest") && !com.agentos.notify.NotificationWatcher.hasAccess(ctx))
            "\nNote: Ubiq does not have notification access yet. Tell the user to allow it: Ubiq Settings > Notifications > Allow access."
        else ""
}
