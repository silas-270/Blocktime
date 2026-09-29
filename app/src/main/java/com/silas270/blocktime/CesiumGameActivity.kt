package com.silas270.blocktime

import android.os.Bundle
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import android.util.Log
import android.view.ViewGroup
import android.view.WindowManager
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.ExperimentalComposeUiApi
import com.silas270.blocktime.engine.live.CesiumEngineManager
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.platform.ComposeView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.NavOptionsBuilder
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.silas270.blocktime.data.local.AppDatabase
import com.silas270.blocktime.data.local.airport.AirportRouteSqliteDataSource
import com.silas270.blocktime.data.model.ChallengeType
import com.silas270.blocktime.data.model.FlightMode
import com.silas270.blocktime.data.model.PausedFlight
import com.silas270.blocktime.data.network.OfflineModeController
import com.silas270.blocktime.data.network.ServerReachability
import com.silas270.blocktime.data.network.room.RoomApiProvider
import com.silas270.blocktime.data.repository.AchievementsRepository
import com.silas270.blocktime.data.repository.AirportRepository
import com.silas270.blocktime.data.repository.ChallengeOutcome
import com.silas270.blocktime.data.repository.ChallengeRepository
import com.silas270.blocktime.data.repository.DestinationPhotoChannel
import com.silas270.blocktime.data.repository.DestinationPhotoRepository
import com.silas270.blocktime.data.repository.LocalAchievementsRepository
import com.silas270.blocktime.data.repository.LandingResult
import com.silas270.blocktime.data.repository.LandingResultChannel
import com.silas270.blocktime.data.repository.PexelsDestinationPhotoRepository
import com.silas270.blocktime.data.repository.LocalAirportRepository
import com.silas270.blocktime.data.repository.LocalChallengeRepository
import com.silas270.blocktime.data.repository.FlightLogRepository
import com.silas270.blocktime.data.repository.LocalFlightLogRepository
import com.silas270.blocktime.data.repository.LocalUserRepository
import com.silas270.blocktime.data.repository.PilotProgressRepository
import com.silas270.blocktime.data.repository.PreferencesRepository
import com.silas270.blocktime.data.repository.SharedChallengeSyncer
import com.silas270.blocktime.data.repository.SyncReason
import com.silas270.blocktime.data.repository.UserRepository
import com.silas270.blocktime.domain.ArrivalDestination
import com.silas270.blocktime.domain.flightNumberFor
import com.silas270.blocktime.domain.resolveArrivalDestination
import com.silas270.blocktime.domain.resolveNextLeg
import com.silas270.blocktime.engine.live.CesiumLiveJniBridge
import com.silas270.blocktime.engine.live.PendingFlightLoader
import com.silas270.blocktime.ui.Screen
import com.silas270.blocktime.ui.screens.arrival.ArrivalCelebrationScreen
import com.silas270.blocktime.ui.screens.challenge.ChallengeOutcomeScreen
import com.silas270.blocktime.ui.screens.checkin.CheckInScreen
import com.silas270.blocktime.ui.screens.flightsearch.FlightSearchScreen
import com.silas270.blocktime.ui.screens.inflight.InFlightScreen
import com.silas270.blocktime.ui.screens.onboarding.OnboardingScreen
import com.silas270.blocktime.ui.theme.BlocktimeTheme
import com.silas270.blocktime.ui.viewmodel.challenges.ChallengesViewModel
import com.silas270.blocktime.ui.viewmodel.challenges.ChallengesViewModelFactory
import com.silas270.blocktime.ui.viewmodel.checkin.CheckInViewModel
import com.silas270.blocktime.ui.viewmodel.checkin.CheckInViewModelFactory
import com.silas270.blocktime.ui.viewmodel.flightsearch.FlightSearchViewModel
import com.silas270.blocktime.ui.viewmodel.flightsearch.FlightSearchViewModelFactory
import com.silas270.blocktime.ui.viewmodel.hub.HubViewModel
import com.silas270.blocktime.ui.viewmodel.hub.HubViewModelFactory
import com.silas270.blocktime.ui.viewmodel.account.AccountViewModel
import com.silas270.blocktime.ui.viewmodel.account.AccountViewModelFactory
import com.silas270.blocktime.ui.viewmodel.inflight.InFlightViewModel
import com.silas270.blocktime.ui.viewmodel.inflight.InFlightViewModelFactory
import com.silas270.blocktime.ui.viewmodel.onboarding.OnboardingViewModel
import com.silas270.blocktime.ui.viewmodel.onboarding.OnboardingViewModelFactory
import com.google.androidgamesdk.GameActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * True only while [entry] is the settled, visible destination. Every tap-driven navigation below
 * goes through this: once the first tap navigates, the outgoing entry drops below RESUMED at once,
 * so a second tap (or a coroutine finishing late) is ignored instead of pushing a duplicate
 * destination - or, for a double-tapped back arrow, popping the Hub itself and leaving the
 * NavHost blank.
 */
private fun NavBackStackEntry.isSettled(): Boolean =
    lifecycle.currentState == Lifecycle.State.RESUMED

private fun NavHostController.navigateFrom(
    from: NavBackStackEntry,
    route: String,
    builder: NavOptionsBuilder.() -> Unit = {}
) {
    if (from.isSettled()) navigate(route, builder)
}

private fun NavHostController.popBackStackFrom(from: NavBackStackEntry) {
    if (from.isSettled()) popBackStack()
}

/** Loads a paused flight's pending-flight data and navigates straight into InFlight with it -
 *  the shared body behind both the Hub's "RESUME FLIGHT" button and the Challenges screen's
 *  "resume this challenge's in-progress leg" action, since [PausedFlight] already carries
 *  everything either call site needs (origin/dest/duration/flight number/mode/challengeId). */
