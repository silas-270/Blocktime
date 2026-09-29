package com.silas270.blocktime.data.repository

import com.silas270.blocktime.data.model.Challenge
import com.silas270.blocktime.data.model.ChallengeStatus
import com.silas270.blocktime.data.model.ChallengeType
import com.silas270.blocktime.data.model.RoomState
import com.silas270.blocktime.data.network.room.RoomResult
import com.silas270.blocktime.domain.MergeResult
import kotlinx.coroutines.flow.Flow

/**
 * Result of trying to start a new challenge instance - see docs/challenges.md's
 * active-challenge cap (3, curated+custom combined across all three types).
 */
sealed class StartChallengeResult {
    data class Started(val challenge: Challenge) : StartChallengeResult()

    /** Refused: 3 challenges are already active. Starting a challenge never evicts an existing
     *  one - the player must abandon one first (challenges.md's "Abandon, not reset"). */
    object CapReached : StartChallengeResult()

    /** The requested curated catalogId/setId doesn't exist in the seed catalog. */
    object UnknownTemplate : StartChallengeResult()
}

/** Why a row cannot be shared right now (docs/shared-challenges.md "Sharing", S3, S4, S7). */
enum class ShareIneligibility {
    /** Terminal rows are never shared (S7). */
    NOT_ACTIVE,

    /** Already linked to a room; the modal shows the code instead (S5). */
    ALREADY_SHARED,

    /** Only a fresh challenge can be shared (S4). */
    HAS_PROGRESS,

    /** A route with a paused leg is mid-flight, even at leg zero (S3). */
    HAS_PAUSED_LEG,

    /** The row does not exist. */
    UNKNOWN,
}

/**
 * Why [challenge] cannot be shared, or null when it can (situation S3): active, not yet linked
 * to a room, and at zero progress for its type. Zero progress is judged per type, because each
 * type records it differently: an empty set, no kilometres, no streak day credited, or a route
 * still at its origin on leg zero with no paused leg. Pure, so the info modal can dim the button
 * with the same rule the repository enforces.
 */
fun Challenge.shareIneligibility(): ShareIneligibility? = when {
    status != ChallengeStatus.ACTIVE -> ShareIneligibility.NOT_ACTIVE
    roomCode != null -> ShareIneligibility.ALREADY_SHARED
    type == ChallengeType.ROUTE && pausedFlight != null -> ShareIneligibility.HAS_PAUSED_LEG
    hasOwnProgress() -> ShareIneligibility.HAS_PROGRESS
    else -> null
}

private fun Challenge.hasOwnProgress(): Boolean = when (type) {
    ChallengeType.SET_COMPLETION -> visitedSetMembers.isNotEmpty()
    ChallengeType.DISTANCE -> cumulativeDistanceKm > 0.0
    ChallengeType.STREAK -> streakDays != 0 || lastFlownDay != null
    ChallengeType.ROUTE -> positionIata != originIata || legIndex != 0
}

/** Result of [ChallengeRepository.shareChallenge] (docs/shared-challenges.md "Sharing"). */
sealed interface ShareResult {
    /** The room exists and the row is linked to it. [challenge] is the linked row. */
    data class Shared(val code: String, val challenge: Challenge) : ShareResult

    /** Nothing was changed. Includes the re-check under the lock failing after the room was
     *  created (S8); the room is then left by the next sync. */
    data class NotEligible(val reason: ShareIneligibility) : ShareResult

    /** The server did not create the room; nothing was changed (S6). */
    data class Unavailable(val error: RoomResult<Nothing>) : ShareResult
}

/** Result of [ChallengeRepository.joinRoom] (docs/shared-challenges.md "Joining"). */
sealed interface JoinResult {
    /** The row exists locally and the server has the pilot's snapshot (J4). */
    data class Joined(val challenge: Challenge) : JoinResult

    /** The cap was full once the server had accepted; the room was left again (J5). */
    data object CapReached : JoinResult

    /** An active local row already has this code (J6a). */
    data class AlreadyJoined(val activeId: Int) : JoinResult

    /** A finished local row has this code; a room is joined once per pilot (J6b). */
    data object AlreadyFinished : JoinResult

    /** This app version cannot build a row from the room's definition (J12). */
    data object UnknownTemplate : JoinResult

    /** No room with that code (J3). */
    data object NotFound : JoinResult

    /** The room already has an outcome (J7). */
    data object RoomClosed : JoinResult

    /** A race that already has progress (J8). */
    data object RaceLocked : JoinResult

    /** Six pilots already (J10). */
    data object RoomFull : JoinResult

    /** The server did not answer usefully; nothing was changed (J11). */
    data class Unavailable(val error: RoomResult<Nothing>) : JoinResult
}

