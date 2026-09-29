package com.silas270.blocktime.ui.viewmodel.challenges

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.silas270.blocktime.data.model.AchievementStatus
import com.silas270.blocktime.data.model.Airport
import com.silas270.blocktime.data.model.Challenge
import com.silas270.blocktime.data.model.ChallengeStatus
import com.silas270.blocktime.data.model.ChallengeType
import com.silas270.blocktime.data.model.PausedFlight
import com.silas270.blocktime.data.model.RoomState
import com.silas270.blocktime.data.local.airport.AirportDataException
import com.silas270.blocktime.data.network.ServerReachability
import com.silas270.blocktime.data.network.ServerState
import com.silas270.blocktime.data.network.room.RoomResult
import com.silas270.blocktime.data.repository.PilotProgressRepository
import com.silas270.blocktime.data.repository.AirportRepository
import com.silas270.blocktime.data.repository.ChallengeRepository
import com.silas270.blocktime.data.repository.JoinResult
import com.silas270.blocktime.data.repository.PreferencesRepository
import com.silas270.blocktime.data.repository.ShareIneligibility
import com.silas270.blocktime.data.repository.ShareResult
import com.silas270.blocktime.data.repository.SharedChallengeSyncer
import com.silas270.blocktime.data.repository.StartChallengeResult
import com.silas270.blocktime.data.repository.SyncReason
import com.silas270.blocktime.data.repository.SyncSummary
import com.silas270.blocktime.data.repository.asJoinFailure
import com.silas270.blocktime.domain.AirportSearchController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The picker's "Have a code?" flow (docs/shared-challenges.md "Joining"). */
sealed interface RoomLookupState {
    data object Idle : RoomLookupState
    data object Loading : RoomLookupState

    /** The preview: name, type, target, crew and warnings come from the room itself. */
    data class Found(val room: RoomState) : RoomLookupState

    /** Why the code cannot be joined, in the join's own vocabulary (J3, J6b, J7, J8, J10, J11, J12). */
    data class Error(val reason: JoinResult) : RoomLookupState

    /** The pilot already runs this room in slot [challengeId]: the screen closes the picker and
     *  opens that slot's info modal instead of a preview (J6a). */
    data class AlreadyJoined(val challengeId: Int) : RoomLookupState
}

/** The info modal's "SHARE CHALLENGE" flow (docs/shared-challenges.md "Sharing"). */
sealed interface ShareUiState {
    data object Idle : ShareUiState
    data object Working : ShareUiState
    data class Shared(val code: String) : ShareUiState
    data class Error(val message: String) : ShareUiState
}

/**
 * Backs the Challenges screen (docs/challenges.md#entry--management-surface): the three
 * active-challenge slots, the completed-challenges log beneath them, the Achievements tab's
 * still-unearned list, and the start/abandon/custom-create flows. One instance is created per
 * composition of that screen - cheap, since it holds no flight/engine state.
 *
 * Opening the screen asks the syncer for a `SCREEN_OPEN` sync (debounced), so a foreign
 * completion is presented on this visit rather than the next (docs/shared-challenges.md, Y2).
 */
