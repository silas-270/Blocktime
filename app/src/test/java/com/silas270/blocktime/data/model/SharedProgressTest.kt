package com.silas270.blocktime.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rules of docs/shared-challenges.md "Team progress is derived, never stored", one test
 * each: own values from the row, the others from the cache, leavers' pooled contributions stay
 * while leavers drop out of the streak minimum and the race ranking, and the set union is read
 * through the current definition.
 */
class SharedProgressTest {

    private companion object {
        const val SELF = "SELF01"
        const val ANNA = "ANNA01"
        const val BOB = "BOB001"
    }

    private fun snapshot(
        code: String,
        colorIndex: Int,
        left: Boolean = false,
        distanceKm: Double = 0.0,
        visited: Set<String> = emptySet(),
        streakDays: Int = 0,
        routeProgress: Float = 0f
    ) = ParticipantSnapshot(
        userCode = code,
        username = code.lowercase(),
        colorIndex = colorIndex,
        left = left,
        distanceKm = distanceKm,
        visitedMembers = visited,
        streakDays = streakDays,
        routeProgress = routeProgress
    )

    private fun cache(
        type: ChallengeType,
        vararg participants: ParticipantSnapshot,
        outcome: SharedOutcome? = null
    ) = RoomStateCache(
        selfCode = SELF,
        room = RoomState(
            code = "ROOM01",
            definition = RoomDefinition(type = type, source = ChallengeSource.CUSTOM, name = "Shared"),
            participants = participants.toList(),
            outcome = outcome
        )
    )

    private fun distance(ownKm: Double, targetKm: Double, cache: RoomStateCache?, roomCode: String? = "ROOM01") = Challenge(
        id = 1,
        userId = 1,
        type = ChallengeType.DISTANCE,
        source = ChallengeSource.CUSTOM,
        name = "Distance",
        targetDistanceKm = targetKm,
        cumulativeDistanceKm = ownKm,
        roomCode = roomCode,
        roomState = cache
    )

    private fun streak(ownDays: Int, targetDays: Int, cache: RoomStateCache?) = Challenge(
        id = 1,
        userId = 1,
        type = ChallengeType.STREAK,
        source = ChallengeSource.CUSTOM,
        name = "Streak",
        targetDays = targetDays,
        streakDays = ownDays,
        roomCode = "ROOM01",
        roomState = cache
    )

    private fun set(ownVisited: Set<String>, cache: RoomStateCache?, status: ChallengeStatus = ChallengeStatus.ACTIVE) = Challenge(
        id = 1,
        userId = 1,
        type = ChallengeType.SET_COMPLETION,
        source = ChallengeSource.CURATED,
        status = status,
        name = "Continents",
        setCatalogId = CuratedChallengeSets.ALL_CONTINENTS.catalogId,
        setMemberKind = SetMemberKind.CONTINENT,
        // Started under an older definition that still listed Antarctica.
        setTotalMembers = 7,
        visitedSetMembers = ownVisited,
        roomCode = "ROOM01",
        roomState = cache
    )

    private fun route(ownProgress: Float, cache: RoomStateCache?, outcome: SharedOutcome? = null, roomCode: String? = "ROOM01") = Challenge(
        id = 1,
        userId = 1,
        type = ChallengeType.ROUTE,
        source = ChallengeSource.CUSTOM,
        name = "Race",
        originIata = "LHR",
        destIata = "SYD",
        positionIata = "DXB",
        routeProgressFraction = ownProgress,
        roomCode = roomCode,
        roomState = cache,
        sharedOutcome = outcome
    )

    @Test
    fun `isShared is a non-null room code`() {
        assertTrue(distance(0.0, 100.0, null).isShared())
        assertFalse(distance(0.0, 100.0, null, roomCode = null).isShared())
    }

    @Test
    fun `sync is pending only while the generations differ`() {
        val row = distance(0.0, 100.0, null)
        assertFalse(row.isSyncPending())
        assertTrue(row.copy(syncGeneration = 3, syncedGeneration = 2).isSyncPending())
        assertFalse(row.copy(syncGeneration = 3, syncedGeneration = 3).isSyncPending())
    }

