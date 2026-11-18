package com.agentos.policy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PolicyEngineTest {
    private class FakeHost : PolicyEngine.Host {
        var stopped = false
        var rules: Map<String, String> = emptyMap()
        val asked = mutableListOf<PolicyEngine.Pending>()
        override fun loadStopped() = stopped
        override fun saveStopped(stopped: Boolean) { this.stopped = stopped }
        override fun loadRules() = rules
        override fun saveRules(rules: Map<String, String>) { this.rules = rules }
        override fun askOwner(p: PolicyEngine.Pending) { asked += p }
    }

    private val env = object : Floors.Env {
        override fun isDialer(pkg: String) = pkg == "my.dialer"
        override fun isEmergencyNumber(number: String) = number == "555"
    }
    private var now = 1_000L
    private val host = FakeHost()
    private val floors = Floors(env, setOf("ai.ubiq.app", "com.agentos.core")) { it.pkg == "com.agentos.core" }
    private val engine = PolicyEngine(floors, host, selfPackage = "ai.ubiq.app", clock = { now })

    private fun check(pkg: String, effect: String = Effect.WRITE, label: String = "", text: String = "", task: String = "t1") =
        engine.check(Check(pkg, effect, "agent", task, label, text))

    @Test fun defaultRuleAllows() = assertTrue(check("com.example").allowed)

    @Test fun floorsBeatEverything() {
        assertEquals(Decision.REFUSED, check("com.android.settings", label = "Factory reset").state)
        assertEquals(Decision.REFUSED, check("com.agentos.core", label = "Allow").state)
        assertEquals(Decision.REFUSED, check("com.android.dialer", label = "Call", text = "911").state)
        assertEquals(Decision.REFUSED, check("my.dialer", text = "555").state)
        assertTrue(check("com.android.dialer", label = "Call", text = "4155550100").allowed)
        // Reading is never a floor.
        assertTrue(check("com.agentos.core", effect = Effect.READ).allowed)
    }

    @Test fun stopRefusesUntilResumed() {
        engine.stop()
        assertTrue(host.stopped)
        assertEquals(Decision.REFUSED, check("com.example").state)
        engine.resume()
        assertTrue(check("com.example").allowed)
    }

    @Test fun readAndOffRules() {
        assertNull(engine.setRule("com.bank", PolicyEngine.READ, byOwner = true))
        assertTrue(check("com.bank", effect = Effect.READ).allowed)
        assertFalse(check("com.bank").allowed)
        assertNull(engine.setRule("com.bank", PolicyEngine.OFF, byOwner = true))
        assertFalse(check("com.bank", effect = Effect.READ).allowed)
        assertEquals(mapOf("com.bank" to "off"), host.rules)
    }

    @Test fun agentMayOnlyTighten() {
        assertNull(engine.setRule("com.bank", PolicyEngine.ASK, byOwner = false))
        assertNotNull(engine.setRule("com.bank", PolicyEngine.FULL, byOwner = false))
        assertNull(engine.setRule("com.bank", PolicyEngine.FULL, byOwner = true))
        assertNotNull(engine.setRule("ai.ubiq.app", PolicyEngine.OFF, byOwner = true))
    }

    @Test fun askOnceThenForTask() {
        engine.setRule("com.shop", PolicyEngine.ASK, byOwner = true)
        val d = check("com.shop", label = "Buy")
        assertEquals(Decision.APPROVAL_REQUIRED, d.state)
        // Polling does not stack a second question.
        assertEquals(d.approvalId, check("com.shop", label = "Buy").approvalId)
        assertEquals(1, host.asked.size)
        assertEquals("pending", engine.approvalStatus(d.approvalId!!))

        engine.answer(d.approvalId!!, "once")
        assertEquals("approved", engine.approvalStatus(d.approvalId!!))
        // The once grant was used up by the status read.
        assertEquals(Decision.APPROVAL_REQUIRED, check("com.shop").state)

        val d2 = check("com.shop")
        engine.answer(d2.approvalId!!, "task")
        engine.approvalStatus(d2.approvalId!!)
        assertTrue(check("com.shop").allowed)
        assertTrue(check("com.shop").allowed)
        assertEquals(Decision.APPROVAL_REQUIRED, check("com.shop", task = "t2").state)

        now += PolicyEngine.APPROVAL_TTL_MS + 1
        assertEquals(Decision.APPROVAL_REQUIRED, check("com.shop").state)
    }

    @Test fun askExpires() {
        engine.setRule("com.shop", PolicyEngine.ASK, byOwner = true)
        val d = check("com.shop")
        now += PolicyEngine.APPROVAL_TTL_MS + 1
        assertEquals("expired", engine.approvalStatus(d.approvalId!!))
    }

    @Test fun selfPackageSkipsRules() = assertTrue(check("ai.ubiq.app").allowed)

    @Test fun effects() {
        assertEquals(Effect.READ, Effect.of("get_screen", "Delete"))
        assertEquals(Effect.DESTRUCTIVE, Effect.of("tap", "Delete chat"))
        assertEquals(Effect.EXTERNAL, Effect.of("tap", "Send"))
        assertEquals(Effect.EXTERNAL, Effect.of("type_text", "", submits = true))
        assertEquals(Effect.WRITE, Effect.of("tap", "Next"))
        assertFalse(Effect.gated("read_notifications"))
    }

    @Test fun settingsDenylist() {
        assertTrue(Floors.settingDenied("adb_enabled"))
        assertTrue(Floors.settingDenied("enabled_accessibility_services"))
        assertFalse(Floors.settingDenied("screen_brightness"))
    }
}
