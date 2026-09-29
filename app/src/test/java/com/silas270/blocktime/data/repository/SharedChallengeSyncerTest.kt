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
import com.silas270.blocktime.data.model.UserProfile
import com.silas270.blocktime.data.model.isSyncPending
import com.silas270.blocktime.data.network.ServerReachability
import com.silas270.blocktime.data.network.ServerState
import com.silas270.blocktime.data.network.room.FakeRoomApi
import com.silas270.blocktime.testutil.FakeAirportRepository
import com.silas270.blocktime.testutil.FakeChallengeDao
import com.silas270.blocktime.testutil.FakeSharedPreferences
import com.silas270.blocktime.testutil.FakeUserProfileDao
import com.silas270.blocktime.testutil.RecordingRoomApi
import com.silas270.blocktime.testutil.testAirport
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * [SharedChallengeSyncer] against the real [LocalChallengeRepository], a [FakeRoomApi] behind a
 * recorder and a real [ServerReachability]: docs/shared-challenges.md "Sync moments" and
 * "Lifecycle and visibility" (Y1), the pending leaves (A2, A3, J13), the upload-or-download
 * choice, the claim round trip (P2), a room the server forgot (P11), an unreachable server
 * (P13), a credit during an upload (L11) and an abandon during a sync (P15).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SharedChallengeSyncerTest {

    private companion object {
        const val SELF = "ABC123"
        const val ANNA = "ANNA02"
        const val ROOM = "ROOM42"
        const val T0 = 1_700_000_000_000L
    }

    /** A clock the test moves by hand, for the 60 s debounce. */
    private class ManualClock(var now: Long) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId?): Clock = this
        override fun instant(): Instant = Instant.ofEpochMilli(now)
    }

    private val clock = ManualClock(T0)
    private val dao = FakeChallengeDao()
    private val fake = FakeRoomApi { clock.now }.apply { callerUserCode = SELF }
    private val roomApi = RecordingRoomApi(fake)
    private val prefs = PreferencesRepository(FakeSharedPreferences()).apply { setOnlineFeaturesEnabled(true) }
    private val connected = MutableStateFlow(true)
    private val profileDao = FakeUserProfileDao(UserProfile(id = 1, username = "Cap", userCode = SELF, homeAirportIata = "ORI"))
    private val repository = LocalChallengeRepository(
        dao, profileDao,
        FakeAirportRepository(mapOf("DST" to testAirport("DST", 0.0, 100.0, "OC", "AU"))),
        clock, roomApi, null, prefs
    )

    private fun TestScope.reachability(configured: Boolean = true) =
        ServerReachability(configured, connected, prefs, { !fake.unreachable }, backgroundScope, clock)

    private fun TestScope.syncer(reachability: ServerReachability = reachability()) =
        SharedChallengeSyncer(repository, LocalUserRepository(profileDao), prefs, roomApi, reachability, backgroundScope, clock)

    private fun definition(type: ChallengeType = ChallengeType.DISTANCE) = RoomDefinition(
        type = type, source = ChallengeSource.CUSTOM, name = "D", targetDistanceKm = 1000.0, targetDays = 3
    )

    private fun room(code: String, vararg participants: ParticipantSnapshot, outcome: SharedOutcome? = null) =
        RoomState(code = code, definition = definition(), createdAt = T0, participants = participants.toList(), outcome = outcome, version = 1L)

    private val self = ParticipantSnapshot(SELF, "Cap", colorIndex = 0, distanceKm = 0.0)
    private val anna = ParticipantSnapshot(ANNA, "Anna", colorIndex = 1, distanceKm = 100.0)

    /** A shared distance row whose room is on the server with self and Anna in it. */
    private suspend fun sharedRow(
        code: String = ROOM,
        km: Double = 0.0,
        syncGeneration: Long = 0L,
        syncedGeneration: Long = 0L,
        status: ChallengeStatus = ChallengeStatus.ACTIVE,
        sharedOutcome: SharedOutcome? = null,
        serverRoom: RoomState? = room(code, self, anna),
    ): Challenge {
        serverRoom?.let { fake.seedRoom(it) }
        val id = dao.insert(
            Challenge(
                userId = 1, type = ChallengeType.DISTANCE, source = ChallengeSource.CUSTOM, name = "D",
                targetDistanceKm = 1000.0, cumulativeDistanceKm = km, roomCode = code,
                roomState = RoomStateCache(SELF, room(code, self, anna)),
                syncGeneration = syncGeneration, syncedGeneration = syncedGeneration,
                status = status, completedAt = if (status == ChallengeStatus.ACTIVE) null else T0,
                sharedOutcome = sharedOutcome
            )
        )
        return dao.getById(id.toInt())!!
    }

    // ── Y1: debounce and skipped states ──────────────────────────────────────────────────

    @Test
    fun `Y1 a second foreground sync within 60 s is skipped, a landing sync is not`() = runTest(UnconfinedTestDispatcher()) {
        sharedRow()
        val syncer = syncer()

        assertEquals(SyncSummary.Synced(1, T0), syncer.syncNow(SyncReason.FOREGROUND))
        assertEquals(1, roomApi.calls.size)

        clock.now += 30_000L
        assertEquals(SyncSummary.Skipped(ServerState.REACHABLE), syncer.syncNow(SyncReason.FOREGROUND))
        assertEquals(SyncSummary.Skipped(ServerState.REACHABLE), syncer.syncNow(SyncReason.SCREEN_OPEN))
        assertEquals(1, roomApi.calls.size)
        // A debounced request is not a sync and leaves the summary alone.
        assertEquals(SyncSummary.Synced(1, T0), syncer.lastSummary.value)

        assertEquals(SyncSummary.Synced(1, T0 + 30_000L), syncer.syncNow(SyncReason.LANDING))
        assertEquals(2, roomApi.calls.size)
        assertTrue(syncer.syncNow(SyncReason.USER_ACTION) is SyncSummary.Synced)
        assertEquals(3, roomApi.calls.size)

        clock.now += 60_000L
        assertTrue(syncer.syncNow(SyncReason.FOREGROUND) is SyncSummary.Synced)
        assertEquals(4, roomApi.calls.size)
    }

    @Test
    fun `a sync is skipped when sharing is off, the device is offline or no server is configured`() = runTest(UnconfinedTestDispatcher()) {
        sharedRow()

        prefs.setOnlineFeaturesEnabled(false)
        val off = syncer(ServerReachability(true, connected, prefs, { true }, backgroundScope, clock))
        assertEquals(SyncSummary.Skipped(ServerState.DISABLED), off.syncNow(SyncReason.LANDING))
        assertEquals(SyncSummary.Skipped(ServerState.DISABLED), off.lastSummary.value)

        prefs.setOnlineFeaturesEnabled(true)
        connected.value = false
        assertEquals(SyncSummary.Skipped(ServerState.DEVICE_OFFLINE), syncer().syncNow(SyncReason.LANDING))

        connected.value = true
        assertEquals(SyncSummary.Skipped(ServerState.NOT_CONFIGURED), syncer(reachability(configured = false)).syncNow(SyncReason.LANDING))

        assertEquals(emptyList<String>(), roomApi.calls)
        assertNull(prefs.getLastRoomSyncAt())
    }

    // ── pending leaves ───────────────────────────────────────────────────────────────────

    @Test
    fun `A2 a pending leave is sent first and removed once the server confirms`() = runTest(UnconfinedTestDispatcher()) {
        fake.seedRoom(room("GONE01", self, anna))
        prefs.addPendingRoomLeave("GONE01")
        prefs.addPendingRoomLeave("NEVER1")

        syncer().syncNow(SyncReason.USER_ACTION)

        assertTrue(fake.room("GONE01")!!.participants.first { it.userCode == SELF }.left)
        // Unknown on the server counts as done: there is nothing left to leave.
        assertEquals(emptySet<String>(), prefs.getPendingRoomLeaves())
        assertEquals(listOf("leaveRoom", "leaveRoom"), roomApi.calls)
    }

    @Test
    fun `A3 a pending leave is kept when the server is unreachable`() = runTest(UnconfinedTestDispatcher()) {
        fake.seedRoom(room("GONE01", self, anna))
        prefs.addPendingRoomLeave("GONE01")
        fake.unreachable = true

        assertEquals(SyncSummary.Unreachable, syncer().syncNow(SyncReason.USER_ACTION))

        assertEquals(setOf("GONE01"), prefs.getPendingRoomLeaves())
        assertFalse(fake.room("GONE01")!!.participants.first { it.userCode == SELF }.left)
    }

    @Test
    fun `J13 a pending leave for a room with a live local row is dropped without leaving`() = runTest(UnconfinedTestDispatcher()) {
        sharedRow()
        prefs.addPendingRoomLeave(ROOM)

        syncer().syncNow(SyncReason.USER_ACTION)

        assertEquals(emptySet<String>(), prefs.getPendingRoomLeaves())
        assertFalse(fake.room(ROOM)!!.participants.first { it.userCode == SELF }.left)
        assertEquals(listOf("getRoom"), roomApi.calls)
    }

    // ── upload or download ───────────────────────────────────────────────────────────────

    @Test
    fun `a pending row is uploaded and its generation confirmed`() = runTest(UnconfinedTestDispatcher()) {
        val row = sharedRow(km = 250.0, syncGeneration = 2L, syncedGeneration = 1L)

        val summary = syncer().syncNow(SyncReason.LANDING)

        assertEquals(SyncSummary.Synced(1, T0), summary)
        assertEquals(listOf("putSnapshot"), roomApi.calls)
        val onServer = fake.room(ROOM)!!.participants.first { it.userCode == SELF }
        assertEquals(250.0, onServer.distanceKm, 0.0)
        assertEquals("Cap", onServer.username)
        assertEquals(0, onServer.colorIndex)
        val stored = dao.getById(row.id)!!
        assertEquals(2L, stored.syncedGeneration)
        assertFalse(stored.isSyncPending())
        assertEquals(2L, stored.roomState?.room?.version)
    }

    @Test
    fun `a row that is not pending is only downloaded`() = runTest(UnconfinedTestDispatcher()) {
        val row = sharedRow(serverRoom = room(ROOM, self, anna.copy(distanceKm = 900.0)))

        syncer().syncNow(SyncReason.LANDING)

        assertEquals(listOf("getRoom"), roomApi.calls)
        val stored = dao.getById(row.id)!!
        assertEquals(900.0, stored.roomState!!.room.participants.first { it.userCode == ANNA }.distanceKm, 0.0)
        assertEquals(ChallengeStatus.ACTIVE, stored.status)
    }

    @Test
    fun `the snapshot takes the pilot's colour from the cached self snapshot`() = runTest(UnconfinedTestDispatcher()) {
        val late = self.copy(colorIndex = 3)
        val id = dao.insert(
            Challenge(
                userId = 1, type = ChallengeType.DISTANCE, source = ChallengeSource.CUSTOM, name = "D",
                targetDistanceKm = 1000.0, roomCode = ROOM, roomState = RoomStateCache(SELF, room(ROOM, anna, late)),
                syncGeneration = 1L
            )
        ).toInt()
        fake.seedRoom(room(ROOM, anna, late))

        syncer().syncNow(SyncReason.LANDING)

        assertEquals(3, fake.room(ROOM)!!.participants.first { it.userCode == SELF }.colorIndex)
        assertEquals(1L, dao.getById(id)!!.syncedGeneration)
    }

    @Test
    fun `L11 a credit during the upload keeps the row pending`() = runTest(UnconfinedTestDispatcher()) {
        val row = sharedRow(syncGeneration = 1L)
        roomApi.onCall = { if (it == "putSnapshot") repository.creditEligibleFlight("DST", 100.0, T0) }

        syncer().syncNow(SyncReason.LANDING)

        val stored = dao.getById(row.id)!!
        assertEquals(2L, stored.syncGeneration)
        assertEquals(0L, stored.syncedGeneration)
        assertTrue(stored.isSyncPending())
        assertEquals(100.0, stored.cumulativeDistanceKm, 0.0)
        // Nothing lost: the next sync sends the newer state.
        roomApi.onCall = {}
        syncer().syncNow(SyncReason.LANDING)
        assertEquals(2L, dao.getById(row.id)!!.syncedGeneration)
        assertEquals(100.0, fake.room(ROOM)!!.participants.first { it.userCode == SELF }.distanceKm, 0.0)
    }

    // ── claims ───────────────────────────────────────────────────────────────────────────

    @Test
    fun `P2 a pool the others filled is completed, claimed at once and finalised from the reply`() = runTest(UnconfinedTestDispatcher()) {
        val row = sharedRow(km = 500.0, serverRoom = room(ROOM, self.copy(distanceKm = 500.0), anna.copy(distanceKm = 600.0)))

        syncer().syncNow(SyncReason.FOREGROUND)

        assertEquals(listOf("getRoom", "putSnapshot+claim"), roomApi.calls)
        val stored = dao.getById(row.id)!!
        assertEquals(ChallengeStatus.COMPLETED, stored.status)
        assertFalse(stored.celebrated)
        val outcome = stored.sharedOutcome as SharedOutcome.Completed
        assertEquals(SELF, outcome.byUserCode)
        assertTrue(outcome.bySelf)
        assertNotNull(stored.roomState?.room?.outcome)
        assertEquals(SELF, (fake.room(ROOM)!!.outcome as SharedOutcome.Completed).byUserCode)
    }

    @Test
    fun `L2 a row completed by a landing sends its claim with the upload and takes the server's outcome`() = runTest(UnconfinedTestDispatcher()) {
        val row = sharedRow(
            km = 1000.0, syncGeneration = 1L, status = ChallengeStatus.COMPLETED,
            sharedOutcome = SharedOutcome.Completed(SELF, bySelf = true, at = T0)
        )

        syncer().syncNow(SyncReason.LANDING)

        assertEquals(listOf("putSnapshot+claim"), roomApi.calls)
        val stored = dao.getById(row.id)!!
        assertEquals(ChallengeStatus.COMPLETED, stored.status)
        assertTrue((stored.sharedOutcome as SharedOutcome.Completed).bySelf)
        assertEquals(1L, stored.syncedGeneration)
        // Once the room has an outcome the row owes nothing more: the next sync only reads.
        syncer().syncNow(SyncReason.LANDING)
        assertEquals(listOf("putSnapshot+claim", "getRoom"), roomApi.calls)
    }

    @Test
    fun `a refused claim takes the placement the server holds`() = runTest(UnconfinedTestDispatcher()) {
        val row = sharedRow(
            km = 1000.0, syncGeneration = 1L, status = ChallengeStatus.COMPLETED,
            sharedOutcome = SharedOutcome.Completed(SELF, bySelf = true, at = T0),
            serverRoom = room(ROOM, self, anna, outcome = SharedOutcome.Completed(ANNA, at = T0 - 1))
        )

        syncer().syncNow(SyncReason.LANDING)

        val outcome = dao.getById(row.id)!!.sharedOutcome as SharedOutcome.Completed
        assertEquals(ANNA, outcome.byUserCode)
        assertFalse(outcome.bySelf)
        assertEquals(ChallengeStatus.COMPLETED, dao.getById(row.id)!!.status)
    }

    @Test
    fun `a completion presented offline is corrected in the log by the server's outcome and then left alone`() = runTest(UnconfinedTestDispatcher()) {
        // Completed and celebrated while offline, before any reply: the cache holds no outcome,
        // so the claim is still owed. Anna's completion reached the server first.
        val row = sharedRow(
            km = 1000.0, syncGeneration = 1L, status = ChallengeStatus.COMPLETED,
            sharedOutcome = SharedOutcome.Completed(SELF, bySelf = true, at = T0),
            serverRoom = room(ROOM, self, anna, outcome = SharedOutcome.Completed(ANNA, at = T0 - 1))
        )
        dao.update(dao.getById(row.id)!!.copy(celebrated = true))

        syncer().syncNow(SyncReason.LANDING)

        val corrected = dao.getById(row.id)!!
        val outcome = corrected.sharedOutcome as SharedOutcome.Completed
        assertEquals(ANNA, outcome.byUserCode)
        assertFalse(outcome.bySelf)
        assertEquals(ChallengeStatus.COMPLETED, corrected.status)
        assertTrue(corrected.celebrated)
        assertEquals(T0, corrected.completedAt)

        // Confirmed now: the row leaves the syncable list and the next sync never touches it.
        roomApi.calls.clear()
        syncer().syncNow(SyncReason.LANDING)
        assertEquals(emptyList<String>(), roomApi.calls)
    }

    // ── the server's failures ────────────────────────────────────────────────────────────

    @Test
    fun `P11 a room the server no longer knows is marked gone and skipped from then on`() = runTest(UnconfinedTestDispatcher()) {
        val row = sharedRow(serverRoom = null)
        val syncer = syncer()

        assertEquals(SyncSummary.Synced(0, T0), syncer.syncNow(SyncReason.LANDING))

        val stored = dao.getById(row.id)!!
        assertTrue(stored.roomState!!.room.roomGone)
        assertEquals(ChallengeStatus.ACTIVE, stored.status)
        assertEquals(listOf("getRoom"), roomApi.calls)

        syncer.syncNow(SyncReason.LANDING)
        assertEquals(listOf("getRoom"), roomApi.calls)
    }

    @Test
    fun `P13 an unreachable server stops the sync, reports it and leaves the rest for next time`() = runTest(UnconfinedTestDispatcher()) {
        sharedRow(code = "ROOM01", syncGeneration = 1L, serverRoom = room("ROOM01", self, anna))
        sharedRow(code = "ROOM02", syncGeneration = 1L, serverRoom = room("ROOM02", self, anna))
        val reachability = reachability()
        assertEquals(ServerState.REACHABLE, reachability.check())
        fake.unreachable = true
        val syncer = syncer(reachability)

        assertEquals(SyncSummary.Unreachable, syncer.syncNow(SyncReason.LANDING))

        assertEquals(1, roomApi.calls.size)
        assertEquals(ServerState.UNREACHABLE, reachability.state.value)
        assertTrue(dao.rows.values.all { it.isSyncPending() })
        assertNull(prefs.getLastRoomSyncAt())
        assertEquals(SyncSummary.Unreachable, syncer.lastSummary.value)
    }

    @Test
    fun `P15 a row abandoned during the sync is never re-inserted`() = runTest(UnconfinedTestDispatcher()) {
        val row = sharedRow(syncGeneration = 1L)
        roomApi.onCall = { if (it == "putSnapshot") repository.abandonChallenge(row.id) }
        dao.calls.clear()

        assertEquals(SyncSummary.Synced(1, T0), syncer().syncNow(SyncReason.LANDING))

        assertEquals(0, dao.rows.size)
        assertFalse(dao.calls.contains("insert"))
    }

    @Test
    fun `last_room_sync_at is written when a sync completes`() = runTest(UnconfinedTestDispatcher()) {
        sharedRow()
        assertNull(prefs.getLastRoomSyncAt())

        syncer().syncNow(SyncReason.LANDING)

        assertEquals(T0, prefs.getLastRoomSyncAt())
    }

    @Test
    fun `every successful call reports the server reachable`() = runTest(UnconfinedTestDispatcher()) {
        sharedRow()
        val reachability = reachability()

        syncer(reachability).syncNow(SyncReason.LANDING)

        assertEquals(ServerState.REACHABLE, reachability.state.value)
    }

    // ── requestSync ──────────────────────────────────────────────────────────────────────

    @Test
    fun `requestSync runs on the syncer's scope and publishes the summary`() = runTest {
        sharedRow()
        val syncer = SharedChallengeSyncer(repository, LocalUserRepository(profileDao), prefs, roomApi, reachability(), this, clock)

        syncer.requestSync(SyncReason.FOREGROUND)
        advanceUntilIdle()

        assertEquals(SyncSummary.Synced(1, T0), syncer.lastSummary.value)
    }

    @Test
    fun `requests during a running sync are coalesced into exactly one more`() = runTest {
        sharedRow()
        fake.latencyMs = 100L
        val syncer = SharedChallengeSyncer(repository, LocalUserRepository(profileDao), prefs, roomApi, reachability(), this, clock)

        syncer.requestSync(SyncReason.LANDING)
        syncer.requestSync(SyncReason.LANDING)
        syncer.requestSync(SyncReason.LANDING)
        advanceUntilIdle()

        // The first sync and one follow-up: the probe is the ping, then one getRoom per sync.
        assertEquals(listOf("getRoom", "getRoom"), roomApi.calls)
    }

    // ── Interval and reconnect ───────────────────────────────────────────────────────────

    @Test
    fun `an interval sync is debounced, a reconnect sync is not`() = runTest(UnconfinedTestDispatcher()) {
        sharedRow()
        val syncer = syncer()
        syncer.syncNow(SyncReason.FOREGROUND)

        clock.now += 30_000L
        assertEquals(SyncSummary.Skipped(ServerState.REACHABLE), syncer.syncNow(SyncReason.PERIODIC))
        assertTrue(syncer.syncNow(SyncReason.RECONNECT) is SyncSummary.Synced)
        assertEquals(2, roomApi.calls.size)
    }

    @Test
    fun `keepFresh syncs on the interval and when the connection comes back`() = runTest {
        sharedRow()
        val syncer = SharedChallengeSyncer(repository, LocalUserRepository(profileDao), prefs, roomApi, reachability(), backgroundScope, clock)
        backgroundScope.launch { syncer.keepFresh(connected, intervalMs = 1_000L) }
        runCurrent()
        // Arriving connected is the state on arrival, not a reconnect.
        assertEquals(0, roomApi.calls.size)

        advanceTimeBy(1_001L)
        assertEquals(1, roomApi.calls.size)

        connected.value = false
        runCurrent()
        connected.value = true
        runCurrent()
        // Right after the interval sync: the reconnect is not debounced.
        assertEquals(2, roomApi.calls.size)

        // The next tick falls inside the debounce window of the reconnect sync...
        advanceTimeBy(1_000L)
        assertEquals(2, roomApi.calls.size)
        // ...and the one after it does not.
        clock.now += 60_000L
        advanceTimeBy(1_000L)
        assertEquals(3, roomApi.calls.size)
    }

    @Test
    fun `hasAnythingToSync needs a shared row or a pending leave`() = runTest(UnconfinedTestDispatcher()) {
        val syncer = syncer()
        assertFalse(syncer.hasAnythingToSync())

        prefs.addPendingRoomLeave("GONE01")
        assertTrue(syncer.hasAnythingToSync())
        prefs.removePendingRoomLeave("GONE01")
        assertFalse(syncer.hasAnythingToSync())

        sharedRow()
        assertTrue(syncer.hasAnythingToSync())
    }
}
