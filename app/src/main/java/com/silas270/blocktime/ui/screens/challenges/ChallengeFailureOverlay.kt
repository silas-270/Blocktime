package com.silas270.blocktime.ui.screens.challenges

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.silas270.blocktime.data.model.Challenge
import com.silas270.blocktime.domain.FailurePresentation
import com.silas270.blocktime.ui.theme.Danger
import com.silas270.blocktime.ui.theme.Midnight
import com.silas270.blocktime.ui.theme.OffWhite
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.random.Random

private const val CrackDurationMs = 220
private const val ShatterDurationMs = 700
private const val FailureScrimAlpha = 0.8f
private const val ShardColumns = 3
private const val ShardRows = 3
/** The shards fall under this, in dp per second squared. */
private const val ShardGravityDpPerS2 = 2400f
/** The shards are fully opaque until this far through the shatter, then fade to nothing. */
private const val ShardFadeStart = 0.6f

private enum class FailurePhase {
    INITIAL_DELAY,
    FLYING_IN,
    PRESENTED,
    CRACK,
    SHATTER,
    DONE
}

/**
 * The shatter (docs/shared-challenges.md "Presentation"): a failed shared streak lifts out of its
 * slot to centre stage under a darker scrim, the same beat as a completion but without the
 * confetti, the glow or the flash, says "STREAK BROKEN" and who missed the day, and on a tap
 * (scrim, button or system back) shakes, cracks and falls apart into shards. Once the shards are
 * gone [onPresented] fires with the row's id and the overlay draws nothing until [current]
 * changes; the screen then deletes the row and the slot frees. Renders nothing when [current]
 * is null.
 */
