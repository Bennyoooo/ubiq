package com.agentos.notify

import com.agentos.core.api.ZenModes
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ZenModesTest {
    @Test fun describesWhatGetsThrough() {
        val a = JSONObject().put("calls", "starred").put("messages", "none").put("repeat_callers", true)
            .put("media", true).put("touch_sounds", false).put("notifications", "hidden")
        assertEquals("Calls from starred contacts, messages from no one. Allowed: repeat callers, media. Silenced: touch sounds. " +
            "Silenced notifications are hidden.", ZenModes.describeAllow("priority", a))
    }

    @Test fun levelsAndEmptyAllow() {
        assertEquals("Only alarms get through.", ZenModes.describeAllow("alarms_only", null))
        assertTrue(ZenModes.describeAllow("total_silence", null).startsWith("Total silence"))
        assertTrue(ZenModes.describeAllow("priority", JSONObject()).contains("usual Do Not Disturb"))
    }

    @Test fun unknownAllowKeysAreNamed() {
        assertNull(ZenModes.unknownKeys(JSONObject().put("calls", "none")))
        assertTrue(ZenModes.unknownKeys(JSONObject().put("calls", "none").put("sms", true))!!.startsWith("unknown allow keys sms"))
    }

    @Test fun typesAppsMayUse() {
        assertEquals(3, ZenModes.typeFrom("bedtime"))
        assertEquals(0, ZenModes.typeFrom(""))
        assertNull(ZenModes.typeFrom("managed"))
        assertEquals("driving", ZenModes.typeName(4))
    }
}
