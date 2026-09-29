package com.silas270.blocktime.ui.viewmodel.account

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.silas270.blocktime.data.model.Airport
import com.silas270.blocktime.data.model.FlightHighlights
import com.silas270.blocktime.data.model.FlightLog
import com.silas270.blocktime.data.model.FlightSortOrder
import com.silas270.blocktime.data.model.FlightStats
import com.silas270.blocktime.data.model.Tour
import com.silas270.blocktime.data.model.HomeBaseCooldown
import com.silas270.blocktime.data.network.NetworkMode
import com.silas270.blocktime.data.network.OfflineModeController
import com.silas270.blocktime.data.network.ServerReachability
import com.silas270.blocktime.data.network.ServerState
import com.silas270.blocktime.data.repository.PilotProgressRepository
import com.silas270.blocktime.data.repository.AirportRepository
import com.silas270.blocktime.data.repository.FlightLogRepository
import com.silas270.blocktime.data.repository.PreferencesRepository
import com.silas270.blocktime.data.repository.UserRepository
import com.silas270.blocktime.domain.AirportSearchController
import com.silas270.blocktime.ui.components.airportpicker.resolveSuggestedAirports
import com.silas270.blocktime.ui.screens.account.AchievementStack
import com.silas270.blocktime.ui.screens.account.buildAchievementStacks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class AccountUiState(
    // Profile Data
    val username: String = "",
    val userCode: String = "",
    val homeAirportIata: String = "",
    // Resolved from [homeAirportIata] so the return-home celebration can name the airport and city
    // rather than only echoing the code back at the pilot.
    val homeAirport: Airport? = null,
    val joinDateFormatted: String = "",
    
    // Passport/Stats Data
    val stats: FlightStats = FlightStats(),

    // Flight History (used for map paths/stats, and rendered directly by the logbook)
    val flightHistory: List<FlightLog> = emptyList(),

    /** Newest first. Groups [flightHistory] in the logbook, and drives the tour-days stat. */
    val tours: List<Tour> = emptyList(),
    
    // Map Data
    val allVisitedCountries: Set<String> = emptySet(),
    val completedContinents: Set<String> = emptySet(),
    val countryToContinent: Map<String, String> = emptyMap(),
    val mapPaths: List<com.silas270.blocktime.ui.map.CountryPath> = emptyList(),
    
    // Highlights Card Data
    val highlights: FlightHighlights = FlightHighlights(),
    val sortOrder: FlightSortOrder = FlightSortOrder.DATE_DESC,

    // Earned achievements only - the Passport is a trophy case. Everything still unearned lives on
    // the Challenges screen instead. Not split by category: the badge row is one flat scroll, with
    // no group headers to feed.
    //
    // Grouped into stacks rather than a flat list, so a ladder family (the cumulative-distance
    // milestones) shows as one tile instead of four near-identical plaques. A non-ladder
    // achievement is simply a stack of one. See buildAchievementStacks.
    val achievementStacks: List<AchievementStack> = emptyList(),

    // Home base + return (docs/modes.md) - the two cooldown-gated actions, both
    // computed once per loadData()/action call rather than ticking live every second; see
    // AccountViewModel's doc comment on refreshHomeBaseCooldowns() for why that's an acceptable
    // simplification here.
    val currentAirportIata: String = "",
    /** Destination of a paused Story flight that returning home would discard (it departs from
     *  somewhere other than home), or null if there is none - the confirm modal warns about it. */
    val returnHomeDiscardsFlightTo: String? = null,
    val returnHomeEligible: Boolean = true,
    val returnHomeRemainingMillis: Long = 0L,
    val changeHomeBaseEligible: Boolean = true,
    val changeHomeBaseRemainingMillis: Long = 0L,

    val isLoading: Boolean = true
)