    @Test
    fun `crew lists self first and leavers last`() {
        val row = distance(
            0.0, 100.0,
            cache(ChallengeType.DISTANCE, snapshot(ANNA, 0, left = true), snapshot(BOB, 1), snapshot(SELF, 2))
        )
        assertEquals(listOf(SELF, BOB, ANNA), row.crew().map { it.userCode })
    }

    @Test
    fun `others excludes self and leavers`() {
        val row = distance(
            0.0, 100.0,
            cache(ChallengeType.DISTANCE, snapshot(SELF, 0), snapshot(ANNA, 1, left = true), snapshot(BOB, 2))
        )
        assertEquals(listOf(BOB), row.others().map { it.userCode })
    }

    @Test
    fun `a row without a cache has no crew and reads its own progress as the team's`() {
        val row = distance(25.0, 100.0, null)
        assertTrue(row.crew().isEmpty())
        assertTrue(row.others().isEmpty())
        assertEquals(0.25f, row.teamProgressFraction(), 0.0001f)
        assertTrue(row.progressSegments().isEmpty())
    }

    @Test
    fun `team distance is the sum of self and others, leavers included`() {
        val row = distance(
            100.0, 1000.0,
            cache(
                ChallengeType.DISTANCE,
                snapshot(SELF, 0, distanceKm = 100.0),
                snapshot(ANNA, 1, distanceKm = 300.0),
                snapshot(BOB, 2, left = true, distanceKm = 200.0)
            )
        )
        assertEquals(600.0, row.teamDistanceKm(), 0.001)
        assertEquals(0.6f, row.teamProgressFraction(), 0.0001f)
    }

    @Test
    fun `own values come from the local row, not the cached self snapshot`() {
        // The row was credited at landing; the cached snapshot is what the server last saw.
        val row = distance(
            500.0, 1000.0,
            cache(ChallengeType.DISTANCE, snapshot(SELF, 0, distanceKm = 100.0), snapshot(ANNA, 1, distanceKm = 100.0))
        )
        assertEquals(600.0, row.teamDistanceKm(), 0.001)
        val selfSegment = row.progressSegments().single { it.isSelf }
        assertEquals(0.5f, selfSegment.fraction, 0.0001f)
    }

    @Test
    fun `group streak minimum excludes leavers`() {
        val row = streak(
            5, 7,
            cache(
                ChallengeType.STREAK,
                snapshot(SELF, 0, streakDays = 5),
                snapshot(ANNA, 1, streakDays = 4),
                snapshot(BOB, 2, left = true, streakDays = 1)
            )
        )
        assertEquals(4, row.teamStreakDays())
        assertEquals(4f / 7f, row.teamProgressFraction(), 0.0001f)
    }

    @Test
    fun `group streak minimum includes self`() {
        val row = streak(
            2, 7,
            cache(ChallengeType.STREAK, snapshot(SELF, 0, streakDays = 6), snapshot(ANNA, 1, streakDays = 5))
        )
        // Self's days come from the row (2), not from the stale cached snapshot (6).
        assertEquals(2, row.teamStreakDays())
    }

    @Test
    fun `set union is filtered against the current definition`() {
        val row = set(
            setOf("EU", "AN"),
            cache(
                ChallengeType.SET_COMPLETION,
                snapshot(SELF, 0, visited = setOf("EU")),
                snapshot(ANNA, 1, visited = setOf("AS", "EU", "XX")),
                snapshot(BOB, 2, left = true, visited = setOf("SA"))
            )
        )
        // Antarctica and XX are not members any more; Europe is counted once; the leaver's
        // South America stays in the pool.
        assertEquals(setOf("EU", "AS", "SA"), row.teamVisitedMembers())
        assertEquals(3f / 6f, row.teamProgressFraction(), 0.0001f)
    }

    @Test
    fun `progress segments follow join order, flag self and count each member once`() {
        val row = set(
            setOf("EU", "NA"),
            cache(
                ChallengeType.SET_COMPLETION,
                snapshot(ANNA, 0, visited = setOf("EU", "AS")),
                snapshot(SELF, 1, visited = setOf("EU"))
            )
        )
        val segments = row.progressSegments()
        assertEquals(listOf(ANNA, SELF), segments.map { it.participant.userCode })
        assertEquals(listOf(false, true), segments.map { it.isSelf })
        // Anna joined first, so Europe is hers; self adds only North America.
        assertEquals(2f / 6f, segments[0].fraction, 0.0001f)
        assertEquals(1f / 6f, segments[1].fraction, 0.0001f)
    }

