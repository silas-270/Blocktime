package com.silas270.blocktime.domain

import com.silas270.blocktime.data.model.Challenge
import com.silas270.blocktime.data.model.ChallengeSource
import com.silas270.blocktime.data.model.ChallengeStatus
import com.silas270.blocktime.data.model.ChallengeType
import com.silas270.blocktime.data.model.CuratedChallengeSets
import com.silas270.blocktime.data.model.FlightMode
import com.silas270.blocktime.data.model.OutcomeClaim
import com.silas270.blocktime.data.model.ParticipantSnapshot
import com.silas270.blocktime.data.model.PausedFlight
import com.silas270.blocktime.data.model.RoomDefinition
import com.silas270.blocktime.data.model.RoomState
import com.silas270.blocktime.data.model.RoomStateCache
import com.silas270.blocktime.data.model.SetMemberKind
import com.silas270.blocktime.data.model.SharedOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * The rules of docs/shared-challenges.md "The merge", one test per row of "Applying a room
 * state" (P1 to P15) that the merge decides, plus the per-type "Ends" column of "Per type" (C7).
 * The merge is a pure function, so every test is a row, a room state and a clock.
 */
class SharedChallengeMergeTest {

    private companion object {
        const val SELF = "SELF01"
        const val ANNA = "ANNA01"
        const val BOB = "BOB001"
        const val ROOM = "ROOM01"
        val TODAY: LocalDate = LocalDate.of(2026, 9, 29)
        val ZONE: ZoneId = ZoneOffset.UTC
        val NOW: Long = TODAY.atTime(12, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
        const val DAY_MS = 24 * 60 * 60 * 1000L
    }

    private fun day(offset: Long): String = TODAY.plusDays(offset).toString()

    private fun noonOf(dayOffset: Long): Long =
        TODAY.plusDays(dayOffset).atTime(12, 0).toInstant(ZoneOffset.UTC).toEpochMilli()

    private fun snapshot(
        code: String,
        colorIndex: Int,
        left: Boolean = false,
        distanceKm: Double = 0.0,
        visited: Set<String> = emptySet(),
        streakDays: Int = 0,
        lastFlownDay: String? = null,
        streakAlive: Boolean = true,
        routeProgress: Float = 0f,
        updatedAt: Long = NOW,
    ) = ParticipantSnapshot(
        userCode = code,
        username = code.lowercase(),
        colorIndex = colorIndex,
        left = left,
        distanceKm = distanceKm,
        visitedMembers = visited,
        streakDays = streakDays,
        lastFlownDay = lastFlownDay,
        streakAlive = streakAlive,
        routeProgress = routeProgress,
        updatedAt = updatedAt,
    )

    private fun room(
        type: ChallengeType,
        vararg participants: ParticipantSnapshot,
        outcome: SharedOutcome? = null,
        version: Long = 1L,
    ) = RoomState(
        code = ROOM,
        definition = RoomDefinition(type = type, source = ChallengeSource.CUSTOM, name = "Shared"),
        participants = participants.toList(),
        outcome = outcome,
        version = version,
    )

    private fun set(
        ownVisited: Set<String>,
        status: ChallengeStatus = ChallengeStatus.ACTIVE,
        celebrated: Boolean = false,
        catalogId: String = CuratedChallengeSets.ALL_CONTINENTS.catalogId,
        cache: RoomStateCache? = null,
    ) = Challenge(
        id = 1,
        userId = 1,
        type = ChallengeType.SET_COMPLETION,
        source = ChallengeSource.CURATED,
        status = status,
        name = "Continents",
        setCatalogId = catalogId,
        setMemberKind = SetMemberKind.CONTINENT,
        setTotalMembers = 6,
        visitedSetMembers = ownVisited,
        celebrated = celebrated,
        roomCode = ROOM,
        roomState = cache,
        startedAt = noonOf(-10),
        completedAt = if (status == ChallengeStatus.ACTIVE) null else noonOf(-1),
    )

    private fun distance(
        ownKm: Double,
        targetKm: Double = 1000.0,
        status: ChallengeStatus = ChallengeStatus.ACTIVE,
        celebrated: Boolean = false,
        outcome: SharedOutcome? = null,
        cache: RoomStateCache? = null,
    ) = Challenge(
        id = 1,
        userId = 1,
        type = ChallengeType.DISTANCE,
        source = ChallengeSource.CUSTOM,
        status = status,
        name = "Distance",
        targetDistanceKm = targetKm,
        cumulativeDistanceKm = ownKm,
        celebrated = celebrated,
        roomCode = ROOM,
        roomState = cache,
        sharedOutcome = outcome,
        startedAt = noonOf(-10),
        completedAt = if (status == ChallengeStatus.ACTIVE) null else noonOf(-1),
    )

    private fun streak(
        ownDays: Int,
        lastFlownDay: String?,
        targetDays: Int = 3,
        startedAt: Long = noonOf(-10),
        status: ChallengeStatus = ChallengeStatus.ACTIVE,
        celebrated: Boolean = false,
        cache: RoomStateCache? = null,
    ) = Challenge(
        id = 1,
        userId = 1,
        type = ChallengeType.STREAK,
        source = ChallengeSource.CUSTOM,
        status = status,
        name = "Streak",
        targetDays = targetDays,
        streakDays = ownDays,
        lastFlownDay = lastFlownDay,
        celebrated = celebrated,
        roomCode = ROOM,
        roomState = cache,
        startedAt = startedAt,
        completedAt = if (status == ChallengeStatus.ACTIVE) null else noonOf(-1),
    )

    private fun route(
        ownProgress: Float,
        pausedFlight: PausedFlight? = null,
        status: ChallengeStatus = ChallengeStatus.ACTIVE,
        outcome: SharedOutcome? = null,
        cache: RoomStateCache? = null,
    ) = Challenge(
        id = 1,
        userId = 1,
        type = ChallengeType.ROUTE,
        source = ChallengeSource.CUSTOM,
        status = status,
        name = "Race",
        originIata = "LHR",
        destIata = "SYD",
        positionIata = "DXB",
        routeProgressFraction = ownProgress,
        pausedFlight = pausedFlight,
        roomCode = ROOM,
        roomState = cache,
        sharedOutcome = outcome,
        startedAt = noonOf(-10),
        completedAt = if (status == ChallengeStatus.ACTIVE) null else noonOf(-1),
    )

    private val pausedLeg = PausedFlight(
        flightNumber = "BT123",
        originIata = "DXB",
        destIata = "SIN",
        durationMin = 420,
        mode = FlightMode.CHALLENGE,
        challengeId = 1,
    )

    private fun merge(local: Challenge, room: RoomState) =
        mergeRoomIntoChallenge(local, room, SELF, TODAY, NOW, ZONE)

    // ── Rule 1 and rule 7: the cache ──────────────────────────────────────────────────────

    @Test
    fun `P1 only foreign progress refreshes the cache and is not a change`() {
        val local = distance(100.0)
        val room = room(ChallengeType.DISTANCE, snapshot(SELF, 0, distanceKm = 100.0), snapshot(ANNA, 1, distanceKm = 300.0))

        val result = merge(local, room)

        assertEquals(RoomStateCache(SELF, room), result.challenge.roomState)
        assertEquals(local, result.challenge.copy(roomState = null))
        assertFalse(result.changed)
        assertNull(result.claim)
        assertFalse(result.challenge.roomState!!.room.roomGone)
    }

    @Test
    fun `changed is false when only the cache is equal`() {
        val room = room(ChallengeType.DISTANCE, snapshot(SELF, 0), snapshot(ANNA, 1, distanceKm = 300.0))
        val local = distance(100.0, cache = RoomStateCache(SELF, room))

        val result = merge(local, room)

        assertEquals(local, result.challenge)
        assertFalse(result.changed)
        assertNull(result.claim)
    }

    // ── Rule 2 ────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a presented terminal row only refreshes its cache`() {
        val own = SharedOutcome.Completed(byUserCode = SELF, bySelf = true, at = noonOf(-1))
        val local = distance(1000.0, status = ChallengeStatus.COMPLETED, celebrated = true, outcome = own)
        val room = room(
            ChallengeType.DISTANCE,
            snapshot(SELF, 0, distanceKm = 1000.0),
            snapshot(ANNA, 1, distanceKm = 50.0),
            outcome = SharedOutcome.Completed(byUserCode = ANNA, at = noonOf(-2)),
        )

        val result = merge(local, room)

        assertEquals(RoomStateCache(SELF, room), result.challenge.roomState)
        assertEquals(ChallengeStatus.COMPLETED, result.challenge.status)
        assertEquals(own, result.challenge.sharedOutcome)
        assertTrue(result.challenge.celebrated)
        assertFalse(result.changed)
        assertNull(result.claim)
    }

