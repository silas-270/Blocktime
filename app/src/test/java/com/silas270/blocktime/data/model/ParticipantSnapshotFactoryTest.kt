package com.silas270.blocktime.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * [toParticipantSnapshot] maps the row's own fields per type and nothing else, and
 * [isOwnStreakAlive] is the local aliveness rule of docs/shared-challenges.md "The merge": a
 * live run, or a joiner on the join day or the day after.
 */
class ParticipantSnapshotFactoryTest {

    private companion object {
        val TODAY: LocalDate = LocalDate.of(2026, 9, 29)
    }

    private fun noonOf(dayOffset: Long): Long =
        TODAY.plusDays(dayOffset).atTime(12, 0).toInstant(ZoneOffset.UTC).toEpochMilli()

    private fun base(type: ChallengeType) = Challenge(
        id = 1,
        userId = 1,
        type = type,
        source = ChallengeSource.CUSTOM,
        name = "Row",
        roomCode = "ROOM01",
        startedAt = noonOf(-10),
    )

    @Test
    fun `a route snapshot carries position, leg and progress`() {
        val row = base(ChallengeType.ROUTE).copy(originIata = "LHR", destIata = "SYD", positionIata = "DXB", legIndex = 2, routeProgressFraction = 0.4f)

        val snapshot = row.toParticipantSnapshot("Me", 1, TODAY, ZoneOffset.UTC)

        assertEquals("", snapshot.userCode)
        assertEquals("Me", snapshot.username)
        assertEquals(1, snapshot.colorIndex)
        assertFalse(snapshot.left)
        assertEquals("DXB", snapshot.positionIata)
        assertEquals(2, snapshot.legIndex)
        assertEquals(0.4f, snapshot.routeProgress)
        assertEquals(0L, snapshot.updatedAt)
        // The other types' fields stay at their defaults.
        assertEquals(ParticipantSnapshot(userCode = "", username = "Me", colorIndex = 1, positionIata = "DXB", legIndex = 2, routeProgress = 0.4f), snapshot)
    }

    @Test
    fun `a set snapshot carries the visited members as stored`() {
        // Raw data, not filtered through the current definition: peers filter on their side.
        val row = base(ChallengeType.SET_COMPLETION).copy(
            setCatalogId = CuratedChallengeSets.ALL_CONTINENTS.catalogId,
            setMemberKind = SetMemberKind.CONTINENT,
            setTotalMembers = 7,
            visitedSetMembers = setOf("EU", "AN"),
        )

        val snapshot = row.toParticipantSnapshot("Me", 0, TODAY, ZoneOffset.UTC)

        assertEquals(ParticipantSnapshot(userCode = "", username = "Me", colorIndex = 0, visitedMembers = setOf("EU", "AN")), snapshot)
    }

    @Test
    fun `a distance snapshot carries the kilometres`() {
        val row = base(ChallengeType.DISTANCE).copy(targetDistanceKm = 1000.0, cumulativeDistanceKm = 250.5)

        val snapshot = row.toParticipantSnapshot("Me", 2, TODAY, ZoneOffset.UTC)

        assertEquals(ParticipantSnapshot(userCode = "", username = "Me", colorIndex = 2, distanceKm = 250.5), snapshot)
    }

    @Test
    fun `a streak snapshot carries the run as of today and its aliveness`() {
        val alive = base(ChallengeType.STREAK).copy(targetDays = 5, streakDays = 3, lastFlownDay = TODAY.minusDays(1).toString())
        assertEquals(
            ParticipantSnapshot(userCode = "", username = "Me", colorIndex = 0, streakDays = 3, lastFlownDay = TODAY.minusDays(1).toString(), streakAlive = true),
            alive.toParticipantSnapshot("Me", 0, TODAY, ZoneOffset.UTC),
        )

        // The stored run is stale: the snapshot reports it through the clock, dead at zero.
        val dead = alive.copy(lastFlownDay = TODAY.minusDays(3).toString())
        assertEquals(
            ParticipantSnapshot(userCode = "", username = "Me", colorIndex = 0, streakDays = 0, lastFlownDay = TODAY.minusDays(3).toString(), streakAlive = false),
            dead.toParticipantSnapshot("Me", 0, TODAY, ZoneOffset.UTC),
        )
    }

    @Test
    fun `a run is alive while its last day is today or yesterday`() {
        val row = base(ChallengeType.STREAK).copy(targetDays = 5, streakDays = 2)

        assertTrue(row.copy(lastFlownDay = TODAY.toString()).isOwnStreakAlive(TODAY, ZoneOffset.UTC))
        assertTrue(row.copy(lastFlownDay = TODAY.minusDays(1).toString()).isOwnStreakAlive(TODAY, ZoneOffset.UTC))
        assertFalse(row.copy(lastFlownDay = TODAY.minusDays(2).toString()).isOwnStreakAlive(TODAY, ZoneOffset.UTC))
    }

    @Test
    fun `a joiner who has not flown is alive on the join day and the day after`() {
        val row = base(ChallengeType.STREAK).copy(targetDays = 5)

        assertTrue(row.copy(startedAt = noonOf(0)).isOwnStreakAlive(TODAY, ZoneOffset.UTC))
        assertTrue(row.copy(startedAt = noonOf(-1)).isOwnStreakAlive(TODAY, ZoneOffset.UTC))
        assertFalse(row.copy(startedAt = noonOf(-2)).isOwnStreakAlive(TODAY, ZoneOffset.UTC))
    }

    @Test
    fun `the join day is the pilot's own calendar day`() {
        // 23:30 UTC two days ago is still "two days ago" in UTC and already "yesterday" three
        // hours east, so the same row is dead in one zone and alive in the other.
        val startedAt = TODAY.minusDays(2).atTime(23, 30).toInstant(ZoneOffset.UTC).toEpochMilli()
        val row = base(ChallengeType.STREAK).copy(targetDays = 5, startedAt = startedAt)

        assertFalse(row.isOwnStreakAlive(TODAY, ZoneOffset.UTC))
        assertTrue(row.isOwnStreakAlive(TODAY, ZoneOffset.ofHours(3)))
        assertEquals(TODAY.minusDays(1), row.startedDay(ZoneOffset.ofHours(3)))
    }

    @Test
    fun `a dead run is not revived by the join rule`() {
        // Had a run and lost it: the row started yesterday but the run's last day is older,
        // which cannot happen in practice and must still read as dead rather than alive.
        val row = base(ChallengeType.STREAK).copy(targetDays = 5, streakDays = 2, lastFlownDay = TODAY.minusDays(3).toString(), startedAt = noonOf(-1))

        assertFalse(row.isOwnStreakAlive(TODAY, ZoneOffset.UTC))
    }
}
