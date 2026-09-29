package com.silas270.blocktime.ui.screens.challenge

import com.silas270.blocktime.ui.theme.Success
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.PaddingValues
import com.silas270.blocktime.data.model.Challenge
import com.silas270.blocktime.data.model.ChallengeType
import com.silas270.blocktime.data.model.predefinedRoute
import com.silas270.blocktime.data.repository.ChallengeOutcome
import com.silas270.blocktime.util.formatMiles
import com.silas270.blocktime.util.kmToMiles
import java.util.Locale
import com.silas270.blocktime.ui.components.BadgeSize
import com.silas270.blocktime.ui.components.BadgeStyle
import com.silas270.blocktime.ui.components.BadgeVariant
import com.silas270.blocktime.ui.components.CaptionLabel
import com.silas270.blocktime.ui.components.CardVariant
import com.silas270.blocktime.ui.components.FocusBadge
import com.silas270.blocktime.ui.components.FocusButton
import com.silas270.blocktime.ui.components.FocusCard
import com.silas270.blocktime.ui.components.FocusInfoRow
import com.silas270.blocktime.ui.components.ChallengeProgressBar
import com.silas270.blocktime.ui.components.challengeIcon
import com.silas270.blocktime.ui.components.challengeTypeLabel
import com.silas270.blocktime.ui.theme.Amber
import com.silas270.blocktime.ui.theme.Border
import com.silas270.blocktime.ui.theme.ChallengeGold
import com.silas270.blocktime.ui.theme.DeepNavy
import com.silas270.blocktime.ui.theme.Green
import com.silas270.blocktime.ui.theme.Haze
import com.silas270.blocktime.ui.theme.Midnight
import com.silas270.blocktime.ui.theme.OffWhite
import com.silas270.blocktime.ui.theme.Spacing
import kotlinx.coroutines.delay
import kotlin.math.roundToInt
import kotlin.random.Random

private const val AdvanceDurationMs = 1400
private const val CompleteDurationMs = 1200
private const val StartBeatMs = 250

/**
 * The post-landing challenge beat (docs/challenges.md's "Per-leg progress feedback" and
 * "Completion presentation") - shown after the existing rank-stamp `ArrivalCelebrationScreen`,
 * never instead of it, whenever this landing changed one or more active challenges. Replaces what
 * used to be two separate screens (a single-bar tick-up screen and a single-bar completion
 * screen): a single landing can change up to 3 active challenges at once
 * (`MAX_ACTIVE_CHALLENGES`), so this renders one card with one bar per [outcomes] entry rather
 * than picking a single winner to show.
 */