/**
 * This result as the failure it is. Every failure case is a `data object` typed
 * `RoomResult<Nothing>`, so this is a narrowing the compiler cannot do from a `when` on its own.
 * Throws for [RoomResult.Ok], which is not a failure.
 */
fun RoomResult<*>.asFailure(): RoomResult<Nothing> = when (this) {
    is RoomResult.Ok -> throw IllegalArgumentException("Ok is not a failure")
    RoomResult.NotFound -> RoomResult.NotFound
    RoomResult.RoomClosed -> RoomResult.RoomClosed
    RoomResult.RaceLocked -> RoomResult.RaceLocked
    RoomResult.RoomFull -> RoomResult.RoomFull
    RoomResult.Unreachable -> RoomResult.Unreachable
    RoomResult.Unauthorized -> RoomResult.Unauthorized
}

/**
 * The [JoinResult] a failed room call maps to. Shared by the repository's join and the
 * picker's look-up, so a code that cannot be joined is explained the same way in both places.
 * Throws for [RoomResult.Ok], which is not a failure.
 */
fun RoomResult<*>.asJoinFailure(): JoinResult = when (val failure = asFailure()) {
    RoomResult.NotFound -> JoinResult.NotFound
    RoomResult.RoomClosed -> JoinResult.RoomClosed
    RoomResult.RaceLocked -> JoinResult.RaceLocked
    RoomResult.RoomFull -> JoinResult.RoomFull
    RoomResult.Unreachable, RoomResult.Unauthorized -> JoinResult.Unavailable(failure)
    is RoomResult.Ok -> throw IllegalStateException("asFailure never returns Ok")
}

/**
 * Single store for all active/completed challenges (all three types, curated and custom alike) -
 * see docs/challenges.md#persistence--route-scoping. This is the one seam
 * `InFlightViewModel.completeFlight()`'s post-landing pipeline calls into for the challenge half
 * of `checkAchievementsAndChallenges()` (via [processLandingForChallenges]), and the seam the
 * Hub's quest-log UI calls into for start/abandon/list/render-a-progress-bar.
 */
interface ChallengeRepository {
    suspend fun listActiveChallenges(): List<Challenge>
    fun listActiveChallengesFlow(): Flow<List<Challenge>>
    suspend fun getChallenge(id: Int): Challenge?

    /**
     * ACTIVE challenges, plus any COMPLETED one that hasn't been shown its completion-presentation
     * animation yet (see [Challenge.celebrated], docs/challenges.md) - what the Challenges
     * screen's three slots actually render, as opposed to [listActiveChallengesFlow]'s strict
     * ACTIVE-only view (used for landing-outcome diffing, where an uncelebrated completion must
     * NOT be mistaken for still-active).
     */
    fun listSlotDisplayChallengesFlow(): Flow<List<Challenge>>

    /**
     * Every completed challenge (curated or custom, duplicates included for repeat completions of
     * the same one), newest first - the data source for the Achievements screen's "Challenges
     * completed" log (docs/achievements.md). Cross-mode exception per achievements.md's
     * "Scope & isolation": this is the one place a CHALLENGE-tagged flight's effect (completing a
     * Route challenge) or any mode's Distance/Set-completion crediting surfaces in Achievements,
     * display/recognition only - it grants nothing Story-Mode-scoped.
     *
     * Only *celebrated* completions - a challenge doesn't join this log until its
     * completion-presentation animation has played (see [markCelebrated]).
     */
    suspend fun listCompletedChallenges(): List<Challenge>

    /** Marks challenge [id]'s completion as shown to the player - called once, by the
     *  completion-presentation overlay, the instant its fly-out animation for this challenge
     *  finishes. Moves the row out of [listSlotDisplayChallengesFlow] and into
     *  [listCompletedChallenges] in the same write. No-op if [id] doesn't exist. */
    suspend fun markCelebrated(id: Int)

    suspend fun startCuratedChallenge(catalogId: String): StartChallengeResult
    suspend fun startCustomRouteChallenge(originIata: String, destIata: String, name: String): StartChallengeResult
    suspend fun startCustomDistanceChallenge(targetDistanceKm: Double, name: String): StartChallengeResult

    /** [targetDays] is expected to be small (3-5) - see [ChallengeType.STREAK] for why. */
    suspend fun startCustomStreakChallenge(targetDays: Int, name: String): StartChallengeResult

    /** Removes the challenge entirely and frees its cap slot - no in-place reset
     *  (challenges.md's "Abandon, not reset"). No-op if [id] doesn't exist. */
    suspend fun abandonChallenge(id: Int)

