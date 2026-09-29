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
 * The progress lines beside the bars: a shared pool reads the team total with the pilot's own
 * share after it, because the bar and percentage beside it are the team's; a race and every
 * solo row read the pilot's own progress, unchanged.
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
    fun `solo status reads own progress`() {
        assertEquals("270 mi / 1,000 mi", challengeStatusText(distance(270.0, shared = false)))
        assertEquals("2/7 visited", challengeStatusText(set(shared = false)))
        assertEquals("3 of 3 days", challengeStatusText(streak(shared = false)))
        assertEquals("DXB → SYD", challengeStatusText(race(shared = false)))
    }

    @Test
    fun `shared pool status reads the team total with the pilot's own share`() {
        assertEquals("581 / 1,000 mi · you 270 mi", challengeStatusText(distance(270.0, shared = true)))
        // Antarctica and XX are no members any more; the leaver's South America stays.
        assertEquals("3/6 visited · you 1", challengeStatusText(set(shared = true)))
        assertEquals("2 of 3 days · you 3", challengeStatusText(streak(shared = true)))
    }

    @Test
    fun `shared race status reads own progress`() {
        assertEquals("DXB → SYD", challengeStatusText(race(shared = true)))
    }

    @Test
    fun `solo outcome reads own progress`() {
        assertEquals("270 / 1,000 mi", challengeOutcomeText(distance(270.0, shared = false)))
        assertEquals("2/7 visited", challengeOutcomeText(set(shared = false)))
        assertEquals("3 of 3 days", challengeOutcomeText(streak(shared = false)))
        assertNull(challengeOutcomeText(race(shared = false)))
    }

    @Test
    fun `shared pool outcome reads the team total with the pilot's own share`() {
        assertEquals("581 / 1,000 mi · you 270 mi", challengeOutcomeText(distance(270.0, shared = true)))
        assertEquals("3/6 visited · you 1", challengeOutcomeText(set(shared = true)))
        assertEquals("2 of 3 days · you 3", challengeOutcomeText(streak(shared = true)))
        assertNull(challengeOutcomeText(race(shared = true)))
    }

    @Test
    fun `shared outcome caps the team total at the target`() {
        assertEquals("1,000 / 1,000 mi · you 800 mi", challengeOutcomeText(distance(800.0, shared = true)))
    }
}
