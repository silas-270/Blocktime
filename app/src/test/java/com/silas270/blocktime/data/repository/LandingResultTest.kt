package com.silas270.blocktime.data.repository

import com.silas270.blocktime.data.model.Challenge
import com.silas270.blocktime.data.model.ChallengeSource
import com.silas270.blocktime.data.model.ChallengeStatus
import com.silas270.blocktime.data.model.ChallengeType
import com.silas270.blocktime.data.model.ParticipantSnapshot
import com.silas270.blocktime.data.model.RoomDefinition
import com.silas270.blocktime.data.model.RoomState
import com.silas270.blocktime.data.model.RoomStateCache
import com.silas270.blocktime.data.model.SharedOutcome
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers [resolveLandingOutcome] (the before/after diff that turns [processLandingForChallenges]'s
 * side effects into a UI-facing [LandingResult]) and [LandingResultChannel] (the state holder that
 * bridges that result across the InFlight -> ArrivalCelebration -> outcome navigation hop). See
 * ChallengeLandingTest for the sibling coverage of processLandingForChallenges itself.
 */
class LandingResultTest {

    private fun routeChallenge(id: Int, progress: Float, status: ChallengeStatus = ChallengeStatus.ACTIVE) = Challenge(
        id = id,
        userId = 1,
        type = ChallengeType.ROUTE,
        source = ChallengeSource.CURATED,
        status = status,
        name = "London → Sydney",
        originIata = "LHR",
        destIata = "SYD",
        positionIata = "LHR",
        routeProgressFraction = progress,
        completedAt = if (status == ChallengeStatus.COMPLETED) 1L else null
    )

    private fun distanceChallenge(id: Int, cumulativeKm: Double, targetKm: Double = 10_000.0, status: ChallengeStatus = ChallengeStatus.ACTIVE) = Challenge(
        id = id,
        userId = 1,
        type = ChallengeType.DISTANCE,
        source = ChallengeSource.CURATED,
        status = status,
        name = "10,000 km Club",
        targetDistanceKm = targetKm,
        cumulativeDistanceKm = cumulativeKm,
        completedAt = if (status == ChallengeStatus.COMPLETED) 1L else null
    )

    private fun setChallenge(id: Int, name: String, status: ChallengeStatus = ChallengeStatus.ACTIVE) = Challenge(
        id = id,
        userId = 1,
        type = ChallengeType.SET_COMPLETION,
        source = ChallengeSource.CURATED,
        status = status,
        name = name,
        setCatalogId = "all_continents",
        setTotalMembers = 7,
        visitedSetMembers = if (status == ChallengeStatus.COMPLETED) setOf("EU", "AF", "AS", "NA", "SA", "OC", "AN") else setOf("EU"),
        completedAt = if (status == ChallengeStatus.COMPLETED) 1L else null
    )

    private fun streakChallenge(id: Int, streakDays: Int, targetDays: Int = 4, status: ChallengeStatus = ChallengeStatus.ACTIVE) = Challenge(
        id = id,
        userId = 1,
        type = ChallengeType.STREAK,
        source = ChallengeSource.CUSTOM,
        status = status,
        name = "4-Day Streak",
        targetDays = targetDays,
        streakDays = streakDays,
        lastFlownDay = "2026-03-10",
        completedAt = if (status == ChallengeStatus.COMPLETED) 1L else null
    )

    @Test
    fun `no changes resolves to None`() {
        val before = listOf(routeChallenge(1, 0.2f), distanceChallenge(2, 1000.0))
        val after = listOf(routeChallenge(1, 0.2f), distanceChallenge(2, 1000.0))

        assertEquals(LandingResult.None, resolveLandingOutcome(before, after))
    }

    @Test
    fun `empty before (no active challenges) resolves to None`() {
        assertEquals(LandingResult.None, resolveLandingOutcome(emptyList(), emptyList()))
    }

    @Test
    fun `a challenge that advanced but is still active resolves to a single Advanced outcome with old and new progress`() {
        val before = listOf(routeChallenge(1, 0.2f))
        val after = listOf(routeChallenge(1, 0.55f))

        val result = resolveLandingOutcome(before, after)
        assertTrue(result is LandingResult.ChallengesAffected)
        val outcomes = (result as LandingResult.ChallengesAffected).outcomes
        assertEquals(1, outcomes.size)
        val outcome = outcomes.single() as ChallengeOutcome.Advanced
        assertEquals(1, outcome.challengeId)
        assertEquals(0.2f, outcome.oldProgress)
        assertEquals(0.55f, outcome.newProgress)
        assertEquals(ChallengeType.ROUTE, outcome.type)
    }

