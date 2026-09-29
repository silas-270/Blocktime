package com.silas270.blocktime.data.model

/**
 * Lifecycle per docs/challenges.md and docs/shared-challenges.md: Active -> Completed (final
 * qualifying flight, or a crew member's for a shared pool), Active -> Failed (a shared streak
 * broken by any crew member), or Active -> gone (abandon deletes the row entirely rather than
 * storing e.g. an ABANDONED status here - see `ChallengeRepository.abandonChallenge` and
 * challenges.md's "Abandon, not reset").
 *
 * Both terminal states use the same `celebrated` mechanic: the row keeps its slot until its
 * presentation has been shown. What happens after differs: a completion stays as a log entry, a
 * failure is deleted (docs/shared-challenges.md "Presentation"), so the log stays a log of
 * successes.
 */
enum class ChallengeStatus {
    ACTIVE,
    COMPLETED,

    /**
     * Terminal like [COMPLETED]. Only a shared streak can fail: one crew member missing a day
     * ends the group streak for everyone (docs/shared-challenges.md "The merge"). A solo streak
     * never fails, it just decays to zero and keeps running, as `Challenge.currentStreak`
     * describes. A FAILED row is deleted once its presentation has played, so there is never a
     * FAILED row with `celebrated = 1`.
     */
    FAILED
}
