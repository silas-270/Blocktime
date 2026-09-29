package com.silas270.blocktime.data.network

import android.content.Context
import com.silas270.blocktime.data.repository.PreferencesRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** Whether the app may use the network, and if not, why. */
enum class NetworkMode {
    ONLINE,

    /** No validated connection; detected automatically. */
    OFFLINE_NO_CONNECTION,

    /** The pilot turned on "Offline maps" in Settings to save data. */
    OFFLINE_DATA_SAVER;

    val isOffline: Boolean get() = this != ONLINE
}

/** Data saver wins over a live connection: the pilot asked for no downloads. */
internal fun resolveNetworkMode(connected: Boolean, dataSaver: Boolean): NetworkMode = when {
    dataSaver -> NetworkMode.OFFLINE_DATA_SAVER
    !connected -> NetworkMode.OFFLINE_NO_CONNECTION
    else -> NetworkMode.ONLINE
}

/**
 * The one place that decides whether the app is offline. It combines the detected
 * connectivity with the pilot's data-saver setting. Screens read [mode] or [isOffline]
 * and never ask the monitor directly, so both kinds of offline behave the same.
 *
 * Production code uses the process-wide [getInstance]. Its state is
 * process-wide rather than per screen, so it isn't tied to the Activity's scope.
 */
class OfflineModeController(
    /**
     * The raw connectivity value, passed through untouched for [ServerReachability], which must
     * see the device's connection and not the data saver: [mode] reports `OFFLINE_DATA_SAVER`
     * before it looks at connectivity, and would make an offline device look like "server not
     * reachable" (docs/shared-challenges.md "Two signals"). Screens keep reading [mode].
     */
    val isConnected: StateFlow<Boolean>,
    private val preferencesRepository: PreferencesRepository,
    scope: CoroutineScope,
) {
    private val _dataSaverEnabled = MutableStateFlow(preferencesRepository.isOfflineDataSaverEnabled())
    val dataSaverEnabled: StateFlow<Boolean> = _dataSaverEnabled.asStateFlow()

    val mode: StateFlow<NetworkMode> = combine(isConnected, _dataSaverEnabled, ::resolveNetworkMode)
        .stateIn(scope, SharingStarted.Eagerly, resolveNetworkMode(isConnected.value, _dataSaverEnabled.value))

    val isOffline: StateFlow<Boolean> = mode.map { it.isOffline }
        .stateIn(scope, SharingStarted.Eagerly, mode.value.isOffline)

    fun setDataSaverEnabled(enabled: Boolean) {
        _dataSaverEnabled.value = enabled
        preferencesRepository.setOfflineDataSaverEnabled(enabled)
    }

    companion object {
        @Volatile
        private var instance: OfflineModeController? = null

        fun getInstance(context: Context): OfflineModeController =
            instance ?: synchronized(this) {
                instance ?: run {
                    val appContext = context.applicationContext
                    OfflineModeController(
                        ConnectivityMonitor(appContext).isConnected,
                        PreferencesRepository(appContext),
                        CoroutineScope(SupervisorJob() + Dispatchers.Default),
                    ).also { instance = it }
                }
            }
    }
}
