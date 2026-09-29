package com.silas270.blocktime.domain

import com.silas270.blocktime.data.model.Challenge
import com.silas270.blocktime.data.model.ChallengeSource
import com.silas270.blocktime.data.model.ChallengeStatus
import com.silas270.blocktime.data.model.ChallengeType
import com.silas270.blocktime.data.model.ParticipantSnapshot
import com.silas270.blocktime.data.model.RoomDefinition
import com.silas270.blocktime.data.model.RoomState
import com.silas270.blocktime.data.model.RoomStateCache
import com.silas270.blocktime.data.model.SharedOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Every variant of docs/shared-challenges.md "Presentation", derived from the row alone by
 * [presentationFor] and [failurePresentationFor].
 */
class ChallengePresentationTest {

    private companion object {
        const val SELF = "SELF01"
        const val ANNA = "ANNA01"
        const val BOB = "BOB001"
    }

    private fun snapshot(code: String, colorIndex: Int, username: String = code.lowercase(), left: Boolean = false, routeProgress: Float = 0f) =
        ParticipantSnapshot(userCode = code, username = username, colorIndex = colorIndex, left = left, routeProgress = routeProgress)

    private fun cache(type: ChallengeType, vararg participants: ParticipantSnapshot) = RoomStateCache(
        selfCode = SELF,
        room = RoomState(
            code = "ROOM01",
            definition = RoomDefinition(type = type, source = ChallengeSource.CUSTOM, name = "Shared"),
            participants = participants.toList(),
        ),
    )

    private fun distance(
        status: ChallengeStatus = ChallengeStatus.COMPLETED,
        roomCode: String? = "ROOM01",
        cache: RoomStateCache? = null,
        outcome: SharedOutcome? = null,
    ) = Challenge(
        id = 1,
        userId = 1,
        type = ChallengeType.DISTANCE,
        source = ChallengeSource.CUSTOM,
        status = status,
        name = "Distance",
        targetDistanceKm = 1000.0,
        cumulativeDistanceKm = 1000.0,
        roomCode = roomCode,
        roomState = cache,
        sharedOutcome = outcome,
    )

    private fun route(
        status: ChallengeStatus = ChallengeStatus.COMPLETED,
        cache: RoomStateCache? = null,
        outcome: SharedOutcome? = null,
        ownProgress: Float = 1f,
    ) = Challenge(
        id = 1,
        userId = 1,
        type = ChallengeType.ROUTE,
        source = ChallengeSource.CUSTOM,
        status = status,
        name = "Race",
        originIata = "LHR",
        destIata = "SYD",
        positionIata = "SYD",
        routeProgressFraction = ownProgress,
        roomCode = "ROOM01",
        roomState = cache,
        sharedOutcome = outcome,
    )

    private fun streak(
        status: ChallengeStatus = ChallengeStatus.FAILED,
        cache: RoomStateCache? = null,
        outcome: SharedOutcome? = null,
    ) = Challenge(
        id = 1,
        userId = 1,
        type = ChallengeType.STREAK,
        source = ChallengeSource.CUSTOM,
        status = status,
        name = "Streak",
        targetDays = 3,
        streakDays = 2,
        lastFlownDay = "2026-09-28",
        roomCode = "ROOM01",
        roomState = cache,
        sharedOutcome = outcome,
    )

    // ── Completion ────────────────────────────────────────────────────────────────────────

    @Test
    fun `no completion presentation unless completed`() {
        assertNull(presentationFor(distance(status = ChallengeStatus.ACTIVE)))
        assertNull(presentationFor(distance(status = ChallengeStatus.FAILED)))
    }

    @Test
    fun `a solo row is presented solo`() {
        assertEquals(CompletionPresentation.Solo, presentationFor(distance(roomCode = null)))
    }

    @Test
    fun `a shared pool is presented as a team, whoever completed it`() {
        val cache = cache(ChallengeType.DISTANCE, snapshot(SELF, 0), snapshot(ANNA, 1), snapshot(BOB, 2, left = true))
        val bySelf = SharedOutcome.Completed(byUserCode = SELF, bySelf = true, at = 1L)
        val byAnna = SharedOutcome.Completed(byUserCode = ANNA, bySelf = false, at = 1L)

        // Leavers are not part of the crew count.
        assertEquals(CompletionPresentation.Team(2), presentationFor(distance(cache = cache, outcome = bySelf)))
        assertEquals(CompletionPresentation.Team(2), presentationFor(distance(cache = cache, outcome = byAnna)))
    }

    @Test
    fun `a shared pool without a cache is a team of one`() {
        assertEquals(CompletionPresentation.Team(1), presentationFor(distance()))
    }

    @Test
    fun `a race completed by self is a win`() {
        val cache = cache(ChallengeType.ROUTE, snapshot(SELF, 0), snapshot(ANNA, 1), snapshot(BOB, 2))
        val outcome = SharedOutcome.Completed(byUserCode = SELF, bySelf = true, at = 1L, placements = listOf(SELF, ANNA, BOB))

        assertEquals(CompletionPresentation.RaceWon(3), presentationFor(route(cache = cache, outcome = outcome)))
    }

