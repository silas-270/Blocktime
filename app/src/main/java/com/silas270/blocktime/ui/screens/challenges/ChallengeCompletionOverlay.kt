package com.silas270.blocktime.ui.screens.challenges

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.silas270.blocktime.data.model.Challenge
import com.silas270.blocktime.domain.CompletionPresentation
import com.silas270.blocktime.ui.components.BadgeSize
import com.silas270.blocktime.ui.components.BadgeVariant
import com.silas270.blocktime.ui.components.FocusBadge
import com.silas270.blocktime.ui.theme.Amber
import com.silas270.blocktime.ui.theme.ChallengeGold
import com.silas270.blocktime.ui.theme.DeepNavy
import com.silas270.blocktime.ui.theme.Green
import com.silas270.blocktime.ui.theme.Haze
import com.silas270.blocktime.ui.theme.Midnight
import com.silas270.blocktime.ui.theme.OffWhite
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

private const val AnticipationDurationMs = 90
private const val SmashDurationMs = 300
private const val ImpactDurationMs = 220
private const val ConfettiBurstDurationMs = 2000
private const val ConfettiParticleCount = 75

private enum class CelebrationPhase {
    INITIAL_DELAY,
    FLYING_IN,
    PRESENTED,
    ANTICIPATION,
    SMASHING_DOWN,
    IMPACT
}

/**
 * The completion-presentation beat (docs/challenges.md): a just-finished challenge duplicates
 * from its slot, scales up to center stage with an ambient accent glow, bursts confetti once fully
 * settled, and on tap smashes down at high speed into the completed-challenges log, morphing into a
 * paper log entry with an impact shockwave. Renders nothing when [current] is null.
 *
 * [presentation] (docs/shared-challenges.md "Presentation") changes only what the settled card
 * says: a solo completion is as it always was; a shared pool wears a "CREW ×N" badge above the
 * card; a won race adds "YOU WON THE RACE" in gold; a lost race names the winner and the pilot's
 * place, fires half the confetti in cooler colours and closes on "GG" instead of "CONGRATS".
 */
