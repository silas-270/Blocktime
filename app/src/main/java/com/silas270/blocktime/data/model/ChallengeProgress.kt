package com.silas270.blocktime.data.model

import java.time.LocalDate
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Pure progress math for docs/challenges.md's three types - no Room/AirportRepository/JNI
 * dependency, so it's directly unit-testable (see ChallengeProgressTest) independent of the
 * repository layer that calls into it.
 */
object ChallengeProgress {
    private const val EARTH_RADIUS_KM = 6371.0

    /** Great-circle distance between two lat/lon points, in km. */
    fun haversineKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val sinDLat = sin(dLat / 2)
        val sinDLon = sin(dLon / 2)
        val a = sinDLat * sinDLat +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sinDLon * sinDLon
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return EARTH_RADIUS_KM * c
    }

    /**
     * docs/challenges.md's Route progress formula:
     *
     * ```
     * progress = 1 - ( straight_line(current, destination) / straight_line(origin, destination) )
     * ```
     *
     * Clamped to 0f..1f. Deliberately allowed to legitimately *decrease* when a real onward
     * flight happens to point away from the destination - an accepted v1 limitation (straight
     * line is only a proxy for real routing), not a bug to fix here.
     */
    fun routeProgress(
        originLat: Double,
        originLon: Double,
        destLat: Double,
        destLon: Double,
        currentLat: Double,
        currentLon: Double
    ): Float {
        val originToDest = haversineKm(originLat, originLon, destLat, destLon)
        if (originToDest <= 0.0) return 1f
        val currentToDest = haversineKm(currentLat, currentLon, destLat, destLon)
        return (1.0 - (currentToDest / originToDest)).coerceIn(0.0, 1.0).toFloat()
    }
}

/**
 * The UI-facing 0f..1f progress for any challenge, per its own type's metric (challenges.md).
 * Kept as an extension function rather than a [Challenge] member/computed property so Room's
 * entity field-scanning never mistakes it for a column.
 */
fun Challenge.progressFraction(): Float = when (type) {
    // A predefined route is scored by kilometres flown along its itinerary, not by the
    // straight-line proxy - that is the whole point of the submode (see PredefinedRoute.progressAt).
    // Derived here rather than trusted from the cached column so a catalog edit that changes an
    // itinerary can never leave a live challenge reporting against legs or distances that no longer
    // exist.
    ChallengeType.ROUTE -> predefinedRoute()
        ?.takeIf { it.legCount > 0 }
        ?.progressAt(legIndex)
        ?: routeProgressFraction
    ChallengeType.SET_COMPLETION ->
        if (setTotalMembers > 0) (visitedSetMembers.size.toFloat() / setTotalMembers).coerceIn(0f, 1f) else 0f
    ChallengeType.DISTANCE ->
        targetDistanceKm?.takeIf { it > 0 }
            ?.let { (cumulativeDistanceKm / it).toFloat().coerceIn(0f, 1f) }
            ?: 0f
    ChallengeType.STREAK ->
        targetDays?.takeIf { it > 0 }
            ?.let { (streakDays.toFloat() / it).coerceIn(0f, 1f) }
            ?: 0f
}

/**
 * The streak as of [today], which is not always what [Challenge.streakDays] says.
 *
 * The row records the past: the last day a flight was credited, and how long the run was as of
 * that day. Whether the run is still alive is a question about *today*, and nothing runs at
 * midnight to answer it - so a pilot who flew Monday and Tuesday and then skipped Wednesday still
 * has `streakDays = 2` sitting in the database on Thursday. This is where that gets resolved: a
 * run is alive only if its last day was today (already flown) or yesterday (still time to keep
 * it), and is otherwise dead at zero.
 *
 * Callers should not need to reach for this directly - `LocalChallengeRepository` applies it on
 * the way out of the database so everything downstream sees an already-correct row.
 */
fun Challenge.currentStreak(today: LocalDate): Int =
    lastFlownDay
        ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        // `>=` rather than "is today or yesterday": a last-flown day *ahead* of today is not
        // stale, whatever else it is. That happens on a device clock moved backwards or a
        // timezone shift westward, and zeroing a live streak over it would destroy real progress
        // to punish a condition the pilot did not cause.
        ?.takeIf { !it.isBefore(today.minusDays(1)) }
        ?.let { streakDays }
        ?: 0

/**
 * This row with its streak resolved against [today]. A no-op for every other type.
 *
 * The returned [Challenge] deliberately differs from what is stored - it is the same facts read
 * through the clock. The stored row catches up on the next credited flight.
 *
 * Completed challenges are left alone: a finished streak's [Challenge.streakDays] is the final
 * score, not a live count, and decaying it afterwards would rewrite history in the completed log.
 */
fun Challenge.withStreakEvaluatedAt(today: LocalDate): Challenge =
    if (type != ChallengeType.STREAK || status != ChallengeStatus.ACTIVE) this
    else currentStreak(today).let { if (it == streakDays) this else copy(streakDays = it) }

/**
 * This row with its Set-completion totals resolved against the *current* curated definition. A
 * no-op for every other type, and for finished challenges.
 *
 * The row stores `setTotalMembers` at start time, but completion has always been judged against
 * the live definition. When a definition shrinks (Visit All Continents dropped the unreachable
 * Antarctica), a row started earlier would otherwise keep reading "x / 7" while completing at 6.
 * Like [withStreakEvaluatedAt], this is the same facts read through today's catalog; the stored
 * row catches up on the next credited landing.
 */
fun Challenge.withSetDefinitionResolved(): Challenge {
    if (type != ChallengeType.SET_COMPLETION || status != ChallengeStatus.ACTIVE) return this
    val definition = setCatalogId?.let { CuratedChallengeSets.find(it) } ?: return this
    val visited = visitedSetMembers.filterTo(LinkedHashSet()) { it in definition.members }
    return if (definition.members.size == setTotalMembers && visited == visitedSetMembers) this
    else copy(setTotalMembers = definition.members.size, visitedSetMembers = visited)
}

/**
 * Resolves the member checklist for a Set Completion challenge, ticking the members in [visited]:
 * the pilot's own by default, the crew's union ([teamVisitedMembers]) for a shared pool, so the
 * checklist agrees with the "2/6 visited" beside it.
 * Returns null if the challenge is not a Set Completion challenge or its set definition is unknown.
 */
fun Challenge.resolveSetMemberProgress(visited: Set<String> = visitedSetMembers): List<SetMemberProgress>? {
    if (type != ChallengeType.SET_COMPLETION) return null
    val definition = setCatalogId?.let { CuratedChallengeSets.find(it) } ?: return null
    return definition.memberItems
        .map { member ->
            SetMemberProgress(
                id = member.id,
                displayName = member.displayName,
                isVisited = visited.contains(member.id)
            )
        }
        .visitedFirst()
}
