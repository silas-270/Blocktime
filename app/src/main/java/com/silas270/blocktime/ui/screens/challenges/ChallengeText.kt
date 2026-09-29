package com.silas270.blocktime.ui.screens.challenges

import com.silas270.blocktime.data.model.Challenge
import com.silas270.blocktime.data.model.ChallengeType
import com.silas270.blocktime.data.model.isShared
import com.silas270.blocktime.data.model.predefinedRoute
import com.silas270.blocktime.data.model.teamDistanceKm
import com.silas270.blocktime.data.model.teamStreakDays
import com.silas270.blocktime.data.model.teamVisitedMembers
import com.silas270.blocktime.data.model.withSetDefinitionResolved
import com.silas270.blocktime.ui.viewmodel.challenges.formatKm
import com.silas270.blocktime.util.formatMiles
import com.silas270.blocktime.util.kmToMiles
import java.util.Locale

/**
 * The progress lines of the info modal's STATUS row, the Hub card and the outcome screen, kept
 * free of Compose so they can be unit tested.
 *
 * A shared pool reads the team total with the pilot's own share after it
 * (docs/shared-challenges.md "Per type"), because the bar and the percentage beside these lines
 * are the team's: own miles next to a team percentage read as two different challenges. A race
 * and every solo row read the pilot's own progress, as the bar does.
 */

/** "STR → PEK" / "3/7 visited" / "2,600 / 6,200 mi" / "2 of 4 days", by type; a shared pool
 *  as "4/6 visited · you 2", "581 / 1,000 mi · you 270 mi" or "2 of 3 days · you 3". */
internal fun challengeStatusText(challenge: Challenge): String {
    val pooled = isPooled(challenge)
    return when (challenge.type) {
        // A predefined itinerary names the leg as well as its endpoints: on a circuit the endpoint
        // pair alone can read as "LHR → LHR", which says nothing about how far along you are.
        ChallengeType.ROUTE -> {
            val hop = "${challenge.positionIata ?: "?"} → ${nextStop(challenge) ?: "?"}"
            challenge.predefinedRoute()
                ?.let { "$hop · leg ${challenge.legIndex + 1}/${it.legCount}" }
                ?: hop
        }
        ChallengeType.SET_COMPLETION -> if (pooled) {
            // Read through the current definition, as the team bar is (situation P14), so a
            // member a definition dropped counts in neither the union nor the pilot's own share.
            val resolved = challenge.withSetDefinitionResolved()
            "${challenge.teamVisitedMembers().size}/${resolved.setTotalMembers} visited · you ${resolved.visitedSetMembers.size}"
        } else {
            "${challenge.visitedSetMembers.size}/${challenge.setTotalMembers} visited"
        }
        ChallengeType.DISTANCE -> {
            val target = challenge.targetDistanceKm ?: 0.0
            if (pooled) {
                "${bareMiles(challenge.teamDistanceKm())} / ${formatKm(target)} · you ${formatKm(challenge.cumulativeDistanceKm)}"
            } else {
                "${formatKm(challenge.cumulativeDistanceKm)} / ${formatKm(target)}"
            }
        }
        // Days rather than a percentage: with a target of 3-5, "2 of 4 days" is both shorter and
        // more actionable than "50%", and the streak reads as a count everywhere else too.
        ChallengeType.STREAK -> if (pooled) {
            "${challenge.teamStreakDays()} of ${challenge.targetDays ?: 0} days · you ${challenge.streakDays}"
        } else {
            "${challenge.streakDays} of ${challenge.targetDays ?: 0} days"
        }
    }
}

/**
 * The challenge's progress in its own units, from its row as it stands after this landing -
 * legs for an itinerary, members for a set, miles for distance, days for a streak; a shared
 * pool as the team's count with the pilot's own after it. Null where there is no count more
 * meaningful than the percentage (a free-form route, scored by distance closed along a straight
 * line), or where the row has no target to count against.
 */
internal fun challengeOutcomeText(challenge: Challenge): String? {
    val pooled = isPooled(challenge)
    return when (challenge.type) {
        ChallengeType.ROUTE -> challenge.predefinedRoute()
            ?.takeIf { it.legCount > 0 }
            ?.let { "Leg ${challenge.legIndex.coerceIn(0, it.legCount)}/${it.legCount}" }
        ChallengeType.SET_COMPLETION -> if (pooled) {
            val resolved = challenge.withSetDefinitionResolved()
            resolved.setTotalMembers.takeIf { it > 0 }
                ?.let { "${challenge.teamVisitedMembers().size}/$it visited · you ${resolved.visitedSetMembers.size}" }
        } else {
            challenge.setTotalMembers.takeIf { it > 0 }
                ?.let { "${challenge.visitedSetMembers.size}/$it visited" }
        }
        ChallengeType.DISTANCE -> challenge.targetDistanceKm?.takeIf { it > 0 }?.let { target ->
            // Bare numbers on the left so the unit is said once: "2,600 / 6,200 mi".
            val total = if (pooled) challenge.teamDistanceKm() else challenge.cumulativeDistanceKm
            val line = "${bareMiles(total.coerceAtMost(target))} / ${formatMiles(target)}"
            if (pooled) "$line · you ${formatMiles(challenge.cumulativeDistanceKm)}" else line
        }
        ChallengeType.STREAK -> challenge.targetDays?.takeIf { it > 0 }?.let { target ->
            val days = if (pooled) challenge.teamStreakDays() else challenge.streakDays
            val line = "${days.coerceAtMost(target)} of $target days"
            if (pooled) "$line · you ${challenge.streakDays.coerceAtMost(target)}" else line
        }
    }
}

/** A shared row whose progress is a pool (set, distance, streak); a race ranks instead of pooling. */
private fun isPooled(challenge: Challenge): Boolean =
    challenge.isShared() && challenge.type != ChallengeType.ROUTE

/** Miles without the unit, for the left side of "581 / 1,000 mi", where the unit is said once. */
private fun bareMiles(km: Double): String = String.format(Locale.US, "%,.0f", kmToMiles(km))

/** Where the challenge's next flight is headed - the itinerary's next waypoint for a predefined
 *  route, the final destination for a free-form one (which is free to get there any way it likes). */
private fun nextStop(challenge: Challenge): String? =
    challenge.predefinedRoute()?.destOf(challenge.legIndex) ?: challenge.destIata
