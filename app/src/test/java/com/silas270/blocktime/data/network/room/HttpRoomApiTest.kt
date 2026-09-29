package com.silas270.blocktime.data.network.room

import com.google.gson.JsonParser
import com.silas270.blocktime.data.model.ChallengeSource
import com.silas270.blocktime.data.model.ChallengeType
import com.silas270.blocktime.data.model.OutcomeClaim
import com.silas270.blocktime.data.model.ParticipantSnapshot
import com.silas270.blocktime.data.model.RoomDefinition
import com.silas270.blocktime.data.model.SharedOutcome
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * What [HttpRoomApi] sends and how it reads each answer, against a scripted server. The rules
 * themselves are the server's (backend/tests/protocol.rs); `HttpRoomApiLiveTest` walks them
 * through this client against the real one.
 */
class HttpRoomApiTest {

    private lateinit var server: MockWebServer
    private lateinit var api: HttpRoomApi

    private val self = ParticipantSnapshot(userCode = "SELF01", username = "Swift Pilot", distanceKm = 12.5)
    private val distance = RoomDefinition(
        type = ChallengeType.DISTANCE,
        source = ChallengeSource.CUSTOM,
        name = "Around the world",
        targetDistanceKm = 40_000.0,
    )

    private val roomJson = """
        {"code":"ROOM42","definition":{"type":"DISTANCE","source":"CUSTOM","name":"Around the world","targetDistanceKm":40000.0},
         "createdAt":1000,"participants":[{"userCode":"SELF01","username":"Swift Pilot","colorIndex":0,"left":false,"distanceKm":12.5,"updatedAt":1000}],
         "outcome":{"kind":"completed","byUserCode":"SELF01","at":2000,"placements":["SELF01"]},"version":3}
    """.trimIndent()

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        api = HttpRoomApi(server.url("/").toString()).apply { bindCaller("SELF01", "SECRET-SECRET-SECRET-SECRET-1234") }
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun reply(status: Int, body: String = "") {
        server.enqueue(MockResponse().setResponseCode(status).setBody(body))
    }

    @Test
    fun `create posts the definition and snapshot with the pilot's headers and reads the room`() = runTest {
        reply(201, roomJson)

        val result = api.createRoom(distance, self)

        val room = (result as RoomResult.Ok).value
        assertEquals("ROOM42", room.code)
        assertEquals(3L, room.version)
        assertEquals(SharedOutcome.Completed("SELF01", bySelf = false, at = 2000L, placements = listOf("SELF01")), room.outcome)

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/rooms", request.path)
        assertEquals("SELF01", request.getHeader("X-Pilot"))
        assertEquals("Bearer SECRET-SECRET-SECRET-SECRET-1234", request.getHeader("Authorization"))
        val body = JsonParser.parseString(request.body.readUtf8()).asJsonObject
        assertEquals("DISTANCE", body["definition"].asJsonObject["type"].asString)
        assertEquals("SELF01", body["snapshot"].asJsonObject["userCode"].asString)
    }

    @Test
    fun `put sends the snapshot and the claim to the pilot's own path`() = runTest {
        reply(200, roomJson)

        api.putSnapshot("ROOM42", self, OutcomeClaim.Failed(brokenBy = "ANNA02"))

        val request = server.takeRequest()
        assertEquals("PUT", request.method)
        assertEquals("/rooms/ROOM42/participants/SELF01", request.path)
        val body = JsonParser.parseString(request.body.readUtf8()).asJsonObject
        assertEquals("failed", body["claim"].asJsonObject["kind"].asString)
        assertEquals("ANNA02", body["claim"].asJsonObject["brokenBy"].asString)
        assertEquals(12.5, body["snapshot"].asJsonObject["distanceKm"].asDouble, 0.0)
    }

    @Test
    fun `a put without a claim sends none`() = runTest {
        reply(200, roomJson)
        api.putSnapshot("ROOM42", self, null)
        assertFalse(JsonParser.parseString(server.takeRequest().body.readUtf8()).asJsonObject.has("claim"))
    }

