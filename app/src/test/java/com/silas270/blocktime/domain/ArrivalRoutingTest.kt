package com.silas270.blocktime.domain

import com.silas270.blocktime.data.model.ChallengeType
import com.silas270.blocktime.data.repository.ChallengeOutcome
import com.silas270.blocktime.data.repository.LandingResult
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Every branch of [resolveArrivalDestination], the one routing rule behind both CONTINUE
 * buttons of the landing sequence (docs/shared-challenges.md "Landing").
 */
class ArrivalRoutingTest {

    private val advanced = ChallengeOutcome.Advanced(1, "Race", ChallengeType.ROUTE, 0.2f, 0.5f)
    private val completed = ChallengeOutcome.Completed(2, "Pool", ChallengeType.DISTANCE, 0.9f)

    // ── From the arrival screen ───────────────────────────────────────────────────────────

    @Test
    fun `arrival with outcomes goes to the outcome screen`() {
        assertEquals(ArrivalDestination.OUTCOME, resolveArrivalDestination(LandingResult.ChallengesAffected(listOf(advanced)), hasPendingPresentation = false))
        assertEquals(ArrivalDestination.OUTCOME, resolveArrivalDestination(LandingResult.ChallengesAffected(listOf(completed)), hasPendingPresentation = false))
    }

    @Test
    fun `arrival with outcomes goes to the outcome screen even when a presentation is pending`() {
        assertEquals(ArrivalDestination.OUTCOME, resolveArrivalDestination(LandingResult.ChallengesAffected(listOf(advanced)), hasPendingPresentation = true))
    }

    @Test
    fun `L3 arrival with no outcome but a pending presentation goes to Challenges`() {
        assertEquals(ArrivalDestination.CHALLENGES, resolveArrivalDestination(LandingResult.None, hasPendingPresentation = true))
    }

    @Test
    fun `arrival with nothing goes to the Hub`() {
        assertEquals(ArrivalDestination.HUB, resolveArrivalDestination(LandingResult.None, hasPendingPresentation = false))
    }

    @Test
    fun `an unresolved result counts as nothing`() {
        assertEquals(ArrivalDestination.HUB, resolveArrivalDestination(LandingResult.Pending, hasPendingPresentation = false))
        assertEquals(ArrivalDestination.CHALLENGES, resolveArrivalDestination(LandingResult.Pending, hasPendingPresentation = true))
    }

    @Test
    fun `an affected result without outcomes is treated as nothing`() {
        assertEquals(ArrivalDestination.HUB, resolveArrivalDestination(LandingResult.ChallengesAffected(emptyList()), hasPendingPresentation = false))
    }

    // ── From the outcome screen ───────────────────────────────────────────────────────────

    @Test
    fun `outcome screen with a completion goes to Challenges`() {
        assertEquals(
            ArrivalDestination.CHALLENGES,
            resolveArrivalDestination(LandingResult.ChallengesAffected(listOf(advanced, completed)), hasPendingPresentation = false, fromOutcomeScreen = true)
        )
    }

    @Test
    fun `outcome screen with only advances goes to the Hub`() {
        assertEquals(
            ArrivalDestination.HUB,
            resolveArrivalDestination(LandingResult.ChallengesAffected(listOf(advanced)), hasPendingPresentation = false, fromOutcomeScreen = true)
        )
    }

    @Test
    fun `L8 outcome screen with only advances but a pending presentation goes to Challenges`() {
        assertEquals(
            ArrivalDestination.CHALLENGES,
            resolveArrivalDestination(LandingResult.ChallengesAffected(listOf(advanced)), hasPendingPresentation = true, fromOutcomeScreen = true)
        )
    }

    @Test
    fun `outcome screen never routes back to the outcome screen`() {
        assertEquals(
            ArrivalDestination.HUB,
            resolveArrivalDestination(LandingResult.None, hasPendingPresentation = false, fromOutcomeScreen = true)
        )
        assertEquals(
            ArrivalDestination.CHALLENGES,
            resolveArrivalDestination(LandingResult.None, hasPendingPresentation = true, fromOutcomeScreen = true)
        )
    }
}
