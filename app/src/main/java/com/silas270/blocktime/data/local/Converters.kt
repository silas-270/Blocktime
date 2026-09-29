package com.silas270.blocktime.data.local

import androidx.room.TypeConverter
import com.silas270.blocktime.data.model.ChallengeSource
import com.silas270.blocktime.data.model.ChallengeStatus
import com.silas270.blocktime.data.model.ChallengeType
import com.silas270.blocktime.data.model.FlightMode
import com.silas270.blocktime.data.model.PausedFlight
import com.silas270.blocktime.data.model.RoomStateCache
import com.silas270.blocktime.data.model.SetMemberKind
import com.silas270.blocktime.data.model.SharedOutcome
import com.silas270.blocktime.data.network.room.RoomJson

/**
 * Room [TypeConverter]s for enum (and one Set<String>) columns: [FlightMode] (`FlightLog.mode`)
 * plus the `Challenge` entity's [ChallengeType]/[ChallengeSource]/[ChallengeStatus]/
 * [SetMemberKind] and its `visitedSetMembers` field. Enums are stored as their plain
 * enum-constant name - the simplest representation for a small, stable set of values.
 *
 * The two shared-challenge columns (`room_state`, `shared_outcome`) are JSON through [RoomJson],
 * the same flat DTOs the wire uses, so there is one codec for a room state, not two.
 */
class Converters {
    @TypeConverter
    fun fromFlightMode(mode: FlightMode): String = mode.name

    @TypeConverter
    fun toFlightMode(value: String): FlightMode =
        runCatching { FlightMode.valueOf(value) }.getOrDefault(FlightMode.STORY)

    @TypeConverter
    fun fromChallengeType(type: ChallengeType): String = type.name

    @TypeConverter
    fun toChallengeType(value: String): ChallengeType =
        runCatching { ChallengeType.valueOf(value) }.getOrDefault(ChallengeType.DISTANCE)

    @TypeConverter
    fun fromChallengeSource(source: ChallengeSource): String = source.name

    @TypeConverter
    fun toChallengeSource(value: String): ChallengeSource =
        runCatching { ChallengeSource.valueOf(value) }.getOrDefault(ChallengeSource.CUSTOM)

    @TypeConverter
    fun fromChallengeStatus(status: ChallengeStatus): String = status.name

    /** The ACTIVE fallback is a guard against a corrupted value, not a downgrade path: an
     *  older app never opens a newer schema (AppDatabase has no destructive fallback on
     *  downgrade), so a `FAILED` row is never read by a build that does not know the name. */
    @TypeConverter
    fun toChallengeStatus(value: String): ChallengeStatus =
        runCatching { ChallengeStatus.valueOf(value) }.getOrDefault(ChallengeStatus.ACTIVE)

    @TypeConverter
    fun fromSetMemberKind(kind: SetMemberKind?): String? = kind?.name

    @TypeConverter
    fun toSetMemberKind(value: String?): SetMemberKind? =
        value?.let { runCatching { SetMemberKind.valueOf(it) }.getOrNull() }

    /** No member value in play (continent/country/IATA codes) can ever contain a comma, so a
     *  plain join/split is safe and keeps this consistent with the rest of this file's
     *  simplest-representation approach. */
    @TypeConverter
    fun fromStringSet(value: Set<String>): String = value.joinToString(",")

    @TypeConverter
    fun toStringSet(value: String): Set<String> =
        if (value.isBlank()) emptySet() else value.split(",").toSet()

    /** `Challenge.pausedFlight` - the same pipe-delimited format
     *  [PreferencesRepository][com.silas270.blocktime.data.repository.PreferencesRepository]
     *  uses for the Story/Free slot, so there's one (de)serialization to trust, not two. */
    @TypeConverter
    fun fromPausedFlight(value: PausedFlight?): String? = value?.serialize()

    @TypeConverter
    fun toPausedFlight(value: String?): PausedFlight? = value?.let { PausedFlight.parse(it) }

    /** `Challenge.roomState` - the last room state seen, as the same JSON the server sends.
     *  A decode failure reads as null rather than throwing, because the column is a cache
     *  (docs/shared-challenges.md "Data model"): dropping it changes nothing the pilot owns, and
     *  the next sync rebuilds it. */
    @TypeConverter
    fun fromRoomStateCache(value: RoomStateCache?): String? = value?.let { RoomJson.encodeCache(it) }

    @TypeConverter
    fun toRoomStateCache(value: String?): RoomStateCache? = value?.let { RoomJson.decodeCache(it) }

    /** `Challenge.sharedOutcome` - how the room ended, as the wire's outcome JSON plus the
     *  `bySelf` stamp the merge added. Unlike the cache this is a fact, so a decode failure
     *  reading as null is a loss; it is accepted over a crash on read, and the shape is ours to
     *  keep stable. */
    @TypeConverter
    fun fromSharedOutcome(value: SharedOutcome?): String? = value?.let { RoomJson.encodeOutcome(it) }

    @TypeConverter
    fun toSharedOutcome(value: String?): SharedOutcome? = value?.let { RoomJson.decodeOutcome(it) }
}
