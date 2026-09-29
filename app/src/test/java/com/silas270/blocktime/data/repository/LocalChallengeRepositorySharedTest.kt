package com.silas270.blocktime.data.repository

import com.silas270.blocktime.data.model.Challenge
import com.silas270.blocktime.data.model.ChallengeSource
import com.silas270.blocktime.data.model.ChallengeStatus
import com.silas270.blocktime.data.model.ChallengeType
import com.silas270.blocktime.data.model.CuratedChallengeSets
import com.silas270.blocktime.data.model.FlightMode
import com.silas270.blocktime.data.model.ParticipantSnapshot
import com.silas270.blocktime.data.model.PausedFlight
import com.silas270.blocktime.data.model.RoomDefinition
import com.silas270.blocktime.data.model.RoomState
import com.silas270.blocktime.data.model.RoomStateCache
import com.silas270.blocktime.data.model.SetMemberKind
import com.silas270.blocktime.data.model.SharedOutcome
import com.silas270.blocktime.data.model.UserProfile
import com.silas270.blocktime.data.network.room.FakeRoomApi
import com.silas270.blocktime.data.network.room.RoomResult
import com.silas270.blocktime.testutil.FakeAirportRepository
import com.silas270.blocktime.testutil.FakeChallengeDao
import com.silas270.blocktime.testutil.FakeSharedPreferences
import com.silas270.blocktime.testutil.FakeUserProfileDao
import com.silas270.blocktime.testutil.RecordingRoomApi
import com.silas270.blocktime.testutil.testAirport
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * The sharing side of [LocalChallengeRepository]: docs/shared-challenges.md "Sharing" (S3 to
 * S8), "Joining" (J3 to J13), the shared-row credit rules of "Landing" (L1, L2, L4, L5, L6) and
 * the sync-path operations of "Applying a room state" (scoped statements only, P11, P15). Each
 * test is named after its situation. The fakes are the same as `LocalChallengeRepositoryTest`
 * uses, plus a [FakeRoomApi] behind a recorder.
 */
class LocalChallengeRepositorySharedTest {

    private companion object {
        private val ZONE: ZoneId = ZoneOffset.UTC
        private val DAY_1: Long = LocalDate.of(2026, 3, 2).atStartOfDay(ZONE).toInstant().toEpochMilli()
        private val DAY_3: Long = DAY_1 + 2 * 24L * 60 * 60 * 1000
        private val TEST_CLOCK: Clock = Clock.fixed(Instant.ofEpochMilli(DAY_3), ZONE)

        const val SELF = "ABC123"
        const val ANNA = "ANNA02"
        const val ROOM = "ROOM42"
    }

    private val profile = UserProfile(id = 1, username = "Cap", userCode = SELF, homeAirportIata = "ORI")

    private lateinit var dao: FakeChallengeDao
    private lateinit var fake: FakeRoomApi
    private lateinit var roomApi: RecordingRoomApi
    private lateinit var prefs: PreferencesRepository
    private lateinit var repository: LocalChallengeRepository

    @Before
    fun setUp() {
        dao = FakeChallengeDao()
        fake = FakeRoomApi { DAY_3 }.apply { callerUserCode = SELF }
        roomApi = RecordingRoomApi(fake)
        prefs = PreferencesRepository(FakeSharedPreferences())
        repository = LocalChallengeRepository(
            dao,
            FakeUserProfileDao(profile),
            FakeAirportRepository(
                mapOf(
                    "ORI" to testAirport("ORI", 0.0, 0.0, "EU", "FR"),
                    "MID" to testAirport("MID", 0.0, 50.0, "AS", "IN"),
                    "DST" to testAirport("DST", 0.0, 100.0, "OC", "AU"),
                    "CDG" to testAirport("CDG", 49.0, 2.5, "EU", "FR"),
                    "LHR" to testAirport("LHR", 51.5, -0.5, "EU", "GB")
                )
            ),
            TEST_CLOCK,
            roomApi,
            null,
            prefs
        )
    }

    // ── fixtures ─────────────────────────────────────────────────────────────────────────

    private suspend fun distanceRow(target: Double = 1000.0, km: Double = 0.0, roomCode: String? = null): Challenge {
        val id = dao.insert(
            Challenge(
                userId = 1, type = ChallengeType.DISTANCE, source = ChallengeSource.CUSTOM, name = "D",
                targetDistanceKm = target, cumulativeDistanceKm = km, roomCode = roomCode,
                roomState = roomCode?.let { RoomStateCache(SELF, openRoom(it, ChallengeType.DISTANCE)) }
            )
        )
        return dao.getById(id.toInt())!!
    }

