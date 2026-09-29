package com.silas270.blocktime.ui.screens.challenges

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import com.silas270.blocktime.ui.theme.ScreenGutter
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.FlightTakeoff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.silas270.blocktime.data.model.Challenge
import com.silas270.blocktime.data.model.ChallengeType
import com.silas270.blocktime.data.model.PausedFlight
import com.silas270.blocktime.data.repository.MAX_ACTIVE_CHALLENGES
import com.silas270.blocktime.data.repository.StartChallengeResult
import com.silas270.blocktime.data.repository.SyncSummary
import com.silas270.blocktime.domain.onlineAvailability
import com.silas270.blocktime.data.model.AchievementCategory
import com.silas270.blocktime.data.model.AchievementStatus
import com.silas270.blocktime.ui.components.AchievementDetailModal
import com.silas270.blocktime.ui.components.AchievementProgressRow
import com.silas270.blocktime.ui.components.achievementCategoryLabel
import com.silas270.blocktime.ui.components.BackTopAppBar
import com.silas270.blocktime.ui.components.CardVariant
import com.silas270.blocktime.ui.components.DiscardFlightConfirmModal
import com.silas270.blocktime.ui.components.FocusCard
import com.silas270.blocktime.ui.components.SectionHeader
import com.silas270.blocktime.ui.screens.account.ChallengeCompletionEntry
import com.silas270.blocktime.ui.theme.Amber
import com.silas270.blocktime.ui.theme.Border
import com.silas270.blocktime.ui.theme.DeepNavy
import com.silas270.blocktime.ui.theme.Haze
import com.silas270.blocktime.ui.theme.Midnight
import com.silas270.blocktime.ui.theme.OffWhite
import com.silas270.blocktime.ui.theme.Spacing
import com.silas270.blocktime.ui.viewmodel.challenges.ChallengesViewModel

private enum class ChallengesTab { CHALLENGES, ACHIEVEMENTS }