@Composable
internal fun ChallengeFailureOverlay(
    current: Challenge?,
    presentation: FailurePresentation,
    slotRect: Rect?,
    isFirst: Boolean,
    onFlyInStart: () -> Unit,
    onPresented: (Int) -> Unit
) {
    if (current == null) return

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val geometry = rememberPresentationGeometry(
            slotRect = slotRect,
            logAnchorRect = null,
            maxWidth = maxWidth,
            maxHeight = maxHeight
        )
        val centerRect = geometry.centerRect

        // The copy is a pure function of the presentation, so it is derived once per card.
        val subline = remember(presentation) {
            when (presentation) {
                is FailurePresentation.StreakBroken -> when {
                    presentation.bySelf -> "You missed a day"
                    presentation.brokenByName != null -> "${presentation.brokenByName} missed a day"
                    else -> "A crew member missed a day"
                }
            }
        }

        val currentSlotRect by rememberUpdatedState(slotRect)
        val startRect = currentSlotRect ?: centerRect

        var phase by remember(current.id) { mutableStateOf(FailurePhase.INITIAL_DELAY) }
        val fraction = remember(current.id) { Animatable(0f) }
        val scope = rememberCoroutineScope()

        LaunchedEffect(current.id) {
            phase = FailurePhase.INITIAL_DELAY
            fraction.snapTo(0f)

            // Wait briefly if slotRect is still measuring
            if (currentSlotRect == null) {
                withTimeoutOrNull(120) {
                    snapshotFlow { currentSlotRect }.first { it != null }
                }
            }

            // 1. The same pause as a completion, so the pilot registers the broken slot first
            val delayMs = if (isFirst) InitialDelayMs else RepeatDelayMs
            delay(delayMs)

            // 2. Lift off to centre stage; the slot row compacts underneath
            onFlyInStart()
            phase = FailurePhase.FLYING_IN
            fraction.animateTo(
                targetValue = 1f,
                animationSpec = tween(FlyInDurationMs, easing = FlyInEasing)
            )

            // 3. Settled at centre stage
            phase = FailurePhase.PRESENTED
        }

        val t = fraction.value

        // The shake while the card cracks: a few dp side to side, several times over the phase.
        val shakeDx = if (phase == FailurePhase.CRACK) with(density) { 3.dp.toPx() } * sin(t * 40f) else 0f

        val animatedRect = when (phase) {
            FailurePhase.INITIAL_DELAY -> startRect
            FailurePhase.FLYING_IN -> lerpRect(startRect, centerRect, t)
            FailurePhase.PRESENTED,
            FailurePhase.SHATTER,
            FailurePhase.DONE -> centerRect
            FailurePhase.CRACK -> centerRect.translate(shakeDx, 0f)
        }

        val scaleMultiplier = when (phase) {
            FailurePhase.FLYING_IN -> 1f + 0.10f * sin((t * Math.PI).toFloat())
            else -> 1f
        }

        val startOffsetX = startRect.center.x - centerRect.center.x
        val maxTiltDeg = (startOffsetX / geometry.centerXPx).coerceIn(-1f, 1f) * 6.5f
        val currentRotation = when (phase) {
            FailurePhase.FLYING_IN -> maxTiltDeg * (1f - t) * cos(t * Math.PI.toFloat() * 1.5f)
            else -> 0f
        }

        // The whole card, until the shards take over
        val cardAlpha = when (phase) {
            FailurePhase.INITIAL_DELAY,
            FailurePhase.SHATTER,
            FailurePhase.DONE -> 0f
            FailurePhase.FLYING_IN,
            FailurePhase.PRESENTED,
            FailurePhase.CRACK -> 1f
        }

        val scrimAlpha = when (phase) {
            FailurePhase.INITIAL_DELAY -> 0f
            FailurePhase.FLYING_IN -> fraction.value * FailureScrimAlpha
            FailurePhase.PRESENTED,
            FailurePhase.CRACK -> FailureScrimAlpha
            FailurePhase.SHATTER -> FailureScrimAlpha * (1f - fraction.value)
            FailurePhase.DONE -> 0f
        }

        fun startClosingAnimation() {
            if (phase == FailurePhase.PRESENTED) {
                scope.launch {
                    // Crack: the card shakes while the cracks spread over it
                    phase = FailurePhase.CRACK
                    fraction.snapTo(0f)
                    fraction.animateTo(1f, tween(CrackDurationMs, easing = LinearEasing))

                    // Shatter: the shards fall away
                    phase = FailurePhase.SHATTER
                    fraction.snapTo(0f)
                    fraction.animateTo(1f, tween(ShatterDurationMs, easing = LinearEasing))

                    // Gone: commit the presentation once, then draw nothing
                    phase = FailurePhase.DONE
                    onPresented(current.id)
                }
            }
        }

        // Only while the scrim is actually visible, as in the celebration: during INITIAL_DELAY
        // it is fully transparent, and after DONE it has faded out again.
        val interceptsInput = phase != FailurePhase.INITIAL_DELAY && phase != FailurePhase.DONE

        // System back does what a scrim tap does: once presented it shatters the card, and
        // mid-animation it is swallowed.
        BackHandler(enabled = interceptsInput) { startClosingAnimation() }

        // Scrim - darker than the celebration's, intercepts taps and starts the shatter
        val scrimInteractionSource = remember { MutableInteractionSource() }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Midnight.copy(alpha = scrimAlpha))
                .then(
                    if (interceptsInput) {
                        Modifier.clickable(
                            interactionSource = scrimInteractionSource,
                            indication = null
                        ) {
                            startClosingAnimation()
                        }
                    } else {
                        Modifier
                    }
                )
        )

        // ── The Card (whole) ──
        CelebrationCard(
            challenge = current,
            rect = animatedRect,
            alpha = cardAlpha,
            rotationZ = currentRotation,
            scaleMultiplier = scaleMultiplier,
            baseSlotWidthPx = geometry.baseSlotWidthPx
        )

        // ── Cracks over the shaking card ──
        // Deterministic per row: the same card cracks the same way every time it is drawn.
        val cracks = remember(current.id, centerRect) { crackPaths(current.id, centerRect) }
        if (phase == FailurePhase.CRACK) {
            val crackAlpha = t.coerceIn(0f, 1f)
            val strokePx = with(density) { 1.5.dp.toPx() }
            val cornerPx = with(density) {
                ((16.dp * (centerRect.width / geometry.baseSlotWidthPx).coerceAtLeast(1f))).coerceAtLeast(6.dp).toPx()
            }
            Canvas(modifier = Modifier.fillMaxSize()) {
                translate(left = shakeDx, top = 0f) {
                    val cardOutline = Path().apply {
                        addRoundRect(RoundRect(centerRect, CornerRadius(cornerPx)))
                    }
                    clipPath(cardOutline) {
                        cracks.forEach { crack ->
                            drawPath(
                                path = crack,
                                color = OffWhite,
                                alpha = crackAlpha,
                                style = Stroke(width = strokePx)
                            )
                        }
                    }
                }
            }
        }

        // ── Shards falling away ──
        val shards = remember(current.id, centerRect, density) { shatterShards(current.id, centerRect, density) }
        if (phase == FailurePhase.SHATTER) {
            val seconds = t * ShatterDurationMs / 1000f
            val gravityPx = with(density) { ShardGravityDpPerS2.dp.toPx() }
            val shardAlpha = if (t < ShardFadeStart) {
                1f
            } else {
                (1f - (t - ShardFadeStart) / (1f - ShardFadeStart)).coerceIn(0f, 1f)
            }
            shards.forEach { shard ->
                val dx = shard.driftPxPerS * seconds
                val dy = shard.liftPxPerS * seconds + 0.5f * gravityPx * seconds * seconds
                val spin = shard.spinDeg * t
                // Each shard is the whole card, clipped to its own polygon and moved as one
                // layer, so nine cheap clips replace one expensive re-render per shard.
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            transformOrigin = TransformOrigin(
                                pivotFractionX = shard.pivot.x / size.width,
                                pivotFractionY = shard.pivot.y / size.height
                            )
                            translationX = dx
                            translationY = dy
                            rotationZ = spin
                            alpha = shardAlpha
                        }
                        .drawWithContent {
                            clipPath(shard.path) {
                                this@drawWithContent.drawContent()
                            }
                        }
                ) {
                    CelebrationCard(
                        challenge = current,
                        rect = centerRect,
                        alpha = 1f,
                        baseSlotWidthPx = geometry.baseSlotWidthPx
                    )
                }
            }
        }

        // ── Caption above and floating "DAMN" button under the card ──
        val presentedAlpha = when (phase) {
            FailurePhase.PRESENTED -> 1f
            else -> 0f
        }
        val animatedPresentedAlpha by animateFloatAsState(
            targetValue = presentedAlpha,
            animationSpec = tween(PresentedFadeDurationMs, easing = FastOutSlowInEasing),
            label = "damn_button_alpha"
        )

        // One line each, like the celebration's headline: the pill is sized for the copy, not
        // for a name long enough to wrap, which is cut short instead.
        PresentationCaption(cardRect = animatedRect, gapPx = geometry.buttonGapPx, alpha = animatedPresentedAlpha) {
            PresentationHeadline(text = "STREAK BROKEN", color = Danger)
            Text(
                text = subline,
                style = MaterialTheme.typography.bodyMedium,
                color = OffWhite,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        PresentationButton(
            cardRect = animatedRect,
            gapPx = geometry.buttonGapPx,
            heightPx = geometry.buttonHeightPx,
            alpha = animatedPresentedAlpha,
            label = "DAMN",
            background = Danger,
            textColor = OffWhite,
            onClick = { startClosingAnimation() }
        )
    }
}

