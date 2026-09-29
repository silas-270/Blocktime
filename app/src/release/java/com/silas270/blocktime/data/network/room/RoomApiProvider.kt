package com.silas270.blocktime.data.network.room

import com.silas270.blocktime.BuildConfig
import java.io.File

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

    /** A no-op: release has no fake to persist. Present so `CesiumGameActivity` calls the same
     *  function in both builds (the debug one keeps the fake's rooms in a file). */
    @Suppress("UNUSED_PARAMETER")
    fun attach(filesDir: File) = Unit
}
