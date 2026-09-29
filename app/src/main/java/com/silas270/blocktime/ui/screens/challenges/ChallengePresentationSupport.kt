package com.silas270.blocktime.ui.screens.challenges

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp as lerpColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.silas270.blocktime.data.model.Challenge
import com.silas270.blocktime.data.model.ChallengeStatus
import com.silas270.blocktime.data.model.ChallengeType
import com.silas270.blocktime.data.model.displayProgressFraction
import com.silas270.blocktime.ui.components.RingProgress
import com.silas270.blocktime.data.model.isShared
import com.silas270.blocktime.data.model.progressSegments
import com.silas270.blocktime.ui.theme.participantColor
import com.silas270.blocktime.ui.components.RingSegment
import com.silas270.blocktime.ui.components.challengeTypeLabel
import com.silas270.blocktime.ui.components.icon
import com.silas270.blocktime.ui.theme.Amber
import com.silas270.blocktime.ui.screens.account.finishedRacePlace
import com.silas270.blocktime.ui.screens.account.placeStamp
import com.silas270.blocktime.ui.screens.account.finishedRacePlace
import com.silas270.blocktime.ui.theme.Bronze
import com.silas270.blocktime.ui.theme.Silver
import com.silas270.blocktime.ui.theme.DeepNavy
import com.silas270.blocktime.ui.theme.Haze
import com.silas270.blocktime.ui.theme.LogbookInkDark
import com.silas270.blocktime.ui.theme.LogbookInkFaint
import com.silas270.blocktime.ui.theme.LogbookMarginRed
import com.silas270.blocktime.ui.theme.LogbookParchment
import com.silas270.blocktime.ui.theme.Midnight
import com.silas270.blocktime.ui.theme.OffWhite
import com.silas270.blocktime.ui.theme.Spacing
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

// What the completion overlay (docs/challenges.md "Completion presentation") and the failure
// overlay (docs/shared-challenges.md "Presentation") share: the opening beat's timings, the
// geometry of the card on its way from the slot to centre stage, the card itself and the caption
// and button around it. The celebration's own beats (confetti, glow, the smash into the log) and
// the shatter stay in their own files.

/** The pause before the first card lifts off, so the pilot sees where they are (step 1). */
internal const val InitialDelayMs = 325L
/** The shorter pause before every card after the first in one visit. */
internal const val RepeatDelayMs = 200L
internal const val FlyInDurationMs = 460
/** The fade of everything that appears once the card has settled: the button, a caption. */
internal const val PresentedFadeDurationMs = 240
private const val CardSizeDp = 220
/** Room for the caption's pill at its tallest: two headline lines, a badge and its padding. */
private const val CaptionHeightDp = 128

/** The flight's easing: a fast lift-off with a long, soft settle at centre stage. */
internal val FlyInEasing: CubicBezierEasing = CubicBezierEasing(0.05f, 0.85f, 0.15f, 1f)

/**
 * Where a presented card sits, computed from the overlay's constraints.
 *
 * [baseSlotWidthPx] is the divisor for every "how much bigger than its slot is the card now"
 * scale; [centerRect] is the card at centre stage, with the button below it counted into the
 * group so both together are centred; [anticipationRect] the celebration's recoil before the
 * smash and [targetLogEntryRect] the new log entry it smashes into.
 */
internal class PresentationGeometry(
    val centerXPx: Float,
    val baseSlotWidthPx: Float,
    val buttonHeightPx: Float,
    val buttonGapPx: Float,
    val centerRect: Rect,
    val anticipationRect: Rect,
    val targetLogEntryRect: Rect,
)

/**
 * The geometry for an overlay laid out in a `BoxWithConstraints` of [maxWidth] by [maxHeight],
 * lifting a card out of [slotRect] (window coordinates, null while the slot is still measuring)
 * and, for the celebration, dropping it under [logAnchorRect] (the COMPLETED header; null falls
 * back to a fixed position, and the failure overlay passes null because nothing lands).
 */
