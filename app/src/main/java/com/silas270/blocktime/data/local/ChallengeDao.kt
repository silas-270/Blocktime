package com.silas270.blocktime.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.silas270.blocktime.data.model.Challenge
import com.silas270.blocktime.data.model.ChallengeStatus
import com.silas270.blocktime.data.model.PausedFlight
import com.silas270.blocktime.data.model.RoomStateCache
import com.silas270.blocktime.data.model.SharedOutcome
import kotlinx.coroutines.flow.Flow

@Dao
interface ChallengeDao {
    @Insert
    suspend fun insert(challenge: Challenge): Long

    /**
     * Rewrites **every column** of the row - which is the part that matters, because two of these
     * racing each other is a lost update, not a merge: whichever writer read first and writes last
     * silently reverts the other's columns. Callers that only mean to change one concern should
     * use the scoped updates below instead; this stays for whole-row writes (insert-shaped edits
     * from the challenge lifecycle itself), and `LocalChallengeRepository` serialises every
     * read-modify-write pair that reaches it. The sync path never uses it (docs/state.md
     * "Concurrent writers").
     */
    @Update
    suspend fun update(challenge: Challenge)

    /**
     * The paused-flight slot alone.
     *
     * Scoped rather than a whole-row [update] because the two writers of this column - the
     * in-flight session saving its elapsed time/camera, and the landing pipeline clearing the slot
     * - are not the writers of the route columns next to it, and used to revert them. A landing
     * that credited a leg and then had `position_iata`/`leg_index`/`status` written back to their
     * pre-landing values by a camera save is the bug this exists to make unrepresentable; see
     * ChallengeRowConcurrentWriteTest and `InFlightViewModel.landingStarted`.
     */
    @Query("UPDATE challenges SET paused_flight = :flight WHERE id = :id")
    suspend fun updatePausedFlight(id: Int, flight: PausedFlight?)

    /**
     * The Route-progress columns alone - what [advanceRouteChallenge][com.silas270.blocktime
     * .data.repository.LocalChallengeRepository.advanceRouteChallenge] moves, and nothing else.
     * Same reasoning as [updatePausedFlight] in the other direction: crediting a leg must not
     * write back a stale `paused_flight` (or a stale streak, or a stale visited-member set) that
     * some other writer changed in between.
     *
     * [syncGeneration] travels with the route columns because a credited leg on a shared row is
     * a change to the pilot's own data, and the generation has to move in the same statement
     * as the data it describes, or a sync between the two writes would confirm a generation
     * whose progress it never sent. The caller passes the row's current value when nothing
     * needs uploading.
     */
    @Query(
        """
        UPDATE challenges
        SET position_iata = :positionIata,
            route_progress_fraction = :routeProgressFraction,
            leg_index = :legIndex,
            status = :status,
            completed_at = :completedAt,
            sync_generation = :syncGeneration
        WHERE id = :id
        """
    )
    suspend fun updateRouteProgress(
        id: Int,
        positionIata: String?,
        routeProgressFraction: Float,
        legIndex: Int,
        status: ChallengeStatus,
        completedAt: Long?,
        syncGeneration: Long
    )

    @Query("DELETE FROM challenges WHERE id = :id")
    suspend fun deleteById(id: Int)

    @Query("SELECT * FROM challenges WHERE id = :id")
    suspend fun getById(id: Int): Challenge?

    @Query("SELECT * FROM challenges WHERE user_id = :userId AND status = :status")
    suspend fun getByStatus(userId: Int, status: ChallengeStatus): List<Challenge>

    @Query("SELECT * FROM challenges WHERE user_id = :userId AND status = :status")
    fun getByStatusFlow(userId: Int, status: ChallengeStatus): Flow<List<Challenge>>

    @Query("SELECT COUNT(*) FROM challenges WHERE user_id = :userId AND status = :status")
    suspend fun countByStatus(userId: Int, status: ChallengeStatus): Int

    /** Ordered by completion time, newest first - backs the Achievements screen's "Challenges
     *  completed" log (docs/achievements.md - a flat log, not an x/N tally, since custom
     *  challenges and repeat completions mean there's no fixed denominator). Every completion
     *  leaves its own row (completing sets `status = COMPLETED` and keeps the row; only
     *  *abandoning* deletes it - see `ChallengeRepository.abandonChallenge`), so duplicates from
     *  repeat completions of the same curated/custom challenge show up here as separate rows,
     *  same as the flight logbook. */
    @Query("SELECT * FROM challenges WHERE user_id = :userId AND status = :status ORDER BY completed_at DESC")
    suspend fun getByStatusOrderedByCompletedAt(userId: Int, status: ChallengeStatus): List<Challenge>

