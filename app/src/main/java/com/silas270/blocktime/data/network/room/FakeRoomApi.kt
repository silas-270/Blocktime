package com.silas270.blocktime.data.network.room

import com.silas270.blocktime.data.model.ChallengeType
import com.silas270.blocktime.data.model.MAX_ROOM_PARTICIPANTS
import com.silas270.blocktime.data.model.OutcomeClaim
import com.silas270.blocktime.data.model.ParticipantSnapshot
import com.silas270.blocktime.data.model.ROOM_CODE_LENGTH
import com.silas270.blocktime.data.model.RoomDefinition
import com.silas270.blocktime.data.model.RoomState
import com.silas270.blocktime.data.model.SharedOutcome
import com.silas270.blocktime.data.model.generateCode
import com.silas270.blocktime.data.model.nextFreeColorIndex
import kotlinx.coroutines.delay

/**
 * Where a [FakeRoomApi] keeps its rooms across process death. The debug build attaches a
 * file-backed one, because an in-memory fake emptied by a reinstall or a killed process would
 * make the next sync mark every shared row "Room closed". Tests attach an in-memory one.
 */
internal interface FakeRoomStore {
    fun load(): List<RoomState>
    fun save(rooms: List<RoomState>)
}

/**
 * An in-memory room server that applies the rules of docs/shared-challenges.md "Protocol". Unit
 * tests drive it to play the other pilots, and a debug build without a server uses it with a bot
 * crew member, so the whole flow can be walked on a phone before a backend exists. **Its test
 * suite is the backend's contract test**: whatever `FakeRoomApiTest` asserts, the real server
 * must do too.
 *
 * Lives in `src/main` rather than `src/test` because the debug build needs it and `src/test`
 * cannot see `src/debug`.
 *
 * Thread-safe: every access to the room map is under one lock, and the simulated latency is
 * waited out before the lock is taken. With a [FakeRoomStore] attached, every mutation saves the
 * whole map under the same lock, so the stored rooms are always a state the fake has held.
 */
internal class FakeRoomApi(private val clock: () -> Long = System::currentTimeMillis) : RoomApi {

    /**
     * The pilot on whose behalf the app calls. `HttpRoomApi` sends it as the `X-Pilot` header;
     * here the app sets it once it knows the profile, before the first call. A snapshot or a
     * claim for another code answers [RoomResult.Unauthorized], as the server would.
     */
    var callerUserCode: String = ""

    /** True makes every call answer [RoomResult.Unreachable], a dead server. */
    var unreachable: Boolean = false

    /** Simulated round-trip time, waited before each call is handled. */
    var latencyMs: Long = 0L

    override val isConfigured: Boolean = true

    private val lock = Any()
    private val rooms = mutableMapOf<String, RoomState>()
    private var store: FakeRoomStore? = null

    /**
     * Replaces the rooms with those [store] holds and saves to it after every mutation from now
     * on: create, put, leave, and every helper below that changes a room. Attach before the
     * first call, so no room created earlier is dropped by the replacement.
     */
    fun attachStore(store: FakeRoomStore) {
        val loaded = store.load()
        synchronized(lock) {
            rooms.clear()
            loaded.associateByTo(rooms) { it.code }
            this.store = store
        }
    }

    /** Writes the map to the attached store. Called under [lock], after each mutation. */
    private fun persist() {
        store?.save(rooms.values.toList())
    }

    override suspend fun ping(): Boolean {
        delay(latencyMs)
        return !unreachable
    }

    override suspend fun createRoom(definition: RoomDefinition, self: ParticipantSnapshot): RoomResult<RoomState> {
        delay(latencyMs)
        if (unreachable) return RoomResult.Unreachable
        if (self.userCode != callerUserCode) return RoomResult.Unauthorized
        return synchronized(lock) {
            var code: String
            do {
                code = generateCode(ROOM_CODE_LENGTH)
            } while (rooms.containsKey(code))
            val now = clock()
            val room = RoomState(
                code = code,
                definition = definition,
                createdAt = now,
                participants = listOf(self.copy(colorIndex = 0, left = false, updatedAt = now)),
                outcome = null,
                version = 1L,
            )
            rooms[code] = room
            persist()
            RoomResult.Ok(room)
        }
    }

    override suspend fun getRoom(code: String): RoomResult<RoomState> {
        delay(latencyMs)
        if (unreachable) return RoomResult.Unreachable
        return synchronized(lock) {
            rooms[code]?.let { RoomResult.Ok(it) } ?: RoomResult.NotFound
        }
    }