@Composable
internal fun rememberPresentationGeometry(
    slotRect: Rect?,
    logAnchorRect: Rect?,
    maxWidth: Dp,
    maxHeight: Dp
): PresentationGeometry {
    val density = LocalDensity.current
    val centerXPx = with(density) { (maxWidth / 2).toPx() }
    val centerYPx = with(density) { (maxHeight / 2).toPx() }
    // The divisor for every "how much bigger than its slot is the card now" scale below. A slot
    // measured mid-layout can report a zero width, and x / 0f is Infinity (or NaN for 0 / 0),
    // which `coerceAtLeast(1f)` lets straight through - so both sources are floored at 1px.
    val baseSlotWidthPx = (
        slotRect?.width?.takeIf { it > 0f }
            ?: with(density) { ((maxWidth - (Spacing.Large * 2)) / 3).toPx() }
        ).coerceAtLeast(1f)

    // Middle size between previous card size (CardSizeDp = 220.dp) and typical modal width (maxWidth - 48.dp)
    val buttonHeightPx = with(density) { 50.dp.toPx() }
    val buttonGapPx = with(density) { 16.dp.toPx() }
    val maxAvailableCardHeightPx = with(density) { maxHeight.toPx() } - with(density) { 140.dp.toPx() } - buttonHeightPx - buttonGapPx
    val modalWidthPx = with(density) { maxWidth.toPx() } - with(density) { (Spacing.Large * 2).toPx() }
    val previousCardSizePx = with(density) { CardSizeDp.dp.toPx() }
    val targetSizePx = (previousCardSizePx + modalWidthPx) / 2f
    val cardSizePx = targetSizePx.coerceAtMost(maxAvailableCardHeightPx)

    val totalGroupHeightPx = cardSizePx + buttonGapPx + buttonHeightPx
    val groupTopPx = (centerYPx - totalGroupHeightPx / 2f).coerceAtLeast(with(density) { 40.dp.toPx() })

    val centerRect = remember(maxWidth, maxHeight, cardSizePx, groupTopPx) {
        Rect(
            left = centerXPx - cardSizePx / 2f,
            top = groupTopPx,
            right = centerXPx + cardSizePx / 2f,
            bottom = groupTopPx + cardSizePx
        )
    }
    val anticipationRect = remember(centerRect) {
        val extraPx = with(density) { 12.dp.toPx() }
        Rect(
            left = centerRect.left - extraPx,
            top = centerRect.top - extraPx - with(density) { 8.dp.toPx() },
            right = centerRect.right + extraPx,
            bottom = centerRect.bottom + extraPx - with(density) { 8.dp.toPx() }
        )
    }
    val fallbackLogAnchorRect = remember(maxWidth) {
        val marginPx = with(density) { Spacing.Medium.toPx() }
        val topPx = with(density) { 260.dp.toPx() }
        val heightPx = with(density) { 88.dp.toPx() }
        val widthPx = with(density) { maxWidth.toPx() } - marginPx * 2
        Rect(marginPx, topPx, marginPx + widthPx, topPx + heightPx)
    }
    val targetLogEntryRect = remember(logAnchorRect, maxWidth) {
        if (logAnchorRect != null) {
            val topPx = logAnchorRect.bottom + with(density) { Spacing.Small.toPx() }
            val heightPx = with(density) { 88.dp.toPx() }
            val marginPx = with(density) { Spacing.Medium.toPx() }
            val widthPx = with(density) { maxWidth.toPx() } - marginPx * 2
            Rect(marginPx, topPx, marginPx + widthPx, topPx + heightPx)
        } else {
            fallbackLogAnchorRect
        }
    }

    return PresentationGeometry(
        centerXPx = centerXPx,
        baseSlotWidthPx = baseSlotWidthPx,
        buttonHeightPx = buttonHeightPx,
        buttonGapPx = buttonGapPx,
        centerRect = centerRect,
        anticipationRect = anticipationRect,
        targetLogEntryRect = targetLogEntryRect,
    )
}

internal fun lerpRect(start: Rect, end: Rect, t: Float): Rect = Rect(
    left = start.left + (end.left - start.left) * t,
    top = start.top + (end.top - start.top) * t,
    right = start.right + (end.right - start.right) * t,
    bottom = start.bottom + (end.bottom - start.bottom) * t
)

/**
 * Exact quadratic duplicate of the slot card layout ([FilledSlot]), upscaled to center stage,
 * morphing into a logbook entry format when smashing down into the completed log. The failure
 * overlay draws it too, whole and then in shards, always at [morphProgress] zero.
 */