    @Test
    fun `progress segments of a distance pool are each pilot's share of the target`() {
        val row = distance(
            250.0, 1000.0,
            cache(ChallengeType.DISTANCE, snapshot(SELF, 0), snapshot(ANNA, 1, distanceKm = 500.0))
        )
        val segments = row.progressSegments()
        assertEquals(listOf(0.25f, 0.5f), segments.map { it.fraction })
        assertEquals(listOf(true, false), segments.map { it.isSelf })
    }

    @Test
    fun `team progress of a race is own progress`() {
        val row = route(0.4f, cache(ChallengeType.ROUTE, snapshot(SELF, 0), snapshot(ANNA, 1, routeProgress = 0.9f)))
        assertEquals(0.4f, row.teamProgressFraction(), 0.0001f)
    }

    @Test
    fun `display progress is own progress for a race`() {
        val row = route(0.4f, cache(ChallengeType.ROUTE, snapshot(SELF, 0), snapshot(ANNA, 1, routeProgress = 0.9f)))
        assertEquals(0.4f, row.displayProgressFraction(), 0.0001f)
    }

    @Test
    fun `display progress is team progress for a shared pool and own progress for a solo row`() {
        val shared = distance(100.0, 1000.0, cache(ChallengeType.DISTANCE, snapshot(SELF, 0), snapshot(ANNA, 1, distanceKm = 400.0)))
        assertEquals(0.5f, shared.displayProgressFraction(), 0.0001f)
        assertEquals(0.1f, shared.progressFraction(), 0.0001f)

        val solo = distance(100.0, 1000.0, null, roomCode = null)
        assertEquals(0.1f, solo.displayProgressFraction(), 0.0001f)
    }

    @Test
    fun `race placement follows placements when the room is decided`() {
        val outcome = SharedOutcome.Completed(byUserCode = ANNA, bySelf = false, at = 1L, placements = listOf(ANNA, BOB, SELF))
        val row = route(
            0.9f,
            cache(ChallengeType.ROUTE, snapshot(SELF, 0), snapshot(ANNA, 1, routeProgress = 1f), snapshot(BOB, 2, routeProgress = 0.1f)),
            outcome = outcome
        )
        // Placements win over progress: self is far ahead of Bob but the server ranked Bob second.
        assertEquals(3, row.racePlacement())
    }

    @Test
    fun `race placement ranks by route progress while the race is open, leavers excluded`() {
        val row = route(
            0.5f,
            cache(
                ChallengeType.ROUTE,
                snapshot(SELF, 0, routeProgress = 0.1f),
                snapshot(ANNA, 1, routeProgress = 0.8f),
                snapshot(BOB, 2, left = true, routeProgress = 0.9f)
            )
        )
        // Own progress from the row (0.5), not the cached 0.1; Bob left and does not count.
        assertEquals(2, row.racePlacement())
    }

    @Test
    fun `pilots who are level share a place`() {
        // Nobody has started: everyone is first, not "3rd" for the pilot who joined last.
        val fresh = route(
            0f,
            cache(ChallengeType.ROUTE, snapshot(SELF, 0), snapshot(ANNA, 1), snapshot(BOB, 2))
        )
        assertEquals(1, fresh.racePlacement())

        // Level with the leader is first; one strictly ahead makes it second.
        val level = route(0.5f, cache(ChallengeType.ROUTE, snapshot(SELF, 0), snapshot(ANNA, 1, routeProgress = 0.5f)))
        assertEquals(1, level.racePlacement())
        val behind = route(0.5f, cache(ChallengeType.ROUTE, snapshot(SELF, 0), snapshot(ANNA, 1, routeProgress = 0.6f)))
        assertEquals(2, behind.racePlacement())
    }

    @Test
    fun `race placement is null when not shared or not a race`() {
        assertNull(route(0.5f, null, roomCode = null).racePlacement())
        assertNull(route(0.5f, null).racePlacement())
        assertNull(distance(0.0, 100.0, cache(ChallengeType.DISTANCE, snapshot(SELF, 0))).racePlacement())
    }
}