    // ── Rule 3 ────────────────────────────────────────────────────────────────────────────

    @Test
    fun `P7 a refused claim is replaced by the server's outcome and the status stays`() {
        val local = route(
            1f,
            status = ChallengeStatus.COMPLETED,
            outcome = SharedOutcome.Completed(byUserCode = SELF, bySelf = true, at = noonOf(-1), placements = listOf(SELF)),
        )
        val server = SharedOutcome.Completed(byUserCode = ANNA, at = noonOf(-1) - 5_000L, placements = listOf(ANNA, SELF))
        val room = room(ChallengeType.ROUTE, snapshot(SELF, 0, routeProgress = 1f), snapshot(ANNA, 1, routeProgress = 1f), outcome = server)

        val result = merge(local, room)

        assertEquals(ChallengeStatus.COMPLETED, result.challenge.status)
        assertEquals(server.copy(bySelf = false), result.challenge.sharedOutcome)
        assertEquals(server.at, result.challenge.completedAt)
        assertFalse(result.challenge.celebrated)
        assertTrue(result.changed)
        assertNull(result.claim)
    }

    @Test
    fun `P8 a local completion loses to a server failure`() {
        val local = streak(3, day(0), status = ChallengeStatus.COMPLETED)
        val server = SharedOutcome.Failed(brokenByUserCode = BOB, at = noonOf(-1))
        val room = room(ChallengeType.STREAK, snapshot(SELF, 0, streakDays = 3), snapshot(BOB, 1, streakAlive = false), outcome = server)

        val result = merge(local, room)

        assertEquals(ChallengeStatus.FAILED, result.challenge.status)
        assertEquals(server.copy(bySelf = false), result.challenge.sharedOutcome)
        assertEquals(server.at, result.challenge.completedAt)
        assertTrue(result.changed)
        assertNull(result.claim)
    }

