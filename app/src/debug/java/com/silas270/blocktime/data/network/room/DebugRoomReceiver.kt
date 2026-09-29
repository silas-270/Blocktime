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
 * Every op applies to every room the fake holds. `op` is a string extra. The `-p` is required:
 * since Android 8 an implicit broadcast never reaches a manifest-declared receiver, so without it
 * the broadcast completes and nothing happens.
 *
 * ```
 * adb shell am broadcast -a com.silas270.blocktime.DEBUG_ROOM -p com.silas270.blocktime --es op join      # the bot joins
 * adb shell am broadcast -a com.silas270.blocktime.DEBUG_ROOM -p com.silas270.blocktime --es op advance   # one step per type
 * adb shell am broadcast -a com.silas270.blocktime.DEBUG_ROOM -p com.silas270.blocktime --es op break     # the bot's streak dies
 * adb shell am broadcast -a com.silas270.blocktime.DEBUG_ROOM -p com.silas270.blocktime --es op win       # the bot wins a race
 * ```
 *
 * `advance` moves the bot one step by the room's type: 500 km for a distance pool, the next
 * unvisited member of the definition for a set, one more day (flown today) for a streak, and one
 * leg (a third of the route) for a race, which the bot claims as completed once it reaches the
 * end. `break` and `win` are the two outcomes a merge can only receive from someone else.
 *
 * `present` drives one presentation (docs/shared-challenges.md "Presentation") on the newest room
 * that has no outcome yet and whose type fits the mode, so the four shared beats can be checked one
 * after the other. It takes a second extra, `mode`:
 *
 * ```
 * adb shell am broadcast -a com.silas270.blocktime.DEBUG_ROOM -p com.silas270.blocktime --es op present --es mode team
 * adb shell am broadcast -a com.silas270.blocktime.DEBUG_ROOM -p com.silas270.blocktime --es op present --es mode won
 * adb shell am broadcast -a com.silas270.blocktime.DEBUG_ROOM -p com.silas270.blocktime --es op present --es mode placed
 * adb shell am broadcast -a com.silas270.blocktime.DEBUG_ROOM -p com.silas270.blocktime --es op present --es mode broken
 * ```
 *
 * - `team` (a distance, set or streak room): the bot joins if it has not, advances until its own
 *   contribution alone fills the pool, and claims the completion, as a second phone whose merge
 *   saw the pool fill would. The next sync completes the row through the server's outcome (merge
 *   rule 4) and Challenges plays the team celebration with the "CREW ×2" badge; the log entry
 *   wears the same stamp.
 * - `won` (a route room): the bot only joins, so the crew is two. Fly the route yourself; your
 *   own arrival wins the race (L2) and Challenges plays "YOU WON THE RACE".
 * - `placed` (a route room): the bot joins and claims the finish, the same as `join` then `win`.
 *   The next sync places you second: "BOT PILOT WON · YOU FINISHED 2ND", half the confetti,
 *   "GG", and the log entry stamped "2ND".
 * - `broken` (a streak room): the bot joins and reports its streak dead, the same as `join` then
 *   `break`. The next sync fails the row and Challenges plays the shatter: "STREAK BROKEN",
 *   "Bot Pilot missed a day", "DAMN", then the row is deleted and the slot frees. For "You missed
 *   a day" instead, share a streak, let the bot join, and skip a day yourself.
 *
 * After each broadcast, bring the app to the foreground or open Challenges to sync.
 */
class DebugRoomReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val fake = RoomApiProvider.fake
        if (fake == null) {
            Log.w(TAG, "No fake room api: this build has a ROOM_SERVER_URL, so the bot cannot play")
            return
        }
        // A broadcast can start the process without the Activity, so the fake would still be
        // empty here; attaching loads the rooms from their file, and is a no-op once attached.
        RoomApiProvider.attach(context.filesDir)
        val op = intent.getStringExtra("op") ?: "advance"
        val codes = fake.roomCodes()
        if (codes.isEmpty()) {
            Log.w(TAG, "The fake holds no rooms; share a challenge from the info modal first")
            return
        }
        val mode = intent.getStringExtra("mode")
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (op == "present") {
                    // One presentation on one room: the newest still open one the mode fits, so
                    // the beats can run one after the other while earlier rooms stay in the fake.
                    val code = presentTarget(fake, codes, mode)
                    if (code == null) {
                        Log.w(TAG, "No open room fits mode=$mode; share a fresh challenge of the right type first")
                    } else {
                        present(fake, code, mode)
                        Log.i(TAG, "op=present mode=$mode applied to $code; now bring the app to the foreground or open Challenges to sync")
                    }
                } else {
                    for (code in codes) {
                        when (op) {
                            "join" -> join(fake, code)
                            "advance" -> advance(fake, code)
                            "break" -> breakStreak(fake, code)
                            "win" -> win(fake, code)
                            else -> Log.w(TAG, "Unknown op '$op' (join, advance, break, win, present)")
                        }
                    }
                    Log.i(TAG, "op=$op applied to ${codes.size} room(s); now bring the app to the foreground or open Challenges to sync")
                }
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

    /**
     * The room `present` acts on: the newest one without an outcome whose type the mode can
     * present (a pool for `team`, a race for `won` and `placed`, a streak for `broken`). The
     * fake keeps rooms in creation order, so the last match is the newest.
     */
    private fun presentTarget(fake: FakeRoomApi, codes: List<String>, mode: String?): String? {
        val fits: (ChallengeType) -> Boolean = when (mode) {
            "team" -> { type -> type != ChallengeType.ROUTE }
            "won", "placed" -> { type -> type == ChallengeType.ROUTE }
            "broken" -> { type -> type == ChallengeType.STREAK }
            else -> { _ -> true }
        }
        return codes.lastOrNull { code ->
            val room = fake.room(code)
            room != null && room.outcome == null && fits(room.definition.type)
        }
    }

    /**
     * The recipe for one presentation, see the class doc. Every mode starts with the bot joining,
     * because none of the four can happen to a pilot who is alone in the room.
     */
    private suspend fun present(fake: FakeRoomApi, code: String, mode: String?) {
        join(fake, code)
        val room = fake.room(code) ?: return
        val type = room.definition.type
        when (mode) {
            "team" -> {
                if (type == ChallengeType.ROUTE) {
                    Log.w(TAG, "$code: a race has no team completion; use mode=won or mode=placed")
                    return
                }
                fillPool(fake, code)
            }
            "won" -> {
                if (type != ChallengeType.ROUTE) {
                    Log.w(TAG, "$code: not a race; share a route to win one")
                    return
                }
                Log.i(TAG, "$code: the bot is in; fly the route yourself, your arrival wins the race")
            }
            "placed" -> win(fake, code)
            "broken" -> breakStreak(fake, code)
            else -> Log.w(TAG, "Unknown mode '$mode' (team, won, placed, broken)")
        }
    }

    /**
     * The bot advances until its own contribution alone reaches the pool's target, then claims
     * the completion. Bounded, so a room whose target the steps cannot reach (a set with an
     * unknown definition) logs instead of looping.
     */
    private suspend fun fillPool(fake: FakeRoomApi, code: String) {
        repeat(MAX_FILL_STEPS) {
            val room = fake.room(code) ?: return
            val bot = botIn(room) ?: return
            val definition = room.definition
            val filled = when (definition.type) {
                ChallengeType.DISTANCE -> bot.distanceKm >= (definition.targetDistanceKm ?: return)
                ChallengeType.SET_COMPLETION -> {
                    val members = definition.setCatalogId
                        ?.let { CuratedChallengeSets.find(it) }
                        ?.memberItems
                        ?.map { it.id }
                        ?: return
                    bot.visitedMembers.containsAll(members)
                }
                ChallengeType.STREAK -> bot.streakDays >= (definition.targetDays ?: return)
                ChallengeType.ROUTE -> return
            }
            if (filled) {
                claimCompleted(fake, code, bot)
                Log.i(TAG, "$code: the bot filled the pool and claimed the completion")
                return
            }
            advance(fake, code)
        }
        Log.w(TAG, "$code: the pool did not fill in $MAX_FILL_STEPS steps")
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
        const val MAX_FILL_STEPS = 200
    }
}
