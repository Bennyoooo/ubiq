package com.agentos.connect

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import android.provider.ContactsContract
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * The phone's own calendars and contacts, read and written through Android's providers. They are
 * synced with the user's Google (or other) accounts, so no sign-in is needed: only the permission.
 */
object DeviceData {
    /** What stops the phone's calendars from being read, in words for the user, or null when they can be. */
    fun calendarIssue(c: Context): String? = if (hasCalendars(c)) null else if (calendarAllowed(c)) {
        // Google's calendar sync ships with GApps builds (and with the Calendar app); then only an account is missing.
        fun installed(pkg: String) = try { c.packageManager.getPackageInfo(pkg, 0); true } catch (_: Exception) { false }
        if (installed("com.google.android.syncadapters.calendar") || installed("com.google.android.calendar"))
            "No calendar syncs to this phone yet: sign in to Google in Android Settings > Passwords & accounts."
        else "No calendar syncs to this phone: install Google Calendar and sign in."
    } else "Allow calendar access: Ubiq Settings > Connectors > Calendar."

    fun calendarAllowed(ctx: Context) = granted(ctx, Manifest.permission.READ_CALENDAR) && granted(ctx, Manifest.permission.WRITE_CALENDAR)
    fun contactsAllowed(ctx: Context) = granted(ctx, Manifest.permission.READ_CONTACTS)
    /** False when no account syncs a calendar (e.g. no Google Calendar app installed). */
    fun hasCalendars(ctx: Context): Boolean = calendarAllowed(ctx) && try {
        ctx.contentResolver.query(CalendarContract.Calendars.CONTENT_URI, arrayOf(CalendarContract.Calendars._ID), null, null, null)?.use { it.count > 0 } == true
    } catch (_: Throwable) { false }
    private fun granted(ctx: Context, p: String) = ctx.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    private val dayFmt get() = SimpleDateFormat("EEE MMM d", Locale.US)
    private val timeFmt get() = SimpleDateFormat("HH:mm", Locale.US)

    /** "2026-10-20", "2026-10-20T15:30" or "2026-10-20 15:30", in the phone's time zone. */
    fun parseTime(s: String): Long? {
        val t = s.trim().replace(' ', 'T')
        for (p in listOf("yyyy-MM-dd'T'HH:mm:ss", "yyyy-MM-dd'T'HH:mm", "yyyy-MM-dd"))
            try { return SimpleDateFormat(p, Locale.US).apply { isLenient = false }.parse(t)?.time } catch (_: Throwable) {}
        return null
    }

    fun events(ctx: Context, from: Long, days: Int, query: String?): String {
        val rows = eventRows(ctx, from, days, query)
        if (rows.isEmpty()) return "No events" + (if (query.isNullOrBlank()) "" else " matching \"$query\"") + " in that range."
        return rows.take(80).joinToString("\n") { r ->
            "(event ${r["id"]}) ${r["day"]} ${r["time"]}  ${r["title"]!!.ifBlank { "(no title)" }}" +
                (if (r["location"]!!.isNotBlank()) " @ ${r["location"]}" else "") + " [${r["calendar"]}]" +
                (if (r["notes"]!!.isNotBlank()) "\n    ${r["notes"]!!.replace('\n', ' ').take(160)}" else "")
        }
    }

    /** Events as rows of fields (id, title, day, time, start, end, location, calendar, notes, all_day), soonest first. */
    fun eventRows(ctx: Context, from: Long, days: Int, query: String?): List<Map<String, String>> {
        val to = from + days * 86_400_000L
        val proj = arrayOf(CalendarContract.Instances.EVENT_ID, CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END, CalendarContract.Instances.ALL_DAY, CalendarContract.Instances.EVENT_LOCATION,
            CalendarContract.Instances.CALENDAR_DISPLAY_NAME, CalendarContract.Instances.DESCRIPTION)
        val rows = ArrayList<Pair<Long, Map<String, String>>>()
        val utc = TimeZone.getTimeZone("UTC")
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
        CalendarContract.Instances.query(ctx.contentResolver, proj, from, to)?.use { c ->
            while (c.moveToNext()) {
                val title = c.getString(1).orEmpty()
                val loc = c.getString(5).orEmpty()
                val desc = c.getString(7).orEmpty()
                if (!query.isNullOrBlank() && listOf(title, loc, desc).none { it.contains(query, true) }) continue
                val begin = c.getLong(2); val end = c.getLong(3); val allDay = c.getInt(4) == 1
                // All-day events are stored as UTC midnights.
                val day = if (allDay) dayFmt.apply { timeZone = utc }.format(begin) else dayFmt.format(begin)
                val time = if (allDay) "all day" else "${timeFmt.format(begin)}-${timeFmt.format(end)}"
                val fmt = if (allDay) stamp.apply { timeZone = utc } else stamp.apply { timeZone = TimeZone.getDefault() }
                rows += begin to mapOf("id" to c.getLong(0).toString(), "title" to title, "day" to day, "time" to time,
                    "start" to fmt.format(begin), "end" to fmt.format(end), "location" to loc, "calendar" to c.getString(6).orEmpty(),
                    "notes" to desc.take(500), "all_day" to allDay.toString())
            }
        }
        return rows.sortedBy { it.first }.map { it.second }
    }