    @Test
    fun `a server outcome by self is stamped bySelf on a terminal row`() {
        val local = distance(
            1000.0,
            status = ChallengeStatus.COMPLETED,
            outcome = SharedOutcome.Completed(byUserCode = SELF, bySelf = true, at = noonOf(-1)),
        )
        val server = SharedOutcome.Completed(byUserCode = SELF, at = noonOf(-1))
        val room = room(ChallengeType.DISTANCE, snapshot(SELF, 0, distanceKm = 1000.0), outcome = server)

        val result = merge(local, room)

        assertEquals(server.copy(bySelf = true), result.challenge.sharedOutcome)
        assertEquals(ChallengeStatus.COMPLETED, result.challenge.status)
        assertNull(result.claim)
    }

    @Test
    fun `a terminal unpresented row with an open room keeps its own outcome`() {
        // The claim is still on its way; the server has not answered yet.
        val own = SharedOutcome.Completed(byUserCode = SELF, bySelf = true, at = noonOf(-1))
        val local = distance(1000.0, status = ChallengeStatus.COMPLETED, outcome = own)
        val room = room(ChallengeType.DISTANCE, snapshot(SELF, 0, distanceKm = 600.0), snapshot(ANNA, 1, distanceKm = 400.0))

        val result = merge(local, room)

        assertEquals(ChallengeStatus.COMPLETED, result.challenge.status)
        assertEquals(own, result.challenge.sharedOutcome)
        assertFalse(result.changed)
        assertNull(result.claim)
    }

    // ── Rules 4 and 5 ─────────────────────────────────────────────────────────────────────

