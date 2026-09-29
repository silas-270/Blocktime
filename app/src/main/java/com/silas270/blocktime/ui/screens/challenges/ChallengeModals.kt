package com.silas270.blocktime.ui.screens.challenges

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.FlightTakeoff
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.layout
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.silas270.blocktime.data.model.Challenge
import com.silas270.blocktime.data.model.ChallengeStatus
import com.silas270.blocktime.data.model.ChallengeType
import com.silas270.blocktime.data.model.CuratedChallengeSets
import com.silas270.blocktime.data.model.ParticipantSnapshot
import com.silas270.blocktime.data.model.PausedFlight
import com.silas270.blocktime.data.model.PredefinedRoute
import com.silas270.blocktime.data.model.PredefinedRouteCatalog
import com.silas270.blocktime.data.model.ROOM_CODE_ALPHABET
import com.silas270.blocktime.data.model.ROOM_CODE_LENGTH
import com.silas270.blocktime.data.model.RoomDefinition
import com.silas270.blocktime.data.model.RoomState
import com.silas270.blocktime.data.model.SharedOutcome
import com.silas270.blocktime.data.model.crew
import com.silas270.blocktime.data.model.displayProgressFraction
import com.silas270.blocktime.data.model.isShared
import com.silas270.blocktime.data.model.predefinedRoute
import com.silas270.blocktime.data.model.progressSegments
import com.silas270.blocktime.data.model.racePlacement
import com.silas270.blocktime.data.model.resolveSetMemberProgress
import com.silas270.blocktime.data.model.CuratedChallengeCatalog
import com.silas270.blocktime.data.model.progressFraction
import com.silas270.blocktime.data.repository.JoinResult
import com.silas270.blocktime.data.repository.shareIneligibility
import com.silas270.blocktime.domain.OnlineFeatureAvailability
import com.silas270.blocktime.domain.onlineAvailability
import com.silas270.blocktime.domain.onlineHint
import androidx.compose.foundation.layout.PaddingValues
import com.silas270.blocktime.ui.components.BadgeSize
import com.silas270.blocktime.ui.components.BadgeStyle
import com.silas270.blocktime.ui.components.BadgeVariant
import com.silas270.blocktime.ui.components.ButtonSize
import com.silas270.blocktime.ui.components.ButtonStyle
import com.silas270.blocktime.ui.components.ButtonVariant
import com.silas270.blocktime.ui.components.CaptionLabel
import com.silas270.blocktime.ui.components.CardVariant
import com.silas270.blocktime.ui.components.FocusBadge
import com.silas270.blocktime.ui.components.FocusButton
import com.silas270.blocktime.ui.components.FocusCard
import com.silas270.blocktime.ui.components.FocusInfoRow
import com.silas270.blocktime.ui.components.ChallengeProgressBar
import com.silas270.blocktime.ui.components.DestructiveActionButton
import com.silas270.blocktime.ui.components.ModalTitle
import com.silas270.blocktime.ui.components.PrimaryActionButton
import com.silas270.blocktime.ui.components.SecondaryActionButton
import com.silas270.blocktime.ui.components.ScrimCardModal
import com.silas270.blocktime.ui.components.SectionHeader
import com.silas270.blocktime.ui.components.SegmentedProgressBar
import com.silas270.blocktime.ui.components.SetMemberChecklist
import com.silas270.blocktime.ui.components.challengeTypeDescription
import com.silas270.blocktime.ui.components.challengeTypeIcon
import com.silas270.blocktime.ui.components.challengeTypeLabel
import com.silas270.blocktime.ui.components.icon
import com.silas270.blocktime.ui.theme.Amber
import com.silas270.blocktime.ui.theme.Border
import com.silas270.blocktime.ui.theme.DeepNavy
import com.silas270.blocktime.ui.theme.Haze
import com.silas270.blocktime.ui.theme.OffWhite
import com.silas270.blocktime.ui.theme.Slate
import com.silas270.blocktime.ui.theme.Spacing
import com.silas270.blocktime.ui.theme.participantColor

import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import com.silas270.blocktime.data.model.Airport
import com.silas270.blocktime.ui.screens.flightsearch.OriginSearchPanel
import com.silas270.blocktime.ui.viewmodel.challenges.ChallengesViewModel
import com.silas270.blocktime.ui.viewmodel.challenges.RoomLookupState
import com.silas270.blocktime.ui.viewmodel.challenges.ShareUiState
import com.silas270.blocktime.ui.viewmodel.challenges.formatKm
import java.time.LocalDate
import java.util.Locale

private sealed interface PickerStep {
    object TypeGrid : PickerStep
    data class CuratedList(val type: ChallengeType) : PickerStep
    data class CreateCustom(val type: ChallengeType) : PickerStep

    /** The "Have a code?" preview of a room found by the look-up (docs/shared-challenges.md, J4). */
    data class JoinPreview(val room: RoomState) : PickerStep
}

/**
 * Opened by tapping an empty slot:
 * 1. Shows a 2x2 grid of challenge types, and under it the "Have a code?" field that joins a
 *    shared challenge (docs/shared-challenges.md "Joining"), present only while sharing is on.
 * 2. Selecting a type lists the curated challenges for that type, with a "Create your own" button
 *    at the bottom (for Route, Distance, and Streak).
 * 3. Tapping "Create your own" transitions directly within the modal to that type's dedicated
 *    custom creation form.
 * 4. A successful look-up transitions to the room's preview, whose JOIN is published as a start,
 *    so the same effect that closes the picker after a start closes it after a join.
 */