@Composable
internal fun ChallengeCompletionOverlay(
    current: Challenge?,
    slotRect: Rect?,
    logAnchorRect: Rect?,
    presentation: CompletionPresentation = CompletionPresentation.Solo,
    isFirstCelebration: Boolean = true,
    onFlyInStart: () -> Unit = {},
    onCelebrated: (Int) -> Unit
) {
    if (current == null) return

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val geometry = rememberPresentationGeometry(
            slotRect = slotRect,
            logAnchorRect = logAnchorRect,
            maxWidth = maxWidth,
            maxHeight = maxHeight
        )
        val centerXPx = geometry.centerXPx
        val baseSlotWidthPx = geometry.baseSlotWidthPx
        val buttonHeightPx = geometry.buttonHeightPx
        val buttonGapPx = geometry.buttonGapPx
        val centerRect = geometry.centerRect
        val anticipationRect = geometry.anticipationRect
        val targetLogEntryRect = geometry.targetLogEntryRect

        // The copy is a pure function of the presentation, so it is derived once per card.
        val copy = remember(presentation) { CelebrationCopy.of(presentation) }

        val currentSlotRect by rememberUpdatedState(slotRect)
        val startRect = currentSlotRect ?: centerRect

        var phase by remember(current.id) { mutableStateOf(CelebrationPhase.INITIAL_DELAY) }
        val fraction = remember(current.id) { Animatable(0f) }
        var showConfetti by remember(current.id) { mutableStateOf(false) }
        val scope = rememberCoroutineScope()

        LaunchedEffect(current.id) {
            phase = CelebrationPhase.INITIAL_DELAY
            showConfetti = false
            fraction.snapTo(0f)

            // Wait briefly if slotRect is still measuring
            if (currentSlotRect == null) {
                withTimeoutOrNull(120) {
                    snapshotFlow { currentSlotRect }.first { it != null }
                }
            }

            // 1. Initial delay so pilot clearly sees where they are and registers their completed slot
            val delayMs = if (isFirstCelebration) InitialDelayMs else RepeatDelayMs
            delay(delayMs)

            // 2. Start flying to center with dynamic arc & camera overshoot
            onFlyInStart()
            phase = CelebrationPhase.FLYING_IN
            val flyInJob = launch {
                fraction.animateTo(
                    targetValue = 1f,
                    animationSpec = tween(FlyInDurationMs, easing = FlyInEasing)
                )
            }

            // 3. Fire confetti and apex flash right as the card snaps into center stage (74% through flight)
            delay((FlyInDurationMs * 0.74f).toLong())
            showConfetti = true
            flyInJob.join()

            // 4. Settled at center stage
            phase = CelebrationPhase.PRESENTED
        }

        val t = fraction.value

        val animatedRect = when (phase) {
            CelebrationPhase.INITIAL_DELAY -> startRect
            CelebrationPhase.FLYING_IN -> lerpRect(startRect, centerRect, t)
            CelebrationPhase.PRESENTED -> centerRect
            CelebrationPhase.ANTICIPATION -> lerpRect(centerRect, anticipationRect, fraction.value)
            CelebrationPhase.SMASHING_DOWN -> lerpRect(anticipationRect, targetLogEntryRect, fraction.value)
            CelebrationPhase.IMPACT -> targetLogEntryRect
        }

        val scaleMultiplier = when (phase) {
            CelebrationPhase.FLYING_IN -> 1f + 0.10f * sin((t * Math.PI).toFloat())
            CelebrationPhase.ANTICIPATION -> 1f + 0.04f * fraction.value
            else -> 1f
        }

        val startOffsetX = startRect.center.x - centerRect.center.x
        val maxTiltDeg = (startOffsetX / centerXPx).coerceIn(-1f, 1f) * 6.5f
        val currentRotation = when (phase) {
            CelebrationPhase.FLYING_IN -> maxTiltDeg * (1f - t) * cos(t * Math.PI.toFloat() * 1.5f)
            CelebrationPhase.ANTICIPATION -> -1.5f * fraction.value
            else -> 0f
        }

        val morphProgress = when (phase) {
            CelebrationPhase.INITIAL_DELAY,
            CelebrationPhase.FLYING_IN,
            CelebrationPhase.PRESENTED,
            CelebrationPhase.ANTICIPATION -> 0f
            CelebrationPhase.SMASHING_DOWN -> fraction.value
            CelebrationPhase.IMPACT -> 1f
        }

        val cardAlpha = when (phase) {
            CelebrationPhase.INITIAL_DELAY -> 0f
            CelebrationPhase.FLYING_IN,
            CelebrationPhase.PRESENTED,
            CelebrationPhase.ANTICIPATION,
            CelebrationPhase.SMASHING_DOWN -> 1f
            CelebrationPhase.IMPACT -> 0f
        }

        val scrimAlpha = when (phase) {
            CelebrationPhase.INITIAL_DELAY -> 0f
            CelebrationPhase.FLYING_IN -> fraction.value * 0.7f
            CelebrationPhase.PRESENTED,
            CelebrationPhase.ANTICIPATION -> 0.7f
            CelebrationPhase.SMASHING_DOWN -> 0.7f * (1f - fraction.value)
            CelebrationPhase.IMPACT -> 0f
        }

        fun startClosingAnimation() {
            if (phase == CelebrationPhase.PRESENTED) {
                scope.launch {
                    // Anticipation: brief slight recoil before the smash
                    phase = CelebrationPhase.ANTICIPATION
                    fraction.snapTo(0f)
                    fraction.animateTo(1f, tween(AnticipationDurationMs, easing = FastOutSlowInEasing))

                    // High-speed smash into the log
                    phase = CelebrationPhase.SMASHING_DOWN
                    fraction.snapTo(0f)
                    fraction.animateTo(
                        1f,
                        tween(SmashDurationMs, easing = CubicBezierEasing(0.35f, 0f, 0.75f, 0.15f))
                    )

                    // Impact shockwave & commit celebration
                    phase = CelebrationPhase.IMPACT
                    onCelebrated(current.id)
                    fraction.snapTo(0f)
                    fraction.animateTo(1f, tween(ImpactDurationMs, easing = FastOutSlowInEasing))
                }
            }
        }

        // Only while the scrim is actually visible. During INITIAL_DELAY it is fully transparent,
        // and after IMPACT it has faded out again - intercepting taps then swallowed every touch
        // on the screen (the app bar's back arrow included) with nothing on screen to explain why.
        val interceptsInput = phase != CelebrationPhase.INITIAL_DELAY && phase != CelebrationPhase.IMPACT

        // System back does what a scrim tap does: once presented it smashes the card into the log,
        // and mid-animation it is swallowed. Without this, back fell through to whatever was
        // underneath - an open modal, or the screen itself - while the celebration kept playing.
        BackHandler(enabled = interceptsInput) { startClosingAnimation() }

        // Scrim - intercepts taps and initiates the smash into the log
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

        // ── Accent Glow around centered card (Square shape with rounded corners matching the card, native blur) ──
        val infiniteTransition = rememberInfiniteTransition(label = "glow_pulse")
        val glowPulse by infiniteTransition.animateFloat(
            initialValue = 0.55f,
            targetValue = 0.90f,
            animationSpec = infiniteRepeatable(
                animation = tween(1200, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "glow_alpha"
        )

        val glowAlpha = when (phase) {
            CelebrationPhase.INITIAL_DELAY -> 0f
            CelebrationPhase.FLYING_IN -> fraction.value * glowPulse
            CelebrationPhase.PRESENTED,
            CelebrationPhase.ANTICIPATION -> glowPulse
            CelebrationPhase.SMASHING_DOWN -> (1f - fraction.value).coerceAtLeast(0f) * glowPulse
            CelebrationPhase.IMPACT -> 0f
        }

        val currentScale = (animatedRect.width / baseSlotWidthPx).coerceAtLeast(1f)
        val currentCornerRadius = ((16.dp * currentScale) * (1f - morphProgress) + 8.dp * morphProgress).coerceAtLeast(6.dp)

        if (glowAlpha > 0.01f) {
            val blurRadiusDp = (32 * (currentScale / 2.5f)).coerceIn(24f, 44f).dp
            val radiusPx = with(density) { currentCornerRadius.toPx() }
            val maxSpreadPx = with(density) { blurRadiusDp.toPx() }

            Canvas(modifier = Modifier.fillMaxSize()) {
                val baseRect = animatedRect
                val steps = 14
                for (i in steps downTo 1) {
                    val progress = i.toFloat() / steps
                    val spread = progress * maxSpreadPx
                    val alphaFactor = (1f - progress) * (1f - progress)
                    val layerAlpha = (0.28f * glowAlpha * alphaFactor).coerceIn(0f, 1f)

                    drawRoundRect(
                        color = Amber.copy(alpha = layerAlpha),
                        topLeft = Offset(baseRect.left - spread, baseRect.top - spread),
                        size = Size(baseRect.width + spread * 2f, baseRect.height + spread * 2f),
                        cornerRadius = CornerRadius(radiusPx + spread)
                    )
                }

                // Crisp accent border directly outlining the card's edge
                drawRoundRect(
                    color = Amber.copy(alpha = 0.65f * glowAlpha),
                    topLeft = baseRect.topLeft,
                    size = baseRect.size,
                    cornerRadius = CornerRadius(radiusPx),
                    style = Stroke(width = with(density) { 1.5.dp.toPx() })
                )
            }
        }

        // ── Apex Arrival Flash (radial bloom explosion at the apex punch) ──
        val apexFlashAlpha = remember(current.id) { Animatable(0f) }
        val apexFlashRadius = remember(current.id) { Animatable(0.4f) }
        LaunchedEffect(showConfetti) {
            if (showConfetti) {
                launch {
                    apexFlashAlpha.snapTo(0.90f)
                    apexFlashAlpha.animateTo(0f, tween(480, easing = FastOutSlowInEasing))
                }
                launch {
                    apexFlashRadius.snapTo(0.4f)
                    apexFlashRadius.animateTo(1.75f, tween(480, easing = FastOutSlowInEasing))
                }
            }
        }

        if (apexFlashAlpha.value > 0.01f) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val center = Offset(centerRect.center.x, centerRect.center.y)
                val baseRadius = centerRect.width * 0.55f
                val radius = baseRadius * apexFlashRadius.value
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Amber.copy(alpha = apexFlashAlpha.value * 0.80f),
                            ChallengeGold.copy(alpha = apexFlashAlpha.value * 0.40f),
                            Color.Transparent
                        ),
                        center = center,
                        radius = radius
                    ),
                    center = center,
                    radius = radius
                )
            }
        }

        // ── Confetti Burst (fires right at apex punch) ──
        if (showConfetti) {
            RadialConfettiBurst(
                trigger = true,
                centerPx = Offset(centerRect.center.x, centerRect.center.y),
                key = current.id,
                count = copy.confettiCount,
                colors = copy.confettiColors
            )
        }

        // ── The Card (Exact upscaled layout of slot, morphs on smash) ──
        CelebrationCard(
            challenge = current,
            rect = animatedRect,
            alpha = cardAlpha,
            rotationZ = currentRotation,
            scaleMultiplier = scaleMultiplier,
            baseSlotWidthPx = baseSlotWidthPx,
            morphProgress = morphProgress
        )

        // ── Caption above and floating "CONGRATS" button under the card (no modal background) ──
        val buttonAlpha = when (phase) {
            CelebrationPhase.PRESENTED -> 1f
            else -> 0f
        }
        val animatedButtonAlpha by animateFloatAsState(
            targetValue = buttonAlpha,
            animationSpec = tween(PresentedFadeDurationMs, easing = FastOutSlowInEasing),
            label = "congrats_button_alpha"
        )

        // A solo completion has no caption; a shared one says what happened, in the same fade
        // as the button so nothing appears before the card has settled.
        if (copy.headline != null || copy.crewBadge != null) {
            PresentationCaption(cardRect = animatedRect, gapPx = buttonGapPx, alpha = animatedButtonAlpha) {
                copy.headline?.let { headline ->
                    PresentationHeadline(text = headline, color = copy.headlineColor)
                }
                copy.crewBadge?.let { badge ->
                    FocusBadge(
                        text = badge,
                        variant = BadgeVariant.Neutral,
                        size = BadgeSize.Compact
                    )
                }
            }
        }

        PresentationButton(
            cardRect = animatedRect,
            gapPx = buttonGapPx,
            heightPx = buttonHeightPx,
            alpha = animatedButtonAlpha,
            label = copy.buttonLabel,
            background = Amber,
            textColor = DeepNavy,
            onClick = { startClosingAnimation() }
        )

        // ── Impact Shockwave at log entry site ──
        if (phase == CelebrationPhase.IMPACT) {
            val impactT = fraction.value
            Canvas(modifier = Modifier.fillMaxSize()) {
                val baseRect = targetLogEntryRect
                val expandPx = impactT * with(density) { 70.dp.toPx() }
                val rippleRect = Rect(
                    left = baseRect.left - expandPx,
                    top = baseRect.top - expandPx * 0.35f,
                    right = baseRect.right + expandPx,
                    bottom = baseRect.bottom + expandPx * 0.35f
                )
                val shockAlpha = (1f - impactT).coerceIn(0f, 1f)
                // Golden expanding shockwave ring
                drawRoundRect(
                    color = Amber.copy(alpha = shockAlpha * 0.8f),
                    topLeft = rippleRect.topLeft,
                    size = rippleRect.size,
                    cornerRadius = CornerRadius(14.dp.toPx()),
                    style = Stroke(width = (4 * (1f - impactT)).dp.toPx())
                )
                // Flash on the impacted card
                drawRoundRect(
                    color = Color.White.copy(alpha = shockAlpha * 0.40f),
                    topLeft = baseRect.topLeft,
                    size = baseRect.size,
                    cornerRadius = CornerRadius(8.dp.toPx())
                )
            }
        }
    }
}

