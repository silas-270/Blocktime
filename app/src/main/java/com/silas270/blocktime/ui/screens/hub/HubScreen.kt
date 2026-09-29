package com.silas270.blocktime.ui.screens.hub

import com.silas270.blocktime.ui.theme.LocalDesignScreenSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.EmojiEvents
import androidx.compose.material.icons.outlined.FlightTakeoff
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetScaffoldState
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.graphics.graphicsLayer
import com.silas270.blocktime.ui.components.SheetHandle
import com.silas270.blocktime.ui.components.toggle
import com.silas270.blocktime.util.formatDuration
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.allowHardware
import androidx.compose.ui.platform.LocalContext
import com.silas270.blocktime.data.model.Challenge
import com.silas270.blocktime.data.model.ChallengeType
import com.silas270.blocktime.data.model.PausedFlight
import com.silas270.blocktime.data.model.crewSize
import com.silas270.blocktime.data.model.isShared
import com.silas270.blocktime.data.model.others
import com.silas270.blocktime.data.model.progressFraction
import com.silas270.blocktime.data.model.racePlacement
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.PaddingValues
import com.silas270.blocktime.ui.components.ButtonStyle
import com.silas270.blocktime.ui.components.ButtonVariant
import com.silas270.blocktime.ui.components.CardVariant
import com.silas270.blocktime.ui.components.ChallengeProgressBar
import com.silas270.blocktime.ui.components.DiscardFlightConfirmModal
import com.silas270.blocktime.ui.components.FocusButton
import com.silas270.blocktime.ui.components.FocusCard
import com.silas270.blocktime.ui.components.FocusStatItem
import com.silas270.blocktime.ui.components.OfflineBadge
import com.silas270.blocktime.ui.components.PrimaryActionButton
import com.silas270.blocktime.ui.components.challengeTypeLabel
import com.silas270.blocktime.ui.components.icon
import com.silas270.blocktime.ui.screens.challenges.challengeSubtitle
import com.silas270.blocktime.ui.screens.challenges.ordinalSuffix
import com.silas270.blocktime.ui.theme.Amber
import com.silas270.blocktime.ui.theme.Border
import com.silas270.blocktime.ui.theme.DeepNavy
import com.silas270.blocktime.ui.theme.Haze
import com.silas270.blocktime.ui.theme.Midnight
import com.silas270.blocktime.ui.theme.OffWhite
import com.silas270.blocktime.ui.theme.Slate
import com.silas270.blocktime.ui.theme.Spacing
import com.silas270.blocktime.ui.viewmodel.hub.HubViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HubScreen(
    viewModel: HubViewModel,
    onBookFlightClick: () -> Unit,
    onResumeFlightClick: (flight: PausedFlight) -> Unit,
    onPassportClick: () -> Unit,
    onChallengesClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onContinueChallengeClick: (challengeId: Int) -> Unit
) {
    val currentAirport by viewModel.currentAirport.collectAsState()
    val focusedChallenge by viewModel.focusedChallenge.collectAsState()
    val stats by viewModel.flightStats.collectAsState()
    val pilotName by viewModel.pilotName.collectAsState()
    val routeMapPath by viewModel.routeMapPath.collectAsState()
    val networkMode by viewModel.networkMode.collectAsState()

    // Hub's ViewModel (and its data) is loaded once in init, but the Hub's own back-stack entry
    // survives a popBackStack() from Account/Passport - so returning from there after using
    // docs/modes.md's return-home teleport (a direct currentAirport write, no flight/session)
    // needs an explicit refresh, or the globe/greeting would keep showing the pre-teleport
    // airport until the next full Hub recreation. ON_START (not just first composition) fires
    // both on initial entry and on returning to this screen - same lifecycle-observer pattern
    // InFlightScreen already uses for its own pause/resume handling.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) {
                viewModel.refresh()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val scaffoldState = rememberBottomSheetScaffoldState()
    val isExpanded = scaffoldState.bottomSheetState.currentValue == SheetValue.Expanded

    val pausedFlight by viewModel.pausedFlight.collectAsState()
    var showDiscardFlightConfirm by remember { mutableStateOf(false) }

    // "Book from here": the sheet's BOOK NEW FLIGHT button and a tap on the globe. A paused
    // flight has to be discarded first; a focused challenge books its next leg.
    val onBookNewFlight: () -> Unit = {
        if (pausedFlight != null) {
            showDiscardFlightConfirm = true
        } else if (focusedChallenge != null) {
            onContinueChallengeClick(focusedChallenge!!.id)
        } else {
            onBookFlightClick()
        }
    }
    val sheetScope = rememberCoroutineScope()

    androidx.compose.material3.Scaffold(
        bottomBar = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(DeepNavy)
                    .padding(
                        start = Spacing.Large,
                        end = Spacing.Large,
                        bottom = Spacing.Large,
                        top = Spacing.Medium
                    )
            ) {
              Column {
                // The obvious cue that the Hub is in Route-challenge focus mode: a full card
                // (same style as the Achievements-tab cards) with a progress bar and an explicit
                // exit, stacked above the normal Resume/Book button rather than replacing it.
                focusedChallenge?.let { challenge ->
                    FocusedChallengeCard(
                        challenge = challenge,
                        onExit = { viewModel.exitFocusedChallenge() },
                        modifier = Modifier.padding(bottom = Spacing.Large)
                    )
                }
                if (pausedFlight != null) {
                    val flight = pausedFlight!!
                    PrimaryActionButton(
                        text = "RESUME FLIGHT",
                        modifier = Modifier.heightIn(min = 56.dp),
                        icon = Icons.Outlined.FlightTakeoff,
                        onClick = { onResumeFlightClick(flight) }
                    )
                } else {
                    val focused = focusedChallenge
                    PrimaryActionButton(
                        text = "BOOK A FLIGHT",
                        modifier = Modifier.heightIn(min = 56.dp),
                        icon = Icons.Outlined.FlightTakeoff,
                        onClick = { if (focused != null) onContinueChallengeClick(focused.id) else onBookFlightClick() }
                    )
                }
              }
            }
        }
    ) { innerPadding ->
        BottomSheetScaffold(
            modifier = Modifier.padding(innerPadding),
            scaffoldState = scaffoldState,
            sheetContainerColor = DeepNavy,
            sheetShape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
            sheetDragHandle = {
                SheetHandle(
                    sheetState = scaffoldState.bottomSheetState,
                    topPadding = 12.dp,
                    bottomPadding = 38.dp
                )
            },
            sheetPeekHeight = 191.dp,
            containerColor = Midnight,
            sheetContent = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.Large)
                    .padding(bottom = Spacing.Small)
            ) {
              // The peek block toggles the sheet on tap, like the handle above it - the stats
              // below it were otherwise only reachable by a swipe nothing hinted at.
              Column(
                  modifier = Modifier
                      .fillMaxWidth()
                      .clickable(
                          interactionSource = remember { MutableInteractionSource() },
                          indication = null,
                          onClickLabel = if (isExpanded) "Collapse" else "Show stats"
                      ) { sheetScope.toggle(scaffoldState.bottomSheetState) }
              ) {
                // The pilot's own (generated, renameable) name rather than a rank - ranks are
                // earned per flight on arrival, so a fixed "Captain" here contradicted them.
                Text(
                    text = pilotName?.let { "Welcome back, $it" } ?: "Welcome back",
                    style = MaterialTheme.typography.titleMedium,
                    color = Amber,
                    letterSpacing = 0.5.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(9.dp))
                
                Text(
                    text = currentAirport?.iataCode ?: "---",
                    style = MaterialTheme.typography.displayLarge.copy(
                        fontWeight = FontWeight.Black,
                        fontSize = 44.sp
                    ),
                    color = OffWhite
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = currentAirport?.name ?: "Loading base...",
                    style = MaterialTheme.typography.bodyLarge,
                    color = Haze
                )
              }

                Spacer(modifier = Modifier.height(30.dp))
                HorizontalDivider(color = Border, thickness = 1.dp)
                Spacer(modifier = Modifier.height(30.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    FocusStatItem(value = stats.totalFlights.toString(), label = "FLIGHTS")
                    FocusStatItem(value = formatDuration(stats.totalMinutes), label = "FLIGHT TIME")
                    FocusStatItem(value = stats.airportsVisited.toString(), label = "AIRPORTS")
                }

                // Secondary Button (when any active flight exists, in story mode or route challenge)
                if (pausedFlight != null) {
                    Spacer(modifier = Modifier.height(30.dp))
                    FocusButton(
                        text = "BOOK NEW FLIGHT",
                        onClick = onBookNewFlight,
                        variant = ButtonVariant.Primary,
                        style = ButtonStyle.Outlined,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Midnight)
        ) {
            // Background Globe image. Sized off the screen's own height, not this Box's
            // immediate parent - that parent shrinks whenever the outer Scaffold's bottomBar
            // grows (e.g. the focused-challenge card adds a row above Resume/Book), and sizing
            // off a shrinking container recentered the crop of this fixed-aspect image, reading
            // as the globe getting cut off at the top the moment a challenge is focused.
            val screenHeightDp = LocalDesignScreenSize.current.height
            // Tapping the globe books from here - the arcs are this airport's destinations - so
            // the picture isn't a dead end for anyone who expects a globe to respond. A press
            // shrinks it slightly; no ripple across a photo-like image.
            val globeInteraction = remember { MutableInteractionSource() }
            val globePressed by globeInteraction.collectIsPressedAsState()
            val globeScale by animateFloatAsState(if (globePressed) 0.98f else 1f, label = "globePress")
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(screenHeightDp * 0.775f)
                    .padding(top = 48.dp)
                    .align(Alignment.TopCenter)
                    .then(
                        if (routeMapPath != null) {
                            Modifier.clickable(
                                interactionSource = globeInteraction,
                                indication = null,
                                onClickLabel = "Book a flight from here",
                                onClick = onBookNewFlight
                            )
                        } else {
                            Modifier
                        }
                    )
            ) {
                if (routeMapPath != null) {
                    val context = LocalContext.current
                    AsyncImage(
                        model = ImageRequest.Builder(context)
                            .data(routeMapPath)
                            .allowHardware(false)
                            .build(),
                        contentDescription = "Routes from your airport",
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                scaleX = globeScale
                                scaleY = globeScale
                                // The render leaves ~24 % empty space above the horizon; lifting
                                // it puts the horizon just under the header buttons instead of
                                // a dark band. Tied to the render framing - see RENDER_HEIGHT.
                                translationY = -GlobeLiftFraction * size.height
                            },
                        contentScale = ContentScale.Crop,
                        alignment = Alignment.Center
                    )
                } else {
                    val mapRenderError by viewModel.mapRenderError.collectAsState()
                    if (mapRenderError != null) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            FocusCard(
                                variant = CardVariant.Surface,
                                onClick = { viewModel.retryRenderMap() },
                                horizontalAlignment = Alignment.CenterHorizontally,
                                contentPadding = PaddingValues(Spacing.Large)
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Info,
                                    contentDescription = "Error",
                                    tint = Amber,
                                    modifier = Modifier.size(32.dp)
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = mapRenderError!!,
                                    color = OffWhite,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Text(
                                    text = "Tap to retry",
                                    color = Amber,
                                    style = MaterialTheme.typography.labelMedium
                                )
                            }
                        }
                    }
                }
            }

            // Top Header & Navigation (outside solid container so globe is behind). Settings sits
            // alone on the left so it doesn't compete with the Challenges/Account pair - those two
            // are the frequent, session-shaped destinations; Settings is the occasional one.
            // Each button is a 40dp tile inside a 48dp touch area (see HubHeaderIconButton), so
            // every padding here is 4dp less than the visual spacing it produces.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(
                        start = Spacing.Large - HeaderTouchInset,
                        end = Spacing.Large - HeaderTouchInset,
                        top = Spacing.Large - HeaderTouchInset,
                        bottom = Spacing.Medium - HeaderTouchInset
                    ),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Settings: theme preference and the Story Mode home-base actions.
                HubHeaderIconButton(
                    icon = Icons.Outlined.Settings,
                    contentDescription = "Settings",
                    onClick = onSettingsClick
                )

                // Challenges and Passport, visually Spacing.Small apart - exactly the two touch
                // insets, so the touch areas meet without overlapping.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Only visible while offline (no connection, or data saver on in Settings).
                    if (networkMode.isOffline) {
                        OfflineBadge(mode = networkMode)
                        Spacer(modifier = Modifier.width(Spacing.Small - HeaderTouchInset))
                    }

                    // Challenges: Free Mode entry, the challenge slots, and achievements
                    HubHeaderIconButton(
                        icon = Icons.Outlined.EmojiEvents,
                        contentDescription = "Challenges",
                        onClick = onChallengesClick
                    )

                    Spacer(modifier = Modifier.width(Spacing.Small - HeaderTouchInset * 2))

                    // Pilot Passport - named for the screen it opens
                    HubHeaderIconButton(
                        icon = Icons.Outlined.Person,
                        contentDescription = "Passport",
                        onClick = onPassportClick
                    )
                }
            }
        }
    }
    }

    if (showDiscardFlightConfirm && pausedFlight != null) {
        val flight = pausedFlight!!
        val focused = focusedChallenge
        DiscardFlightConfirmModal(
            flight = flight,
            onConfirm = {
                showDiscardFlightConfirm = false
                if (focused != null) onContinueChallengeClick(focused.id) else onBookFlightClick()
            },
            onDismiss = { showDiscardFlightConfirm = false }
        )
    }
}

