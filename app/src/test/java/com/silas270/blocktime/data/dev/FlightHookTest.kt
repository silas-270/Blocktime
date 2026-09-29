package com.silas270.blocktime.data.dev

import com.silas270.blocktime.data.dev.FlightHook.Leg
import com.silas270.blocktime.data.dev.FlightHook.Result
import com.silas270.blocktime.data.model.FlightMode
import com.silas270.blocktime.data.model.FlightRoute
import com.silas270.blocktime.testutil.testAirport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The flight list the adb hook takes, and the logbook rows it becomes. */
class FlightHookTest {

    private val airports = mapOf(
        "JFK" to testAirport("JFK", 40.64, -73.78, "NA", "US"),
        "HND" to testAirport("HND", 35.55, 139.78, "AS", "JP"),
        "BOS" to testAirport("BOS", 42.36, -71.01, "NA", "US"),
    )

    private val jfkBos = FlightRoute(1, "JFK", "BOS", 300.0, 83, "", "Boston", "Boston", "US", 42.36, -71.01)

    @Test
    fun `a list parses in order, trimmed and upper-cased`() {
        assertEquals(listOf(Leg("JFK", "HND"), Leg("LHR", "SYD")), FlightHook.parse(" jfk-hnd , LHR - syd,"))
    }

    @Test
    fun `any malformed entry refuses the whole list`() {
        assertNull(FlightHook.parse("JFK-HND,JFKHND"))
        assertNull(FlightHook.parse("JFK-HND-LAX"))
        assertNull(FlightHook.parse("JFK-JFK"))
        assertNull(FlightHook.parse("JF-HND"))
        assertNull(FlightHook.parse(""))
    }

    @Test
    fun `a known route takes its distance and duration, an unknown pair the great circle`() {
        val result = FlightHook.toFlightLogs(
            listOf(Leg("JFK", "BOS"), Leg("JFK", "HND")), userId = 7, mode = FlightMode.STORY, now = 10_000_000L,
            airport = airports::get,
            route = { o, d -> jfkBos.takeIf { o == "JFK" && d == "BOS" } },
        ) as Result.Flights

        val (bos, hnd) = result.logs
        assertEquals(300.0 to 83, bos.distanceKm to bos.durationMin)
        assertEquals(10_850.0, hnd.distanceKm, 100.0)
        assertEquals(814.0, hnd.durationMin.toDouble(), 10.0)
        // In the order given, a minute apart, the last one a minute ago.
        assertEquals(listOf(10_000_000L - 120_000L, 10_000_000L - 60_000L), result.logs.map { it.completedAt })
        assertEquals(7, bos.userId)
        assertEquals(FlightMode.STORY, hnd.mode)
    }

    @Test
    fun `an unknown airport adds nothing and names the code`() {
        val result = FlightHook.toFlightLogs(
            listOf(Leg("JFK", "BOS"), Leg("JFK", "XXX")), 1, FlightMode.FREE, 0L, airports::get, { _, _ -> null },
        )
        assertEquals(Result.UnknownAirport("XXX"), result)
    }
}