    @Test
    fun `a race completed locally with no outcome yet is a win`() {
        val cache = cache(ChallengeType.ROUTE, snapshot(SELF, 0), snapshot(ANNA, 1))
        assertEquals(CompletionPresentation.RaceWon(2), presentationFor(route(cache = cache)))
    }

    @Test
    fun `a race someone else won is a placement with the winner's name`() {
        val cache = cache(ChallengeType.ROUTE, snapshot(SELF, 0), snapshot(ANNA, 1, username = "Anna"), snapshot(BOB, 2))
        val outcome = SharedOutcome.Completed(byUserCode = ANNA, bySelf = false, at = 1L, placements = listOf(ANNA, BOB, SELF))

        assertEquals(CompletionPresentation.RacePlaced(place = 3, winnerName = "Anna", crewSize = 3), presentationFor(route(cache = cache, outcome = outcome)))
    }

    @Test
    fun `a placement the server does not list falls back to second`() {
        // Joined after the finish, or an older server: no placements, and without a cache no
        // rank to derive either.
        val outcome = SharedOutcome.Completed(byUserCode = ANNA, bySelf = false, at = 1L)

        // Without a cache the crew is unknown, which counts as one, as for a pool.
        assertEquals(CompletionPresentation.RacePlaced(place = 2, winnerName = ANNA, crewSize = 1), presentationFor(route(outcome = outcome)))
    }

    @Test
    fun `without placements the open-race rank from the cache stands in`() {
        val cache = cache(
            ChallengeType.ROUTE,
            snapshot(SELF, 0),
            snapshot(ANNA, 1, username = "Anna", routeProgress = 1f),
            snapshot(BOB, 2, routeProgress = 0.9f),
        )
        val outcome = SharedOutcome.Completed(byUserCode = ANNA, bySelf = false, at = 1L)

        assertEquals(
            CompletionPresentation.RacePlaced(place = 3, winnerName = "Anna", crewSize = 3),
            presentationFor(route(cache = cache, outcome = outcome, ownProgress = 0.5f)),
        )
    }

    @Test
    fun `the winner's code stands in when the cache has no name`() {
        val cache = cache(ChallengeType.ROUTE, snapshot(SELF, 0), snapshot(ANNA, 1, username = ""))
        val outcome = SharedOutcome.Completed(byUserCode = ANNA, bySelf = false, at = 1L, placements = listOf(ANNA, SELF))

        assertEquals(CompletionPresentation.RacePlaced(place = 2, winnerName = ANNA, crewSize = 2), presentationFor(route(cache = cache, outcome = outcome)))
    }

    // ── Failure ───────────────────────────────────────────────────────────────────────────

    @Test
    fun `no failure presentation unless failed`() {
        assertNull(failurePresentationFor(streak(status = ChallengeStatus.ACTIVE)))
        assertNull(failurePresentationFor(streak(status = ChallengeStatus.COMPLETED)))
    }

    @Test
    fun `a streak broken by a crew member names them`() {
        val cache = cache(ChallengeType.STREAK, snapshot(SELF, 0), snapshot(ANNA, 1, username = "Anna"))
        val outcome = SharedOutcome.Failed(brokenByUserCode = ANNA, bySelf = false, at = 1L)

        assertEquals(FailurePresentation.StreakBroken(brokenByName = "Anna", bySelf = false), failurePresentationFor(streak(cache = cache, outcome = outcome)))
    }

    @Test
    fun `a streak broken by self says so`() {
        val cache = cache(ChallengeType.STREAK, snapshot(SELF, 0, username = "Me"), snapshot(ANNA, 1))
        val outcome = SharedOutcome.Failed(brokenByUserCode = SELF, bySelf = true, at = 1L)

        assertEquals(FailurePresentation.StreakBroken(brokenByName = "Me", bySelf = true), failurePresentationFor(streak(cache = cache, outcome = outcome)))
    }

    @Test
    fun `a broken streak whose breaker is not in the cache has no name`() {
        val outcome = SharedOutcome.Failed(brokenByUserCode = BOB, bySelf = false, at = 1L)
        assertEquals(FailurePresentation.StreakBroken(brokenByName = null, bySelf = false), failurePresentationFor(streak(outcome = outcome)))

        val blank = cache(ChallengeType.STREAK, snapshot(SELF, 0), snapshot(BOB, 1, username = " "))
        assertEquals(FailurePresentation.StreakBroken(brokenByName = null, bySelf = false), failurePresentationFor(streak(cache = blank, outcome = outcome)))
    }

    @Test
    fun `a failed row without an outcome is still presented`() {
        assertEquals(FailurePresentation.StreakBroken(brokenByName = null, bySelf = false), failurePresentationFor(streak()))
    }
}