/**
 * Three to five cracks, each a jagged polyline from a point near the card's middle out past its
 * edge (the caller clips to the card), in window coordinates. Seeded with the row's id so a
 * recomposition mid-shake draws the same cracks.
 */
private fun crackPaths(seed: Int, card: Rect): List<Path> {
    val random = Random(seed)
    val count = 3 + random.nextInt(3)
    val origin = Offset(
        x = card.left + card.width * (0.4f + random.nextFloat() * 0.2f),
        y = card.top + card.height * (0.4f + random.nextFloat() * 0.2f)
    )
    val step = max(card.width, card.height) * 0.22f
    return List(count) { i ->
        var angle = (i.toFloat() / count) * (2f * Math.PI.toFloat()) + (random.nextFloat() - 0.5f) * 0.8f
        var x = origin.x
        var y = origin.y
        Path().apply {
            moveTo(x, y)
            repeat(5) {
                x += cos(angle) * step
                y += sin(angle) * step
                lineTo(x, y)
                angle += (random.nextFloat() - 0.5f) * 1.1f
            }
        }
    }
}

/**
 * One piece of the shattered card: its polygon in window coordinates, the point it spins about,
 * and its own drift, initial lift and total spin.
 */
private class Shard(
    val path: Path,
    val pivot: Offset,
    val driftPxPerS: Float,
    val liftPxPerS: Float,
    val spinDeg: Float
)

