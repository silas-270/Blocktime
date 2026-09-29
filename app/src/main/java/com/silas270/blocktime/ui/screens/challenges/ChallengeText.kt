package com.silas270.blocktime.ui.screens.challenges

import com.silas270.blocktime.data.model.Challenge
import com.silas270.blocktime.data.model.ChallengeType
import com.silas270.blocktime.data.model.displayProgressFraction
import com.silas270.blocktime.data.model.isShared
import com.silas270.blocktime.data.model.predefinedRoute
import com.silas270.blocktime.data.model.teamDistanceKm
import com.silas270.blocktime.data.model.teamStreakDays
import com.silas270.blocktime.data.model.teamVisitedMembers
import com.silas270.blocktime.data.model.withSetDefinitionResolved

/**
 * The progress texts of the info modal, the slots, the Hub card and the outcome screen, kept free
 * of Compose so they can be unit tested. One rule for all of them, see [challengeProgressText].
 */

/**
 * The pilot's progress in the one form its type is always written in, on every screen: distance
 * and route as a percentage ("37%"), set and streak as absolute numbers ("3/6 visited",
 * "2 of 5 days"). A shared pool reads the team's value, a race and every solo row the pilot's own,
 * as the bar beside it does. Never carries a "you" share: the multi-coloured bar and ring already
 * show whose share is whose.
 */
internal fun challengeProgressText(challenge: Challenge): String {
    val pooled = isPooled(challenge)
    return when (challenge.type) {
        ChallengeType.ROUTE, ChallengeType.DISTANCE ->
            "${(challenge.displayProgressFraction() * 100).toInt()}%"
        ChallengeType.SET_COMPLETION -> {
            // Read through the current definition, as the team bar is (situation P14), so a
            // member a definition dropped counts in neither the union nor the pilot's own share.
            if (pooled) {
                val resolved = challenge.withSetDefinitionResolved()
                "${challenge.teamVisitedMembers().size}/${resolved.setTotalMembers} visited"
            } else {
                "${challenge.visitedSetMembers.size}/${challenge.setTotalMembers} visited"
            }
        }
        ChallengeType.STREAK -> {
            val target = challenge.targetDays ?: 0
            val days = if (pooled) challenge.teamStreakDays() else challenge.streakDays
            "${days.coerceAtMost(target.coerceAtLeast(0))} of $target days"
        }
    }
}

/** The short form of [challengeProgressText] for a slot's ring: "3/6" for a set, "2/5" for a
 *  streak, the percentage for distance and route. */
internal fun challengeRingLabel(challenge: Challenge): String {
    val pooled = isPooled(challenge)
    return when (challenge.type) {
        ChallengeType.ROUTE, ChallengeType.DISTANCE ->
            "${(challenge.displayProgressFraction() * 100).toInt()}%"
        ChallengeType.SET_COMPLETION -> {
            if (pooled) {
                "${challenge.teamVisitedMembers().size}/${challenge.withSetDefinitionResolved().setTotalMembers}"
            } else {
                "${challenge.visitedSetMembers.size}/${challenge.setTotalMembers}"
            }
        }
        ChallengeType.STREAK -> {
            val target = challenge.targetDays ?: 0
            val days = if (pooled) challenge.teamStreakDays() else challenge.streakDays
            "${days.coerceAtMost(target.coerceAtLeast(0))}/$target"
        }
    }
}

/** Where a route challenge is and where its next flight goes: "STR → PEK", with the leg on a
 *  predefined itinerary ("STR → PEK · leg 2/5"); null for every other type. A predefined
 *  itinerary names the leg as well as its endpoints: on a circuit the endpoint pair alone can
 *  read as "LHR → LHR", which says nothing about how far along you are. */
internal fun challengeRouteText(challenge: Challenge): String? {
    if (challenge.type != ChallengeType.ROUTE) return null
    val hop = "${challenge.positionIata ?: "?"} → ${nextStop(challenge) ?: "?"}"
    return challenge.predefinedRoute()
        ?.let { "$hop · leg ${challenge.legIndex + 1}/${it.legCount}" }
        ?: hop
}

/** A shared row whose progress is a pool (set, distance, streak); a race ranks instead of pooling. */
private fun isPooled(challenge: Challenge): Boolean =
    challenge.isShared() && challenge.type != ChallengeType.ROUTE

/** Where the challenge's next flight is headed - the itinerary's next waypoint for a predefined
 *  route, the final destination for a free-form one (which is free to get there any way it likes). */
private fun nextStop(challenge: Challenge): String? =
    challenge.predefinedRoute()?.destOf(challenge.legIndex) ?: challenge.destIata