    private suspend fun setRow(visited: Set<String> = emptySet(), roomCode: String? = null): Challenge {
        val def = CuratedChallengeSets.G7_CAPITALS
        val id = dao.insert(
            Challenge(
                userId = 1, type = ChallengeType.SET_COMPLETION, source = ChallengeSource.CURATED, name = "S",
                setCatalogId = def.catalogId, setMemberKind = def.memberKind, setTotalMembers = def.members.size,
                visitedSetMembers = visited, roomCode = roomCode,
                roomState = roomCode?.let { RoomStateCache(SELF, openRoom(it, ChallengeType.SET_COMPLETION)) }
            )
        )
        return dao.getById(id.toInt())!!
    }

    private suspend fun streakRow(targetDays: Int = 3, streakDays: Int = 0, lastFlownDay: String? = null, roomCode: String? = null): Challenge {
        val id = dao.insert(
            Challenge(
                userId = 1, type = ChallengeType.STREAK, source = ChallengeSource.CUSTOM, name = "K",
                targetDays = targetDays, streakDays = streakDays, lastFlownDay = lastFlownDay, roomCode = roomCode,
                roomState = roomCode?.let { RoomStateCache(SELF, openRoom(it, ChallengeType.STREAK)) }
            )
        )
        return dao.getById(id.toInt())!!
    }

    private suspend fun routeRow(positionIata: String = "ORI", legIndex: Int = 0, paused: PausedFlight? = null, roomCode: String? = null): Challenge {
        val id = dao.insert(
            Challenge(
                userId = 1, type = ChallengeType.ROUTE, source = ChallengeSource.CUSTOM, name = "R",
                originIata = "ORI", destIata = "DST", positionIata = positionIata, legIndex = legIndex,
                pausedFlight = paused, roomCode = roomCode,
                roomState = roomCode?.let { RoomStateCache(SELF, openRoom(it, ChallengeType.ROUTE)) }
            )
        )
        return dao.getById(id.toInt())!!
    }

    private fun definition(type: ChallengeType, predefinedRouteId: String? = null) = RoomDefinition(
        type = type,
        source = ChallengeSource.CUSTOM,
        name = "Anna's",
        originIata = "ORI",
        destIata = "DST",
        predefinedRouteId = predefinedRouteId,
        setCatalogId = "g7_capitals",
        setMemberKind = SetMemberKind.IATA,
        targetDistanceKm = 1000.0,
        targetDays = 3
    )

    /** A room Anna created, with self not yet in it. */
    private fun openRoom(code: String, type: ChallengeType, anna: ParticipantSnapshot = ParticipantSnapshot(ANNA, "Anna", colorIndex = 0)) =
        RoomState(code = code, definition = definition(type), createdAt = DAY_1, participants = listOf(anna), version = 1L)

    private fun pausedFlight(id: Int) = PausedFlight("BT1", "ORI", "MID", 60, FlightMode.CHALLENGE, id, elapsedMs = 1000L)

    // ── Sharing ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `S3 a fresh challenge is shared, creating the room and linking the row`() = runTest {
        val row = distanceRow()

        val result = repository.shareChallenge(row.id)