@Composable
fun ChallengeOutcomeScreen(
    outcomes: List<ChallengeOutcome>,
    /** Reads a challenge's current row, so each progress line can be spelled out in its own
     *  units ("Leg 3/5", "2,600 / 6,200 mi") rather than only as a percentage. Null, or a
     *  lookup that finds nothing, falls back to the percentage. */
    loadChallenge: (suspend (Int) -> Challenge?)? = null,
    onContinue: () -> Unit
) {
    BackHandler { onContinue() }

    val progressLabels by produceState(emptyMap<Int, String>(), outcomes) {
        val lookup = loadChallenge ?: return@produceState
        value = outcomes.mapNotNull { outcome ->
            runCatching { lookup(outcome.challengeId) }.getOrNull()
                ?.let(::outcomeProgressLabel)
                ?.let { outcome.challengeId to it }
        }.toMap()
    }

    val completedCount = outcomes.count { it is ChallengeOutcome.Completed }
    val advancedCount = outcomes.count { it is ChallengeOutcome.Advanced }
    val hasAdvance = advancedCount > 0
    val settleDelayMs = StartBeatMs + if (hasAdvance) AdvanceDurationMs else CompleteDurationMs

    var animateIn by remember { mutableStateOf(false) }
    var showContinue by remember { mutableStateOf(false) }
    var showConfetti by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        delay(StartBeatMs.toLong())
        animateIn = true
        delay((settleDelayMs - StartBeatMs).toLong())
        showContinue = true
        if (completedCount > 0) showConfetti = true
    }

    // Says what actually happened: "CHALLENGE COMPLETE" over one completion and two mere
    // advances overstated it, so a mixed landing names both counts.
    val headline = when {
        completedCount == 0 -> "CHALLENGE PROGRESS"
        // Broken on purpose, so the two counts sit on balanced lines instead of wrapping wherever.
        advancedCount > 0 -> "$completedCount COMPLETE\n$advancedCount ADVANCED"
        completedCount == 1 -> "CHALLENGE COMPLETE"
        else -> "CHALLENGES COMPLETE"
    }
    val headlineColor by animateColorAsState(
        // Gold marks what's done. The headline only turns gold when everything in it is; on a
        // mixed landing the completed rows carry the gold and the headline stays Amber.
        targetValue = if (completedCount > 0 && advancedCount == 0 && animateIn) ChallengeGold else Amber,
        animationSpec = tween(durationMillis = 400),
        label = "challengeOutcomeHeadlineColor"
    )

    Box(modifier = Modifier.fillMaxSize().background(Midnight), contentAlignment = Alignment.Center) {
        // Content scrolls in the space above CONTINUE, which is pinned to the bottom like on
        // Arrival and Check-in (it used to follow the card, mid-screen). One scroll container,
        // so three rows at a large font scale still can't squeeze the button away.
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.systemBars)
                .padding(Spacing.Large),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
          Column(
              modifier = Modifier
                  .weight(1f)
                  .fillMaxWidth()
                  .verticalScroll(rememberScrollState()),
              horizontalAlignment = Alignment.CenterHorizontally,
              verticalArrangement = Arrangement.Center
          ) {
            CaptionLabel(text = "CHALLENGE UPDATE")
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = headline,
                style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Black, letterSpacing = 2.sp),
                color = headlineColor,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(Spacing.ExtraLarge))

            FocusCard(
                modifier = Modifier.fillMaxWidth(),
                variant = CardVariant.Surface,
                shape = RoundedCornerShape(20.dp),
                contentPadding = PaddingValues(24.dp)
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(20.dp)
                ) {
                    outcomes.forEachIndexed { index, outcome ->
                        if (index > 0) HorizontalDivider(color = Border, thickness = 1.dp)
                        ChallengeOutcomeRow(
                            outcome = outcome,
                            animateIn = animateIn,
                            progressLabel = progressLabels[outcome.challengeId]
                        )
                    }
                }
            }

          }

            Spacer(modifier = Modifier.height(Spacing.Large))

            FocusButton(
                text = "CONTINUE",
                onClick = onContinue,
                enabled = showContinue,
                modifier = Modifier.fillMaxWidth()
            )
        }

        ConfettiOverlay(trigger = showConfetti)
    }
}