    @Test
    fun `a challenge that flips from ACTIVE to COMPLETED resolves to a single Completed outcome`() {
        val before = listOf(distanceChallenge(1, 9000.0))
        val after = listOf(distanceChallenge(1, cumulativeKm = 10_500.0, status = ChallengeStatus.COMPLETED))

        val result = resolveLandingOutcome(before, after)
        assertTrue(result is LandingResult.ChallengesAffected)
        val outcomes = (result as LandingResult.ChallengesAffected).outcomes
        assertEquals(1, outcomes.size)
        val outcome = outcomes.single() as ChallengeOutcome.Completed
        assertEquals(1, outcome.challengeId)
        assertEquals(ChallengeType.DISTANCE, outcome.type)
        assertEquals(0.9f, outcome.oldProgress)
    }

    @Test
    fun `a completion and a mere advance in the same landing both appear, in before's order`() {
        val before = listOf(routeChallenge(1, 0.3f), distanceChallenge(2, 9000.0))
        val after = listOf(
            routeChallenge(1, 0.6f), // this landing's own scoped Route challenge also advanced
            distanceChallenge(2, cumulativeKm = 10_500.0, status = ChallengeStatus.COMPLETED)
        )

        val result = resolveLandingOutcome(before, after)
        assertTrue(result is LandingResult.ChallengesAffected)
        val outcomes = (result as LandingResult.ChallengesAffected).outcomes
        assertEquals(2, outcomes.size)
        assertTrue(outcomes[0] is ChallengeOutcome.Advanced)
        assertEquals(1, outcomes[0].challengeId)
        assertTrue(outcomes[1] is ChallengeOutcome.Completed)
        assertEquals(2, outcomes[1].challengeId)
    }

    @Test
    fun `two challenges both advance - both appear, in before's order`() {
        val before = listOf(routeChallenge(1, 0.1f), distanceChallenge(2, 1000.0))
        val after = listOf(routeChallenge(1, 0.3f), distanceChallenge(2, 4000.0))

        val result = resolveLandingOutcome(before, after)
        assertTrue(result is LandingResult.ChallengesAffected)
        val outcomes = (result as LandingResult.ChallengesAffected).outcomes
        assertEquals(2, outcomes.size)
        assertEquals(1, outcomes[0].challengeId)
        assertEquals(2, outcomes[1].challengeId)
    }

    @Test
    fun `three simultaneous outcomes (the MAX_ACTIVE_CHALLENGES=3 case) all appear, in before's order`() {
        val before = listOf(
            distanceChallenge(1, 1000.0),
            distanceChallenge(2, 9000.0),
            setChallenge(3, "All Continents")
        )
        val after = listOf(
            distanceChallenge(1, 3000.0), // advances
            distanceChallenge(2, cumulativeKm = 10_500.0, status = ChallengeStatus.COMPLETED), // completes
            setChallenge(3, "All Continents", status = ChallengeStatus.COMPLETED) // completes
        )

        val result = resolveLandingOutcome(before, after)
        assertTrue(result is LandingResult.ChallengesAffected)
        val outcomes = (result as LandingResult.ChallengesAffected).outcomes
        assertEquals(3, outcomes.size)
        assertEquals(listOf(1, 2, 3), outcomes.map { it.challengeId })
        assertTrue(outcomes[0] is ChallengeOutcome.Advanced)
        assertTrue(outcomes[1] is ChallengeOutcome.Completed)
        assertTrue(outcomes[2] is ChallengeOutcome.Completed)
    }

    /**
     * `resolveLandingOutcome` is type-blind - it diffs `status` and `progressFraction()` and never
     * switches on [ChallengeType] - so a new type should need no change here at all. These two pin
     * that, since "it works by accident" and "it works by design" look identical until someone
     * adds a type-specific branch.
     */
    @Test
    fun `a streak that gained a day resolves to an Advanced outcome like any other type`() {
        val before = listOf(streakChallenge(1, streakDays = 1))
        val after = listOf(streakChallenge(1, streakDays = 2))

        val result = resolveLandingOutcome(before, after)
        val outcome = (result as LandingResult.ChallengesAffected).outcomes.single() as ChallengeOutcome.Advanced
        assertEquals(ChallengeType.STREAK, outcome.type)
        assertEquals(0.25f, outcome.oldProgress)
        assertEquals(0.5f, outcome.newProgress)
    }

    @Test
    fun `a streak reaching its target resolves to a Completed outcome`() {
        val before = listOf(streakChallenge(1, streakDays = 3))
        val after = listOf(streakChallenge(1, streakDays = 4, status = ChallengeStatus.COMPLETED))

        val result = resolveLandingOutcome(before, after)
        val outcome = (result as LandingResult.ChallengesAffected).outcomes.single()
        assertTrue(outcome is ChallengeOutcome.Completed)
        assertEquals(ChallengeType.STREAK, outcome.type)
    }

