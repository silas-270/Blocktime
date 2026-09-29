package com.silas270.blocktime.testutil

import com.silas270.blocktime.data.local.ChallengeDao
import com.silas270.blocktime.data.local.UserProfileDao
import com.silas270.blocktime.data.model.Airport
import com.silas270.blocktime.data.model.Challenge
import com.silas270.blocktime.data.model.ChallengeStatus
import com.silas270.blocktime.data.model.FlightRoute
import com.silas270.blocktime.data.model.PausedFlight
import com.silas270.blocktime.data.model.RoomStateCache
import com.silas270.blocktime.data.model.Runway
import com.silas270.blocktime.data.model.SharedOutcome
import com.silas270.blocktime.data.model.UserProfile
import com.silas270.blocktime.data.repository.AirportRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * The collaborators of `LocalChallengeRepository`, as hand-written fakes shared by its tests
 * (`LocalChallengeRepositoryTest`, `LocalChallengeRepositorySharedTest`,
 * `SharedChallengeSyncerTest`). No mocking library: each fake is the smallest honest
 * implementation of its interface.
 */

/**
 * An in-memory `challenges` table. Every scoped statement changes only the columns the real
 * query names, and [calls] records which statement ran, in order, so a test can assert that a
 * path used a scoped statement and never the whole-row [update] (docs/state.md "Concurrent
 * writers"). The slot flow is re-read on every collection (a fresh `MutableStateFlow`), not
 * live: tests that need a later emission collect it again.
 */
class FakeChallengeDao : ChallengeDao {
    val rows = mutableMapOf<Int, Challenge>()
    private var nextId = 1

    /** The names of the DAO methods called, in order. */
    val calls = mutableListOf<String>()

    override suspend fun insert(challenge: Challenge): Long {
        calls += "insert"
        val id = nextId++
        rows[id] = challenge.copy(id = id)
        return id.toLong()
    }

    override suspend fun update(challenge: Challenge) {
        calls += "update"
        rows[challenge.id] = challenge
    }

