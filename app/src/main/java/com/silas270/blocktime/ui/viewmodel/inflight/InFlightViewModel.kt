package com.silas270.blocktime.ui.viewmodel.inflight

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.silas270.blocktime.data.model.Airport
import com.silas270.blocktime.data.model.CameraPose
import com.silas270.blocktime.data.model.Challenge
import com.silas270.blocktime.data.model.FlightLog
import com.silas270.blocktime.data.model.FlightMode
import com.silas270.blocktime.data.model.FlightRoute
import com.silas270.blocktime.data.network.NetworkMode
import com.silas270.blocktime.data.network.OfflineModeController
import com.silas270.blocktime.data.repository.AirportRepository
import com.silas270.blocktime.data.repository.ChallengeRepository
import com.silas270.blocktime.data.repository.DestinationPhotoChannel
import com.silas270.blocktime.data.repository.DestinationPhotoRepository
import com.silas270.blocktime.data.repository.FlightLogRepository
import com.silas270.blocktime.data.repository.LandingResult
import com.silas270.blocktime.data.repository.LandingResultChannel
import com.silas270.blocktime.data.repository.PausedFlightStore
import com.silas270.blocktime.data.repository.PreferencesRepository
import com.silas270.blocktime.data.repository.SessionPausedFlightStore
import com.silas270.blocktime.data.repository.SharedChallengeSyncer
import com.silas270.blocktime.data.repository.SyncReason
import com.silas270.blocktime.data.repository.processLandingForChallenges
import com.silas270.blocktime.data.repository.resolveLandingOutcome
import com.silas270.blocktime.domain.EnginePowerModel
import com.silas270.blocktime.domain.NetworkNotice
import com.silas270.blocktime.domain.networkNoticeFor
import com.silas270.blocktime.domain.resolveEffectiveMapStyle
import com.silas270.blocktime.domain.loadRouteContext
import com.silas270.blocktime.engine.headless.CesiumHeadlessMapRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

data class InFlightState(
    val timeRemainingSeconds: Long = 0,
    val timeElapsedSeconds: Long = 0,
    val timeElapsedMs: Long = 0,
    val totalDurationSeconds: Long = 0,
    val isRunning: Boolean = true,
    val isCompleted: Boolean = false,
    val speedKmh: Int = 840,
    val altitudeMeters: Int = 10600,
    val currentLat: Double = 0.0,
    val currentLon: Double = 0.0,
    val progress: Float = 0.0f,
    /** Camera mode (0=Free/1=Tracking/2=Cockpit) restored from a saved flight, if any — lets
     *  the camera-view picker UI reflect it instead of defaulting to Chase. Null for a
     *  brand-new flight, which keeps resetting to the default as before. */
    val restoredCameraMode: Int? = null
)