@Composable
internal fun CelebrationCard(
    challenge: Challenge,
    rect: Rect,
    alpha: Float,
    baseSlotWidthPx: Float,
    morphProgress: Float = 0f,
    rotationZ: Float = 0f,
    scaleMultiplier: Float = 1f
) {
    if (alpha <= 0.001f) return

    val density = LocalDensity.current
    // A failed streak lifts out of its slot as the slot drew it: a Haze ring at the progress it
    // reached, under "BROKEN" (ChallengeSlotRow), not as a full ring it never earned.
    val failed = challenge.status == ChallengeStatus.FAILED
    // A lost race lifts out the same way: the pilot never reached the finish first, so the ring
    // shows how far they got, in the medal colour of their place, under the place itself.
    val lostPlace = finishedRacePlace(challenge)?.takeIf { it > 1 }
    val partialRing = failed || lostPlace != null
    val ringColor = when {
        failed -> Haze
        lostPlace == 2 -> Silver
        lostPlace == 3 -> Bronze
        lostPlace != null -> Haze
        else -> Amber
    }
    val slotLabelText = when {
        failed -> "BROKEN"
        lostPlace != null -> placeStamp(lostPlace)
        // Percent for distance and route, counts for set and streak, as in the slot.
        challenge.type == ChallengeType.SET_COMPLETION || challenge.type == ChallengeType.STREAK ->
            challengeRingLabel(challenge)
        else -> "100%"
    }
    // A shared pool's ring is split by pilot, as in its slot, so the celebration shows whose
    // team it was without a caption; a race, a broken streak and a solo row stay one colour.
    val ringSegments = if (challenge.isShared() && !failed && lostPlace == null && !partialRing &&
        challenge.type != ChallengeType.ROUTE
    ) {
        challenge.progressSegments().map { RingSegment(it.fraction, participantColor(it.participant.colorIndex, it.isSelf)) }
    } else {
        emptyList()
    }

    val dateStr = remember(challenge.completedAt, challenge.startedAt) {
        SimpleDateFormat("dd MMM yyyy", Locale.US).format(
            Date(challenge.completedAt ?: challenge.startedAt ?: System.currentTimeMillis())
        )
    }

    val widthDp = with(density) { rect.width.toDp() }
    val heightDp = with(density) { rect.height.toDp() }
    val scale = (rect.width / baseSlotWidthPx).coerceAtLeast(1f)

    // Corner radius proportionally scaled from the slot's 16.dp corner radius
    val cornerRadius = ((16.dp * scale) * (1f - morphProgress) + 8.dp * morphProgress).coerceAtLeast(6.dp)
    val bgColor = lerpColor(DeepNavy, LogbookParchment, morphProgress)

    Box(
        modifier = Modifier
            .offset { IntOffset(rect.left.roundToInt(), rect.top.roundToInt()) }
            .size(width = widthDp, height = heightDp)
            .graphicsLayer {
                this.rotationZ = rotationZ
                this.scaleX = scaleMultiplier
                this.scaleY = scaleMultiplier
                this.alpha = alpha
            }
            .clip(RoundedCornerShape(cornerRadius))
            .background(bgColor)
    ) {
        // ── Slot Layout (EXACT proportional match to FilledSlot, quadratic upscaled) ──
        if (morphProgress < 0.85f) {
            val slotAlpha = (1f - morphProgress * 1.5f).coerceIn(0f, 1f)
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .alpha(slotAlpha),
                contentAlignment = Alignment.Center
            ) {
                // Dimmed challenge icon watermark behind the ring - matches FilledSlot's Spacing.Large (24.dp)
                Icon(
                    imageVector = challenge.icon(),
                    contentDescription = null,
                    tint = Haze.copy(alpha = 0.15f),
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(Spacing.Large * scale)
                )
                // Progress ring, full and Amber for a completion, partial for a broken streak or a lost
                // race - matches FilledSlot's Spacing.Small (8.dp) and 5.dp stroke
                RingProgress(
                    // Whole ring for every outcome: the colour says which (gold, silver or bronze place,
                    // grey for a break), whatever the pilot had reached.
                    progress = 1f,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(Spacing.Small * scale),
                    strokeWidth = 5.dp * scale,
                    fillColor = ringColor,
                    segments = ringSegments
                ) {
                    val baseFontSize = when {
                        slotLabelText.length <= 3 -> 15f
                        slotLabelText.length == 4 -> 13f
                        slotLabelText.length == 5 -> 11.5f
                        else -> 10f
                    }
                    Text(
                        text = slotLabelText,
                        color = OffWhite,
                        maxLines = 1,
                        softWrap = false,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 6.dp * scale),
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            fontSize = (baseFontSize * scale).sp
                        )
                    )
                }
            }
        }

        // ── Logbook Paper Preview Layout (revealed as card smashes down) ──
        if (morphProgress > 0.25f) {
            val logAlpha = ((morphProgress - 0.25f) / 0.75f).coerceIn(0f, 1f)
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .alpha(logAlpha)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Red vertical margin line
                Box(
                    modifier = Modifier
                        .width(2.dp)
                        .height(56.dp)
                        .background(LogbookMarginRed.copy(alpha = 0.6f))
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = dateStr.uppercase(),
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 9.sp,
                                letterSpacing = 1.2.sp
                            ),
                            color = LogbookInkFaint
                        )
                        // The same single stamp the finished entry wears: the place in a shared race.
                        finishedRacePlace(challenge)?.let { place ->
                            LogStampPreview(text = placeStamp(place))
                        }
                    }
                    Text(
                        text = challenge.name,
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                            letterSpacing = 0.5.sp
                        ),
                        color = LogbookInkDark,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

