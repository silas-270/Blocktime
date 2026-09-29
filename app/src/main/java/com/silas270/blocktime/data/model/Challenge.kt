package com.silas270.blocktime.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One row per challenge instance (curated or custom, any of the three types) - a single store,
 * not per-type tables, per docs/challenges.md#persistence--route-scoping. Which fields
 * are meaningful depends on [type]:
 *
 * - ROUTE: [originIata] (fixed - the progress formula's denominator), [destIata] (the win
 *   condition), [positionIata] (the pointer - starts equal to [originIata], moves on landing;
 *   this is what a CHALLENGE-tagged session's FlightSearch/CheckIn/InFlight read as origin
 *   instead of `currentAirport`), [routeProgressFraction] (cached, recomputed on every move by
 *   [ChallengeProgress.routeProgress]).
 * - SET_COMPLETION: [setCatalogId]/[setMemberKind]/[setTotalMembers] identify which curated
 *   [ChallengeSetDefinition] this instance is tracking and how to test membership;
 *   [visitedSetMembers] is *this instance's own* tracked set - starts empty when the challenge is
 *   started and is never seeded from Story Mode's visited-set (challenges.md#isolation - this is
 *   the detail that makes a challenge repeatable, unlike an achievement).
 * - DISTANCE: [targetDistanceKm] / [cumulativeDistanceKm].
 * - STREAK: [targetDays] (a small number - this is a final-push mechanic, not a habit tracker),
 *   with [streakDays]/[lastFlownDay] recording the run so far. Unlike every other type this one
 *   can go *down*, and can do so without any flight happening - see [Challenge.currentStreak].
 *
 * [source] and [status] apply to every type. The 3-active cap and "abandon deletes the row
 * entirely" live in `ChallengeRepository`, not here - this class is pure storage shape.
 *
 * Shared (docs/shared-challenges.md "Data model"), orthogonal to type and source: [roomCode]
 * non-null means the row is a participant in a room, [roomState] caches the last room state
 * seen, [syncGeneration]/[syncedGeneration] say whether the pilot's own data still needs
 * uploading, and [sharedOutcome] records how the room ended. Team progress is never stored; it
 * is derived from this row plus the cache (`SharedProgress.kt`).
 */