    @Test
    fun `leave deletes the pilot's own participant`() = runTest {
        reply(204)

        assertEquals(RoomResult.Ok(Unit), api.leaveRoom("ROOM42"))

        val request = server.takeRequest()
        assertEquals("DELETE", request.method)
        assertEquals("/rooms/ROOM42/participants/SELF01", request.path)
    }

    @Test
    fun `statuses map to the matching results`() = runTest {
        reply(404, """{"error":"NotFound"}""")
        assertEquals(RoomResult.NotFound, api.getRoom("NOPE42"))
        reply(401, """{"error":"Unauthorized"}""")
        assertEquals(RoomResult.Unauthorized, api.putSnapshot("ROOM42", self, null))
        reply(409, """{"error":"RoomClosed"}""")
        assertEquals(RoomResult.RoomClosed, api.putSnapshot("ROOM42", self, null))
        reply(409, """{"error":"RaceLocked"}""")
        assertEquals(RoomResult.RaceLocked, api.putSnapshot("ROOM42", self, null))
        reply(409, """{"error":"RoomFull"}""")
        assertEquals(RoomResult.RoomFull, api.putSnapshot("ROOM42", self, null))
        reply(404)
        assertEquals(RoomResult.NotFound, api.leaveRoom("NOPE42"))
    }

    @Test
    fun `anything the client cannot act on reads as unreachable`() = runTest {
        reply(500)
        assertEquals(RoomResult.Unreachable, api.getRoom("ROOM42"))
        reply(503)
        assertEquals(RoomResult.Unreachable, api.leaveRoom("ROOM42"))
        reply(429, """{"error":"RateLimited"}""")
        assertEquals(RoomResult.Unreachable, api.getRoom("ROOM42"))
        reply(409, """{"error":"SomethingNew"}""")
        assertEquals(RoomResult.Unreachable, api.putSnapshot("ROOM42", self, null))
        reply(200, "not json")
        assertEquals(RoomResult.Unreachable, api.getRoom("ROOM42"))
        reply(200, """{"code":"ROOM42"}""")
        assertEquals(RoomResult.Unreachable, api.getRoom("ROOM42"))
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        assertEquals(RoomResult.Unreachable, api.getRoom("ROOM42"))
    }

    @Test
    fun `ping is true only for a success`() = runTest {
        reply(204)
        assertTrue(api.ping())
        assertEquals("/health", server.takeRequest().path)
        reply(503)
        assertFalse(api.ping())
        server.shutdown()
        assertFalse(api.ping())
    }

    @Test
    fun `a write before any pilot is bound never reaches the server`() = runTest {
        val unbound = HttpRoomApi(server.url("/").toString())

        assertEquals(RoomResult.Unauthorized, unbound.createRoom(distance, self))
        assertEquals(RoomResult.Unauthorized, unbound.putSnapshot("ROOM42", self, null))
        assertEquals(RoomResult.Unauthorized, unbound.leaveRoom("ROOM42"))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `a read needs no pilot`() = runTest {
        reply(200, roomJson)
        val unbound = HttpRoomApi(server.url("/").toString())

        assertEquals("ROOM42", (unbound.getRoom("ROOM42") as RoomResult.Ok).value.code)
        assertNull(server.takeRequest().getHeader("X-Pilot"))
    }

    @Test
    fun `bindCallerIdentity binds the http client`() = runTest {
        reply(204)
        val api = HttpRoomApi(server.url("/").toString())
        (api as RoomApi).bindCallerIdentity("ANNA02", "ANNA-SECRET-ANNA-SECRET-ANNA-SEC")

        api.leaveRoom("ROOM42")

        val request = server.takeRequest()
        assertEquals("/rooms/ROOM42/participants/ANNA02", request.path)
        assertEquals("Bearer ANNA-SECRET-ANNA-SECRET-ANNA-SEC", request.getHeader("Authorization"))
    }
}
