package com.silas270.blocktime.domain

import com.silas270.blocktime.data.repository.ChallengeOutcome
import com.silas270.blocktime.data.repository.LandingResult

/** Where CONTINUE goes after a landing (docs/shared-challenges.md "Landing"). */
enum class ArrivalDestination {
    /** The outcome screen: what the pilot's own landing did to their challenges. */
    OUTCOME,

    /** The Challenges screen, which presents whatever completion or failure is waiting. */
    CHALLENGES,

    HUB;
}

/**
 * The one routing rule behind both CONTINUE buttons of the landing sequence, the arrival
 * screen's and the outcome screen's, so the two cannot drift apart.
 *
 * From the arrival screen ([fromOutcomeScreen] false): a result with outcomes goes to the
 * outcome screen first, as today. Otherwise a pending presentation goes to Challenges, and
 * nothing at all goes to the Hub. `Pending` counts as nothing, the way the arrival screen already
 * treats a result that never resolved.
 *
 * From the outcome screen ([fromOutcomeScreen] true): any completion in the result, or a
 * terminal row still waiting to be presented, goes to Challenges; a landing that merely
 * advanced goes to the Hub, as today.
 *
 * [hasPendingPresentation] is what lets a completion that arrived mid-flight through a sync be
 * seen right after landing rather than the next time the pilot happens to open Challenges: the
 * landing itself reported nothing for that row (L8), so the result alone would send the pilot
 * to the Hub.
 */
fun resolveArrivalDestination(
    result: LandingResult,
    hasPendingPresentation: Boolean,
    fromOutcomeScreen: Boolean = false,
): ArrivalDestination {
    val outcomes = (result as? LandingResult.ChallengesAffected)?.outcomes.orEmpty()
    return when {
        !fromOutcomeScreen && outcomes.isNotEmpty() -> ArrivalDestination.OUTCOME
        outcomes.any { it is ChallengeOutcome.Completed } -> ArrivalDestination.CHALLENGES
        hasPendingPresentation -> ArrivalDestination.CHALLENGES
        else -> ArrivalDestination.HUB
    }
}
