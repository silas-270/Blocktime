package com.silas270.blocktime.domain

import com.silas270.blocktime.data.model.Challenge
import com.silas270.blocktime.data.model.ChallengeStatus
import com.silas270.blocktime.data.model.ChallengeType
import com.silas270.blocktime.data.model.CuratedChallengeSets
import com.silas270.blocktime.data.model.OutcomeClaim
import com.silas270.blocktime.data.model.ParticipantSnapshot
import com.silas270.blocktime.data.model.RoomState
import com.silas270.blocktime.data.model.RoomStateCache
import com.silas270.blocktime.data.model.SharedOutcome
import com.silas270.blocktime.data.model.isOwnStreakAlive
import com.silas270.blocktime.data.model.others
import com.silas270.blocktime.data.model.teamDistanceKm
import com.silas270.blocktime.data.model.teamStreakDays
import com.silas270.blocktime.data.model.teamVisitedMembers
import com.silas270.blocktime.data.model.withStreakEvaluatedAt
import java.time.LocalDate
import java.time.ZoneId

/**
 * What [mergeRoomIntoChallenge] decided: the row as it should now be stored, the claim to send
 * back to the server if the merge ended the challenge, and whether anything beyond the cache
 * changed. [changed] is false for a row that only got a fresher cache, so the caller can write
 * the cache with its own scoped statement and touch the shared fields only when they moved.
 */
data class MergeResult(val challenge: Challenge, val claim: OutcomeClaim?, val changed: Boolean)

/**
 * The merge of docs/shared-challenges.md "The merge": a pure function of the local row, the
 * room state the server returned, the pilot's own code and the clock. The rules, in order:
 *
 * 1. The cache is always refreshed with [room].
 * 2. A terminal row that was already presented gets nothing else, unless its completion was
 *    never confirmed: then the log takes the server's outcome ([withLogCorrection]).
 * 3. A terminal row not yet presented, when the room has an outcome: the server wins. The
 *    status follows the outcome's kind and the outcome is copied. This is how a refused claim
 *    resolves (someone finished first: a placement instead of a win) and how "completed locally
 *    while offline, but the group streak broke meanwhile" resolves (a failure). No claim: the
 *    server already has its answer.
 * 4. An active row when the room says `Completed`: completed, unpresented, `bySelf` stamped. A
 *    route row drops its paused leg, because a paused leg of a finished race is worthless and
 *    would otherwise still offer RESUME.
 * 5. An active row when the room says `Failed`: failed, likewise.
 * 6. An active row when the room is still open, per type ([decideOpenRoom]).
 * 7. [MergeResult.changed] is any row change beyond the cache.
 *
 * The pilot's own columns are never written here. The only inputs to them are the landing
 * pipeline and the pilot's own actions, and a merge that could rewrite them would let a peer's
 * upload change what this pilot flew.
 *
 * [zone] is the pilot's own time zone, used to read the row's `startedAt` as a calendar day for
 * the joiner rule; it defaults to the device's zone and is a parameter so tests are not tied to
 * the machine they run on.
 */
fun mergeRoomIntoChallenge(
    local: Challenge,
    room: RoomState,
    selfCode: String,
    today: LocalDate,
    now: Long,
    zone: ZoneId = ZoneId.systemDefault(),
): MergeResult {
    // Rule 1. The room passed in is what the server returned, so the cache says the room is
    // there; a gone room reaches the row through the repository's own path, never through here.
    val cached = local.copy(roomState = RoomStateCache(selfCode, room))
    val outcome = room.outcome
    val terminal = local.status != ChallengeStatus.ACTIVE

    val (decided, claim) = when {
        // Rule 2.
        terminal && local.celebrated -> cached.withLogCorrection(local, outcome, selfCode) to null
        // Rule 3.
        terminal -> (if (outcome != null) cached.withServerOutcome(outcome, selfCode) else cached) to null
        // Rules 4 and 5. The row completes or fails unpresented so the screen presents it;
        // `celebrated` is already false on an active row and is written explicitly anyway, so
        // the rule reads as it is stated.
        outcome != null -> cached.withServerOutcome(outcome, selfCode)
            .copy(
                celebrated = false,
                pausedFlight = if (local.type == ChallengeType.ROUTE) null else local.pausedFlight,
            ) to null
        // Rule 6.
        else -> cached.decideOpenRoom(selfCode, today, now, zone)
    }

    // Rule 7. Compared with the old cache put back, so a cache-only refresh is not a change.
    val changed = decided.copy(roomState = local.roomState) != local
    return MergeResult(decided, claim, changed)
}

/**
 * Rule 2's one exception: a completion this phone presented before the server confirmed it. A
 * race won offline is celebrated as a win, and the server may answer later that a crew member
 * arrived first. **The log follows the server; the celebration is not replayed.** Only the
 * outcome is replaced, so the stamp reads the real placement ("2ND"), while status, completion
 * time and the presented flag stay as they are and the entry keeps its place in the log.
 *
 * "Not confirmed" is the cache holding no outcome, the same condition under which the syncer
 * still sends this row's claim. Once a reply carries an outcome the cache holds it, the row
 * leaves the syncable list and this never runs for it again. Only a completion can correct a
 * completion: a presented row is never turned into a failure after the fact.
 */
private fun Challenge.withLogCorrection(local: Challenge, outcome: SharedOutcome?, selfCode: String): Challenge =
    if (
        outcome is SharedOutcome.Completed &&
        local.status == ChallengeStatus.COMPLETED &&
        local.roomState?.room?.outcome == null
    ) {
        copy(sharedOutcome = outcome.copy(bySelf = outcome.byUserCode == selfCode))
    } else {
        this
    }

