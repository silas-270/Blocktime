package com.silas270.blocktime.data.repository

import com.silas270.blocktime.data.local.ChallengeDao
import com.silas270.blocktime.data.local.UserProfileDao
import com.silas270.blocktime.data.model.Airport
import com.silas270.blocktime.data.model.CameraPose
import com.silas270.blocktime.data.model.Challenge
import com.silas270.blocktime.data.model.ChallengeSource
import com.silas270.blocktime.data.model.ChallengeStatus
import com.silas270.blocktime.data.model.ChallengeType
import com.silas270.blocktime.data.model.FlightMode
import com.silas270.blocktime.data.model.PausedFlight
import com.silas270.blocktime.data.model.RoomStateCache
import com.silas270.blocktime.data.model.Runway
import com.silas270.blocktime.data.model.SharedOutcome
import com.silas270.blocktime.data.model.UserProfile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The concurrency rules on a `challenges` row, pinned as behaviour.
 *
 * The bug these come from: a Route challenge was credited by a landing, showed its completion
 * celebration, and was then found back at its previous progress with the just-landed flight still
 * offered as resumable. Two unsynchronised writers, both doing a read-modify-write of the *whole*
 * row - the landing pipeline, and `InFlightViewModel`'s camera/elapsed-time saves - so whichever
 * read first and wrote last silently reverted the other. `InFlightScreen`'s `ON_STOP` observer
 * fires when the screen is popped for the arrival celebration, which is precisely while the
 * landing is running, so this was the common path rather than an unlucky one.
 *
 * Two independent fixes are asserted here, one per failure direction:
 *
 * 1. The writes are column-scoped ([ChallengeDao.updatePausedFlight] /
 *    [ChallengeDao.updateRouteProgress]), so neither writer can revert the other's columns at all.
 * 2. [SessionPausedFlightStore] refuses a session's paused-flight write once the landing has
 *    cleared the slot, so the flight cannot be resurrected either.
 */
class ChallengeRowConcurrentWriteTest {

    /**
     * Models Room's actual write granularity: [update] rewrites every column, the scoped updates
     * touch only their own. Getting that distinction wrong here would make these tests pass
     * against a DAO that cannot exist.
     */
    private class FakeChallengeDao : ChallengeDao {
        val rows = mutableMapOf<Int, Challenge>()
        private var nextId = 1

        /** Runs immediately before a paused-flight write lands - the seam the interleaving that
         *  used to lose the landing is injected at. */
        var beforePausedFlightWrite: (suspend () -> Unit)? = null

        override suspend fun insert(challenge: Challenge): Long {
            val id = nextId++
            rows[id] = challenge.copy(id = id)
            return id.toLong()
        }

        override suspend fun update(challenge: Challenge) {
            rows[challenge.id] = challenge
        }

        override suspend fun updatePausedFlight(id: Int, flight: PausedFlight?) {
            beforePausedFlightWrite?.let {
                beforePausedFlightWrite = null
                it()
            }
            rows[id]?.let { rows[id] = it.copy(pausedFlight = flight) }
        }

        override suspend fun updateRouteProgress(
            id: Int,
            positionIata: String?,
            routeProgressFraction: Float,
            legIndex: Int,
            status: ChallengeStatus,
            completedAt: Long?,
            syncGeneration: Long
        ) {
            rows[id]?.let {
                rows[id] = it.copy(
                    positionIata = positionIata,
                    routeProgressFraction = routeProgressFraction,
                    legIndex = legIndex,
                    status = status,
                    completedAt = completedAt,
                    syncGeneration = syncGeneration
                )
            }
        }

        override suspend fun deleteById(id: Int) { rows.remove(id) }
        override suspend fun getById(id: Int): Challenge? = rows[id]
        override suspend fun getByStatus(userId: Int, status: ChallengeStatus): List<Challenge> =
            rows.values.filter { it.userId == userId && it.status == status }
        override fun getByStatusFlow(userId: Int, status: ChallengeStatus): Flow<List<Challenge>> =
            MutableStateFlow(rows.values.filter { it.userId == userId && it.status == status })
        override suspend fun countByStatus(userId: Int, status: ChallengeStatus): Int =
            rows.values.count { it.userId == userId && it.status == status }
        override suspend fun getByStatusOrderedByCompletedAt(userId: Int, status: ChallengeStatus) =
            rows.values.filter { it.userId == userId && it.status == status }

        /** Mirrors the DAO's slot predicate: ACTIVE, or terminal (COMPLETED or FAILED) and not
         *  yet presented. */
        private fun Challenge.occupiesSlot(): Boolean =
            status == ChallengeStatus.ACTIVE ||
                ((status == ChallengeStatus.COMPLETED || status == ChallengeStatus.FAILED) && !celebrated)

        override fun getSlotDisplayFlow(userId: Int): Flow<List<Challenge>> =
            MutableStateFlow(rows.values.filter { it.userId == userId && it.occupiesSlot() })

