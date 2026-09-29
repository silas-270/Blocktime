package com.silas270.blocktime.data.repository

import com.silas270.blocktime.data.model.Challenge
import com.silas270.blocktime.data.model.ChallengeStatus
import com.silas270.blocktime.data.model.ChallengeType
import com.silas270.blocktime.data.model.crewSize
import com.silas270.blocktime.data.model.displayProgressFraction
import com.silas270.blocktime.data.model.isShared
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What docs/core-loop.md's post-landing pipeline step 4 found for the just-landed flight,
 * as far as challenges are concerned - the second beat of step 5's "always sequenced, never
 * replaced" landing sequence (the existing rank-stamp `ArrivalCelebrationScreen` always shows
 * first, unchanged; this is what decides whether anything follows it).
 *
 * [Pending] is deliberately distinct from [None]: `InFlightViewModel.completeFlight()`'s challenge
 * check runs on `Dispatchers.IO` and can still be in flight by the time the rank-stamp screen's
 * own timed animation finishes and the player taps "continue" - the UI awaits a *resolved* value
 * (anything but [Pending]) rather than racing it (see `CesiumGameActivity`'s
 * `Screen.ArrivalCelebration` `onContinue`), so a flight that actually did complete a challenge can
 * never be silently skipped past just because the check hadn't finished yet.
 */
sealed class LandingResult {
    /** The challenge check hasn't resolved yet for this flight. Never a terminal value - the UI
     *  awaits past it, it never navigates on it. */
    object Pending : LandingResult()

    /** The check resolved and nothing changed - no active challenge advanced or completed. This is
     *  every Story/Free Mode landing with no active challenges, and also a STORY/CHALLENGE landing
     *  that simply didn't touch any active challenge (e.g. landed somewhere irrelevant to any
     *  Set-completion challenge). The landing sequence stops after the rank stamp, unchanged from
     *  today. */
    object None : LandingResult()

    /** At least one active challenge changed - a single landing can credit more than one at once
     *  (e.g. two Distance challenges and a Set-completion challenge all get credited by the same
     *  flight, since `MAX_ACTIVE_CHALLENGES` is 3). [outcomes] holds every one of them, in
     *  [before]'s order - see [resolveLandingOutcome]. `ChallengeOutcomeScreen` shows one bar per
     *  entry, so [outcomes] is never empty. */
    data class ChallengesAffected(val outcomes: List<ChallengeOutcome>) : LandingResult()
}

/**
 * One challenge's change from a single landing - either it advanced (challenges.md's "Per-leg
 * progress feedback") but didn't reach 100%, or it completed (challenges.md's "Completion
 * presentation"). [oldProgress]/[newProgress] are 0f..1f, per
 * [com.silas270.blocktime.data.model.displayProgressFraction]: the team's progress for a shared
 * pool, the pilot's own otherwise.
 *
 * [isShared] and [crewSize] let the outcome row show "CREW ×N" next to a team bar
 * (docs/shared-challenges.md "Landing"). They sit after [iconName] with defaults so every
 * existing construction, positional or named, keeps compiling and means "solo".
 */
sealed interface ChallengeOutcome {
    val challengeId: Int
    val name: String
    val type: ChallengeType
    val iconName: String?
    val isShared: Boolean
    /** Pilots in the room, self included and leavers excluded; 1 when not shared. */
    val crewSize: Int

    data class Advanced(
        override val challengeId: Int,
        override val name: String,
        override val type: ChallengeType,
        val oldProgress: Float,
        val newProgress: Float,
        override val iconName: String? = null,
        override val isShared: Boolean = false,
        override val crewSize: Int = 1
    ) : ChallengeOutcome

    data class Completed(
        override val challengeId: Int,
        override val name: String,
        override val type: ChallengeType,
        val oldProgress: Float,
        override val iconName: String? = null,
        override val isShared: Boolean = false,
        override val crewSize: Int = 1
    ) : ChallengeOutcome
}