@Composable
internal fun ChallengePickerModal(
    viewModel: ChallengesViewModel,
    onDismiss: () -> Unit
) {
    var step by remember { mutableStateOf<PickerStep>(PickerStep.TypeGrid) }
    val serverState by viewModel.serverState.collectAsState()
    val roomLookup by viewModel.roomLookup.collectAsState()
    val availability = onlineAvailability(serverState)

    // A found room opens its preview. The preview keeps its room while the join runs or fails,
    // so a failed join stays on the preview with its reason rather than falling back to the grid.
    LaunchedEffect(roomLookup) {
        val lookup = roomLookup
        if (lookup is RoomLookupState.Found) step = PickerStep.JoinPreview(lookup.room)
    }

    // One level up the wizard - what the in-card back arrows do, and (below) what system back does.
    fun stepBack() {
        when (val current = step) {
            PickerStep.TypeGrid -> Unit
            is PickerStep.CuratedList -> step = PickerStep.TypeGrid
            is PickerStep.CreateCustom -> {
                viewModel.clearRouteSearch()
                step = PickerStep.CuratedList(current.type)
            }
            is PickerStep.JoinPreview -> {
                viewModel.clearRoomLookup()
                step = PickerStep.TypeGrid
            }
        }
    }

    // A scrim tap still closes the whole picker, the same as every other ScrimCardModal: tapping
    // outside the card is an explicit "not now". System back is different - it has to agree with the
    // back arrow on screen, which steps back one level, so it only closes from the first step.
    ScrimCardModal(onScrimTap = {
        viewModel.clearRouteSearch()
        viewModel.clearRoomLookup()
        onDismiss()
    }) {
        // Composed after ScrimCardModal's own BackHandler, so it takes priority while enabled.
        BackHandler(enabled = step != PickerStep.TypeGrid) { stepBack() }

        when (val current = step) {
            PickerStep.TypeGrid -> {
                ModalTitle("START A CHALLENGE")
                Spacer(modifier = Modifier.height(Spacing.Small))
                Text(
                    text = "Select a challenge type to explore:",
                    style = MaterialTheme.typography.bodySmall,
                    color = Haze
                )
                Spacer(modifier = Modifier.height(Spacing.Medium))

                // 2x2 Grid of Challenge Types
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        ChallengeTypeCard(
                            type = ChallengeType.ROUTE,
                            modifier = Modifier.weight(1f),
                            onClick = { step = PickerStep.CuratedList(ChallengeType.ROUTE) }
                        )
                        ChallengeTypeCard(
                            type = ChallengeType.SET_COMPLETION,
                            modifier = Modifier.weight(1f),
                            onClick = { step = PickerStep.CuratedList(ChallengeType.SET_COMPLETION) }
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        ChallengeTypeCard(
                            type = ChallengeType.DISTANCE,
                            modifier = Modifier.weight(1f),
                            onClick = { step = PickerStep.CuratedList(ChallengeType.DISTANCE) }
                        )
                        ChallengeTypeCard(
                            type = ChallengeType.STREAK,
                            modifier = Modifier.weight(1f),
                            onClick = { step = PickerStep.CuratedList(ChallengeType.STREAK) }
                        )
                    }
                }

                // Absent, not dimmed, without sharing (J1): the pilot has not asked for anything
                // online, so nothing online is offered.
                if (availability != OnlineFeatureAvailability.HIDDEN) {
                    Spacer(modifier = Modifier.height(Spacing.Medium))
                    HorizontalDivider(color = Border.copy(alpha = 0.4f))
                    Spacer(modifier = Modifier.height(Spacing.Medium))
                    JoinRoomRow(
                        availability = availability,
                        lookup = roomLookup,
                        onLookUp = viewModel::lookUpRoom
                    )
                }
            }

            is PickerStep.JoinPreview -> {
                JoinPreviewStep(
                    room = current.room,
                    availability = availability,
                    lookup = roomLookup,
                    onBack = { stepBack() },
                    onJoin = { viewModel.joinRoom(current.room.code) }
                )
            }

            is PickerStep.CuratedList -> {
                val currentType = current.type
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    PickerBackButton(contentDescription = "Back to types", onClick = { stepBack() })
                    ModalTitle(challengeTypeLabel(currentType))
                }
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = challengeTypeDescription(currentType),
                    style = MaterialTheme.typography.bodySmall,
                    color = Haze
                )
                Spacer(modifier = Modifier.height(Spacing.Medium))

                // Curated challenges for this type
                val templates = CuratedChallengeCatalog.ALL.filter { it.type == currentType }
                Column(
                    modifier = Modifier
                        .heightIn(max = 280.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (templates.isEmpty()) {
                        Text(
                            text = "No curated challenges available for this type.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Haze,
                            modifier = Modifier.padding(vertical = Spacing.Medium)
                        )
                    } else {
                        templates.forEach { template ->
                            FocusCard(
                                modifier = Modifier.fillMaxWidth(),
                                variant = CardVariant.Secondary,
                                shape = RoundedCornerShape(14.dp),
                                contentPadding = PaddingValues(Spacing.Medium),
                                onClick = { viewModel.startCurated(template.catalogId) }
                            ) {
                                Column(modifier = Modifier.fillMaxWidth()) {
                                    Text(
                                        text = template.name,
                                        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold),
                                        color = OffWhite
                                    )
                                    Text(
                                        text = template.description,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Haze
                                    )
                                }
                            }
                        }
                    }
                }

                // Create your own button (only for non-set-completion challenges)
                if (currentType != ChallengeType.SET_COMPLETION) {
                    Spacer(modifier = Modifier.height(Spacing.Medium))
                    HorizontalDivider(color = Border.copy(alpha = 0.4f))
                    Spacer(modifier = Modifier.height(Spacing.Small))

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .clickable { step = PickerStep.CreateCustom(currentType) }
                            .padding(vertical = Spacing.Small, horizontal = Spacing.Small),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Add,
                            contentDescription = null,
                            tint = Amber,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(Spacing.Small))
                        Text(
                            text = "Create your own…",
                            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold),
                            color = Amber
                        )
                    }
                }
            }

            is PickerStep.CreateCustom -> {
                val currentType = current.type
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    PickerBackButton(contentDescription = "Back to list", onClick = { stepBack() })
                    ModalTitle("CUSTOM ${challengeTypeLabel(currentType)}")
                }
                Spacer(modifier = Modifier.height(Spacing.Medium))

                when (currentType) {
                    ChallengeType.ROUTE -> CustomRouteModalForm(
                        viewModel = viewModel,
                        onCreate = viewModel::startCustomRoute
                    )
                    ChallengeType.DISTANCE -> CustomDistanceModalForm(
                        onCreate = viewModel::startCustomDistance
                    )
                    ChallengeType.STREAK -> CustomStreakModalForm(
                        onCreate = viewModel::startCustomStreak
                    )
                    ChallengeType.SET_COMPLETION -> Unit
                }
            }
        }
    }
}