    @Test
    fun `P3 a foreign completion completes a pool row unpresented with bySelf false`() {
        val local = distance(100.0)
        val server = SharedOutcome.Completed(byUserCode = ANNA, at = noonOf(0) - 60_000L)
        val room = room(ChallengeType.DISTANCE, snapshot(SELF, 0, distanceKm = 100.0), snapshot(ANNA, 1, distanceKm = 900.0), outcome = server)

        val result = merge(local, room)

        assertEquals(ChallengeStatus.COMPLETED, result.challenge.status)
        assertEquals(server.copy(bySelf = false), result.challenge.sharedOutcome)
        assertEquals(server.at, result.challenge.completedAt)
        assertFalse(result.challenge.celebrated)
        // Own data untouched.
        assertEquals(100.0, result.challenge.cumulativeDistanceKm, 0.0)
        assertTrue(result.changed)
        assertNull(result.claim)
    }

    @Test
    fun `P3 P6 a foreign race win completes the route row and clears its paused leg`() {
        val local = route(0.4f, pausedFlight = pausedLeg)
        val server = SharedOutcome.Completed(byUserCode = ANNA, at = noonOf(0) - 60_000L, placements = listOf(ANNA, SELF))
        val room = room(ChallengeType.ROUTE, snapshot(SELF, 0, routeProgress = 0.4f), snapshot(ANNA, 1, routeProgress = 1f), outcome = server)

        val result = merge(local, room)

        assertEquals(ChallengeStatus.COMPLETED, result.challenge.status)
        assertEquals(server.copy(bySelf = false), result.challenge.sharedOutcome)
        assertNull(result.challenge.pausedFlight)
        assertFalse(result.challenge.celebrated)
        assertEquals(0.4f, result.challenge.routeProgressFraction)
        assertTrue(result.changed)
        assertNull(result.claim)
    }

    @Test
    fun `a completion by self from the server stamps bySelf on an active row`() {
        // Another phone restored from the same backup claimed under this code.
        val local = route(0.4f)
        val server = SharedOutcome.Completed(byUserCode = SELF, at = noonOf(0), placements = listOf(SELF))
        val room = room(ChallengeType.ROUTE, snapshot(SELF, 0, routeProgress = 1f), outcome = server)

        val result = merge(local, room)

        assertEquals(server.copy(bySelf = true), result.challenge.sharedOutcome)
        assertEquals(ChallengeStatus.COMPLETED, result.challenge.status)
    }

    @Test
    fun `P5 a server failure fails an active streak row with the outcome copied`() {
        val local = streak(2, day(0))
        val server = SharedOutcome.Failed(brokenByUserCode = ANNA, at = noonOf(0) - 60_000L)
        val room = room(ChallengeType.STREAK, snapshot(SELF, 0, streakDays = 2), snapshot(ANNA, 1, streakAlive = false), outcome = server)

        val result = merge(local, room)

        assertEquals(ChallengeStatus.FAILED, result.challenge.status)
        assertEquals(server.copy(bySelf = false), result.challenge.sharedOutcome)
        assertEquals(server.at, result.challenge.completedAt)
        assertFalse(result.challenge.celebrated)
        assertEquals(2, result.challenge.streakDays)
        assertTrue(result.changed)
        assertNull(result.claim)
    }

    @Test
    fun `a server failure broken by self is stamped bySelf`() {
        val local = streak(0, day(-5))
        val server = SharedOutcome.Failed(brokenByUserCode = SELF, at = noonOf(0))
        val room = room(ChallengeType.STREAK, snapshot(SELF, 0, streakAlive = false), snapshot(ANNA, 1, streakDays = 2), outcome = server)

        val result = merge(local, room)

        assertEquals(server.copy(bySelf = true), result.challenge.sharedOutcome)
        assertEquals(ChallengeStatus.FAILED, result.challenge.status)
    }

    // ── Rule 6: pools ─────────────────────────────────────────────────────────────────────

    @Test
    fun `P2 a set whose union covers the definition completes with a claim`() {
        val local = set(setOf("EU", "AF"))
        val room = room(
            ChallengeType.SET_COMPLETION,
            snapshot(SELF, 0, visited = setOf("EU")),
            snapshot(ANNA, 1, visited = setOf("AS", "NA")),
            snapshot(BOB, 2, visited = setOf("OC", "SA")),
        )

        val result = merge(local, room)

        assertEquals(ChallengeStatus.COMPLETED, result.challenge.status)
        assertEquals(SharedOutcome.Completed(byUserCode = SELF, bySelf = true, at = NOW), result.challenge.sharedOutcome)
        assertEquals(NOW, result.challenge.completedAt)
        assertFalse(result.challenge.celebrated)
        assertEquals(OutcomeClaim.Completed(NOW), result.claim)
        assertTrue(result.changed)
        // Own data untouched: the union is derived, never stored.
        assertEquals(setOf("EU", "AF"), result.challenge.visitedSetMembers)
    }

