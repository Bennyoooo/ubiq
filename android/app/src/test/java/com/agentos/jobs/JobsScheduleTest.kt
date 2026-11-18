package com.agentos.jobs

import org.junit.Assert.assertEquals
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class JobsScheduleTest {
    private val f = SimpleDateFormat("yyyy-MM-dd HH:mm EEE", Locale.US)
    private fun t(s: String) = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).parse(s)!!.time
    private fun next(j: Jobs.Job, after: String) = Jobs.next(j, t(after)).let { if (it == 0L) "none" else f.format(it) }
    private fun rec(repeat: String, time: String = "08:00", at: String = "2026-10-19 00:00", interval: Int = 1, days: String = "", until: String? = null, max: Int = 0, runs: Int = 0) =
        Jobs.Job(1, Jobs.Kind.RECURRING, "x", "y", repeat = repeat, time = time, at = t(at), interval = interval, days = days,
            until = until?.let { t(it) } ?: 0, maxRuns = max, runs = runs)

    init { TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles")) }

    @Test fun daily() {
        assertEquals("2026-10-19 08:00 Mon", next(rec("daily"), "2026-10-19 07:00"))
        assertEquals("2026-10-20 08:00 Tue", next(rec("daily"), "2026-10-19 08:00"))
        assertEquals("2026-10-21 08:00 Wed", next(rec("daily", interval = 2), "2026-10-19 09:00"))
    }
    @Test fun weekdays() {
        assertEquals("2026-10-26 08:00 Mon", next(rec("weekdays"), "2026-10-23 09:00")) // Fri after 8 -> Mon
    }
    @Test fun weekly() {
        assertEquals("2026-10-22 08:00 Thu", next(rec("weekly", days = "mon,thu"), "2026-10-19 09:00"))
        assertEquals("2026-10-26 08:00 Mon", next(rec("weekly", days = "mon"), "2026-10-19 09:00"))
        assertEquals("2026-11-02 08:00 Mon", next(rec("weekly", days = "mon", interval = 2), "2026-10-19 09:00"))
        assertEquals("2026-10-22 08:00 Thu", next(rec("weekly", days = "mon,thu", interval = 2), "2026-10-19 09:00"))
        assertEquals("2026-11-02 08:00 Mon", next(rec("weekly", days = "mon,thu", interval = 2), "2026-10-22 09:00"))
    }
    @Test fun monthly() {
        assertEquals("2026-11-30 08:00 Mon", next(rec("monthly", at = "2026-10-31 00:00"), "2026-11-01 00:00")) // clamps to month end
        assertEquals("2026-12-31 08:00 Thu", next(rec("monthly", at = "2026-10-31 00:00"), "2026-12-01 00:00"))
    }
    @Test fun hourly() {
        assertEquals("2026-10-19 13:00 Mon", next(rec("hourly", at = "2026-10-19 09:00", interval = 2), "2026-10-19 11:30"))
    }
    @Test fun dstAndEnds() {
        assertEquals("2026-11-01 08:00 Sun", next(rec("daily"), "2026-10-31 09:00")) // DST ends Nov 1
        assertEquals("none", next(rec("daily", until = "2026-10-20 00:00"), "2026-10-20 09:00"))
        assertEquals("2026-10-20 08:00 Tue", next(rec("daily", until = "2026-10-20 00:00"), "2026-10-19 09:00"))
        assertEquals("none", next(rec("daily", max = 3, runs = 3), "2026-10-19 09:00"))
    }
    @Test fun once() {
        val j = Jobs.Job(1, Jobs.Kind.ONCE, "x", "y", at = t("2026-10-20 09:00"))
        assertEquals("2026-10-20 09:00 Tue", next(j, "2026-10-19 09:00"))
        assertEquals("none", next(j.copy(runs = 1), "2026-10-19 09:00"))
    }
}