/**
 * "Have a code?" under the type grid (docs/shared-challenges.md "Joining", J2, J3, J7 to J12).
 * The field takes only the room alphabet in upper case and stops at six characters, so what is
 * typed is a code or nothing; the button and the search key both look it up. Below, one caption
 * says why the field is dimmed or why the last look-up failed.
 */
@Composable
private fun JoinRoomRow(
    availability: OnlineFeatureAvailability,
    lookup: RoomLookupState,
    onLookUp: (String) -> Unit
) {
    var code by remember { mutableStateOf("") }
    val online = availability == OnlineFeatureAvailability.ENABLED
    val canLookUp = online && code.length == ROOM_CODE_LENGTH && lookup !is RoomLookupState.Loading

    CaptionLabel(text = "HAVE A CODE?")
    Spacer(modifier = Modifier.height(Spacing.Small))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Spacing.Small),
        verticalAlignment = Alignment.CenterVertically
    ) {
        OutlinedTextField(
            value = code,
            onValueChange = { raw ->
                code = raw.uppercase(Locale.US).filter { it in ROOM_CODE_ALPHABET }.take(ROOM_CODE_LENGTH)
            },
            placeholder = { Text("ABC234", color = Haze.copy(alpha = 0.5f)) },
            singleLine = true,
            enabled = online,
            textStyle = MaterialTheme.typography.bodyLarge.copy(
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp
            ),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Ascii,
                capitalization = KeyboardCapitalization.Characters,
                imeAction = ImeAction.Search
            ),
            keyboardActions = KeyboardActions(onSearch = { if (canLookUp) onLookUp(code) }),
            modifier = Modifier.weight(1f),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = Slate,
                unfocusedContainerColor = DeepNavy,
                disabledContainerColor = DeepNavy,
                cursorColor = Amber,
                focusedBorderColor = Amber,
                unfocusedBorderColor = Border.copy(alpha = 0.3f),
                disabledBorderColor = Border.copy(alpha = 0.3f),
                focusedTextColor = OffWhite,
                unfocusedTextColor = OffWhite,
                disabledTextColor = Haze,
                disabledPlaceholderColor = Haze.copy(alpha = 0.3f)
            )
        )
        FocusButton(
            text = "LOOK UP",
            onClick = { onLookUp(code) },
            variant = ButtonVariant.Secondary,
            style = ButtonStyle.Filled,
            size = ButtonSize.Compact,
            enabled = canLookUp,
            fillMaxWidth = false
        )
    }
    val hint = onlineHint(availability)
        ?: (lookup as? RoomLookupState.Error)?.let { joinErrorText(it.reason) }
    if (hint != null) {
        Spacer(modifier = Modifier.height(Spacing.ExtraSmall))
        CaptionLabel(text = hint)
    }
}

/** The join's reasons in the pilot's words (docs/shared-challenges.md "Joining"). */
private fun joinErrorText(reason: JoinResult): String = when (reason) {
    JoinResult.NotFound -> "No challenge with that code"
    JoinResult.RoomClosed -> "This challenge is already over"
    JoinResult.RaceLocked -> "The race has already started"
    JoinResult.RoomFull -> "This crew is full"
    JoinResult.UnknownTemplate -> "Update Blocktime to join this challenge"
    is JoinResult.AlreadyJoined -> "You are already in this challenge"
    JoinResult.AlreadyFinished -> "You already finished this challenge"
    is JoinResult.Unavailable -> "Server not reachable, try again"
    // Both are published as a start result, never as a look-up error; named here so the `when`
    // stays exhaustive when the result set grows.
    JoinResult.CapReached -> "Your challenge slots are full"
    is JoinResult.Joined -> "Joined"
}

/**
 * The room as the server holds it, before the pilot commits (J4): what it is, what it takes, who
 * is in it, and the two warnings a join can carry. A race that has started cannot be joined (J8),
 * so the button is dimmed; a running streak can, but joining drops the group minimum to zero
 * (J9), so it warns instead.
 */
