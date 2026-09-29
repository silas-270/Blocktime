package com.silas270.blocktime.domain

import com.silas270.blocktime.data.model.Challenge
import com.silas270.blocktime.data.model.ChallengeStatus
import com.silas270.blocktime.data.model.ChallengeType
import com.silas270.blocktime.data.model.SharedOutcome
import com.silas270.blocktime.data.model.crew
import com.silas270.blocktime.data.model.crewSize
import com.silas270.blocktime.data.model.isShared
import com.silas270.blocktime.data.model.racePlacement

/**
 * What the completion overlay says for a completed row (docs/shared-challenges.md
 * "Presentation"). The mechanics of the overlay are the same for all four; only the copy, the
 * colours and the amount of confetti differ.
 */
sealed interface CompletionPresentation {
    /** Not shared: as today. */
    data object Solo : CompletionPresentation

    /** A shared pool, completed by anyone: the "CREW ×N" badge, full confetti. */
    data class Team(val crewSize: Int) : CompletionPresentation

    /** A shared race the pilot won: "YOU WON THE RACE", gold. */
    data class RaceWon(val crewSize: Int) : CompletionPresentation

    /** A shared race someone else won: "ANNA WON" over "YOU FINISHED 2ND", the "CREW ×N" badge,
     *  subdued. */
    data class RacePlaced(val place: Int, val winnerName: String, val crewSize: Int) : CompletionPresentation
}

/** What the failure overlay says for a failed row. Only a shared streak can fail. */
sealed interface FailurePresentation {
    /** "STREAK BROKEN", with "Anna missed a day" or "You missed a day" under it. [brokenByName]
     *  is null when the crew cache has no name for the pilot who broke it. */
    data class StreakBroken(val brokenByName: String?, val bySelf: Boolean) : FailurePresentation
}

/**
 * The presentation for [challenge], or null unless it is completed. A pure derivation from the
 * row: `bySelf` was stamped by the merge and the crew's names sit in the cache, so no profile or
 * repository is needed (invariant 11).
 *
 * A shared race whose outcome is not a foreign completion is presented as won: the row
 * completed by the pilot's own arrival, and until the server says otherwise that is the truth
 * the pilot has (L6). A refused claim rewrites the outcome through the merge's rule 3, and the
 * queue resolves the row by id at presentation time, so the corrected presentation is the one
 * shown.
 */
fun presentationFor(challenge: Challenge): CompletionPresentation? {
    if (challenge.status != ChallengeStatus.COMPLETED) return null
    if (!challenge.isShared()) return CompletionPresentation.Solo
    if (challenge.type != ChallengeType.ROUTE) return CompletionPresentation.Team(challenge.crewSize())
    val outcome = challenge.sharedOutcome
    return if (outcome is SharedOutcome.Completed && !outcome.bySelf) {
        CompletionPresentation.RacePlaced(
            // A pilot the placements do not list (joined after the finish, an older server)
            // still did not win, so second is the least wrong place to show.
            place = challenge.racePlacement() ?: 2,
            winnerName = challenge.nameOf(outcome.byUserCode),
            crewSize = challenge.crewSize(),
        )
    } else {
        CompletionPresentation.RaceWon(challenge.crewSize())
    }
}

/**
 * The presentation for [challenge], or null unless it is failed. A failed row without a
 * `Failed` outcome cannot be produced by the merge, but a row is presented and then deleted on
 * its status alone, so it still gets the shatter rather than being stuck in its slot.
 */
fun failurePresentationFor(challenge: Challenge): FailurePresentation? {
    if (challenge.status != ChallengeStatus.FAILED) return null
    val outcome = challenge.sharedOutcome as? SharedOutcome.Failed
        ?: return FailurePresentation.StreakBroken(brokenByName = null, bySelf = false)
    return FailurePresentation.StreakBroken(
        brokenByName = challenge.crew().firstOrNull { it.userCode == outcome.brokenByUserCode }
            ?.username?.takeIf { it.isNotBlank() },
        bySelf = outcome.bySelf,
    )
}

/** The crew's name for [userCode], or the code itself when the cache has no name for it. */
private fun Challenge.nameOf(userCode: String): String =
    crew().firstOrNull { it.userCode == userCode }?.username?.takeIf { it.isNotBlank() } ?: userCode
