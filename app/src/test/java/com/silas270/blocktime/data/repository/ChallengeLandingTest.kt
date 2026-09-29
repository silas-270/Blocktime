package com.silas270.blocktime.data.repository

import com.silas270.blocktime.data.model.Challenge
import com.silas270.blocktime.data.model.FlightMode
import com.silas270.blocktime.data.model.RoomState
import com.silas270.blocktime.data.network.room.RoomResult
import com.silas270.blocktime.domain.MergeResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Covers [processLandingForChallenges] - the exact call sequence
 * `InFlightViewModel.completeFlight()`'s `checkAchievementsAndChallenges()` delegates to on every
 * landed flight (docs/core-loop.md's post-landing pipeline step 4, challenge half).
 *
 * This is the closest thing to an end-to-end test of that path achievable in this project's
 * plain-JVM unit test setup: `InFlightViewModel` itself can't be instantiated here at all, since
 * `CesiumLiveJniBridge` (which `completeFlight()` calls into) eagerly runs
 * `System.loadLibrary("cesium_rs")` in an `init` block the moment the object is touched, and no
 * native library is available in a JVM-only test run - a pre-existing condition, not introduced
 * by this phase. `processLandingForChallenges` was pulled out specifically so the *logic*
 * `checkAchievementsAndChallenges()` adds is still directly testable despite that.
 */
class ChallengeLandingTest {

    private companion object {
        /** Any fixed instant - these tests only care that it is threaded through unchanged. */
        const val LANDED_AT = 1_700_000_000_000L
    }

    private class FakeChallengeRepository : ChallengeRepository {
        val advancedRouteCalls = mutableListOf<Pair<Int, String>>()
        val creditedFlights = mutableListOf<Triple<String, Double, Long>>()

        override suspend fun listActiveChallenges(): List<Challenge> = emptyList()
        override fun listActiveChallengesFlow(): Flow<List<Challenge>> = MutableStateFlow(emptyList())
        override fun listSlotDisplayChallengesFlow(): Flow<List<Challenge>> = MutableStateFlow(emptyList())
        override suspend fun getChallenge(id: Int): Challenge? = null
        override suspend fun listCompletedChallenges(): List<Challenge> = emptyList()
        override suspend fun markCelebrated(id: Int) = Unit
        override suspend fun startCuratedChallenge(catalogId: String): StartChallengeResult =
            StartChallengeResult.UnknownTemplate
        override suspend fun startCustomRouteChallenge(originIata: String, destIata: String, name: String): StartChallengeResult =
            throw NotImplementedError("unused in this test")
        override suspend fun startCustomDistanceChallenge(targetDistanceKm: Double, name: String): StartChallengeResult =
            throw NotImplementedError("unused in this test")
        override suspend fun startCustomStreakChallenge(targetDays: Int, name: String): StartChallengeResult =
            throw NotImplementedError("unused in this test")
        override suspend fun abandonChallenge(id: Int) = Unit

        override fun pausedFlightStore(challengeId: Int): PausedFlightStore =
            throw NotImplementedError("unused in this test")

        override suspend fun advanceRouteChallenge(challengeId: Int, newPositionIata: String): Challenge? {
            advancedRouteCalls.add(challengeId to newPositionIata)
            return null
        }

        override suspend fun creditEligibleFlight(destIata: String, distanceKm: Double, completedAt: Long) {
            creditedFlights.add(Triple(destIata, distanceKm, completedAt))
        }

        // The sharing side is never reached by the landing pipeline.
        override suspend fun shareChallenge(id: Int): ShareResult = throw NotImplementedError("unused in this test")
        override suspend fun lookUpRoom(code: String): RoomResult<RoomState> = throw NotImplementedError("unused in this test")
        override suspend fun findByRoomCode(code: String): Challenge? = throw NotImplementedError("unused in this test")
        override suspend fun joinRoom(code: String): JoinResult = throw NotImplementedError("unused in this test")
        override suspend fun listSyncableChallenges(): List<Challenge> = emptyList()
        override suspend fun applyRoomState(id: Int, room: RoomState): MergeResult? = null
        override suspend fun markRoomGone(id: Int) = Unit
        override suspend fun confirmSynced(id: Int, generation: Long) = Unit
        override suspend fun hasPendingPresentation(): Boolean = false
        override suspend fun dismissFailed(id: Int) = Unit
    }

    @Test
    fun `a FREE flight never touches the challenge repository at all`() = runTest {
        val repo = FakeChallengeRepository()

        processLandingForChallenges(repo, FlightMode.FREE, challengeId = 42, destIata = "JFK", distanceKm = 5000.0, completedAt = LANDED_AT)

        assertEquals(emptyList<Pair<Int, String>>(), repo.advancedRouteCalls)
        assertEquals(emptyList<Triple<String, Double, Long>>(), repo.creditedFlights)
    }

    @Test
    fun `a STORY flight credits Distance and Set-completion challenges but advances no route`() = runTest {
        val repo = FakeChallengeRepository()

        processLandingForChallenges(repo, FlightMode.STORY, challengeId = null, destIata = "JFK", distanceKm = 5000.0, completedAt = LANDED_AT)

        assertEquals(emptyList<Pair<Int, String>>(), repo.advancedRouteCalls)
        assertEquals(listOf(Triple("JFK", 5000.0, LANDED_AT)), repo.creditedFlights)
    }

    @Test
    fun `a CHALLENGE flight both advances its own scoped route and credits other active challenges`() = runTest {
        val repo = FakeChallengeRepository()

        processLandingForChallenges(repo, FlightMode.CHALLENGE, challengeId = 7, destIata = "SYD", distanceKm = 3000.0, completedAt = LANDED_AT)

        // Advances the scoped Route challenge...
        assertEquals(listOf(7 to "SYD"), repo.advancedRouteCalls)
        // ...and still credits every other active Distance/Set-completion challenge, per
        // docs/challenges.md's "which flights count" (they have no position pointer, so
        // they passively credit any eligible flight regardless of its own Route scoping).
        assertEquals(listOf(Triple("SYD", 3000.0, LANDED_AT)), repo.creditedFlights)
    }

    @Test
    fun `a CHALLENGE flight with no scoped challenge id still credits Distance and Set-completion`() = runTest {
        val repo = FakeChallengeRepository()

        // Defensive case - shouldn't happen in practice (CHALLENGE mode is only ever produced
        // with a challengeId), but the function must not silently drop the passive crediting.
        processLandingForChallenges(repo, FlightMode.CHALLENGE, challengeId = null, destIata = "SYD", distanceKm = 3000.0, completedAt = LANDED_AT)

        assertEquals(emptyList<Pair<Int, String>>(), repo.advancedRouteCalls)
        assertEquals(listOf(Triple("SYD", 3000.0, LANDED_AT)), repo.creditedFlights)
    }

    @Test
    fun `challengeId is ignored (no crash) for a STORY flight even if one is somehow present`() = runTest {
        val repo = FakeChallengeRepository()

        processLandingForChallenges(repo, FlightMode.STORY, challengeId = 99, destIata = "JFK", distanceKm = 100.0, completedAt = LANDED_AT)

        assertEquals(emptyList<Pair<Int, String>>(), repo.advancedRouteCalls)
        assertNull(repo.advancedRouteCalls.firstOrNull())
    }
}
