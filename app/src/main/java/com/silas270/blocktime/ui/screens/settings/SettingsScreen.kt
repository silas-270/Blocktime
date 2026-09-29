package com.silas270.blocktime.ui.screens.settings

import com.silas270.blocktime.ui.theme.ScreenGutter
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Spacer
import com.silas270.blocktime.data.model.Airport
import com.silas270.blocktime.data.model.ThemeMode
import com.silas270.blocktime.data.network.ServerState
import com.silas270.blocktime.ui.components.BackTopAppBar
import com.silas270.blocktime.ui.components.ModalButtonRow
import com.silas270.blocktime.ui.components.ModalTitle
import com.silas270.blocktime.ui.components.ScrimCardModal
import com.silas270.blocktime.ui.components.SectionHeader
import com.silas270.blocktime.ui.theme.Haze
import com.silas270.blocktime.ui.theme.Midnight
import com.silas270.blocktime.ui.theme.Spacing
import com.silas270.blocktime.ui.viewmodel.account.AccountViewModel
import com.silas270.blocktime.ui.viewmodel.account.HomeBaseActionResult
import java.util.Locale

/**
 * Settings: theme preference and the Story Mode home-base actions (docs/modes.md). Split out of
 * the Passport (`AccountScreen`) so that screen can stay a read-only trophy case/logbook, and
 * anything that changes app state or app-wide preferences lives here instead - the same
 * [AccountViewModel] backs both (it already owns theme mode and the home-base cooldown/search
 * state), just via its own instance scoped to this destination's back-stack entry.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: AccountViewModel,
    onBackClick: () -> Unit,
    onNavigateHome: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val themeMode by viewModel.themeMode.collectAsState()
    val networkMode by viewModel.networkMode.collectAsState()
    val offlineDataSaverEnabled by viewModel.offlineDataSaverEnabled.collectAsState()
    val onlineFeaturesEnabled by viewModel.onlineFeaturesEnabled.collectAsState()
    val serverState by viewModel.serverState.collectAsState()
    val systemInDarkTheme = isSystemInDarkTheme()
    val isLightMode = when (themeMode) {
        ThemeMode.LIGHT -> true
        ThemeMode.DARK -> false
        ThemeMode.SYSTEM -> !systemInDarkTheme
    }

    var showReturnHomeModal by remember { mutableStateOf(false) }
    var showReturningHomeModal by remember { mutableStateOf(false) }
    var showChangeHomeBase by remember { mutableStateOf(false) }
    var showWelcomeHome by remember { mutableStateOf(false) }
    var showCredits by remember { mutableStateOf(false) }
    var showDisableSharedChallengesConfirm by remember { mutableStateOf(false) }
    var homeBaseSetAirport by remember { mutableStateOf<Airport?>(null) }
    val homeBaseSearchQuery by viewModel.homeBaseSearchQuery.collectAsState()
    val homeBaseSearchResults by viewModel.homeBaseSearchResults.collectAsState()
    val homeBaseSuggestions by viewModel.homeBaseSuggestions.collectAsState()
    val homeBaseActionResult by viewModel.homeBaseActionResult.collectAsState()

    // Same reasoning as the Passport's former copy of this effect: the work runs in
    // viewModelScope, so the result arrives asynchronously and may just as well say the cooldown
    // re-check refused the action, in which case the right behaviour is to show nothing at all.
    LaunchedEffect(homeBaseActionResult) {
        when (val result = homeBaseActionResult) {
            null -> Unit
            is HomeBaseActionResult.ReturnedHome -> {
                showWelcomeHome = true
                viewModel.consumeHomeBaseActionResult()
            }
            is HomeBaseActionResult.HomeBaseSet -> {
                homeBaseSetAirport = result.airport
                viewModel.consumeHomeBaseActionResult()
            }
            HomeBaseActionResult.Ineligible, HomeBaseActionResult.Failed -> {
                viewModel.consumeHomeBaseActionResult()
            }
        }
    }

    val listState = rememberLazyListState()
    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            topBar = {
                BackTopAppBar(title = "SETTINGS", onBackClick = onBackClick, scrolled = listState.canScrollBackward)
            },
            containerColor = Midnight
        ) { paddingValues ->
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                verticalArrangement = Arrangement.spacedBy(20.dp),
                contentPadding = PaddingValues(
                    start = ScreenGutter,
                    end = ScreenGutter,
                    top = Spacing.Small,
                    bottom = Spacing.ExtraLarge
                )
            ) {
                item { SectionHeader(title = "PREFERENCES") }
                item {
                    ThemePreferenceRow(
                        isLightMode = isLightMode,
                        onToggle = { checked ->
                            viewModel.setThemeMode(if (checked) ThemeMode.LIGHT else ThemeMode.DARK)
                        }
                    )
                }
                item {
                    OfflineMapsPreferenceRow(
                        dataSaverEnabled = offlineDataSaverEnabled,
                        networkMode = networkMode,
                        onToggle = viewModel::setOfflineDataSaverEnabled
                    )
                }
                // Absent without a server in the build (docs/shared-challenges.md "Two
                // signals"): a switch that could never do anything is worse than none.
                if (serverState != ServerState.NOT_CONFIGURED) {
                    item {
                        OnlineFeaturesPreferenceRow(
                            enabled = onlineFeaturesEnabled,
                            serverState = serverState,
                            onToggle = { checked ->
                                if (checked) {
                                    viewModel.setOnlineFeaturesEnabled(true)
                                } else {
                                    showDisableSharedChallengesConfirm = true
                                }
                            }
                        )
                    }
                }

                item { SectionHeader(title = "HOME BASE") }
                item {
                    HomeBaseActions(
                        state = uiState,
                        onReturnHomeClick = { showReturnHomeModal = true },
                        onChangeHomeBaseClick = { showChangeHomeBase = true }
                    )
                }

                item { SectionHeader(title = "ABOUT") }
                item {
                    SettingsRow(
                        icon = Icons.Outlined.Info,
                        title = "Credits & licenses",
                        subtitle = "Maps, photos, flight data and 3D models",
                        interaction = Modifier.clickable { showCredits = true }
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                            contentDescription = null,
                            tint = Haze
                        )
                    }
                }
            }
        }

        if (showDisableSharedChallengesConfirm) {
            DeactivateSharedChallengesModal(
                onConfirm = {
                    showDisableSharedChallengesConfirm = false
                    viewModel.setOnlineFeaturesEnabled(false)
                },
                onDismiss = { showDisableSharedChallengesConfirm = false }
            )
        }

        if (showReturnHomeModal) {
            ReturnHomeConfirmModal(
                homeAirportIata = uiState.homeAirportIata,
                currentAirportIata = uiState.currentAirportIata,
                discardsPausedFlightTo = uiState.returnHomeDiscardsFlightTo,
                onConfirm = {
                    showReturnHomeModal = false
                    showReturningHomeModal = true
                },
                onDismiss = { showReturnHomeModal = false }
            )
        }

        // The teleport's outcome is settled the moment this modal appears - it cannot be
        // dismissed and it ends by attempting the teleport - so the globe the Hub will want is
        // rendered during the animation instead of after it. Keyed on Unit inside the `if` so it
        // fires once per showing.
        if (showReturningHomeModal) {
            LaunchedEffect(Unit) { viewModel.prepareReturnHome() }
            ReturningHomeModal(
                onComplete = {
                    viewModel.returnHome()
                    showReturningHomeModal = false
                }
            )
        }

        // Drawn last so it's the topmost sibling, fully covering everything above it.
        if (showChangeHomeBase) {
            ChangeHomeBaseScreen(
                query = homeBaseSearchQuery,
                onQueryChange = { viewModel.onHomeBaseSearchQueryChanged(it) },
                results = homeBaseSearchResults,
                suggestions = homeBaseSuggestions,
                // Close the picker on select, but leave the celebration to the published result -
                // changeHomeBase re-checks the 30-day cooldown and may write nothing at all.
                onAirportSelect = { airport ->
                    viewModel.changeHomeBase(airport)
                    showChangeHomeBase = false
                },
                onBackClick = { showChangeHomeBase = false }
            )
        }

        if (showCredits) {
            CreditsScreen(onBackClick = { showCredits = false })
        }

        // ── Celebrations ─────────────────────────────────────────────────
        // Topmost of all: either can open over the picker above, and both end the flow by handing
        // the pilot back to the Hub rather than returning them here.
        if (showWelcomeHome) {
            val home = uiState.homeAirport
            HomeBaseCelebrationScreen(
                eyebrow = "WELCOME BACK",
                iata = uiState.homeAirportIata,
                airportName = home?.name.orEmpty(),
                locationLine = locationLine(home?.municipality, home?.isoCountry),
                onContinue = {
                    showWelcomeHome = false
                    onNavigateHome()
                },
                // The aircraft settling in above the code - this is an arrival, not a new base,
                // so it no longer borrows the change-home-base tower mark.
                hero = { ReturnHomeHero() }
            )
        }

        homeBaseSetAirport?.let { airport ->
            HomeBaseCelebrationScreen(
                eyebrow = "HOME BASE SET",
                iata = airport.iataCode,
                airportName = airport.name,
                locationLine = locationLine(airport.municipality, airport.isoCountry),
                onContinue = {
                    homeBaseSetAirport = null
                    onNavigateHome()
                },
                inlineHero = { HomeBaseSetHero() }
            )
        }
    }
}


/** "MUNICH · GERMANY". [isoCountry] is a 2-letter code, and the app has no code-to-name table -
 *  `Locale` already ships one, and falls back to blank for anything it doesn't recognise. */
private fun locationLine(city: String?, isoCountry: String?): String {
    val country = isoCountry
        ?.takeIf { it.isNotBlank() }
        ?.let { Locale("", it).getDisplayCountry(Locale.US) }
        ?.takeIf { it.isNotBlank() }
    return listOfNotNull(city?.takeIf { it.isNotBlank() }, country)
        .joinToString(" · ")
        .uppercase(Locale.US)
}

@Composable
private fun DeactivateSharedChallengesModal(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    ScrimCardModal(onScrimTap = onDismiss) {
        ModalTitle("TURN OFF SHARED CHALLENGES?")
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Your crew keeps what you already shared for up to 30 days. Nothing new is sent, and your progress stays on this phone.",
            style = MaterialTheme.typography.bodyMedium,
            color = Haze
        )
        Spacer(modifier = Modifier.height(24.dp))
        ModalButtonRow(
            dismissText = "CANCEL",
            confirmText = "TURN OFF",
            onDismiss = onDismiss,
            onConfirm = onConfirm,
            isDestructive = true
        )
    }
}
