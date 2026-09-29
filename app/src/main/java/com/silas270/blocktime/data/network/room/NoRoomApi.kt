package com.silas270.blocktime.data.network.room

import com.silas270.blocktime.data.model.OutcomeClaim
import com.silas270.blocktime.data.model.ParticipantSnapshot
import com.silas270.blocktime.data.model.RoomDefinition
import com.silas270.blocktime.data.model.RoomState

/**
 * The [RoomApi] of a build without a server URL. [isConfigured] is false, which hides every
 * sharing surface, and every call answers [RoomResult.Unreachable] so a caller that gets here by
 * mistake still changes nothing locally.
 */
object NoRoomApi : RoomApi {
    override val isConfigured: Boolean = false

    override suspend fun ping(): Boolean = false

    override suspend fun createRoom(definition: RoomDefinition, self: ParticipantSnapshot): RoomResult<RoomState> =
        RoomResult.Unreachable

    override suspend fun getRoom(code: String): RoomResult<RoomState> = RoomResult.Unreachable

    override suspend fun putSnapshot(code: String, self: ParticipantSnapshot, claim: OutcomeClaim?): RoomResult<RoomState> =
        RoomResult.Unreachable

    override suspend fun leaveRoom(code: String): RoomResult<Unit> = RoomResult.Unreachable
}