/**
 * The Challenges screen - a full destination rather than the bottom sheet this used to live in,
 * which needed its own inner scroll and swapped five view states inside a card.
 *
 * Layout, top to bottom: the Free Mode entry, a two-tab switcher, then either the three challenge
 * slots plus the completed-challenges log, or the still-unearned achievements list.
 *
 * Achievements share this screen because they are the same kind of thing as a challenge - a goal
 * you have not finished yet. Once earned they leave here entirely and become badges on the
 * Passport, so this list only ever shrinks.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChallengesScreen(
    viewModel: ChallengesViewModel,
    onBackClick: () -> Unit,
    onFreeModeClick: () -> Unit,
    onResumeFreeFlight: (flight: PausedFlight) -> Unit,
    onContinueRouteChallenge: (challengeId: Int) -> Unit,
    onResumeRouteChallenge: (challenge: Challenge) -> Unit,
    onChallengeStarted: () -> Unit
) {
    val slotChallenges by viewModel.slotChallenges.collectAsState()
    val completedChallenges by viewModel.completedChallenges.collectAsState()
    val celebrationQueue by viewModel.celebrationQueue.collectAsState()
    val unfinishedAchievements by viewModel.unfinishedAchievements.collectAsState()
    val startResult by viewModel.startResult.collectAsState()
    val focusedChallengeId by viewModel.focusedChallengeId.collectAsState()
    val pausedFreeFlight by viewModel.pausedFreeFlight.collectAsState()
    val serverState by viewModel.serverState.collectAsState()
    val shareState by viewModel.shareState.collectAsState()
    val lastSyncSummary by viewModel.lastSyncSummary.collectAsState()
    val context = LocalContext.current

    var tab by rememberSaveable { mutableStateOf(ChallengesTab.CHALLENGES) }
    var showPicker by remember { mutableStateOf(false) }
    // The id, not the row: the modal reads the row from the live slot list on every composition,
    // so a sync that changes it (a crew member's landing, a room ending) shows at once (Y11).
    var infoChallengeId by remember { mutableStateOf<Int?>(null) }
    val infoChallenge = infoChallengeId?.let { id -> slotChallenges.firstOrNull { it.id == id } }
    var pendingAbandon by remember { mutableStateOf<Challenge?>(null) }
    var showFreeModeNotice by remember { mutableStateOf(false) }
    var discardFreeFlightTarget by remember { mutableStateOf<PausedFlight?>(null) }
    // Hoisted here, not inside the tab's item block: a ScrimCardModal opened from inside a
    // LazyColumn item is clipped to that item, so it has to be a sibling of the Scaffold.
    var detailAchievement by remember { mutableStateOf<AchievementStatus?>(null) }

    // Completion-presentation tracking (docs/challenges.md):
    // Track celebrated IDs in this session so that even before Room database emission completes,
    // celebrating/celebrated challenges are immediately removed from the active slot display.
    var celebratedIds by remember { mutableStateOf(emptySet<Int>()) }
    val slotBoundsByIndex = remember { mutableStateMapOf<Int, Rect>() }
    var logAnchorRect by remember { mutableStateOf<Rect?>(null) }
    val currentCelebration = celebrationQueue.firstOrNull()

    // Track whether the current celebrating card has begun lifting off from its slot
    var isCardLiftingOff by remember(currentCelebration?.id) { mutableStateOf(false) }

    // Challenges occupying the active slots before lifting off the currently celebrated one
    val candidateChallenges = remember(slotChallenges, celebratedIds) {
        slotChallenges.filterNot { it.id in celebratedIds }
    }

    // The slot index where currentCelebration was located before duplicating & flying to center
    val celebratingSlotIndex = remember(candidateChallenges, currentCelebration?.id) {
        currentCelebration?.let { cur ->
            val idx = candidateChallenges.indexOfFirst { it.id == cur.id }
            if (idx >= 0) idx else null
        }
    }

    // Displayed challenges: while waiting (initial delay), still show the completed challenge in its slot.
    // As soon as it lifts off, compact immediately to shift remaining challenges left and reveal the '+' slot.
    val displayedChallenges = remember(candidateChallenges, currentCelebration?.id, isCardLiftingOff) {
        if (isCardLiftingOff) {
            candidateChallenges.filterNot { it.id == currentCelebration?.id }
        } else {
            candidateChallenges
        }
    }

    // A successful start just fills a slot - close the picker and let the slot row update. A
    // Route challenge additionally takes over the Hub's focus (already set by the ViewModel), so
    // it should drop straight back there instead of lingering on this screen.
    LaunchedEffect(startResult) {
        val result = startResult
        if (result is StartChallengeResult.Started) {
            showPicker = false
            viewModel.clearStartResult()
            if (result.challenge.type == ChallengeType.ROUTE) {
                onChallengeStarted()
            }
        }
    }

    // A row that left the slot list while its modal was open (abandoned, or a failure dismissed
    // after its presentation) closes the modal rather than leaving an id nothing resolves.
    LaunchedEffect(infoChallengeId, infoChallenge) {
        if (infoChallengeId != null && infoChallenge == null) infoChallengeId = null
    }

    val listState = rememberLazyListState()
    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = Midnight,
            topBar = {
                BackTopAppBar(
                    title = "CHALLENGES",
                    onBackClick = onBackClick,
                    scrolled = listState.canScrollBackward
                )
            }
        ) { padding ->
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(
                    start = ScreenGutter,
                    end = ScreenGutter,
                    top = Spacing.Small,
                    bottom = Spacing.ExtraLarge
                ),
                verticalArrangement = Arrangement.spacedBy(Spacing.Medium)
            ) {
                item {
                    FreeModeRow(
                        pausedFlight = pausedFreeFlight,
                        onClick = {
                            if (pausedFreeFlight != null) {
                                discardFreeFlightTarget = pausedFreeFlight
                            } else {
                                showFreeModeNotice = true
                            }
                        },
                        onResumeClick = { pausedFreeFlight?.let(onResumeFreeFlight) }
                    )
                }

                item {
                    TabSwitcher(
                        selected = tab,
                        onSelect = { tab = it }
                    )
                }

                when (tab) {
                    ChallengesTab.CHALLENGES -> {
                        item {
                            ChallengeSlotRow(
                                challenges = displayedChallenges,
                                onEmptySlotClick = { showPicker = true },
                                onChallengeClick = { infoChallengeId = it.id },
                                onSlotPositioned = { slotIndex, bounds -> slotBoundsByIndex[slotIndex] = bounds }
                            )
                        }

                        item {
                            Box(
                                modifier = Modifier.onGloballyPositioned { logAnchorRect = it.boundsInWindow() }
                            ) {
                                SectionHeader(title = "COMPLETED")
                            }
                        }

                        if (completedChallenges.isEmpty()) {
                            item { EmptyLine("No challenges completed yet.") }
                        } else {
                            itemsIndexed(
                                items = completedChallenges,
                                key = { _, challenge -> "completed_${challenge.id}" }
                            ) { index, challenge ->
                                ChallengeCompletionEntry(
                                    challenge = challenge,
                                    entryNumber = completedChallenges.size - index
                                )
                            }
                        }
                    }

                    ChallengesTab.ACHIEVEMENTS -> {
                        if (unfinishedAchievements.isEmpty()) {
                            item { EmptyLine("Every achievement earned. Check your Passport.") }
                        } else {
                            // Grouped by strict category, iterating the enum rather than the data
                            // so the section order is fixed and a category can't reorder itself
                            // out from under the pilot as its contents change. Within a section the
                            // ViewModel's closest-to-done ordering is preserved untouched.
                            AchievementCategory.entries.forEach { category ->
                                val inCategory = unfinishedAchievements.filter { it.category == category }
                                if (inCategory.isEmpty()) return@forEach

                                item(key = "header_${category.name}") {
                                    SectionHeader(title = achievementCategoryLabel(category))
                                }
                                items(
                                    items = inCategory,
                                    key = { achievement -> achievement.id }
                                ) { achievement ->
                                    FocusCard(
                                        modifier = Modifier.fillMaxWidth(),
                                        variant = CardVariant.Surface,
                                        shape = RoundedCornerShape(16.dp),
                                        contentPadding = PaddingValues(Spacing.Medium),
                                        onClick = { detailAchievement = achievement }
                                    ) {
                                        // Everything on this tab is unearned by construction, so
                                        // the "DONE" pill and locked/unlocked dot would be dead
                                        // pixels.
                                        AchievementProgressRow(
                                            achievement = achievement,
                                            showUnlockedCues = false
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        if (showFreeModeNotice) {
            FreeModeNoticeModal(
                onConfirm = {
                    showFreeModeNotice = false
                    onFreeModeClick()
                },
                onDismiss = { showFreeModeNotice = false }
            )
        }

        if (showPicker) {
            ChallengePickerModal(
                viewModel = viewModel,
                onDismiss = { showPicker = false }
            )
        }

        detailAchievement?.let { achievement ->
            AchievementDetailModal(
                achievement = achievement,
                onDismiss = { detailAchievement = null }
            )
        }

        infoChallenge?.let { challenge ->
            // Stamped when the modal opens or a sync lands, not every frame: the caption is "how
            // old are the crew's numbers", and a minute's drift while the modal is open is fine.
            val syncedAgo = (lastSyncSummary as? SyncSummary.Synced)?.let { synced ->
                val now = remember(challenge.id, synced) { System.currentTimeMillis() }
                syncedAgoLabel(synced.at, now)
            }
            ChallengeInfoModal(
                challenge = challenge,
                isFocused = challenge.id == focusedChallengeId,
                availability = onlineAvailability(serverState),
                shareState = shareState,
                syncedAgo = syncedAgo,
                onContinue = {
                    infoChallengeId = null
                    viewModel.focusRouteChallenge(challenge.id)
                    if (challenge.pausedFlight != null) {
                        onResumeRouteChallenge(challenge)
                    } else {
                        onContinueRouteChallenge(challenge.id)
                    }
                },
                onPause = {
                    infoChallengeId = null
                    viewModel.pauseFocusedChallenge()
                },
                onAbandon = {
                    infoChallengeId = null
                    pendingAbandon = challenge
                },
                onShare = { viewModel.shareChallenge(challenge.id) },
                onCopyCode = { code ->
                    context.getSystemService(ClipboardManager::class.java)
                        ?.setPrimaryClip(ClipData.newPlainText("Blocktime challenge code", code))
                },
                onShareCode = { code ->
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, "Join my Blocktime challenge \"${challenge.name}\": $code")
                    }
                    context.startActivity(Intent.createChooser(send, "Share challenge code"))
                },
                onDismiss = {
                    infoChallengeId = null
                    viewModel.clearShareState()
                }
            )
        }

        discardFreeFlightTarget?.let { flight ->
            DiscardFlightConfirmModal(
                flight = flight,
                onConfirm = {
                    discardFreeFlightTarget = null
                    showFreeModeNotice = true
                },
                onDismiss = { discardFreeFlightTarget = null }
            )
        }

        pendingAbandon?.let { challenge ->
            AbandonConfirmModal(
                challenge = challenge,
                onConfirm = {
                    viewModel.abandon(challenge.id)
                    pendingAbandon = null
                },
                onDismiss = { pendingAbandon = null }
            )
        }

        // Structurally hard to reach now that the picker only opens from an empty slot, but a
        // start can still lose a race against a concurrently-started challenge - so it is
        // surfaced rather than swallowed.
        (startResult as? StartChallengeResult.CapReached)?.let {
            InfoModal(
                title = "CHALLENGE SLOTS FULL",
                message = "You already have $MAX_ACTIVE_CHALLENGES active challenges - the most you can run at once. Abandon one before starting another.",
                onDismiss = { viewModel.clearStartResult() }
            )
        }

        (startResult as? StartChallengeResult.UnknownTemplate)?.let {
            // Shouldn't happen from UI-driven catalogIds - defensive only.
            LaunchedEffect(it) { viewModel.clearStartResult() }
        }

        // Rendered last so it sits on top of - and its scrim blocks taps into - everything else on
        // this screen, including the modals above, whenever a completion is queued.
        ChallengeCompletionOverlay(
            current = currentCelebration,
            slotRect = celebratingSlotIndex?.let { slotBoundsByIndex[it] },
            logAnchorRect = logAnchorRect,
            isFirstCelebration = celebratedIds.isEmpty(),
            onFlyInStart = { isCardLiftingOff = true },
            onCelebrated = { id ->
                celebratedIds = celebratedIds + id
                viewModel.celebrate(id)
            }
        )
    }
}

/**
 * Free Mode's entry point. When a Free Mode flight is sitting paused in its own slot
 * ([com.silas270.blocktime.data.repository.PreferencesRepository.pausedFreeFlightStore] -
 * separate from Story Mode's, so the two can be paused at the same time), a second row offers to
 * resume it directly - without this, starting a fresh Free flight would silently overwrite that
 * slot (same "fresh PausedFlight is the reset" behavior Story Mode's CheckIn already has),
 * stranding the paused one with no way back to it.
 *
 * With a flight paused, the top row relabels itself as the *new*-flight action and says what it
 * costs, because that is what tapping it now does: it goes through a discard confirmation rather
 * than straight to the Free Mode notice. It used to read "Free Mode" either way, so the only way to
 * learn the difference was to be asked whether to throw a flight away.
 */
