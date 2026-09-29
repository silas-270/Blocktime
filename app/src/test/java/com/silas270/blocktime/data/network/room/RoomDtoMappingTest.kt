package com.silas270.blocktime.data.network.room

import com.silas270.blocktime.data.model.ChallengeSource
import com.silas270.blocktime.data.model.ChallengeType
import com.silas270.blocktime.data.model.OutcomeClaim
import com.silas270.blocktime.data.model.ParticipantSnapshot
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

class RoomDtoMappingTest {

    private val definition = RoomDefinition(
        type = ChallengeType.SET_COMPLETION,
        source = ChallengeSource.CURATED,
        name = "Every continent",
        description = "Land on all seven",
        iconName = "globe",
        catalogId = "continents",
        setCatalogId = "continents",
        setMemberKind = SetMemberKind.CONTINENT,
    )

    private val self = ParticipantSnapshot(
        userCode = "SELF01",
        username = "Swift Pilot",
        colorIndex = 0,
        visitedMembers = setOf("EU", "NA"),
        updatedAt = 1_000L,
    )

    private val other = ParticipantSnapshot(
        userCode = "ANNA02",
        username = "Anna",
        colorIndex = 1,
        left = true,
        positionIata = "LHR",
        legIndex = 2,
        routeProgress = 0.4f,
        visitedMembers = setOf("AS"),
        distanceKm = 1234.5,
        streakDays = 3,
        lastFlownDay = "2026-09-28",
        streakAlive = false,
        updatedAt = 2_000L,
    )

    @Test
    fun `a cache survives the round trip through JSON`() {
        val cache = RoomStateCache(
            selfCode = "SELF01",
            room = RoomState(
                code = "ABC234",
                definition = definition,
                createdAt = 500L,
                participants = listOf(self, other),
                outcome = SharedOutcome.Completed(byUserCode = "SELF01", bySelf = true, at = 3_000L, placements = listOf("SELF01", "ANNA02")),
                version = 7L,
                roomGone = true,
            ),
        )

        assertEquals(cache, RoomJson.decodeCache(RoomJson.encodeCache(cache)))
    }

    @Test
    fun `a failed outcome survives the round trip and bySelf follows selfCode`() {
        val room = RoomState(
            code = "ABC234",
            definition = definition.copy(type = ChallengeType.STREAK, targetDays = 5),
            participants = listOf(self, other),
            outcome = SharedOutcome.Failed(brokenByUserCode = "ANNA02", bySelf = false, at = 3_000L),
        )
        val cache = RoomStateCache(selfCode = "SELF01", room = room)
        assertEquals(cache, RoomJson.decodeCache(RoomJson.encodeCache(cache)))

        // The wire never carries bySelf; the cache stamps it from its own code.
        val decoded = room.toDto().toDomain(selfCode = "ANNA02")!!
        assertTrue(decoded.outcome!!.bySelf)
        assertFalse(room.toDto().toDomain()!!.outcome!!.bySelf)
    }

    @Test
    fun `missing fields take their defaults`() {
        val json = """
            {"selfCode":"SELF01","room":{"code":"ABC234","definition":{"type":"DISTANCE","name":"Far"},
             "participants":[{"userCode":"SELF01"},{"userCode":"ANNA02","username":"Anna","distanceKm":10.5}]}}
        """.trimIndent()

        val cache = RoomJson.decodeCache(json)!!
        val room = cache.room
        assertEquals("ABC234", room.code)
        assertEquals(0L, room.createdAt)
        assertEquals(0L, room.version)
        assertFalse(room.roomGone)
        assertNull(room.outcome)
        assertEquals(RoomDefinition(type = ChallengeType.DISTANCE, source = ChallengeSource.CUSTOM, name = "Far"), room.definition)
        assertEquals(
            listOf(
                ParticipantSnapshot(userCode = "SELF01", username = ""),
                ParticipantSnapshot(userCode = "ANNA02", username = "Anna", distanceKm = 10.5),
            ),
            room.participants,
        )
        assertTrue(room.participants[0].streakAlive)
        assertFalse(room.participants[0].left)
    }

    @Test
    fun `an unknown outcome kind reads as no outcome`() {
        val dto = RoomStateDto(
            code = "ABC234",
            definition = definition.toDto(),
            outcome = OutcomeDto(kind = "postponed", byUserCode = "SELF01", at = 1L),
        )
        assertNull(dto.toDomain()!!.outcome)
        assertNull(OutcomeDto().toDomain())
    }

    @Test
    fun `an unknown challenge type cannot be mapped`() {
        assertNull(RoomDefinitionDto(type = "TREASURE_HUNT", name = "x").toDomain())
        assertNull(RoomDefinitionDto(name = "x").toDomain())
        assertNull(RoomStateDto(code = "ABC234", definition = RoomDefinitionDto(type = "TREASURE_HUNT")).toDomain())
    }

