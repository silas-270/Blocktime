package com.silas270.blocktime.data.network.room

import com.silas270.blocktime.data.model.ChallengeSource
import com.silas270.blocktime.data.model.ChallengeType
import com.silas270.blocktime.data.model.MAX_ROOM_PARTICIPANTS
import com.silas270.blocktime.data.model.OutcomeClaim
import com.silas270.blocktime.data.model.ParticipantSnapshot
import com.silas270.blocktime.data.model.ROOM_CODE_LENGTH
import com.silas270.blocktime.data.model.RoomDefinition
import com.silas270.blocktime.data.model.RoomState
import com.silas270.blocktime.data.model.SharedOutcome
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The backend's contract test (docs/shared-challenges.md "Protocol"): every rule the server has
 * to apply is one test here, and the real server must pass the same suite.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FakeRoomApiTest {

    private var now = 1_000L
    private val api = FakeRoomApi(clock = { now }).apply { callerUserCode = "SELF01" }

    private val distance = RoomDefinition(
        type = ChallengeType.DISTANCE,
        source = ChallengeSource.CUSTOM,
        name = "Around the world",
        targetDistanceKm = 40_000.0,
    )

    private val race = RoomDefinition(
        type = ChallengeType.ROUTE,
        source = ChallengeSource.CURATED,
        name = "London to Sydney",
        originIata = "LHR",
        destIata = "SYD",
    )

    private val self = ParticipantSnapshot(userCode = "SELF01", username = "Swift Pilot")

    private fun <T> RoomResult<T>.value(): T = (this as RoomResult.Ok<T>).value

    private suspend fun create(definition: RoomDefinition = distance): RoomState = api.createRoom(definition, self).value()

    /** Plays another pilot for one call, then hands the identity back. */
    private suspend fun <T> asPilot(code: String, block: suspend () -> T): T {
        val before = api.callerUserCode
        api.callerUserCode = code
        try {
            return block()
        } finally {
            api.callerUserCode = before
        }
    }

    @Test
    fun `creating a room gives a six-character code from the alphabet and colour 0`() = runTest {
        val room = create()

        assertEquals(ROOM_CODE_LENGTH, room.code.length)
        assertTrue(room.code.all { it in "ABCDEFGHJKLMNPQRSTUVWXYZ23456789" })
        assertEquals(distance, room.definition)
        assertEquals(now, room.createdAt)
        assertEquals(listOf(self.copy(colorIndex = 0, updatedAt = now)), room.participants)
        assertNull(room.outcome)
        assertEquals(room, api.room(room.code))
    }

    @Test
    fun `getting an unknown room is NotFound`() = runTest {
        assertEquals(RoomResult.NotFound, api.getRoom("NOPE42"))
        assertEquals(RoomResult.NotFound, api.putSnapshot("NOPE42", self, null))
    }

    @Test
    fun `a stranger's first put is the join and takes the next colour`() = runTest {
        val code = create().code
        val anna = ParticipantSnapshot(userCode = "ANNA02", username = "Anna", colorIndex = 5, distanceKm = 100.0)

        val room = asPilot("ANNA02") { api.putSnapshot(code, anna, null) }.value()

        assertEquals(listOf("SELF01", "ANNA02"), room.participants.map { it.userCode })
        assertEquals(1, room.participants[1].colorIndex)
        assertEquals(100.0, room.participants[1].distanceKm, 0.0)
        assertEquals(now, room.participants[1].updatedAt)
    }

    @Test
    fun `a put by an existing participant replaces their snapshot`() = runTest {
        val code = create().code
        api.addSimulatedParticipant(code, "ANNA02", "Anna")

        now = 2_000L
        val room = api.putSnapshot(code, self.copy(distanceKm = 500.0), null).value()

        assertEquals(2, room.participants.size)
        val mine = room.participants.first { it.userCode == "SELF01" }
        assertEquals(500.0, mine.distanceKm, 0.0)
        assertEquals(0, mine.colorIndex)
        assertEquals(2_000L, mine.updatedAt)
    }

    @Test
    fun `a left snapshot with the same code is replaced and no longer left`() = runTest {
        val code = create().code
        api.addSimulatedParticipant(code, "ANNA02", "Anna")
        api.leaveRoom(code).value()
        assertTrue(api.room(code)!!.participants.first { it.userCode == "SELF01" }.left)

        val room = api.putSnapshot(code, self.copy(distanceKm = 10.0), null).value()

        val mine = room.participants.first { it.userCode == "SELF01" }
        assertFalse(mine.left)
        assertEquals(0, mine.colorIndex)
        assertEquals(2, room.participants.size)
    }

    @Test
    fun `joining a closed room is RoomClosed`() = runTest {
        val code = create().code
        api.putSnapshot(code, self.copy(distanceKm = 40_000.0), OutcomeClaim.Completed(at = now)).value()

        val result = asPilot("ANNA02") { api.putSnapshot(code, ParticipantSnapshot("ANNA02", "Anna"), null) }

        assertEquals(RoomResult.RoomClosed, result)
        assertEquals(1, api.room(code)!!.participants.size)
    }

    @Test
    fun `an existing participant may still put into a closed room`() = runTest {
        val code = create().code
        api.addSimulatedParticipant(code, "ANNA02", "Anna")
        api.putSnapshot(code, self.copy(distanceKm = 40_000.0), OutcomeClaim.Completed(at = now)).value()

        val room = asPilot("ANNA02") { api.putSnapshot(code, ParticipantSnapshot("ANNA02", "Anna", distanceKm = 7.0), null) }.value()

        assertEquals(7.0, room.participants.first { it.userCode == "ANNA02" }.distanceKm, 0.0)
        assertTrue(room.outcome is SharedOutcome.Completed)
    }

    @Test
    fun `joining a route room with progress is RaceLocked`() = runTest {
        val byProgress = create(race).code
        api.advanceSimulated(byProgress, "SELF01") { it.copy(routeProgress = 0.1f) }
        assertEquals(RoomResult.RaceLocked, asPilot("ANNA02") { api.putSnapshot(byProgress, ParticipantSnapshot("ANNA02", "Anna"), null) })

        val byLeg = create(race).code
        api.advanceSimulated(byLeg, "SELF01") { it.copy(legIndex = 1) }
        assertEquals(RoomResult.RaceLocked, asPilot("ANNA02") { api.putSnapshot(byLeg, ParticipantSnapshot("ANNA02", "Anna"), null) })
    }

    @Test
    fun `joining a route room at its origin is allowed`() = runTest {
        val code = create(race).code
        val room = asPilot("ANNA02") { api.putSnapshot(code, ParticipantSnapshot("ANNA02", "Anna"), null) }.value()
        assertEquals(2, room.participants.size)
    }

    @Test
    fun `an existing participant may still put into a race with progress`() = runTest {
        val code = create(race).code
        api.addSimulatedParticipant(code, "ANNA02", "Anna")
        api.advanceSimulated(code, "ANNA02") { it.copy(routeProgress = 0.5f, legIndex = 1) }

        val room = api.putSnapshot(code, self.copy(routeProgress = 0.2f), null).value()
        assertEquals(0.2f, room.participants.first { it.userCode == "SELF01" }.routeProgress)
    }

    @Test
    fun `joining a pool with progress is allowed`() = runTest {
        val code = create().code
        api.advanceSimulated(code, "SELF01") { it.copy(distanceKm = 12_000.0) }

        val room = asPilot("ANNA02") { api.putSnapshot(code, ParticipantSnapshot("ANNA02", "Anna"), null) }.value()
        assertEquals(2, room.participants.size)
    }

    @Test
    fun `the seventh join is RoomFull`() = runTest {
        val code = create().code
        repeat(MAX_ROOM_PARTICIPANTS - 1) { i -> api.addSimulatedParticipant(code, "BOT00$i", "Bot $i") }
        assertEquals(MAX_ROOM_PARTICIPANTS, api.room(code)!!.participants.size)

        val result = asPilot("LATE07") { api.putSnapshot(code, ParticipantSnapshot("LATE07", "Late"), null) }

        assertEquals(RoomResult.RoomFull, result)
        // A member of a full room can still write.
        assertEquals(6, api.putSnapshot(code, self.copy(distanceKm = 1.0), null).value().participants.size)
    }

    @Test
    fun `a left pilot still counts toward the cap`() = runTest {
        val code = create().code
        repeat(MAX_ROOM_PARTICIPANTS - 1) { i -> api.addSimulatedParticipant(code, "BOT00$i", "Bot $i") }
        api.leaveRoom(code).value()

        assertEquals(RoomResult.RoomFull, asPilot("LATE07") { api.putSnapshot(code, ParticipantSnapshot("LATE07", "Late"), null) })
    }

    @Test
    fun `the first claim sets the outcome with bySelf false`() = runTest {
        val code = create().code
        now = 5_000L

        val room = api.putSnapshot(code, self.copy(distanceKm = 40_000.0), OutcomeClaim.Completed(at = 4_900L)).value()

        assertEquals(SharedOutcome.Completed(byUserCode = "SELF01", bySelf = false, at = 5_000L, placements = listOf("SELF01")), room.outcome)
    }

    @Test
    fun `a second claim is ignored, the first outcome stands`() = runTest {
        val code = create().code
        api.addSimulatedParticipant(code, "ANNA02", "Anna")
        now = 5_000L
        val first = asPilot("ANNA02") {
            api.putSnapshot(code, ParticipantSnapshot("ANNA02", "Anna", distanceKm = 40_000.0), OutcomeClaim.Completed(at = now))
        }.value().outcome

        now = 6_000L
        val room = api.putSnapshot(code, self.copy(distanceKm = 40_000.0), OutcomeClaim.Completed(at = now)).value()

        assertEquals(first, room.outcome)
        assertEquals("ANNA02", (room.outcome as SharedOutcome.Completed).byUserCode)
        // The refused claimer's snapshot was still stored.
        assertEquals(40_000.0, room.participants.first { it.userCode == "SELF01" }.distanceKm, 0.0)
    }

    @Test
    fun `a failed claim after a completion is ignored too`() = runTest {
        val code = create(distance.copy(type = ChallengeType.STREAK, targetDays = 3)).code
        api.putSnapshot(code, self.copy(streakDays = 3), OutcomeClaim.Completed(at = now)).value()

        val room = api.putSnapshot(code, self.copy(streakDays = 0, streakAlive = false), OutcomeClaim.Failed(brokenBy = "SELF01")).value()

        assertTrue(room.outcome is SharedOutcome.Completed)
    }

    @Test
    fun `a route completion ranks the claimer first then the rest by progress`() = runTest {
        val code = create(race).code
        api.addSimulatedParticipant(code, "ANNA02", "Anna")
        api.addSimulatedParticipant(code, "BOB003", "Bob")
        api.addSimulatedParticipant(code, "GONE04", "Gone")
        api.advanceSimulated(code, "ANNA02") { it.copy(routeProgress = 0.3f) }
        api.advanceSimulated(code, "BOB003") { it.copy(routeProgress = 0.8f) }
        api.advanceSimulated(code, "GONE04") { it.copy(routeProgress = 0.9f, left = true) }
        now = 9_000L

        val room = api.putSnapshot(code, self.copy(routeProgress = 1f, positionIata = "SYD"), OutcomeClaim.Completed(at = now)).value()

        assertEquals(
            SharedOutcome.Completed(byUserCode = "SELF01", bySelf = false, at = 9_000L, placements = listOf("SELF01", "BOB003", "ANNA02")),
            room.outcome,
        )
    }

    @Test
    fun `a pooled completion places only the claimer`() = runTest {
        val code = create().code
        api.addSimulatedParticipant(code, "ANNA02", "Anna")

        val room = api.putSnapshot(code, self.copy(distanceKm = 40_000.0), OutcomeClaim.Completed(at = now)).value()

        assertEquals(listOf("SELF01"), (room.outcome as SharedOutcome.Completed).placements)
    }

    @Test
    fun `a failed claim names the breaker`() = runTest {
        val code = create(distance.copy(type = ChallengeType.STREAK, targetDays = 3)).code
        api.addSimulatedParticipant(code, "ANNA02", "Anna")
        now = 7_000L

        val room = api.putSnapshot(code, self.copy(streakDays = 2), OutcomeClaim.Failed(brokenBy = "ANNA02")).value()

        assertEquals(SharedOutcome.Failed(brokenByUserCode = "ANNA02", bySelf = false, at = 7_000L), room.outcome)
    }

    @Test
    fun `every write stamps updatedAt and bumps version`() = runTest {
        val created = create()
        assertEquals(1L, created.version)

        now = 2_000L
        val afterPut = api.putSnapshot(created.code, self.copy(distanceKm = 1.0), null).value()
        assertEquals(2L, afterPut.version)
        assertEquals(2_000L, afterPut.participants.single().updatedAt)

        now = 3_000L
        api.leaveRoom(created.code).value()
        val afterLeave = api.room(created.code)!!
        assertEquals(3L, afterLeave.version)
        assertEquals(3_000L, afterLeave.participants.single().updatedAt)

        // The client's own stamp is never trusted.
        val stamped = api.putSnapshot(created.code, self.copy(updatedAt = 99L), null).value()
        assertEquals(3_000L, stamped.participants.single().updatedAt)
    }

    @Test
    fun `leaving marks left and keeps the snapshot`() = runTest {
        val code = create().code
        api.putSnapshot(code, self.copy(distanceKm = 250.0), null).value()

        assertEquals(RoomResult.Ok(Unit), api.leaveRoom(code))

        val mine = api.room(code)!!.participants.single()
        assertTrue(mine.left)
        assertEquals(250.0, mine.distanceKm, 0.0)
        assertEquals(RoomResult.Ok(Unit), api.leaveRoom(code))
    }

    @Test
    fun `leaving an unknown room is NotFound`() = runTest {
        assertEquals(RoomResult.NotFound, api.leaveRoom("NOPE42"))
    }

    @Test
    fun `leaving a room one never joined is Ok and changes nothing`() = runTest {
        val code = create().code
        assertEquals(RoomResult.Ok(Unit), asPilot("ANNA02") { api.leaveRoom(code) })
        assertEquals(listOf("SELF01"), api.room(code)!!.participants.map { it.userCode })
    }

    @Test
    fun `a snapshot for another pilot is Unauthorized`() = runTest {
        val code = create().code
        val anna = ParticipantSnapshot("ANNA02", "Anna")

        assertEquals(RoomResult.Unauthorized, api.putSnapshot(code, anna, null))
        assertEquals(RoomResult.Unauthorized, api.createRoom(distance, anna))
        assertEquals(1, api.room(code)!!.participants.size)
    }

    @Test
    fun `unreachable fails every call`() = runTest {
        val code = create().code
        api.unreachable = true

        assertFalse(api.ping())
        assertEquals(RoomResult.Unreachable, api.createRoom(distance, self))
        assertEquals(RoomResult.Unreachable, api.getRoom(code))
        assertEquals(RoomResult.Unreachable, api.putSnapshot(code, self, null))
        assertEquals(RoomResult.Unreachable, api.leaveRoom(code))

        api.unreachable = false
        assertTrue(api.ping())
        assertEquals(1L, api.getRoom(code).value().version)
    }

    @Test
    fun `latency delays every reply`() = runTest {
        api.latencyMs = 1_500L
        val start = testScheduler.currentTime

        create()

        assertEquals(1_500L, testScheduler.currentTime - start)
    }

    @Test
    fun `seedRoom puts a prepared room in place`() = runTest {
        val seeded = RoomState(code = "SEED01", definition = race, participants = listOf(self), version = 40L)
        api.seedRoom(seeded)

        assertEquals(seeded, api.getRoom("SEED01").value())
    }
}
