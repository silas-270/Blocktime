package com.silas270.blocktime.data.model

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * The pilot's own snapshot, built from the local row (docs/shared-challenges.md "Wire and cache
 * shapes", plan B3). Pure: the row, a name, a colour and the clock in, a [ParticipantSnapshot]
 * out. The syncer uploads what this returns; nothing else ever writes the pilot's snapshot
 * (invariant 1).
 *
 * Only the fields of the row's own type are filled; the other types' fields keep their
 * defaults, which is what the wire shape allows. Values are the row's raw values, except the
 * streak, which is read through the clock the way every other reader sees it: a peer on any app
 * version gets the pilot's visited members and kilometres as stored and does the filtering and
 * summing on their side (P14).
 */
fun Challenge.toParticipantSnapshot(
    username: String,
    colorIndex: Int,
    today: LocalDate,
    zone: ZoneId? = null,
): ParticipantSnapshot {
    val base = ParticipantSnapshot(userCode = "", username = username, colorIndex = colorIndex)
    return when (type) {
        ChallengeType.ROUTE -> base.copy(
            positionIata = positionIata,
            legIndex = legIndex,
            routeProgress = progressFraction(),
        )
        ChallengeType.SET_COMPLETION -> base.copy(visitedMembers = visitedSetMembers)
        ChallengeType.DISTANCE -> base.copy(distanceKm = cumulativeDistanceKm)
        ChallengeType.STREAK -> base.copy(
            streakDays = currentStreak(today),
            lastFlownDay = lastFlownDay,
            streakAlive = isOwnStreakAlive(today, zone ?: ZoneId.systemDefault()),
        )
    }
}

/**
 * Whether the pilot's own streak is still alive as of [today], by the local rule: the run is
 * alive while [Challenge.currentStreak] is above zero, or, before the first credited flight,
 * while the row was started today or yesterday.
 *
 * The second clause gives a fresh joiner the same tolerance [currentStreak] gives a run: the day
 * of joining and the day after are not yet a missed day. Without it a pilot who joined a group
 * streak in the evening would be dead at midnight, before they could have flown. [startedAt] is
 * epoch millis and is read in [zone], the same way `creditStreak` turns a landing time into a
 * calendar day, so the join day is the pilot's own calendar day.
 *
 * This is the one place the rule lives: the uploaded snapshot's `streakAlive` and the merge's
 * "self is dead" check both read it, so the pilot can never report one thing and act on another.
 */
fun Challenge.isOwnStreakAlive(today: LocalDate, zone: ZoneId = ZoneId.systemDefault()): Boolean =
    currentStreak(today) > 0 ||
        (lastFlownDay == null && !startedDay(zone).isBefore(today.minusDays(1)))

/** The calendar day the row was started, in [zone]. */
fun Challenge.startedDay(zone: ZoneId = ZoneId.systemDefault()): LocalDate =
    Instant.ofEpochMilli(startedAt).atZone(zone).toLocalDate()