@Composable
private fun FreeModeRow(pausedFlight: PausedFlight?, onClick: () -> Unit, onResumeClick: () -> Unit) {
    FocusCard(
        modifier = Modifier.fillMaxWidth(),
        variant = CardVariant.Surface,
        shape = RoundedCornerShape(16.dp),
        contentPadding = PaddingValues(0.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(Spacing.Medium),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Outlined.FlightTakeoff,
                contentDescription = null,
                tint = Amber,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(Spacing.Small))
            if (pausedFlight == null) {
                Text(
                    text = "Free Mode",
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold),
                    color = OffWhite,
                    modifier = Modifier.weight(1f)
                )
            } else {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "New Free Flight",
                        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold),
                        color = OffWhite
                    )
                    Text(
                        text = "Discards the paused flight below",
                        style = MaterialTheme.typography.bodySmall,
                        color = Haze
                    )
                }
            }
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                contentDescription = null,
                tint = Amber,
                modifier = Modifier.size(22.dp)
            )
        }

        if (pausedFlight != null) {
            HorizontalDivider(color = Border, thickness = 0.5.dp)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onResumeClick)
                    .padding(Spacing.Medium),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Outlined.FlightTakeoff,
                    contentDescription = null,
                    tint = Amber,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(Spacing.Small))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Resume Free Flight",
                        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold),
                        color = OffWhite
                    )
                    Text(
                        text = "${pausedFlight.originIata} → ${pausedFlight.destIata}",
                        style = MaterialTheme.typography.bodySmall,
                        color = Haze
                    )
                }
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                    contentDescription = null,
                    tint = Amber,
                    modifier = Modifier.size(22.dp)
                )
            }
        }
    }
}

@Composable
private fun TabSwitcher(selected: ChallengesTab, onSelect: (ChallengesTab) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth()) {
        ChallengesTab.entries.forEach { entry ->
            val active = entry == selected
            // heightIn keeps the whole tab a 48dp touch target; Bottom keeps the underline where
            // it was, so the extra height only adds room above the label.
            Column(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
                    .clickable { onSelect(entry) }
                    .padding(vertical = Spacing.Small),
                verticalArrangement = Arrangement.Bottom,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = entry.name,
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    ),
                    color = if (active) Amber else Haze
                )
                Spacer(modifier = Modifier.height(6.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp)
                        .background(if (active) Amber else Border.copy(alpha = 0.4f))
                )
            }
        }
    }
}

@Composable
private fun EmptyLine(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = Haze,
        modifier = Modifier.padding(vertical = Spacing.Small)
    )
}