class InFlightViewModel(
    private val airportRepository: AirportRepository,
    private val preferencesRepository: PreferencesRepository,
    private val flightLogRepository: FlightLogRepository,
    private val challengeRepository: ChallengeRepository,
    /** Asked for a `LANDING` sync once the landing has been credited and published; it runs on
     *  the syncer's own scope, never on [landingScope] (docs/shared-challenges.md "Landing"). */
    private val sharedChallengeSyncer: SharedChallengeSyncer,
    /** Phase 3b's landing-result channel (see docs/core-loop.md's post-landing pipeline
     *  step 5) - published into at the end of [checkAchievementsAndChallenges], read by
     *  `CesiumGameActivity`'s `Screen.ArrivalCelebration` `onContinue` once this ViewModel (and
     *  its nav entry) may already be gone. */
    private val landingResultChannel: LandingResultChannel,
    /** Prefetches the arrival screen's destination photo as soon as [destAirport] resolves below,
     *  and publishes it here for `CesiumGameActivity`'s `Screen.ArrivalCelebration` to read - see
     *  DestinationPhotoChannel's doc for why this can't just be a nav arg. */
    private val destinationPhotoChannel: DestinationPhotoChannel,
    private val destinationPhotoRepository: DestinationPhotoRepository,
    private val offlineModeController: OfflineModeController,
    private val cacheDir: java.io.File,
    val flightNumber: String,
    val originIata: String,
    val destIata: String,
    val durationMin: Int,
    val mode: FlightMode = FlightMode.STORY,
    /** Which Route challenge this CHALLENGE-tagged session is scoped to (see
     *  docs/challenges.md#persistence--route-scoping) - null for STORY/FREE, and
     *  meaningless when [mode] isn't CHALLENGE. Threaded through the same nav-arg mechanism
     *  Phase 2 used for [originIata] (see Screen.InFlight). */
    val challengeId: Int? = null
) : ViewModel() {

    private val _originAirport = MutableStateFlow<Airport?>(null)
    val originAirport: StateFlow<Airport?> = _originAirport.asStateFlow()

    private val _destAirport = MutableStateFlow<Airport?>(null)
    val destAirport: StateFlow<Airport?> = _destAirport.asStateFlow()

    private val _routeDetails = MutableStateFlow<FlightRoute?>(null)
    val routeDetails: StateFlow<FlightRoute?> = _routeDetails.asStateFlow()

    private val _uiState = MutableStateFlow(InFlightState())
    val uiState: StateFlow<InFlightState> = _uiState.asStateFlow()

    /** Drives [com.silas270.blocktime.audio.EngineSoundEngine] - see [EnginePowerModel].
     *  Kept separate from [uiState] since it's an audio-presentation concern, not core telemetry. */
    private val enginePowerModel = EnginePowerModel()
    // Idle, not zero: a turbofan on the stand is still turning, and the flight begins with the
    // aircraft sitting on the runway rather than with the engines shut down.
    private val _enginePower = MutableStateFlow(EnginePowerModel.IDLE_N1)
    val enginePower: StateFlow<Float> = _enginePower.asStateFlow()

    private val _isEngineSoundEnabled = MutableStateFlow(preferencesRepository.getEngineSoundEnabled())
    val isEngineSoundEnabled: StateFlow<Boolean> = _isEngineSoundEnabled.asStateFlow()

    fun setEngineSoundEnabled(enabled: Boolean) {
        _isEngineSoundEnabled.value = enabled
        preferencesRepository.setEngineSoundEnabled(enabled)
    }

    private val _routeLineMode = MutableStateFlow(preferencesRepository.getRouteLineMode())
    val routeLineMode: StateFlow<Int> = _routeLineMode.asStateFlow()

    fun setRouteLineMode(mode: Int) {
        _routeLineMode.value = mode
        preferencesRepository.setRouteLineMode(mode)
    }

    /** The pilot's preferred map style, one of the `CesiumLiveJniBridge.MAP_STYLE_*` ids.
     *  Persisted so the choice carries over to the next flight. While offline the globe shows
     *  [effectiveMapStyle] instead, and this preference is left untouched. */
    private val _mapStyle = MutableStateFlow(preferencesRepository.getMapStyle())
    val mapStyle: StateFlow<Int> = _mapStyle.asStateFlow()

    fun setMapStyle(style: Int) {
        _mapStyle.value = style
        preferencesRepository.setMapStyle(style)
    }

    /** Drives the HUD's OFFLINE badge and locks the network styles in the map picker. */
    val networkMode: StateFlow<NetworkMode> = offlineModeController.mode

    /** What the live globe actually renders: [mapStyle], or the offline map while offline. */
    val effectiveMapStyle: StateFlow<Int> = combine(mapStyle, offlineModeController.isOffline, ::resolveEffectiveMapStyle)
        .stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            resolveEffectiveMapStyle(_mapStyle.value, offlineModeController.isOffline.value)
        )

    private val _networkNotice = MutableStateFlow<NetworkNotice?>(null)

    /** Set when the connection change switched the map on its own. The screen shows it briefly,
     *  then calls [consumeNetworkNotice]. */
    val networkNotice: StateFlow<NetworkNotice?> = _networkNotice.asStateFlow()

    fun consumeNetworkNotice() {
        _networkNotice.value = null
    }

    private var timerJob: Job? = null

    /** Debug menu only (debug builds): how many flight-milliseconds each real millisecond
     *  advances. Nothing in release writes it, so there it stays 1.0 - real time. */
    private val _debugTimeScale = MutableStateFlow(1.0)
    val debugTimeScale: StateFlow<Double> = _debugTimeScale.asStateFlow()

    fun setDebugTimeScale(scale: Double) {
        _debugTimeScale.value = if (scale.isFinite()) scale.coerceAtLeast(0.0) else 1.0
    }

    /**
     * Everything the landing must finish even though this ViewModel is about to be destroyed.
     *
     * `InFlightScreen` navigates to the arrival celebration the instant `isCompleted` flips, with
     * `popUpTo(InFlight) { inclusive = true }` - which pops this nav entry and cancels
     * [viewModelScope]. Any post-landing work launched there is therefore racing its own
     * destruction. Losing that race used to mean, in ascending order of damage: a lost destination
     * pre-render; a flight missing from the logbook while `currentAirport` had already moved; a
     * challenge landing that never got credited; and worst, a [landingResultChannel] left on
     * `Pending` forever, which hangs `ArrivalCelebration`'s `first { it != Pending }` and leaves
     * the pilot on a screen whose only button does nothing.
     *
     * So the landing pipeline runs here instead - a scope this ViewModel does not own the
     * lifetime of - and [onCleared] joins its jobs before tearing it down. The render job already
     * worked this way; the writes that actually matter did not.
     */
    private val landingScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mapRenderer = CesiumHeadlessMapRenderer(cacheDir)

    /**
     * Set the moment [completeFlight] begins, and never cleared - this flight is over.
     *
     * **A landing happens once.** [skipFlight] (debug menu) is a plain button with no debounce, and the
     * timer branch can reach [completeFlight] independently of it. Two landings would log the
     * flight twice and credit the challenge twice - advancing two legs for one flight, or
     * double-counting a Distance challenge's kilometres.
     *
     * It also short-circuits [saveCameraState] and [persistElapsed], which run outside the
     * landing's own coroutine and would otherwise be assembling a write to a slot the landing is
     * about to take. That half is a courtesy rather than the guarantee - [SessionPausedFlightStore]
     * is what actually makes the stale write unrepresentable, and it does so without depending on
     * this flag being set before the racing read. See its doc for why the two are not the same
     * check.
     *
     * `@Volatile` because it is written from [viewModelScope] (main) and read from the timer
     * coroutine, and [landingScope] (IO) is what acts on its consequences.
     */
    @Volatile
    private var landingStarted = false

    /** Where this session's paused flight lives: the global Story/Free slot, or this specific
     *  Route challenge's own row. Picked once here instead of re-branching on mode/challengeId
     *  on every read/write/clear - see [PausedFlightStore]'s own doc. */
    private val pausedFlightStore: PausedFlightStore = SessionPausedFlightStore(
        if (mode == FlightMode.CHALLENGE && challengeId != null) {
            challengeRepository.pausedFlightStore(challengeId)
        } else {
            preferencesRepository.pausedFlightStore(mode)
        }
    )

    init {
        viewModelScope.launch {
            var previous = offlineModeController.mode.value
            offlineModeController.mode.collect { current ->
                networkNoticeFor(previous, current, _mapStyle.value)?.let { _networkNotice.value = it }
                previous = current
            }
        }

        // A fresh nav entry (new flight, or resuming one) always means a fresh ViewModel
        // instance - clear out whatever the *previous* flight's landing left behind so a stale
        // result can never leak into this flight's own landing sequence.
        landingResultChannel.reset()
        destinationPhotoChannel.reset()

        val totalSec = durationMin * 60L

        // Reading the paused flight (for its elapsed time / camera, if any) is a suspend Room
        // call for CHALLENGE sessions - see pausedFlightStore - so the rest of what used to run
        // synchronously in init now runs after it resolves, in the same order as before.
        viewModelScope.launch {
            val saved = pausedFlightStore.get()
            val initialElapsedMs = saved?.elapsedMs ?: -3000L
            val savedCamera = saved?.camera

            _uiState.value = InFlightState(
                timeRemainingSeconds = totalSec - (initialElapsedMs.coerceAtLeast(0L) / 1000L),
                timeElapsedMs = initialElapsedMs, // 3 second start hold if not saved
                totalDurationSeconds = totalSec,
                timeElapsedSeconds = initialElapsedMs.coerceAtLeast(0L) / 1000L,
                restoredCameraMode = savedCamera?.mode
            )
            loadFlightDetails()
            startTimer()

            // Only present when resuming a flight that was previously saved with a camera pose —
            // a brand-new flight never has one, so the engine's own default framing applies
            // unchanged. Mode is set first so it's already correct by the time the pose lands.
            if (savedCamera != null) {
                com.silas270.blocktime.engine.live.CesiumLiveJniBridge.nativeSetCameraMode(savedCamera.mode)
                com.silas270.blocktime.engine.live.CesiumLiveJniBridge.nativeSetCameraPose(
                    savedCamera.x, savedCamera.y, savedCamera.z,
                    savedCamera.qx, savedCamera.qy, savedCamera.qz, savedCamera.qw
                )
            }

            com.silas270.blocktime.engine.live.CesiumLiveJniBridge.nativeSetRouteLineMode(
                preferencesRepository.getRouteLineMode(),
                com.silas270.blocktime.engine.live.CesiumLiveJniBridge.DEFAULT_ROUTE_LINE_BEHIND_NM,
                com.silas270.blocktime.engine.live.CesiumLiveJniBridge.DEFAULT_ROUTE_LINE_AHEAD_NM
            )
        }
    }

    /** Snapshots the live camera and persists it for this flight, so resuming later restores
     *  the same mode/position/rotation instead of the default framing. Call on the way out
     *  (e.g. ON_STOP), not on a timer — this only needs to be current when the user leaves. */
    fun saveCameraState() {
        val pose = com.silas270.blocktime.engine.live.CesiumLiveJniBridge.nativeGetCameraPose()
        // The native side answers "no camera yet" (ON_STOP before the engine has one) with all
        // zeros rather than an empty array. A zero quaternion is never a real rotation, and saving
        // it would restore a broken pose on resume - keep whatever was saved before instead.
        if (pose.size < 8 || (pose[4] == 0.0 && pose[5] == 0.0 && pose[6] == 0.0 && pose[7] == 0.0)) return
        val camera = CameraPose(
            mode = pose[0].toInt(),
            x = pose[1], y = pose[2], z = pose[3],
            qx = pose[4], qy = pose[5], qz = pose[6], qw = pose[7]
        )
        viewModelScope.launch {
            // See [landingStarted]: ON_STOP fires when this screen is popped for the arrival
            // celebration, which is exactly while the landing is clearing this same slot.
            if (landingStarted) return@launch
            val current = pausedFlightStore.get() ?: return@launch
            pausedFlightStore.save(current.copy(camera = camera))
        }
    }

    // [originIata] arrives from the nav route (see Screen.InFlight) instead of being read here
    // via `preferencesRepository.getCurrentAirport()` as it was pre-Phase-2 - for a STORY flight
    // it's still exactly that value (threaded through from FlightSearch/CheckIn unchanged), but
    // a Free Mode flight's origin is a user choice that isn't `currentAirport`, so this is the
    // one place that has to stop assuming the two are the same.
    private fun loadFlightDetails() {
        viewModelScope.launch(Dispatchers.IO) {
            val context = loadRouteContext(airportRepository, originIata, destIata)
            _originAirport.value = context.origin
            _destAirport.value = context.dest
            _routeDetails.value = context.route

            // Prefetch the arrival screen's destination photo now, well ahead of landing, so the
            // arrival screen itself never has to block on or retry a network call - see
            // DestinationPhotoChannel's doc. While offline (no connection, or data saver on) this
            // waits instead of burning the fetch's 12 s timeout; if the app comes online during
            // the flight the photo is fetched then, otherwise arrival uses its plain background.
            context.dest?.let { dest ->
                launch {
                    offlineModeController.isOffline.first { offline -> !offline }
                    val photo = destinationPhotoRepository.fetchDestinationPhoto(dest.municipality, dest.isoCountry)
                    destinationPhotoChannel.publish(photo)
                }
            }

            // Initialize coordinates to origin
            context.origin?.let { origin ->
                _uiState.update { it.copy(currentLat = origin.lat, currentLon = origin.lon) }
            }
        }
    }

    fun startTimer() {
        if (timerJob != null) return
        _uiState.update { it.copy(isRunning = true) }
        val tickDelayMs = 33L
        var lastTickTime = android.os.SystemClock.elapsedRealtime()
        timerJob = viewModelScope.launch {
            while (true) {
                delay(tickDelayMs)
                val now = android.os.SystemClock.elapsedRealtime()
                val deltaMs = now - lastTickTime
                lastTickTime = now
                
                // Persisting elapsed time can be a suspend Room write (CHALLENGE sessions), so it
                // can't happen inside _uiState.update {}'s lambda - computed here, then actually
                // written just below, still inside this same timer coroutine. Same reason
                // completeFlight() (now suspend) is called after the update rather than inside it.
                var elapsedToPersist: Long? = null
                var justCompleted = false

                _uiState.update { state ->
                    // The screen can start the timer (ON_START) before init's paused-flight read
                    // has filled in the duration. Ticking against a zero total would push a NaN
                    // progress (0 / 0) into the engine and could even "complete" the flight after
                    // the 3 s hold, so wait for the real duration instead.
                    if (state.totalDurationSeconds <= 0L) return@update state
                    // Real deltaMs still drives the engine sound's spool smoothing below; only the
                    // flight clock is scaled.
                    val newElapsedMs = state.timeElapsedMs + (deltaMs * _debugTimeScale.value).toLong()
                    val totalMs = state.totalDurationSeconds * 1000L

                    if (newElapsedMs >= totalMs + 3000L) { // Added 3 second end hold
                        justCompleted = true
                        state.copy(
                            timeRemainingSeconds = 0,
                            timeElapsedSeconds = state.totalDurationSeconds,
                            timeElapsedMs = totalMs,
                            progress = 1.0f,
                            isRunning = false,
                            isCompleted = true
                        )
                    } else {
                        val displayElapsedMs = newElapsedMs.coerceIn(0L, totalMs)
                        val newProgress = (displayElapsedMs.toFloat() / totalMs.toFloat()).coerceIn(0f, 1f)
                        com.silas270.blocktime.engine.live.CesiumLiveJniBridge.nativeSetProgress(newProgress.toDouble())

                        val telemetry = com.silas270.blocktime.engine.live.CesiumLiveJniBridge.nativeGetTelemetry()
                        val newElapsedSec = displayElapsedMs / 1000L
                        val newRemainingSec = state.totalDurationSeconds - newElapsedSec

                        if (newElapsedSec != state.timeElapsedSeconds) {
                            elapsedToPersist = newElapsedMs
                        }

                        var currentLat = state.currentLat
                        var currentLon = state.currentLon
                        var currentAlt = state.altitudeMeters
                        var currentSpeed = state.speedKmh

                        // All zeros is the native side's "no telemetry yet", not a reading.
                        if (telemetry.size >= 8 && telemetry.any { it != 0.0 }) {
                            currentLat = telemetry[1]
                            currentLon = telemetry[2]
                            currentAlt = telemetry[3].toInt()
                            currentSpeed = (telemetry[4] * 3.6).toInt() // Convert m/s to km/h

                            // Deliberately the raw telemetry rather than the rounded copies above:
                            // EnginePowerModel reads pitch attitude and true airspeed directly, and
                            // the Int truncation the HUD wants would cost it exactly the precision
                            // it needs. See its doc.
                            _enginePower.value = enginePowerModel.update(
                                progress = telemetry[0].toFloat(),
                                altitudeMeters = telemetry[3].toFloat(),
                                speedMetersPerSecond = telemetry[4].toFloat(),
                                pitchRadians = telemetry[6].toFloat(),
                                deltaSeconds = deltaMs / 1000f
                            )
                        }

                        state.copy(
                            timeRemainingSeconds = newRemainingSec,
                            timeElapsedSeconds = newElapsedSec,
                            timeElapsedMs = newElapsedMs, // Keep underlying timer going
                            progress = newProgress,
                            currentLat = currentLat,
                            currentLon = currentLon,
                            speedKmh = currentSpeed,
                            altitudeMeters = currentAlt
                        )
                    }
                }

                elapsedToPersist?.let { persistElapsed(it) }
                if (justCompleted) completeFlight()
            }
        }
    }

    private suspend fun persistElapsed(elapsedMs: Long) {
        // Same reason as [saveCameraState]'s guard: the timer's cancellation is not instant, so a
        // tick already suspended inside this function can otherwise write the slot back after the
        // landing has cleared it. See [landingStarted].
        if (landingStarted) return
        val current = pausedFlightStore.get() ?: return
        pausedFlightStore.save(current.copy(elapsedMs = elapsedMs))
    }

    fun pauseTimer() {
        timerJob?.cancel()
        timerJob = null
        _uiState.update { it.copy(isRunning = false) }
    }

    /**
     * Debug menu only (debug builds): jumps the flight to [progress]. Moves the flight clock
     * itself, not just the plane, so a running timer carries on from here and a paused one
     * resumes from here instead of snapping back to where it was. Also refreshes the scene and
     * telemetry directly, so a seek while paused is visible straight away.
     */
    fun debugSeek(progress: Float) {
        if (landingStarted) return
        val clamped = progress.coerceIn(0f, 1f)
        val totalMs = _uiState.value.totalDurationSeconds * 1000L
        if (totalMs <= 0L) return
        val elapsedMs = (clamped * totalMs).toLong()
        val elapsedSec = elapsedMs / 1000L
        _uiState.update {
            it.copy(
                progress = clamped,
                timeElapsedMs = elapsedMs,
                timeElapsedSeconds = elapsedSec,
                timeRemainingSeconds = it.totalDurationSeconds - elapsedSec
            )
        }
        com.silas270.blocktime.engine.live.CesiumLiveJniBridge.nativeSetProgress(clamped.toDouble())
        viewModelScope.launch { persistElapsed(elapsedMs) }
        val telemetry = com.silas270.blocktime.engine.live.CesiumLiveJniBridge.nativeGetTelemetry()
        if (telemetry.size < 8 || telemetry.all { it == 0.0 }) return
        _uiState.update {
            it.copy(
                currentLat = telemetry[1],
                currentLon = telemetry[2],
                altitudeMeters = telemetry[3].toInt(),
                speedKmh = (telemetry[4] * 3.6).toInt()
            )
        }
        // settleAt rather than update: a seek jumps to an arbitrary point in the flight, and
        // spooling there from wherever the last position left the engine would make the
        // sound describe the drag rather than the destination.
        _enginePower.value = enginePowerModel.settleAt(
            progress = telemetry[0].toFloat(),
            altitudeMeters = telemetry[3].toFloat(),
            speedMetersPerSecond = telemetry[4].toFloat(),
            pitchRadians = telemetry[6].toFloat()
        )
    }

    /** Debug menu only (debug builds): lands the flight now, through the real landing pipeline. */
    fun skipFlight() {
        viewModelScope.launch {
            completeFlight()
            _uiState.update { state ->
                state.copy(
                    timeRemainingSeconds = 0,
                    timeElapsedSeconds = state.totalDurationSeconds,
                    timeElapsedMs = state.totalDurationSeconds * 1000L,
                    progress = 1.0f,
                    isRunning = false,
                    isCompleted = true
                )
            }
        }
    }

    /**
     * Shared landing/completion sequence: stop the timer, kick off the destination pre-render,
     * then run docs/core-loop.md's post-landing pipeline in [landingScope] - step 2
     * (logbook), step 3 (`currentAirport`/visited-set, STORY only, and only if step 2 succeeded),
     * step 4 (achievement/challenge check) - and snap the native engine to 100% progress.
     * Invoked by both the normal timer-completion branch and the debug menu's [skipFlight] so
     * the two paths can't drift out of sync. Does not touch [_uiState] - each call site applies
     * its own (identical) completed-state update.
     *
     * Returns as soon as the work is *launched*; it does not wait for it. That is deliberate -
     * this is called from the timer coroutine, which lives in `viewModelScope` and is about to be
     * cancelled. [landingScope] is what actually carries the work to completion.
     *
     * ORDERING ASSUMPTION, relied on for correctness: this must be called before, or synchronously
     * with, the `isCompleted = true` state emission that triggers navigation. [onCleared] cancels
     * [landingScope], so a landing launched *after* that point would be silently dropped. Today
     * both call sites satisfy this with no suspension point in between (the timer branch sets
     * `elapsedToPersist` only in the not-completed case, so nothing suspends between the update
     * and this call). If you ever add a suspending step before this, the landing becomes
     * droppable - gate [onCleared]'s cancel on [landingStarted] instead.
     */
    private fun completeFlight() {
        // A flight lands once. See [landingStarted] for the two ways this used to be reachable
        // twice, and what a second landing costs.
        if (landingStarted) return
        landingStarted = true

        timerJob?.cancel()
        timerJob = null

        // Independent of the data writes below and by far the slowest step (a native render), so
        // it starts immediately and runs alongside them rather than queueing behind them - the
        // destination map wants to be ready by the time the arrival sequence ends.
        renderJob = landingScope.launch { preRenderDestinationMap() }

        // The data writes, strictly sequential because their ORDER is the invariant. Previously
        // these were three independent `viewModelScope.launch`es with `setCurrentAirport` run
        // synchronously first, which meant the pilot's position could move to a destination whose
        // flight never reached the logbook - a divergence nothing in the app can repair, since
        // the logbook is the only record of how they got anywhere.
        landingJob = landingScope.launch {
            // Step 2: the logbook entry is the flight's only durable record, so it goes first and
            // everything else is conditional on it.
            val logged = saveFlightLog()

            if (logged != null) {
                // Step 3: only a STORY-tagged session moves the player's main position/visited-set.
                // FREE and CHALLENGE sessions are logged (above) but never touch currentAirport.
                if (mode == FlightMode.STORY) {
                    preferencesRepository.setCurrentAirport(destIata)
                }
                pausedFlightStore.clear()
            } else {
                // Deliberately leaving both the position and the paused flight alone. Staying at
                // the origin with the flight still resumable is a consistent state the pilot can
                // act on; standing at a destination with no flight explaining it is not.
                android.util.Log.e(
                    "InFlightViewModel",
                    "Landing not committed for $originIata->$destIata: logbook write failed, leaving position and paused flight untouched"
                )
            }

            // Step 4: always runs, even if the logbook write failed - it is what resolves
            // [landingResultChannel], and an unresolved channel hangs the arrival screen (see
            // [landingScope]). A landing that could not be logged simply has nothing to credit.
            checkAchievementsAndChallenges(loggedFlight = logged)
        }

        com.silas270.blocktime.engine.live.CesiumLiveJniBridge.nativeSetProgress(1.0)
    }

    // ── Post-landing pipeline step 4 (docs/modes.md) ─────────────────────────
    // Every eligible flight (STORY or CHALLENGE - never FREE) is checked against all active
    // challenges here, and the result is published to [landingResultChannel] for step 5's landing
    // sequence (the rank stamp always shows first, unchanged; this decides what - if anything -
    // follows it). The challenge half delegates to processLandingForChallenges (a standalone,
    // JNI-free function so it's unit-testable without instantiating this ViewModel - see
    // ChallengeLandingTest); the before/after diffing that turns its side effects into a
    // `LandingResult` is [resolveLandingOutcome], pulled out the same way for the same reason -
    // see LandingResultTest.
    //
    // Phase 4 (achievements) deliberately has no achievement half here, and this isn't a stub -
    // docs/achievements.md never specifies an unlock-celebration screen or landing-sequence
    // beat for achievements the way achievements.md/challenges.md explicitly do for challenge
    // completion. Unlike Challenge progress (which lives on the `challenges` row and has to be
    // mutated somewhere), every v1 achievement category is fully re-derivable on read: Geographic
    // reads `AirportRepository.getVisitedGeography`'s output, Distance/Behavioral fold over the
    // STORY-tagged flight history, and Challenges-completed is a live query - see
    // `AchievementProgress`/`AccountViewModel`. So achievement state is computed on-demand
    // whenever the Account screen is viewed (same reactive pattern `AccountViewModel`/
    // `FlightSearchViewModel` already use for `visitedCountries`/`completedContinents`), not
    // checked/persisted here after every landing - there's no "just unlocked" flag to set, and
    // no new Room migration needed for this feature.
    private suspend fun checkAchievementsAndChallenges(loggedFlight: FlightLog?) {
        val distanceKm = flownDistanceKm()

        // Every exit path from here MUST leave [landingResultChannel] resolved. The arrival
        // screen's "continue" awaits `first { it != Pending }` with no fallback of its own, so an
        // unpublished channel is not a degraded result - it is a dead button on a screen the
        // pilot cannot leave. That is why this is one try/catch around the whole body rather than
        // per-query error handling: whatever goes wrong, the landing sequence still moves.
        try {
            // FREE never reaches processLandingForChallenges's own checks anyway (it's a no-op
            // for FREE), but short-circuiting here too skips two DB round trips and resolves the
            // channel near-instantly rather than leaving it Pending until a query completes.
            // A landing whose logbook write failed is treated the same way: nothing to credit.
            if (mode == FlightMode.FREE || loggedFlight == null) {
                landingResultChannel.publish(LandingResult.None)
                return
            }

            val before: List<Challenge> = challengeRepository.listActiveChallenges()
            processLandingForChallenges(
                challengeRepository, mode, challengeId, destIata, distanceKm, loggedFlight.completedAt
            )
            // Re-fetched by id (not re-listing "active" challenges) because a challenge that just
            // *completed* this landing is no longer ACTIVE - listing active-only here would make
            // every completion invisible to the diff. See resolveLandingOutcome's own note.
            val after: List<Challenge> = before.mapNotNull { challengeRepository.getChallenge(it.id) }
            landingResultChannel.publish(resolveLandingOutcome(before, after))
            // Only after the credit and the publish, and never on landingScope: requestSync
            // launches on the syncer's own scope, so onCleared's join above never waits for the
            // network and the outcome screen is never delayed by it (docs/shared-challenges.md
            // "Landing", L1, L10).
            sharedChallengeSyncer.requestSync(SyncReason.LANDING)
        } catch (e: Exception) {
            // Includes CancellationException on the way out: if this scope is being torn down
            // mid-check, publishing None is still strictly better than leaving the arrival screen
            // waiting on a value that can now never arrive.
            android.util.Log.e("InFlightViewModel", "Post-landing challenge check failed", e)
            landingResultChannel.publish(LandingResult.None)
        }
    }

    // Both launched into [landingScope] by completeFlight(), and both joined in [onCleared] -
    // they are the reason that scope exists rather than being viewModelScope work.
    private var renderJob: kotlinx.coroutines.Job? = null
    private var landingJob: kotlinx.coroutines.Job? = null

    /** Body only - the caller owns which scope this runs in (see completeFlight). */
    private suspend fun preRenderDestinationMap() {
        val dest = airportRepository.getAirportByIata(destIata) ?: return
        android.util.Log.d("InFlightViewModel", "Pre-rendering map for destination ${dest.iataCode}...")
        val result = mapRenderer.renderRouteMapForAirport(airportRepository, dest)
        when (result) {
            is CesiumHeadlessMapRenderer.Result.Success ->
                android.util.Log.d("InFlightViewModel", "Pre-rendering succeeded: ${result.path}")
            is CesiumHeadlessMapRenderer.Result.Failure ->
                android.util.Log.e("InFlightViewModel", "Pre-rendering failed: ${result.message}")
        }
    }

    /**
     * The flight's distance for the logbook and for challenge credit: the route's own figure, or,
     * if the route lookup came back empty, the great-circle distance between the two airports.
     * Logging 0 km for a flight that really was flown would quietly shortchange every Distance
     * achievement and challenge.
     */
    private fun flownDistanceKm(): Double {
        _routeDetails.value?.distanceKm?.let { return it }
        val origin = _originAirport.value ?: return 0.0
        val dest = _destAirport.value ?: return 0.0
        return com.silas270.blocktime.data.model.ChallengeProgress.haversineKm(origin.lat, origin.lon, dest.lat, dest.lon)
    }

    /**
     * Writes the logbook entry and returns it, or null if the write failed - the caller gates the
     * position write on that, so this must never report success it didn't achieve. Body only; the
     * caller owns the scope.
     *
     * Returns the row rather than a Boolean because Streak challenges need its `completedAt`: the
     * timestamp is assigned here, and reading a fresh `now` further down the pipeline would put a
     * flight on the wrong side of midnight.
     */
    private suspend fun saveFlightLog(): FlightLog? = try {
        val distanceKm = flownDistanceKm()

        flightLogRepository.logFlight(
            flightNumber = flightNumber,
            originIata = originIata,
            destIata = destIata,
            durationMin = durationMin,
            distanceKm = distanceKm,
            mode = mode
        )
    } catch (e: Exception) {
        android.util.Log.e("InFlightViewModel", "Error saving flight log to Room", e)
        null
    }

    override fun onCleared() {
        super.onCleared()
        timerJob?.cancel()

        // Let the landing finish before tearing its scope down. This runs on [landingScope]
        // itself, so it is not affected by viewModelScope having just been cancelled - which is
        // the whole point: at this moment the nav entry is already popped and viewModelScope is
        // gone, while the logbook write and challenge check may still be in flight.
        landingScope.launch {
            landingJob?.join()
            renderJob?.join()
            landingScope.cancel()
        }
    }
}

class InFlightViewModelFactory(
    private val airportRepository: AirportRepository,
    private val preferencesRepository: PreferencesRepository,
    private val flightLogRepository: FlightLogRepository,
    private val challengeRepository: ChallengeRepository,
    private val sharedChallengeSyncer: SharedChallengeSyncer,
    private val landingResultChannel: LandingResultChannel,
    private val destinationPhotoChannel: DestinationPhotoChannel,
    private val destinationPhotoRepository: DestinationPhotoRepository,
    private val offlineModeController: OfflineModeController,
    private val cacheDir: java.io.File,
    private val flightNumber: String,
    private val originIata: String,
    private val destIata: String,
    private val durationMin: Int,
    private val mode: FlightMode = FlightMode.STORY,
    private val challengeId: Int? = null
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(InFlightViewModel::class.java)) {
            return InFlightViewModel(airportRepository, preferencesRepository, flightLogRepository, challengeRepository, sharedChallengeSyncer, landingResultChannel, destinationPhotoChannel, destinationPhotoRepository, offlineModeController, cacheDir, flightNumber, originIata, destIata, durationMin, mode, challengeId) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