/**
 * Everything a [CompletionPresentation] changes about the overlay, resolved once per card: the
 * headline and badge above it, the button's label, and how much confetti in which colours.
 */
private class CelebrationCopy(
    val headline: String?,
    val headlineColor: Color,
    val crewBadge: String?,
    val buttonLabel: String,
    val confettiCount: Int,
    val confettiColors: List<Color>
) {
    companion object {
        fun of(presentation: CompletionPresentation): CelebrationCopy = when (presentation) {
            CompletionPresentation.Solo -> CelebrationCopy(
                headline = null,
                headlineColor = OffWhite,
                crewBadge = null,
                buttonLabel = "CONGRATS",
                confettiCount = ConfettiParticleCount,
                confettiColors = ConfettiColors
            )
            is CompletionPresentation.Team -> CelebrationCopy(
                headline = null,
                headlineColor = OffWhite,
                crewBadge = "CREW ×${presentation.crewSize}",
                buttonLabel = "CONGRATS",
                confettiCount = ConfettiParticleCount,
                confettiColors = ConfettiColors
            )
            is CompletionPresentation.RaceWon -> CelebrationCopy(
                headline = "YOU WON THE RACE",
                headlineColor = ChallengeGold,
                crewBadge = "CREW ×${presentation.crewSize}",
                buttonLabel = "CONGRATS",
                confettiCount = ConfettiParticleCount,
                confettiColors = ConfettiColors
            )
            is CompletionPresentation.RacePlaced -> CelebrationCopy(
                headline = "${presentation.winnerName.uppercase(Locale.US)} WON · YOU FINISHED " +
                    "${presentation.place}${ordinalSuffix(presentation.place).uppercase(Locale.US)}",
                headlineColor = OffWhite,
                crewBadge = null,
                buttonLabel = "GG",
                confettiCount = ConfettiParticleCount / 2,
                confettiColors = PlacedConfettiColors
            )
        }
    }
}