    /** The calendar new events go to: the primary calendar of a synced account, else any writable one. */
    private fun defaultCalendar(ctx: Context, name: String?): Pair<Long, String>? {
        val proj = arrayOf(CalendarContract.Calendars._ID, CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, CalendarContract.Calendars.IS_PRIMARY,
            CalendarContract.Calendars.ACCOUNT_TYPE)
        val sel = "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ${CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR} AND ${CalendarContract.Calendars.VISIBLE} = 1"
        val all = ArrayList<Triple<Long, String, Int>>()
        ctx.contentResolver.query(CalendarContract.Calendars.CONTENT_URI, proj, sel, null, null)?.use { c ->
            while (c.moveToNext()) {
                val score = (if (c.getInt(2) == 1) 2 else 0) + (if (c.getString(3) == "com.google") 1 else 0)
                all += Triple(c.getLong(0), c.getString(1).orEmpty(), score)
            }
        }
        val pick = name?.takeIf { it.isNotBlank() }?.let { n -> all.firstOrNull { it.second.equals(n, true) } ?: all.firstOrNull { it.second.contains(n, true) } }
            ?: all.maxByOrNull { it.third }
        return pick?.let { it.first to it.second }
    }

    fun addEvent(ctx: Context, title: String, start: Long, end: Long, allDay: Boolean, location: String?, notes: String?, calendar: String?): String {
        val (calId, calName) = defaultCalendar(ctx, calendar) ?: return "ERROR: no writable calendar on this phone."
        val tz = if (allDay) "UTC" else TimeZone.getDefault().id
        // All-day events are stored as UTC midnights.
        fun utcMidnight(t: Long): Long { val c = java.util.Calendar.getInstance(); c.timeInMillis = t
            return java.util.Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { clear(); set(c.get(1), c.get(2), c.get(5)) }.timeInMillis }
        val v = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calId)
            put(CalendarContract.Events.TITLE, title)
            put(CalendarContract.Events.DTSTART, if (allDay) utcMidnight(start) else start)
            put(CalendarContract.Events.DTEND, if (allDay) utcMidnight(end).coerceAtLeast(utcMidnight(start) + 86_400_000L) else end)
            put(CalendarContract.Events.ALL_DAY, if (allDay) 1 else 0)
            put(CalendarContract.Events.EVENT_TIMEZONE, tz)
            location?.takeIf { it.isNotBlank() }?.let { put(CalendarContract.Events.EVENT_LOCATION, it) }
            notes?.takeIf { it.isNotBlank() }?.let { put(CalendarContract.Events.DESCRIPTION, it) }
        }
        val uri = ctx.contentResolver.insert(CalendarContract.Events.CONTENT_URI, v) ?: return "ERROR: the calendar refused the event."
        return "Added \"$title\" to $calName (event ${uri.lastPathSegment}): ${dayFmt.format(start)}" + if (allDay) ", all day." else " ${timeFmt.format(start)}-${timeFmt.format(end)}."
    }

    fun deleteEvent(ctx: Context, id: Long): Boolean =
        ctx.contentResolver.delete(android.content.ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id), null, null) > 0

    fun contacts(ctx: Context, query: String): String {
        val out = contactMap(ctx, query)
        if (out.isEmpty()) return "No contacts match \"$query\"."
        return out.entries.joinToString("\n") { (n, v) -> "$n: ${v.joinToString(", ")}" }
    }

    /** Contacts as rows: name, phone (first number), email (first address). */
    fun contactRows(ctx: Context, query: String): List<Map<String, String>> = contactMap(ctx, query).map { (n, v) ->
        mapOf("name" to n, "phone" to (v.firstOrNull { it.startsWith("phone ") }?.removePrefix("phone ") ?: ""),
            "email" to (v.firstOrNull { it.startsWith("email ") }?.removePrefix("email ") ?: ""))
    }

    private fun contactMap(ctx: Context, query: String): Map<String, Set<String>> {
        val out = LinkedHashMap<String, MutableSet<String>>()
        val res = ctx.contentResolver
        fun collect(uri: android.net.Uri, nameCol: String, valueCol: String, typeLabel: String) {
            res.query(uri, arrayOf(nameCol, valueCol), null, null, null)?.use { c ->
                while (c.moveToNext() && out.size < 25) {
                    val n = c.getString(0) ?: continue
                    c.getString(1)?.let { out.getOrPut(n) { LinkedHashSet() } += "$typeLabel $it" }
                }
            }
        }
        val q = android.net.Uri.encode(query.trim())
        collect(android.net.Uri.withAppendedPath(ContactsContract.CommonDataKinds.Phone.CONTENT_FILTER_URI, q),
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER, "phone")
        collect(android.net.Uri.withAppendedPath(ContactsContract.CommonDataKinds.Email.CONTENT_FILTER_URI, q),
            ContactsContract.CommonDataKinds.Email.DISPLAY_NAME, ContactsContract.CommonDataKinds.Email.ADDRESS, "email")
        return out
    }
}