@Entity(
    tableName = "challenges",
    foreignKeys = [
        ForeignKey(
            entity = UserProfile::class,
            parentColumns = ["id"],
            childColumns = ["user_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["user_id"]),
        Index(value = ["user_id", "status"]),
        // One row per room per pilot, so a finished room cannot be joined twice (J6b). SQLite
        // treats NULLs as distinct in a unique index, so every unshared row keeps its NULL
        // room_code without colliding.
        Index(value = ["user_id", "room_code"], unique = true)
    ]
)
data class Challenge(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    @ColumnInfo(name = "user_id") val userId: Int,
    val type: ChallengeType,
    val source: ChallengeSource,
    val status: ChallengeStatus = ChallengeStatus.ACTIVE,
    val name: String,
    val description: String = "",

    // ── Route only ──────────────────────────────────────────────────────────────────────
    @ColumnInfo(name = "origin_iata") val originIata: String? = null,
    @ColumnInfo(name = "dest_iata") val destIata: String? = null,
    @ColumnInfo(name = "position_iata") val positionIata: String? = null,
    @ColumnInfo(name = "route_progress_fraction") val routeProgressFraction: Float = 0f,

    // ── Route only: the predefined-itinerary submode ────────────────────────────────────
    // Non-null means this Route challenge follows a fixed authored sequence of airports
    // ([PredefinedRoute]) rather than any path between two endpoints, and is scored by legs flown
    // rather than by the straight-line proxy above. That is what makes a circuit ("around the
    // world", start == end) expressible at all - see PredefinedRoute's own doc.
    //
    // The endpoint fields above stay populated for these rows too: originIata/destIata are the
    // first/last waypoint and positionIata is the origin of the *next* leg, so every existing
    // reader (challengeSubtitle, the Hub focus card, FlightSearchViewModel's challenge origin)
    // keeps working without knowing this submode exists. Only progress and advancement branch.
    @ColumnInfo(name = "predefined_route_id") val predefinedRouteId: String? = null,
    // How many legs are done - the pointer for this submode, the way positionIata is the pointer
    // for a free-form route. Same `defaultValue` reasoning as streakDays below: SQLite cannot ADD
    // a NOT NULL column without one, and Room validates the declared default against the migrated
    // schema.
    @ColumnInfo(name = "leg_index", defaultValue = "0") val legIndex: Int = 0,

    // ── Set-completion only ─────────────────────────────────────────────────────────────
    @ColumnInfo(name = "set_catalog_id") val setCatalogId: String? = null,
    @ColumnInfo(name = "set_member_kind") val setMemberKind: SetMemberKind? = null,
    @ColumnInfo(name = "set_total_members") val setTotalMembers: Int = 0,
    @ColumnInfo(name = "set_visited_members") val visitedSetMembers: Set<String> = emptySet(),

    // ── Distance only ───────────────────────────────────────────────────────────────────
    @ColumnInfo(name = "target_distance_km") val targetDistanceKm: Double? = null,
    @ColumnInfo(name = "cumulative_distance_km") val cumulativeDistanceKm: Double = 0.0,

    // ── Streak only ─────────────────────────────────────────────────────────────────────
    // These two record what happened, not what is true now: [lastFlownDay] is the last local
    // calendar day credited, and [streakDays] is how long the run was *as of that day*. Whether
    // the run is still alive depends on today's date, which no write can know - see
    // [Challenge.currentStreak]. Storing a live "is the streak alive" flag would need a writer
    // that runs at midnight, and there isn't one.
    @ColumnInfo(name = "target_days") val targetDays: Int? = null,
    // defaultValue is not decoration: SQLite cannot ADD a NOT NULL column without one, so
    // MIGRATION_6_7 has to supply `DEFAULT 0` - and Room compares declared defaults when it
    // validates a migrated schema, so the entity has to declare the same one or validation fails
    // on a migration that is actually correct. Same reason FlightLog.mode carries one.
    @ColumnInfo(name = "streak_days", defaultValue = "0") val streakDays: Int = 0,
    /** ISO-8601 local date (`2026-08-30`), or null before the first credited flight. */
    @ColumnInfo(name = "last_flown_day") val lastFlownDay: String? = null,

    // ── Route only: this challenge's own in-progress (paused) flight, independent of Story
    // Mode's and every other challenge's - see PreferencesRepository.getPausedFlight for the
    // Story/Free equivalent. One field, not several: a PausedFlight already carries its own
    // originIata (== this challenge's positionIata at the time it was booked), elapsed time, and
    // camera framing - see PausedFlight's own doc for why that's one model instead of pieces
    // scattered across separately-keyed stores. Stored via a Room TypeConverter (Converters.kt).
    @ColumnInfo(name = "paused_flight") val pausedFlight: PausedFlight? = null,

    @ColumnInfo(name = "icon_name") val iconName: String? = null,

    @ColumnInfo(name = "started_at") val startedAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "completed_at") val completedAt: Long? = null,

    // Whether the completion-presentation animation has been shown to the player yet. A
    // COMPLETED challenge with this still false keeps occupying its slot (and the cap) and is
    // excluded from the completed-log query - see docs/challenges.md and docs/state.md. Same
    // defaultValue reasoning as legIndex/streakDays above.
    @ColumnInfo(name = "celebrated", defaultValue = "0") val celebrated: Boolean = false,

    // ── Shared (docs/shared-challenges.md "Data model") ─────────────────────────────────
    // Non-null means shared: this row is one participant's view of a room. Stays on terminal
    // rows so a finished room cannot be joined twice, which the unique (user_id, room_code)
    // index above enforces.
    @ColumnInfo(name = "room_code") val roomCode: String? = null,
    // The last room state seen, with the pilot's own code (see RoomStateCache). A CACHE in
    // docs/state.md's sense: it may be thrown away without changing anything the pilot owns,
    // and the next sync rebuilds it. Every "the others" value on screen comes from here; the
    // pilot's own values never do (SharedProgress.kt). Stored as JSON via a TypeConverter.
    @ColumnInfo(name = "room_state") val roomState: RoomStateCache? = null,
    // Incremented by every local change to the pilot's own data on this row. The row needs
    // uploading iff it differs from syncedGeneration. Same defaultValue reasoning as
    // legIndex/streakDays above: MIGRATION_10_11 adds a NOT NULL column and Room validates the
    // declared default against the migrated schema.
    @ColumnInfo(name = "sync_generation", defaultValue = "0") val syncGeneration: Long = 0L,
    // The generation the server last confirmed. Two counters rather than a "needs upload" flag
    // because a landing can be credited while an upload is in flight: the upload confirms the
    // generation it sent, that confirmation is a no-op if the row has moved on, and the newer
    // state goes with the next sync. Same defaultValue reasoning as syncGeneration.
    @ColumnInfo(name = "synced_generation", defaultValue = "0") val syncedGeneration: Long = 0L,
    // How the room ended: who completed it, whether that was this pilot, race placements, or
    // who broke the streak. A FACT, not a cache: it is what the completed log and the
    // presentation read, and it is kept after the room itself is gone. Stored as JSON via a
    // TypeConverter, with `bySelf` written explicitly (RoomJson.encodeOutcome).
    @ColumnInfo(name = "shared_outcome") val sharedOutcome: SharedOutcome? = null
)
