package com.agentos.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentChecksTest {
    // ---------- secrets ----------

    @Test fun otpBecomesStableToken() {
        val s = Secrets()
        val a = s.redact("[4] TextView \"Your verification code is 482913. Don't share it.\" @(1,2)")
        assertFalse(a.contains("482913"))
        assertTrue(a.contains("⟦otp:1⟧"))
        assertEquals(a, s.redact("[4] TextView \"Your verification code is 482913. Don't share it.\" @(1,2)"))
        assertEquals("482913", s.resolve("⟦otp:1⟧"))
        assertTrue(s.redact("G-771234 is your Google verification code").contains("⟦otp:2⟧"))
        assertTrue(s.redact("Code: 12 34 56").contains("⟦otp:3⟧"))
    }

    @Test fun ordinaryNumbersStay() {
        val s = Secrets()
        listOf("Battery 85%", "Order total \$1234.50", "Meeting at 10:30", "Security update 2024", "Call 555-0100 tomorrow", "Brightness level 40%")
            .forEach { assertEquals(it, s.redact(it)) }
    }

    @Test fun passwordFieldIsTokenized() {
        val s = Secrets()
        val r = s.redact("[9] EditText \"hunter2!\" id=pass (clickable, editable, password) @(5,5)\n[10] EditText \"bob\" (editable) @(1,1)")
        assertFalse(r.contains("hunter2"))
        assertTrue(r.contains("⟦pw:1⟧"))
        assertTrue(r.contains("\"bob\""))
        assertEquals("hunter2!", s.resolve("⟦pw:1⟧"))
        assertEquals("[9] EditText \"••••\" (editable, password) @(5,5)", s.redact("[9] EditText \"••••\" (editable, password) @(5,5)"))
    }

    // ---------- completion check ----------

    private fun ledger(vararg ops: Pair<String, EffectLedger.State>) = EffectLedger().apply {
        ops.forEach { (k, st) -> open(k, "tap", "com.x", "Chat", "hi").state = st }
    }

    @Test fun sentNeedsADoneSend() {
        assertNotNull(CompletionCheck.check("Sent.", "text Sam hi", EffectLedger()))
        assertNotNull(CompletionCheck.check("I texted Sam.", "text Sam hi", ledger("send" to EffectLedger.State.UNKNOWN)))
        assertNull(CompletionCheck.check("I texted Sam.", "text Sam hi", ledger("send" to EffectLedger.State.DONE)))
        assertNull(CompletionCheck.check("Message sent to Sam.", "text Sam hi", ledger("submit" to EffectLedger.State.DONE)))
    }

    @Test fun hedgedAndUnaskedClaimsPass() {
        assertNull(CompletionCheck.check("I couldn't send it: no signal.", "text Sam hi", EffectLedger()))
        assertNull(CompletionCheck.check("Should I send it?", "text Sam hi", EffectLedger()))
        assertNull(CompletionCheck.check("Brightness is 40%. Nothing was sent.", "what is my brightness", EffectLedger()))
        assertNull(CompletionCheck.check("The last email was sent by Ann.", "read my email", EffectLedger()))
    }

    @Test fun deletedAndChanges() {
        assertNotNull(CompletionCheck.check("Deleted the photo.", "delete the last photo", EffectLedger()))
        assertNull(CompletionCheck.check("Deleted the photo.", "delete the last photo", ledger("delete" to EffectLedger.State.DONE)))
        assertNotNull(CompletionCheck.check("I've set a 7 minute timer.", "set a 7 minute timer", EffectLedger()))
        assertNull(CompletionCheck.check("I've set a 7 minute timer.", "set a 7 minute timer", EffectLedger().apply { acted = true }))
    }

    @Test fun counts() {
        val l = EffectLedger().apply { gathered += 6 }
        assertNotNull(CompletionCheck.check("Here are your 10 latest emails: ...", "read my emails", l))
        assertNull(CompletionCheck.check("Here are your 6 latest emails: ...", "read my emails", l))
        assertNull(CompletionCheck.check("Here are your 10 latest emails: ...", "read my emails", EffectLedger()))
    }

    @Test fun unknownOpsAreFlagged() {
        assertNotNull(CompletionCheck.check("Done.", "open settings", ledger("confirm" to EffectLedger.State.UNKNOWN)))
    }

    // ---------- memory provenance ----------

    @Test fun provenance() {
        val owner = "my gate code is 4417, and my email is me@ann.dev"
        val screen = "Your bank: IBAN DE89 3704 0044 0532 0130 00. Contact support@bank.com. code 991100"
        assertNull(Provenance.refusal("The user's gate code is 4417.", "", owner, screen))
        assertNull(Provenance.refusal("The user's email is me@ann.dev.", "", owner, screen))
        assertNotNull(Provenance.refusal("The user's IBAN is DE89 3704 0044 0532 0130 00.", "", owner, screen))
        assertNotNull(Provenance.refusal("Support is support@bank.com.", "", owner, screen))
        assertNull(Provenance.refusal("The user prefers dark mode.", "", owner, screen))
        assertNotNull(Provenance.refusal("Code ⟦otp:1⟧", "", owner, screen))
        assertNull(Provenance.refusal("In Settings: Settings > Display > Brightness level", "", owner, screen))
    }

    @Test fun observedValuesNeedTheOwnersOk() {
        val owner = "save the restaurant's number. my number is 415-555-0100"
        val observed = "[Wed Nov 11 01:05, notification] Priya: Booked Zuni Cafe. Call them at 415-555-0142 if late. zunicafe.com"
        assertEquals(listOf("4155550142"), Provenance.fromObserved("Zuni Cafe: 415-555-0142", owner, observed))
        assertEquals(listOf("zunicafe.com"), Provenance.fromObserved("{\"text\":\"see zunicafe.com\"}", owner, observed))
        assertTrue(Provenance.fromObserved("Call me at 415-555-0100", owner, observed).isEmpty())
        assertTrue(Provenance.fromObserved("Dinner with Priya on Friday", owner, observed).isEmpty())
        assertTrue(Provenance.fromObserved("Zuni Cafe: 415-555-0142", owner, "").isEmpty())
    }
}