@Composable
private fun JoinPreviewStep(
    room: RoomState,
    availability: OnlineFeatureAvailability,
    lookup: RoomLookupState,
    onBack: () -> Unit,
    onJoin: () -> Unit
) {
    val definition = room.definition
    val present = room.participants.filter { !it.left }
    val raceStarted = definition.type == ChallengeType.ROUTE &&
        present.any { it.routeProgress > 0f || it.legIndex > 0 }
    val streakRunning = definition.type == ChallengeType.STREAK && present.any { it.streakDays > 0 }
    val today = remember { LocalDate.now() }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        PickerBackButton(contentDescription = "Back to types", onClick = onBack)
        ModalTitle(definition.name.uppercase(Locale.US))
    }
    Spacer(modifier = Modifier.height(Spacing.Small))
    FocusBadge(
        text = challengeTypeLabel(definition.type),
        variant = BadgeVariant.Primary,
        style = BadgeStyle.Translucent,
        size = BadgeSize.Compact
    )
    if (definition.description.isNotBlank()) {
        Spacer(modifier = Modifier.height(Spacing.Small))
        Text(
            text = definition.description,
            style = MaterialTheme.typography.bodySmall,
            color = Haze
        )
    }
    Spacer(modifier = Modifier.height(Spacing.Medium))
    FocusInfoRow(label = "TARGET", value = roomTargetLine(definition))

    Spacer(modifier = Modifier.height(Spacing.Medium))
    CrewSection(lines = room.crewLines(today), code = null)

    Spacer(modifier = Modifier.height(Spacing.Medium))
    val joinable = !raceStarted && availability == OnlineFeatureAvailability.ENABLED && lookup !is RoomLookupState.Loading
    FocusButton(
        text = "JOIN CHALLENGE",
        onClick = onJoin,
        variant = ButtonVariant.Primary,
        style = ButtonStyle.Filled,
        size = ButtonSize.Compact,
        enabled = joinable
    )
    val hint = when {
        raceStarted -> "The race has already started"
        lookup is RoomLookupState.Error -> joinErrorText(lookup.reason)
        streakRunning -> "Joining resets the group streak"
        else -> onlineHint(availability)
    }
    if (hint != null) {
        Spacer(modifier = Modifier.height(Spacing.ExtraSmall))
        CaptionLabel(text = hint)
    }
}

/** "STR → PEK · 5 legs" / "6 members" / "6,200 mi" / "7 days": what the room is asking for. */
private fun roomTargetLine(definition: RoomDefinition): String = when (definition.type) {
    ChallengeType.ROUTE ->
        definition.predefinedRouteId
            ?.let { PredefinedRouteCatalog.find(it) }
            ?.let { "${it.waypoints.first()} → ${it.waypoints.last()} · ${it.legCount} legs" }
            ?: "${definition.originIata ?: "?"} → ${definition.destIata ?: "?"}"
    ChallengeType.SET_COMPLETION -> {
        val members = definition.setCatalogId?.let { CuratedChallengeSets.find(it) }?.members?.size ?: 0
        "$members members"
    }
    ChallengeType.DISTANCE -> formatKm(definition.targetDistanceKm ?: 0.0)
    ChallengeType.STREAK -> "${definition.targetDays ?: 0} days"
}

/**
 * The picker's in-card back arrow. 48dp to touch, but the pressed highlight stays the old 32dp
 * rounded square. The 48dp box is pulled 8dp left (into the card's padding) and reports 8dp less
 * width, so the arrow and the title after it sit exactly where they did with the old 32dp box and
 * its 8dp spacer.
 */
@Composable
private fun PickerBackButton(contentDescription: String, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .layout { measurable, constraints ->
                val placeable = measurable.measure(constraints)
                val pullPx = 8.dp.roundToPx()
                layout(placeable.width - pullPx, placeable.height) { placeable.place(-pullPx, 0) }
            }
            .size(48.dp)
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(RoundedCornerShape(8.dp))
                .indication(interactionSource, LocalIndication.current),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                contentDescription = contentDescription,
                tint = Amber,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
private fun ChallengeTypeCard(
    type: ChallengeType,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    FocusCard(
        modifier = modifier,
        variant = CardVariant.Secondary,
        shape = RoundedCornerShape(16.dp),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 14.dp),
        onClick = onClick,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        FocusBadge(
            text = challengeTypeLabel(type),
            variant = BadgeVariant.Primary,
            style = BadgeStyle.Translucent,
            size = BadgeSize.Compact
        )
        Spacer(modifier = Modifier.height(10.dp))
        Icon(
            imageVector = challengeTypeIcon(type),
            contentDescription = null,
            tint = Amber,
            modifier = Modifier.size(36.dp)
        )
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = challengeTypeDescription(type),
            style = MaterialTheme.typography.bodySmall.copy(
                fontSize = 11.sp,
                lineHeight = 14.sp
            ),
            color = Haze,
            textAlign = TextAlign.Center,
            minLines = 3
        )
    }
}

