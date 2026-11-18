package com.agentos.notify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FocusMatchTest {
    @Test fun sameName() {
        assertTrue(FocusMatch.matches("Mom", "Mom"))
        assertTrue(FocusMatch.matches("mom", "MOM"))
    }

    @Test fun decoratedNames() {
        assertTrue(FocusMatch.matches("Mom", "Mom ❤️"))
        assertTrue(FocusMatch.matches("Anna Lee", "Anna Lee (2 messages)"))
        assertTrue(FocusMatch.matches("Anna", "Family: Anna"))
    }

    @Test fun wholeWordsOnly() {
        assertFalse(FocusMatch.matches("Ann", "Anna"))
        assertFalse(FocusMatch.matches("Mom", "Mommy blog"))
        assertFalse(FocusMatch.matches("Anna Lee", "Lee Kim"))
    }

    @Test fun firstNameOnlyApps() {
        assertTrue(FocusMatch.matches("Anna Lee", "Anna"))
        assertFalse(FocusMatch.matches("Anna Lee", "Lee"))
    }

    @Test fun nonLatinNames() {
        assertTrue(FocusMatch.matches("妈妈", "妈妈"))
    }

    @Test fun anySenderAllowed() {
        assertTrue(FocusMatch.allowed(listOf("Mom", "Sam"), listOf("Dinner group", "Sam")))
        assertFalse(FocusMatch.allowed(listOf("Mom"), listOf("Slack", "#general")))
        assertFalse(FocusMatch.allowed(listOf("Mom"), emptyList()))
    }

    @Test fun untilMinutesAndClock() {
        val now = java.util.Calendar.getInstance().apply { set(2026, 10, 8, 14, 0, 0); set(java.util.Calendar.MILLISECOND, 0) }.timeInMillis
        assertEquals(0L, Focus.parseUntil("", now))
        assertEquals(now + 45 * 60_000L, Focus.parseUntil("45", now))
        assertEquals(now + 90 * 60_000L, Focus.parseUntil("15:30", now))
        assertEquals(now + 23 * 3_600_000L, Focus.parseUntil("13:00", now)) // already past today: tomorrow
        assertEquals(null, Focus.parseUntil("soon", now))
    }
}
