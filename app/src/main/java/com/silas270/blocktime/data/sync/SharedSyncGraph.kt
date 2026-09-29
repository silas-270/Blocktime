package com.silas270.blocktime.data.sync

import android.content.Context
import com.silas270.blocktime.data.local.AppDatabase
import com.silas270.blocktime.data.local.airport.AirportRouteSqliteDataSource
import com.silas270.blocktime.data.network.ServerReachability
import com.silas270.blocktime.data.network.room.RoomApiProvider
import com.silas270.blocktime.data.repository.AirportRepository
import com.silas270.blocktime.data.repository.ChallengeRepository
import com.silas270.blocktime.data.repository.LocalAirportRepository
import com.silas270.blocktime.data.repository.LocalChallengeRepository
import com.silas270.blocktime.data.repository.LocalUserRepository
import com.silas270.blocktime.data.repository.PreferencesRepository
import com.silas270.blocktime.data.repository.SharedChallengeSyncer
import com.silas270.blocktime.data.repository.UserRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * The one challenge repository and the one syncer of the process, shared by the Activity and
 * [SharedSyncWorker] (docs/shared-challenges.md "Sync moments"). **One of each, not one per
 * user:** the repository's write mutex and the syncer's run mutex only serialise what goes
 * through the same instance, so a worker with its own pair could merge a room state into a row
 * while the Activity credits a landing to it.
 *
 * The syncer runs on a scope of its own that lives as long as the process, since the worker has
 * no Activity to borrow one from. Building the graph also keeps the background schedule in step
 * with the "Shared challenges" switch.
 */
class SharedSyncGraph private constructor(context: Context) {
    val preferencesRepository = PreferencesRepository(context)
    val airportRepository: AirportRepository = LocalAirportRepository(AirportRouteSqliteDataSource(context))
    val userRepository: UserRepository
    val challengeRepository: ChallengeRepository
    val serverReachability: ServerReachability
    val syncer: SharedChallengeSyncer

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        // Before the first use of the room api: attaching replaces the rooms the debug fake holds
        // with those in its file, and a worker that synced against an empty fake would mark every
        // shared row "Room closed". A no-op in release and with a server URL.
        RoomApiProvider.attach(context.filesDir)
        val roomApi = RoomApiProvider.roomApi
        serverReachability = ServerReachability.getInstance(context)

        val database = AppDatabase.getInstance(context)
        userRepository = LocalUserRepository(database.userProfileDao())
        challengeRepository = LocalChallengeRepository(
            database.challengeDao(),
            database.userProfileDao(),
            airportRepository,
            roomApi = roomApi,
            serverReachability = serverReachability,
            preferencesRepository = preferencesRepository,
        )
        syncer = SharedChallengeSyncer(
            challengeRepository,
            userRepository,
            preferencesRepository,
            roomApi,
            serverReachability,
            scope,
        )

        scope.launch {
            serverReachability.optIn.collect { optIn ->
                SharedSyncWorker.schedule(context, enabled = roomApi.isConfigured && optIn)
            }
        }
    }

    companion object {
        @Volatile
        private var instance: SharedSyncGraph? = null

        fun get(context: Context): SharedSyncGraph =
            instance ?: synchronized(this) {
                instance ?: SharedSyncGraph(context.applicationContext).also { instance = it }
            }
    }
}