@Composable
private fun CustomRouteModalForm(
    viewModel: ChallengesViewModel,
    onCreate: (Airport, Airport) -> Unit
) {
    val originQuery by viewModel.originQuery.collectAsState()
    val originResults by viewModel.originResults.collectAsState()
    val destQuery by viewModel.destQuery.collectAsState()
    val destResults by viewModel.destResults.collectAsState()

    var pickedOrigin by remember { mutableStateOf<Airport?>(null) }
    var pickedDest by remember { mutableStateOf<Airport?>(null) }

    Column(modifier = Modifier.fillMaxWidth()) {
        when {
            pickedOrigin == null -> {
                Text(
                    text = "Pick a departure airport.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Haze
                )
                Spacer(modifier = Modifier.height(Spacing.Small))
                Box(modifier = Modifier.height(280.dp)) {
                    OriginSearchPanel(
                        query = originQuery,
                        onQueryChange = viewModel::onOriginQueryChanged,
                        results = originResults,
                        onAirportSelect = { pickedOrigin = it },
                        caption = "DEPARTURE AIRPORT",
                        placeholder = "Search origin airport…"
                    )
                }
            }

            pickedDest == null -> {
                Text(
                    text = "Departing ${pickedOrigin!!.iataCode} (${pickedOrigin!!.municipality}) - now pick a destination.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Haze
                )
                Spacer(modifier = Modifier.height(Spacing.Small))
                Box(modifier = Modifier.height(280.dp)) {
                    OriginSearchPanel(
                        query = destQuery,
                        onQueryChange = viewModel::onDestQueryChanged,
                        results = destResults,
                        onAirportSelect = { pickedDest = it },
                        caption = "DESTINATION AIRPORT",
                        placeholder = "Search destination airport…"
                    )
                }
            }

            pickedOrigin!!.iataCode == pickedDest!!.iataCode -> {
                Text(
                    text = "Origin and destination can't be the same airport.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Haze
                )
                Spacer(modifier = Modifier.height(Spacing.Medium))
                PrimaryActionButton(
                    text = "PICK A DIFFERENT DESTINATION",
                    size = ButtonSize.Compact
                ) { pickedDest = null }
            }

            else -> {
                val origin = pickedOrigin!!
                val dest = pickedDest!!
                FocusInfoRow(
                    label = "ORIGIN",
                    value = "${origin.municipality} (${origin.iataCode})"
                )
                FocusInfoRow(
                    label = "DESTINATION",
                    value = "${dest.municipality} (${dest.iataCode})"
                )
                Spacer(modifier = Modifier.height(Spacing.Medium))
                PrimaryActionButton(
                    text = "START CHALLENGE",
                    size = ButtonSize.Compact
                ) { onCreate(origin, dest) }
                Spacer(modifier = Modifier.height(Spacing.Small))
                // Styled like "Create your own…" - bold Amber is this picker's inline-link look.
                // In Haze it read as one more caption, not something you could tap.
                Text(
                    text = "Change destination",
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                    color = Amber,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { pickedDest = null }
                        .padding(vertical = Spacing.Small)
                )
            }
        }
    }
}

@Composable
private fun CustomDistanceModalForm(onCreate: (Double) -> Unit) {
    var customText by remember { mutableStateOf("") }
    val target = customText.toDoubleOrNull() ?: 0.0

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "Enter a custom target distance to fly in miles.",
            style = MaterialTheme.typography.bodySmall,
            color = Haze
        )
        Spacer(modifier = Modifier.height(Spacing.Medium))
        OutlinedTextField(
            value = customText,
            onValueChange = { customText = it.filter(Char::isDigit) },
            label = { Text("Target distance (mi)") },
            placeholder = { Text("e.g. 10000", color = Haze.copy(alpha = 0.5f)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = Slate,
                unfocusedContainerColor = DeepNavy,
                cursorColor = Amber,
                focusedBorderColor = Amber,
                unfocusedBorderColor = Border.copy(alpha = 0.3f),
                focusedTextColor = OffWhite,
                unfocusedTextColor = OffWhite
            )
        )
        Spacer(modifier = Modifier.height(Spacing.Large))
        PrimaryActionButton(
            text = if (target > 0) "START CHALLENGE (${String.format(java.util.Locale.US, "%,.0f mi", target)})" else "START CHALLENGE",
            size = ButtonSize.Compact,
            enabled = target > 0
        ) {
            // Typed in miles, stored in km like every other distance in the database.
            onCreate(com.silas270.blocktime.util.milesToKm(target))
        }
    }
}

@Composable
private fun CustomStreakModalForm(onCreate: (Int) -> Unit) {
    var customText by remember { mutableStateOf("") }
    val target = customText.toIntOrNull() ?: 0

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "Enter the number of consecutive days you want to fly. Miss a day and the streak resets.",
            style = MaterialTheme.typography.bodySmall,
            color = Haze
        )
        Spacer(modifier = Modifier.height(Spacing.Medium))
        OutlinedTextField(
            value = customText,
            onValueChange = { customText = it.filter(Char::isDigit) },
            label = { Text("Target days") },
            placeholder = { Text("e.g. 7", color = Haze.copy(alpha = 0.5f)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = Slate,
                unfocusedContainerColor = DeepNavy,
                cursorColor = Amber,
                focusedBorderColor = Amber,
                unfocusedBorderColor = Border.copy(alpha = 0.3f),
                focusedTextColor = OffWhite,
                unfocusedTextColor = OffWhite
            )
        )
        Spacer(modifier = Modifier.height(Spacing.Large))
        PrimaryActionButton(
            text = if (target > 0) "START CHALLENGE ($target DAYS)" else "START CHALLENGE",
            size = ButtonSize.Compact,
            enabled = target > 0
        ) {
            onCreate(target)
        }
    }
}

/**
 * Opened by tapping a filled slot. Route challenges get a "continue" action (they need an actual
 * scoped flight); Distance and Set-completion credit passively from any eligible flight, so for
 * those there is nothing to continue - only progress to read and the option to abandon.
 *
 * Shared rows (docs/shared-challenges.md "Sharing", S1 to S7) add the crew: a segmented team bar
 * for a pool, the crew list with each pilot's figure, the room code with copy and share, and a
 * line saying how fresh the crew's numbers are. An unshared, fresh, active row offers "SHARE
 * CHALLENGE" while sharing is on; the modal stays open and re-renders with the code once the row
 * updates through the slot flow. A terminal row is read-only (A6): the presentation is the only
 * way out, so there is nothing to continue, pause or abandon, only CLOSE.
 *
 * Holds no state of its own: [challenge] is resolved by the screen from the live slot list on
 * every composition (Y11), so a sync that changes the row changes this modal.
 */