class ChallengesViewModel(
    private val challengeRepository: ChallengeRepository,
    private val airportRepository: AirportRepository,
    private val pilotProgressRepository: PilotProgressRepository,
    private val preferencesRepository: PreferencesRepository,
    private val sharedChallengeSyncer: SharedChallengeSyncer,
    serverReachability: ServerReachability
) : ViewModel() {

    /** What the sharing surfaces ask before offering an online action (docs/shared-challenges.md
     *  "Two signals"); `onlineAvailability(state)` turns it into hidden, dimmed or enabled. */
    val serverState: StateFlow<ServerState> = serverReachability.state

    /** What the last sync did, for the info modal's "Synced 3 min ago" line under the crew. */
    val lastSyncSummary: StateFlow<SyncSummary?> = sharedChallengeSyncer.lastSummary

    /** What the three slots actually render: ACTIVE challenges, plus any COMPLETED-but-not-yet-
     *  celebrated one (docs/challenges.md) - so a just-finished challenge keeps its slot until its
     *  completion-presentation animation has shown it. `WhileSubscribed` rather than `Eagerly`
     *  since this is only ever collected while the Challenges screen is actually open. */
    val slotChallenges: StateFlow<List<Challenge>> =
        challengeRepository.listSlotDisplayChallengesFlow()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Completed challenges, newest-first (the DAO orders by `completed_at DESC`) - the logbook-
     *  style list below the slots. Refreshed off [slotChallenges] rather than its own Flow:
     *  a challenge can only ever leave [slotChallenges] by being celebrated, so that emission is
     *  an exact signal, not an approximation. Only *celebrated* completions ever appear here -
     *  see [ChallengeRepository.listCompletedChallenges]. */
    private val _completedChallenges = MutableStateFlow<List<Challenge>>(emptyList())
    val completedChallenges: StateFlow<List<Challenge>> = _completedChallenges.asStateFlow()

    /**
     * Which terminal-but-unpresented challenges to run the presentation for this session, in
     * the order they were noticed - see docs/challenges.md and docs/shared-challenges.md
     * "Presentation". The queue holds **ids**, fed live from [slotChallenges] through
     * [nextQueue]: every emission appends the unpresented terminal rows it has not seen before,
     * and [seenIds] keeps a row that is mid-animation from being queued twice by a later,
     * unrelated emission (another celebration finishing, a sync refreshing a cache). Live rather
     * than computed once so a completion that a sync brings in while the screen is open is
     * presented on this visit.
     *
     * [celebrationQueue] resolves the ids against the latest emission at read time, so a sync
     * that corrects a row's outcome after it was queued (a refused claim turning a win into a
     * placement) is presented as corrected. The database is still the source: the queue is
     * rebuilt from `status IN (COMPLETED, FAILED) AND celebrated = 0` whenever this ViewModel is
     * created, so the app being killed mid-celebration loses nothing.
     */
    private val queuedIds = MutableStateFlow<List<Int>>(emptyList())
    private var seenIds: Set<Int> = emptySet()
    val celebrationQueue: StateFlow<List<Challenge>> =
        combine(queuedIds, slotChallenges) { ids, rows -> ids.mapNotNull { id -> rows.firstOrNull { it.id == id } } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Still-unearned achievements, ordered closest-to-done first (the screen groups them under
     *  category headers) so the next reachable goal is always on top. Earned ones are
     *  deliberately absent - they live on the Passport as badges. */
    private val _unfinishedAchievements = MutableStateFlow<List<AchievementStatus>>(emptyList())
    val unfinishedAchievements: StateFlow<List<AchievementStatus>> = _unfinishedAchievements.asStateFlow()

    /** Free Mode's own paused-flight slot (fully separate from Story Mode's, see
     *  PreferencesRepository.pausedFreeFlightStore) - lets the Free Mode row offer "RESUME"
     *  instead of always dropping into a fresh booking. Loaded once at construction (this VM is
     *  recreated per screen visit, same as [focusedChallengeId] below).
     *
     *  Declared *above* the init block on purpose. Kotlin runs property initialisers and init
     *  blocks strictly in declaration order, and init's loader below writes this flow from a
     *  `viewModelScope.launch { }` with no dispatcher - which is `Dispatchers.Main.immediate`, so
     *  on the main thread the body runs synchronously, inline, before the constructor has moved
     *  on. With this property declared further down the file it was still null at that point and
     *  opening the Challenges screen died on a NullPointerException every time. */
    private val _pausedFreeFlight = MutableStateFlow<PausedFlight?>(null)
    val pausedFreeFlight: StateFlow<PausedFlight?> = _pausedFreeFlight.asStateFlow()

    init {
        viewModelScope.launch(Dispatchers.IO) {
            // The completed-challenge log still keys off the slot-display set - a challenge can
            // only reach the log by leaving that one (being celebrated), so the emission is an
            // exact signal.
            slotChallenges.collect { _completedChallenges.value = challengeRepository.listCompletedChallenges() }
        }
        viewModelScope.launch {
            // On the main dispatcher, like celebrate() and dismissFailed(), so the queue and the
            // seen set have one writer thread. See celebrationQueue's own doc.
            slotChallenges.collect { rows ->
                val (queue, seen) = nextQueue(queuedIds.value, seenIds, rows)
                seenIds = seen
                queuedIds.value = queue
            }
        }
        sharedChallengeSyncer.requestSync(SyncReason.SCREEN_OPEN)
        viewModelScope.launch {
            // The achievement board is no longer re-derived here. It used to call loadBoard() on
            // every activeChallenges emission - re-reading the entire flight history and
            // re-scanning the airports DB - which duplicated, exactly, the work the Passport was
            // doing separately. Both now read one shared derivation that is already warm.
            pilotProgressRepository.progress.collect { progress ->
                val board = progress?.achievements ?: return@collect
                _unfinishedAchievements.value = (board.geographic + board.distance + board.behavioral)
                    .filterNot { it.isUnlocked }
                    .sortedByDescending { it.progress }
            }
        }
        viewModelScope.launch {
            _pausedFreeFlight.value = preferencesRepository.pausedFreeFlightStore.get()
        }
    }

    /** Result of the most recent start attempt (curated or custom) - surfaced once (e.g. a
     *  [StartChallengeResult.CapReached] message) then cleared via [clearStartResult] so it
     *  doesn't reappear on an unrelated recomposition. Null means "nothing to show". */
    private val _startResult = MutableStateFlow<StartChallengeResult?>(null)
    val startResult: StateFlow<StartChallengeResult?> = _startResult.asStateFlow()

    /** The Route challenge currently focused on the Hub, if any - lets the info modal offer
     *  "PAUSE" instead of "CONTINUE" for that one. Seeded from the pref and kept in sync by every
     *  method here that changes it, since this screen can stay open across a pause/focus change
     *  (unlike Hub, which re-reads the pref fresh on every [PreferencesRepository] read). */
    private val _focusedChallengeId = MutableStateFlow(preferencesRepository.getFocusedRouteChallengeId())
    val focusedChallengeId: StateFlow<Int?> = _focusedChallengeId.asStateFlow()

    // ── Custom Route creation: origin/destination airport search ────────────────────────
    // Shares AirportSearchController with FlightSearchViewModel's Free-Mode origin picker -
    // one instance per endpoint, since a custom Route challenge needs both ends picked rather
    // than one fixed + one browsed.
    private val originSearch = AirportSearchController(airportRepository, viewModelScope)
    val originQuery: StateFlow<String> = originSearch.query
    val originResults: StateFlow<List<Airport>> = originSearch.results

    private val destSearch = AirportSearchController(airportRepository, viewModelScope)
    val destQuery: StateFlow<String> = destSearch.query
    val destResults: StateFlow<List<Airport>> = destSearch.results

    fun onOriginQueryChanged(query: String) { originSearch.onQueryChanged(query) }
    fun onDestQueryChanged(query: String) { destSearch.onQueryChanged(query) }

    fun clearRouteSearch() {
        originSearch.onQueryChanged("")
        originSearch.clearResults()
        destSearch.onQueryChanged("")
        destSearch.clearResults()
    }

    fun startCurated(catalogId: String) {
        viewModelScope.launch {
            val result = challengeRepository.startCuratedChallenge(catalogId)
            _startResult.value = result
            focusIfRoute(result)
        }
    }

    /** [origin]/[dest] named from city names, per challenges.md's "Where it comes from" for Route
     *  ("Stuttgart → Beijing," not "STR → PEK"). Some airports have no city in the data, so the
     *  airport name (or, failing that, the code) stands in rather than leaving " → ". */
    fun startCustomRoute(origin: Airport, dest: Airport) {
        fun placeName(a: Airport) = a.municipality.ifBlank { a.name.ifBlank { a.iataCode } }
        val name = "${placeName(origin)} → ${placeName(dest)}"
        viewModelScope.launch {
            val result = challengeRepository.startCustomRouteChallenge(origin.iataCode, dest.iataCode, name)
            _startResult.value = result
            clearRouteSearch()
            focusIfRoute(result)
        }
    }

    private fun focusIfRoute(result: StartChallengeResult) {
        if (result is StartChallengeResult.Started && result.challenge.type == ChallengeType.ROUTE) {
            preferencesRepository.setFocusedRouteChallengeId(result.challenge.id)
            _focusedChallengeId.value = result.challenge.id
        }
    }

    /** Focuses an existing Route challenge on the Hub - called right before navigating into the
     *  scoped flight-search session for "continue"/"resume", so the Hub already reflects it on
     *  return. */
    fun focusRouteChallenge(id: Int) {
        preferencesRepository.setFocusedRouteChallengeId(id)
        _focusedChallengeId.value = id
    }

    /** Pauses the currently-focused challenge - the Hub reverts to the story-mode airport, but
     *  the challenge itself (including any paused flight on it) is untouched. Same semantics as
     *  `HubViewModel.exitFocusedChallenge()`, offered here too since the info modal's "PAUSE
     *  CHALLENGE" action (shown only for the currently-focused one) is reachable from this
     *  screen without going via the Hub. */
    fun pauseFocusedChallenge() {
        preferencesRepository.clearFocusedRouteChallengeId()
        _focusedChallengeId.value = null
    }

    fun startCustomDistance(targetKm: Double) {
        val name = "Custom Distance - ${formatKm(targetKm)}"
        viewModelScope.launch {
            _startResult.value = challengeRepository.startCustomDistanceChallenge(targetKm, name)
        }
    }

    fun startCustomStreak(targetDays: Int) {
        val name = "$targetDays-Day Streak"
        viewModelScope.launch {
            _startResult.value = challengeRepository.startCustomStreakChallenge(targetDays, name)
        }
    }

    fun abandon(id: Int) {
        viewModelScope.launch {
            challengeRepository.abandonChallenge(id)
            // A shared row queued its room leave; send it now rather than at the next foreground
            // (docs/shared-challenges.md, A2). Harmless for a solo row: nothing is pending.
            sharedChallengeSyncer.requestSync(SyncReason.USER_ACTION)
        }
    }

    /** Called by the completion-presentation overlay the instant a challenge's fly-out animation
     *  finishes - persists the flag (moving it into the log/off the slot) and advances the local
     *  queue so the overlay moves on to the next one. Persist first, then dequeue: the other
     *  order would let a slot emission re-queue the row between the two. */
    fun celebrate(id: Int) {
        viewModelScope.launch {
            challengeRepository.markCelebrated(id)
            queuedIds.update { it - id }
        }
    }

    /** Called by the failure overlay once its shatter has played: deletes the row (the log stays
     *  a log of successes, docs/shared-challenges.md "Presentation") and advances the queue, in
     *  the same order as [celebrate] for the same reason. */
    fun dismissFailed(id: Int) {
        viewModelScope.launch {
            challengeRepository.dismissFailed(id)
            queuedIds.update { it - id }
        }
    }

    fun clearStartResult() {
        _startResult.value = null
    }

    // ── Shared challenges (docs/shared-challenges.md) ───────────────────────────────────────

    private val _roomLookup = MutableStateFlow<RoomLookupState>(RoomLookupState.Idle)
    val roomLookup: StateFlow<RoomLookupState> = _roomLookup.asStateFlow()

    private val _shareState = MutableStateFlow<ShareUiState>(ShareUiState.Idle)
    val shareState: StateFlow<ShareUiState> = _shareState.asStateFlow()

    /**
     * The picker's LOOK UP: fetches the room for the preview (J3, J4, J11). A code the pilot
     * already has a row for is answered locally first, as [joinRoom] answers it, because the
     * server has nothing to add: an active row opens its slot (J6a), a finished one is final
     * (J6b).
     */
    fun lookUpRoom(code: String) {
        _roomLookup.value = RoomLookupState.Loading
        viewModelScope.launch {
            val own = challengeRepository.findByRoomCode(code)
            _roomLookup.value = when {
                own != null && own.status == ChallengeStatus.ACTIVE -> RoomLookupState.AlreadyJoined(own.id)
                own != null -> RoomLookupState.Error(JoinResult.AlreadyFinished)
                else -> when (val result = challengeRepository.lookUpRoom(code)) {
                    is RoomResult.Ok -> RoomLookupState.Found(result.value)
                    else -> RoomLookupState.Error(result.asJoinFailure())
                }
            }
        }
    }

    /**
     * The preview's JOIN. A join is published as the existing [StartChallengeResult.Started], so
     * the screen closes the picker, focuses a route and returns to the Hub exactly as after
     * starting a challenge (J4); a full cap reuses the existing "CHALLENGE SLOTS FULL" modal
     * (J5); a row the pilot already runs opens its slot, as the look-up does (J6a). Everything
     * else stays on the preview with its reason.
     */
    fun joinRoom(code: String) {
        _roomLookup.value = RoomLookupState.Loading
        viewModelScope.launch {
            when (val result = challengeRepository.joinRoom(code)) {
                is JoinResult.Joined -> {
                    _roomLookup.value = RoomLookupState.Idle
                    val started = StartChallengeResult.Started(result.challenge)
                    _startResult.value = started
                    focusIfRoute(started)
                    requestSyncAfterUserAction()
                }
                JoinResult.CapReached -> {
                    _roomLookup.value = RoomLookupState.Idle
                    _startResult.value = StartChallengeResult.CapReached
                }
                // A row for this code appeared since the look-up: open it, as the look-up would (J6a).
                is JoinResult.AlreadyJoined -> _roomLookup.value = RoomLookupState.AlreadyJoined(result.activeId)
                else -> _roomLookup.value = RoomLookupState.Error(result)
            }
        }
    }

    fun clearRoomLookup() {
        _roomLookup.value = RoomLookupState.Idle
    }

    /** The info modal's SHARE CHALLENGE (S3 to S8). The modal stays open and switches to the
     *  code once [ShareUiState.Shared] arrives; the row itself updates through [slotChallenges]. */
    fun shareChallenge(id: Int) {
        _shareState.value = ShareUiState.Working
        viewModelScope.launch {
            _shareState.value = when (val result = challengeRepository.shareChallenge(id)) {
                is ShareResult.Shared -> {
                    requestSyncAfterUserAction()
                    ShareUiState.Shared(result.code)
                }
                is ShareResult.NotEligible -> ShareUiState.Error(
                    when (result.reason) {
                        ShareIneligibility.NOT_ACTIVE -> "This challenge is already over"
                        ShareIneligibility.ALREADY_SHARED -> "This challenge is already shared"
                        ShareIneligibility.HAS_PROGRESS, ShareIneligibility.HAS_PAUSED_LEG -> "Only a fresh challenge can be shared"
                        ShareIneligibility.UNKNOWN -> "This challenge no longer exists"
                    }
                )
                is ShareResult.Unavailable -> ShareUiState.Error("Server not reachable, try again")
            }
        }
    }

    fun clearShareState() {
        _shareState.value = ShareUiState.Idle
    }

    /**
     * A successful share or join asks for a `USER_ACTION` sync (docs/shared-challenges.md "Sync
     * moments"). The server has just answered, so the crew caption must read "Synced just now";
     * without a sync it kept the time of the last one, often the screen-open sync minutes
     * earlier. The request is never debounced and returns at once.
     */
    private fun requestSyncAfterUserAction() {
        sharedChallengeSyncer.requestSync(SyncReason.USER_ACTION)
    }
}

/**
 * Formats a distance *stored in km* for display - in miles, like every distance in the UI (see
 * util/Units.kt). Kept under its old name because the Challenges screens call it throughout;
 * the argument is still kilometres, only the output unit changed.
 */
fun formatKm(km: Double): String = com.silas270.blocktime.util.formatMiles(km)

class ChallengesViewModelFactory(
    private val challengeRepository: ChallengeRepository,
    private val airportRepository: AirportRepository,
    private val pilotProgressRepository: PilotProgressRepository,
    private val preferencesRepository: PreferencesRepository,
    private val sharedChallengeSyncer: SharedChallengeSyncer,
    private val serverReachability: ServerReachability
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(ChallengesViewModel::class.java)) {
            return ChallengesViewModel(challengeRepository, airportRepository, pilotProgressRepository, preferencesRepository, sharedChallengeSyncer, serverReachability) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
