package com.silas270.blocktime.data.repository

import android.util.Log
import com.silas270.blocktime.data.model.Challenge
import com.silas270.blocktime.data.model.ChallengeStatus
import com.silas270.blocktime.data.model.OutcomeClaim
import com.silas270.blocktime.data.model.SharedOutcome
import com.silas270.blocktime.data.model.isSyncPending
import com.silas270.blocktime.data.model.toParticipantSnapshot
import com.silas270.blocktime.data.network.ServerReachability
import com.silas270.blocktime.data.network.ServerState
import com.silas270.blocktime.data.network.room.RoomApi
import com.silas270.blocktime.data.network.room.RoomResult
import com.silas270.blocktime.data.network.room.bindCallerIdentity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Clock
import java.time.LocalDate

/** Why a sync was asked for (docs/shared-challenges.md "Sync moments"). */
enum class SyncReason {
    /** The app came to the foreground, cold start included. Debounced. */
    FOREGROUND,

    /** A landing has just been credited. Never debounced: the pilot's own data changed. */
    LANDING,

    /** The Challenges screen opened. Debounced. */
    SCREEN_OPEN,

    /** A successful share or join, or an abandon. Never debounced. */
    USER_ACTION;

    /** The two triggers that fire often without anything having changed locally. */
    val debounced: Boolean get() = this == FOREGROUND || this == SCREEN_OPEN
}

/** What the last sync did, for "Synced 3 min ago", the error hints and the Hub's refresh. */
sealed interface SyncSummary {
    /** Nothing was asked of the server: the state made it pointless, or the request was
     *  debounced (then [state] is the live state). A debounced request leaves
     *  [SharedChallengeSyncer.lastSummary] alone, because it is not a sync. */
    data class Skipped(val state: ServerState) : SyncSummary

    /** The server did not answer; whatever was not yet done is left for the next sync. */
    data object Unreachable : SyncSummary

    /** Every syncable row was talked about. [rows] is how many got a fresh room state. */
    data class Synced(val rows: Int, val at: Long) : SyncSummary
}

/**
 * The one place the app talks to the room server about the pilot's rows
 * (docs/shared-challenges.md "Sync moments", plan B6). Activity-scoped, built once in
 * `CesiumGameActivity.onCreate` on the Activity's own scope, so a landing can ask for a sync
 * without the In-Flight ViewModel's landing scope waiting for the network.
 *
 * Nothing here polls, and nothing here is on the landing pipeline's path: a landing is credited
 * locally first, and the sync is asked for afterwards, fire-and-forget. Every write to a row goes
 * through the repository, which takes its write mutex, re-reads the row and writes with a scoped
 * statement, so a credit that lands mid-sync is never reverted and a row deleted mid-sync is
 * never re-inserted (P15).
 *
 * [clock] is the pilot's own calendar: the snapshot's streak is read against today, so the day
 * has to be the pilot's day, not UTC's.
 */
