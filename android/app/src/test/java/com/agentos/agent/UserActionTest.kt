package com.agentos.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Messages from the 2026-11-15 traces where a task asked the user to act on its hidden workspace. */
class UserActionTest {
    @Test fun asksToActOnAScreen() {
        listOf(
            "Garmin isn’t connected yet. Android needs your approval for Garmin to write fitness data; please review and allow only the data you want shared on the permission screen, then tell me when done.",
            "The location permission prompt isn’t responding on my workspace. Please select “Allow this time” on the Best Buy/Chrome prompt if it appears on your phone, then let me know.",
            "Please review the permission prompt on your phone and allow the data you want.",
            "Tap Allow when you see it.",
            "You'll need to tap \"Continue\" to finish signing in.",
            "Please sign in to your Best Buy account, then tell me.",
            "Enter your password and I'll take it from there.",
        ).forEach { assertTrue(it, UserAction.asksUserToAct(it)) }
    }

    @Test fun plainQuestionsPass() {
        listOf(
            "I found the ready-for-pickup text. Best Buy’s site asks to use your device’s precise location; may I choose “Allow this time”? Also, are you parked yet, and if so, what curbside spot and vehicle color/make/model?",
            "Which size do you want, M or L?",
            "Should I send the message to Anna now?",
            "Left Garmin disconnected as you requested; no sharing permissions were granted.",
            "Your next alarm is Monday at 9:20.",
        ).forEach { assertFalse(it, UserAction.asksUserToAct(it)) }
    }
}