    override suspend fun putSnapshot(code: String, self: ParticipantSnapshot, claim: OutcomeClaim?): RoomResult<RoomState> {
        delay(latencyMs)
        if (unreachable) return RoomResult.Unreachable
        if (self.userCode != callerUserCode) return RoomResult.Unauthorized
        return synchronized(lock) {
            val room = rooms[code] ?: return@synchronized RoomResult.NotFound
            val existing = room.participants.firstOrNull { it.userCode == self.userCode }

            // A stranger's first put is the join, and only a join can be refused.
            if (existing == null) {
                if (room.outcome != null) return@synchronized RoomResult.RoomClosed
                if (room.definition.type == ChallengeType.ROUTE && room.participants.any { it.hasRouteProgress() }) {
                    return@synchronized RoomResult.RaceLocked
                }
                if (room.participants.size >= MAX_ROOM_PARTICIPANTS) return@synchronized RoomResult.RoomFull
            }

            val now = clock()
            val stored = self.copy(
                colorIndex = existing?.colorIndex ?: room.nextFreeColorIndex(),
                left = false,
                updatedAt = now,
            )
            val participants = if (existing == null) {
                room.participants + stored
            } else {
                room.participants.map { if (it.userCode == stored.userCode) stored else it }
            }

            // The first claim wins; a room with an outcome ignores every later one.
            val outcome = room.outcome ?: claim?.let { resolveClaim(room.definition.type, participants, stored.userCode, it, now) }

            val updated = room.copy(participants = participants, outcome = outcome, version = room.version + 1)
            rooms[code] = updated
            persist()
            RoomResult.Ok(updated)
        }
    }

    override suspend fun leaveRoom(code: String): RoomResult<Unit> {
        delay(latencyMs)
        if (unreachable) return RoomResult.Unreachable
        return synchronized(lock) {
            val room = rooms[code] ?: return@synchronized RoomResult.NotFound
            val participants = room.participants.map {
                if (it.userCode == callerUserCode) it.copy(left = true, updatedAt = clock()) else it
            }
            rooms[code] = room.copy(participants = participants, version = room.version + 1)
            persist()
            RoomResult.Ok(Unit)
        }
    }

    // Test and debug helpers. These bypass the caller identity, because they play the other pilots.

    /** Adds another pilot to the room, taking the next colour, and returns their snapshot. */
    fun addSimulatedParticipant(code: String, userCode: String, username: String): ParticipantSnapshot =
        synchronized(lock) {
            val room = requireNotNull(rooms[code]) { "No room $code" }
            val snapshot = ParticipantSnapshot(
                userCode = userCode,
                username = username,
                colorIndex = room.nextFreeColorIndex(),
                updatedAt = clock(),
            )
            rooms[code] = room.copy(participants = room.participants + snapshot, version = room.version + 1)
            persist()
            snapshot
        }

    /** Replaces a simulated pilot's snapshot with [transform] of it, stamped with the clock. */
    fun advanceSimulated(code: String, userCode: String, transform: (ParticipantSnapshot) -> ParticipantSnapshot) {
        synchronized(lock) {
            val room = requireNotNull(rooms[code]) { "No room $code" }
            val current = requireNotNull(room.participants.firstOrNull { it.userCode == userCode }) {
                "No participant $userCode in room $code"
            }
            val next = transform(current).copy(userCode = userCode, updatedAt = clock())
            rooms[code] = room.copy(
                participants = room.participants.map { if (it.userCode == userCode) next else it },
                version = room.version + 1,
            )
            persist()
        }
    }

    /** The room as the server holds it, or null. */
    fun room(code: String): RoomState? = synchronized(lock) { rooms[code] }

    /** Every room the fake holds, for the debug receiver that plays the bot in all of them. */
    fun roomCodes(): List<String> = synchronized(lock) { rooms.keys.toList() }

    /** Puts a whole room in place, as it is, for tests that start from a prepared state. */
    fun seedRoom(state: RoomState) {
        synchronized(lock) {
            rooms[state.code] = state
            persist()
        }
    }

    private fun ParticipantSnapshot.hasRouteProgress(): Boolean = routeProgress > 0f || legIndex > 0

    /**
     * Route placements: the arrivals in order of arrival, then the rest by progress. With the
     * first claim closing the room there is exactly one arrival, the claimer; the others rank by
     * their progress at that moment, and a pilot who left is not ranked.
     */
    private fun resolveClaim(
        type: ChallengeType,
        participants: List<ParticipantSnapshot>,
        claimer: String,
        claim: OutcomeClaim,
        now: Long,
    ): SharedOutcome = when (claim) {
        is OutcomeClaim.Completed -> {
            val placements = if (type == ChallengeType.ROUTE) {
                listOf(claimer) + participants
                    .filter { !it.left && it.userCode != claimer }
                    .sortedByDescending { it.routeProgress }
                    .map { it.userCode }
            } else {
                listOf(claimer)
            }
            SharedOutcome.Completed(byUserCode = claimer, bySelf = false, at = now, placements = placements)
        }
        is OutcomeClaim.Failed -> SharedOutcome.Failed(brokenByUserCode = claim.brokenBy, bySelf = false, at = now)
    }
}

/**
 * Tells the API on whose behalf the app calls. `HttpRoomApi` sends the pilot code as the
 * `X-Pilot` header on every request and needs nothing here; the fake has no request to read it
 * from, so the repository and the syncer bind it before their first call. A no-op for every
 * other implementation, which is why callers can use it without knowing which one they hold.
 */
internal fun RoomApi.bindCallerIdentity(userCode: String) {
    if (this is FakeRoomApi) callerUserCode = userCode
}