    @Test
    fun `C7 a set one member short stays active`() {
        val local = set(setOf("EU", "AF"))
        val room = room(ChallengeType.SET_COMPLETION, snapshot(ANNA, 1, visited = setOf("AS", "NA")), snapshot(BOB, 2, visited = setOf("OC")))

        val result = merge(local, room)

        assertEquals(ChallengeStatus.ACTIVE, result.challenge.status)
        assertNull(result.claim)
        assertFalse(result.changed)
    }

    @Test
    fun `P14 members outside the current definition do not count towards the union`() {
        // Antarctica was a member of an older definition; XX never was. Neither may fill the gap
        // left by the missing South America.
        val local = set(setOf("EU", "AF", "AN"))
        val room = room(ChallengeType.SET_COMPLETION, snapshot(ANNA, 1, visited = setOf("AS", "NA", "XX")), snapshot(BOB, 2, visited = setOf("OC", "AN")))

        assertEquals(ChallengeStatus.ACTIVE, merge(local, room).challenge.status)

        val filled = room(ChallengeType.SET_COMPLETION, snapshot(ANNA, 1, visited = setOf("AS", "NA", "XX")), snapshot(BOB, 2, visited = setOf("OC", "AN", "SA")))
        assertEquals(ChallengeStatus.COMPLETED, merge(local, filled).challenge.status)
    }

    @Test
    fun `a set with an unknown definition never completes from the merge`() {
        val local = set(setOf("EU", "AF"), catalogId = "no_such_set")
        val room = room(ChallengeType.SET_COMPLETION, snapshot(ANNA, 1, visited = setOf("AS", "NA", "OC", "SA")))

        val result = merge(local, room)

        assertEquals(ChallengeStatus.ACTIVE, result.challenge.status)
        assertNull(result.claim)
    }

    @Test
    fun `P2 a distance pool whose sum reaches the target completes with a claim`() {
        val local = distance(400.0)
        val room = room(ChallengeType.DISTANCE, snapshot(SELF, 0, distanceKm = 100.0), snapshot(ANNA, 1, distanceKm = 350.0), snapshot(BOB, 2, left = true, distanceKm = 250.0))

        val result = merge(local, room)

        // Own 400 from the row (not the cached 100) plus Anna's 350 plus the leaver's 250.
        assertEquals(ChallengeStatus.COMPLETED, result.challenge.status)
        assertEquals(SharedOutcome.Completed(byUserCode = SELF, bySelf = true, at = NOW), result.challenge.sharedOutcome)
        assertEquals(OutcomeClaim.Completed(NOW), result.claim)
        assertEquals(400.0, result.challenge.cumulativeDistanceKm, 0.0)
        assertTrue(result.changed)
    }

    @Test
    fun `C7 a distance pool short of its target stays active`() {
        val local = distance(400.0)
        val room = room(ChallengeType.DISTANCE, snapshot(ANNA, 1, distanceKm = 350.0))

        val result = merge(local, room)

        assertEquals(ChallengeStatus.ACTIVE, result.challenge.status)
        assertNull(result.claim)
        assertFalse(result.changed)
    }

    // ── Rule 6: streak ────────────────────────────────────────────────────────────────────

    @Test
    fun `P4 a crew member reporting a dead streak fails the row with a claim naming them`() {
        val local = streak(2, day(0))
        val room = room(ChallengeType.STREAK, snapshot(SELF, 0, streakDays = 2), snapshot(ANNA, 1, streakDays = 0, lastFlownDay = day(-1), streakAlive = false))

        val result = merge(local, room)

        assertEquals(ChallengeStatus.FAILED, result.challenge.status)
        assertEquals(SharedOutcome.Failed(brokenByUserCode = ANNA, bySelf = false, at = NOW), result.challenge.sharedOutcome)
        assertEquals(NOW, result.challenge.completedAt)
        assertFalse(result.challenge.celebrated)
        assertEquals(OutcomeClaim.Failed(ANNA), result.claim)
        assertTrue(result.changed)
    }