/** Share of the globe image's height it is lifted by (see the globe's graphicsLayer). */
private const val GlobeLiftFraction = 0.12f

/** How far each side of a [HubHeaderIconButton]'s 48dp touch area extends past its 40dp tile. */
private val HeaderTouchInset = 4.dp

/**
 * One of the Hub header's 40dp icon tiles, with a 48dp touch area around it. The ripple is drawn
 * on the tile only, so it looks exactly as before; the extra 4dp each side is invisible but
 * tappable, and is what accessibility services see as the button's bounds.
 */
@Composable
private fun HubHeaderIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .size(40.dp + HeaderTouchInset * 2)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                role = Role.Button,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(DeepNavy)
                .indication(interactionSource, ripple()),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = OffWhite,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

/**
 * The Hub's cue for Route-challenge focus mode - same card language as the Achievements-tab cards
 * (rounded corners, a colored container, progress bar) but in [Slate] rather than [DeepNavy] so
 * it reads as a distinct, temporary mode rather than just another list item. Purely informational
 * (like an achievement card) aside from the exit "x" - continuing/resuming lives on the Resume/
 * Book button below it instead of on the card itself.
 */
@Composable
private fun FocusedChallengeCard(challenge: Challenge, onExit: () -> Unit, modifier: Modifier = Modifier) {
    FocusCard(
        modifier = modifier.fillMaxWidth(),
        variant = CardVariant.Secondary,
        border = BorderStroke(1.dp, Border.copy(alpha = 0.6f)),
        contentPadding = PaddingValues(horizontal = Spacing.Medium, vertical = 12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    imageVector = challenge.icon(),
                    contentDescription = null,
                    tint = Amber,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = challenge.name,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                    color = OffWhite,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(modifier = Modifier.width(Spacing.Small))
            // 24dp visually, but not 24dp to touch: Compose expands any pointer target smaller
            // than ViewConfiguration.minimumTouchTargetSize to 48dp for touch input. Padding it
            // out to 48dp explicitly would make the whole card 24dp taller.
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(DeepNavy.copy(alpha = 0.7f))
                    .clickable(onClick = onExit),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Outlined.Close,
                    contentDescription = "Exit challenge",
                    tint = Haze,
                    modifier = Modifier.size(13.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        ChallengeProgressBar(
            progress = challenge.progressFraction(),
            trackColor = DeepNavy,
            fillColor = Amber,
            modifier = Modifier.fillMaxWidth(),
            height = 6.dp
        )

        Spacer(modifier = Modifier.height(6.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = challengeSubtitle(challenge),
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                color = Haze
            )
            val pct = (challenge.progressFraction() * 100).toInt()
            Text(
                text = "$pct%",
                style = MaterialTheme.typography.labelSmall.copy(
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                ),
                color = if (pct > 0) Amber else Haze.copy(alpha = 0.6f)
            )
        }

        // A race says where the pilot stands and who is ahead (docs/shared-challenges.md "Per
        // type"); a pool's team bar has no leader to name, so only a shared route gets the line.
        raceStandingLine(challenge)?.let { line ->
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = line,
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                color = Haze
            )
        }
    }
}

/**
 * "2nd of 3 · Anna leads", or "1st of 3 · you lead", for a shared route with a crew in its
 * cache; null for everything else. The leader is whoever of the crew still in the race has the
 * most route progress, self's from the row, so it agrees with [racePlacement].
 */
private fun raceStandingLine(challenge: Challenge): String? {
    if (!challenge.isShared() || challenge.type != ChallengeType.ROUTE) return null
    val place = challenge.racePlacement() ?: return null
    val others = challenge.others()
    val crewSize = challenge.crewSize()
    if (crewSize < 2) return null
    val bestOther = others.maxByOrNull { it.routeProgress }
    val leader = if (bestOther == null || challenge.progressFraction() >= bestOther.routeProgress) {
        "you lead"
    } else {
        "${bestOther.username.ifBlank { bestOther.userCode }} leads"
    }
    return "$place${ordinalSuffix(place)} of $crewSize · $leader"
}

@Deprecated("Use FocusStatItem instead", ReplaceWith("FocusStatItem(value, label)", "com.silas270.blocktime.ui.components.FocusStatItem"))
@Composable
private fun StatItem(value: String, label: String) {
    FocusStatItem(value = value, label = label)
}
