package com.silas270.blocktime.ui.screens.account

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import com.silas270.blocktime.data.model.Challenge
import com.silas270.blocktime.data.model.ChallengeStatus
import com.silas270.blocktime.data.model.ChallengeType
import com.silas270.blocktime.data.model.SharedOutcome
import com.silas270.blocktime.data.model.isShared
import com.silas270.blocktime.data.model.racePlacement
import com.silas270.blocktime.ui.components.BadgeSize
import com.silas270.blocktime.ui.components.BadgeStyle
import com.silas270.blocktime.ui.components.BadgeVariant
import com.silas270.blocktime.ui.components.FocusBadge
import com.silas270.blocktime.ui.screens.challenges.ordinalSuffix
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * One row in the Achievements screen's "Challenges completed" log (docs/achievements.md -
 * a flat log of every completed challenge, curated or custom, duplicates included for repeat
 * completions - modeled on the flight logbook, not an "x/N" tally, since custom challenges and
 * repeats mean there's no fixed denominator). Reuses [LogPaperCard] - the same paper-card chrome
 * [LogbookEntry] draws its own rows with - and [LogbookDataCell], so this reads as the same
 * physical logbook rather than a second visual language.
 */
@Composable
internal fun ChallengeCompletionEntry(challenge: Challenge, entryNumber: Int) {
    // completedAt is always non-null for a COMPLETED-status row in practice (see
    // ChallengeStatus/LocalChallengeRepository's crediting paths, which always set it alongside
    // the status flip) - startedAt is only a defensive fallback so this never crashes on an
    // unexpected null.
    val dateStr = remember(challenge.completedAt, challenge.startedAt) {
        SimpleDateFormat("dd MMM yyyy", Locale.US).format(Date(challenge.completedAt ?: challenge.startedAt))
    }

    val inkDark   = LogbookInkDark
    val inkMid    = LogbookInkMid
    val inkFaint  = LogbookInkFaint

    LogPaperCard(entryNumber = entryNumber) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            // Row 1: DATE on left, the race place stamp on right when there is one
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
                    color = inkFaint
                )
                // The only stamp is the place in a finished shared race; every other entry has none.
                finishedRacePlace(challenge)?.let { place ->
                    FocusBadge(
                        text = placeStamp(place),
                        variant = BadgeVariant.Danger,
                        style = BadgeStyle.Stamp,
                        size = BadgeSize.Compact
                    )
                }
            }

            // Row 2: challenge name (in place of LogbookEntry's ORIGIN ✈ DEST route stamp - a
            // Set-completion/Distance challenge has no single origin/dest pair, so the name is
            // the one thing every challenge type has)
            Text(
                text = challenge.name,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    letterSpacing = 0.5.sp
                ),
                color = inkDark,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            // Row 3: whether it was flown alone or with a crew.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                LogbookDataCell(
                    label = "MODE",
                    value = if (challenge.isShared()) "TEAM" else "SINGLE",
                    labelColor = inkFaint,
                    valueColor = inkMid
                )
            }
        }
    }
}

/**
 * The pilot's place in a finished shared race, or null for anything else. A race this phone
 * completed by arriving, before any server reply, has no outcome of its own yet (L6) and is a
 * win until the server says otherwise (merge rule 2 then corrects it). Internal so the lifted
 * card and the log entry read the same place.
 */
internal fun finishedRacePlace(challenge: Challenge): Int? {
    if (!challenge.isShared() || challenge.type != ChallengeType.ROUTE) return null
    if (challenge.status != ChallengeStatus.COMPLETED) return null
    return when (challenge.sharedOutcome) {
        null -> 1
        is SharedOutcome.Completed -> challenge.racePlacement()
        is SharedOutcome.Failed -> null
    }
}

/** "2ND" for a place in a race. */
internal fun placeStamp(place: Int): String = "$place${ordinalSuffix(place).uppercase(Locale.US)}"