    @Test
    fun `P4 the pilot's own dead streak fails the row with a claim naming self`() {
        // Flew three days ago with a run of two: the run is over by the local rule.
        val local = streak(2, day(-3))
        val room = room(ChallengeType.STREAK, snapshot(SELF, 0, streakDays = 2, lastFlownDay = day(-3)), snapshot(ANNA, 1, streakDays = 2, lastFlownDay = day(0)))

        val result = merge(local, room)

        assertEquals(ChallengeStatus.FAILED, result.challenge.status)
        assertEquals(SharedOutcome.Failed(brokenByUserCode = SELF, bySelf = true, at = NOW), result.challenge.sharedOutcome)
        assertEquals(OutcomeClaim.Failed(SELF), result.claim)
        // The row's own columns are the record of what happened and stay as they are.
        assertEquals(2, result.challenge.streakDays)
        assertEquals(day(-3), result.challenge.lastFlownDay)
    }

    @Test
    fun `P4 self is judged by the local row, not by the cached self snapshot`() {
        // The cache still says self is alive; the row knows better.
        val local = streak(2, day(-3))
        val room = room(ChallengeType.STREAK, snapshot(SELF, 0, streakDays = 2, lastFlownDay = day(-1), streakAlive = true), snapshot(ANNA, 1, streakDays = 2, lastFlownDay = day(0)))

        assertEquals(OutcomeClaim.Failed(SELF), merge(local, room).claim)
    }

    @Test
    fun `P4 a stale snapshot claiming alive is dead when its last flown day is before the day before yesterday`() {
        val local = streak(2, day(0))
        val stale = room(ChallengeType.STREAK, snapshot(ANNA, 1, streakDays = 2, lastFlownDay = day(-3), streakAlive = true, updatedAt = noonOf(-3)))

        val result = merge(local, stale)

        assertEquals(ChallengeStatus.FAILED, result.challenge.status)
        assertEquals(OutcomeClaim.Failed(ANNA), result.claim)
    }

    @Test
    fun `P4 a stale snapshot with a last flown day two days ago is still alive, one day of slack for time zones`() {
        val local = streak(2, day(0))
        val room = room(ChallengeType.STREAK, snapshot(ANNA, 1, streakDays = 2, lastFlownDay = day(-2), streakAlive = true, updatedAt = noonOf(-2)))

        val result = merge(local, room)

        assertEquals(ChallengeStatus.ACTIVE, result.challenge.status)
        assertNull(result.claim)
    }

    @Test
    fun `P4 a snapshot that never flew and was not written for over two days is dead`() {
        val local = streak(2, day(0))
        val stale = room(ChallengeType.STREAK, snapshot(ANNA, 1, streakDays = 0, lastFlownDay = null, streakAlive = true, updatedAt = NOW - 2 * DAY_MS - 1))

        val result = merge(local, stale)

        assertEquals(ChallengeStatus.FAILED, result.challenge.status)
        assertEquals(OutcomeClaim.Failed(ANNA), result.claim)
    }

    @Test
    fun `P4 a snapshot that never flew but was written within two days is alive`() {
        val local = streak(2, day(0))
        val fresh = room(ChallengeType.STREAK, snapshot(ANNA, 1, streakDays = 0, lastFlownDay = null, streakAlive = true, updatedAt = NOW - 2 * DAY_MS))

        assertEquals(ChallengeStatus.ACTIVE, merge(local, fresh).challenge.status)

        // An unstamped snapshot gives no evidence and keeps its owner's verdict.
        val unstamped = room(ChallengeType.STREAK, snapshot(ANNA, 1, streakDays = 0, lastFlownDay = null, streakAlive = true, updatedAt = 0L))
        assertEquals(ChallengeStatus.ACTIVE, merge(local, unstamped).challenge.status)
    }

    @Test
    fun `P10 a leaver's dead streak does not break the group`() {
        val local = streak(2, day(0))
        val room = room(ChallengeType.STREAK, snapshot(ANNA, 1, streakDays = 2, lastFlownDay = day(0)), snapshot(BOB, 2, left = true, streakAlive = false))

        val result = merge(local, room)

        assertEquals(ChallengeStatus.ACTIVE, result.challenge.status)
        assertNull(result.claim)
    }

