package com.silas270.blocktime.data.network.room

import com.silas270.blocktime.BuildConfig

/**
 * Debug wiring of the [RoomApi] (src/release has its own). With a `ROOM_SERVER_URL` it behaves
 * like release; without one it uses a [FakeRoomApi], so the whole sharing flow can be walked on
 * a phone before a backend exists.
 *
 * The bot: a later debug receiver drives [fake] to play a second pilot, joining the room the
 * pilot shared and advancing its snapshot a step at a time through `addSimulatedParticipant`
 * and `advanceSimulated`, so share, join, team progress, celebration and shatter can all be
 * seen without a server. Nothing is scheduled here; the receiver decides when the bot moves.
 */
object RoomApiProvider {
    /** The fake behind [roomApi] when the build has no server URL, for the debug receiver. */
    internal val fake: FakeRoomApi? = if (BuildConfig.ROOM_SERVER_URL.isBlank()) FakeRoomApi() else null

    val roomApi: RoomApi = fake
        // TODO(G8): HttpRoomApi(BuildConfig.ROOM_SERVER_URL) once the backend exists.
        ?: NoRoomApi
}