    /** ACTIVE, or terminal (COMPLETED or FAILED) but not yet presented - everything that should
     *  still occupy a slot on the Challenges screen (docs/challenges.md). An unpresented failure
     *  occupies its slot exactly like an unpresented completion: the shatter has to play from
     *  that slot before it frees (docs/shared-challenges.md "Presentation"). Ordered by id so
     *  slot position is stable and matches original creation order. The statuses are literals,
     *  as in [getCelebratedCompletedOrderedByCompletedAt]: the set is the query's definition,
     *  not a parameter a caller could vary. */
    @Query(
        """
        SELECT * FROM challenges
        WHERE user_id = :userId
        AND (status = 'ACTIVE' OR (status IN ('COMPLETED', 'FAILED') AND celebrated = 0))
        ORDER BY id ASC
        """
    )
    fun getSlotDisplayFlow(userId: Int): Flow<List<Challenge>>

    /** Same "occupying a slot" definition as [getSlotDisplayFlow], as a count - what the
     *  active-challenge cap check (`LocalChallengeRepository.hasCapSlot`) tests against, so an
     *  unpresented completion or failure still counts against `MAX_ACTIVE_CHALLENGES` instead of
     *  leaving a fourth challenge with nowhere to render. */
    @Query(
        """
        SELECT COUNT(*) FROM challenges
        WHERE user_id = :userId
        AND (status = 'ACTIVE' OR (status IN ('COMPLETED', 'FAILED') AND celebrated = 0))
        """
    )
    suspend fun countOccupyingSlots(userId: Int): Int

    /** The completed-challenges log, but only entries the player has actually been shown the
     *  completion-presentation animation for - a completion is not "in the log" until its
     *  celebration has played (docs/challenges.md). COMPLETED only, and deliberately so: a FAILED
     *  row is deleted once its shatter has played, so the log stays a log of successes. Backs
     *  `listCompletedChallenges()`; replaces [getByStatusOrderedByCompletedAt] for that one
     *  caller. */
    @Query(
        """
        SELECT * FROM challenges WHERE user_id = :userId AND status = 'COMPLETED' AND celebrated = 1
        ORDER BY completed_at DESC
        """
    )
    suspend fun getCelebratedCompletedOrderedByCompletedAt(userId: Int): List<Challenge>

    /** Flips the one column the completion-presentation overlay owns, once its fly-out animation
     *  finishes for this challenge - scoped rather than a whole-row [update] for the same reason
     *  [updatePausedFlight] is: this writer is not the writer of the progress/status columns next
     *  to it, and must not revert them if it races one of those writes. */
    @Query("UPDATE challenges SET celebrated = 1 WHERE id = :id")
    suspend fun markCelebrated(id: Int)

    // ── Shared challenges (docs/shared-challenges.md) ───────────────────────────────────────
    //
    // Everything the sync path writes is scoped. The merge runs under the repository's write
    // mutex, but the in-flight session's paused-flight saves and the landing pipeline's credits
    // do not wait for a network round trip, so a whole-row write from the sync path could revert
    // a credit the pilot just earned. Each statement below names exactly the columns its writer
    // owns (docs/state.md "Concurrent writers").

    /**
     * What a merge writes when the room state changes the row: the status it decided, when the
     * room ended, the fresh cache and the outcome (docs/shared-challenges.md "The merge"). The
     * pilot's own progress columns are never among these, because the merge never changes them
     * (invariant 1: each pilot writes only their own snapshot). Writer: `applyRoomState`.
     */
    @Query(
        """
        UPDATE challenges
        SET status = :status,
            completed_at = :completedAt,
            room_state = :roomState,
            shared_outcome = :sharedOutcome
        WHERE id = :id
        """
    )
    suspend fun updateSharedFields(
        id: Int,
        status: ChallengeStatus,
        completedAt: Long?,
        roomState: RoomStateCache?,
        sharedOutcome: SharedOutcome?
    )