    @Test
    fun `a dead crew member is named before a dead self`() {
        val local = streak(2, day(-3))
        val room = room(ChallengeType.STREAK, snapshot(ANNA, 1, streakAlive = false), snapshot(BOB, 2, streakAlive = false))

        assertEquals(OutcomeClaim.Failed(ANNA), merge(local, room).claim)
    }

    @Test
    fun `C7 a streak completes from the merge when the crew's minimum reaches the target`() {
        val local = streak(3, day(0), targetDays = 3)
        val room = room(ChallengeType.STREAK, snapshot(SELF, 0, streakDays = 2, lastFlownDay = day(-1)), snapshot(ANNA, 1, streakDays = 3, lastFlownDay = day(0)))

        val result = merge(local, room)

        assertEquals(ChallengeStatus.COMPLETED, result.challenge.status)
        assertEquals(SharedOutcome.Completed(byUserCode = SELF, bySelf = true, at = NOW), result.challenge.sharedOutcome)
        assertEquals(OutcomeClaim.Completed(NOW), result.claim)
        assertFalse(result.challenge.celebrated)
        assertTrue(result.changed)
    }

    @Test
    fun `C7 a streak stays active while any crew member is below the target`() {
        val local = streak(3, day(0), targetDays = 3)
        val room = room(ChallengeType.STREAK, snapshot(ANNA, 1, streakDays = 2, lastFlownDay = day(0)))

        val result = merge(local, room)

        assertEquals(ChallengeStatus.ACTIVE, result.challenge.status)
        assertNull(result.claim)
        assertFalse(result.changed)
    }

    @Test
    fun `a streak whose minimum is held back by self stays active`() {
        val local = streak(1, day(0), targetDays = 3)
        val room = room(ChallengeType.STREAK, snapshot(ANNA, 1, streakDays = 5, lastFlownDay = day(0)))

        assertEquals(ChallengeStatus.ACTIVE, merge(local, room).challenge.status)
    }

    @Test
    fun `a joiner is alive on the join day and the day after`() {
        val joinedToday = streak(0, null, startedAt = noonOf(0))
        val joinedYesterday = streak(0, null, startedAt = noonOf(-1))
        val joinedBefore = streak(0, null, startedAt = noonOf(-2))
        val room = room(ChallengeType.STREAK, snapshot(ANNA, 1, streakDays = 2, lastFlownDay = day(0)))

        assertEquals(ChallengeStatus.ACTIVE, merge(joinedToday, room).challenge.status)
        assertEquals(ChallengeStatus.ACTIVE, merge(joinedYesterday, room).challenge.status)

        val result = merge(joinedBefore, room)
        assertEquals(ChallengeStatus.FAILED, result.challenge.status)
        assertEquals(OutcomeClaim.Failed(SELF), result.claim)
    }

    @Test
    fun `the join day is read in the given zone`() {
        // 23:30 UTC two days ago is already "yesterday" three hours east.
        val startedAt = TODAY.minusDays(2).atTime(23, 30).toInstant(ZoneOffset.UTC).toEpochMilli()
        val local = streak(0, null, startedAt = startedAt)
        val room = room(ChallengeType.STREAK, snapshot(ANNA, 1, streakDays = 2, lastFlownDay = day(0)))

        assertEquals(ChallengeStatus.FAILED, mergeRoomIntoChallenge(local, room, SELF, TODAY, NOW, ZoneOffset.UTC).challenge.status)
        assertEquals(ChallengeStatus.ACTIVE, mergeRoomIntoChallenge(local, room, SELF, TODAY, NOW, ZoneOffset.ofHours(3)).challenge.status)
    }

    // ── Rule 6: route ─────────────────────────────────────────────────────────────────────

    @Test
    fun `C7 a route is never terminal from the merge`() {
        val local = route(1f, pausedFlight = pausedLeg)
        val room = room(ChallengeType.ROUTE, snapshot(SELF, 0, routeProgress = 1f), snapshot(ANNA, 1, routeProgress = 1f))

        val result = merge(local, room)

        assertEquals(ChallengeStatus.ACTIVE, result.challenge.status)
        assertEquals(pausedLeg, result.challenge.pausedFlight)
        assertNull(result.claim)
        assertFalse(result.changed)
    }
}