/**
 * The card cut into a [ShardColumns] by [ShardRows] grid whose inner vertices are nudged so no two
 * shards are the same shape. The nudge is at most a fifth of a cell each way, which keeps every
 * cell a convex quad, so the clip paths never fold over themselves. Seeded with the row's id so
 * the shards do not change shape between frames.
 */
private fun shatterShards(seed: Int, card: Rect, density: Density): List<Shard> {
    val random = Random(seed)
    val cellWidth = card.width / ShardColumns
    val cellHeight = card.height / ShardRows
    val xs = Array(ShardRows + 1) { FloatArray(ShardColumns + 1) }
    val ys = Array(ShardRows + 1) { FloatArray(ShardColumns + 1) }
    for (row in 0..ShardRows) {
        for (column in 0..ShardColumns) {
            val jitterX = if (column in 1 until ShardColumns) (random.nextFloat() - 0.5f) * cellWidth * 0.4f else 0f
            val jitterY = if (row in 1 until ShardRows) (random.nextFloat() - 0.5f) * cellHeight * 0.4f else 0f
            xs[row][column] = card.left + cellWidth * column + jitterX
            ys[row][column] = card.top + cellHeight * row + jitterY
        }
    }
    val maxDriftPx = with(density) { 180.dp.toPx() }
    val maxLiftPx = with(density) { 240.dp.toPx() }
    val shards = ArrayList<Shard>(ShardColumns * ShardRows)
    for (row in 0 until ShardRows) {
        for (column in 0 until ShardColumns) {
            val path = Path().apply {
                moveTo(xs[row][column], ys[row][column])
                lineTo(xs[row][column + 1], ys[row][column + 1])
                lineTo(xs[row + 1][column + 1], ys[row + 1][column + 1])
                lineTo(xs[row + 1][column], ys[row + 1][column])
                close()
            }
            val pivot = Offset(
                x = (xs[row][column] + xs[row][column + 1] + xs[row + 1][column + 1] + xs[row + 1][column]) / 4f,
                y = (ys[row][column] + ys[row][column + 1] + ys[row + 1][column + 1] + ys[row + 1][column]) / 4f
            )
            shards += Shard(
                path = path,
                pivot = pivot,
                // Outer columns drift outward, the middle one either way; every shard pops up a
                // little before gravity takes it, and spins up to 40 degrees either way.
                driftPxPerS = (random.nextFloat() * 0.5f + 0.5f) * maxDriftPx * when (column) {
                    0 -> -1f
                    ShardColumns - 1 -> 1f
                    else -> if (random.nextBoolean()) 1f else -1f
                },
                liftPxPerS = -random.nextFloat() * maxLiftPx,
                spinDeg = (random.nextFloat() - 0.5f) * 80f
            )
        }
    }
    return shards
}
