package com.silas270.blocktime.ui.screens.challenges

import com.silas270.blocktime.data.model.Challenge
import com.silas270.blocktime.data.model.ChallengeSource
import com.silas270.blocktime.data.model.ChallengeType
import com.silas270.blocktime.data.model.CuratedChallengeSets
import com.silas270.blocktime.data.model.ParticipantSnapshot
import com.silas270.blocktime.data.model.RoomDefinition
import com.silas270.blocktime.data.model.RoomState
import com.silas270.blocktime.data.model.RoomStateCache
import com.silas270.blocktime.data.model.SetMemberKind
import com.silas270.blocktime.util.milesToKm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The progress texts beside the bars: distance and route in percent, set and streak in counts; a
 * shared pool reads the team's value, a race and every solo row the pilot's own.
 */
class ChallengeTextTest {

    private companion object {
        const val SELF = "SELF01"
        const val ANNA = "ANNA01"
        const val BOB = "BOB001"
    }

    private fun cache(type: ChallengeType, vararg participants: ParticipantSnapshot) = RoomStateCache(
        selfCode = SELF,
        room = RoomState(
            code = "ROOM01",
            definition = RoomDefinition(type = type, source = ChallengeSource.CUSTOM, name = "Shared"),
            participants = participants.toList()
        )
    )

    private fun distance(ownMiles: Double, shared: Boolean) = Challenge(
        id = 1,
        userId = 1,
        type = ChallengeType.DISTANCE,
        source = ChallengeSource.CUSTOM,
        name = "Distance",
        targetDistanceKm = milesToKm(1000.0),
        cumulativeDistanceKm = milesToKm(ownMiles),
        roomCode = if (shared) "ROOM01" else null,
        roomState = if (shared) {
            cache(
                ChallengeType.DISTANCE,
                // The cached self snapshot is stale; the row's own miles are the truth.
                ParticipantSnapshot(userCode = SELF, username = "self", colorIndex = 0, distanceKm = 0.0),
                ParticipantSnapshot(userCode = ANNA, username = "anna", colorIndex = 1, distanceKm = milesToKm(311.0))
            )
        } else null
    )

    private fun set(shared: Boolean) = Challenge(
        id = 1,
        userId = 1,
        type = ChallengeType.SET_COMPLETION,
        source = ChallengeSource.CURATED,
        name = "Continents",
        setCatalogId = CuratedChallengeSets.ALL_CONTINENTS.catalogId,
        setMemberKind = SetMemberKind.CONTINENT,
        // Started under an older definition that still listed Antarctica.
        setTotalMembers = 7,
        visitedSetMembers = setOf("EU", "AN"),
        roomCode = if (shared) "ROOM01" else null,
        roomState = if (shared) {
            cache(
                ChallengeType.SET_COMPLETION,
                ParticipantSnapshot(userCode = SELF, username = "self", colorIndex = 0, visitedMembers = setOf("EU")),
                ParticipantSnapshot(userCode = ANNA, username = "anna", colorIndex = 1, visitedMembers = setOf("AS", "EU", "XX")),
                ParticipantSnapshot(userCode = BOB, username = "bob", colorIndex = 2, left = true, visitedMembers = setOf("SA"))
            )
        } else null
    )

    private fun streak(shared: Boolean) = Challenge(
        id = 1,
        userId = 1,
        type = ChallengeType.STREAK,
        source = ChallengeSource.CUSTOM,
        name = "Streak",
        targetDays = 3,
        streakDays = 3,
        roomCode = if (shared) "ROOM01" else null,
        roomState = if (shared) {
            cache(
                ChallengeType.STREAK,
                ParticipantSnapshot(userCode = SELF, username = "self", colorIndex = 0, streakDays = 3),
                ParticipantSnapshot(userCode = ANNA, username = "anna", colorIndex = 1, streakDays = 2)
            )
        } else null
    )

    private fun race(shared: Boolean) = Challenge(
        id = 1,
        userId = 1,
        type = ChallengeType.ROUTE,
        source = ChallengeSource.CUSTOM,
        name = "Race",
        originIata = "LHR",
        destIata = "SYD",
        positionIata = "DXB",
        routeProgressFraction = 0.4f,
        roomCode = if (shared) "ROOM01" else null,
        roomState = if (shared) {
            cache(
                ChallengeType.ROUTE,
                ParticipantSnapshot(userCode = SELF, username = "self", colorIndex = 0),
                ParticipantSnapshot(userCode = ANNA, username = "anna", colorIndex = 1, routeProgress = 0.9f)
            )
        } else null
    )

    @Test
    fun `solo progress is percent for distance and route, counts for set and streak`() {
        assertEquals("27%", challengeProgressText(distance(270.0, shared = false)))
        assertEquals("2/7 visited", challengeProgressText(set(shared = false)))
        assertEquals("3 of 3 days", challengeProgressText(streak(shared = false)))
        assertEquals("40%", challengeProgressText(race(shared = false)))
    }

    @Test
    fun `shared pool progress reads the team value and never a personal share`() {
        // 270 own + 311 from Anna of a 1,000 mi target.
        assertEquals("58%", challengeProgressText(distance(270.0, shared = true)))
        // Antarctica and XX are no members any more; the leaver's South America stays.
        assertEquals("3/6 visited", challengeProgressText(set(shared = true)))
        // The group streak is the minimum: Anna's 2 of 3 days.
        assertEquals("2 of 3 days", challengeProgressText(streak(shared = true)))
    }

    @Test
    fun `shared race progress reads own progress`() {
        assertEquals("40%", challengeProgressText(race(shared = true)))
    }

    @Test
    fun `progress caps at the target`() {
        assertEquals("100%", challengeProgressText(distance(800.0, shared = true)))
    }

    @Test
    fun `ring label is short for set and streak`() {
        assertEquals("27%", challengeRingLabel(distance(270.0, shared = false)))
        assertEquals("3/6", challengeRingLabel(set(shared = true)))
        assertEquals("2/3", challengeRingLabel(streak(shared = true)))
        assertEquals("40%", challengeRingLabel(race(shared = false)))
    }

    @Test
    fun `route text names the hop and only a route has one`() {
        assertEquals("DXB → SYD", challengeRouteText(race(shared = false)))
        assertNull(challengeRouteText(distance(270.0, shared = false)))
        assertNull(challengeRouteText(streak(shared = false)))
    }

    @Test
    fun `percentages round down the same way on every screen`() {
        assertEquals("39%", progressPercentText(397f / 1000f))
        assertEquals("29%", progressPercentText(0.29f))
        assertEquals("99%", progressPercentText(0.999f))
        assertEquals("100%", progressPercentText(1f))
        assertEquals("0%", progressPercentText(0f))
        assertEquals("100%", progressPercentText(1.2f))
    }
}