    @Test
    fun `an unknown source is curated with a catalog id and custom without`() {
        assertEquals(ChallengeSource.CURATED, RoomDefinitionDto(type = "ROUTE", source = "IMPORTED", catalogId = "c1").toDomain()!!.source)
        assertEquals(ChallengeSource.CUSTOM, RoomDefinitionDto(type = "ROUTE", source = "IMPORTED").toDomain()!!.source)
    }

    @Test
    fun `an unknown set member kind becomes null`() {
        assertNull(RoomDefinitionDto(type = "SET_COMPLETION", setMemberKind = "PLANET").toDomain()!!.setMemberKind)
        assertEquals(SetMemberKind.IATA, RoomDefinitionDto(type = "SET_COMPLETION", setMemberKind = "IATA").toDomain()!!.setMemberKind)
    }

    @Test
    fun `a snapshot without a code is dropped from the crew`() {
        val dto = RoomStateDto(
            code = "ABC234",
            definition = definition.toDto(),
            participants = listOf(ParticipantSnapshotDto(username = "Nobody"), ParticipantSnapshotDto(userCode = "", username = "Blank"), self.toDto()),
        )
        assertEquals(listOf(self), dto.toDomain()!!.participants)
    }

    @Test
    fun `a room without a code or a cache without a self code is null`() {
        assertNull(RoomStateDto(definition = definition.toDto()).toDomain())
        assertNull(RoomStateCacheDto(room = RoomStateDto(code = "ABC234", definition = definition.toDto())).toDomain())
        assertNull(RoomStateCacheDto(selfCode = "SELF01").toDomain())
    }

    @Test
    fun `claims map both ways and an unknown kind is no claim`() {
        assertEquals(OutcomeClaim.Completed(at = 9L), OutcomeClaim.Completed(at = 9L).toDto().toDomain())
        assertEquals(OutcomeClaim.Failed(brokenBy = "ANNA02"), OutcomeClaim.Failed(brokenBy = "ANNA02").toDto().toDomain())
        assertEquals(ClaimDto(kind = KIND_COMPLETED, at = 9L), OutcomeClaim.Completed(at = 9L).toDto())
        assertNull(ClaimDto(kind = "maybe").toDomain())
        assertNull(ClaimDto().toDomain())
    }

    @Test
    fun `unknown JSON fields are ignored`() {
        val json = """{"selfCode":"SELF01","future":true,"room":{"code":"ABC234","definition":{"type":"STREAK","name":"Five","targetDays":5,"reward":"gold"},"weather":"fine"}}"""
        val cache = RoomJson.decodeCache(json)!!
        assertEquals(5, cache.room.definition.targetDays)
    }

    @Test
    fun `decodeCache on garbage is null`() {
        listOf("", "not json", "{", "[]", "42", "{\"selfCode\":5,\"room\":\"nope\"}", "{\"selfCode\":\"S\",\"room\":{\"code\":[]}}")
            .forEach { assertNull("input: $it", RoomJson.decodeCache(it)) }
    }

    @Test
    fun `the shared_outcome column keeps bySelf across the round trip without a self code`() {
        // The column has no selfCode next to it, so the stamp has to be in the JSON itself.
        val won = SharedOutcome.Completed(byUserCode = "SELF01", bySelf = true, at = 4_000L, placements = listOf("SELF01", "ANNA02"))
        val lost = SharedOutcome.Failed(brokenByUserCode = "ANNA02", bySelf = false, at = 5_000L)
        assertEquals(won, RoomJson.decodeOutcome(RoomJson.encodeOutcome(won)))
        assertEquals(lost, RoomJson.decodeOutcome(RoomJson.encodeOutcome(lost)))

        // Without a stamp (wire-shaped JSON) the self code decides, and with neither it is false.
        val wire = """{"kind":"completed","byUserCode":"SELF01","at":4000}"""
        assertTrue(RoomJson.decodeOutcome(wire, selfCode = "SELF01")!!.bySelf)
        assertFalse(RoomJson.decodeOutcome(wire)!!.bySelf)

        // The wire mapping still ignores the stamp, so a peer's JSON can never claim to be us.
        assertFalse(RoomJson.gson.fromJson(RoomJson.encodeOutcome(won), OutcomeDto::class.java).toDomain()!!.bySelf)
    }

    @Test
    fun `decodeOutcome on garbage or an unknown kind is null`() {
        listOf("", "not json", "{", "[]", "{\"kind\":\"draw\"}", "{\"kind\":5}")
            .forEach { assertNull("input: $it", RoomJson.decodeOutcome(it)) }
    }
}