    /**
     * Route-only: moves challenge [challengeId]'s own position pointer to [newPositionIata] and
     * recomputes its progress via [com.silas270.blocktime.data.model.ChallengeProgress.routeProgress],
     * marking it COMPLETED if [newPositionIata] is the challenge's destination. Never touches
     * `currentAirport` - callers only reach this from a CHALLENGE-tagged session scoped to this
     * specific challenge id (see docs/challenges.md#which-flights-count). No-op (returns
     * the challenge unchanged) if [challengeId] isn't an active Route challenge, or doesn't exist.
     */
    suspend fun advanceRouteChallenge(challengeId: Int, newPositionIata: String): Challenge?

    /**
     * The paused-flight slot for Route challenge [challengeId] - a CHALLENGE-tagged session
     * scoped to it, kept on the challenge's own row (see [Challenge.pausedFlight]) so switching
     * Hub focus (or pausing back to Story Mode) never clobbers it, mirroring
     * [PreferencesRepository.pausedFlightStore] (the mode-dispatching function) for STORY/FREE.
     * Always returns a usable store even
     * if [challengeId] doesn't (currently) exist - its operations are just no-ops in that case.
     */
    fun pausedFlightStore(challengeId: Int): PausedFlightStore

    /**
     * Credits [destIata]/[distanceKm] toward every active Distance and Set-completion challenge
     * (never Route - Route only moves via [advanceRouteChallenge]'s explicit scoping). Meant to
     * be called for every eligible flight (STORY or CHALLENGE, never FREE - see
     * docs/modes.md's isolation matrix), including one flown under a *different*
     * active Route challenge's own session - Distance/Set-completion have no position pointer so
     * they passively credit any eligible flight regardless of its tag's specific scoping. Streak
     * credits the same way, off [completedAt] rather than the route.
     *
     * [completedAt] is the landing's own `FlightLog.completedAt`, not "now". Streak crediting
     * turns it into a local calendar day, so a flight logged just before midnight has to count
     * for the day it actually happened on rather than whenever this code runs.
     */
    suspend fun creditEligibleFlight(destIata: String, distanceKm: Double, completedAt: Long)

    // ── Shared challenges (docs/shared-challenges.md) ───────────────────────────────────────
    //
    // The pattern behind every operation that talks to the server: read, network, then
    // `withLock { read again; check; write with a scoped statement }`. No network call ever
    // runs under the write mutex, and nothing read before a round trip is trusted after it.

    /**
     * Creates a room for challenge [id] and links the row to it (S3). The row must be active,
     * unshared and at zero progress ([shareIneligibility]); the check runs before the room is
     * created and again under the lock, because a landing or an abandon can land in between
     * (S8), in which case the room is queued to be left and nothing local changes.
     */
    suspend fun shareChallenge(id: Int): ShareResult

    /** The room behind [code], for the join preview (J4). The code is normalised to upper case. */
    suspend fun lookUpRoom(code: String): RoomResult<RoomState>

    /**
     * Joins the room behind [code]: the pilot's first snapshot is the join on the server, then a
     * row is built from the room's definition under the lock, after the cap is checked again
     * (J4, J5). A code the pilot already has a row for is refused without a network call (J6a,
     * J6b). Removes [code] from the pending leaves (J13).
     */
    suspend fun joinRoom(code: String): JoinResult

    /**
     * Every shared row the syncer has to talk to the server about: still running, or terminal
     * but not yet presented, and whose room the server still knows. Raw rows, not evaluated
     * against today: the snapshot factory does that itself.
     */
    suspend fun listSyncableChallenges(): List<Challenge>

    /**
     * Merges [room] into row [id] under the lock, reading the row fresh first, and writes the
     * result with a scoped statement: the cache alone when nothing else changed, the shared
     * fields when the merge ended the challenge, and for a route that a foreign completion
     * ended, the paused leg is cleared in the same statement. Null when the row no longer exists
     * (P15): a row deleted during the sync is never re-inserted.
     */
    suspend fun applyRoomState(id: Int, room: RoomState): MergeResult?

    /** Records in row [id]'s cache that the server no longer knows its room (P11, P12). The row
     *  keeps running locally and is not synced again. No-op without a cache or a row. */
    suspend fun markRoomGone(id: Int)

    /** Records that the server accepted the snapshot of [generation]. A no-op when the row has
     *  moved on since (L11). */
    suspend fun confirmSynced(id: Int, generation: Long)

    /** Whether any terminal row still waits for its presentation, which is what sends the pilot
     *  to Challenges after a landing that itself completed nothing (L3, L7). */
    suspend fun hasPendingPresentation(): Boolean

    /** Deletes row [id] once its shatter has played, and only if it is still failed: the log
     *  stays a log of successes, and a failed row never carries `celebrated = 1`. */
    suspend fun dismissFailed(id: Int)
}