private suspend fun resumeFlight(
    navController: NavHostController,
    from: NavBackStackEntry,
    pendingFlightLoader: PendingFlightLoader,
    flight: PausedFlight
) {
    if (!from.isSettled()) return
    withContext(Dispatchers.IO) {
        pendingFlightLoader.loadPendingFlight(flight.originIata, flight.destIata, flight.durationMin)
    }
    navController.navigateFrom(
        from,
        Screen.InFlight.createRoute(flight.originIata, flight.flightNumber, flight.destIata, flight.durationMin, flight.mode, flight.challengeId)
    )
}

/**
 * Sends the pilot into the next flight of Route challenge [challengeId].
 *
 * Two different journeys behind one button. A free-form Route challenge has a real decision left -
 * which onward flight gets it closer - so it goes to Flight Search scoped to that challenge, as it
 * always has. A predefined-itinerary challenge does not: its next hop is authored, so
 * [resolveNextLeg] books it directly and this drops straight onto the boarding card, skipping a
 * destination picker that would have exactly one correct answer.
 *
 * The null return from [resolveNextLeg] is what distinguishes the two - the free-form path is
 * reached by falling through, not by re-deriving which kind of challenge this is.
 */
private suspend fun continueRouteChallenge(
    navController: NavHostController,
    from: NavBackStackEntry,
    pendingFlightLoader: PendingFlightLoader,
    challengeRepository: ChallengeRepository,
    airportRepository: AirportRepository,
    challengeId: Int
) {
    if (!from.isSettled()) return
    val leg = withContext(Dispatchers.IO) {
        resolveNextLeg(challengeRepository, airportRepository, challengeId)
    }
    if (leg == null) {
        navController.navigateFrom(from, Screen.FlightSearch.createRoute(FlightMode.CHALLENGE, challengeId))
        return
    }
    withContext(Dispatchers.IO) {
        pendingFlightLoader.loadPendingFlight(leg.originIata, leg.destIata, leg.durationMin)
    }
    navController.navigateFrom(
        from,
        Screen.CheckIn.createRoute(leg.originIata, leg.flightNumber, leg.destIata, leg.durationMin, FlightMode.CHALLENGE, challengeId)
    )
}

/**
 * How long the arrival screen will wait for the post-landing challenge check before giving up and
 * sending the pilot to the Hub anyway. Generous on purpose - it exists to bound a pathological
 * case, not to race a healthy check, which resolves in well under this on any device.
 */
private const val LANDING_RESULT_TIMEOUT_MS = 5000L

class CesiumGameActivity : GameActivity() {

    private lateinit var airportRepository: AirportRepository
    private lateinit var pendingFlightLoader: PendingFlightLoader
    private lateinit var preferencesRepository: PreferencesRepository
    private lateinit var userRepository: UserRepository
    private lateinit var flightLogRepository: FlightLogRepository
    private lateinit var challengeRepository: ChallengeRepository
    private lateinit var achievementsRepository: AchievementsRepository
    private lateinit var pilotProgressRepository: PilotProgressRepository

    /** Process-wide (see OfflineModeController.getInstance): ViewModels can outlive this Activity. */
    private lateinit var offlineModeController: OfflineModeController

    /** Process-wide like [offlineModeController]: the second signal, "does our server answer"
     *  (docs/shared-challenges.md "Two signals"). */
    private lateinit var serverReachability: ServerReachability

    /** Built on [appScope] after the repositories, so a landing's sync request never waits on a
     *  ViewModel's scope (docs/shared-challenges.md "Sync moments"). */
    private lateinit var sharedChallengeSyncer: SharedChallengeSyncer

    /** Outlives every ViewModel on purpose - it is what keeps the shared derivation warm across
     *  navigation, which is the entire point of PilotProgressRepository. Cancelled in onDestroy. */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Bridges the post-landing challenge-check result across the InFlight -> ArrivalCelebration
    // -> (tick-up | completion) navigation hop (Phase 3b - see LandingResultChannel's doc). Needs
    // no Activity/context dependency, so - unlike the repositories above - it's constructed
    // directly here rather than in onCreate.
    private val landingResultChannel = LandingResultChannel()

    // Bridges the arrival screen's prefetched destination photo across the same navigation hop,
    // for the same reason (see DestinationPhotoChannel's doc) - also needs no Activity/context
    // dependency to construct, unlike the repositories above.
    private val destinationPhotoChannel = DestinationPhotoChannel()
    private val destinationPhotoRepository: DestinationPhotoRepository = PexelsDestinationPhotoRepository()
    private var activeNavController: NavHostController? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mSurfaceView.visibility = android.view.View.GONE

