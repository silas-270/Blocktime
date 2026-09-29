package com.silas270.blocktime.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * Pure-function coverage for [ChallengeProgress] and [progressFraction] - no Room/repository
 * involved, per docs/challenges.md's Route progress formula and the per-type metrics for
 * Set-completion/Distance.
 */
class ChallengeProgressTest {

    private fun challenge(
        type: ChallengeType,
        routeProgressFraction: Float = 0f,
        setTotalMembers: Int = 0,
        visitedSetMembers: Set<String> = emptySet(),
        targetDistanceKm: Double? = null,
        cumulativeDistanceKm: Double = 0.0,
        targetDays: Int? = null,
        streakDays: Int = 0,
        lastFlownDay: String? = null,
        predefinedRouteId: String? = null,
        legIndex: Int = 0,
        status: ChallengeStatus = ChallengeStatus.ACTIVE
    ) = Challenge(
        userId = 1,
        type = type,
        source = ChallengeSource.CURATED,
        name = "Test",
        routeProgressFraction = routeProgressFraction,
        setTotalMembers = setTotalMembers,
        visitedSetMembers = visitedSetMembers,
        targetDistanceKm = targetDistanceKm,
        cumulativeDistanceKm = cumulativeDistanceKm,
        targetDays = targetDays,
        streakDays = streakDays,
        lastFlownDay = lastFlownDay,
        predefinedRouteId = predefinedRouteId,
        legIndex = legIndex,
        status = status
    )

    // ── haversineKm ──────────────────────────────────────────────────────────────────────

    @Test
    fun `haversine distance between a point and itself is zero`() {
        assertEquals(0.0, ChallengeProgress.haversineKm(51.47, -0.45, 51.47, -0.45), 0.0001)
    }

    @Test
    fun `haversine distance is symmetric`() {
        val a = ChallengeProgress.haversineKm(0.0, 0.0, 0.0, 100.0)
        val b = ChallengeProgress.haversineKm(0.0, 100.0, 0.0, 0.0)
        assertEquals(a, b, 0.0001)
    }

    // ── routeProgress ────────────────────────────────────────────────────────────────────

    @Test
    fun `progress is zero when current position equals origin`() {
        val progress = ChallengeProgress.routeProgress(
            originLat = 0.0, originLon = 0.0,
            destLat = 0.0, destLon = 100.0,
            currentLat = 0.0, currentLon = 0.0
        )
        assertEquals(0f, progress, 0.001f)
    }

    @Test
    fun `progress is one when current position equals destination`() {
        val progress = ChallengeProgress.routeProgress(
            originLat = 0.0, originLon = 0.0,
            destLat = 0.0, destLon = 100.0,
            currentLat = 0.0, currentLon = 100.0
        )
        assertEquals(1f, progress, 0.001f)
    }

    @Test
    fun `progress is roughly one half at the midpoint`() {
        val progress = ChallengeProgress.routeProgress(
            originLat = 0.0, originLon = 0.0,
            destLat = 0.0, destLon = 100.0,
            currentLat = 0.0, currentLon = 50.0
        )
        assertEquals(0.5f, progress, 0.01f)
    }

    @Test
    fun `progress can legitimately decrease when a leg points away from the destination`() {
        // docs/challenges.md's accepted v1 limitation: a straight-line proxy, not real
        // routing, so a real onward flight that happens to point away from the destination makes
        // the percentage go down, not up.
        val closer = ChallengeProgress.routeProgress(
            originLat = 0.0, originLon = 0.0,
            destLat = 0.0, destLon = 100.0,
            currentLat = 0.0, currentLon = 50.0
        )
        val fartherAgain = ChallengeProgress.routeProgress(
            originLat = 0.0, originLon = 0.0,
            destLat = 0.0, destLon = 100.0,
            currentLat = 0.0, currentLon = 30.0
        )
        assertTrue("expected progress to decrease from $closer to $fartherAgain", fartherAgain < closer)
    }

