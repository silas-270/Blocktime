package com.silas270.blocktime.data.model

/**
 * The two ways a [RoomDefinition] comes to exist (docs/shared-challenges.md "Data model"): from
 * the curated template a challenge is started from, and from a row the pilot shares. Both feed
 * the one row builder the repository uses, so a joined room produces the same row shape as a
 * started challenge, with nothing that changes afterwards in it.
 */

/** The definition of a room for this template: what `startCuratedChallenge` would build from. */
fun CuratedChallengeTemplate.toRoomDefinition(): RoomDefinition = RoomDefinition(
    type = type,
    source = ChallengeSource.CURATED,
    name = name,
    description = description,
    iconName = iconName,
    catalogId = catalogId,
    predefinedRouteId = predefinedRouteId,
    originIata = originIata,
    destIata = destIata,
    setCatalogId = setDefinition?.catalogId,
    setMemberKind = setDefinition?.memberKind,
    targetDistanceKm = targetDistanceKm,
    targetDays = targetDays,
)

/**
 * The definition of a room for this row: the fixed part of the row, none of its progress. The
 * row does not record which curated template it came from, so `catalogId` is null; a joiner
 * never needs it, because everything the row builder reads is carried here directly.
 */
fun Challenge.toRoomDefinition(): RoomDefinition = RoomDefinition(
    type = type,
    source = source,
    name = name,
    description = description,
    iconName = iconName,
    catalogId = null,
    predefinedRouteId = predefinedRouteId,
    originIata = originIata,
    destIata = destIata,
    setCatalogId = setCatalogId,
    setMemberKind = setMemberKind,
    targetDistanceKm = targetDistanceKm,
    targetDays = targetDays,
)
