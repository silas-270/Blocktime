package com.silas270.blocktime.data.network.room

import com.silas270.blocktime.data.model.OutcomeClaim
import com.silas270.blocktime.data.model.ParticipantSnapshot
import com.silas270.blocktime.data.model.RoomDefinition
import com.silas270.blocktime.data.model.RoomState

/**
 * The answer to a [RoomApi] call. Every failure the client has to act on differently is its
 * own case, so callers match on it instead of parsing status codes or exception types.
 */
sealed interface RoomResult<out T> {
    data class Ok<T>(val value: T) : RoomResult<T>

    /** Timeout, DNS failure, connection error or a 5xx: the server did not answer. The caller
     *  reports it to `ServerReachability` and changes nothing locally. */
    data object Unreachable : RoomResult<Nothing>

    /** The code does not exist, or no longer does (retention). */
    data object NotFound : RoomResult<Nothing>

    /** The room already has an outcome; nobody can join it any more. */
    data object RoomClosed : RoomResult<Nothing>

    /** A race that already has progress refuses new pilots. */
    data object RaceLocked : RoomResult<Nothing>

    /** The room already holds `MAX_ROOM_PARTICIPANTS` pilots. */
    data object RoomFull : RoomResult<Nothing>

    /** The pilot code and secret do not match what the server bound on the first write. */
    data object Unauthorized : RoomResult<Nothing>
}

/**
 * The room server's five routes as one interface, the contract of docs/shared-challenges.md
 * "Protocol". The server is a key-value store that knows nothing about flights: it holds, per
 * room, one definition and one snapshot per pilot, and applies the rules below.
 *
 * Three implementations: `HttpRoomApi` for a configured server, [NoRoomApi] when none is
 * configured, and [FakeRoomApi], the in-memory store that unit tests and the debug build drive.
 * The fake's test suite is the backend's contract test.
 *
 * Calls never throw for a server-side condition; they return the matching [RoomResult]. Every
 * call is expected to report its outcome to `ServerReachability` through the caller.
 */
interface RoomApi {
    /** False when the build has no server URL. Nothing else on this interface is called then. */
    val isConfigured: Boolean

    /** `GET /health`. True when the server answered. */
    suspend fun ping(): Boolean

    /**
     * `POST /rooms`. Creates a room with a fresh six-character code from the pilot-code
     * alphabet. The creator's snapshot is the first participant and takes colour 0.
     */
    suspend fun createRoom(definition: RoomDefinition, self: ParticipantSnapshot): RoomResult<RoomState>

    /** `GET /rooms/{code}`. The current state, or [RoomResult.NotFound]. */
    suspend fun getRoom(code: String): RoomResult<RoomState>

    /**
     * `PUT /rooms/{code}/participants/{userCode}`: upsert of the pilot's own snapshot, with an
     * optional claim. The reply is the fresh room state, so push and pull are one round trip.
     *
     * Server rules:
     * - **A stranger's first put is the join.** It takes the next free colour. A `left` snapshot
     *   with the same code is replaced by the new one, no longer left.
     * - A join is refused with [RoomResult.RoomClosed] once the room has an outcome, with
     *   [RoomResult.RaceLocked] for a route room in which any participant has progress, and with
     *   [RoomResult.RoomFull] at `MAX_ROOM_PARTICIPANTS`. An existing participant's put is never
     *   refused for these reasons.
     * - **A claim is kept only while the room has no outcome; the first wins.** Later claims are
     *   ignored and the reply carries the outcome that stands. Route placements are the arrivals
     *   in order of arrival, then the rest by progress.
     * - The server stamps `updatedAt` and bumps `version`. `bySelf` is always false from the
     *   server; the merge stamps it.
     */
    suspend fun putSnapshot(code: String, self: ParticipantSnapshot, claim: OutcomeClaim?): RoomResult<RoomState>

    /**
     * `DELETE /rooms/{code}/participants/{userCode}`: marks the pilot's snapshot `left`. The
     * snapshot stays, so pooled contributions stay; streak and race skip it. Idempotent, and
     * succeeds for a pilot who was never in the room.
     */
    suspend fun leaveRoom(code: String): RoomResult<Unit>
}