private data class BurstParticle(
    val angleRad: Float,
    val velocityPx: Float,
    val fallDurationMs: Int,
    val delayMs: Int,
    val color: Color,
    val sizePx: Float,
    val rotationSpeedDegPerMs: Float
)

private val ConfettiColors = listOf(
    ChallengeGold,
    Amber,
    Green,
    OffWhite,
    Color(0xFFFFD54F),
    Color(0xFFFFB74D)
)

/** The cooler burst of a race somebody else won: no gold, no green, just paper and sky. */
private val PlacedConfettiColors = listOf(
    OffWhite,
    Haze,
    Color(0xFF90CAF9),
    Color(0xFFB0BEC5)
)

/**
 * Adapted from `ChallengeOutcomeScreen`'s `ConfettiOverlay` - same `Animatable`-driven,
 * particle-data-class-plus-`remember`, single-`Canvas`-with-`rotate` approach, but launched
 * outward from [centerPx] in every direction instead of falling from off-screen. [key] resets the
 * particle set and re-fires the burst for a new challenge (that composable isn't reused directly
 * here since it's `private` to its own file and shaped for full-screen rain, not a burst behind a
 * card). [count] particles are spread evenly around the circle and coloured from [colors].
 */
@Composable
private fun RadialConfettiBurst(
    trigger: Boolean,
    centerPx: Offset,
    key: Any,
    count: Int = ConfettiParticleCount,
    colors: List<Color> = ConfettiColors
) {
    if (!trigger) return

    val particles = remember(key) {
        List(count) { i ->
            val baseAngle = (i.toFloat() / count.toFloat()) * (2f * Math.PI.toFloat())
            val jitter = (Random.nextFloat() - 0.5f) * 0.35f
            BurstParticle(
                angleRad = baseAngle + jitter,
                velocityPx = Random.nextInt(320, 800).toFloat(),
                fallDurationMs = Random.nextInt(1000, ConfettiBurstDurationMs),
                delayMs = Random.nextInt(0, 140),
                color = colors[Random.nextInt(colors.size)],
                sizePx = Random.nextInt(6, 15).toFloat(),
                rotationSpeedDegPerMs = Random.nextFloat() * 0.8f - 0.4f
            )
        }
    }
    val elapsedMs = remember(key) { Animatable(0f) }
    LaunchedEffect(key) {
        elapsedMs.snapTo(0f)
        elapsedMs.animateTo(
            targetValue = ConfettiBurstDurationMs.toFloat(),
            animationSpec = tween(durationMillis = ConfettiBurstDurationMs, easing = LinearEasing)
        )
    }

    Canvas(modifier = Modifier.fillMaxSize()) {
        val now = elapsedMs.value
        particles.forEach { p ->
            val localElapsed = now - p.delayMs
            if (localElapsed < 0f) return@forEach
            val t = (localElapsed / p.fallDurationMs).coerceIn(0f, 1f)
            if (t >= 1f) return@forEach // Fully finished and faded out

            // Eases outward then settles
            val outwardEase = 1f - (1f - t) * (1f - t)
            val travel = p.velocityPx * outwardEase

            // Gravity gently pulls the confetti down
            val gravityDrop = 120f * t * t

            val x = centerPx.x + cos(p.angleRad) * travel
            val y = centerPx.y + sin(p.angleRad) * travel + gravityDrop

            // Smooth fadeout in the second half of life down to 0
            val alpha = if (t < 0.45f) {
                1f
            } else {
                (1f - (t - 0.45f) / 0.55f).coerceIn(0f, 1f)
            }
            if (alpha <= 0.01f) return@forEach

            rotate(degrees = localElapsed * p.rotationSpeedDegPerMs, pivot = Offset(x, y)) {
                drawRect(
                    color = p.color.copy(alpha = alpha),
                    topLeft = Offset(x - p.sizePx / 2f, y - p.sizePx / 2f),
                    size = Size(p.sizePx, p.sizePx * 1.6f)
                )
            }
        }
    }
}
