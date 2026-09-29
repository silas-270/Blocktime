package com.silas270.blocktime.data.network.room

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonParseException
import com.silas270.blocktime.data.model.ChallengeSource
import com.silas270.blocktime.data.model.ChallengeType
import com.silas270.blocktime.data.model.OutcomeClaim
import com.silas270.blocktime.data.model.ParticipantSnapshot
import com.silas270.blocktime.data.model.RoomDefinition
import com.silas270.blocktime.data.model.RoomState
import com.silas270.blocktime.data.model.RoomStateCache
import com.silas270.blocktime.data.model.SetMemberKind
import com.silas270.blocktime.data.model.SharedOutcome

/**
 * The flat JSON shapes of a room, on the wire and in the `room_state` cache. **Every field is
 * nullable and the outcome and claim carry a `kind` string**, not the Kotlin sealed types: Gson
 * cannot read a sealed interface polymorphically, and it bypasses Kotlin constructors, so a
 * field a peer's app version did not send would silently become null where the domain type
 * promised a value. The defaults live in the `toDomain()` mappings below, each one documented,
 * which is what lets two app versions share a room (docs/shared-challenges.md Y9).
 *
 * The release build keeps these classes' field names for Gson (proguard-rules.pro).
 */

data class ParticipantSnapshotDto(
    val userCode: String? = null,
    val username: String? = null,
    val colorIndex: Int? = null,
    val left: Boolean? = null,
    val positionIata: String? = null,
    val legIndex: Int? = null,
    val routeProgress: Float? = null,
    val visitedMembers: List<String>? = null,
    val distanceKm: Double? = null,
    val streakDays: Int? = null,
    val lastFlownDay: String? = null,
    val streakAlive: Boolean? = null,
    val updatedAt: Long? = null,
)

data class RoomDefinitionDto(
    val type: String? = null,
    val source: String? = null,
    val name: String? = null,
    val description: String? = null,
    val iconName: String? = null,
    val catalogId: String? = null,
    val predefinedRouteId: String? = null,
    val originIata: String? = null,
    val destIata: String? = null,
    val setCatalogId: String? = null,
    val setMemberKind: String? = null,
    val targetDistanceKm: Double? = null,
    val targetDays: Int? = null,
)

/** [kind] is [KIND_COMPLETED] or [KIND_FAILED]; [byUserCode] is the winner or the streak breaker. */
data class OutcomeDto(
    val kind: String? = null,
    val byUserCode: String? = null,
    val at: Long? = null,
    val placements: List<String>? = null,
)

data class RoomStateDto(
    val code: String? = null,
    val definition: RoomDefinitionDto? = null,
    val createdAt: Long? = null,
    val participants: List<ParticipantSnapshotDto>? = null,
    val outcome: OutcomeDto? = null,
    val version: Long? = null,
    val roomGone: Boolean? = null,
)

data class RoomStateCacheDto(
    val selfCode: String? = null,
    val room: RoomStateDto? = null,
)

/** [kind] is [KIND_COMPLETED] or [KIND_FAILED]. */
data class ClaimDto(
    val kind: String? = null,
    val at: Long? = null,
    val brokenBy: String? = null,
)

const val KIND_COMPLETED = "completed"
const val KIND_FAILED = "failed"

// Domain to DTO. Lossless apart from `bySelf` and `roomGone`, which are client-side facts: the
// cache re-derives `bySelf` from its own `selfCode`, and `roomGone` is kept in the cache only.

fun ParticipantSnapshot.toDto() = ParticipantSnapshotDto(
    userCode = userCode,
    username = username,
    colorIndex = colorIndex,
    left = left,
    positionIata = positionIata,
    legIndex = legIndex,
    routeProgress = routeProgress,
    visitedMembers = visitedMembers.toList(),
    distanceKm = distanceKm,
    streakDays = streakDays,
    lastFlownDay = lastFlownDay,
    streakAlive = streakAlive,
    updatedAt = updatedAt,
)

fun RoomDefinition.toDto() = RoomDefinitionDto(
    type = type.name,
    source = source.name,
    name = name,
    description = description,
    iconName = iconName,
    catalogId = catalogId,
    predefinedRouteId = predefinedRouteId,
    originIata = originIata,
    destIata = destIata,
    setCatalogId = setCatalogId,
    setMemberKind = setMemberKind?.name,
    targetDistanceKm = targetDistanceKm,
    targetDays = targetDays,
)

fun SharedOutcome.toDto(): OutcomeDto = when (this) {
    is SharedOutcome.Completed -> OutcomeDto(kind = KIND_COMPLETED, byUserCode = byUserCode, at = at, placements = placements)
    is SharedOutcome.Failed -> OutcomeDto(kind = KIND_FAILED, byUserCode = brokenByUserCode, at = at)
}

fun RoomState.toDto() = RoomStateDto(
    code = code,
    definition = definition.toDto(),
    createdAt = createdAt,
    participants = participants.map { it.toDto() },
    outcome = outcome?.toDto(),
    version = version,
    roomGone = roomGone,
)

fun RoomStateCache.toDto() = RoomStateCacheDto(selfCode = selfCode, room = room.toDto())

fun OutcomeClaim.toDto(): ClaimDto = when (this) {
    is OutcomeClaim.Completed -> ClaimDto(kind = KIND_COMPLETED, at = at)
    is OutcomeClaim.Failed -> ClaimDto(kind = KIND_FAILED, brokenBy = brokenBy)
}

// DTO to domain, with the defaults for what was not sent.