@Composable
internal fun ChallengeInfoModal(
    challenge: Challenge,
    isFocused: Boolean,
    availability: OnlineFeatureAvailability,
    shareState: ShareUiState,
    syncedAgo: String?,
    onContinue: () -> Unit,
    onPause: () -> Unit,
    onAbandon: () -> Unit,
    onShare: () -> Unit,
    onCopyCode: (String) -> Unit,
    onShareCode: (String) -> Unit,
    onDismiss: () -> Unit
) {
    ScrimCardModal(onScrimTap = onDismiss) {
        Text(
            text = challenge.name,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            color = OffWhite
        )
        if (challenge.description.isNotBlank()) {
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = challenge.description,
                style = MaterialTheme.typography.bodySmall,
                color = Haze
            )
        }

        val shared = challenge.isShared()
        val active = challenge.status == ChallengeStatus.ACTIVE
        // A pool's bar is split by who earned what; a race and a solo row keep the plain bar.
        // Without a cache the segments are empty and the plain bar shows the team fraction,
        // which then collapses to the pilot's own.
        val segments = if (shared && challenge.type != ChallengeType.ROUTE) challenge.progressSegments() else emptyList()
        Spacer(modifier = Modifier.height(Spacing.Medium))
        if (segments.isNotEmpty()) {
            SegmentedProgressBar(segments = segments, height = 12.dp)
        } else {
            ChallengeProgressBar(progress = challenge.displayProgressFraction(), height = 12.dp)
        }
        Spacer(modifier = Modifier.height(Spacing.Medium))

        FocusInfoRow(
            label = "PROGRESS",
            value = "${(challenge.displayProgressFraction() * 100).toInt()}%"
        )
        FocusInfoRow(
            label = "STATUS",
            value = challengeSubtitle(challenge)
        )

        val setMembers = challenge.resolveSetMemberProgress()
        if (!setMembers.isNullOrEmpty()) {
            Spacer(modifier = Modifier.height(Spacing.Medium))
            SetMemberChecklist(members = setMembers)
        }

        challenge.predefinedRoute()?.let { route ->
            if (route.hasDistances) {
                FocusInfoRow(
                    label = "DISTANCE FLOWN",
                    value = "${formatKm(route.distanceFlownKm(challenge.legIndex))} of ${formatKm(route.totalDistanceKm)}"
                )
            }
            Spacer(modifier = Modifier.height(Spacing.Medium))
            RouteLegList(route = route, legIndex = challenge.legIndex)
        }

        if (shared) {
            val today = remember { LocalDate.now() }
            Spacer(modifier = Modifier.height(Spacing.Medium))
            CrewSection(
                lines = challenge.crewLines(today),
                code = challenge.roomCode,
                onCopyCode = onCopyCode,
                onShareCode = onShareCode,
                // A room the server no longer knows keeps its cached crew but says so (P8); the
                // rest of the time the line is the sync age, or why there is no sync right now.
                caption = if (challenge.roomState?.room?.roomGone == true) {
                    "Room closed · continuing solo"
                } else {
                    syncedAgo ?: onlineHint(availability)
                }
            )
        }

        Spacer(modifier = Modifier.height(Spacing.Medium))

        // S1 hides the button, S2 dims it with the reason, S3 offers it, S4 dims it because the
        // row has moved, S5 replaces it with the crew above, S7 is the read-only branch below.
        if (active && !shared && availability != OnlineFeatureAvailability.HIDDEN) {
            val fresh = challenge.shareIneligibility() == null
            FocusButton(
                text = "SHARE CHALLENGE",
                onClick = onShare,
                variant = ButtonVariant.Secondary,
                style = ButtonStyle.Filled,
                size = ButtonSize.Compact,
                icon = Icons.Outlined.Share,
                enabled = fresh && availability == OnlineFeatureAvailability.ENABLED && shareState !is ShareUiState.Working
            )
            val hint = when {
                !fresh -> "Only a fresh challenge can be shared"
                shareState is ShareUiState.Error -> shareState.message
                else -> onlineHint(availability)
            }
            if (hint != null) {
                Spacer(modifier = Modifier.height(Spacing.ExtraSmall))
                CaptionLabel(text = hint)
            }
            Spacer(modifier = Modifier.height(Spacing.Small))
        }

        if (!active) {
            FocusButton(
                text = "CLOSE",
                onClick = onDismiss,
                variant = ButtonVariant.Primary,
                style = ButtonStyle.Filled,
                size = ButtonSize.Compact
            )
        } else if (challenge.type == ChallengeType.ROUTE) {
            // onContinue resumes the paused leg when there is one and books a fresh leg otherwise,
            // so the label says which of the two is about to happen.
            FocusButton(
                text = when {
                    isFocused -> "PAUSE CHALLENGE"
                    challenge.pausedFlight != null -> "RESUME CHALLENGE"
                    else -> "CONTINUE CHALLENGE"
                },
                onClick = if (isFocused) onPause else onContinue,
                variant = ButtonVariant.Primary,
                style = ButtonStyle.Filled,
                size = ButtonSize.Compact
            )
            Spacer(modifier = Modifier.height(Spacing.Small))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.Small)
            ) {
                FocusButton(
                    text = "ABANDON",
                    onClick = onAbandon,
                    variant = ButtonVariant.Danger,
                    style = ButtonStyle.Filled,
                    size = ButtonSize.Compact,
                    modifier = Modifier.weight(1f),
                    fillMaxWidth = false
                )
                FocusButton(
                    text = "CLOSE",
                    onClick = onDismiss,
                    variant = ButtonVariant.Secondary,
                    style = ButtonStyle.Filled,
                    size = ButtonSize.Compact,
                    modifier = Modifier.weight(1f),
                    fillMaxWidth = false
                )
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.Small)
            ) {
                FocusButton(
                    text = "ABANDON",
                    onClick = onAbandon,
                    variant = ButtonVariant.Danger,
                    style = ButtonStyle.Filled,
                    size = ButtonSize.Compact,
                    modifier = Modifier.weight(1f),
                    fillMaxWidth = false
                )
                FocusButton(
                    text = "CLOSE",
                    onClick = onDismiss,
                    variant = ButtonVariant.Primary,
                    style = ButtonStyle.Filled,
                    size = ButtonSize.Compact,
                    modifier = Modifier.weight(1f),
                    fillMaxWidth = false
                )
            }
        }
    }
}