    @Test
    fun `progress is clamped to zero when current is farther from destination than origin`() {
        val progress = ChallengeProgress.routeProgress(
            originLat = 0.0, originLon = 0.0,
            destLat = 0.0, destLon = 100.0,
            currentLat = 0.0, currentLon = -50.0 // behind the origin, away from the destination
        )
        assertEquals(0f, progress, 0.001f)
    }

    @Test
    fun `origin equal to destination is treated as already complete`() {
        val progress = ChallengeProgress.routeProgress(
            originLat = 10.0, originLon = 10.0,
            destLat = 10.0, destLon = 10.0,
            currentLat = 5.0, currentLon = 5.0
        )
        assertEquals(1f, progress, 0.001f)
    }

    // ── progressFraction() per type ──────────────────────────────────────────────────────

    @Test
    fun `route progress fraction reads the cached routeProgressFraction field`() {
        val c = challenge(ChallengeType.ROUTE, routeProgressFraction = 0.42f)
        assertEquals(0.42f, c.progressFraction(), 0.001f)
    }

    @Test
    fun `a predefined route is scored by distance flown, not by the cached fraction`() {
        // The cached column deliberately disagrees here: the itinerary is the authority for this
        // submode, so a stale or wrong cache must not be what the pilot sees.
        val route = PredefinedRouteCatalog.PACIFIC_RIM
        val c = challenge(
            ChallengeType.ROUTE,
            routeProgressFraction = 0.99f,
            predefinedRouteId = route.id,
            legIndex = 2
        )
        // SIN-BKK-HKG is 1,409 + 1,689 km of an 11,770 km loop - about 26%, where counting legs
        // would have paid 40% for the two shortest hops on the route.
        assertEquals((3098.0 / route.totalDistanceKm).toFloat(), c.progressFraction(), 0.001f)
        assertTrue(c.progressFraction() < 2f / 5)
    }

    @Test
    fun `a long leg is worth more than a short one on the same itinerary`() {
        val route = PredefinedRouteCatalog.PACIFIC_RIM
        val afterShortestLeg = challenge(ChallengeType.ROUTE, predefinedRouteId = route.id, legIndex = 1)
        val beforeAndAfterLongest = listOf(4, 5).map { legs ->
            challenge(ChallengeType.ROUTE, predefinedRouteId = route.id, legIndex = legs).progressFraction()
        }

        val longestLegWorth = beforeAndAfterLongest[1] - beforeAndAfterLongest[0]
        assertTrue(
            "the 5,349 km flight home must outweigh the 1,409 km opening hop",
            longestLegWorth > afterShortestLeg.progressFraction()
        )
    }

    @Test
    fun `the final leg of a predefined route reaches exactly one`() {
        val route = PredefinedRouteCatalog.PACIFIC_RIM
        val c = challenge(ChallengeType.ROUTE, predefinedRouteId = route.id, legIndex = route.legCount)
        assertEquals(1f, c.progressFraction(), 0.0001f)
    }

    @Test
    fun `an itinerary authored without distances falls back to counting legs`() {
        val route = PredefinedRoute(id = "x", waypoints = listOf("AAA", "BBB", "CCC", "DDD"))
        assertEquals(0f, route.progressAt(0), 0.001f)
        assertEquals(1f / 3, route.progressAt(1), 0.001f)
        assertEquals(1f, route.progressAt(3), 0.001f)
    }

    @Test
    fun `a predefined route at leg zero reads as zero even when it is a circuit`() {
        // The case the geometric proxy gets wrong: PACIFIC_RIM starts and ends at SIN, so
        // origin == destination and routeProgress() would report a finished challenge.
        val c = challenge(ChallengeType.ROUTE, predefinedRouteId = PredefinedRouteCatalog.PACIFIC_RIM.id)
        assertEquals(0f, c.progressFraction(), 0.001f)
    }

    @Test
    fun `an unknown predefined route id falls back to the cached fraction rather than crashing`() {
        val c = challenge(ChallengeType.ROUTE, routeProgressFraction = 0.3f, predefinedRouteId = "no_such_route", legIndex = 4)
        assertEquals(0.3f, c.progressFraction(), 0.001f)
    }