/**
 * Outcome of one [AccountViewModel.returnHome] / [AccountViewModel.changeHomeBase] attempt.
 *
 * Both actions are fire-and-forget from the UI's point of view (they stay in `viewModelScope`, so
 * they survive the Account screen leaving composition) *and* both re-check their cooldown inside
 * the coroutine, because the screen's copy of eligibility can be stale or racy. That combination
 * used to mean the celebration screens were shown optimistically at the call site for a teleport /
 * home-base change the ViewModel had silently refused. Everything the celebration needs now comes
 * back through here instead, so "we celebrated" and "it actually happened" can no longer diverge.
 *
 * [HomeBaseSet] carries the [Airport] it wrote rather than letting the screen re-read
 * `uiState.homeAirport`: that field is fed by the Room profile flow, which has not necessarily
 * re-emitted by the time the celebration draws.
 *
 * [Ineligible] and [Failed] are both "show no celebration" for the UI, and are kept apart only so
 * the distinction (refused by a cooldown vs. a write that broke) stays legible at the call site.
 */
sealed interface HomeBaseActionResult {
    /** The teleport happened: `currentAirport` is now the home base. */
    object ReturnedHome : HomeBaseActionResult

    /** The home base is now [airport] - the value written, not a re-read of any state. */
    data class HomeBaseSet(val airport: Airport) : HomeBaseActionResult

    /** Refused by the re-checked cooldown. The pilot's state is untouched. */
    object Ineligible : HomeBaseActionResult

    /** Attempted but broken - no home airport to return to, or a store write that threw. */
    object Failed : HomeBaseActionResult
}

