package com.silas270.blocktime.ui.viewmodel.hub

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.silas270.blocktime.data.model.Airport
import com.silas270.blocktime.data.model.Challenge
import com.silas270.blocktime.data.model.ChallengeStatus
import com.silas270.blocktime.data.model.ChallengeType
import com.silas270.blocktime.data.model.FlightMode
import com.silas270.blocktime.data.model.FlightStats
import com.silas270.blocktime.data.model.PausedFlight
import com.silas270.blocktime.data.network.NetworkMode
import com.silas270.blocktime.data.network.OfflineModeController
import com.silas270.blocktime.data.repository.AirportRepository
import com.silas270.blocktime.data.repository.ChallengeRepository
import com.silas270.blocktime.data.repository.PilotProgressRepository
import com.silas270.blocktime.data.repository.PreferencesRepository
import com.silas270.blocktime.data.repository.SharedChallengeSyncer
import com.silas270.blocktime.data.repository.SyncSummary
import com.silas270.blocktime.data.repository.UserRepository
import com.silas270.blocktime.domain.resolveCurrentAirportIata
import com.silas270.blocktime.domain.resolveHomeAirportIata
import com.silas270.blocktime.engine.headless.CesiumHeadlessMapRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import java.io.File

class HubViewModel(
    private val airportRepository: AirportRepository,
    private val preferencesRepository: PreferencesRepository,
    private val userRepository: UserRepository,
    private val challengeRepository: ChallengeRepository,
    private val pilotProgressRepository: PilotProgressRepository,
    offlineModeController: OfflineModeController,
    sharedChallengeSyncer: SharedChallengeSyncer,
    private val cacheDir: File
) : ViewModel() {

    /** Drives the header's OFFLINE badge. The globe itself never needs the network. */
    val networkMode: StateFlow<NetworkMode> = offlineModeController.mode

    private val _currentAirport = MutableStateFlow<Airport?>(null)
    val currentAirport: StateFlow<Airport?> = _currentAirport.asStateFlow()

    /** The Route challenge currently focused on the Hub (its position/progress drives
     *  [currentAirport] and the globe/progress-strip below), or null when the Hub is showing the
     *  normal story-mode airport. See PreferencesRepository.getFocusedRouteChallengeId's doc. */
    private val _focusedChallenge = MutableStateFlow<Challenge?>(null)
    val focusedChallenge: StateFlow<Challenge?> = _focusedChallenge.asStateFlow()

    private val _flightStats = MutableStateFlow(FlightStats())
    val flightStats: StateFlow<FlightStats> = _flightStats.asStateFlow()

    /** The pilot's username for the Hub greeting, or null until the profile has loaded. Follows
     *  the profile flow, so a rename on the Passport shows up here without a refresh. */
    private val _pilotName = MutableStateFlow<String?>(null)
    val pilotName: StateFlow<String?> = _pilotName.asStateFlow()

    private val _routeMapPath = MutableStateFlow<String?>(null)
    val routeMapPath: StateFlow<String?> = _routeMapPath.asStateFlow()

    private val _isRendering = MutableStateFlow(false)
    val isRendering: StateFlow<Boolean> = _isRendering.asStateFlow()

    private val _pausedFlight = MutableStateFlow<PausedFlight?>(null)
    val pausedFlight: StateFlow<PausedFlight?> = _pausedFlight.asStateFlow()

    private val _mapRenderError = MutableStateFlow<String?>(null)
    val mapRenderError: StateFlow<String?> = _mapRenderError.asStateFlow()

    private val mapRenderer = CesiumHeadlessMapRenderer(cacheDir)

    init {
        loadData()
        viewModelScope.launch {
            pilotProgressRepository.progress.collect { progress ->
                _flightStats.value = progress?.stats ?: FlightStats()
            }
        }
        viewModelScope.launch {
            userRepository.getProfileFlow().collect { profile ->
                _pilotName.value = profile?.username?.takeIf { it.isNotBlank() }
            }
        }
        viewModelScope.launch {
            // A sync can end the focused route (someone else won) while the pilot sits on the
            // Hub; refreshing on each completed sync lets resolveFocusedChallenge clear the focus
            // now rather than at the next ON_START (docs/shared-challenges.md, Y10). The current
            // value is dropped: loadData() above already read what that sync wrote.
            sharedChallengeSyncer.lastSummary.drop(1).collect { summary ->
                if (summary is SyncSummary.Synced) refresh()
            }
        }
    }

    /** Re-runs [loadData]. `init` only fires once per ViewModel instance, but the Hub's nav
     *  back-stack entry (and this ViewModel with it) survives a `popBackStack()` from screens
     *  like Account/Passport - so anything that can change `currentAirport` while Hub isn't the
     *  active screen (e.g. docs/modes.md's return-home teleport) needs an explicit re-fetch on
     *  return, not just a fresh load on first creation. See HubScreen's ON_START observer. */
    fun refresh() {
        loadData()
    }

    private fun loadData() {
        viewModelScope.launch(Dispatchers.IO) {
            val focused = resolveFocusedChallenge()
            _focusedChallenge.value = focused

            // A focused Route challenge takes over the displayed airport (its own position
            // pointer) - the Hub shows "where the challenge is", not the story-mode base - until
            // the player explicitly exits it. See exitFocusedChallenge().
            val baseIata = focused?.positionIata
                ?: resolveCurrentAirportIata(preferencesRepository, userRepository)
            if (baseIata != null) {
                val airport = airportRepository.getAirportByIata(baseIata)
                _currentAirport.value = airport

                // A focused challenge's paused flight (its own row, not the Story slot below)
                // drives Resume/Book here - so switching focus never shows a Resume button for a
                // flight that belongs to a different mode/challenge. See Challenge.pausedFlight's
                // doc. The Hub only ever shows Story Mode's own slot - Free Mode's paused flight
                // (a fully separate slot, see PreferencesRepository.pausedFreeFlightStore) has
                // its own Resume row on the Challenges screen instead.
                _pausedFlight.value = if (focused != null) {
                    focused.pausedFlight
                } else {
                    preferencesRepository.pausedFlightStore(FlightMode.STORY).get()
                }

                if (airport != null) {
                    generateRouteMap(airport)
                }
            }
        }
    }

    private fun generateRouteMap(origin: Airport) {
        _mapRenderError.value = null
        viewModelScope.launch(Dispatchers.IO) {
            _isRendering.value = true
            try {
                val result = mapRenderer.renderRouteMapForAirport(airportRepository, origin, reuseCachedFile = true)
                when (result) {
                    is CesiumHeadlessMapRenderer.Result.Success -> _routeMapPath.value = result.path
                    is CesiumHeadlessMapRenderer.Result.Failure -> _mapRenderError.value = result.message
                }
            } finally {
                _isRendering.value = false
            }
        }
    }

    fun retryRenderMap() {
        val origin = _currentAirport.value
        if (origin != null) {
            generateRouteMap(origin)
        }
    }

    /** Reads the focused-challenge pref and validates it still points at something focusable -
     *  an ACTIVE Route challenge. Self-heals a stale pref (abandoned/completed elsewhere) by
     *  clearing it, so a leftover id never silently strands the Hub. */
    private suspend fun resolveFocusedChallenge(): Challenge? {
        val id = preferencesRepository.getFocusedRouteChallengeId() ?: return null
        val challenge = challengeRepository.getChallenge(id)
        val stillFocusable = challenge != null &&
            challenge.type == ChallengeType.ROUTE &&
            challenge.status == ChallengeStatus.ACTIVE
        if (!stillFocusable) {
            preferencesRepository.clearFocusedRouteChallengeId()
            return null
        }
        return challenge
    }

    /** Pauses the focused challenge: the Hub reverts to the story-mode airport, but the challenge
     *  itself is untouched - still ACTIVE, at its saved position - and can be refocused later. */
    fun exitFocusedChallenge() {
        preferencesRepository.clearFocusedRouteChallengeId()
        refresh()
    }
}

class HubViewModelFactory(
    private val airportRepository: AirportRepository,
    private val preferencesRepository: PreferencesRepository,
    private val userRepository: UserRepository,
    private val challengeRepository: ChallengeRepository,
    private val pilotProgressRepository: PilotProgressRepository,
    private val offlineModeController: OfflineModeController,
    private val sharedChallengeSyncer: SharedChallengeSyncer,
    private val cacheDir: File
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(HubViewModel::class.java)) {
            return HubViewModel(airportRepository, preferencesRepository, userRepository, challengeRepository, pilotProgressRepository, offlineModeController, sharedChallengeSyncer, cacheDir) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
