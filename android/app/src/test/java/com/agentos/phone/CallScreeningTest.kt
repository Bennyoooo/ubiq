package com.agentos.phone

import com.agentos.phone.CallScreening.Decision
import org.junit.Assert.assertEquals
import org.junit.Test

class CallScreeningTest {
    @Test fun offMeansEveryCallRings() {
        assertEquals(Decision.ALLOW, CallScreening.decide(enabled = false, known = false, busy = false, canTalk = true))
    }

    @Test fun contactsRingAsUsual() {
        assertEquals(Decision.ALLOW, CallScreening.decide(enabled = true, known = true, busy = false, canTalk = true))
    }

    @Test fun unknownCallersAreScreenedWhenUbiqCanTalk() {
        assertEquals(Decision.SCREEN, CallScreening.decide(enabled = true, known = false, busy = false, canTalk = true))
    }

    @Test fun withoutCallAudioUnknownCallersRingSilently() {
        assertEquals(Decision.SILENCE, CallScreening.decide(enabled = true, known = false, busy = false, canTalk = false))
    }

    @Test fun aCallWhileBusyRingsAsCallWaiting() {
        // Answering it would put the owner's current call (or the one Ubiq is screening) on hold.
        assertEquals(Decision.ALLOW, CallScreening.decide(enabled = true, known = false, busy = true, canTalk = true))
    }

    @Test fun muLawRoundTripsCloseEnough() {
        for (s in listOf(0, 100, -100, 1000, -1000, 12000, -12000, 32000, -32000)) {
            val back = MuLaw.decode(MuLaw.encode(s.toShort())).toInt()
            val tol = maxOf(16, kotlin.math.abs(s) / 16)
            assert(kotlin.math.abs(back - s) <= tol) { "$s -> $back" }
        }
    }
}
