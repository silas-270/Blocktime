package com.silas270.blocktime.data.network.room

import android.util.Log
import com.silas270.blocktime.BuildConfig
import com.silas270.blocktime.data.model.RoomState
import java.io.File

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
    private const val TAG = "RoomApiProvider"

    /** The fake behind [roomApi] when the build has no server URL, for the debug receiver. */
    internal val fake: FakeRoomApi? = if (BuildConfig.ROOM_SERVER_URL.isBlank()) FakeRoomApi() else null

    val roomApi: RoomApi = fake
        // TODO(G8): HttpRoomApi(BuildConfig.ROOM_SERVER_URL) once the backend exists.
        ?: NoRoomApi

    private var attached = false

    /**
     * Keeps [fake]'s rooms in `debug_rooms.json` under [filesDir], loading them now and saving
     * after every change. A no-op without a fake, and after the first call, so the Activity and
     * the debug receiver can both call it. Called before the first use of [roomApi], because
     * attaching replaces the rooms the fake holds; without the file a reinstall or a killed
     * process empties the fake, and the next sync marks every shared row "Room closed".
     */
    @Synchronized
    fun attach(filesDir: File) {
        val target = fake ?: return
        if (attached) return
        attached = true
        target.attachStore(FileRoomStore(File(filesDir, "debug_rooms.json")))
    }

    /** The fake's rooms as one JSON file. A write goes to a temporary file first and is then
     *  renamed over the old one, so a process killed mid-write leaves the previous state. */
    private class FileRoomStore(private val file: File) : FakeRoomStore {
        override fun load(): List<RoomState> =
            try {
                if (file.exists()) RoomJson.decodeRooms(file.readText()) else emptyList()
            } catch (e: Exception) {
                Log.w(TAG, "Could not read ${file.name}; the fake starts empty", e)
                emptyList()
            }

        override fun save(rooms: List<RoomState>) {
            try {
                val temp = File(file.parentFile, "${file.name}.tmp")
                temp.writeText(RoomJson.encodeRooms(rooms))
                if (!temp.renameTo(file)) Log.w(TAG, "Could not replace ${file.name}")
            } catch (e: Exception) {
                Log.w(TAG, "Could not write ${file.name}", e)
            }
        }
    }
}