/**
 * The row ended by [outcome] as the server holds it: status by kind, `bySelf` stamped from
 * [selfCode] because the server always sends false, and `completedAt` set to the moment the
 * room ended so the log orders it with everyone else's copy of the same event.
 */
private fun Challenge.withServerOutcome(outcome: SharedOutcome, selfCode: String): Challenge = when (outcome) {
    is SharedOutcome.Completed -> copy(
        status = ChallengeStatus.COMPLETED,
        sharedOutcome = outcome.copy(bySelf = outcome.byUserCode == selfCode),
        completedAt = outcome.at,
    )
    is SharedOutcome.Failed -> copy(
        status = ChallengeStatus.FAILED,
        sharedOutcome = outcome.copy(bySelf = outcome.brokenByUserCode == selfCode),
        completedAt = outcome.at,
    )
}

/**
 * Rule 6: an active row whose room has no outcome yet. Whoever sees a pool fill first claims
 * the completion, and whoever sees a streak die first claims the failure; the server keeps the
 * first claim, so a claim needs no coordination and a refused one resolves through rule 3.
 *
 * - Set: the union covers every member of the current definition. An unknown definition never
 *   completes: there is nothing to be complete against, and the solo credit path behaves the
 *   same way.
 * - Distance: the sum reaches the target.
 * - Streak: any crew member is dead, or the pilot's own streak is dead by the local rule: fail,
 *   naming the first dead pilot. Otherwise the crew's minimum reaches the target: complete.
 * - Route: never terminal here. A race is won only by an arrival.
 */
private fun Challenge.decideOpenRoom(selfCode: String, today: LocalDate, now: Long, zone: ZoneId): Pair<Challenge, OutcomeClaim?> {
    val completedBySelf = SharedOutcome.Completed(byUserCode = selfCode, bySelf = true, at = now)
    fun complete() = copy(
        status = ChallengeStatus.COMPLETED,
        celebrated = false,
        sharedOutcome = completedBySelf,
        completedAt = now,
    ) to OutcomeClaim.Completed(now)

    return when (type) {
        ChallengeType.ROUTE -> this to null
        ChallengeType.SET_COMPLETION -> {
            val definition = setCatalogId?.let { CuratedChallengeSets.find(it) }
            if (definition != null && teamVisitedMembers().containsAll(definition.members)) complete() else this to null
        }
        ChallengeType.DISTANCE -> {
            val target = targetDistanceKm?.takeIf { it > 0 }
            if (target != null && teamDistanceKm() >= target) complete() else this to null
        }
        ChallengeType.STREAK -> {
            // The others first, in join order, then self: the claim names one pilot, and a
            // crew member's snapshot is the older evidence, so a group that died on two pilots
            // at once names the same one from every phone that merges the same room state.
            val brokenBy = others().firstOrNull { !it.isStreakAliveAt(today, now) }?.userCode
                ?: selfCode.takeIf { !isOwnStreakAlive(today, zone) }
            if (brokenBy != null) {
                copy(
                    status = ChallengeStatus.FAILED,
                    celebrated = false,
                    sharedOutcome = SharedOutcome.Failed(brokenByUserCode = brokenBy, bySelf = brokenBy == selfCode, at = now),
                    completedAt = now,
                ) to OutcomeClaim.Failed(brokenBy)
            } else {
                // Self's days through the clock, as every reader downstream of the repository
                // sees them; the decision is made on the evaluated copy, the stored row keeps
                // its own columns (invariant 1).
                val target = targetDays?.takeIf { it > 0 }
                if (target != null && withStreakEvaluatedAt(today).teamStreakDays() >= target) complete() else this to null
            }
        }
    }
}

/** Two days in millis, the slack of [isStreakAliveAt]. */
private const val STREAK_SLACK_MS = 2 * 24 * 60 * 60 * 1000L

/**
 * Whether a crew member's streak is alive, from their snapshot alone.
 *
 * Their own verdict ([ParticipantSnapshot.streakAlive]) is taken when it says dead: the owner
 * computed it with the local rule on their own row, which is the best evidence there is. When
 * it says alive, it is only as fresh as their last upload, so a stale snapshot is checked
 * against the calendar instead: a last flown day before the day before yesterday is dead, and
 * a snapshot that has never flown and has not been written for over two days is dead by the
 * joiner rule (alive on the join day and the day after).
 *
 * The slack is two days rather than the one day [Challenge.currentStreak] gives yesterday
 * because the peer's `lastFlownDay` is their local calendar day and [today] is this pilot's.
 * Two pilots can sit up to 26 hours apart in calendar time (UTC-12 to UTC+14), so a peer who
 * flew on their "yesterday" may have flown on this pilot's "day before yesterday". One day of
 * slack for the zone gap on top of the one day the rule gives everyone means a run that is in
 * fact alive is never called dead over the clock; a run that died is called dead one day
 * later at worst, and by then the owner's own upload has usually said so.
 */
private fun ParticipantSnapshot.isStreakAliveAt(today: LocalDate, now: Long): Boolean {
    if (!streakAlive) return false
    val lastDay = lastFlownDay?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
    return if (lastDay != null) {
        !lastDay.isBefore(today.minusDays(2))
    } else {
        // 0 is "never stamped", not "ancient": a snapshot the server has not timed gives no
        // evidence either way, and the default is alive.
        updatedAt <= 0L || now - updatedAt <= STREAK_SLACK_MS
    }
}