/** The morph preview's rendering of a log entry's stamp: a thin red-inked box, not a badge. */
@Composable
private fun LogStampPreview(text: String) {
    Box(
        modifier = Modifier
            .border(1.dp, LogbookMarginRed.copy(alpha = 0.55f), RoundedCornerShape(3.dp))
            .padding(horizontal = 5.dp, vertical = 1.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall.copy(
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 9.sp,
                letterSpacing = 0.8.sp
            ),
            color = LogbookMarginRed.copy(alpha = 0.75f)
        )
    }
}

/**
 * The caption above a presented card: a column of [content] bottom-aligned to [gapPx] above
 * [cardRect], no wider than the card, faded by [alpha]. Draws nothing while fully transparent.
 *
 * The column sits on its own pill, because the scrim under it is only 70 to 80% opaque and the
 * tab labels ("CHALLENGES | ACHIEVEMENTS") show through exactly where the caption lands; text
 * over text was unreadable in both themes.
 */
@Composable
internal fun PresentationCaption(
    cardRect: Rect,
    gapPx: Float,
    alpha: Float,
    content: @Composable ColumnScope.() -> Unit
) {
    if (alpha <= 0.01f) return
    val density = LocalDensity.current
    val heightPx = with(density) { CaptionHeightDp.dp.toPx() }
    Box(
        modifier = Modifier
            .offset { IntOffset(cardRect.left.roundToInt(), (cardRect.top - gapPx - heightPx).roundToInt()) }
            .size(width = with(density) { cardRect.width.toDp() }, height = CaptionHeightDp.dp)
            .alpha(alpha),
        contentAlignment = Alignment.BottomCenter
    ) {
        Column(
            modifier = Modifier
                .background(Midnight.copy(alpha = 0.92f), RoundedCornerShape(12.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
            content = content
        )
    }
}

/**
 * One line of a caption's monospace headline: "YOU WON THE RACE", "STREAK BROKEN". Never wraps:
 * a headline that needs two lines is passed as two of these, so each line breaks where the copy
 * says and not where the card's width happens to.
 */
@Composable
internal fun PresentationHeadline(text: String, color: Color) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium.copy(
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            letterSpacing = 1.5.sp
        ),
        color = color,
        textAlign = TextAlign.Center,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
}

/**
 * The floating button under a presented card (no modal background): [label] on [background],
 * as wide as [cardRect] and [gapPx] below it, faded by [alpha]. Draws nothing while fully
 * transparent, so it never takes a tap the pilot cannot see.
 */
@Composable
internal fun PresentationButton(
    cardRect: Rect,
    gapPx: Float,
    heightPx: Float,
    alpha: Float,
    label: String,
    background: Color,
    textColor: Color,
    onClick: () -> Unit
) {
    if (alpha <= 0.01f) return
    val density = LocalDensity.current
    val buttonTopPx = cardRect.bottom + gapPx
    Box(
        modifier = Modifier
            .offset { IntOffset(cardRect.left.roundToInt(), buttonTopPx.roundToInt()) }
            .size(
                width = with(density) { cardRect.width.toDp() },
                height = with(density) { heightPx.toDp() }
            )
            .alpha(alpha)
            .clip(RoundedCornerShape(14.dp))
            .background(background)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {
                onClick()
            },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium.copy(
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 1.5.sp
            ),
            color = textColor
        )
    }
}