        assertTrue(result.toString(), result is ShareResult.Shared)
        val shared = result as ShareResult.Shared
        val stored = dao.getById(row.id)!!
        assertEquals(shared.code, stored.roomCode)
        assertEquals(SELF, stored.roomState?.selfCode)
        assertEquals(shared.code, shared.challenge.roomCode)
        val room = fake.room(shared.code)!!
        assertEquals(listOf(SELF), room.participants.map { it.userCode })
        assertEquals(0, room.participants.single().colorIndex)
        assertEquals("Cap", room.participants.single().username)
        assertEquals(ChallengeType.DISTANCE, room.definition.type)
        assertEquals(1000.0, room.definition.targetDistanceKm)
        // Linked with the scoped statement, never a whole-row write.
        assertTrue(dao.calls.contains("updateRoomLink"))
        assertFalse(dao.calls.contains("update"))
    }

    @Test
    fun `S3 sharing binds the caller identity on the fake before the first call`() = runTest {
        val bare = FakeRoomApi { DAY_3 }
        val repo = LocalChallengeRepository(dao, FakeUserProfileDao(profile), FakeAirportRepository(emptyMap()), TEST_CLOCK, bare, null, prefs)
        val row = streakRow()

        val result = repo.shareChallenge(row.id)

        assertTrue(result.toString(), result is ShareResult.Shared)
        assertEquals(SELF, bare.callerUserCode)
    }

    @Test
    fun `S4 a distance challenge with kilometres is not shared`() = runTest {
        val row = distanceRow(km = 10.0)
        assertEquals(ShareResult.NotEligible(ShareIneligibility.HAS_PROGRESS), repository.shareChallenge(row.id))
        assertEquals(emptyList<String>(), roomApi.calls)
        assertNull(dao.getById(row.id)!!.roomCode)
    }

    @Test
    fun `S4 a set challenge with a visited member is not shared`() = runTest {
        val row = setRow(visited = setOf("CDG"))
        assertEquals(ShareResult.NotEligible(ShareIneligibility.HAS_PROGRESS), repository.shareChallenge(row.id))
        assertEquals(emptyList<String>(), roomApi.calls)
    }

    @Test
    fun `S4 a streak challenge with a credited day is not shared`() = runTest {
        val row = streakRow(streakDays = 1, lastFlownDay = "2026-03-02")
        assertEquals(ShareResult.NotEligible(ShareIneligibility.HAS_PROGRESS), repository.shareChallenge(row.id))
        assertEquals(emptyList<String>(), roomApi.calls)
    }

    @Test
    fun `S4 a route challenge that left its origin is not shared`() = runTest {
        val row = routeRow(positionIata = "MID")
        assertEquals(ShareResult.NotEligible(ShareIneligibility.HAS_PROGRESS), repository.shareChallenge(row.id))
        val onLegOne = routeRow(legIndex = 1)
        assertEquals(ShareResult.NotEligible(ShareIneligibility.HAS_PROGRESS), repository.shareChallenge(onLegOne.id))
        assertEquals(emptyList<String>(), roomApi.calls)
    }

    @Test
    fun `S4 a route challenge with a paused leg is not shared even at its origin`() = runTest {
        val row = routeRow(paused = pausedFlight(1))
        assertEquals(ShareResult.NotEligible(ShareIneligibility.HAS_PAUSED_LEG), repository.shareChallenge(row.id))
        assertEquals(emptyList<String>(), roomApi.calls)
    }

    @Test
    fun `S5 an already shared row is not shared again`() = runTest {
        val row = distanceRow(roomCode = ROOM)
        assertEquals(ShareResult.NotEligible(ShareIneligibility.ALREADY_SHARED), repository.shareChallenge(row.id))
        assertEquals(emptyList<String>(), roomApi.calls)
    }

    @Test
    fun `S6 an unreachable server shares nothing and changes nothing`() = runTest {
        fake.unreachable = true
        val row = distanceRow()

        assertEquals(ShareResult.Unavailable(RoomResult.Unreachable), repository.shareChallenge(row.id))

        assertNull(dao.getById(row.id)!!.roomCode)
        assertEquals(emptySet<String>(), prefs.getPendingRoomLeaves())
    }

    @Test
    fun `S7 a terminal row is not shared`() = runTest {
        val row = distanceRow()
        dao.update(row.copy(status = ChallengeStatus.COMPLETED, completedAt = DAY_1))
        assertEquals(ShareResult.NotEligible(ShareIneligibility.NOT_ACTIVE), repository.shareChallenge(row.id))
        assertEquals(emptyList<String>(), roomApi.calls)
    }

    @Test
    fun `S8 a landing between creating the room and taking the lock fails the re-check and queues the leave`() = runTest {
        val row = distanceRow()
        roomApi.onCall = { if (it == "createRoom") repository.creditEligibleFlight("DST", 100.0, DAY_1) }

        val result = repository.shareChallenge(row.id)

        assertEquals(ShareResult.NotEligible(ShareIneligibility.HAS_PROGRESS), result)
        val stored = dao.getById(row.id)!!
        assertNull(stored.roomCode)
        assertEquals(100.0, stored.cumulativeDistanceKm, 0.0)
        // The room exists on the server, so it has to be left: by the syncer, through the queue.
        assertEquals(1, prefs.getPendingRoomLeaves().size)
        assertNotNull(fake.room(prefs.getPendingRoomLeaves().single()))
    }

    @Test
    fun `S8 an abandon between creating the room and taking the lock is reported as unknown and queues the leave`() = runTest {
        val row = distanceRow()
        roomApi.onCall = { if (it == "createRoom") repository.abandonChallenge(row.id) }

        assertEquals(ShareResult.NotEligible(ShareIneligibility.UNKNOWN), repository.shareChallenge(row.id))

        assertEquals(1, prefs.getPendingRoomLeaves().size)
        assertNull(dao.getById(row.id))
    }

    // ── Joining ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `J3 an unknown code is not found`() = runTest {
        assertEquals(JoinResult.NotFound, repository.joinRoom("NOPE00"))
        assertEquals(0, dao.rows.size)
    }

    @Test
    fun `J4 joining a distance room builds the row and puts the first snapshot`() = runTest {
        fake.seedRoom(openRoom(ROOM, ChallengeType.DISTANCE))

        val result = repository.joinRoom(ROOM)

        val joined = result as JoinResult.Joined
        val row = dao.getById(joined.challenge.id)!!
        assertEquals(ROOM, row.roomCode)
        assertEquals(ChallengeType.DISTANCE, row.type)
        assertEquals(1000.0, row.targetDistanceKm)
        assertEquals(0.0, row.cumulativeDistanceKm, 0.0)
        assertEquals("Anna's", row.name)
        assertEquals(SELF, row.roomState?.selfCode)
        // The cache is the reply to the put, so self is in it with the joiner's colour.
        assertEquals(listOf(ANNA, SELF), row.roomState?.room?.participants?.map { it.userCode })
        assertEquals(1, row.roomState?.room?.participants?.last()?.colorIndex)
        assertEquals(listOf(ANNA, SELF), fake.room(ROOM)!!.participants.map { it.userCode })
        assertEquals(listOf("getRoom", "putSnapshot"), roomApi.calls)
    }

    @Test
    fun `J4 joining a set room resolves the definition`() = runTest {
        fake.seedRoom(openRoom(ROOM, ChallengeType.SET_COMPLETION))

        val row = (repository.joinRoom(ROOM) as JoinResult.Joined).challenge

        assertEquals("g7_capitals", row.setCatalogId)
        assertEquals(SetMemberKind.IATA, row.setMemberKind)
        assertEquals(CuratedChallengeSets.G7_CAPITALS.members.size, row.setTotalMembers)
        assertTrue(row.visitedSetMembers.isEmpty())
    }

    @Test
    fun `J4 joining a streak room starts at zero days`() = runTest {
        fake.seedRoom(openRoom(ROOM, ChallengeType.STREAK))

        val row = (repository.joinRoom(ROOM) as JoinResult.Joined).challenge

        assertEquals(3, row.targetDays)
        assertEquals(0, row.streakDays)
        assertNull(row.lastFlownDay)
    }

    @Test
    fun `J4 joining a free-form route room starts at the origin`() = runTest {
        fake.seedRoom(openRoom(ROOM, ChallengeType.ROUTE))

        val row = (repository.joinRoom(ROOM) as JoinResult.Joined).challenge

        assertEquals("ORI", row.originIata)
        assertEquals("DST", row.destIata)
        assertEquals("ORI", row.positionIata)
        assertEquals(0, row.legIndex)
        assertNull(row.predefinedRouteId)
    }

    @Test
    fun `J4 joining a predefined route room seeds the position from the itinerary`() = runTest {
        val room = openRoom(ROOM, ChallengeType.ROUTE).let { it.copy(definition = definition(ChallengeType.ROUTE, predefinedRouteId = "race_of_mercy")) }
        fake.seedRoom(room)

        val row = (repository.joinRoom(ROOM) as JoinResult.Joined).challenge

        assertEquals("race_of_mercy", row.predefinedRouteId)
        assertEquals("ANC", row.originIata)
        assertEquals("OME", row.destIata)
        assertEquals("ANC", row.positionIata)
        assertEquals(0, row.legIndex)
    }

    @Test
    fun `J4 the code is normalised before the look-up`() = runTest {
        fake.seedRoom(openRoom(ROOM, ChallengeType.DISTANCE))

        val row = (repository.joinRoom(" room42\n") as JoinResult.Joined).challenge

        assertEquals(ROOM, row.roomCode)
    }

    @Test
    fun `J5 a cap filled after the server accepted leaves the room again`() = runTest {
        fake.seedRoom(openRoom(ROOM, ChallengeType.DISTANCE))
        repository.startCustomDistanceChallenge(100.0, "A")
        repository.startCustomDistanceChallenge(100.0, "B")
        // The third slot fills while the put is in flight, after the pre-check passed.
        roomApi.onCall = { if (it == "putSnapshot") repository.startCustomDistanceChallenge(100.0, "C") }

        assertEquals(JoinResult.CapReached, repository.joinRoom(ROOM))

        assertEquals(3, dao.rows.size)
        assertTrue(dao.rows.values.none { it.roomCode == ROOM })
        assertTrue(fake.room(ROOM)!!.participants.first { it.userCode == SELF }.left)
        assertEquals(listOf("getRoom", "putSnapshot", "leaveRoom"), roomApi.calls)
    }

    @Test
    fun `J5 a cap already full is refused before anything is sent`() = runTest {
        fake.seedRoom(openRoom(ROOM, ChallengeType.DISTANCE))
        repeat(3) { repository.startCustomDistanceChallenge(100.0, "X$it") }

        assertEquals(JoinResult.CapReached, repository.joinRoom(ROOM))

        assertEquals(listOf("getRoom"), roomApi.calls)
        assertEquals(listOf(ANNA), fake.room(ROOM)!!.participants.map { it.userCode })
    }

    @Test
    fun `J6a a local active row with this code opens that row instead`() = runTest {
        val row = distanceRow(roomCode = ROOM)
        assertEquals(JoinResult.AlreadyJoined(row.id), repository.joinRoom(ROOM))
        assertEquals(emptyList<String>(), roomApi.calls)
    }

    @Test
    fun `J6b a local completed row with this code is final`() = runTest {
        val row = distanceRow(roomCode = ROOM)
        dao.update(row.copy(status = ChallengeStatus.COMPLETED, celebrated = true, completedAt = DAY_1))
        assertEquals(JoinResult.AlreadyFinished, repository.joinRoom(ROOM))
        assertEquals(emptyList<String>(), roomApi.calls)
        assertEquals(1, dao.rows.size)
    }

    @Test
    fun `J6a findByRoomCode normalises case and surrounding spaces and asks no server`() = runTest {
        val row = distanceRow(roomCode = ROOM)
        assertEquals(row.id, repository.findByRoomCode("  room42 ")?.id)
        assertNull(repository.findByRoomCode("ROOM43"))
        assertEquals(emptyList<String>(), roomApi.calls)
    }

    @Test
    fun `J7 a room with an outcome is closed`() = runTest {
        fake.seedRoom(openRoom(ROOM, ChallengeType.DISTANCE).copy(outcome = SharedOutcome.Completed(ANNA, at = DAY_1)))
        assertEquals(JoinResult.RoomClosed, repository.joinRoom(ROOM))
        assertEquals(listOf("getRoom"), roomApi.calls)
        assertEquals(0, dao.rows.size)
    }

    @Test
    fun `J8 a race with progress is locked`() = runTest {
        fake.seedRoom(openRoom(ROOM, ChallengeType.ROUTE, ParticipantSnapshot(ANNA, "Anna", positionIata = "MID", routeProgress = 0.5f)))
        assertEquals(JoinResult.RaceLocked, repository.joinRoom(ROOM))
        assertEquals(listOf("getRoom"), roomApi.calls)
    }

    @Test
    fun `J9 a pool already in progress can still be joined`() = runTest {
        fake.seedRoom(openRoom(ROOM, ChallengeType.DISTANCE, ParticipantSnapshot(ANNA, "Anna", distanceKm = 400.0)))
        assertTrue(repository.joinRoom(ROOM) is JoinResult.Joined)
    }

    @Test
    fun `J10 a full room refuses the join`() = runTest {
        val crew = (1..6).map { ParticipantSnapshot("CREW0$it", "Pilot $it", colorIndex = it - 1) }
        fake.seedRoom(openRoom(ROOM, ChallengeType.DISTANCE).copy(participants = crew))
        assertEquals(JoinResult.RoomFull, repository.joinRoom(ROOM))
        assertEquals(listOf("getRoom"), roomApi.calls)
    }

    @Test
    fun `J12 a definition this app cannot build is an unknown template and sends nothing`() = runTest {
        fake.seedRoom(openRoom(ROOM, ChallengeType.ROUTE).copy(definition = definition(ChallengeType.ROUTE, predefinedRouteId = "route_from_the_future")))
        assertEquals(JoinResult.UnknownTemplate, repository.joinRoom(ROOM))
        assertEquals(listOf("getRoom"), roomApi.calls)
        assertEquals(listOf(ANNA), fake.room(ROOM)!!.participants.map { it.userCode })
    }

    @Test
    fun `J13 joining removes the code from the pending leaves`() = runTest {
        fake.seedRoom(openRoom(ROOM, ChallengeType.DISTANCE))
        prefs.addPendingRoomLeave(ROOM)
        prefs.addPendingRoomLeave("OTHER1")

        assertTrue(repository.joinRoom(ROOM) is JoinResult.Joined)

        assertEquals(setOf("OTHER1"), prefs.getPendingRoomLeaves())
    }

    @Test
    fun `J11 an unreachable server joins nothing`() = runTest {
        fake.seedRoom(openRoom(ROOM, ChallengeType.DISTANCE))
        fake.unreachable = true
        assertEquals(JoinResult.Unavailable(RoomResult.Unreachable), repository.joinRoom(ROOM))
        assertEquals(0, dao.rows.size)
    }

    @Test
    fun `lookUpRoom normalises the code and returns the room`() = runTest {
        fake.seedRoom(openRoom(ROOM, ChallengeType.STREAK))
        val result = repository.lookUpRoom(" room42 ")
        assertEquals(ROOM, (result as RoomResult.Ok).value.code)
        assertEquals(RoomResult.NotFound, repository.lookUpRoom("ZZZZZZ"))
    }

    @Test
    fun `startCuratedChallenge still builds the same rows through the shared builder`() = runTest {
        val route = (repository.startCuratedChallenge("route_race_of_mercy") as StartChallengeResult.Started).challenge
        assertEquals("ANC", route.positionIata)
        assertEquals("OME", route.destIata)
        assertEquals("race_of_mercy", route.predefinedRouteId)
        assertEquals(ChallengeSource.CURATED, route.source)
        val set = (repository.startCuratedChallenge("set_g7_capitals") as StartChallengeResult.Started).challenge
        assertEquals(CuratedChallengeSets.G7_CAPITALS.members.size, set.setTotalMembers)
        assertEquals(StartChallengeResult.UnknownTemplate, repository.startCuratedChallenge("not_a_real_template"))
    }

    // ── Landing on a shared row ──────────────────────────────────────────────────────────

    @Test
    fun `L1 a shared pool short of its team target is credited and its generation bumped`() = runTest {
        val row = distanceRow(roomCode = ROOM)

        repository.creditEligibleFlight("DST", 200.0, DAY_1)

        val stored = dao.getById(row.id)!!
        assertEquals(ChallengeStatus.ACTIVE, stored.status)
        assertEquals(200.0, stored.cumulativeDistanceKm, 0.0)
        assertEquals(1L, stored.syncGeneration)
        assertEquals(0L, stored.syncedGeneration)
        assertNull(stored.sharedOutcome)
    }

    @Test
    fun `L2 a landing that fills a shared distance pool with the cached others completes it as the pilot's own`() = runTest {
        val anna = ParticipantSnapshot(ANNA, "Anna", distanceKm = 800.0)
        val id = dao.insert(
            Challenge(
                userId = 1, type = ChallengeType.DISTANCE, source = ChallengeSource.CUSTOM, name = "D",
                targetDistanceKm = 1000.0, cumulativeDistanceKm = 0.0, roomCode = ROOM,
                roomState = RoomStateCache(SELF, openRoom(ROOM, ChallengeType.DISTANCE, anna))
            )
        ).toInt()

        repository.creditEligibleFlight("DST", 300.0, DAY_1)

        val stored = dao.getById(id)!!
        assertEquals(ChallengeStatus.COMPLETED, stored.status)
        assertFalse(stored.celebrated)
        assertEquals(300.0, stored.cumulativeDistanceKm, 0.0)
        assertEquals(1L, stored.syncGeneration)
        val outcome = stored.sharedOutcome as SharedOutcome.Completed
        assertEquals(SELF, outcome.byUserCode)
        assertTrue(outcome.bySelf)
        assertEquals(stored.completedAt, outcome.at)
    }

    @Test
    fun `L2 a landing that completes a shared set with the cached others completes it as the pilot's own`() = runTest {
        val allButOne = CuratedChallengeSets.G7_CAPITALS.members - "CDG"
        val anna = ParticipantSnapshot(ANNA, "Anna", visitedMembers = allButOne)
        val id = dao.insert(
            Challenge(
                userId = 1, type = ChallengeType.SET_COMPLETION, source = ChallengeSource.CURATED, name = "S",
                setCatalogId = "g7_capitals", setMemberKind = SetMemberKind.IATA, setTotalMembers = 7,
                roomCode = ROOM, roomState = RoomStateCache(SELF, openRoom(ROOM, ChallengeType.SET_COMPLETION, anna))
            )
        ).toInt()

        repository.creditEligibleFlight("CDG", 300.0, DAY_1)

        val stored = dao.getById(id)!!
        assertEquals(ChallengeStatus.COMPLETED, stored.status)
        assertEquals(setOf("CDG"), stored.visitedSetMembers)
        assertEquals(1L, stored.syncGeneration)
        assertTrue((stored.sharedOutcome as SharedOutcome.Completed).bySelf)
    }

    @Test
    fun `L1 a shared set landing that adds nothing new writes nothing`() = runTest {
        val row = setRow(visited = setOf("CDG"), roomCode = ROOM)
        dao.calls.clear()

        repository.creditEligibleFlight("CDG", 300.0, DAY_1)

        assertEquals(0L, dao.getById(row.id)!!.syncGeneration)
        assertFalse(dao.calls.contains("update"))
    }

    @Test
    fun `L4 a shared streak is credited but never completed by a landing`() = runTest {
        val shared = streakRow(targetDays = 1, roomCode = ROOM)
        val solo = streakRow(targetDays = 1)

        repository.creditEligibleFlight("DST", 100.0, DAY_3)

        val storedShared = dao.getById(shared.id)!!
        assertEquals(ChallengeStatus.ACTIVE, storedShared.status)
        assertEquals(1, storedShared.streakDays)
        assertEquals("2026-03-04", storedShared.lastFlownDay)
        assertEquals(1L, storedShared.syncGeneration)
        assertNull(storedShared.completedAt)
        // The solo row keeps today's behaviour: the target is met, so it completes.
        val storedSolo = dao.getById(solo.id)!!
        assertEquals(ChallengeStatus.COMPLETED, storedSolo.status)
        assertEquals(0L, storedSolo.syncGeneration)
    }

    @Test
    fun `L5 a leg flown under a shared race bumps the generation`() = runTest {
        val row = routeRow(roomCode = ROOM)

        val updated = repository.advanceRouteChallenge(row.id, "MID")!!

        assertEquals(ChallengeStatus.ACTIVE, updated.status)
        assertEquals("MID", dao.getById(row.id)!!.positionIata)
        assertEquals(1L, dao.getById(row.id)!!.syncGeneration)
        assertTrue(dao.calls.contains("updateRouteProgress"))
        assertFalse(dao.calls.contains("update"))
    }

    @Test
    fun `L6 an arrival under a shared race completes it locally, the outcome coming with the claim's reply`() = runTest {
        val row = routeRow(roomCode = ROOM)

        repository.advanceRouteChallenge(row.id, "DST")

        val stored = dao.getById(row.id)!!
        assertEquals(ChallengeStatus.COMPLETED, stored.status)
        assertFalse(stored.celebrated)
        assertEquals(1L, stored.syncGeneration)
        assertNull(stored.sharedOutcome)
    }

    @Test
    fun `every shared credit bumps the generation once`() = runTest {
        val row = distanceRow(roomCode = ROOM)

        repository.creditEligibleFlight("DST", 100.0, DAY_1)
        repository.creditEligibleFlight("MID", 100.0, DAY_1)

        assertEquals(2L, dao.getById(row.id)!!.syncGeneration)
    }

    @Test
    fun `solo rows are credited exactly as before, with no generation and no outcome`() = runTest {
        val distance = distanceRow(target = 100.0)
        val set = setRow()
        val route = routeRow()

        repository.creditEligibleFlight("CDG", 150.0, DAY_1)
        repository.advanceRouteChallenge(route.id, "MID")

        val storedDistance = dao.getById(distance.id)!!
        assertEquals(ChallengeStatus.COMPLETED, storedDistance.status)
        assertNull(storedDistance.sharedOutcome)
        assertEquals(0L, storedDistance.syncGeneration)
        val storedSet = dao.getById(set.id)!!
        assertEquals(setOf("CDG"), storedSet.visitedSetMembers)
        assertEquals(0L, storedSet.syncGeneration)
        assertEquals(0L, dao.getById(route.id)!!.syncGeneration)
    }

    // ── Applying a room state ────────────────────────────────────────────────────────────

    @Test
    fun `P1 a room state that only moves the cache is written with the cache statement alone`() = runTest {
        val row = distanceRow(roomCode = ROOM)
        val room = openRoom(ROOM, ChallengeType.DISTANCE, ParticipantSnapshot(ANNA, "Anna", distanceKm = 50.0)).copy(version = 2L)
        dao.calls.clear()

        val result = repository.applyRoomState(row.id, room)!!

        assertFalse(result.changed)
        assertNull(result.claim)
        assertEquals(listOf("updateRoomState"), dao.calls)
        assertEquals(2L, dao.getById(row.id)!!.roomState?.room?.version)
        assertEquals(ChallengeStatus.ACTIVE, dao.getById(row.id)!!.status)
    }

    @Test
    fun `P3 a foreign completion of a pool is written with the shared-fields statement`() = runTest {
        val row = distanceRow(roomCode = ROOM)
        val room = openRoom(ROOM, ChallengeType.DISTANCE).copy(outcome = SharedOutcome.Completed(ANNA, at = DAY_3))
        dao.calls.clear()

        val result = repository.applyRoomState(row.id, room)!!

        assertTrue(result.changed)
        assertEquals(listOf("updateSharedFields"), dao.calls)
        val stored = dao.getById(row.id)!!
        assertEquals(ChallengeStatus.COMPLETED, stored.status)
        assertFalse(stored.celebrated)
        assertEquals(DAY_3, stored.completedAt)
        assertFalse((stored.sharedOutcome as SharedOutcome.Completed).bySelf)
    }

    @Test
    fun `P6 a foreign win ends a race and clears its paused leg in one statement`() = runTest {
        val row = routeRow(paused = pausedFlight(1), roomCode = ROOM)
        val room = openRoom(ROOM, ChallengeType.ROUTE).copy(outcome = SharedOutcome.Completed(ANNA, at = DAY_3, placements = listOf(ANNA, SELF)))
        dao.calls.clear()

        repository.applyRoomState(row.id, room)

        assertEquals(listOf("updateSharedFieldsAndClearPausedFlight"), dao.calls)
        val stored = dao.getById(row.id)!!
        assertNull(stored.pausedFlight)
        assertEquals(ChallengeStatus.COMPLETED, stored.status)
    }

    @Test
    fun `a foreign outcome on a pool never touches the paused-flight column`() = runTest {
        val row = distanceRow(roomCode = ROOM)
        dao.updatePausedFlight(row.id, pausedFlight(row.id))
        val room = openRoom(ROOM, ChallengeType.DISTANCE).copy(outcome = SharedOutcome.Completed(ANNA, at = DAY_3))
        dao.calls.clear()

        repository.applyRoomState(row.id, room)

        assertEquals(listOf("updateSharedFields"), dao.calls)
        assertNotNull(dao.getById(row.id)!!.pausedFlight)
    }

    @Test
    fun `P2 a pool the others filled completes with a claim`() = runTest {
        val row = distanceRow(km = 500.0, roomCode = ROOM)
        val room = openRoom(ROOM, ChallengeType.DISTANCE, ParticipantSnapshot(ANNA, "Anna", distanceKm = 600.0))

        val result = repository.applyRoomState(row.id, room)!!

        assertNotNull(result.claim)
        assertEquals(ChallengeStatus.COMPLETED, dao.getById(row.id)!!.status)
        assertTrue((dao.getById(row.id)!!.sharedOutcome as SharedOutcome.Completed).bySelf)
    }

    @Test
    fun `P15 applying a room state to a deleted row does nothing and never re-inserts`() = runTest {
        val row = distanceRow(roomCode = ROOM)
        repository.abandonChallenge(row.id)
        dao.calls.clear()

        assertNull(repository.applyRoomState(row.id, openRoom(ROOM, ChallengeType.DISTANCE)))

        assertEquals(emptyList<String>(), dao.calls)
        assertEquals(0, dao.rows.size)
    }

    @Test
    fun `the sync path never uses the whole-row update`() = runTest {
        val row = distanceRow(roomCode = ROOM)
        dao.calls.clear()

        repository.applyRoomState(row.id, openRoom(ROOM, ChallengeType.DISTANCE).copy(version = 5L))
        repository.applyRoomState(row.id, openRoom(ROOM, ChallengeType.DISTANCE).copy(outcome = SharedOutcome.Completed(ANNA, at = DAY_3)))
        repository.markRoomGone(row.id)
        repository.confirmSynced(row.id, 0L)

        assertFalse(dao.calls.toString(), dao.calls.contains("update"))
    }

    @Test
    fun `P11 markRoomGone flags the cache and drops the row from the syncable list`() = runTest {
        val row = distanceRow(roomCode = ROOM)
        assertEquals(listOf(row.id), repository.listSyncableChallenges().map { it.id })

        repository.markRoomGone(row.id)

        assertTrue(dao.getById(row.id)!!.roomState!!.room.roomGone)
        assertEquals(ChallengeStatus.ACTIVE, dao.getById(row.id)!!.status)
        assertEquals(emptyList<Int>(), repository.listSyncableChallenges().map { it.id })
    }

    @Test
    fun `listSyncableChallenges keeps unpresented terminal rows and drops presented ones and solo rows`() = runTest {
        val active = distanceRow(roomCode = ROOM)
        val unpresented = distanceRow(roomCode = "ROOM02")
        dao.update(dao.getById(unpresented.id)!!.copy(status = ChallengeStatus.COMPLETED, completedAt = DAY_1))
        val presented = distanceRow(roomCode = "ROOM03")
        dao.update(dao.getById(presented.id)!!.copy(status = ChallengeStatus.COMPLETED, completedAt = DAY_1, celebrated = true))
        distanceRow()

        assertEquals(listOf(active.id, unpresented.id), repository.listSyncableChallenges().map { it.id })
    }

    @Test
    fun `L11 confirmSynced is a no-op once the generation moved on`() = runTest {
        val row = distanceRow(roomCode = ROOM)
        repository.creditEligibleFlight("DST", 100.0, DAY_1)
        repository.creditEligibleFlight("MID", 100.0, DAY_1)

        repository.confirmSynced(row.id, 1L)
        assertEquals(0L, dao.getById(row.id)!!.syncedGeneration)

        repository.confirmSynced(row.id, 2L)
        assertEquals(2L, dao.getById(row.id)!!.syncedGeneration)
    }

    @Test
    fun `dismissFailed deletes a failed row and leaves every other row alone`() = runTest {
        val failed = streakRow(roomCode = ROOM)
        dao.update(dao.getById(failed.id)!!.copy(status = ChallengeStatus.FAILED, completedAt = DAY_1))
        val completed = distanceRow()
        dao.update(dao.getById(completed.id)!!.copy(status = ChallengeStatus.COMPLETED, completedAt = DAY_1))
        val active = distanceRow()

        repository.dismissFailed(failed.id)
        repository.dismissFailed(completed.id)
        repository.dismissFailed(active.id)
        repository.dismissFailed(999)

        assertNull(dao.getById(failed.id))
        assertNotNull(dao.getById(completed.id))
        assertNotNull(dao.getById(active.id))
    }

    @Test
    fun `hasPendingPresentation follows the unpresented terminal rows`() = runTest {
        assertFalse(repository.hasPendingPresentation())
        val row = distanceRow()
        assertFalse(repository.hasPendingPresentation())

        dao.update(dao.getById(row.id)!!.copy(status = ChallengeStatus.FAILED, completedAt = DAY_1))
        assertTrue(repository.hasPendingPresentation())

        repository.markCelebrated(row.id)
        assertFalse(repository.hasPendingPresentation())
    }

    // ── Abandoning ───────────────────────────────────────────────────────────────────────

    @Test
    fun `A2 abandoning a shared row deletes it and queues the room leave for the next sync`() = runTest {
        val row = distanceRow(roomCode = ROOM)

        repository.abandonChallenge(row.id)

        assertNull(dao.getById(row.id))
        assertEquals(setOf(ROOM), prefs.getPendingRoomLeaves())
        // Never under the lock: the leave itself is the syncer's, not the repository's.
        assertEquals(emptyList<String>(), roomApi.calls)
    }

    @Test
    fun `A1 abandoning a solo row queues nothing`() = runTest {
        val row = distanceRow()

        repository.abandonChallenge(row.id)

        assertNull(dao.getById(row.id))
        assertEquals(emptySet<String>(), prefs.getPendingRoomLeaves())
    }
}