        override suspend fun countOccupyingSlots(userId: Int): Int =
            rows.values.count { it.userId == userId && it.occupiesSlot() }

        override suspend fun getCelebratedCompletedOrderedByCompletedAt(userId: Int): List<Challenge> =
            rows.values.filter { it.userId == userId && it.status == ChallengeStatus.COMPLETED && it.celebrated }

        override suspend fun markCelebrated(id: Int) {
            rows[id]?.let { rows[id] = it.copy(celebrated = true) }
        }

        override suspend fun updateSharedFields(
            id: Int,
            status: ChallengeStatus,
            completedAt: Long?,
            roomState: RoomStateCache?,
            sharedOutcome: SharedOutcome?
        ) {
            rows[id]?.let {
                rows[id] = it.copy(status = status, completedAt = completedAt, roomState = roomState, sharedOutcome = sharedOutcome)
            }
        }

        override suspend fun updateSharedFieldsAndClearPausedFlight(
            id: Int,
            status: ChallengeStatus,
            completedAt: Long?,
            roomState: RoomStateCache?,
            sharedOutcome: SharedOutcome?
        ) {
            rows[id]?.let {
                rows[id] = it.copy(
                    status = status,
                    completedAt = completedAt,
                    roomState = roomState,
                    sharedOutcome = sharedOutcome,
                    pausedFlight = null
                )
            }
        }

        override suspend fun updateRoomState(id: Int, roomState: RoomStateCache?) {
            rows[id]?.let { rows[id] = it.copy(roomState = roomState) }
        }

        override suspend fun bumpSyncGeneration(id: Int) {
            rows[id]?.let { rows[id] = it.copy(syncGeneration = it.syncGeneration + 1) }
        }

        override suspend fun confirmSynced(id: Int, generation: Long) {
            rows[id]?.takeIf { it.syncGeneration == generation }?.let { rows[id] = it.copy(syncedGeneration = generation) }
        }

        override suspend fun updateRoomLink(id: Int, roomCode: String?, roomState: RoomStateCache?) {
            rows[id]?.let { rows[id] = it.copy(roomCode = roomCode, roomState = roomState) }
        }

        override suspend fun getSyncable(userId: Int): List<Challenge> =
            rows.values.filter { it.userId == userId && it.roomCode != null && (it.status == ChallengeStatus.ACTIVE || !it.celebrated) }

        override suspend fun getByRoomCode(userId: Int, roomCode: String): Challenge? =
            rows.values.firstOrNull { it.userId == userId && it.roomCode == roomCode }

        override suspend fun countPendingPresentation(userId: Int): Int =
            rows.values.count {
                it.userId == userId &&
                    (it.status == ChallengeStatus.COMPLETED || it.status == ChallengeStatus.FAILED) &&
                    !it.celebrated
            }
    }

    private class FakeUserProfileDao : UserProfileDao {
        private val profile = UserProfile(id = 1, username = "pilot", userCode = "ABC123", homeAirportIata = "STR")
        override fun getProfileFlow(): Flow<UserProfile?> = MutableStateFlow(profile)
        override suspend fun getProfile(): UserProfile? = profile
        override suspend fun insertProfile(profile: UserProfile): Long = 1L
        override suspend fun updateProfile(profile: UserProfile) = Unit
        override suspend fun updateUsername(id: Int, username: String, updatedAt: Long) = Unit
        override suspend fun updateHomeAirport(id: Int, iata: String, updatedAt: Long) = Unit
    }

    private class FakeAirportRepository(private val airports: Map<String, Airport>) : AirportRepository {
        override fun ensureDatabaseCopied() = Unit
        override fun searchAirports(query: String): List<Airport> = emptyList()
        override fun getAirportByIata(iataCode: String): Airport? = airports[iataCode]
        override fun getRunwaysForAirport(airportId: Int): List<Runway> = emptyList()
        override fun getOutboundRoutes(originIata: String, searchQuery: String, sortBy: String) =
            emptyList<com.silas270.blocktime.data.model.FlightRoute>()
        override fun getContinentCountryMap(): Map<String, Set<String>> = emptyMap()
        override fun getCountriesForAirports(iatas: List<String>): Set<String> = emptySet()
    }

    private fun airport(iata: String, lon: Double) = Airport(
        id = iata.hashCode(), ident = iata, iataCode = iata, name = iata,
        lat = 0.0, lon = lon, elevationFt = 0.0, continent = "EU", isoCountry = "DE",
        isoRegion = "", municipality = iata, type = "large_airport"
    )

