package com.silas270.blocktime.ui.screens.challenges

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.silas270.blocktime.data.model.Challenge
import com.silas270.blocktime.data.model.ChallengeType
import com.silas270.blocktime.data.model.isShared
import com.silas270.blocktime.data.model.predefinedRoute
import com.silas270.blocktime.data.model.progressFraction
import com.silas270.blocktime.ui.components.ButtonSize
import com.silas270.blocktime.ui.components.ModalButtonRow
import com.silas270.blocktime.ui.components.ModalTitle
import com.silas270.blocktime.ui.components.PrimaryActionButton
import com.silas270.blocktime.ui.components.ScrimCardModal
import com.silas270.blocktime.ui.theme.Haze
import com.silas270.blocktime.ui.viewmodel.challenges.formatKm
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.Spacer

/**
 * Bits shared by the Challenges screen and the custom-creation screen - the confirm modals and
 * the one-line progress summary. Salvaged from the old Hub quest-log sheet, which kept them all
 * `private` in a single 800-line file; they are `internal` here so both screens can use one copy.
 * The button primitives that used to live here (`PrimaryActionButton`, `DestructiveActionButton`,
 * `ModalButtonRow`, `ModalTitle`) moved to `ui/components/ActionButtons.kt` so screens outside
 * challenges can share them too.
 */

/** "STR → PEK" / "3/7 visited" / "2,600 / 6,200 mi" / "2 of 4 days", by type. */
internal fun challengeSubtitle(challenge: Challenge): String {
    return when (challenge.type) {
        // A predefined itinerary names the leg as well as its endpoints: on a circuit the endpoint
        // pair alone can read as "LHR → LHR", which says nothing about how far along you are.
        ChallengeType.ROUTE -> {
            val hop = "${challenge.positionIata ?: "?"} → ${nextStop(challenge) ?: "?"}"
            challenge.predefinedRoute()
                ?.let { "$hop · leg ${challenge.legIndex + 1}/${it.legCount}" }
                ?: hop
        }
        ChallengeType.SET_COMPLETION -> "${challenge.visitedSetMembers.size}/${challenge.setTotalMembers} visited"
        ChallengeType.DISTANCE -> {
            val target = challenge.targetDistanceKm ?: 0.0
            "${formatKm(challenge.cumulativeDistanceKm)} / ${formatKm(target)}"
        }
        // Days rather than a percentage: with a target of 3-5, "2 of 4 days" is both shorter and
        // more actionable than "50%", and the streak reads as a count everywhere else too.
        ChallengeType.STREAK -> "${challenge.streakDays} of ${challenge.targetDays ?: 0} days"
    }
}

/** "1st", "2nd", "3rd", "4th", "11th", "22nd": the suffix for a race placement. */
internal fun ordinalSuffix(n: Int): String {
    val rem100 = n % 100
    if (rem100 in 11..13) return "th"
    return when (n % 10) {
        1 -> "st"
        2 -> "nd"
        3 -> "rd"
        else -> "th"
    }
}

/**
 * "Synced just now" / "Synced 3 min ago" / "Synced 2 h ago" for the info modal's crew section,
 * from the last successful sync's timestamp. Minutes are the unit that matters: the crew's
 * numbers are as fresh as the last sync, and "3 min ago" says whether to expect a change.
 */
internal fun syncedAgoLabel(syncedAt: Long, now: Long): String {
    val minutes = ((now - syncedAt) / 60_000L).coerceAtLeast(0L)
    return when {
        minutes < 1L -> "Synced just now"
        minutes < 60L -> "Synced $minutes min ago"
        else -> "Synced ${minutes / 60L} h ago"
    }
}

/** Where the challenge's next flight is headed - the itinerary's next waypoint for a predefined
 *  route, the final destination for a free-form one (which is free to get there any way it likes). */
private fun nextStop(challenge: Challenge): String? =
    challenge.predefinedRoute()?.destOf(challenge.legIndex) ?: challenge.destIata

/**
 * Shown before a Free Mode flight, since the flight looks exactly like a Story Mode one from the
 * cockpit but is only partly recorded (docs/modes.md's isolation matrix). It *is* logged: it shows
 * in the logbook and counts toward the mode-blind totals, tours and highlights. It is filtered out
 * of achievement evaluation (`AchievementProgress` keeps to STORY-tagged flights) and of the
 * visited-countries map, is a no-op for challenge crediting (`processLandingForChallenges`), and
 * never moves `current_airport_iata` - the pilot is still where they were once it lands. (The old
 * copy said it "won't be counted" and that the landing wouldn't become the *home* airport, which no
 * flight in any mode ever changes.) Better said here than discovered after an hour in the air.
 */
@Composable
internal fun FreeModeNoticeModal(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    ScrimCardModal(onScrimTap = onDismiss) {
        ModalTitle("FREE MODE")
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Fly anywhere you like. This flight goes in your logbook and counts toward " +
                "your totals, tours and highlights - but not toward achievements, challenges or " +
                "your map, and you'll still be at your current airport afterwards.",
            style = MaterialTheme.typography.bodyMedium,
            color = Haze
        )
        Spacer(modifier = Modifier.height(24.dp))
        ModalButtonRow(
            dismissText = "CANCEL",
            confirmText = "FLY",
            onDismiss = onDismiss,
            onConfirm = onConfirm
        )
    }
}

@Composable
internal fun AbandonConfirmModal(challenge: Challenge, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    ScrimCardModal(onScrimTap = onDismiss) {
        ModalTitle("ABANDON CHALLENGE?")
        Spacer(modifier = Modifier.height(8.dp))
        // A shared row says what happens to the others (docs/shared-challenges.md, A2): the room
        // outlives the pilot, so "removed entirely" alone would read as ending it for the crew.
        val sharedNote = if (challenge.isShared()) " Your crew keeps the challenge; you leave the room." else ""
        Text(
            text = "\"${challenge.name}\" will be removed entirely, freeing up a challenge slot. " +
                "This can't be undone - starting it again later begins from zero." + sharedNote,
            style = MaterialTheme.typography.bodyMedium,
            color = Haze
        )
        Spacer(modifier = Modifier.height(24.dp))
        ModalButtonRow(
            dismissText = "KEEP IT",
            confirmText = "ABANDON",
            onDismiss = onDismiss,
            onConfirm = onConfirm,
            isDestructive = true
        )
    }
}

@Composable
internal fun InfoModal(title: String, message: String, onDismiss: () -> Unit) {
    ScrimCardModal(onScrimTap = onDismiss) {
        ModalTitle(title)
        Spacer(modifier = Modifier.height(8.dp))
        Text(text = message, style = MaterialTheme.typography.bodyMedium, color = Haze)
        Spacer(modifier = Modifier.height(24.dp))
        PrimaryActionButton(text = "OK", size = ButtonSize.Compact, onClick = onDismiss)
    }
}