    /**
     * [updateSharedFields] plus clearing the paused flight, in one statement, for a Route row
     * that a foreign completion ends: a paused leg of a finished race is worthless (merge rule
     * 4). One statement rather than two so no camera save can slip a paused flight back in
     * between the status flip and the clear. A separate statement rather than a `pausedFlight`
     * parameter on [updateSharedFields], so the pooled types never touch a column that belongs
     * to the in-flight session. Writer: `applyRoomState`.
     */
    @Query(
        """
        UPDATE challenges
        SET status = :status,
            completed_at = :completedAt,
            room_state = :roomState,
            shared_outcome = :sharedOutcome,
            paused_flight = NULL
        WHERE id = :id
        """
    )
    suspend fun updateSharedFieldsAndClearPausedFlight(
        id: Int,
        status: ChallengeStatus,
        completedAt: Long?,
        roomState: RoomStateCache?,
        sharedOutcome: SharedOutcome?
    )

    /**
     * The cache alone, for a merge that changed nothing but the cache (only foreign progress
     * arrived, or the room is gone and `roomGone` is being recorded). Writer: `applyRoomState`
     * and the syncer's not-found handling.
     */
    @Query("UPDATE challenges SET room_state = :roomState WHERE id = :id")
    suspend fun updateRoomState(id: Int, roomState: RoomStateCache?)

    /**
     * Marks the pilot's own data on this row as changed since the last confirmed upload. An
     * increment in SQL rather than a value from Kotlin so two credits that race each other both
     * count, whatever either of them read. Writers: the credit paths (`creditDistance`,
     * `creditSetCompletion`, `creditStreak`) on a shared row.
     */
    @Query("UPDATE challenges SET sync_generation = sync_generation + 1 WHERE id = :id")
    suspend fun bumpSyncGeneration(id: Int)

    /**
     * Records that the server accepted the snapshot of [generation]. The `sync_generation`
     * guard is the whole point: if a credit moved the row on while the upload was in flight, the
     * confirmation is a no-op, the row stays pending, and the next sync sends the newer state
     * (docs/shared-challenges.md "Data model", situation L11). Writer: the syncer, after a put.
     */
    @Query(
        """
        UPDATE challenges SET synced_generation = :generation
        WHERE id = :id AND sync_generation = :generation
        """
    )
    suspend fun confirmSynced(id: Int, generation: Long)

    /**
     * Turns a solo row into a shared one (or, with null, back): the room code and the first
     * cache, written together so a row is never shared without a cache to show its crew from.
     * Writer: `shareChallenge`, under the write mutex, after the re-check that the row is still
     * shareable (situation S8).
     */
    @Query("UPDATE challenges SET room_code = :roomCode, room_state = :roomState WHERE id = :id")
    suspend fun updateRoomLink(id: Int, roomCode: String?, roomState: RoomStateCache?)

    /**
     * Every row the syncer has to talk to the server about: shared, and either still running
     * or terminal but not yet presented (a presented row's log entry freezes; docs/
     * shared-challenges.md "Sync moments"). `roomGone` is not filtered here because it lives
     * inside the cache JSON; `listSyncableChallenges` drops those rows in Kotlin.
     */
    @Query(
        """
        SELECT * FROM challenges
        WHERE user_id = :userId
        AND room_code IS NOT NULL
        AND (status = 'ACTIVE' OR celebrated = 0)
        ORDER BY id ASC
        """
    )
    suspend fun getSyncable(userId: Int): List<Challenge>

    /** The pilot's row for a room, if any - at most one, by the unique `(user_id, room_code)`
     *  index. What `joinRoom` checks before joining (situations J6a and J6b). */
    @Query("SELECT * FROM challenges WHERE user_id = :userId AND room_code = :roomCode")
    suspend fun getByRoomCode(userId: Int, roomCode: String): Challenge?

    /** How many terminal rows still wait for their presentation, completion or failure. What
     *  `resolveArrivalDestination` reads to send the pilot to the Challenges tab after a landing
     *  (docs/shared-challenges.md "Landing"). */
    @Query(
        """
        SELECT COUNT(*) FROM challenges
        WHERE user_id = :userId AND status IN ('COMPLETED', 'FAILED') AND celebrated = 0
        """
    )
    suspend fun countPendingPresentation(userId: Int): Int
}
