package com.silas270.blocktime.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.silas270.blocktime.data.model.ProgressSegment
import com.silas270.blocktime.ui.theme.OffWhite
import com.silas270.blocktime.ui.theme.Slate
import com.silas270.blocktime.ui.theme.participantColor

/**
 * The team bar of a shared pool (docs/shared-challenges.md "Per type"): one contiguous slice per
 * crew member in join order, drawn left to right on the same track as [ChallengeProgressBar], so
 * a shared distance or set challenge reads as the same bar with the credit split by who earned
 * it. The pilot's own slice is Amber with a thin light border, the others take their palette
 * colour ([participantColor]), and the slices are capped at the full width, since the fractions
 * come from a derivation that can overshoot by a rounding step.
 *
 * Drawn in `drawBehind` like the deferred sibling of [ChallengeProgressBar], so a slot's
 * recomposition never re-measures anything here.
 */
@Composable
fun SegmentedProgressBar(
    segments: List<ProgressSegment>,
    modifier: Modifier = Modifier,
    trackColor: Color = Slate,
    height: Dp = 8.dp
) {
    // Read outside the draw lambda: the palette tokens are Compose state and must be observed
    // in composition for a theme switch to repaint the bar.
    val selfBorder = OffWhite
    val colours = segments.map { participantColor(it.participant.colorIndex, it.isSelf) }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(height / 2))
            .background(trackColor)
            .drawBehind {
                var start = 0f
                val borderWidth = 1.dp.toPx()
                segments.forEachIndexed { index, segment ->
                    val width = (segment.fraction.coerceAtLeast(0f) * size.width).coerceAtMost(size.width - start)
                    if (width <= 0f) return@forEachIndexed
                    drawRect(
                        color = colours[index],
                        topLeft = Offset(start, 0f),
                        size = Size(width, size.height)
                    )
                    // The border sits inside the slice so it never bleeds into the neighbour.
                    if (segment.isSelf) {
                        drawRect(
                            color = selfBorder,
                            topLeft = Offset(start + borderWidth / 2f, borderWidth / 2f),
                            size = Size(width - borderWidth, size.height - borderWidth),
                            style = Stroke(width = borderWidth)
                        )
                    }
                    start += width
                }
            }
    )
}