    @Test
    fun `set-completion progress fraction is visited over total`() {
        val c = challenge(ChallengeType.SET_COMPLETION, setTotalMembers = 4, visitedSetMembers = setOf("EU", "AS"))
        assertEquals(0.5f, c.progressFraction(), 0.001f)
    }

    @Test
    fun `set-completion progress fraction is zero when total is zero (avoids div by zero)`() {
        val c = challenge(ChallengeType.SET_COMPLETION, setTotalMembers = 0)
        assertEquals(0f, c.progressFraction(), 0.001f)
    }

    @Test
    fun `distance progress fraction is cumulative over target`() {
        val c = challenge(ChallengeType.DISTANCE, targetDistanceKm = 10000.0, cumulativeDistanceKm = 2500.0)
        assertEquals(0.25f, c.progressFraction(), 0.001f)
    }

    @Test
    fun `distance progress fraction clamps at one past the target`() {
        val c = challenge(ChallengeType.DISTANCE, targetDistanceKm = 10000.0, cumulativeDistanceKm = 15000.0)
        assertEquals(1f, c.progressFraction(), 0.001f)
    }

    // ── STREAK progressFraction ──────────────────────────────────────────────────────────

    @Test
    fun `streak progress fraction is days over target`() {
        val c = challenge(ChallengeType.STREAK, targetDays = 4, streakDays = 3)
        assertEquals(0.75f, c.progressFraction(), 0.001f)
    }

    @Test
    fun `streak progress fraction is zero when the target is missing or zero`() {
        assertEquals(0f, challenge(ChallengeType.STREAK, streakDays = 3).progressFraction(), 0.001f)
        assertEquals(0f, challenge(ChallengeType.STREAK, targetDays = 0, streakDays = 3).progressFraction(), 0.001f)
    }

    @Test
    fun `streak progress fraction clamps at one past the target`() {
        val c = challenge(ChallengeType.STREAK, targetDays = 3, streakDays = 5)
        assertEquals(1f, c.progressFraction(), 0.001f)
    }

    // ── currentStreak: the run resolved against today ────────────────────────────────────

    private val today: LocalDate = LocalDate.of(2026, 3, 10)

    @Test
    fun `a streak flown today is alive`() {
        val c = challenge(ChallengeType.STREAK, targetDays = 4, streakDays = 2, lastFlownDay = "2026-03-10")
        assertEquals(2, c.currentStreak(today))
    }

    @Test
    fun `a streak flown yesterday is alive - there is still time to extend it`() {
        val c = challenge(ChallengeType.STREAK, targetDays = 4, streakDays = 2, lastFlownDay = "2026-03-09")
        assertEquals(2, c.currentStreak(today))
    }

    @Test
    fun `a streak whose last day is two days ago is dead`() {
        val c = challenge(ChallengeType.STREAK, targetDays = 4, streakDays = 2, lastFlownDay = "2026-03-08")
        assertEquals(0, c.currentStreak(today))
    }

    @Test
    fun `a last-flown day ahead of today is treated as alive, not stale`() {
        // A backwards clock change or a westward timezone shift can produce this. Zeroing a live
        // streak over it would destroy real progress for something the pilot did not do.
        val c = challenge(ChallengeType.STREAK, targetDays = 4, streakDays = 2, lastFlownDay = "2026-03-12")
        assertEquals(2, c.currentStreak(today))
    }

    @Test
    fun `a streak with no flights yet is zero`() {
        assertEquals(0, challenge(ChallengeType.STREAK, targetDays = 4).currentStreak(today))
    }

    @Test
    fun `an unparseable stored date reads as zero rather than throwing`() {
        val c = challenge(ChallengeType.STREAK, targetDays = 4, streakDays = 2, lastFlownDay = "not-a-date")
        assertEquals(0, c.currentStreak(today))
    }

    // ── withStreakEvaluatedAt ────────────────────────────────────────────────────────────