        // Immersive mode
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).let { controller ->
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        // Initialize database helper and preferences repository
        airportRepository = LocalAirportRepository(AirportRouteSqliteDataSource(applicationContext))
        pendingFlightLoader = PendingFlightLoader(airportRepository)
        preferencesRepository = PreferencesRepository(applicationContext)
        offlineModeController = OfflineModeController.getInstance(applicationContext)
        serverReachability = ServerReachability.getInstance(applicationContext)
        val roomApi = RoomApiProvider.roomApi
        com.silas270.blocktime.ui.theme.ThemeModeHolder.current = preferencesRepository.getThemeMode()

        // Initialize Room database and repositories
        val appDatabase = AppDatabase.getInstance(applicationContext)
        userRepository = LocalUserRepository(appDatabase.userProfileDao())
        flightLogRepository = LocalFlightLogRepository(appDatabase.flightLogDao(), appDatabase.userProfileDao())
        challengeRepository = LocalChallengeRepository(
            appDatabase.challengeDao(),
            appDatabase.userProfileDao(),
            airportRepository,
            roomApi = roomApi,
            serverReachability = serverReachability,
            preferencesRepository = preferencesRepository
        )
        achievementsRepository = LocalAchievementsRepository(
            appDatabase.achievementUnlockDao(),
            appDatabase.userProfileDao(),
            airportRepository,
            flightLogRepository
        )

        pilotProgressRepository = PilotProgressRepository(
            userRepository,
            flightLogRepository,
            airportRepository,
            achievementsRepository,
            appScope
        )

        sharedChallengeSyncer = SharedChallengeSyncer(
            challengeRepository,
            userRepository,
            preferencesRepository,
            roomApi,
            serverReachability,
            appScope
        )

        // Copy reference database asset on first run
        airportRepository.ensureDatabaseCopied()

        // Attach the lifecycle observer ONCE before Compose content is set.
        // This is not re-triggered on recomposition because it targets the Activity lifecycle,
        // not the Compose recomposition lifecycle.
        lifecycle.addObserver(CesiumEngineManager())

        // Keep the home base's rendered globe out of the cache's eviction pool for as long as it
        // is the home base - see MapImageCache.pinnedIatas. Re-read from the profile flow rather
        // than set once, so changing home base moves the pin with it.
        lifecycleScope.launch {
            userRepository.getProfileFlow().collect { profile ->
                val home = profile?.homeAirportIata?.takeIf { it.isNotBlank() }
                com.silas270.blocktime.engine.headless.MapImageCache.pinnedIatas = setOfNotNull(home)
            }
        }

        // Parse the world-map SVG before any screen asks for it - see WorldMapParser.warm.
        // Dispatchers.Default rather than IO: this is CPU-bound path parsing, not file I/O.
        lifecycleScope.launch(Dispatchers.Default) {
            com.silas270.blocktime.ui.map.WorldMapParser.warm(applicationContext)
        }

        val hasProfile = kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) {
            val profileExists = userRepository.getProfile() != null
            if (!profileExists && preferencesRepository.isOnboardingCompleted()) {
                preferencesRepository.setOnboardingCompleted(false)
            }
            profileExists
        }

        val composeView = ComposeView(this).apply {
            setContent {
                val themeMode = com.silas270.blocktime.ui.theme.ThemeModeHolder.current
                BlocktimeTheme(mode = themeMode) {
                    val navController = rememberNavController()
                    androidx.compose.runtime.DisposableEffect(navController) {
                        activeNavController = navController
                        onDispose { activeNavController = null }
                    }
                    val startDestination = if (preferencesRepository.isOnboardingCompleted() && hasProfile == true) {
                        Screen.Hub.route
                    } else {
                        Screen.Onboarding.route
                    }

                    // ── Rendering toggle: enable wgpu rendering on flight screens only ──
                    val currentEntry = navController.currentBackStackEntryAsState().value
                    val currentRoute = currentEntry?.destination?.route
                    val shouldRender = currentRoute?.startsWith("check_in/") == true
                            || currentRoute?.startsWith("in_flight/") == true

                    // Rendering enable/disable is route-scoped (only on flight screens).
                    // Suspend/resume (winit sleep/wake) is lifecycle-scoped via CesiumEngineManager.
                    LaunchedEffect(shouldRender) {
                        CesiumLiveJniBridge.nativeSetRenderingEnabled(shouldRender)
                        mSurfaceView.visibility = if (shouldRender) {
                            android.view.View.VISIBLE
                        } else {
                            android.view.View.GONE
                        }
                    }

                    // Keep the screen awake for the flight session (check-in + in-flight) only.
                    // Route-scoped like rendering, so no screen can clear it for another - the Hub,
                    // Passport and Settings sleep normally.
                    LaunchedEffect(shouldRender) {
                        if (shouldRender) {
                            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        } else {
                            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        }
                    }

                    // Portrait everywhere except InFlight, which allows free rotation.
                    val isInFlight = currentRoute?.startsWith("in_flight/") == true
                    LaunchedEffect(isInFlight) {
                        requestedOrientation = if (isInFlight) {
                            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                        } else {
                            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                        }
                    }

                    val bgColor = if (shouldRender) {
                        androidx.compose.ui.graphics.Color.Transparent
                    } else {
                        androidx.compose.material3.MaterialTheme.colorScheme.background
                    }

                    // Every screen scales as one S23-proportioned layout - see ProvideDesignDensity.
                    com.silas270.blocktime.ui.theme.ProvideDesignDensity {
                        Surface(
                            modifier = Modifier
                                .fillMaxSize(),
                            color = bgColor
                        ) {
                            NavHost(
                                navController = navController,
                                startDestination = startDestination,
                                enterTransition = {
                                    slideIntoContainer(
                                        towards = AnimatedContentTransitionScope.SlideDirection.Start,
                                        animationSpec = tween(300)
                                    )
                                },
                                exitTransition = {
                                    slideOutOfContainer(
                                        towards = AnimatedContentTransitionScope.SlideDirection.Start,
                                        animationSpec = tween(300)
                                    )
                                },
                                popEnterTransition = {
                                    slideIntoContainer(
                                        towards = AnimatedContentTransitionScope.SlideDirection.End,
                                        animationSpec = tween(300)
                                    )
                                },
                                popExitTransition = {
                                    slideOutOfContainer(
                                        towards = AnimatedContentTransitionScope.SlideDirection.End,
                                        animationSpec = tween(300)
                                    )
                                }
                            ) {
                                // ── Onboarding ──
                                composable(Screen.Onboarding.route) { entry ->
                                    val viewModel: OnboardingViewModel = viewModel(
                                        factory = OnboardingViewModelFactory(airportRepository, preferencesRepository, userRepository, cacheDir)
                                    )
                                    OnboardingScreen(
                                        viewModel = viewModel,
                                        onOnboardingComplete = {
                                            navController.navigateFrom(entry, Screen.Hub.route) {
                                                popUpTo(Screen.Onboarding.route) { inclusive = true }
                                            }
                                        }
                                    )
                                }

                                // ── Hub ──
                                composable(Screen.Hub.route) { entry ->
                                    val viewModel: HubViewModel = viewModel(
                                        factory = HubViewModelFactory(airportRepository, preferencesRepository, userRepository, challengeRepository, pilotProgressRepository, offlineModeController, sharedChallengeSyncer, cacheDir)
                                    )
                                    val coroutineScope = androidx.compose.runtime.rememberCoroutineScope()
                                    com.silas270.blocktime.ui.screens.hub.HubScreen(
                                        viewModel = viewModel,
                                        onBookFlightClick = {
                                            navController.navigateFrom(entry, Screen.FlightSearch.createRoute())
                                        },
                                        onChallengesClick = {
                                            navController.navigateFrom(entry, Screen.Challenges.route)
                                        },
                                        onResumeFlightClick = { flight ->
                                            coroutineScope.launch { resumeFlight(navController, entry, pendingFlightLoader, flight) }
                                        },
                                        onPassportClick = {
                                            navController.navigateFrom(entry, Screen.Account.route)
                                        },
                                        onSettingsClick = {
                                            navController.navigateFrom(entry, Screen.Settings.route)
                                        },
                                        onContinueChallengeClick = { challengeId ->
                                            coroutineScope.launch {
                                                continueRouteChallenge(navController, entry, pendingFlightLoader, challengeRepository, airportRepository, challengeId)
                                            }
                                        }
                                    )
                                }

                                // ── Challenges (modes/goals surface) ──
                                composable(Screen.Challenges.route) { entry ->
                                    val challengesViewModel: ChallengesViewModel = viewModel(
                                        factory = ChallengesViewModelFactory(challengeRepository, airportRepository, pilotProgressRepository, preferencesRepository, sharedChallengeSyncer, serverReachability)
                                    )
                                    val coroutineScope = androidx.compose.runtime.rememberCoroutineScope()
                                    com.silas270.blocktime.ui.screens.challenges.ChallengesScreen(
                                        viewModel = challengesViewModel,
                                        onBackClick = { navController.popBackStackFrom(entry) },
                                        onFreeModeClick = {
                                            navController.navigateFrom(entry, Screen.FlightSearch.createRoute(FlightMode.FREE))
                                        },
                                        onResumeFreeFlight = { flight ->
                                            // The Story/Free slot already has a Free Mode flight paused - resume
                                            // it directly instead of routing through a fresh booking, same as
                                            // Hub's onResumeFlightClick and onResumeRouteChallenge below.
                                            coroutineScope.launch { resumeFlight(navController, entry, pendingFlightLoader, flight) }
                                        },
                                        onContinueRouteChallenge = { challengeId ->
                                            // Free-form: the same booking flow Story Mode's "Book a flight" uses,
                                            // just tagged CHALLENGE and scoped to this challenge's own position
                                            // pointer. Predefined itinerary: straight to the boarding card with
                                            // the authored next leg already filled in. See continueRouteChallenge.
                                            coroutineScope.launch {
                                                continueRouteChallenge(navController, entry, pendingFlightLoader, challengeRepository, airportRepository, challengeId)
                                            }
                                        },
                                        onResumeRouteChallenge = { challenge ->
                                            // This challenge already has its own paused flight (see
                                            // Challenge.pausedFlight) - resume it directly instead of sending
                                            // the player through flight search again, same as Hub's
                                            // onResumeFlightClick above.
                                            val flight = challenge.pausedFlight
                                            if (flight != null) {
                                                coroutineScope.launch { resumeFlight(navController, entry, pendingFlightLoader, flight) }
                                            }
                                        },
                                        onChallengeStarted = {
                                            // A Route challenge just took over Hub focus - drop straight back
                                            // there instead of lingering on the Challenges screen.
                                            navController.navigateFrom(entry, Screen.Hub.route) {
                                                popUpTo(Screen.Hub.route) { inclusive = true }
                                            }
                                        }
                                    )
                                }

                                // ── Flight Search ──
                                composable(
                                    route = Screen.FlightSearch.route,
                                    arguments = listOf(
                                        navArgument("mode") {
                                            type = NavType.StringType
                                            defaultValue = FlightMode.STORY.name
                                        },
                                        navArgument("challengeId") {
                                            type = NavType.IntType
                                            defaultValue = -1
                                        }
                                    )
                                ) { backStackEntry ->
                                    val mode = backStackEntry.arguments?.getString("mode")
                                        ?.let { runCatching { FlightMode.valueOf(it) }.getOrDefault(FlightMode.STORY) }
                                        ?: FlightMode.STORY
                                    val challengeId = backStackEntry.arguments?.getInt("challengeId")
                                        ?.takeIf { it >= 0 }

                                    val viewModel: FlightSearchViewModel = viewModel(
                                        factory = FlightSearchViewModelFactory(applicationContext, airportRepository, preferencesRepository, userRepository, challengeRepository, pilotProgressRepository, mode, challengeId)
                                    )
                                    val coroutineScope = androidx.compose.runtime.rememberCoroutineScope()

                                    FlightSearchScreen(
                                        viewModel = viewModel,
                                        mode = mode,
                                        onBackClick = {
                                            navController.popBackStackFrom(backStackEntry)
                                        },
                                        onRouteConfirm = { route ->
                                            if (backStackEntry.isSettled()) coroutineScope.launch {
                                                // `route.originIata` is whatever origin FlightSearchViewModel resolved
                                                // for this session - `currentAirport` for STORY (unchanged), the
                                                // player's picked airport for FREE, that Route challenge's own position
                                                // pointer for CHALLENGE (Phase 3). Reading it off the route (rather
                                                // than re-reading currentAirport here) is what lets both Free Mode's
                                                // and a Route challenge's origin actually reach booking instead of
                                                // being silently overridden.
                                                val originIata = route.originIata
                                                val flightNo = flightNumberFor(route.destIata)
                                                val durationMin = route.durationMin
                                                withContext(Dispatchers.IO) {
                                                    pendingFlightLoader.loadPendingFlight(originIata, route.destIata, durationMin)
                                                }
                                                navController.navigateFrom(backStackEntry, Screen.CheckIn.createRoute(originIata, flightNo, route.destIata, durationMin, mode, challengeId))
                                            }
                                        }
                                    )
                                }

                                // ── Check-In ──
                                composable(
                                    route = Screen.CheckIn.route,
                                    arguments = listOf(
                                        navArgument("originIata") { type = NavType.StringType },
                                        navArgument("flightNo") { type = NavType.StringType },
                                        navArgument("destIata") { type = NavType.StringType },
                                        navArgument("durationMin") { type = NavType.IntType },
                                        navArgument("mode") { type = NavType.StringType },
                                        navArgument("challengeId") {
                                            type = NavType.IntType
                                            defaultValue = -1
                                        }
                                    )
                                ) { backStackEntry ->
                                    val originIata = backStackEntry.arguments?.getString("originIata") ?: ""
                                    val flightNo = backStackEntry.arguments?.getString("flightNo") ?: ""
                                    val destIata = backStackEntry.arguments?.getString("destIata") ?: ""
                                    val durationMin = backStackEntry.arguments?.getInt("durationMin") ?: 0
                                    val mode = backStackEntry.arguments?.getString("mode")
                                        ?.let { runCatching { FlightMode.valueOf(it) }.getOrDefault(FlightMode.STORY) }
                                        ?: FlightMode.STORY
                                    // Pure passthrough here (CheckInViewModel doesn't need it) - carried forward to
                                    // InFlight, which is where a CHALLENGE session's scoping is actually consumed.
                                    val challengeId = backStackEntry.arguments?.getInt("challengeId")
                                        ?.takeIf { it >= 0 }

                                    // No-op on the normal path; reloads the engine after process death.
                                    LaunchedEffect(originIata, destIata, durationMin) {
                                        withContext(Dispatchers.IO) {
                                            pendingFlightLoader.ensureLoaded(originIata, destIata, durationMin)
                                        }
                                    }

                                    val viewModel: CheckInViewModel = viewModel(
                                        factory = CheckInViewModelFactory(airportRepository, userRepository, originIata, destIata, flightNo)
                                    )
                                    val coroutineScope = androidx.compose.runtime.rememberCoroutineScope()

                                    CheckInScreen(
                                        viewModel = viewModel,
                                        onBackClick = {
                                            navController.popBackStackFrom(backStackEntry)
                                        },
                                        onStartFlight = { fn, di, dm ->
                                            // A fresh PausedFlight (elapsedMs/camera both null) is the reset -
                                            // no separate "clear the old progress/camera" call needed, unlike
                                            // before this was unified into one model. A CHALLENGE session's
                                            // marker lives on the challenge's own row (see Challenge.pausedFlight),
                                            // never in the global STORY/FREE slot - keeps a challenge pause from
                                            // ever being confused with, or clobbered by, Story Mode's.
                                            val flight = PausedFlight(fn, originIata, di, dm, mode, challengeId)
                                            val store = if (mode == FlightMode.CHALLENGE && challengeId != null) {
                                                challengeRepository.pausedFlightStore(challengeId)
                                            } else {
                                                preferencesRepository.pausedFlightStore(mode)
                                            }
                                            // Navigate only once the slot is actually written. This used
                                            // to launch the save and navigate immediately, which for a
                                            // CHALLENGE session was a race the save could lose: that
                                            // store's save() does a suspending Room read first, and the
                                            // navigation below pops CheckIn inclusively - cancelling the
                                            // rememberCoroutineScope this runs in. When it lost, no
                                            // PausedFlight was ever written, and InFlightViewModel's
                                            // persistElapsed (`get() ?: return`) then silently skipped
                                            // every elapsed-time write for the whole flight, so
                                            // backgrounding it lost all progress with no way to resume.
                                            //
                                            // Ordering it this way removes the race rather than widening
                                            // it: nothing pops this screen until the write returns, so
                                            // the scope cannot be torn down underneath it.
                                            if (backStackEntry.isSettled()) coroutineScope.launch {
                                                store.save(flight)
                                                navController.navigateFrom(backStackEntry, Screen.InFlight.createRoute(originIata, fn, di, dm, mode, challengeId)) {
                                                    popUpTo(Screen.CheckIn.route) { inclusive = true }
                                                }
                                            }
                                        }
                                    )
                                }

                                // ── In-Flight ──
                                composable(
                                    route = Screen.InFlight.route,
                                    arguments = listOf(
                                        navArgument("originIata") { type = NavType.StringType },
                                        navArgument("flightNo") { type = NavType.StringType },
                                        navArgument("destIata") { type = NavType.StringType },
                                        navArgument("durationMin") { type = NavType.IntType },
                                        navArgument("mode") { type = NavType.StringType },
                                        navArgument("challengeId") {
                                            type = NavType.IntType
                                            defaultValue = -1
                                        }
                                    )
                                ) { backStackEntry ->
                                    val originIata = backStackEntry.arguments?.getString("originIata") ?: ""
                                    val flightNo = backStackEntry.arguments?.getString("flightNo") ?: ""
                                    val destIata = backStackEntry.arguments?.getString("destIata") ?: ""
                                    val durationMin = backStackEntry.arguments?.getInt("durationMin") ?: 0
                                    val mode = backStackEntry.arguments?.getString("mode")
                                        ?.let { runCatching { FlightMode.valueOf(it) }.getOrDefault(FlightMode.STORY) }
                                        ?: FlightMode.STORY
                                    // Consumed by InFlightViewModel.checkAchievementsAndChallenges() on landing - see
                                    // docs/challenges.md#persistence--route-scoping.
                                    val challengeId = backStackEntry.arguments?.getInt("challengeId")
                                        ?.takeIf { it >= 0 }

                                    // No-op on the normal path; reloads the engine after process death.
                                    LaunchedEffect(originIata, destIata, durationMin) {
                                        withContext(Dispatchers.IO) {
                                            pendingFlightLoader.ensureLoaded(originIata, destIata, durationMin)
                                        }
                                    }

                                    val viewModel: InFlightViewModel = viewModel(
                                        factory = InFlightViewModelFactory(airportRepository, preferencesRepository, flightLogRepository, challengeRepository, sharedChallengeSyncer, landingResultChannel, destinationPhotoChannel, destinationPhotoRepository, offlineModeController, cacheDir, flightNo, originIata, destIata, durationMin, mode, challengeId)
                                    )

                                    InFlightScreen(
                                        viewModel = viewModel,
                                        onLandingCelebration = { rank ->
                                            // InFlightViewModel.completeFlight() already cleared this session's
                                            // paused flight (global slot or this challenge's own row, whichever
                                            // applies) before this fires - nothing left to do here but navigate.
                                            navController.navigate(Screen.ArrivalCelebration.createRoute(flightNo, destIata, durationMin, rank, mode)) {
                                                popUpTo(Screen.InFlight.route) { inclusive = true }
                                            }
                                        },
                                        onExitFlight = {
                                            navController.navigateFrom(backStackEntry, Screen.Hub.route) {
                                                popUpTo(Screen.Hub.route) { inclusive = true }
                                            }
                                        }
                                    )
                                }

                                // ── Arrival Celebration ──
                                composable(
                                    route = Screen.ArrivalCelebration.route,
                                    arguments = listOf(
                                        navArgument("flightNo") { type = NavType.StringType },
                                        navArgument("destIata") { type = NavType.StringType },
                                        navArgument("durationMin") { type = NavType.IntType },
                                        navArgument("rank") { type = NavType.StringType },
                                        navArgument("mode") { type = NavType.StringType }
                                    )
                                ) { backStackEntry ->
                                    val flightNo = backStackEntry.arguments?.getString("flightNo") ?: ""
                                    val destIata = backStackEntry.arguments?.getString("destIata") ?: ""
                                    val durationMin = backStackEntry.arguments?.getInt("durationMin") ?: 0
                                    val rank = backStackEntry.arguments?.getString("rank") ?: ""
                                    // Not consumed by this screen itself - docs/core-loop.md's post-landing
                                    // pipeline step 5 branches purely on `landingResultChannel`, not on mode.
                                    @Suppress("UNUSED_VARIABLE")
                                    val mode = backStackEntry.arguments?.getString("mode")
                                        ?.let { runCatching { FlightMode.valueOf(it) }.getOrDefault(FlightMode.STORY) }
                                        ?: FlightMode.STORY
                                    val coroutineScope = androidx.compose.runtime.rememberCoroutineScope()
                                    // Snapshot only - already resolved (or not) by InFlightViewModel's
                                    // prefetch well before this screen is reached, same as how
                                    // landingResultChannel.result.value is read on the outcome screen.
                                    val destPhoto = destinationPhotoChannel.photo.value
                                    // For "Welcome to <city>". Looked up rather than passed in the route,
                                    // where a free-text city name would need escaping.
                                    val destCity by androidx.compose.runtime.produceState<String?>(null, destIata) {
                                        value = withContext(Dispatchers.IO) {
                                            runCatching { airportRepository.getAirportByIata(destIata)?.municipality }.getOrNull()
                                        }
                                    }

                                    ArrivalCelebrationScreen(
                                        flightNo = flightNo,
                                        destIata = destIata,
                                        durationMin = durationMin,
                                        rank = rank,
                                        destPhoto = destPhoto,
                                        destCity = destCity,
                                        onContinue = {
                                            // docs/core-loop.md's post-landing pipeline step 5: the rank stamp above
                                            // always shows first, unchanged - this is the "always sequenced,
                                            // never replaced" hand-off into whatever step 4's challenge check
                                            // found (Phase 3b). `first { it != Pending }` awaits a resolved value
                                            // rather than racing InFlightViewModel's IO-dispatched check - see
                                            // LandingResultChannel's doc for why Pending is never itself acted on.
                                            coroutineScope.launch {
                                                // Bounded wait. `first { it != Pending }` on its own has no
                                                // fallback: if the landing check never publishes - it threw,
                                                // or its scope died - this suspends forever and "continue"
                                                // becomes a button that does nothing, on a screen with no
                                                // other way out. InFlightViewModel now guarantees a publish on
                                                // every path, so this is the second lock on the same door
                                                // rather than the only one; the timeout being reached at all
                                                // means something upstream is broken, so it is logged.
                                                // Restored after process death: no landing ran in this
                                                // process, so no result can ever arrive - go straight on.
                                                val outcome = if (!landingResultChannel.isArmed) LandingResult.None
                                                else withTimeoutOrNull(LANDING_RESULT_TIMEOUT_MS) {
                                                    landingResultChannel.result.first { it != LandingResult.Pending }
                                                } ?: run {
                                                    Log.w(
                                                        "CesiumGameActivity",
                                                        "Landing result never resolved within ${LANDING_RESULT_TIMEOUT_MS}ms; continuing to Hub"
                                                    )
                                                    LandingResult.None
                                                }
                                                if (outcome is LandingResult.ChallengesAffected) {
                                                    // A completed Route challenge is no longer ACTIVE, so it can
                                                    // no longer be focused - clear the pref rather than leave it
                                                    // stale (HubViewModel would self-heal this anyway, but this
                                                    // avoids the round-trip). Only when the completed one *is* the
                                                    // focused one: finishing some other Route challenge must not
                                                    // unfocus the one the pilot picked.
                                                    val focusedId = preferencesRepository.getFocusedRouteChallengeId()
                                                    if (outcome.outcomes.any {
                                                            it is ChallengeOutcome.Completed &&
                                                                it.type == ChallengeType.ROUTE &&
                                                                it.challengeId == focusedId
                                                        }
                                                    ) {
                                                        preferencesRepository.clearFocusedRouteChallengeId()
                                                    }
                                                }

                                                // One routing rule for both CONTINUE buttons (docs/shared-challenges.md
                                                // "Landing"): outcomes go to the outcome screen; otherwise a terminal
                                                // row still waiting to be presented (completed by a sync mid-flight,
                                                // L3, L7) goes to Challenges, and nothing at all goes to the Hub.
                                                val hasPendingPresentation = withContext(Dispatchers.IO) {
                                                    runCatching { challengeRepository.hasPendingPresentation() }.getOrDefault(false)
                                                }
                                                when (resolveArrivalDestination(outcome, hasPendingPresentation)) {
                                                    ArrivalDestination.OUTCOME ->
                                                        navController.navigateFrom(backStackEntry, Screen.ChallengeOutcome.route) {
                                                            popUpTo(Screen.ArrivalCelebration.route) { inclusive = true }
                                                        }
                                                    ArrivalDestination.CHALLENGES ->
                                                        navController.navigateFrom(backStackEntry, Screen.Challenges.route) {
                                                            popUpTo(Screen.Hub.route) { inclusive = false }
                                                        }
                                                    ArrivalDestination.HUB ->
                                                        navController.navigateFrom(backStackEntry, Screen.Hub.route) {
                                                            popUpTo(Screen.Hub.route) { inclusive = true }
                                                        }
                                                }
                                            }
                                        }
                                    )
                                }

                                // ── Challenge outcome (per-leg tick-up and/or completion) ──
                                composable(Screen.ChallengeOutcome.route) { entry ->
                                    val landingResult = landingResultChannel.result.value
                                    val outcomes = (landingResult as? LandingResult.ChallengesAffected)
                                        ?.outcomes.orEmpty()
                                    if (outcomes.isEmpty()) {
                                        // Only reachable when Navigation restores this screen after
                                        // process death: the in-memory result is gone, so there is
                                        // nothing to show. Completions still surface on Challenges via
                                        // the database-driven celebration queue.
                                        LaunchedEffect(Unit) {
                                            entry.lifecycle.currentStateFlow.first { it == Lifecycle.State.RESUMED }
                                            navController.navigateFrom(entry, Screen.Hub.route) {
                                                popUpTo(Screen.Hub.route) { inclusive = true }
                                            }
                                        }
                                        return@composable
                                    }
                                    val coroutineScope = androidx.compose.runtime.rememberCoroutineScope()
                                    ChallengeOutcomeScreen(
                                        outcomes = outcomes,
                                        loadChallenge = challengeRepository::getChallenge,
                                        onContinue = {
                                            // Any completion (even mixed with merely-advanced
                                            // challenges), or a terminal row a sync left waiting
                                            // to be presented (L8), sends the pilot to Challenges
                                            // instead of Hub, so they land on the presentation
                                            // (docs/challenges.md, docs/shared-challenges.md
                                            // "Landing") rather than having to think to go check.
                                            // Collapses the whole flight-session stack down to Hub
                                            // the same way the plain-Hub branch does, just with
                                            // Challenges pushed on top of it. The same rule as the
                                            // arrival screen's, so the two cannot drift apart.
                                            coroutineScope.launch {
                                                val hasPendingPresentation = withContext(Dispatchers.IO) {
                                                    runCatching { challengeRepository.hasPendingPresentation() }.getOrDefault(false)
                                                }
                                                val destination = resolveArrivalDestination(
                                                    landingResult, hasPendingPresentation, fromOutcomeScreen = true
                                                )
                                                if (destination == ArrivalDestination.CHALLENGES) {
                                                    navController.navigateFrom(entry, Screen.Challenges.route) {
                                                        popUpTo(Screen.Hub.route) { inclusive = false }
                                                    }
                                                } else {
                                                    navController.navigateFrom(entry, Screen.Hub.route) {
                                                        popUpTo(Screen.Hub.route) { inclusive = true }
                                                    }
                                                }
                                            }
                                        }
                                    )
                                }

                                // ── Account / Passport ──
                                composable(Screen.Account.route) { entry ->
                                    val viewModel: AccountViewModel = viewModel(
                                        factory = AccountViewModelFactory(applicationContext, userRepository, flightLogRepository, airportRepository, preferencesRepository, pilotProgressRepository, offlineModeController, serverReachability, cacheDir)
                                    )
                                    
                                    com.silas270.blocktime.ui.screens.account.AccountScreen(
                                        viewModel = viewModel,
                                        onBackClick = { navController.popBackStackFrom(entry) }
                                    )
                                }

                                // ── Settings ──
                                composable(Screen.Settings.route) { entry ->
                                    val viewModel: AccountViewModel = viewModel(
                                        factory = AccountViewModelFactory(applicationContext, userRepository, flightLogRepository, airportRepository, preferencesRepository, pilotProgressRepository, offlineModeController, serverReachability, cacheDir)
                                    )

                                    com.silas270.blocktime.ui.screens.settings.SettingsScreen(
                                        viewModel = viewModel,
                                        onBackClick = { navController.popBackStackFrom(entry) },
                                        // The home-base celebrations end their flow at the Hub rather
                                        // than dropping back onto Settings - same collapse-the-stack
                                        // pattern every other "done, go home" hand-off uses.
                                        onNavigateHome = {
                                            navController.navigateFrom(entry, Screen.Hub.route) {
                                                popUpTo(Screen.Hub.route) { inclusive = true }
                                            }
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        val rootLayout = android.widget.FrameLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        (mSurfaceView.parent as? ViewGroup)?.removeView(mSurfaceView)
        rootLayout.addView(mSurfaceView, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))
        rootLayout.addView(composeView, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))
        setContentView(rootLayout)

        if (BuildConfig.DEBUG) {
            val filter = android.content.IntentFilter().apply {
                addAction("com.silas270.blocktime.CONTROL")
                addAction("com.silas270.blocktime.CAPTURE_SCREEN")
            }
            // Exported on purpose: tools/capture_all_screens.sh sends these from `adb shell`.
            // Debug builds only. Android 14+ throws at registration without an explicit flag.
            androidx.core.content.ContextCompat.registerReceiver(this, object : android.content.BroadcastReceiver() {
                override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
                    if (intent == null) return
                    when (intent.getStringExtra("orientation")) {
                        "landscape" -> requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                        "portrait" -> requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                        "auto" -> requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                    }
                    if (intent.hasExtra("scenic")) {
                        com.silas270.blocktime.ui.screens.inflight.InFlightDebugControl
                            .setScenicMode(intent.getBooleanExtra("scenic", false))
                    }
                    val navTarget = intent.getStringExtra("navigate")
                    if (!navTarget.isNullOrBlank()) {
                        Log.i("CesiumGameActivity", "Received broadcast to navigate to $navTarget")
                        try {
                            if (navTarget.startsWith("challenge_outcome") && landingResultChannel.result.value !is LandingResult.ChallengesAffected) {
                                landingResultChannel.publish(
                                    LandingResult.ChallengesAffected(
                                        listOf(
                                            ChallengeOutcome.Completed(
                                                challengeId = 1,
                                                name = "First Cross-Country",
                                                type = com.silas270.blocktime.data.model.ChallengeType.DISTANCE,
                                                oldProgress = 0.5f,
                                                iconName = "trophy"
                                            ),
                                            ChallengeOutcome.Advanced(
                                                challengeId = 2,
                                                name = "European Explorer",
                                                type = com.silas270.blocktime.data.model.ChallengeType.ROUTE,
                                                oldProgress = 0.25f,
                                                newProgress = 0.50f,
                                                iconName = "globe"
                                            )
                                        )
                                    )
                                )
                            }
                            // The arrival screen reads the photo InFlight prefetched; with no flight
                            // behind it, fetch the destination's photo first so the credit shows too.
                            val arrivalDest = navTarget.split("/").takeIf { it[0] == "arrival_celebration" }?.getOrNull(2)
                            if (arrivalDest != null && destinationPhotoChannel.photo.value == null) {
                                lifecycleScope.launch {
                                    val photo = withContext(Dispatchers.IO) {
                                        runCatching {
                                            airportRepository.getAirportByIata(arrivalDest)?.let { airport ->
                                                airport.municipality?.let { destinationPhotoRepository.fetchDestinationPhoto(it, airport.isoCountry) }
                                            }
                                        }.getOrNull()
                                    }
                                    destinationPhotoChannel.publish(photo)
                                    activeNavController?.navigate(navTarget)
                                }
                                return
                            }
                            activeNavController?.navigate(navTarget)
                        } catch (e: Exception) {
                            Log.e("CesiumGameActivity", "Failed to navigate to $navTarget", e)
                        }
                    }
                    val path = intent.getStringExtra("path")
                    if (!path.isNullOrBlank()) {
                        try {
                            val w = window.decorView.width.coerceAtLeast(1)
                            val h = window.decorView.height.coerceAtLeast(1)
                            val bitmap = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)

                            val saveBitmap = {
                                val file = java.io.File(path)
                                file.parentFile?.mkdirs()
                                file.outputStream().use { out ->
                                    bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
                                }
                                java.io.File("${path}.done").createNewFile()
                                Log.i("CesiumGameActivity", "Captured screen to $path (${bitmap.width}x${bitmap.height})")
                            }

                            if (mSurfaceView.visibility == android.view.View.VISIBLE && mSurfaceView.holder.surface.isValid) {
                                android.view.PixelCopy.request(mSurfaceView, bitmap, { copyResult ->
                                    try {
                                        val canvas = android.graphics.Canvas(bitmap)
                                        composeView.draw(canvas)
                                    } catch (e: Exception) {
                                        Log.w("CesiumGameActivity", "Overlay draw error", e)
                                    }
                                    saveBitmap()
                                }, android.os.Handler(android.os.Looper.getMainLooper()))
                            } else {
                                val canvas = android.graphics.Canvas(bitmap)
                                window.decorView.draw(canvas)
                                saveBitmap()
                            }
                        } catch (e: Exception) {
                            Log.e("CesiumGameActivity", "Failed to capture screen to $path", e)
                        }
                    }
                }
            }, filter, androidx.core.content.ContextCompat.RECEIVER_EXPORTED)
        }
    }

    /** The foreground sync moment, cold start included (docs/shared-challenges.md, Y1). The
     *  syncer debounces it, so a quick app switch costs nothing. */
    override fun onStart() {
        super.onStart()
        sharedChallengeSyncer.requestSync(SyncReason.FOREGROUND)
    }

    override fun onDestroy() {
        appScope.cancel()
        super.onDestroy()
    }

}
