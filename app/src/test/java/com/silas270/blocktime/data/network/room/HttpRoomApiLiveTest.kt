package com.silas270.blocktime.data.network.room

import com.silas270.blocktime.data.model.ChallengeSource
import com.silas270.blocktime.data.model.ChallengeType
import com.silas270.blocktime.data.model.MAX_ROOM_PARTICIPANTS
import com.silas270.blocktime.data.model.OutcomeClaim
import com.silas270.blocktime.data.model.ParticipantSnapshot
import com.silas270.blocktime.data.model.RoomDefinition
import com.silas270.blocktime.data.model.RoomState
import com.silas270.blocktime.data.model.SetMemberKind
import com.silas270.blocktime.data.model.SharedOutcome
import com.silas270.blocktime.data.model.generateCode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The app's client against a running room server: the end-to-end half of the contract, which
 * proves the JSON the app writes is the JSON the server reads and back. Skipped unless
 * `ROOM_SERVER_TEST_URL` is set, e.g.
 *
 *     (cd backend && cargo run) &
 *     ROOM_SERVER_TEST_URL=http://localhost:8080 ./gradlew :app:testDebugUnitTest --tests '*HttpRoomApiLiveTest*'
 *
 * Every pilot is a fresh random code, so the suite can run again and again against one server.
 */
class HttpRoomApiLiveTest {

    private val url: String? = System.getenv("ROOM_SERVER_TEST_URL")?.takeIf { it.isNotBlank() }

    private fun live(block: suspend () -> Unit) {
        assumeTrue("ROOM_SERVER_TEST_URL is not set", url != null)
        runBlocking { block() }
    }

    private inner class Pilot(val code: String = generateCode(6)) {
        val api = HttpRoomApi(url!!).apply { bindCaller(code, generateCode(32)) }
        fun snapshot(transform: ParticipantSnapshot.() -> ParticipantSnapshot = { this }) =
            ParticipantSnapshot(userCode = code, username = "Pilot $code").transform()
    }

    private fun <T> RoomResult<T>.value(): T = (this as? RoomResult.Ok<T>)?.value ?: throw AssertionError("Expected Ok, got $this")

    private val set = RoomDefinition(
        type = ChallengeType.SET_COMPLETION,
        source = ChallengeSource.CURATED,
        name = "Pacific Island Hopper",
        description = "Land in 5 iconic Oceania countries.",
        iconName = "surfing",
        catalogId = "set_pacific",
        setCatalogId = "PACIFIC_ISLAND_HOPPER",
        setMemberKind = SetMemberKind.COUNTRY,
    )

    private val race = RoomDefinition(
        type = ChallengeType.ROUTE,
        source = ChallengeSource.CURATED,
        name = "London to Sydney",
        originIata = "LHR",
        destIata = "SYD",
        predefinedRouteId = "kangaroo",
    )

    @Test
    fun `a room survives the round trip field for field`() = live {
        val self = Pilot()
        val mine = self.snapshot {
            copy(positionIata = "NAN", legIndex = 2, routeProgress = 0.25f, visitedMembers = setOf("FJ", "WS"), distanceKm = 1234.5, streakDays = 4, lastFlownDay = "2026-09-28", streakAlive = true)
        }

        val created = self.api.createRoom(set, mine).value()

        assertEquals(set, created.definition)
        val stored = created.participants.single()
        assertEquals(mine.copy(colorIndex = 0, updatedAt = stored.updatedAt), stored)
        assertTrue(stored.updatedAt > 0)
        assertEquals(created, Pilot().api.getRoom(created.code).value())
    }

    @Test
    fun `join, claim and leave follow the protocol`() = live {
        val self = Pilot()
        val anna = Pilot()
        val code = self.api.createRoom(set, self.snapshot()).value().code

        val joined = anna.api.putSnapshot(code, anna.snapshot { copy(colorIndex = 4) }, null).value()
        assertEquals(listOf(self.code, anna.code), joined.participants.map { it.userCode })
        assertEquals(1, joined.participants[1].colorIndex)

        val closed = anna.api.putSnapshot(code, anna.snapshot(), OutcomeClaim.Completed(at = 1L)).value()
        val outcome = closed.outcome as SharedOutcome.Completed
        assertEquals(anna.code, outcome.byUserCode)
        assertEquals(listOf(anna.code), outcome.placements)

        val second = self.api.putSnapshot(code, self.snapshot(), OutcomeClaim.Completed(at = 2L)).value()
        assertEquals(closed.outcome, second.outcome)
        val stranger = Pilot()
        assertEquals(RoomResult.RoomClosed, stranger.api.putSnapshot(code, stranger.snapshot(), null))

        assertEquals(RoomResult.Ok(Unit), anna.api.leaveRoom(code))
        assertTrue(self.api.getRoom(code).value().participants.first { it.userCode == anna.code }.left)
        assertEquals(RoomResult.Ok(Unit), anna.api.leaveRoom(code))
    }

    @Test
    fun `a race locks once it has progress and ranks the claimer first`() = live {
        val self = Pilot()
        val anna = Pilot()
        val bob = Pilot()
        val code = self.api.createRoom(race, self.snapshot()).value().code
        anna.api.putSnapshot(code, anna.snapshot(), null).value()
        bob.api.putSnapshot(code, bob.snapshot(), null).value()
        anna.api.putSnapshot(code, anna.snapshot { copy(routeProgress = 0.3f) }, null).value()
        bob.api.putSnapshot(code, bob.snapshot { copy(routeProgress = 0.8f, legIndex = 1) }, null).value()

        val late = Pilot()
        assertEquals(RoomResult.RaceLocked, late.api.putSnapshot(code, late.snapshot(), null))

        val room = self.api.putSnapshot(code, self.snapshot { copy(routeProgress = 1f) }, OutcomeClaim.Completed(at = 0L)).value()
        assertEquals(listOf(self.code, bob.code, anna.code), (room.outcome as SharedOutcome.Completed).placements)
    }

    @Test
    fun `a full room refuses the seventh pilot`() = live {
        val self = Pilot()
        val code = self.api.createRoom(set, self.snapshot()).value().code
        repeat(MAX_ROOM_PARTICIPANTS - 1) {
            val bot = Pilot()
            bot.api.putSnapshot(code, bot.snapshot(), null).value()
        }
        val late = Pilot()
        assertEquals(RoomResult.RoomFull, late.api.putSnapshot(code, late.snapshot(), null))
    }

    @Test
    fun `a streak break names the breaker`() = live {
        val self = Pilot()
        val anna = Pilot()
        val streak = RoomDefinition(type = ChallengeType.STREAK, source = ChallengeSource.CUSTOM, name = "Three days", targetDays = 3)
        val code = self.api.createRoom(streak, self.snapshot()).value().code
        anna.api.putSnapshot(code, anna.snapshot(), null).value()

        val room: RoomState = self.api.putSnapshot(code, self.snapshot(), OutcomeClaim.Failed(brokenBy = anna.code)).value()

        val outcome = room.outcome as SharedOutcome.Failed
        assertEquals(anna.code, outcome.brokenByUserCode)
    }

    @Test
    fun `another phone with the same code but a different secret is refused`() = live {
        val self = Pilot()
        val code = self.api.createRoom(set, self.snapshot()).value().code
        val impostor = HttpRoomApi(url!!).apply { bindCaller(self.code, generateCode(32)) }

        assertEquals(RoomResult.Unauthorized, impostor.putSnapshot(code, self.snapshot(), null))
        assertEquals(RoomResult.Unauthorized, impostor.leaveRoom(code))
    }
}