@Composable
private fun ChallengeOutcomeRow(outcome: ChallengeOutcome, animateIn: Boolean, progressLabel: String?) {
    val isCompleted = outcome is ChallengeOutcome.Completed
    val oldProgress = when (outcome) {
        is ChallengeOutcome.Advanced -> outcome.oldProgress
        is ChallengeOutcome.Completed -> outcome.oldProgress
    }
    val newProgress = when (outcome) {
        is ChallengeOutcome.Advanced -> outcome.newProgress
        is ChallengeOutcome.Completed -> 1f
    }

    val animatedProgress by animateFloatAsState(
        targetValue = if (animateIn) newProgress else oldProgress,
        animationSpec = tween(
            durationMillis = if (isCompleted) CompleteDurationMs else AdvanceDurationMs,
            easing = FastOutSlowInEasing
        ),
        label = "challengeOutcomeRowProgress"
    )
    val barColor by animateColorAsState(
        targetValue = if (isCompleted && animateIn) ChallengeGold else Amber,
        animationSpec = tween(durationMillis = 400),
        label = "challengeOutcomeRowColor"
    )

    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = challengeIcon(outcome.type, outcome.iconName),
                contentDescription = null,
                tint = barColor,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(Spacing.Small))
            Text(
                text = outcome.name,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                color = OffWhite,
                modifier = Modifier.weight(1f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        // Type and status as a plain text line under the name, not chips beside it: the chips
        // looked like buttons, and squeezed the name down to "First Cro...".
        Spacer(modifier = Modifier.height(4.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 20.dp + Spacing.Small)
        ) {
            Text(
                text = buildAnnotatedString {
                    withStyle(SpanStyle(color = Amber)) { append(challengeTypeLabel(outcome.type)) }
                    if (isCompleted) {
                        withStyle(SpanStyle(color = Haze)) { append("  ·  ") }
                        withStyle(SpanStyle(color = Success)) { append("COMPLETED") }
                    }
                },
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 0.5.sp
                )
            )
            // A team bar says whose it is (docs/shared-challenges.md "Landing"): the progress
            // shown is the crew's, and without the badge a small own contribution looks like a
            // big jump.
            if (outcome.isShared) {
                Spacer(modifier = Modifier.width(Spacing.Small))
                FocusBadge(
                    text = "CREW ×${outcome.crewSize}",
                    variant = BadgeVariant.Neutral,
                    style = BadgeStyle.Translucent,
                    size = BadgeSize.Compact
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        ChallengeProgressBar(progress = animatedProgress, fillColor = barColor, height = 14.dp)

        Spacer(modifier = Modifier.height(8.dp))

        val percent = if (isCompleted) "100%" else "${(newProgress * 100).roundToInt()}%"
        FocusInfoRow(
            label = "PROGRESS",
            value = progressLabel?.let { "$it · $percent" } ?: percent,
            valueColor = barColor
        )
    }
}

/**
 * The challenge's progress in its own units, from its row as it stands after this landing -
 * legs for an itinerary, members for a set, miles for distance, days for a streak. Null where
 * there is no count more meaningful than the percentage (a free-form route, scored by distance
 * closed along a straight line).
 */
private fun outcomeProgressLabel(challenge: Challenge): String? = when (challenge.type) {
    ChallengeType.ROUTE -> challenge.predefinedRoute()
        ?.takeIf { it.legCount > 0 }
        ?.let { "Leg ${challenge.legIndex.coerceIn(0, it.legCount)}/${it.legCount}" }
    ChallengeType.SET_COMPLETION ->
        challenge.setTotalMembers.takeIf { it > 0 }
            ?.let { "${challenge.visitedSetMembers.size}/$it visited" }
    ChallengeType.DISTANCE -> challenge.targetDistanceKm?.takeIf { it > 0 }?.let { target ->
        // Bare numbers on the left so the unit is said once: "2,600 / 6,200 mi".
        val flown = String.format(Locale.US, "%,.0f", kmToMiles(challenge.cumulativeDistanceKm.coerceAtMost(target)))
        "$flown / ${formatMiles(target)}"
    }
    ChallengeType.STREAK -> challenge.targetDays?.takeIf { it > 0 }
        ?.let { "${challenge.streakDays.coerceAtMost(it)} of $it days" }
}

private data class ConfettiParticle(
    val startXFraction: Float,
    val fallDurationMs: Int,
    val delayMs: Int,
    val color: Color,
    val sizePx: Float,
    val driftFraction: Float,
    val rotationSpeedDegPerMs: Float
)

private val ConfettiColors = listOf(ChallengeGold, Amber, Green, OffWhite)
private const val ConfettiDurationMs = 3200

/**
 * Lightweight particle-shower confetti - no external animation library (none is a dependency
 * here), just a single [Animatable] driving ~50 rectangles down a [Canvas]. Doesn't intercept
 * touch (a plain [Canvas] registers no pointer input), so the CONTINUE button beneath it stays
 * tappable throughout. Fires exactly once per screen regardless of how many challenges completed.
 */
@Composable
private fun ConfettiOverlay(trigger: Boolean) {
    if (!trigger) return

    val particles = remember {
        List(50) {
            ConfettiParticle(
                startXFraction = Random.nextFloat(),
                fallDurationMs = Random.nextInt(1800, ConfettiDurationMs),
                delayMs = Random.nextInt(0, 500),
                color = ConfettiColors[Random.nextInt(ConfettiColors.size)],
                sizePx = Random.nextInt(6, 14).toFloat(),
                driftFraction = Random.nextFloat() * 0.24f - 0.12f,
                rotationSpeedDegPerMs = Random.nextFloat() * 0.5f - 0.25f
            )
        }
    }
    val elapsedMs = remember { Animatable(0f) }
    LaunchedEffect(trigger) {
        elapsedMs.animateTo(
            targetValue = ConfettiDurationMs.toFloat(),
            animationSpec = tween(durationMillis = ConfettiDurationMs, easing = LinearEasing)
        )
    }

    Canvas(modifier = Modifier.fillMaxSize()) {
        val now = elapsedMs.value
        particles.forEach { p ->
            val localElapsed = now - p.delayMs
            if (localElapsed < 0f) return@forEach
            val fall = (localElapsed / p.fallDurationMs).coerceIn(0f, 1f)

            val y = fall * (size.height + 60f) - 30f
            val drift = kotlin.math.sin(fall * Math.PI.toFloat() * 2f) * p.driftFraction * size.width
            val x = p.startXFraction * size.width + drift

            rotate(degrees = localElapsed * p.rotationSpeedDegPerMs, pivot = Offset(x, y)) {
                drawRect(
                    color = p.color.copy(alpha = 1f - fall * 0.3f),
                    topLeft = Offset(x - p.sizePx / 2f, y - p.sizePx),
                    size = Size(p.sizePx, p.sizePx * 1.8f)
                )
            }
        }
    }
}
