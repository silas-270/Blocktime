package com.silas270.blocktime.data.dev

import com.silas270.blocktime.data.model.Airport
import com.silas270.blocktime.data.model.ChallengeProgress
import com.silas270.blocktime.data.model.FlightLog
import com.silas270.blocktime.data.model.FlightMode
import com.silas270.blocktime.data.model.FlightRoute
import com.silas270.blocktime.domain.flightNumberFor
import kotlin.math.roundToInt

/**
 * The adb-only flight hook: adds flights to the logbook from a computer, in every build type
 * while `flightHookEnabled` in app/build.gradle.kts is true. See [FlightHookReceiver] for the
 * command and for why only adb can reach it.
 *
 * The flights go into the logbook and nowhere else: they draw routes on the globe and count
 * toward the Passport and achievements, but they credit no challenge, move no pilot and are never
 * sent to a shared room, so a crew cannot be cheated with them.
 */
object FlightHook {
    /** Typical jet cruise speed, km/h, for a pair the routes database does not carry. */
    private const val CRUISE_SPEED_KMH = 800.0
    private const val MIN_DURATION_MIN = 30

    /** A flight as the command names it, `JFK-HND`. */
    data class Leg(val originIata: String, val destIata: String)

    /**
     * `"JFK-HND, LHR-SYD"` as legs, in order. Codes are trimmed and upper-cased. Null when any
     * entry is not two three-letter codes joined by `-`, so a typo adds nothing rather than half
     * the list.
     */
    fun parse(flights: String): List<Leg>? {
        val legs = flights.split(',').map { it.trim() }.filter { it.isNotEmpty() }.map { entry ->
            val parts = entry.split('-').map { it.trim().uppercase() }
            if (parts.size != 2 || parts.any { !it.matches(Regex("[A-Z]{3}")) } || parts[0] == parts[1]) return null
            Leg(parts[0], parts[1])
        }
        return legs.ifEmpty { null }
    }

    /**
     * The logbook rows for [legs], or the first code that does not resolve. Distance and duration
     * come from the routes database when it carries the pair and from the great circle at cruise
     * speed otherwise, as a booking would. The flights land one minute apart and the last one a
     * minute before [now], in the order given.
     */
    fun toFlightLogs(
        legs: List<Leg>,
        userId: Int,
        mode: FlightMode,
        now: Long,
        airport: (String) -> Airport?,
        route: (String, String) -> FlightRoute?,
    ): Result {
        val logs = legs.mapIndexed { index, leg ->
            val known = route(leg.originIata, leg.destIata)
            val (distanceKm, durationMin) = if (known != null) {
                known.distanceKm to known.durationMin
            } else {
                val origin = airport(leg.originIata) ?: return Result.UnknownAirport(leg.originIata)
                val dest = airport(leg.destIata) ?: return Result.UnknownAirport(leg.destIata)
                val km = ChallengeProgress.haversineKm(origin.lat, origin.lon, dest.lat, dest.lon)
                km to ((km / CRUISE_SPEED_KMH) * 60).roundToInt().coerceAtLeast(MIN_DURATION_MIN)
            }
            FlightLog(
                userId = userId,
                flightNumber = flightNumberFor(leg.destIata),
                originIata = leg.originIata,
                destIata = leg.destIata,
                durationMin = durationMin,
                distanceKm = distanceKm,
                completedAt = now - (legs.size - index) * 60_000L,
                mode = mode,
            )
        }
        return Result.Flights(logs)
    }

    sealed interface Result {
        data class Flights(val logs: List<FlightLog>) : Result
        data class UnknownAirport(val iata: String) : Result
    }
}
