package com.silas270.blocktime.data.network.room

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.silas270.blocktime.data.model.ChallengeType
import com.silas270.blocktime.data.model.CuratedChallengeSets
import com.silas270.blocktime.data.model.OutcomeClaim
import com.silas270.blocktime.data.model.ParticipantSnapshot
import com.silas270.blocktime.data.model.PredefinedRouteCatalog
import com.silas270.blocktime.data.model.RoomState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * The bot pilot for walking the sharing flow on a phone before a backend exists
 * (docs/shared-challenges.md). It only works against [RoomApiProvider.fake], the in-memory
 * [FakeRoomApi] a debug build uses when it has no `ROOM_SERVER_URL`, and it mutates that fake
 * directly, exactly as a second phone's uploads would. Nothing here syncs: the app pulls the
 * change at its next sync moment, so after each broadcast bring the app to the foreground or
 * open Challenges, and the real merge, presentation and log paths run on the result.
 *
 * Every op applies to every room the fake holds. `op` is a string extra:
 *
 * ```
 * adb shell am broadcast -a com.silas270.blocktime.DEBUG_ROOM --es op join      # the bot joins
 * adb shell am broadcast -a com.silas270.blocktime.DEBUG_ROOM --es op advance   # one step per type
 * adb shell am broadcast -a com.silas270.blocktime.DEBUG_ROOM --es op break     # the bot's streak dies
 * adb shell am broadcast -a com.silas270.blocktime.DEBUG_ROOM --es op win       # the bot wins a race
 * ```
 *
 * `advance` moves the bot one step by the room's type: 500 km for a distance pool, the next
 * unvisited member of the definition for a set, one more day (flown today) for a streak, and one
 * leg (a third of the route) for a race, which the bot claims as completed once it reaches the
 * end. `break` and `win` are the two outcomes a merge can only receive from someone else.
 */
class DebugRoomReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val fake = RoomApiProvider.fake
        if (fake == null) {
            Log.w(TAG, "No fake room api: this build has a ROOM_SERVER_URL, so the bot cannot play")
            return
        }
        val op = intent.getStringExtra("op") ?: "advance"
        val codes = fake.roomCodes()
        if (codes.isEmpty()) {
            Log.w(TAG, "The fake holds no rooms; share a challenge from the info modal first")
            return
        }
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                for (code in codes) {
                    when (op) {
                        "join" -> join(fake, code)
                        "advance" -> advance(fake, code)
                        "break" -> breakStreak(fake, code)
                        "win" -> win(fake, code)
                        else -> Log.w(TAG, "Unknown op '$op' (join, advance, break, win)")
                    }
                }
                Log.i(TAG, "op=$op applied to ${codes.size} room(s); now bring the app to the foreground or open Challenges to sync")
            } catch (e: Exception) {
                Log.e(TAG, "op=$op failed", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun join(fake: FakeRoomApi, code: String) {
        val room = fake.room(code) ?: return
        if (room.participants.any { it.userCode == BOT_CODE }) {
            Log.i(TAG, "$code: the bot is already in")
            return
        }
        fake.addSimulatedParticipant(code, BOT_CODE, BOT_NAME)
        Log.i(TAG, "$code: the bot joined")
    }

    private suspend fun advance(fake: FakeRoomApi, code: String) {
        val room = fake.room(code) ?: return
        val bot = botIn(room) ?: return
        when (room.definition.type) {
            ChallengeType.DISTANCE -> fake.advanceSimulated(code, BOT_CODE) { it.copy(distanceKm = it.distanceKm + 500.0) }
            ChallengeType.SET_COMPLETION -> {
                val next = room.definition.setCatalogId
                    ?.let { CuratedChallengeSets.find(it) }
                    ?.memberItems
                    ?.map { it.id }
                    ?.firstOrNull { it !in bot.visitedMembers }
                if (next == null) {
                    Log.i(TAG, "$code: the bot has visited every member")
                    return
                }
                fake.advanceSimulated(code, BOT_CODE) { it.copy(visitedMembers = it.visitedMembers + next) }
            }
            ChallengeType.STREAK -> fake.advanceSimulated(code, BOT_CODE) {
                it.copy(streakDays = it.streakDays + 1, lastFlownDay = LocalDate.now().toString(), streakAlive = true)
            }
            ChallengeType.ROUTE -> {
                val route = room.definition.predefinedRouteId?.let { PredefinedRouteCatalog.find(it) }
                val legIndex = bot.legIndex + 1
                val progress = (bot.routeProgress + 0.34f).coerceAtMost(1f)
                val arrived = progress >= 1f
                val position = when {
                    route != null -> route.waypoints.getOrNull(legIndex.coerceAtMost(route.legCount))
                    arrived -> room.definition.destIata
                    else -> bot.positionIata ?: room.definition.originIata
                }
                val next = bot.copy(legIndex = legIndex, routeProgress = progress, positionIata = position)
                if (arrived) {
                    claimCompleted(fake, code, next)
                } else {
                    fake.advanceSimulated(code, BOT_CODE) { next }
                }
            }
        }
        Log.i(TAG, "$code: the bot advanced (${room.definition.type})")
    }

    private fun breakStreak(fake: FakeRoomApi, code: String) {
        val room = fake.room(code) ?: return
        if (botIn(room) == null) return
        if (room.definition.type != ChallengeType.STREAK) {
            Log.i(TAG, "$code: not a streak, nothing to break")
            return
        }
        fake.advanceSimulated(code, BOT_CODE) { it.copy(streakAlive = false) }
        Log.i(TAG, "$code: the bot's streak is broken")
    }

    private suspend fun win(fake: FakeRoomApi, code: String) {
        val room = fake.room(code) ?: return
        val bot = botIn(room) ?: return
        if (room.definition.type != ChallengeType.ROUTE) {
            Log.i(TAG, "$code: not a race, nothing to win")
            return
        }
        val route = room.definition.predefinedRouteId?.let { PredefinedRouteCatalog.find(it) }
        val finished = bot.copy(
            legIndex = route?.legCount ?: (bot.legIndex + 1),
            routeProgress = 1f,
            positionIata = route?.waypoints?.last() ?: room.definition.destIata
        )
        claimCompleted(fake, code, finished)
        Log.i(TAG, "$code: the bot won the race")
    }

    /** A completed claim goes through [FakeRoomApi.putSnapshot], the way a real second phone
     *  would send it, so the fake resolves the placements itself. The fake checks the caller
     *  against the snapshot, so the caller is the bot for exactly this one call. */
    private suspend fun claimCompleted(fake: FakeRoomApi, code: String, snapshot: ParticipantSnapshot) {
        val previous = fake.callerUserCode
        fake.callerUserCode = BOT_CODE
        try {
            val result = fake.putSnapshot(code, snapshot.copy(userCode = BOT_CODE), OutcomeClaim.Completed(at = System.currentTimeMillis()))
            Log.i(TAG, "$code: claim answered $result")
        } finally {
            fake.callerUserCode = previous
        }
    }

    private fun botIn(room: RoomState): ParticipantSnapshot? {
        val bot = room.participants.firstOrNull { it.userCode == BOT_CODE }
        if (bot == null) Log.w(TAG, "${room.code}: the bot is not in this room; run op=join first")
        return bot
    }

    private companion object {
        const val TAG = "DebugRoomReceiver"
        const val BOT_CODE = "BOT001"
        const val BOT_NAME = "Bot Pilot"
    }
}