/**
 * Diffs a before/after snapshot of the challenges that were active going into this landing to
 * determine what (if anything) changed. Pulled out as a pure, dependency-free function - like
 * [processLandingForChallenges] - so it's directly unit-testable (see LandingResultTest) without
 * touching Room or the native engine; the caller (`InFlightViewModel.checkAchievementsAndChallenges`)
 * does the actual repository reads and hands the two snapshots in.
 *
 * [after] must include every id from [before] regardless of status - a challenge that just
 * *completed* is no longer ACTIVE, so a caller that re-queries only the active list (rather than
 * looking each [before] id up individually) would silently miss every completion. Any [before]
 * entry missing from [after] (e.g. abandoned mid-flight, an unlikely but possible race) is skipped
 * rather than crashing.
 *
 * A single landing can affect more than one active challenge at once (e.g. one Distance challenge
 * and one Set-completion challenge both get credited by the same flight) - every changed challenge
 * is collected here, in [before]'s order, including a Route challenge's own per-leg advance (shown
 * as a bar here too, alongside its own persistent progress strip on the Hub).
 */
fun resolveLandingOutcome(before: List<Challenge>, after: List<Challenge>): LandingResult {
    val afterById = after.associateBy { it.id }
    val outcomes = mutableListOf<ChallengeOutcome>()

    for (old in before) {
        val new = afterById[old.id] ?: continue
        val oldProgress = old.displayProgressFraction()

        if (old.status == ChallengeStatus.ACTIVE && new.status != ChallengeStatus.ACTIVE) {
            // A row that went terminal counts as completed by this landing only when the
            // outcome is absent or the pilot's own. A sync is allowed to run mid-flight, so a
            // foreign completion (or a group streak breaking) can land between the before and
            // after reads; that is not what this landing did, and the outcome screen only ever
            // shows what the pilot's own landing did (docs/shared-challenges.md "Landing", L8).
            // Such a row is presented on Challenges like every other foreign outcome.
            val outcome = new.sharedOutcome
            if (new.status == ChallengeStatus.COMPLETED && (outcome == null || outcome.bySelf)) {
                outcomes += ChallengeOutcome.Completed(
                    new.id, new.name, new.type, oldProgress, new.iconName, new.isShared(), new.crewSize()
                )
            }
            continue
        }

        val newProgress = new.displayProgressFraction()
        if (newProgress != oldProgress) {
            outcomes += ChallengeOutcome.Advanced(
                new.id, new.name, new.type, oldProgress, newProgress, new.iconName, new.isShared(), new.crewSize()
            )
        }
    }

    return if (outcomes.isEmpty()) LandingResult.None else LandingResult.ChallengesAffected(outcomes)
}

/**
 * Bridges the challenge-check result across the InFlight -> ArrivalCelebration -> (tick-up |
 * completion) navigation hop, the same way `PreferencesRepository`'s `PausedFlight` already
 * bridges other per-session state across screens/ViewModels. A plain nav arg can't carry a result
 * shaped like [LandingResult] (a sealed class with a different field set per case, including a
 * float pair) without an ugly string-encoding scheme, and this only ever needs to reach the next
 * screen within the same process - never survive process death. Owned at the Activity level (one
 * instance, constructed alongside the other repositories in `CesiumGameActivity`) so it outlives
 * both the `InFlightViewModel` that publishes into it (cleared once its nav entry is popped) and
 * the screens that read it afterward.
 */
class LandingResultChannel {
    private val _result = MutableStateFlow<LandingResult>(LandingResult.Pending)
    val result: StateFlow<LandingResult> = _result.asStateFlow()

    /**
     * False until a flight in *this process* has reset the channel for its landing. A fresh
     * channel after process death is `Pending` forever - nothing will ever publish to it - so the
     * arrival screen reads this to skip waiting on a result that cannot arrive.
     */
    @Volatile
    var isArmed: Boolean = false
        private set

    /** Call at the start of every new flight (`InFlightViewModel.init`) so a stale result from a
     *  previous flight can never leak into this one's landing sequence. */
    fun reset() {
        isArmed = true
        _result.value = LandingResult.Pending
    }

    fun publish(result: LandingResult) {
        _result.value = result
    }
}
