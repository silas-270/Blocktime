package com.silas270.blocktime.data.network.room

import com.silas270.blocktime.BuildConfig

/**
 * Release wiring of the [RoomApi] (src/debug has its own): the HTTP client when the build has a
 * `ROOM_SERVER_URL`, [NoRoomApi] otherwise, which hides every sharing surface.
 */
object RoomApiProvider {
    val roomApi: RoomApi = if (BuildConfig.ROOM_SERVER_URL.isBlank()) {
        NoRoomApi
    } else {
        // TODO(G8): HttpRoomApi(BuildConfig.ROOM_SERVER_URL) once the backend exists. Until then
        // a configured URL behaves like none, so a release build can never talk to a server that
        // has not been contract-tested against FakeRoomApiTest.
        NoRoomApi
    }
}