@OptIn(ExperimentalCoroutinesApi::class)
class AccountViewModel(
    private val context: android.content.Context,
    private val userRepository: UserRepository,
    private val flightLogRepository: FlightLogRepository,
    private val airportRepository: AirportRepository,
    private val preferencesRepository: PreferencesRepository,
    private val pilotProgressRepository: PilotProgressRepository,
    private val offlineModeController: OfflineModeController,
    private val serverReachability: ServerReachability,
    private val cacheDir: java.io.File
) : ViewModel() {

    private val mapRenderer = com.silas270.blocktime.engine.headless.CesiumHeadlessMapRenderer(cacheDir)

    /**
     * Starts rendering the home base's globe the moment the return-home animation begins, rather
     * than waiting for the teleport to commit.
     *
     * By the time this is called the outcome is already decided: the animation is not dismissible,
     * and it ends by attempting the teleport. So there are ten seconds of guaranteed idle time in
     * which the very image the Hub is about to ask for can be produced. Previously nothing started
     * until the animation finished, and the pilot then watched a second loading state for a render
     * that could have been done already.
     *
     * Runs in `viewModelScope` and is entirely best-effort: it only ever populates the same file
     * cache `HubViewModel` reads through, so if it is slow, fails, or the pilot leaves, the Hub
     * renders exactly as it does today. Nothing downstream waits on it.
     *
     * Deliberately does NOT re-check the cooldown. This writes no state - the worst case for
     * rendering a map the teleport then refuses is a warm cache entry for the pilot's own home
     * base, which is the one airport most worth having warm anyway.
     */
    fun prepareReturnHome() {
        viewModelScope.launch(Dispatchers.IO) {
            val homeIata = com.silas270.blocktime.domain.resolveHomeAirportIata(userRepository) ?: return@launch
            val homeAirport = airportRepository.getAirportByIata(homeIata) ?: return@launch
            when (val result = mapRenderer.renderRouteMapForAirport(airportRepository, homeAirport, reuseCachedFile = true)) {
                is com.silas270.blocktime.engine.headless.CesiumHeadlessMapRenderer.Result.Success ->
                    android.util.Log.d("AccountViewModel", "Return-home map ready (fromCache=${result.fromCache})")
                is com.silas270.blocktime.engine.headless.CesiumHeadlessMapRenderer.Result.Failure ->
                    android.util.Log.w("AccountViewModel", "Return-home pre-render failed: ${result.message}")
            }
        }
    }

    private val _uiState = MutableStateFlow(AccountUiState())
    val uiState: StateFlow<AccountUiState> = _uiState.asStateFlow()

    /** Outcome of the most recent [returnHome]/[changeHomeBase] attempt, or null for "idle". Every
     *  exit path of both actions writes here - including the cooldown re-check bails, which used to
     *  `return@launch` silently while the screen had already started celebrating. Read once by the
     *  Account screen and then cleared via [consumeHomeBaseActionResult], so a celebration can't
     *  replay on an unrelated recomposition or a config change, the same one-shot convention
     *  ChallengesViewModel's `startResult` uses. */
    private val _homeBaseActionResult = MutableStateFlow<HomeBaseActionResult?>(null)
    val homeBaseActionResult: StateFlow<HomeBaseActionResult?> = _homeBaseActionResult.asStateFlow()

    /** Marks the current [homeBaseActionResult] as handled. Called by the screen once it has shown
     *  (or deliberately not shown) the matching celebration. */
    fun consumeHomeBaseActionResult() {
        _homeBaseActionResult.value = null
    }

    // ── Change-home-base airport picker (docs/modes.md) ─────────────────────
    // Shares AirportSearchController with OnboardingViewModel's home-airport search and
    // FlightSearchViewModel's Free Mode origin picker - all three search any airport by free
    // text, reusing the shared ui/components/airportpicker two-step "search -> confirm on map"
    // components as the UI rather than building a new picker from scratch.
    private val homeBaseSearch = AirportSearchController(airportRepository, viewModelScope)
    val homeBaseSearchQuery: StateFlow<String> = homeBaseSearch.query
    val homeBaseSearchResults: StateFlow<List<Airport>> = homeBaseSearch.results

    // Same FRA/LHR/BER/MUC tiles Onboarding and Free Flight's origin picker show before a search
    // is typed, so Change Home Base's picker looks identical to theirs.
    private val _homeBaseSuggestions = MutableStateFlow<List<Airport>>(emptyList())
    val homeBaseSuggestions: StateFlow<List<Airport>> = _homeBaseSuggestions.asStateFlow()

    fun onHomeBaseSearchQueryChanged(query: String) {
        homeBaseSearch.onQueryChanged(query)
    }

    private fun clearHomeBaseSearch() {
        homeBaseSearch.onQueryChanged("")
        homeBaseSearch.clearResults()
    }

    private val dateFormat = SimpleDateFormat("MMM yyyy", Locale.US)

    // Persisted (docs/state.md), so reopening the Passport keeps the pilot's last choice instead
    // of snapping back to Newest First. Seeded into uiState in init.
    private val _sortOrder = MutableStateFlow(preferencesRepository.getLogbookSortOrder())
    val sortOrder: StateFlow<FlightSortOrder> = _sortOrder.asStateFlow()

    // Seeded from ThemeModeHolder (itself seeded from PreferencesRepository in
    // CesiumGameActivity.onCreate) rather than reading preferencesRepository directly here, so
    // this always reflects what BlocktimeTheme is actually rendering right now.
    private val _themeMode = MutableStateFlow(com.silas270.blocktime.ui.theme.ThemeModeHolder.current)
    val themeMode: StateFlow<com.silas270.blocktime.data.model.ThemeMode> = _themeMode.asStateFlow()

    /** Toggling is always an explicit LIGHT/DARK choice - never writes SYSTEM back, since that is
     *  only the pre-toggle default (see [com.silas270.blocktime.data.model.ThemeMode]). */
    fun setThemeMode(mode: com.silas270.blocktime.data.model.ThemeMode) {
        _themeMode.value = mode
        com.silas270.blocktime.ui.theme.ThemeModeHolder.current = mode
        preferencesRepository.setThemeMode(mode)
    }

    /** Current offline state, for the Settings "Offline maps" row's subtitle. */
    val networkMode: StateFlow<NetworkMode> = offlineModeController.mode
    val offlineDataSaverEnabled: StateFlow<Boolean> = offlineModeController.dataSaverEnabled

    fun setOfflineDataSaverEnabled(enabled: Boolean) {
        offlineModeController.setDataSaverEnabled(enabled)
    }

    /** The "Shared challenges" switch and the server state its subtitle reads, both owned by
     *  [ServerReachability] so the row and the sharing surfaces can never disagree. */
    val serverState: StateFlow<ServerState> = serverReachability.state
    val onlineFeaturesEnabled: StateFlow<Boolean> = serverReachability.optIn

    fun setOnlineFeaturesEnabled(enabled: Boolean) {
        serverReachability.setOnlineFeaturesEnabled(enabled)
    }

    // The logbook used to render from a Pager here while its headers were computed from
    // uiState.flightHistory, with nothing keeping the two indexes aligned. The whole history is
    // already loaded eagerly for the map, stats, highlights and achievements, so the Pager was a
    // second query plus a cachedIn copy of data the app was holding anyway - the screen now sorts
    // that one list and lets LazyColumn compose only what is on screen.
    init {
        _uiState.update { it.copy(sortOrder = _sortOrder.value) }
        loadData()
        refreshHomeBaseCooldowns()
        viewModelScope.launch(Dispatchers.IO) {
            _homeBaseSuggestions.value = resolveSuggestedAirports(airportRepository)
        }
        // Off the UI thread, well before the user can scroll a fast fling down to the
        // logbook (see PaperGrainTexture's doc comment).
        viewModelScope.launch(Dispatchers.Default) {
            com.silas270.blocktime.ui.screens.account.PaperGrainTexture.warm()
        }
    }

    /**
     * Recomputes both home-base cooldowns' eligibility/remaining-time against "now". Called once
     * at load and again right after [returnHome]/[changeHomeBase] so the UI reflects the new
     * cooldown immediately post-action. Deliberately *not* re-evaluated on a live ticking timer -
     * nothing in docs/modes.md calls for a second-by-second countdown, and the Account
     * screen is realistically reopened (recreating this ViewModel) long before a multi-day
     * cooldown display would visibly go stale.
     */
    private fun refreshHomeBaseCooldowns() {
        viewModelScope.launch(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            val lastReturnHome = preferencesRepository.getLastReturnHomeAt()
            val lastHomeBaseChanged = preferencesRepository.getLastHomeBaseChangedAt()
            // Resolved before the update rather than inside it. `MutableStateFlow.update` is a
            // compare-and-set loop that may run its lambda more than once under contention, and
            // its contract is that the lambda is pure - resolving the current airport there would
            // put a suspending database read on a path that can legally be retried. It compiles
            // (update is inline) which is exactly what makes it easy to get wrong.
            val currentIata =
                com.silas270.blocktime.domain.resolveCurrentAirportIata(preferencesRepository, userRepository)
                    .orEmpty()
            val homeIata = com.silas270.blocktime.domain.resolveHomeAirportIata(userRepository)
            val discardedDest = pausedStoryFlightStrandedByReturnHome(homeIata)?.destIata
            _uiState.update { state ->
                state.copy(
                    currentAirportIata = currentIata,
                    returnHomeDiscardsFlightTo = discardedDest,
                    returnHomeEligible = HomeBaseCooldown.isEligible(now, lastReturnHome, HomeBaseCooldown.RETURN_HOME_COOLDOWN_DAYS),
                    returnHomeRemainingMillis = HomeBaseCooldown.remainingMillis(now, lastReturnHome, HomeBaseCooldown.RETURN_HOME_COOLDOWN_DAYS),
                    changeHomeBaseEligible = HomeBaseCooldown.isEligible(now, lastHomeBaseChanged, HomeBaseCooldown.CHANGE_HOME_BASE_COOLDOWN_DAYS),
                    changeHomeBaseRemainingMillis = HomeBaseCooldown.remainingMillis(now, lastHomeBaseChanged, HomeBaseCooldown.CHANGE_HOME_BASE_COOLDOWN_DAYS)
                )
            }
        }
    }

    /**
     * Return-home teleport (docs/modes.md) - a direct state mutation, deliberately
     * NOT routed through Screen.FlightSearch/CheckIn/InFlight: no booking flow, no timer/session,
     * no FlightLog row, no FlightMode tag. Just an instant cut of `currentAirport` to the home
     * base, gated by the 7-day cooldown checked again here (not just at the UI-disabled-state
     * layer) so a stale/racy UI state can't bypass it.
     *
     * Because of that re-check this can refuse work the screen already asked for, so every exit
     * path reports through [homeBaseActionResult] - the "WELCOME BACK" celebration is driven off
     * that result rather than off the tap that started the teleport animation.
     */
    fun returnHome() {
        viewModelScope.launch(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            val lastReturnHome = preferencesRepository.getLastReturnHomeAt()
            if (!HomeBaseCooldown.isEligible(now, lastReturnHome, HomeBaseCooldown.RETURN_HOME_COOLDOWN_DAYS)) {
                _homeBaseActionResult.value = HomeBaseActionResult.Ineligible
                return@launch
            }
            // No home base to teleport to at all - not a cooldown refusal but a broken profile
            // (onboarding always writes one), so it reports as a failure rather than as Ineligible.
            val homeIata = com.silas270.blocktime.domain.resolveHomeAirportIata(userRepository)
            if (homeIata == null) {
                _homeBaseActionResult.value = HomeBaseActionResult.Failed
                return@launch
            }

            // A paused Story flight departs from where the pilot *was*. Leaving it resumable after
            // the teleport would let the Hub offer "RESUME FLIGHT" from the old airport, and
            // landing it would break the origin lock. The confirm modal says so beforehand
            // (ReturnHomeConfirmModal). Cleared before the move, so a failure between the two
            // leaves the pilot where they were with nothing to resume rather than the reverse.
            if (pausedStoryFlightStrandedByReturnHome(homeIata) != null) {
                preferencesRepository.pausedStoryFlightStore.clear()
            }
            preferencesRepository.setCurrentAirport(homeIata)
            preferencesRepository.setLastReturnHomeAt(now)
            refreshHomeBaseCooldowns()
            _homeBaseActionResult.value = HomeBaseActionResult.ReturnedHome
        }
    }

    /** The paused Story flight a return home would strand - one departing from anywhere but
     *  [homeIata] - or null. A flight paused *at* home stays valid after the teleport. */
    private suspend fun pausedStoryFlightStrandedByReturnHome(homeIata: String?): com.silas270.blocktime.data.model.PausedFlight? {
        if (homeIata == null) return null
        return preferencesRepository.pausedStoryFlightStore.get()?.takeIf { it.originIata != homeIata }
    }

    /**
     * Change-home-base (docs/modes.md), gated by its own separate 30-day cooldown -
     * never the same clock as [returnHome]'s 7-day one. Writes the home airport to exactly one
     * store - the Room `UserProfile` row - which every reader now resolves through
     * [com.silas270.blocktime.domain.resolveHomeAirportIata]. It used to write
     * `SharedPreferences` first and Room second, which is how the two could end up disagreeing.
     *
     * The cooldown timestamp still lives in prefs, and is now written only *after* the Room write
     * succeeds: burning the 30-day cooldown for a change that did not happen would be the worst
     * of both outcomes.
     *
     * Unlike [returnHome], this leaves a paused Story flight alone: it moves home, not the pilot,
     * so a flight paused at the current airport still departs from where the pilot is.
     *
     * Like [returnHome], the re-checked cooldown means this can refuse work the picker already
     * asked for, so every exit path reports through [homeBaseActionResult] and the "HOME BASE SET"
     * celebration is driven off that result instead of off the airport tap.
     */
    fun changeHomeBase(airport: Airport) {
        viewModelScope.launch(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            val lastChanged = preferencesRepository.getLastHomeBaseChangedAt()
            if (!HomeBaseCooldown.isEligible(now, lastChanged, HomeBaseCooldown.CHANGE_HOME_BASE_COOLDOWN_DAYS)) {
                _homeBaseActionResult.value = HomeBaseActionResult.Ineligible
                return@launch
            }

            // The one write that defines the new home base. Nothing else is touched until it has
            // actually landed, so a failure here leaves the pilot exactly as they were rather than
            // half-changed.
            val roomWriteFailed = try {
                userRepository.updateHomeAirport(airport.iataCode)
                false
            } catch (e: Exception) {
                android.util.Log.e("AccountViewModel", "Failed to update home airport", e)
                true
            }
            if (!roomWriteFailed) {
                preferencesRepository.setLastHomeBaseChangedAt(now)
            }

            clearHomeBaseSearch()
            refreshHomeBaseCooldowns()
            _homeBaseActionResult.value = if (roomWriteFailed) {
                HomeBaseActionResult.Failed
            } else {
                HomeBaseActionResult.HomeBaseSet(airport)
            }
        }
    }

    private fun loadData() {
        // Collect profile data
        viewModelScope.launch(Dispatchers.IO) {
            userRepository.getProfileFlow().collect { profile ->
                if (profile != null) {
                    val formattedDate = "Joined " + dateFormat.format(Date(profile.createdAt))
                    // Already on Dispatchers.IO, and getAirportByIata is a synchronous in-memory
                    // lookup - resolving it here keeps the celebration screens free of any loading
                    // state of their own.
                    val homeAirport = profile.homeAirportIata
                        .takeIf { it.isNotBlank() }
                        ?.let { airportRepository.getAirportByIata(it) }
                    _uiState.update { state ->
                        state.copy(
                            username = profile.username,
                            userCode = profile.userCode.removePrefix("#").removePrefix("@"),
                            homeAirportIata = profile.homeAirportIata,
                            homeAirport = homeAirport,
                            joinDateFormatted = formattedDate
                        )
                    }
                }
            }
        }

        // Everything derived from the flight log now arrives pre-computed and already warm from
        // PilotProgressRepository, which derives it once for the whole app instead of each screen
        // rebuilding its own copy on every visit. This block used to BE that derivation - a full
        // history read, a visited-geography scan over the airports DB, an achievement evaluation
        // and seven aggregate queries, all re-run from scratch every time the Passport opened,
        // because this ViewModel is destroyed on popBackStack. That is what made the screen slow.
        //
        // Failure handling moved with it: the shared flow simply does not emit on a failed
        // derivation, so the last good snapshot stays on screen rather than the Passport blanking
        // out or spinning forever.
        viewModelScope.launch(Dispatchers.IO) {
            // Still resolved here, not in the shared snapshot: the SVG paths are a rendering
            // concern with no dependency on the pilot's data, and they are already warm from
            // WorldMapParser.warm() at app start.
            val mapPaths = com.silas270.blocktime.ui.map.WorldMapParser.parseWorldMap(context)

            pilotProgressRepository.progress.collect { progress ->
                if (progress == null) {
                    _uiState.update { it.copy(isLoading = false) }
                    return@collect
                }

                // Only the earned ones reach the Passport, collapsed into ladder stacks and sorted
                // by difficulty (gold first, then silver, then bronze), newest-first within each
                // tier. The whole board goes in - the fold needs the unearned entries to work out
                // each family's next tier - and it drops anything with nothing earned.
                val achievementStacks = buildAchievementStacks(progress.achievements)

                _uiState.update { state ->
                    state.copy(
                        stats = progress.stats,
                        flightHistory = progress.history,
                        tours = progress.tours,
                        allVisitedCountries = progress.geography.visitedCountries,
                        completedContinents = progress.geography.completedContinents,
                        countryToContinent = progress.geography.countryToContinent,
                        mapPaths = mapPaths,
                        highlights = progress.highlights,
                        achievementStacks = achievementStacks,
                        isLoading = false
                    )
                }
            }
        }
    }

    fun setSortOrder(order: FlightSortOrder) {
        _sortOrder.value = order
        preferencesRepository.setLogbookSortOrder(order)
        _uiState.update { it.copy(sortOrder = order) }
    }

    fun updateUsername(newName: String) {
        if (newName.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                userRepository.updateUsername(newName.trim())
            } catch (e: Exception) {
                android.util.Log.e("AccountViewModel", "Failed to update username", e)
            }
        }
    }
}

class AccountViewModelFactory(
    private val context: android.content.Context,
    private val userRepository: UserRepository,
    private val flightLogRepository: FlightLogRepository,
    private val airportRepository: AirportRepository,
    private val preferencesRepository: PreferencesRepository,
    private val pilotProgressRepository: PilotProgressRepository,
    private val offlineModeController: OfflineModeController,
    private val serverReachability: ServerReachability,
    private val cacheDir: java.io.File
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(AccountViewModel::class.java)) {
            return AccountViewModel(context, userRepository, flightLogRepository, airportRepository, preferencesRepository, pilotProgressRepository, offlineModeController, serverReachability, cacheDir) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