    @Test
    fun `evaluating collapses a dead streak but leaves other types untouched`() {
        val dead = challenge(ChallengeType.STREAK, targetDays = 4, streakDays = 2, lastFlownDay = "2026-03-01")
        assertEquals(0, dead.withStreakEvaluatedAt(today).streakDays)

        val distance = challenge(ChallengeType.DISTANCE, targetDistanceKm = 100.0, cumulativeDistanceKm = 50.0)
        assertEquals(distance, distance.withStreakEvaluatedAt(today))
    }

    @Test
    fun `a completed streak keeps its final score instead of decaying`() {
        // The completed log shows what was achieved; decaying it afterwards would rewrite history.
        val done = challenge(
            ChallengeType.STREAK,
            targetDays = 3,
            streakDays = 3,
            lastFlownDay = "2026-03-01",
            status = ChallengeStatus.COMPLETED
        )
        assertEquals(3, done.withStreakEvaluatedAt(today).streakDays)
    }

    // ── resolveSetMemberProgress ─────────────────────────────────────────────────────────

    @Test
    fun `resolveSetMemberProgress returns null for non-set challenges`() {
        val route = challenge(ChallengeType.ROUTE)
        org.junit.Assert.assertNull(route.resolveSetMemberProgress())
    }

    @Test
    fun `resolveSetMemberProgress ticks the members it is given, such as a crew's union`() {
        val continents = Challenge(
            userId = 1,
            type = ChallengeType.SET_COMPLETION,
            source = ChallengeSource.CURATED,
            name = "All Continents",
            setCatalogId = "all_continents",
            setMemberKind = SetMemberKind.CONTINENT,
            setTotalMembers = 6,
            visitedSetMembers = emptySet()
        )
        val progress = continents.resolveSetMemberProgress(setOf("EU", "AS"))!!
        assertEquals(setOf("EU", "AS"), progress.filter { it.isVisited }.map { it.id }.toSet())
    }

    @Test
    fun `resolveSetMemberProgress marks visited members correctly for all continents`() {
        val continents = Challenge(
            userId = 1,
            type = ChallengeType.SET_COMPLETION,
            source = ChallengeSource.CURATED,
            name = "All Continents",
            setCatalogId = "all_continents",
            setMemberKind = SetMemberKind.CONTINENT,
            setTotalMembers = 7,
            visitedSetMembers = setOf("EU", "AF")
        )
        val progress = continents.resolveSetMemberProgress()
        org.junit.Assert.assertNotNull(progress)
        assertEquals(6, progress!!.size) // Antarctica is not a member: no route reaches it

        val eu = progress.find { it.id == "EU" }
        org.junit.Assert.assertNotNull(eu)
        assertEquals("Europe", eu!!.displayName)
        assertTrue(eu.isVisited)

        val af = progress.find { it.id == "AF" }
        org.junit.Assert.assertNotNull(af)
        assertEquals("Africa", af!!.displayName)
        assertTrue(af.isVisited)

        val na = progress.find { it.id == "NA" }
        org.junit.Assert.assertNotNull(na)
        assertEquals("North America", na!!.displayName)
        org.junit.Assert.assertFalse(na.isVisited)
    }
}

class SetDefinitionResolutionTest {
    @org.junit.Test
    fun `a set row started with seven continents resolves against the current six`() {
        val row = Challenge(
            userId = 1,
            type = ChallengeType.SET_COMPLETION,
            source = ChallengeSource.CURATED,
            name = "Visit All Continents",
            setCatalogId = CuratedChallengeSets.ALL_CONTINENTS.catalogId,
            setMemberKind = SetMemberKind.CONTINENT,
            setTotalMembers = 7,
            visitedSetMembers = setOf("EU", "AS")
        )
        val resolved = row.withSetDefinitionResolved()
        org.junit.Assert.assertEquals(6, resolved.setTotalMembers)
        org.junit.Assert.assertEquals(setOf("EU", "AS"), resolved.visitedSetMembers)
        org.junit.Assert.assertFalse(CuratedChallengeSets.ALL_CONTINENTS.members.contains("AN"))
    }
}
