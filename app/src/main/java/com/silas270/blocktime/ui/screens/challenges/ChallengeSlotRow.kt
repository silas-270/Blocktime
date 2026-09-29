package com.silas270.blocktime.ui.screens.challenges

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.PaddingValues
import com.silas270.blocktime.data.model.Challenge
import com.silas270.blocktime.data.model.ChallengeStatus
import com.silas270.blocktime.data.model.ChallengeType
import com.silas270.blocktime.data.model.displayProgressFraction
import com.silas270.blocktime.data.model.isShared
import com.silas270.blocktime.data.model.racePlacement
import com.silas270.blocktime.data.model.teamVisitedMembers
import com.silas270.blocktime.ui.components.CardVariant
import com.silas270.blocktime.ui.components.FocusCard
import com.silas270.blocktime.ui.components.RingProgress
import com.silas270.blocktime.ui.components.icon
import com.silas270.blocktime.ui.theme.Amber
import com.silas270.blocktime.ui.theme.DeepNavy
import com.silas270.blocktime.ui.theme.Haze
import com.silas270.blocktime.ui.theme.OffWhite
import com.silas270.blocktime.ui.theme.Slate
import com.silas270.blocktime.ui.theme.Spacing
import com.silas270.blocktime.data.repository.MAX_ACTIVE_CHALLENGES

/**
 * The three challenge slots, side by side - the screen's primary affordance.
 *
 * Always renders exactly [MAX_ACTIVE_CHALLENGES] squares regardless of how many challenges are
 * active, so the row never reflows and the cap is legible at a glance rather than being an error
 * message you hit later. An empty slot *is* the "start a challenge" button; there is no separate
 * one.
 *
 * Slot identity is positional, not stable per challenge: when one completes, the list compacts and
 * the remaining challenges shift left. That is intentional - "when a challenge completes it's
 * removed from the layout" - and needs no bookkeeping beyond `getOrNull`.
 */
@Composable
internal fun ChallengeSlotRow(
    challenges: List<Challenge>,
    onEmptySlotClick: () -> Unit,
    onChallengeClick: (Challenge) -> Unit,
    onSlotPositioned: (slotIndex: Int, bounds: Rect) -> Unit = { _, _ -> }
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Spacing.Small)
    ) {
        for (index in 0 until MAX_ACTIVE_CHALLENGES) {
            val challenge = challenges.getOrNull(index)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .aspectRatio(1f)
                    .onGloballyPositioned { coordinates ->
                        onSlotPositioned(index, coordinates.boundsInWindow())
                    }
            ) {
                if (challenge != null) {
                    FilledSlot(
                        challenge = challenge,
                        onClick = { onChallengeClick(challenge) }
                    )
                } else {
                    EmptySlot(onClick = onEmptySlotClick)
                }
            }
        }
    }
}

@Composable
private fun EmptySlot(onClick: () -> Unit) {
    FocusCard(
        modifier = Modifier.fillMaxSize(),
        variant = CardVariant.Secondary,
        shape = RoundedCornerShape(16.dp),
        contentPadding = PaddingValues(0.dp),
        onClick = onClick,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Outlined.Add,
                contentDescription = "Start a challenge",
                tint = Haze,
                modifier = Modifier.size(28.dp)
            )
        }
    }
}

/**
 * One filled slot: the ring shows what the screens show, the team's progress for a shared pool
 * and the pilot's own otherwise ([displayProgressFraction]). A shared row wears a crew badge top
 * left, a race its placement instead; a paused leg's badge top right only while the row is
 * still active. A failed streak, waiting for its presentation, draws its ring in Haze under
 * "BROKEN" (docs/shared-challenges.md "Presentation").
 */