// ── The crew (docs/shared-challenges.md "Per type") ────────────────────────────────────────

/** How a streak pilot stands, from their own flag and their last credited day. */
private enum class StreakState { ALIVE, AT_RISK, BROKEN }

/** One line of the crew list: who, whether that is this pilot, and the figure the room's type
 *  scores by, with a streak's state beside it. */
private data class CrewLine(
    val participant: ParticipantSnapshot,
    val isSelf: Boolean,
    val figure: String,
    val streakState: StreakState?
)

/**
 * The crew of a shared row, self first, in join order, leavers last, as [crew] orders them. Self's
 * figures come from the row and never from the cached self snapshot (SharedProgress.kt): the row
 * is credited at landing and the snapshot only later, so the cached copy is the stale one. The
 * snapshot lends self only its name and colour.
 */
private fun Challenge.crewLines(today: LocalDate): List<CrewLine> {
    val cache = roomState ?: return emptyList()
    val standings = raceStandings()
    return crew().map { participant ->
        val isSelf = participant.userCode == cache.selfCode
        val snapshot = if (isSelf) {
            participant.copy(
                positionIata = positionIata,
                legIndex = legIndex,
                routeProgress = progressFraction(),
                visitedMembers = visitedSetMembers,
                distanceKm = cumulativeDistanceKm,
                streakDays = streakDays,
                lastFlownDay = lastFlownDay,
                streakAlive = (sharedOutcome as? SharedOutcome.Failed)?.bySelf != true
            )
        } else {
            participant
        }
        val rank = if (isSelf) racePlacement() else standings.indexOf(participant.userCode).takeIf { it >= 0 }?.plus(1)
        crewLine(type, snapshot, isSelf, rank, today)
    }
}

/**
 * The race's order for the crew list, winner first once the room is decided, otherwise by route
 * progress among the pilots still in it, self's progress from the row, ties by join order. The
 * same rule [racePlacement] applies to self, applied to everyone.
 */
private fun Challenge.raceStandings(): List<String> {
    if (type != ChallengeType.ROUTE) return emptyList()
    val cache = roomState ?: return emptyList()
    (sharedOutcome as? SharedOutcome.Completed)?.placements?.takeIf { it.isNotEmpty() }?.let { return it }
    return crew()
        .filter { !it.left }
        .map { participant ->
            val progress = if (participant.userCode == cache.selfCode) progressFraction() else participant.routeProgress
            Triple(participant.userCode, progress, participant.colorIndex)
        }
        .sortedWith(compareByDescending<Triple<String, Float, Int>> { it.second }.thenBy { it.third })
        .map { it.first }
}

/** The crew of a room the pilot is not in yet (the join preview): nobody is self, leavers last. */
private fun RoomState.crewLines(today: LocalDate): List<CrewLine> {
    val (left, present) = participants.partition { it.left }
    val standings = present
        .sortedWith(compareByDescending<ParticipantSnapshot> { it.routeProgress }.thenBy { it.colorIndex })
        .map { it.userCode }
    return (present.sortedBy { it.colorIndex } + left.sortedBy { it.colorIndex }).map { participant ->
        val rank = standings.indexOf(participant.userCode).takeIf { it >= 0 }?.plus(1)
        crewLine(definition.type, participant, isSelf = false, rank = rank, today = today)
    }
}

private fun crewLine(
    type: ChallengeType,
    participant: ParticipantSnapshot,
    isSelf: Boolean,
    rank: Int?,
    today: LocalDate
): CrewLine {
    val figure = when (type) {
        ChallengeType.ROUTE -> listOfNotNull(participant.positionIata, rank?.let { "#$it" }).joinToString(" ")
        ChallengeType.SET_COMPLETION -> "${participant.visitedMembers.size} visited"
        ChallengeType.DISTANCE -> formatKm(participant.distanceKm)
        ChallengeType.STREAK -> if (participant.streakDays == 1) "1 day" else "${participant.streakDays} days"
    }
    val streakState = if (type == ChallengeType.STREAK) streakStateOf(participant, today) else null
    return CrewLine(participant, isSelf, figure, streakState)
}

/** Broken by the owner's own flag; alive while the last credited day is today or yesterday
 *  (or ahead, on a clock that moved), the same slack `Challenge.currentStreak` gives; at risk
 *  otherwise, which includes a pilot who has not flown at all yet. */
private fun streakStateOf(participant: ParticipantSnapshot, today: LocalDate): StreakState {
    if (!participant.streakAlive) return StreakState.BROKEN
    val last = participant.lastFlownDay?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
    return if (last != null && !last.isBefore(today.minusDays(1))) StreakState.ALIVE else StreakState.AT_RISK
}