    override suspend fun updatePausedFlight(id: Int, flight: PausedFlight?) {
        calls += "updatePausedFlight"
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
        calls += "updateRouteProgress"
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

    override suspend fun deleteById(id: Int) {
        calls += "deleteById"
        rows.remove(id)
    }

    override suspend fun getById(id: Int): Challenge? = rows[id]

    override suspend fun getByStatus(userId: Int, status: ChallengeStatus): List<Challenge> =
        rows.values.filter { it.userId == userId && it.status == status }.sortedBy { it.id }

    override fun getByStatusFlow(userId: Int, status: ChallengeStatus): Flow<List<Challenge>> =
        MutableStateFlow(rows.values.filter { it.userId == userId && it.status == status }.sortedBy { it.id })

    override suspend fun countByStatus(userId: Int, status: ChallengeStatus): Int =
        rows.values.count { it.userId == userId && it.status == status }

    override suspend fun getByStatusOrderedByCompletedAt(userId: Int, status: ChallengeStatus): List<Challenge> =
        rows.values
            .filter { it.userId == userId && it.status == status }
            .sortedByDescending { it.completedAt ?: 0L }

    /** Mirrors the DAO's slot predicate: ACTIVE, or terminal (COMPLETED or FAILED) and not
     *  yet presented. */
    private fun Challenge.occupiesSlot(): Boolean =
        status == ChallengeStatus.ACTIVE ||
            ((status == ChallengeStatus.COMPLETED || status == ChallengeStatus.FAILED) && !celebrated)

    override fun getSlotDisplayFlow(userId: Int): Flow<List<Challenge>> =
        MutableStateFlow(
            rows.values
                .filter { it.userId == userId && it.occupiesSlot() }
                .sortedBy { it.id }
        )

    override suspend fun countOccupyingSlots(userId: Int): Int =
        rows.values.count { it.userId == userId && it.occupiesSlot() }

    override suspend fun getCelebratedCompletedOrderedByCompletedAt(userId: Int): List<Challenge> =
        rows.values
            .filter { it.userId == userId && it.status == ChallengeStatus.COMPLETED && it.celebrated }
            .sortedByDescending { it.completedAt ?: 0L }

    override suspend fun markCelebrated(id: Int) {
        calls += "markCelebrated"
        rows[id]?.let { rows[id] = it.copy(celebrated = true) }
    }

    override suspend fun updateSharedFields(
        id: Int,
        status: ChallengeStatus,
        completedAt: Long?,
        roomState: RoomStateCache?,
        sharedOutcome: SharedOutcome?
    ) {
        calls += "updateSharedFields"
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
        calls += "updateSharedFieldsAndClearPausedFlight"
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
        calls += "updateRoomState"
        rows[id]?.let { rows[id] = it.copy(roomState = roomState) }
    }

    override suspend fun bumpSyncGeneration(id: Int) {
        calls += "bumpSyncGeneration"
        rows[id]?.let { rows[id] = it.copy(syncGeneration = it.syncGeneration + 1) }
    }

    override suspend fun confirmSynced(id: Int, generation: Long) {
        calls += "confirmSynced"
        rows[id]?.takeIf { it.syncGeneration == generation }?.let { rows[id] = it.copy(syncedGeneration = generation) }
    }

    override suspend fun updateRoomLink(id: Int, roomCode: String?, roomState: RoomStateCache?) {
        calls += "updateRoomLink"
        rows[id]?.let { rows[id] = it.copy(roomCode = roomCode, roomState = roomState) }
    }

    override suspend fun getSyncable(userId: Int): List<Challenge> =
        rows.values
            .filter { it.userId == userId && it.roomCode != null && (it.status == ChallengeStatus.ACTIVE || it.status == ChallengeStatus.COMPLETED || !it.celebrated) }
            .sortedBy { it.id }

    override suspend fun getByRoomCode(userId: Int, roomCode: String): Challenge? =
        rows.values.firstOrNull { it.userId == userId && it.roomCode == roomCode }

    override suspend fun countPendingPresentation(userId: Int): Int =
        rows.values.count {
            it.userId == userId &&
                (it.status == ChallengeStatus.COMPLETED || it.status == ChallengeStatus.FAILED) &&
                !it.celebrated
        }
}

/** One fixed profile row. */
class FakeUserProfileDao(private val profile: UserProfile) : UserProfileDao {
    override fun getProfileFlow(): Flow<UserProfile?> = MutableStateFlow(profile)
    override suspend fun getProfile(): UserProfile? = profile
    override suspend fun insertProfile(profile: UserProfile): Long = profile.id.toLong()
    override suspend fun updateProfile(profile: UserProfile) = Unit
    override suspend fun updateUsername(id: Int, username: String, updatedAt: Long) = Unit
    override suspend fun updateHomeAirport(id: Int, iata: String, updatedAt: Long) = Unit
}

/** A lookup table of airports by IATA code; everything else is empty. */
class FakeAirportRepository(private val airports: Map<String, Airport>) : AirportRepository {
    override fun ensureDatabaseCopied() = Unit
    override fun searchAirports(query: String): List<Airport> = emptyList()
    override fun getAirportByIata(iataCode: String): Airport? = airports[iataCode]
    override fun getRunwaysForAirport(airportId: Int): List<Runway> = emptyList()
    override fun getOutboundRoutes(originIata: String, searchQuery: String, sortBy: String): List<FlightRoute> = emptyList()
    override fun getContinentCountryMap(): Map<String, Set<String>> = emptyMap()
    override fun getCountriesForAirports(iatas: List<String>): Set<String> = emptySet()
}

/** A test airport: [continent] and [country] are what set-completion credits read. */
fun testAirport(iata: String, lat: Double, lon: Double, continent: String, country: String): Airport = Airport(
    id = iata.hashCode(),
    ident = iata,
    iataCode = iata,
    name = iata,
    lat = lat,
    lon = lon,
    elevationFt = 0.0,
    continent = continent,
    isoCountry = country,
    isoRegion = "",
    municipality = iata,
    type = "large_airport"
)
