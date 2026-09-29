package com.silas270.blocktime.data.model

import java.security.SecureRandom

/**
 * The domain shapes of a shared challenge room, as docs/shared-challenges.md "Data model" lists
 * them. These are what the app reasons about; the flat JSON shapes that travel over the wire and
 * sit in the `room_state` cache live in `data/network/room/RoomDto.kt` and are mapped to and from
 * these in code, with explicit defaults for anything a peer's app version did not send.
 *
 * Every field has a default where the design allows one, so a snapshot for one challenge type
 * can leave the other types' fields alone.
 */

/** A room holds at most this many pilots; the seventh join is refused with `RoomFull`. */
const val MAX_ROOM_PARTICIPANTS = 6

/** A room code is six characters, like a pilot code. */
const val ROOM_CODE_LENGTH = 6

/**
 * The one alphabet for pilot codes, room codes and the room secret: upper case without the
 * look-alikes I, O, 0 and 1, so a code read out loud or typed from a screenshot is not
 * ambiguous. [UserProfile.generateUserCode] draws from it too.
 */
internal const val ROOM_CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"

/**
 * [length] characters drawn from [ROOM_CODE_ALPHABET] with a [SecureRandom]. Used for pilot
 * codes, room codes and the room secret, so all three are unguessable to the same degree.
 */
internal fun generateCode(length: Int, random: java.util.Random = SecureRandom()): String {
    val code = StringBuilder(length)
    repeat(length) {
        code.append(ROOM_CODE_ALPHABET[random.nextInt(ROOM_CODE_ALPHABET.length)])
    }
    return code.toString()
}

/**
 * One pilot's own state in a room. **Each pilot only ever writes their own snapshot**, which is
 * why a room never has a conflict: team progress is a union, a sum or a minimum over these.
 *
 * Which fields carry meaning depends on the room's [RoomDefinition.type]; the others keep their
 * defaults. [updatedAt] is server time, stamped on every write, so a stale snapshot can be told
 * from a fresh one without trusting the peer's clock.
 */
data class ParticipantSnapshot(
    val userCode: String,
    val username: String,
    /** Position in join order, the index into the participant palette. The creator is 0. */
    val colorIndex: Int = 0,
    /** Set by leaving. The snapshot stays so pooled contributions stay; streak and race skip it. */
    val left: Boolean = false,

    // Route
    val positionIata: String? = null,
    val legIndex: Int = 0,
    val routeProgress: Float = 0f,

    // Set
    val visitedMembers: Set<String> = emptySet(),

    // Distance
    val distanceKm: Double = 0.0,

    // Streak
    val streakDays: Int = 0,
    /** ISO-8601 local date of the last credited day, as on the challenge row. */
    val lastFlownDay: String? = null,
    /** Computed by the owner with their local rule. Defaults to alive: a snapshot that does not
     *  report a broken streak is not evidence of one; the merge falls back to [lastFlownDay]. */
    val streakAlive: Boolean = true,

    /** Server time of the last write, epoch millis. 0 until the server has seen it. */
    val updatedAt: Long = 0L,
)

/**
 * What the room is about, fixed when it is created. A joining pilot builds their local
 * `challenges` row from this, so it carries everything `startCuratedChallenge` would need and
 * nothing that changes afterwards.
 */
data class RoomDefinition(
    val type: ChallengeType,
    val source: ChallengeSource,
    val name: String,
    val description: String = "",
    val iconName: String? = null,
    val catalogId: String? = null,
    val predefinedRouteId: String? = null,
    val originIata: String? = null,
    val destIata: String? = null,
    val setCatalogId: String? = null,
    val setMemberKind: SetMemberKind? = null,
    val targetDistanceKm: Double? = null,
    val targetDays: Int? = null,
)

/**
 * How a room ended. **One outcome ends the challenge for everyone at the same moment**; the
 * server keeps the first claim and ignores the rest.
 *
 * [Completed.bySelf] and [Failed.bySelf] are stamped by the merge, which knows the pilot's own
 * code. The server always sends false. Once stamped, everything that needs "was this me" reads
 * it from the row alone.
 */
sealed interface SharedOutcome {
    val at: Long
    val bySelf: Boolean

    data class Completed(
        val byUserCode: String,
        override val bySelf: Boolean = false,
        override val at: Long = 0L,
        /** Race placements, winner first. Empty for the pooled types. */
        val placements: List<String> = emptyList(),
    ) : SharedOutcome

    data class Failed(
        val brokenByUserCode: String,
        override val bySelf: Boolean = false,
        override val at: Long = 0L,
    ) : SharedOutcome
}

/** The current state of a room as the server holds it: the definition plus one snapshot per pilot. */
data class RoomState(
    val code: String,
    val definition: RoomDefinition,
    val createdAt: Long = 0L,
    val participants: List<ParticipantSnapshot> = emptyList(),
    val outcome: SharedOutcome? = null,
    /** Bumped by the server on every write; a cheap "did anything change" for the client. */
    val version: Long = 0L,
    /** Client-side only: the server no longer knows this room (not found or unauthorised), so the
     *  row keeps running locally and is not synced again. Never sent. */
    val roomGone: Boolean = false,
)

/**
 * What the `room_state` column holds: the last room state seen, together with the pilot's own
 * code, so `crew()`, team progress and the choice of presentation are pure functions of the row.
 */
data class RoomStateCache(
    val selfCode: String,
    val room: RoomState,
)

/** What a pilot sends along with a snapshot when their local row went terminal. */
sealed interface OutcomeClaim {
    data class Completed(val at: Long) : OutcomeClaim
    data class Failed(val brokenBy: String) : OutcomeClaim
}