    private val dao = FakeChallengeDao()
    private val repository = LocalChallengeRepository(
        dao,
        FakeUserProfileDao(),
        // Synthetic geography along the equator: NEAR is 90 of the 100 degrees to SDD, so the
        // free-form Route proxy scores it at 0.9 - the "stuck at ~85%" the bug report described.
        FakeAirportRepository(
            mapOf("STR" to airport("STR", 0.0), "NEAR" to airport("NEAR", 90.0), "SDD" to airport("SDD", 100.0))
        )
    )

    /** A Route challenge one hop short of its destination, with a live paused flight on its row. */
    private suspend fun seedChallenge(): Int {
        val id = dao.insert(
            Challenge(
                userId = 1,
                type = ChallengeType.ROUTE,
                source = ChallengeSource.CUSTOM,
                name = "STR to SDD",
                originIata = "STR",
                destIata = "SDD",
                positionIata = "NEAR",
                routeProgressFraction = 0.9f
            )
        ).toInt()
        repository.pausedFlightStore(id).save(
            PausedFlight("FF1", "NEAR", "SDD", 60, FlightMode.CHALLENGE, id, elapsedMs = 1000L)
        )
        return id
    }

    /** The landing pipeline's two writes to this row, in `InFlightViewModel`'s own order. */
    private suspend fun runLanding(id: Int, store: PausedFlightStore) {
        store.clear()
        repository.advanceRouteChallenge(id, "SDD")
    }

    @Test
    fun `a paused-flight write that straddles the landing cannot revert the credited leg`() = runTest {
        val id = seedChallenge()
        val session = SessionPausedFlightStore(repository.pausedFlightStore(id))
        val paused = session.get()!!

        // The whole landing lands between this write's read of the row and the write itself -
        // the interleaving that used to put the challenge back where it started.
        dao.beforePausedFlightWrite = { runLanding(id, session) }
        session.save(paused.copy(camera = CameraPose(1, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 1.0)))

        val row = dao.rows.getValue(id)
        assertEquals("position should have advanced to SDD", "SDD", row.positionIata)
        assertEquals("challenge should be completed", ChallengeStatus.COMPLETED, row.status)
        assertEquals("progress should be 100%", 1.0f, row.routeProgressFraction, 0.0001f)
        assertNotNull(row.completedAt)
    }

    @Test
    fun `an elapsed-time write that straddles the landing cannot revert it either`() = runTest {
        val id = seedChallenge()
        val session = SessionPausedFlightStore(repository.pausedFlightStore(id))
        val current = session.get()!!

        dao.beforePausedFlightWrite = { runLanding(id, session) }
        session.save(current.copy(elapsedMs = 5000L))

        val row = dao.rows.getValue(id)
        assertEquals("SDD", row.positionIata)
        assertEquals(ChallengeStatus.COMPLETED, row.status)
    }

    @Test
    fun `a landed session cannot resurrect its paused flight`() = runTest {
        val id = seedChallenge()
        val session = SessionPausedFlightStore(repository.pausedFlightStore(id))
        val paused = session.get()!!

        runLanding(id, session)
        // The camera save arriving late, after the landing already cleared the slot - what left
        // the Hub still offering to resume a flight that had landed.
        session.save(paused.copy(camera = CameraPose(1, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 1.0)))

        assertTrue("the landing's clear seals the session", session.isSealed)
        assertNull("paused flight must stay cleared", dao.rows.getValue(id).pausedFlight)
        assertEquals(ChallengeStatus.COMPLETED, dao.rows.getValue(id).status)
    }

    @Test
    fun `a paused-flight write before the landing is still honoured, then discarded by the clear`() = runTest {
        val id = seedChallenge()
        val session = SessionPausedFlightStore(repository.pausedFlightStore(id))

        session.save(session.get()!!.copy(elapsedMs = 5000L))
        assertFalse(session.isSealed)
        assertEquals(5000L, dao.rows.getValue(id).pausedFlight?.elapsedMs)

        runLanding(id, session)
        assertNull(dao.rows.getValue(id).pausedFlight)
    }

    @Test
    fun `a route advance does not write back a stale paused flight`() = runTest {
        val id = seedChallenge()
        // A landing that credits the leg while a paused flight is still on the row (the pilot
        // backgrounded and resumed): advancing must not carry the row's other columns.
        repository.advanceRouteChallenge(id, "SDD")

        val row = dao.rows.getValue(id)
        assertEquals(ChallengeStatus.COMPLETED, row.status)
        assertEquals("the advance leaves the slot to its own writer", 1000L, row.pausedFlight?.elapsedMs)
    }

    /** Control: the landing on its own, with nothing racing it. */
    @Test
    fun `landing alone credits the challenge and clears the slot`() = runTest {
        val id = seedChallenge()
        runLanding(id, SessionPausedFlightStore(repository.pausedFlightStore(id)))

        val row = dao.rows.getValue(id)
        assertEquals("SDD", row.positionIata)
        assertEquals(ChallengeStatus.COMPLETED, row.status)
        assertNull(row.pausedFlight)
        assertNotNull(row.completedAt)
    }
}
