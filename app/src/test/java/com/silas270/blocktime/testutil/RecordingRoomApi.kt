package com.silas270.blocktime.testutil

import com.silas270.blocktime.data.model.OutcomeClaim
import com.silas270.blocktime.data.model.ParticipantSnapshot
import com.silas270.blocktime.data.model.RoomDefinition
import com.silas270.blocktime.data.model.RoomState
import com.silas270.blocktime.data.network.room.FakeRoomApi
import com.silas270.blocktime.data.network.room.RoomApi
import com.silas270.blocktime.data.network.room.RoomResult

/**
 * A [FakeRoomApi] behind a recorder: [calls] lists every call by name, in order, and [onCall]
 * runs before each one is handed to the fake, which is how a test puts a local write "during
 * the round trip" (a landing between creating a room and taking the lock, a credit while an
 * upload is in flight, an abandon mid-sync).
 *
 * The wrapper is not a [FakeRoomApi], so the repository's and the syncer's identity binding
 * does not reach [fake]; a test sets `fake.callerUserCode` itself.
 */
internal class RecordingRoomApi(val fake: FakeRoomApi) : RoomApi {
    val calls = mutableListOf<String>()
    var onCall: suspend (String) -> Unit = {}

    override val isConfigured: Boolean get() = fake.isConfigured

    override suspend fun ping(): Boolean = fake.ping()

    override suspend fun createRoom(definition: RoomDefinition, self: ParticipantSnapshot): RoomResult<RoomState> {
        record("createRoom")
        return fake.createRoom(definition, self)
    }

    override suspend fun getRoom(code: String): RoomResult<RoomState> {
        record("getRoom")
        return fake.getRoom(code)
    }

    override suspend fun putSnapshot(code: String, self: ParticipantSnapshot, claim: OutcomeClaim?): RoomResult<RoomState> {
        record(if (claim == null) "putSnapshot" else "putSnapshot+claim")
        return fake.putSnapshot(code, self, claim)
    }

    override suspend fun leaveRoom(code: String): RoomResult<Unit> {
        record("leaveRoom")
        return fake.leaveRoom(code)
    }

    private suspend fun record(name: String) {
        calls += name
        onCall(name)
    }
}