    @Test
    fun `a before entry missing from after (e_g_ abandoned mid-flight) is skipped, not a crash`() {
        val before = listOf(routeChallenge(1, 0.2f), distanceChallenge(2, 1000.0))
        val after = listOf(distanceChallenge(2, 1000.0)) // challenge 1 is gone

        assertEquals(LandingResult.None, resolveLandingOutcome(before, after))
    }

    // ── Shared rows (docs/shared-challenges.md "Landing") ─────────────────────────────────

    private fun sharedCache(type: ChallengeType, vararg others: ParticipantSnapshot) = RoomStateCache(
        selfCode = "SELF01",
        room = RoomState(
            code = "ROOM01",
            definition = RoomDefinition(type = type, source = ChallengeSource.CUSTOM, name = "Shared"),
            participants = listOf(ParticipantSnapshot(userCode = "SELF01", username = "me", colorIndex = 0)) + others,
        ),
    )

    private fun sharedDistance(id: Int, cumulativeKm: Double, status: ChallengeStatus = ChallengeStatus.ACTIVE, outcome: SharedOutcome? = null) =
        distanceChallenge(id, cumulativeKm, targetKm = 1000.0, status = status).copy(
            roomCode = "ROOM01",
            roomState = sharedCache(
                ChallengeType.DISTANCE,
                ParticipantSnapshot(userCode = "ANNA01", username = "anna", colorIndex = 1, distanceKm = 400.0),
                ParticipantSnapshot(userCode = "BOB001", username = "bob", colorIndex = 2, left = true, distanceKm = 100.0),
            ),
            sharedOutcome = outcome,
        )

    @Test
    fun `L8 a foreign completion between before and after is not my outcome`() {
        val before = listOf(sharedDistance(1, 100.0))
        val after = listOf(
            sharedDistance(1, 100.0, status = ChallengeStatus.COMPLETED, outcome = SharedOutcome.Completed(byUserCode = "ANNA01", bySelf = false, at = 5L))
        )

        assertEquals(LandingResult.None, resolveLandingOutcome(before, after))
    }

    @Test
    fun `a failed row produces no outcome`() {
        val before = listOf(streakChallenge(1, streakDays = 2).copy(roomCode = "ROOM01"))
        val after = listOf(
            streakChallenge(1, streakDays = 2, status = ChallengeStatus.FAILED).copy(
                roomCode = "ROOM01",
                sharedOutcome = SharedOutcome.Failed(brokenByUserCode = "SELF01", bySelf = true, at = 5L),
            )
        )

        assertEquals(LandingResult.None, resolveLandingOutcome(before, after))
    }

    @Test
    fun `shared pool outcomes carry team progress and crew size`() {
        // Own 100 + Anna 400 + the leaver's 100 = 600 of 1000 before; own 300 after.
        val before = listOf(sharedDistance(1, 100.0), sharedDistance(2, 100.0))
        val after = listOf(
            sharedDistance(1, 300.0),
            sharedDistance(2, 500.0, status = ChallengeStatus.COMPLETED, outcome = SharedOutcome.Completed(byUserCode = "SELF01", bySelf = true, at = 5L)),
        )

        val outcomes = (resolveLandingOutcome(before, after) as LandingResult.ChallengesAffected).outcomes
        assertEquals(2, outcomes.size)

        val advanced = outcomes[0] as ChallengeOutcome.Advanced
        assertEquals(0.6f, advanced.oldProgress, 0.0001f)
        assertEquals(0.8f, advanced.newProgress, 0.0001f)
        assertTrue(advanced.isShared)
        // Self and Anna; Bob left.
        assertEquals(2, advanced.crewSize)

        val completed = outcomes[1] as ChallengeOutcome.Completed
        assertEquals(0.6f, completed.oldProgress, 0.0001f)
        assertTrue(completed.isShared)
        assertEquals(2, completed.crewSize)
    }

    @Test
    fun `solo outcomes are not shared and have a crew of one`() {
        val before = listOf(distanceChallenge(1, 1000.0))
        val after = listOf(distanceChallenge(1, 3000.0))

        val outcome = (resolveLandingOutcome(before, after) as LandingResult.ChallengesAffected).outcomes.single()
        assertFalse(outcome.isShared)
        assertEquals(1, outcome.crewSize)
    }

    @Test
    fun `LandingResultChannel starts Pending, resets to Pending, and publishes resolved values`() = runTest {
        val channel = LandingResultChannel()
        assertEquals(LandingResult.Pending, channel.result.value)

        channel.publish(LandingResult.None)
        assertEquals(LandingResult.None, channel.result.value)

        channel.reset()
        assertEquals(LandingResult.Pending, channel.result.value)

        val outcome = LandingResult.ChallengesAffected(
            listOf(ChallengeOutcome.Completed(1, "10,000 km Club", ChallengeType.DISTANCE, 0.9f))
        )
        channel.publish(outcome)
        assertEquals(outcome, channel.result.value)
    }
}