/**
 * Null when [ParticipantSnapshotDto.userCode] is missing: a snapshot nobody owns cannot be
 * attributed and is dropped from the crew. Every other missing field takes the domain default
 * (empty name, colour 0, not left, no progress, alive, never updated).
 */
fun ParticipantSnapshotDto.toDomain(): ParticipantSnapshot? {
    val code = userCode?.takeIf { it.isNotBlank() } ?: return null
    return ParticipantSnapshot(
        userCode = code,
        username = username ?: "",
        colorIndex = colorIndex ?: 0,
        left = left ?: false,
        positionIata = positionIata,
        legIndex = legIndex ?: 0,
        routeProgress = routeProgress ?: 0f,
        visitedMembers = visitedMembers?.toSet() ?: emptySet(),
        distanceKm = distanceKm ?: 0.0,
        streakDays = streakDays ?: 0,
        lastFlownDay = lastFlownDay,
        streakAlive = streakAlive ?: true,
        updatedAt = updatedAt ?: 0L,
    )
}

/**
 * Null when the type is missing or unknown to this app version: there is no sensible challenge
 * to build from it, and the join path reports an unknown template. An unknown source falls back
 * to curated when a catalog id is present and custom otherwise, which is how the two are told
 * apart anyway. An unknown set member kind becomes null and is resolved from the catalog.
 */
fun RoomDefinitionDto.toDomain(): RoomDefinition? {
    val resolvedType = type?.let { name -> ChallengeType.entries.firstOrNull { it.name == name } } ?: return null
    val resolvedSource = source?.let { name -> ChallengeSource.entries.firstOrNull { it.name == name } }
        ?: if (catalogId != null) ChallengeSource.CURATED else ChallengeSource.CUSTOM
    return RoomDefinition(
        type = resolvedType,
        source = resolvedSource,
        name = name ?: "",
        description = description ?: "",
        iconName = iconName,
        catalogId = catalogId,
        predefinedRouteId = predefinedRouteId,
        originIata = originIata,
        destIata = destIata,
        setCatalogId = setCatalogId,
        setMemberKind = setMemberKind?.let { name -> SetMemberKind.entries.firstOrNull { it.name == name } },
        targetDistanceKm = targetDistanceKm,
        targetDays = targetDays,
    )
}

/**
 * Null for a missing or unknown kind: an outcome this app version cannot interpret is treated
 * as no outcome, so the row keeps running locally rather than ending on a guess. [bySelf] is
 * the caller's business (false from the wire, derived from `selfCode` in the cache).
 */
fun OutcomeDto.toDomain(bySelf: Boolean = false): SharedOutcome? = when (kind) {
    KIND_COMPLETED -> SharedOutcome.Completed(
        byUserCode = byUserCode ?: "",
        bySelf = bySelf,
        at = at ?: 0L,
        placements = placements ?: emptyList(),
    )
    KIND_FAILED -> SharedOutcome.Failed(
        brokenByUserCode = byUserCode ?: "",
        bySelf = bySelf,
        at = at ?: 0L,
    )
    else -> null
}

/**
 * Null when the code or the definition is missing or unusable, because a room without either
 * cannot be shown or joined. Participants without a code are dropped, an unknown outcome reads
 * as open, and [selfCode] stamps `bySelf` on the outcome when given.
 */
fun RoomStateDto.toDomain(selfCode: String? = null): RoomState? {
    val resolvedCode = code?.takeIf { it.isNotBlank() } ?: return null
    val resolvedDefinition = definition?.toDomain() ?: return null
    val resolvedOutcome = outcome?.let { dto ->
        dto.toDomain(bySelf = selfCode != null && dto.byUserCode == selfCode)
    }
    return RoomState(
        code = resolvedCode,
        definition = resolvedDefinition,
        createdAt = createdAt ?: 0L,
        participants = participants?.mapNotNull { it.toDomain() } ?: emptyList(),
        outcome = resolvedOutcome,
        version = version ?: 0L,
        roomGone = roomGone ?: false,
    )
}

/** Null when the pilot's code or the room is missing; the cache is then simply rebuilt. */
fun RoomStateCacheDto.toDomain(): RoomStateCache? {
    val code = selfCode?.takeIf { it.isNotBlank() } ?: return null
    val resolvedRoom = room?.toDomain(selfCode = code) ?: return null
    return RoomStateCache(selfCode = code, room = resolvedRoom)
}

/** Null for a missing or unknown kind; the server then treats the put as claim-free. */
fun ClaimDto.toDomain(): OutcomeClaim? = when (kind) {
    KIND_COMPLETED -> OutcomeClaim.Completed(at = at ?: 0L)
    KIND_FAILED -> OutcomeClaim.Failed(brokenBy = brokenBy ?: "")
    else -> null
}

/** The one Gson for room JSON, and the cache codec the `room_state` type converter uses. */
object RoomJson {
    val gson: Gson = GsonBuilder().disableHtmlEscaping().create()

    fun encodeCache(cache: RoomStateCache): String = gson.toJson(cache.toDto())

    /**
     * Null on any parse failure or on a cache that cannot be mapped. The cache is disposable by
     * design (docs/shared-challenges.md "Data model"), so a corrupt one is dropped, never thrown.
     */
    fun decodeCache(json: String): RoomStateCache? = try {
        gson.fromJson(json, RoomStateCacheDto::class.java)?.toDomain()
    } catch (e: JsonParseException) {
        null
    } catch (e: IllegalStateException) {
        null
    }
}
