package com.agentos.agent

import com.agentos.connect.Forecast
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The express lane's templates and route learning (agent/Express, agent/Routes). */
class SpeedPathsTest {
    private val amazon = "com.amazon.mShop.android.shopping"

    private fun step(tool: String, label: String = "", changed: Boolean = true, receipt: Boolean = false, pkg: String = amazon, args: JSONObject = JSONObject()) =
        Routes.Step(tool, pkg, label, "", "", args, changed, receipt)

    @Test fun routeKeepsTheStepsAfterTheLastLaunch() {
        val log = listOf(
            step("open_app", pkg = "com.google.android.gm"),
            step("tap", "Inbox", pkg = "com.google.android.gm"),
            step("open_app"),
            step("tap", "Menu"),
            step("tap", "Your Orders"),
        )
        val r = Routes.compile(log, amazon, "get me my orders from amazon")!!
        assertEquals(listOf("Menu", "Your Orders"), r.map { it.label })
    }

    @Test fun deadEndsAreDropped() {
        val log = listOf(
            step("open_app"),
            step("tap", "Account"),
            step("tap", "Settings"),          // wrong way...
            step("press", args = JSONObject().put("key", "back")),   // ...undone
            step("tap", "Banner", changed = false),                    // did nothing
            step("tap", "Your Orders"),
        )
        assertEquals(listOf("Account", "Your Orders"), Routes.compile(log, amazon, "my amazon orders")!!.map { it.label })
    }

    @Test fun routesStopBeforeAnythingWithAReceipt() {
        val log = listOf(step("open_app"), step("tap", "Cart"), step("tap", "Proceed to checkout"), step("tap", "Place your order", receipt = true), step("tap", "Done"))
        assertEquals(listOf("Cart", "Proceed to checkout"), Routes.compile(log, amazon, "order it")!!.map { it.label })
    }

    @Test fun deepLinksAndOneTapPathsAreNotRoutes() {
        assertNull(Routes.compile(listOf(step("open_link"), step("tap", "A"), step("tap", "B")), amazon, "x"))
        assertNull(Routes.compile(listOf(step("open_app"), step("tap", "Orders")), amazon, "x"))
        assertNull(Routes.compile(listOf(step("open_app"), step("tap", "A"), step("tap", "B", pkg = "com.other")), amazon, "x"))
    }

    @Test fun typedTextFromTheRequestBecomesASlot() {
        val log = listOf(step("open_app"), step("tap", "Search"), step("type_text", args = JSONObject().put("text", "usb-c cable").put("submit", true)), step("tap", "Filters"))
        val r = Routes.compile(log, amazon, "find usb-c cable on amazon")!!
        assertEquals(0, r[1].slot)
        assertEquals("type {value 0}", r[1].words())
        // Typed text the user never said is replayed as recorded.
        val fixed = Routes.compile(listOf(step("open_app"), step("tap", "Search"), step("type_text", args = JSONObject().put("text", "prime video"))), amazon, "my watchlist")!!
        assertEquals(-1, fixed[1].slot)
    }

    @Test fun routesRoundTripThroughJson() {
        val s = step("tap", "Your Orders", args = JSONObject().put("x", 1))
        val back = Routes.Step.from(JSONObject(s.json().toString()))
        assertEquals("Your Orders", back.label)
        assertEquals(1, back.args.getInt("x"))
    }

    private val forecast = Forecast.Report("Oakland", 61, 55, "partly cloudy", 9, "°F", "mph",
        listOf(Forecast.Hour("15:00", 62, "cloudy", 10), Forecast.Hour("18:00", 58, "rain", 70)),
        listOf(Forecast.Day("Today", "partly cloudy", 66, 52, 20), Forecast.Day("Tomorrow", "rain", 59, 50, 80), Forecast.Day("Friday", "clear", 70, 51, 0)))

    @Test fun weatherReplies() {
        assertEquals("It's 61°F and partly cloudy in Oakland, feels like 55°F. Today: high 66°, low 52°. Rain likely around 18:00.", forecast.spoken(0))
        assertEquals("Tomorrow in Oakland: rain, high 59°F, low 50°F, 80% chance of rain.", forecast.spoken(1))
        assertEquals("Friday in Oakland: clear, high 70°F, low 51°F, no chance of rain.", forecast.spoken(2, rainAsked = true))
        assertTrue(forecast.spoken(-1).startsWith("This week in Oakland: Tod partly cloudy, 66°/52°"))
        assertTrue(forecast.text().contains("18:00 58°F rain (70% rain)"))
    }

    @Test fun metNorwayFallbackParses() {
        // A real MET Norway reply for San Francisco (2026-11-18 06:00 UTC = 22:00 Pacific on the 17th), trimmed to 60 entries.
        val json = org.json.JSONObject(javaClass.getResource("/met-norway-sf.json")!!.readText())
        val zone = java.time.ZoneId.of("America/Los_Angeles")
        val r = Forecast.fromMet(json, 3, true, "San Francisco", zone, java.time.Instant.parse("2026-11-18T06:10:00Z"))
        assertEquals(55, r.temp)                 // 12.7 °C
        assertEquals("°F", r.unit)
        assertEquals("cloudy", r.words)
        assertEquals("22:00", r.hours.first().time)
        assertEquals(listOf("Today", "Tomorrow", "Thursday"), r.days.map { it.name })
        assertTrue(r.days.all { it.rain == -1 && it.mm >= 0 })
        assertTrue(r.days[0].high >= r.days[0].low)
        // Amounts instead of chances, in inches for °F.
        assertTrue(r.text(), r.text().contains("Tomorrow:"))
        val tomorrow = r.spoken(1, rainAsked = true)
        assertTrue(tomorrow, tomorrow.startsWith("Tomorrow in San Francisco:") && (tomorrow.contains(" in of rain") || tomorrow.contains("rain")))
    }

    @Test fun metNorwaySymbols() {
        assertEquals("partly cloudy", Forecast.metWords("partlycloudy_night"))
        assertEquals("showers", Forecast.metWords("lightrainshowers_day"))
        assertEquals("storms", Forecast.metWords("heavyrainandthunder"))
        assertEquals("rain", Forecast.metWords("lightrain"))
        assertEquals("clear", Forecast.metWords("clearsky_day"))
    }

    @Test fun rainAmountsWhenThereIsNoChance() {
        val r = Forecast.Report(null, 55, 55, "cloudy", 8, "°F", "mph", listOf(Forecast.Hour("22:00", 55, "rain", -1, 1.2)),
            listOf(Forecast.Day("Today", "rain", 58, 50, -1, 12.0), Forecast.Day("Tomorrow", "clear", 62, 49, -1, 0.0)))
        assertEquals("Tomorrow: clear, high 62°F, low 49°F, no rain expected.", r.spoken(1, rainAsked = true))
        assertEquals("Tomorrow: clear, high 62°F, low 49°F.", r.spoken(1))
        assertTrue(r.spoken(0), r.spoken(0).contains("about 0.5 in of rain"))
    }

    @Test fun expressGateIgnoresOtherMessages() {
        val gate = Express::class.java.getDeclaredField("GATE").apply { isAccessible = true }.get(Express) as Regex
        listOf("what's the weather tomorrow", "take me home", "play Blinding Lights", "directions to SFO", "will it rain later?").forEach { assertNotNull(it, gate.find(it)) }
        listOf("text mom I'm late", "what's on my calendar", "order more coffee").forEach { assertNull(it, gate.find(it)) }
    }
}