@Composable
private fun FilledSlot(
    challenge: Challenge,
    onClick: () -> Unit
) {
    val active = challenge.status == ChallengeStatus.ACTIVE
    val failed = challenge.status == ChallengeStatus.FAILED
    val shared = challenge.isShared()
    val animatedProgress by animateFloatAsState(
        targetValue = challenge.displayProgressFraction(),
        animationSpec = tween(durationMillis = 800, easing = FastOutSlowInEasing),
        label = "challenge_slot_${challenge.id}"
    )

    val labelText = when {
        failed -> "BROKEN"
        // A shared set counts the union, like its ring.
        challenge.type == ChallengeType.SET_COMPLETION && shared ->
            "${challenge.teamVisitedMembers().size}/${challenge.setTotalMembers}"
        challenge.type == ChallengeType.SET_COMPLETION ->
            "${challenge.visitedSetMembers.size}/${challenge.setTotalMembers}"
        else -> "${(animatedProgress * 100).toInt()}%"
    }

    FocusCard(
        modifier = Modifier.fillMaxSize(),
        variant = CardVariant.Surface,
        shape = RoundedCornerShape(16.dp),
        contentPadding = PaddingValues(0.dp),
        onClick = onClick,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            // Challenge icon sits behind the ring, dimmed - it identifies the challenge without
            // competing with the number, which is the thing you actually read.
            Icon(
                imageVector = challenge.icon(),
                contentDescription = null,
                tint = Haze.copy(alpha = 0.15f),
                modifier = Modifier.fillMaxSize().padding(Spacing.Large)
            )
            RingProgress(
                progress = animatedProgress,
                modifier = Modifier.fillMaxSize().padding(Spacing.Small),
                strokeWidth = 5.dp,
                fillColor = if (failed) Haze else Amber
            ) {
                AutoScalingCenterText(
                    text = labelText,
                    modifier = Modifier.padding(horizontal = 6.dp)
                )
            }

            // A shared row says so before the tap: the crew icon for a pool, the pilot's place
            // for a race ("2nd"), mirroring the pause badge on the other corner.
            if (shared) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(6.dp)
                        .size(18.dp)
                        .clip(CircleShape)
                        .background(Amber),
                    contentAlignment = Alignment.Center
                ) {
                    val placement = if (challenge.type == ChallengeType.ROUTE) challenge.racePlacement() else null
                    if (placement != null) {
                        Text(
                            text = "$placement${ordinalSuffix(placement)}",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                fontSize = 7.sp,
                                lineHeight = 8.sp
                            ),
                            color = DeepNavy,
                            maxLines = 1,
                            softWrap = false
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Outlined.Groups,
                            contentDescription = "Shared challenge",
                            tint = DeepNavy,
                            modifier = Modifier.size(12.dp)
                        )
                    }
                }
            }

            // A Route challenge with a leg paused mid-air: tapping it offers RESUME rather than a
            // fresh booking, and the slot should say so before the tap rather than after. A
            // terminal row has no leg to resume, whatever the column still holds.
            if (active && challenge.pausedFlight != null) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .size(18.dp)
                        .clip(CircleShape)
                        .background(Amber),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Pause,
                        contentDescription = "Flight paused",
                        tint = DeepNavy,
                        modifier = Modifier.size(12.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun AutoScalingCenterText(
    text: String,
    modifier: Modifier = Modifier
) {
    val initialSize = when {
        text.length <= 3 -> 15.sp
        text.length == 4 -> 13.sp
        text.length == 5 -> 11.5.sp
        else -> 10.sp
    }

    var fontSize by remember(text) { mutableStateOf(initialSize) }
    var readyToDraw by remember(text) { mutableStateOf(false) }

    Text(
        text = text,
        color = OffWhite,
        maxLines = 1,
        softWrap = false,
        textAlign = TextAlign.Center,
        style = MaterialTheme.typography.titleMedium.copy(
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            fontSize = fontSize
        ),
        onTextLayout = { textLayoutResult ->
            if (textLayoutResult.didOverflowWidth && fontSize.value > 8f) {
                fontSize = (fontSize.value - 1f).coerceAtLeast(8f).sp
            } else {
                readyToDraw = true
            }
        },
        modifier = modifier.drawWithContent {
            if (readyToDraw) {
                drawContent()
            }
        }
    )
}