class SharedChallengeSyncer(
    private val challengeRepository: ChallengeRepository,
    private val userRepository: UserRepository,
    private val preferencesRepository: PreferencesRepository,
    private val roomApi: RoomApi,
    private val serverReachability: ServerReachability,
    private val scope: CoroutineScope,
    private val clock: Clock = Clock.systemDefaultZone(),
) {
    private val _lastSummary = MutableStateFlow<SyncSummary?>(null)
    val lastSummary: StateFlow<SyncSummary?> = _lastSummary.asStateFlow()

    /** Serialises [syncNow]. Two syncs interleaved would send the same generation twice. */
    private val runMutex = Mutex()

    /** Guards [running] and [pendingReason]; both are touched from whatever thread asks. */
    private val requestLock = Any()
    private var running = false
    private var pendingReason: SyncReason? = null

    /**
     * Asks for a sync and returns at once. A request while one runs is coalesced: the reason is
     * remembered, the stronger one winning (a never-debounced reason over a debounced one), and
     * one more sync runs when the current one finishes. Many requests during a sync still make
     * exactly one more, which is enough, because a sync reads everything fresh when it starts.
     */
    fun requestSync(reason: SyncReason) {
        synchronized(requestLock) {
            if (running) {
                pendingReason = strongerOf(pendingReason, reason)
                return
            }
            running = true
        }
        scope.launch {
            var next: SyncReason? = reason
            while (next != null) {
                try {
                    syncNow(next)
                } catch (e: CancellationException) {
                    synchronized(requestLock) { running = false; pendingReason = null }
                    throw e
                } catch (e: Exception) {
                    // A sync must never take the scope down with it; the next trigger retries.
                    Log.e(TAG, "Sync failed", e)
                }
                next = synchronized(requestLock) {
                    val pending = pendingReason
                    pendingReason = null
                    if (pending == null) running = false
                    pending
                }
            }
        }
    }

    private fun strongerOf(a: SyncReason?, b: SyncReason): SyncReason =
        if (a == null || (a.debounced && !b.debounced)) b else a

    /**
     * One sync, to completion, and its summary. Serialised with every other sync. The steps of
     * docs/shared-challenges.md "Sync moments":
     *
     * 1. Ask [ServerReachability]; not configured, sharing off or the device offline is a skip.
     * 2. Debounce the foreground and screen-open triggers against the last completed sync.
     * 3. Send the pending leaves. A code with a live local row is dropped silently: the pilot
     *    rejoined the room (J13).
     * 4. For every syncable row: upload if the pilot's own data changed or the row has a claim
     *    to make, otherwise download; merge the reply; confirm the generation after an upload;
     *    send a claim the merge produced at once. A room the server no longer knows is marked
     *    gone and never asked about again; an unreachable server ends the sync.
     * 5. Record the time and publish the summary.
     */
    suspend fun syncNow(reason: SyncReason): SyncSummary = runMutex.withLock {
        val state = serverReachability.check()
        if (state == ServerState.NOT_CONFIGURED || state == ServerState.DISABLED || state == ServerState.DEVICE_OFFLINE) {
            return publish(SyncSummary.Skipped(state))
        }
        val now = clock.millis()
        if (reason.debounced) {
            val last = preferencesRepository.getLastRoomSyncAt()
            if (last != null && now - last < DEBOUNCE_MS) return SyncSummary.Skipped(state)
        }
        val profile = userRepository.getProfile() ?: return publish(SyncSummary.Skipped(state))
        val selfCode = profile.userCode
        roomApi.bindCallerIdentity(selfCode)

        val rows = challengeRepository.listSyncableChallenges()

        // Step 3.
        val liveCodes = rows.mapNotNull { it.roomCode }.toSet()
        for (code in preferencesRepository.getPendingRoomLeaves()) {
            if (code in liveCodes) {
                preferencesRepository.removePendingRoomLeave(code)
                continue
            }
            when (val left = roomApi.leaveRoom(code)) {
                is RoomResult.Ok, RoomResult.NotFound -> {
                    serverReachability.report(true)
                    preferencesRepository.removePendingRoomLeave(code)
                }
                RoomResult.Unreachable -> return unreachable()
                else -> {
                    // Refused, and a retry would be refused the same way; the room keeps a
                    // snapshot that will age out under its retention rule.
                    serverReachability.report(true)
                    Log.w(TAG, "Leaving room $code refused with $left; dropping the pending leave")
                    preferencesRepository.removePendingRoomLeave(code)
                }
            }
        }

        // Step 4.
        val today = LocalDate.now(clock)
        var synced = 0
        for (row in rows) {
            val code = row.roomCode ?: continue
            val generation = row.syncGeneration
            val colorIndex = row.roomState?.room?.participants?.firstOrNull { it.userCode == selfCode }?.colorIndex ?: 0
            val snapshot = row.toParticipantSnapshot(profile.username, colorIndex, today, clock.zone).copy(userCode = selfCode)
            val claim = row.claimFor(selfCode, now)
            val uploading = row.isSyncPending() || claim != null

            val reply = if (uploading) roomApi.putSnapshot(code, snapshot, claim) else roomApi.getRoom(code)
            when (reply) {
                is RoomResult.Ok -> {
                    serverReachability.report(true)
                    val merge = challengeRepository.applyRoomState(row.id, reply.value)
                    if (uploading) challengeRepository.confirmSynced(row.id, generation)
                    val followUp = merge?.claim
                    if (followUp != null) {
                        // The merge ended the challenge (P2, P4): tell the server now, not at the
                        // next sync, and take whatever outcome stands in the reply.
                        when (val claimed = roomApi.putSnapshot(code, snapshot, followUp)) {
                            is RoomResult.Ok -> {
                                serverReachability.report(true)
                                challengeRepository.applyRoomState(row.id, claimed.value)
                            }
                            RoomResult.Unreachable -> return unreachable()
                            RoomResult.NotFound, RoomResult.Unauthorized -> roomGone(row, claimed)
                            else -> serverReachability.report(true)
                        }
                    }
                    synced++
                }
                RoomResult.Unreachable -> return unreachable()
                RoomResult.NotFound, RoomResult.Unauthorized -> roomGone(row, reply)
                // Only a join can be refused this way, and the pilot is already in the room; the
                // server answered, so the row is left for the next sync.
                RoomResult.RoomClosed, RoomResult.RaceLocked, RoomResult.RoomFull -> serverReachability.report(true)
            }
        }

        // Step 5.
        preferencesRepository.setLastRoomSyncAt(now)
        publish(SyncSummary.Synced(synced, now))
    }

    private fun publish(summary: SyncSummary): SyncSummary {
        _lastSummary.value = summary
        return summary
    }

    private fun unreachable(): SyncSummary {
        serverReachability.report(false)
        return publish(SyncSummary.Unreachable)
    }

    /** The server no longer knows this row's room (P11), or bound its code to someone else
     *  (P12): the row keeps running locally and is not synced again. */
    private suspend fun roomGone(row: Challenge, reply: RoomResult<*>) {
        serverReachability.report(true)
        if (reply == RoomResult.Unauthorized) {
            Log.w(TAG, "Room ${row.roomCode} refused the pilot code; another pilot bound it first")
        }
        challengeRepository.markRoomGone(row.id)
    }

    /**
     * The claim a terminal row still owes the server: none while the room already has an
     * outcome (the server decided, or this claim was already accepted). A completion claims the
     * moment it completed, so the server orders it with everyone else's; a failure names who
     * broke the streak, this pilot if the row does not say.
     */
    private fun Challenge.claimFor(selfCode: String, now: Long): OutcomeClaim? {
        if (status == ChallengeStatus.ACTIVE || roomState?.room?.outcome != null) return null
        return when (status) {
            ChallengeStatus.COMPLETED -> OutcomeClaim.Completed(completedAt ?: now)
            ChallengeStatus.FAILED -> OutcomeClaim.Failed((sharedOutcome as? SharedOutcome.Failed)?.brokenByUserCode ?: selfCode)
            ChallengeStatus.ACTIVE -> null
        }
    }

    companion object {
        private const val TAG = "SharedChallengeSyncer"

        /** How long a foreground or screen-open trigger waits after the last completed sync. */
        const val DEBOUNCE_MS = 60_000L
    }
}