/**
 * "CREW", the list, then (for the pilot's own row) the room code with copy and share, and a
 * caption under it. The join preview passes no [code]: the pilot just typed it.
 */
@Composable
private fun CrewSection(
    lines: List<CrewLine>,
    code: String?,
    onCopyCode: (String) -> Unit = {},
    onShareCode: (String) -> Unit = {},
    caption: String? = null
) {
    SectionHeader(title = "CREW")
    Spacer(modifier = Modifier.height(Spacing.Small))
    if (lines.isEmpty()) {
        // A shared row whose room was never seen: still shared, crew unknown, not "nobody".
        Text(
            text = "Crew not synced yet",
            style = MaterialTheme.typography.bodySmall,
            color = Haze
        )
    } else {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            lines.forEach { line -> CrewRow(line) }
        }
    }
    if (code != null) {
        Spacer(modifier = Modifier.height(Spacing.Medium))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            FocusBadge(
                text = code,
                variant = BadgeVariant.Primary,
                style = BadgeStyle.Outlined,
                size = BadgeSize.Standard
            )
            Spacer(modifier = Modifier.weight(1f))
            IconButton(onClick = { onCopyCode(code) }) {
                Icon(
                    imageVector = Icons.Outlined.ContentCopy,
                    contentDescription = "Copy code",
                    tint = Amber,
                    modifier = Modifier.size(20.dp)
                )
            }
            IconButton(onClick = { onShareCode(code) }) {
                Icon(
                    imageVector = Icons.Outlined.Share,
                    contentDescription = "Share code",
                    tint = Amber,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
    if (caption != null) {
        Spacer(modifier = Modifier.height(Spacing.ExtraSmall))
        CaptionLabel(text = caption)
    }
}

/** Colour dot, name, `#CODE`, then the figure right-aligned so the column reads at a glance.
 *  A pilot who left stays listed, faded: their contribution to a pool is still in the bar. */
@Composable
private fun CrewRow(line: CrewLine) {
    val participant = line.participant
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (participant.left) 0.45f else 1f),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(participantColor(participant.colorIndex, line.isSelf))
        )
        Spacer(modifier = Modifier.width(Spacing.Small))
        Column(modifier = Modifier.weight(1f)) {
            val name = participant.username.ifBlank { participant.userCode }
            Text(
                text = if (line.isSelf) "$name (you)" else name,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                color = OffWhite,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "#${participant.userCode}",
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                color = Haze
            )
        }
        Spacer(modifier = Modifier.width(Spacing.Small))
        line.streakState?.let { state ->
            FocusBadge(
                text = when (state) {
                    StreakState.ALIVE -> "ALIVE"
                    StreakState.AT_RISK -> "AT RISK"
                    StreakState.BROKEN -> "BROKEN"
                },
                variant = when (state) {
                    StreakState.ALIVE -> BadgeVariant.Success
                    StreakState.AT_RISK -> BadgeVariant.Primary
                    StreakState.BROKEN -> BadgeVariant.Danger
                },
                style = BadgeStyle.Translucent,
                size = BadgeSize.Compact
            )
            Spacer(modifier = Modifier.width(6.dp))
        }
        Text(
            text = line.figure,
            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            color = OffWhite
        )
    }
}

/**
 * A predefined itinerary's legs in flying order - flown ones checked off, the next one lit, the
 * rest waiting.
 *
 * Deliberately *not* [SetMemberChecklist], even though both are "a list with some items done". A
 * set has no order and no repeats, so that component is free to reorder visited-first and filter to
 * MISSING. An itinerary is nothing but its order, and a circuit visits the same airport twice - so
 * reordering it would destroy the only thing it says, and "which are missing" is never the question
 * (they all are, in a fixed sequence, and only one of them is next).
 */
@Composable
private fun RouteLegList(route: PredefinedRoute, legIndex: Int) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 200.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        for (leg in 0 until route.legCount) {
            val flown = leg < legIndex
            val isNext = leg == legIndex
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (isNext) Slate.copy(alpha = 0.55f) else Color.Transparent)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(modifier = Modifier.size(18.dp), contentAlignment = Alignment.Center) {
                    if (flown) {
                        Icon(
                            imageVector = Icons.Outlined.Check,
                            contentDescription = null,
                            tint = Amber,
                            modifier = Modifier.size(16.dp)
                        )
                    } else if (isNext) {
                        Icon(
                            imageVector = Icons.Outlined.FlightTakeoff,
                            contentDescription = null,
                            tint = Amber,
                            modifier = Modifier.size(16.dp)
                        )
                    } else {
                        Text(
                            text = "${leg + 1}",
                            style = MaterialTheme.typography.labelSmall,
                            color = Haze
                        )
                    }
                }
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = "${route.originOf(leg)} → ${route.destOf(leg)}",
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontFamily = FontFamily.Monospace,
                        fontWeight = if (isNext) FontWeight.Bold else FontWeight.Normal
                    ),
                    color = if (flown || isNext) OffWhite else Haze
                )
                if (isNext) {
                    Spacer(modifier = Modifier.width(10.dp))
                    FocusBadge(
                        text = "NEXT",
                        variant = BadgeVariant.Primary,
                        style = BadgeStyle.Translucent,
                        size = BadgeSize.Compact
                    )
                }
                // Right-aligned so the distances form a column - this is what says which legs are
                // the expensive ones before you commit to the challenge.
                route.legDistancesKm.getOrNull(leg)?.let { km ->
                    Spacer(modifier = Modifier.weight(1f))
                    Text(
                        text = formatKm(km),
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                        color = if (flown || isNext) Haze else Haze.copy(alpha = 0.6f)
                    )
                }
            }
        }
    }
}
