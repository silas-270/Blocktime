package com.silas270.blocktime.data.network

import android.content.Context
import com.silas270.blocktime.data.network.room.RoomApiProvider
import com.silas270.blocktime.data.repository.PreferencesRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.time.Clock

/** Whether our room server can be used right now, and if not, why. */
enum class ServerState {
    /** The build has no `ROOM_SERVER_URL`; every sharing surface is absent. */
    NOT_CONFIGURED,

    /** The pilot has not turned on "Shared challenges"; every sharing surface is absent. */
    DISABLED,

    /** The device has no validated connection, so the server was not even asked. */
    DEVICE_OFFLINE,

    /** Connected and opted in, but nothing has asked the server yet. */
    UNKNOWN,

    REACHABLE,
    UNREACHABLE;
}

/** The last thing a probe or an API call said about the server. Starts at [UNKNOWN]. */
enum class ProbeResult { UNKNOWN, REACHABLE, UNREACHABLE }

/**
 * The pure derivation behind [ServerReachability.state], in the order of
 * docs/shared-challenges.md "Two signals": not configured, then not opted in, then the device
 * offline, and only then what the server last answered.
 */
internal fun resolveServerState(
    configured: Boolean,
    optIn: Boolean,
    connected: Boolean,
    lastProbe: ProbeResult,
): ServerState = when {
    !configured -> ServerState.NOT_CONFIGURED
    !optIn -> ServerState.DISABLED
    !connected -> ServerState.DEVICE_OFFLINE
    else -> when (lastProbe) {
        ProbeResult.UNKNOWN -> ServerState.UNKNOWN
        ProbeResult.REACHABLE -> ServerState.REACHABLE
        ProbeResult.UNREACHABLE -> ServerState.UNREACHABLE
    }
}

/**
 * The one place that decides whether *our server* answers, next to [OfflineModeController],
 * which decides whether the *device* is offline. **The two are separate signals on purpose**:
 * map tiles must keep working when our server is dead but the internet is fine, and sharing must
 * keep working when the data saver is on, so this reads the raw [isConnected] value and never
 * [NetworkMode]. `NetworkMode` reports `OFFLINE_DATA_SAVER` before it looks at connectivity and
 * would make an offline device look like "server not reachable". Data saver does not block sync:
 * a room is under 4 KB, and the switch is about map tiles.
 *
 * This class also owns the "Shared challenges" opt-in, the way [OfflineModeController] owns the
 * data saver, so the Settings row and the server state can never disagree.
 *
 * Nothing here polls on its own. [check] runs the [probe] at the sync moments (the interval sync
 * among them) and before a user action,
 * caching the answer for [PROBE_TTL_MS]; every API call [report]s its own outcome, so a dead
 * server is noticed by the first request that hits it.
 *
 * Production code uses the process-wide [getInstance].
 */
class ServerReachability(
    private val isConfigured: Boolean,
    private val isConnected: StateFlow<Boolean>,
    private val preferencesRepository: PreferencesRepository,
    private val probe: suspend () -> Boolean,
    scope: CoroutineScope,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val _optIn = MutableStateFlow(preferencesRepository.isOnlineFeaturesEnabled())
    val optIn: StateFlow<Boolean> = _optIn.asStateFlow()

    private val lastProbe = MutableStateFlow(ProbeResult.UNKNOWN)

    /** Epoch millis of the last [report], or null before the first one. */
    @Volatile
    private var lastProbeAt: Long? = null

    val state: StateFlow<ServerState> =
        combine(isConnected, _optIn, lastProbe) { connected, optIn, probe ->
            resolveServerState(isConfigured, optIn, connected, probe)
        }.stateIn(scope, SharingStarted.Eagerly, current())

    /** Turns sharing on or off. The first enable creates the room secret, so the pilot has a
     *  password before the first write the server binds it with. */
    fun setOnlineFeaturesEnabled(enabled: Boolean) {
        if (enabled) preferencesRepository.getOrCreateRoomSecret()
        preferencesRepository.setOnlineFeaturesEnabled(enabled)
        _optIn.value = enabled
    }

    /**
     * Asks the server, unless the answer is already known: a result younger than [PROBE_TTL_MS]
     * is returned as is when [force] is false. Returns without probing when the state does not
     * depend on the server at all (not configured, not opted in, device offline), so nothing
     * ever touches the network in those states. A probe that throws counts as unreachable.
     */
    suspend fun check(force: Boolean = false): ServerState {
        val before = current()
        if (before == ServerState.NOT_CONFIGURED || before == ServerState.DISABLED || before == ServerState.DEVICE_OFFLINE) {
            return before
        }
        val at = lastProbeAt
        if (!force && at != null && before != ServerState.UNKNOWN && clock.millis() - at < PROBE_TTL_MS) {
            return before
        }
        val success = try {
            probe()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
        report(success)
        return current()
    }

    /** Records the outcome of a probe or of any API call, and restarts the cache window. */
    fun report(success: Boolean) {
        lastProbeAt = clock.millis()
        lastProbe.value = if (success) ProbeResult.REACHABLE else ProbeResult.UNREACHABLE
    }

    /** Computed from the inputs directly, so [check] never waits on the [state] collector. */
    private fun current(): ServerState =
        resolveServerState(isConfigured, _optIn.value, isConnected.value, lastProbe.value)

    companion object {
        /** How long a probe result is trusted before [check] asks again. */
        const val PROBE_TTL_MS = 30_000L

        @Volatile
        private var instance: ServerReachability? = null

        fun getInstance(context: Context): ServerReachability =
            instance ?: synchronized(this) {
                instance ?: run {
                    val appContext = context.applicationContext
                    val roomApi = RoomApiProvider.roomApi
                    ServerReachability(
                        isConfigured = roomApi.isConfigured,
                        isConnected = OfflineModeController.getInstance(appContext).isConnected,
                        preferencesRepository = PreferencesRepository(appContext),
                        probe = { roomApi.ping() },
                        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
                    ).also { instance = it }
                }
            }
    }
}
