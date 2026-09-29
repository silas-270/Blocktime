package com.silas270.blocktime.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.silas270.blocktime.ui.theme.Amber
import com.silas270.blocktime.ui.theme.Slate

/** One member's share of a segmented ring: its [fraction] of the whole circle and its colour. */
data class RingSegment(val fraction: Float, val color: Color)

/**
 * Circular sibling of [ChallengeProgressBar] - a track ring with a fill arc sweeping clockwise
 * from 12 o'clock, and whatever the caller wants in the middle.
 *
 * Generalised from the "EQUATOR PROGRESS" dial in `FlightHighlightsRow`, which hand-rolled this
 * same track-then-fill arc pair; the Challenge slots needed a third copy, so it lives here now.
 *
 * A non-empty [segments] replaces the single fill with one arc per crew member (a shared pool,
 * like [SegmentedProgressBar]); their fractions are scaled so the arcs together sweep [progress],
 * which is what keeps the animation of the whole ring one motion.
 *
 * Like [ChallengeProgressBar], this takes an already-resolved [progress] and does no animating of
 * its own - callers drive that with `animateFloatAsState`, keeping one convention across both
 * progress components.
 */
@Composable
fun RingProgress(
    progress: Float,
    modifier: Modifier = Modifier,
    strokeWidth: Dp = 6.dp,
    trackColor: Color = Slate,
    fillColor: Color = Amber,
    segments: List<RingSegment> = emptyList(),
    markers: List<ProgressMarker> = emptyList(),
    centerContent: @Composable BoxScope.() -> Unit = {}
) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val stroke = strokeWidth.toPx()
            val arcSize = Size(size.width - stroke, size.height - stroke)
            val topLeft = Offset(stroke / 2, stroke / 2)

            drawArc(
                color = trackColor,
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round)
            )
            val fraction = progress.coerceIn(0f, 1f)
            if (segments.isNotEmpty()) {
                // One arc per crew member, contiguous from 12 o'clock like the team bar. The
                // arcs are butt-ended so neighbours meet flush; the two outer ends get the same
                // round cap the single-colour fill has, as dots.
                val total = segments.sumOf { it.fraction.coerceAtLeast(0f).toDouble() }.toFloat()
                if (total > 0f) {
                    val scale = fraction / total
                    val radius = arcSize.width / 2f
                    val center = Offset(size.width / 2f, size.height / 2f)
                    var start = -90f
                    var end = start
                    segments.forEach { segment ->
                        val sweep = 360f * segment.fraction.coerceAtLeast(0f) * scale
                        if (sweep <= 0f) return@forEach
                        drawArc(
                            color = segment.color,
                            startAngle = start,
                            sweepAngle = sweep,
                            useCenter = false,
                            topLeft = topLeft,
                            size = arcSize,
                            style = Stroke(width = stroke, cap = StrokeCap.Butt)
                        )
                        start += sweep
                        end = start
                    }
                    fun cap(angleDeg: Float, color: Color) {
                        val rad = Math.toRadians(angleDeg.toDouble())
                        drawCircle(
                            color = color,
                            radius = stroke / 2f,
                            center = Offset(
                                center.x + radius * kotlin.math.cos(rad).toFloat(),
                                center.y + radius * kotlin.math.sin(rad).toFloat()
                            )
                        )
                    }
                    cap(-90f, segments.first { it.fraction > 0f }.color)
                    cap(end, segments.last { it.fraction > 0f }.color)
                }
            } else if (fraction > 0f) {
                drawArc(
                    color = fillColor,
                    startAngle = -90f,
                    sweepAngle = 360f * fraction,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = stroke, cap = StrokeCap.Round)
                )
            }
            // Other pilots of a race, as dots as thick as the ring, on the track at their progress, over the fill.
            markers.forEach { marker ->
                val rad = Math.toRadians((-90f + 360f * marker.fraction.coerceIn(0f, 1f)).toDouble())
                val r = arcSize.width / 2f
                val c = Offset(
                    size.width / 2f + r * kotlin.math.cos(rad).toFloat(),
                    size.height / 2f + r * kotlin.math.sin(rad).toFloat()
                )
                drawCircle(color = marker.color, radius = stroke / 2f, center = c)
            }
        }
        centerContent()
    }
}
