package com.silas270.blocktime.data.repository

import com.silas270.blocktime.data.model.Airport
import com.silas270.blocktime.data.model.Challenge
import com.silas270.blocktime.data.model.ChallengeStatus
import com.silas270.blocktime.data.model.ChallengeType
import com.silas270.blocktime.data.model.CuratedChallengeSets
import com.silas270.blocktime.data.model.CuratedChallengeCatalog
import com.silas270.blocktime.data.model.PredefinedRouteCatalog
import com.silas270.blocktime.data.model.progressFraction
import com.silas270.blocktime.data.model.UserProfile
import com.silas270.blocktime.testutil.FakeAirportRepository
import com.silas270.blocktime.testutil.FakeChallengeDao
import com.silas270.blocktime.testutil.FakeUserProfileDao
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Exercises [LocalChallengeRepository] - the active-challenge cap, abandon-not-reset, Route's own
 * position-pointer scoping (including the accepted decreasing-progress case), Distance
 * accumulation, and Set-completion crediting (including its "starts fresh per instance"
 * isolation guarantee) - all via hand-written fakes for [ChallengeDao]/[UserProfileDao]/
 * [AirportRepository] (shared with the sharing and syncer tests, in `testutil`), the same
 * fake-collaborator pattern the other repository tests use for repository interfaces.
 */
class LocalChallengeRepositoryTest {

    private fun airport(iata: String, lat: Double, lon: Double, continent: String, country: String) = Airport(
        id = iata.hashCode(),
        ident = iata,
        iataCode = iata,
        name = iata,
        lat = lat,
        lon = lon,
        elevationFt = 0.0,
        continent = continent,
        isoCountry = country,
        isoRegion = "",
        municipality = iata,
        type = "large_airport"
    )

    // Synthetic geography, chosen for clean arithmetic rather than real-world coordinates -
    // origin/dest 100 degrees apart along the equator, "closer"/"farther" at 50/30 degrees in.
    private val origin = airport("ORI", 0.0, 0.0, "EU", "FR")
    private val dest = airport("DST", 0.0, 100.0, "OC", "AU")
    private val closer = airport("MID", 0.0, 50.0, "AS", "IN")
    private val fartherAgain = airport("BAK", 0.0, 30.0, "AS", "CN")

    private lateinit var dao: FakeChallengeDao
    private lateinit var userProfileDao: FakeUserProfileDao
    private lateinit var airportRepository: FakeAirportRepository
    private lateinit var repository: LocalChallengeRepository

    private companion object {
        /**
         * A streak is a function of the calendar, so both the clock the repository reads "today"
         * from and the landing timestamps it credits have to be fixed - an ambient
         * `System.currentTimeMillis()` would make these tests pass or fail depending on how close
         * to midnight they run. UTC so the day boundaries below are exactly 24h apart with no DST
         * shift to reason about.
         */
        private val ZONE: ZoneId = ZoneOffset.UTC
        private val DAY_1: Long = LocalDate.of(2026, 3, 2).atStartOfDay(ZONE).toInstant().toEpochMilli()
        private val DAY_2: Long = DAY_1 + 24L * 60 * 60 * 1000
        private val DAY_3: Long = DAY_1 + 2 * 24L * 60 * 60 * 1000
        private val DAY_5: Long = DAY_1 + 4 * 24L * 60 * 60 * 1000

        /** Fixed at DAY_3, so a run whose last day is DAY_2 or DAY_3 is alive and DAY_1 is not. */
        private val TEST_CLOCK: Clock = Clock.fixed(Instant.ofEpochMilli(DAY_3), ZONE)
    }

    @Before
    fun setUp() {
        dao = FakeChallengeDao()
        userProfileDao = FakeUserProfileDao(UserProfile(id = 1, username = "Cap", userCode = "ABC123", homeAirportIata = "ORI"))
        airportRepository = FakeAirportRepository(
            mapOf(
                origin.iataCode to origin,
                dest.iataCode to dest,
                closer.iataCode to closer,
                fartherAgain.iataCode to fartherAgain,
                "LHR" to airport("LHR", 51.47, -0.45, "EU", "GB"),
                "MEL" to airport("MEL", -37.66, 144.84, "OC", "AU"),
                "JFK" to airport("JFK", 40.64, -73.78, "NA", "US"),
                "CDG" to airport("CDG", 49.01, 2.55, "EU", "FR"),
                "SYD" to airport("SYD", -33.94, 151.18, "OC", "AU")
            )
        )
        repository = LocalChallengeRepository(dao, userProfileDao, airportRepository, TEST_CLOCK)
    }

    // ── start / cap / abandon ────────────────────────────────────────────────────────────

    @Test
    fun `starting a curated route challenge seeds the position pointer at the origin`() = runTest {
        val result = repository.startCuratedChallenge("route_project_sunrise")

        assertTrue(result is StartChallengeResult.Started)
        val challenge = (result as StartChallengeResult.Started).challenge
        assertEquals("MEL", challenge.positionIata)
        assertEquals("MEL", challenge.originIata)
        assertEquals("LHR", challenge.destIata)
        assertEquals(0f, challenge.routeProgressFraction, 0.001f)
        assertEquals(ChallengeStatus.ACTIVE, challenge.status)
    }

    @Test
    fun `starting a curated set-completion challenge starts with zero visited members`() = runTest {
        val result = repository.startCuratedChallenge("set_all_continents")

        val challenge = (result as StartChallengeResult.Started).challenge
        assertEquals(CuratedChallengeSets.ALL_CONTINENTS.members.size, challenge.setTotalMembers)
        assertTrue(challenge.visitedSetMembers.isEmpty())
    }

    @Test
    fun `unknown catalog id is refused`() = runTest {
        val result = repository.startCuratedChallenge("not_a_real_template")
        assertEquals(StartChallengeResult.UnknownTemplate, result)
    }

    @Test
    fun `a fourth active challenge is refused once the cap of three is reached`() = runTest {
        repository.startCustomDistanceChallenge(1000.0, "A")
        repository.startCustomDistanceChallenge(2000.0, "B")
        repository.startCustomDistanceChallenge(3000.0, "C")

        val fourth = repository.startCustomDistanceChallenge(4000.0, "D")

        assertEquals(StartChallengeResult.CapReached, fourth)
        assertEquals(3, repository.listActiveChallenges().size)
    }

    @Test
    fun `an uncelebrated completion still counts against the cap`() = runTest {
        val completed = (repository.startCustomDistanceChallenge(100.0, "Done") as StartChallengeResult.Started).challenge
        repository.creditEligibleFlight("DST", 100.0, DAY_1)
        assertEquals(ChallengeStatus.COMPLETED, dao.getById(completed.id)?.status)

        repository.startCustomDistanceChallenge(1000.0, "B")
        repository.startCustomDistanceChallenge(2000.0, "C")

        // Only 2 challenges are ACTIVE, but the completed-and-not-yet-celebrated one is still
        // occupying a slot visually, so a 4th start must still be refused.
        val fourth = repository.startCustomDistanceChallenge(3000.0, "D")

        assertEquals(StartChallengeResult.CapReached, fourth)
    }

    @Test
    fun `marking a challenge celebrated moves it from the slot display into the completed log`() = runTest {
        val challenge = (repository.startCustomDistanceChallenge(100.0, "Done") as StartChallengeResult.Started).challenge
        repository.creditEligibleFlight("DST", 100.0, DAY_1)

        assertTrue(
            "an uncelebrated completion must still occupy a slot",
            repository.listSlotDisplayChallengesFlow().first().any { it.id == challenge.id }
        )
        assertTrue(
            "an uncelebrated completion must not yet be in the log",
            repository.listCompletedChallenges().none { it.id == challenge.id }
        )

        repository.markCelebrated(challenge.id)

        assertTrue(
            "a celebrated completion must no longer occupy a slot",
            repository.listSlotDisplayChallengesFlow().first().none { it.id == challenge.id }
        )
        assertTrue(
            "a celebrated completion must now be in the log",
            repository.listCompletedChallenges().any { it.id == challenge.id }
        )
    }

    @Test
    fun `abandoning a challenge removes it entirely and frees the cap slot`() = runTest {
        val a = (repository.startCustomDistanceChallenge(1000.0, "A") as StartChallengeResult.Started).challenge
        repository.startCustomDistanceChallenge(2000.0, "B")
        repository.startCustomDistanceChallenge(3000.0, "C")

        repository.abandonChallenge(a.id)

        assertNull(repository.getChallenge(a.id))
        assertEquals(2, repository.listActiveChallenges().size)

        // The freed slot can be used immediately - starting again is a fresh instance, not a
        // reset of the abandoned one (challenges.md's "Abandon, not reset").
        val fourth = repository.startCustomDistanceChallenge(4000.0, "D")
        assertTrue(fourth is StartChallengeResult.Started)
        assertEquals(3, repository.listActiveChallenges().size)
    }

    // ── Route scoping / advanceRouteChallenge ───────────────────────────────────────────

    @Test
    fun `advancing a route challenge moves the pointer and increases progress when getting closer`() = runTest {
        val started = (repository.startCustomRouteChallenge("ORI", "DST", "Test Route") as StartChallengeResult.Started).challenge

        val updated = repository.advanceRouteChallenge(started.id, "MID")

        assertNotNull(updated)
        assertEquals("MID", updated!!.positionIata)
        assertEquals(0.5f, updated.routeProgressFraction, 0.02f)
        assertEquals(ChallengeStatus.ACTIVE, updated.status)
    }

    @Test
    fun `a leg that points away from the destination legitimately decreases progress`() = runTest {
        val started = (repository.startCustomRouteChallenge("ORI", "DST", "Test Route") as StartChallengeResult.Started).challenge
        val afterCloser = repository.advanceRouteChallenge(started.id, "MID")!!

        val afterFartherAgain = repository.advanceRouteChallenge(started.id, "BAK")!!

        assertTrue(afterFartherAgain.routeProgressFraction < afterCloser.routeProgressFraction)
        assertEquals(ChallengeStatus.ACTIVE, afterFartherAgain.status) // still active, not stuck/broken
    }

    @Test
    fun `reaching the destination completes the route challenge`() = runTest {
        val started = (repository.startCustomRouteChallenge("ORI", "DST", "Test Route") as StartChallengeResult.Started).challenge

        val updated = repository.advanceRouteChallenge(started.id, "DST")!!

        assertEquals(ChallengeStatus.COMPLETED, updated.status)
        assertEquals(1f, updated.routeProgressFraction, 0.001f)
        assertNotNull(updated.completedAt)
    }

    @Test
    fun `advancing does not touch other active challenges`() = runTest {
        val route = (repository.startCustomRouteChallenge("ORI", "DST", "Route") as StartChallengeResult.Started).challenge
        val distance = (repository.startCustomDistanceChallenge(5000.0, "Distance") as StartChallengeResult.Started).challenge

        repository.advanceRouteChallenge(route.id, "MID")

        val untouchedDistance = repository.getChallenge(distance.id)!!
        assertEquals(0.0, untouchedDistance.cumulativeDistanceKm, 0.0)
    }

    // ── creditEligibleFlight: Distance ──────────────────────────────────────────────────

    @Test
    fun `distance challenges accumulate across multiple eligible flights`() = runTest {
        val challenge = (repository.startCustomDistanceChallenge(1000.0, "Test") as StartChallengeResult.Started).challenge

        repository.creditEligibleFlight("DST", 400.0, DAY_1)
        repository.creditEligibleFlight("DST", 300.0, DAY_1)

        val updated = repository.getChallenge(challenge.id)!!
        assertEquals(700.0, updated.cumulativeDistanceKm, 0.001)
        assertEquals(ChallengeStatus.ACTIVE, updated.status)
    }

    @Test
    fun `a distance challenge completes once the cumulative total reaches its target`() = runTest {
        val challenge = (repository.startCustomDistanceChallenge(1000.0, "Test") as StartChallengeResult.Started).challenge

        repository.creditEligibleFlight("DST", 1200.0, DAY_1)

        val updated = repository.getChallenge(challenge.id)!!
        assertEquals(ChallengeStatus.COMPLETED, updated.status)
        assertNotNull(updated.completedAt)
    }

    // ── creditEligibleFlight: Set-completion ────────────────────────────────────────────

    @Test
    fun `landing on a new continent credits a set-completion challenge`() = runTest {
        val challenge = (repository.startCuratedChallenge("set_all_continents") as StartChallengeResult.Started).challenge

        // "DST" is continent "OC" per the fake airport data above.
        repository.creditEligibleFlight("DST", 500.0, DAY_1)

        val updated = repository.getChallenge(challenge.id)!!
        assertEquals(setOf("OC"), updated.visitedSetMembers)
    }

    @Test
    fun `landing on an already-credited continent again does not double count it`() = runTest {
        val challenge = (repository.startCuratedChallenge("set_all_continents") as StartChallengeResult.Started).challenge

        repository.creditEligibleFlight("DST", 500.0, DAY_1) // OC
        repository.creditEligibleFlight("DST", 500.0, DAY_1) // OC again

        val updated = repository.getChallenge(challenge.id)!!
        assertEquals(1, updated.visitedSetMembers.size)
    }

    @Test
    fun `set-completion challenge completes once every member has been credited`() = runTest {
        val challenge = (repository.startCuratedChallenge("set_all_continents") as StartChallengeResult.Started).challenge
        val allContinents = CuratedChallengeSets.ALL_CONTINENTS.members
        // Feed one flight per continent using synthetic airports whose `continent` cycles through
        // every member of the curated set.
        allContinents.forEachIndexed { index, continentCode ->
            val iata = "C$index"
            val perContinentAirport = airport(iata, 0.0, index.toDouble(), continentCode, "XX")
            val repo = LocalChallengeRepository(
                dao, userProfileDao,
                FakeAirportRepository(mapOf(iata to perContinentAirport)),
                TEST_CLOCK
            )
            repo.creditEligibleFlight(iata, 100.0, DAY_1)
        }

        val updated = repository.getChallenge(challenge.id)!!
        assertEquals(allContinents.size, updated.visitedSetMembers.size)
        assertEquals(ChallengeStatus.COMPLETED, updated.status)
    }

    @Test
    fun `starting the same curated set-completion challenge again starts fresh at zero`() = runTest {
        val first = (repository.startCuratedChallenge("set_all_continents") as StartChallengeResult.Started).challenge
        repository.creditEligibleFlight("DST", 500.0, DAY_1) // credits OC on the first instance
        repository.abandonChallenge(first.id)

        val second = (repository.startCuratedChallenge("set_all_continents") as StartChallengeResult.Started).challenge

        // A brand-new instance never inherits progress from a previous (even abandoned) one -
        // this is the concrete mechanism behind challenges.md's "isolation" / repeatability
        // guarantee: each instance tracks its own visited-members, starting at zero.
        assertTrue(second.visitedSetMembers.isEmpty())
    }

    @Test
    fun `landing somewhere that is not a relevant set member credits nothing`() = runTest {
        val challenge = (repository.startCuratedChallenge("set_g7_capitals") as StartChallengeResult.Started).challenge

        // "DST"'s IATA code isn't one of the G7 capitals in CuratedChallengeSets.G7_CAPITALS.
        repository.creditEligibleFlight("DST", 500.0, DAY_1)

        val updated = repository.getChallenge(challenge.id)!!
        assertTrue(updated.visitedSetMembers.isEmpty())
    }

    @Test
    fun `route challenges are never touched by creditEligibleFlight`() = runTest {
        val route = (repository.startCustomRouteChallenge("ORI", "DST", "Route") as StartChallengeResult.Started).challenge

        repository.creditEligibleFlight("DST", 999.0, DAY_1)

        val untouched = repository.getChallenge(route.id)!!
        assertEquals("ORI", untouched.positionIata) // unchanged - only advanceRouteChallenge moves it
        assertEquals(0f, untouched.routeProgressFraction, 0.001f)
    }

    // ── creditEligibleFlight: Streak ────────────────────────────────────────────────────

    @Test
    fun `two flights on the same day count as one streak day`() = runTest {
        val challenge = (repository.startCustomStreakChallenge(4, "Streak") as StartChallengeResult.Started).challenge

        repository.creditEligibleFlight("DST", 500.0, DAY_2)
        repository.creditEligibleFlight("DST", 500.0, DAY_2 + 60_000)

        val updated = repository.getChallenge(challenge.id)!!
        assertEquals(1, updated.streakDays)
    }

    @Test
    fun `consecutive days increment the streak`() = runTest {
        val challenge = (repository.startCustomStreakChallenge(4, "Streak") as StartChallengeResult.Started).challenge

        repository.creditEligibleFlight("DST", 500.0, DAY_1)
        repository.creditEligibleFlight("DST", 500.0, DAY_2)
        repository.creditEligibleFlight("DST", 500.0, DAY_3)

        val updated = repository.getChallenge(challenge.id)!!
        assertEquals(3, updated.streakDays)
        assertEquals(ChallengeStatus.ACTIVE, updated.status)
    }

    @Test
    fun `a missed day resets the streak to one rather than continuing it`() = runTest {
        val challenge = (repository.startCustomStreakChallenge(4, "Streak") as StartChallengeResult.Started).challenge

        repository.creditEligibleFlight("DST", 500.0, DAY_1)
        repository.creditEligibleFlight("DST", 500.0, DAY_2)
        // DAY_3 skipped entirely.
        repository.creditEligibleFlight("DST", 500.0, DAY_5)

        // Read the row directly: this is about what the *write* recorded, and reading through the
        // repository would additionally resolve it against TEST_CLOCK, which is a separate rule
        // with its own tests below.
        assertEquals(1, dao.getById(challenge.id)!!.streakDays)
        assertEquals("2026-03-06", dao.getById(challenge.id)!!.lastFlownDay)
    }

    @Test
    fun `reaching the target completes the challenge and stamps completedAt`() = runTest {
        val challenge = (repository.startCustomStreakChallenge(3, "Streak") as StartChallengeResult.Started).challenge

        repository.creditEligibleFlight("DST", 500.0, DAY_1)
        repository.creditEligibleFlight("DST", 500.0, DAY_2)
        repository.creditEligibleFlight("DST", 500.0, DAY_3)

        val updated = repository.getChallenge(challenge.id)!!
        assertEquals(3, updated.streakDays)
        assertEquals(ChallengeStatus.COMPLETED, updated.status)
        assertNotNull(updated.completedAt)
    }

    /**
     * The dead-streak path, and the one most likely to regress: nothing runs at midnight to
     * notice a broken run, so the stored row still reads 2 while the truth is 0. The repository
     * resolves that against its clock on the way out.
     */
    @Test
    fun `a streak whose last day is too old reads as zero without any write`() = runTest {
        val challenge = (repository.startCustomStreakChallenge(4, "Streak") as StartChallengeResult.Started).challenge

        // Fly two days that end well before the clock's "today" (DAY_3).
        val staleRepo = LocalChallengeRepository(
            dao, userProfileDao, airportRepository,
            Clock.fixed(Instant.ofEpochMilli(DAY_1), ZONE)
        )
        staleRepo.creditEligibleFlight("DST", 500.0, DAY_1 - 24L * 60 * 60 * 1000)
        staleRepo.creditEligibleFlight("DST", 500.0, DAY_1)

        // The row itself still records the run that happened.
        assertEquals(2, dao.getById(challenge.id)!!.streakDays)

        // Read through a clock two days later and the run is over.
        val laterRepo = LocalChallengeRepository(
            dao, userProfileDao, airportRepository,
            Clock.fixed(Instant.ofEpochMilli(DAY_5), ZONE)
        )
        assertEquals(0, laterRepo.getChallenge(challenge.id)!!.streakDays)
    }

    @Test
    fun `a streak flown yesterday is still alive`() = runTest {
        val challenge = (repository.startCustomStreakChallenge(4, "Streak") as StartChallengeResult.Started).challenge

        repository.creditEligibleFlight("DST", 500.0, DAY_1)
        repository.creditEligibleFlight("DST", 500.0, DAY_2)

        // TEST_CLOCK is DAY_3, so DAY_2 is yesterday - there is still time to extend it today.
        assertEquals(2, repository.getChallenge(challenge.id)!!.streakDays)
    }

    // ── predefined routes ────────────────────────────────────────────────────────────────
    // The submode that exists because the free-form formula above cannot express a circuit: with
    // start == end it reports 1f before a single leg is flown. These use PACIFIC_RIM
    // (SIN-BKK-HKG-ICN-NRT-SIN), which is exactly that shape - five legs, first waypoint == last.
    // Note none of them need airports in the fake repository: leg counting never resolves
    // coordinates, which is the whole reason a circuit works.

    /**
     * Inserted straight through the DAO rather than started from a catalog template, because
     * `CuratedChallengeCatalog` is deliberately empty while the curated set is re-authored - and
     * these tests are about how an itinerary *advances*, which is true whether or not any template
     * currently offers one. [everyCuratedPredefinedTemplateSeedsLegZero] covers the start path
     * against whatever the catalog holds.
     */
    private suspend fun startPacificRim(): Challenge {
        val route = PredefinedRouteCatalog.PACIFIC_RIM
        val id = dao.insert(
            Challenge(
                userId = 1,
                type = ChallengeType.ROUTE,
                source = com.silas270.blocktime.data.model.ChallengeSource.CURATED,
                name = "Pacific Rim",
                originIata = route.waypoints.first(),
                destIata = route.waypoints.last(),
                positionIata = route.waypoints.first(),
                predefinedRouteId = route.id
            )
        )
        return dao.getById(id.toInt())!!
    }

    /**
     * The curated start path, expressed as a property of the catalog rather than of one hardcoded
     * template: every curated ROUTE template that names a predefined itinerary must instantiate at
     * leg zero, on that itinerary's own first waypoint.
     *
     * Vacuous while the catalog is empty, and that is the point - it starts guarding the moment the
     * first predefined route is authored, instead of being a test someone has to remember to write
     * then.
     */
    @Test
    fun everyCuratedPredefinedTemplateSeedsLegZero() = runTest {
        val templates = CuratedChallengeCatalog.ALL.filter { it.predefinedRouteId != null }
        for (template in templates) {
            val route = PredefinedRouteCatalog.find(template.predefinedRouteId!!)!!
            val started = repository.startCuratedChallenge(template.catalogId)
            val challenge = (started as StartChallengeResult.Started).challenge

            assertEquals(route.id, challenge.predefinedRouteId)
            assertEquals(0, challenge.legIndex)
            assertEquals(route.waypoints.first(), challenge.positionIata)
            assertEquals(route.waypoints.first(), challenge.originIata)
            assertEquals(route.waypoints.last(), challenge.destIata)
            assertEquals(0f, challenge.progressFraction(), 0.001f)
            assertEquals(ChallengeStatus.ACTIVE, challenge.status)

            repository.abandonChallenge(challenge.id) // keep the cap clear for the next template
        }
    }

    @Test
    fun `a circuit is not complete at leg zero even though its origin is its destination`() = runTest {
        val challenge = startPacificRim()

        // The exact case the free-form proxy gets wrong - destIata is already the position.
        assertEquals(0f, challenge.progressFraction(), 0.001f)
        assertEquals(ChallengeStatus.ACTIVE, challenge.status)
    }

    @Test
    fun `flying the itinerary's next waypoint advances one leg`() = runTest {
        val challenge = startPacificRim()

        val advanced = repository.advanceRouteChallenge(challenge.id, "BKK")!!

        assertEquals(1, advanced.legIndex)
        assertEquals("BKK", advanced.positionIata)
        // Distance-weighted, not 1/5: SIN-BKK is 1,409 km of the itinerary's 11,770.
        val route = PredefinedRouteCatalog.PACIFIC_RIM
        assertEquals((1409.0 / route.totalDistanceKm).toFloat(), advanced.progressFraction(), 0.001f)
        assertEquals(ChallengeStatus.ACTIVE, advanced.status)
    }

    @Test
    fun `landing somewhere that is not the next waypoint leaves the itinerary untouched`() = runTest {
        val challenge = startPacificRim()

        // A real airport on the itinerary, just not the leg that is due - "somewhere further along"
        // must not count any more than somewhere off-route does.
        val unchanged = repository.advanceRouteChallenge(challenge.id, "NRT")!!

        assertEquals(0, unchanged.legIndex)
        assertEquals("SIN", unchanged.positionIata)
        assertEquals(0f, unchanged.progressFraction(), 0.001f)
    }

    @Test
    fun `landing back at the origin on the final leg completes the circuit`() = runTest {
        val challenge = startPacificRim()

        listOf("BKK", "HKG", "ICN", "NRT").forEach { repository.advanceRouteChallenge(challenge.id, it) }
        val beforeFinal = repository.getChallenge(challenge.id)!!
        assertEquals(ChallengeStatus.ACTIVE, beforeFinal.status)
        // Four legs down is 6,421 of 11,770 km - just past half the route, not the 80% a leg count
        // would have claimed, because the flight home is the longest one in it.
        val route = PredefinedRouteCatalog.PACIFIC_RIM
        assertEquals((6421.0 / route.totalDistanceKm).toFloat(), beforeFinal.progressFraction(), 0.001f)
        assertTrue("the final leg must still be most of the remaining route", beforeFinal.progressFraction() < 0.6f)

        val completed = repository.advanceRouteChallenge(challenge.id, "SIN")!!

        assertEquals(5, completed.legIndex)
        assertEquals(1f, completed.progressFraction(), 0.001f)
        assertEquals(ChallengeStatus.COMPLETED, completed.status)
        assertNotNull(completed.completedAt)
    }

    @Test
    fun `a predefined route is never advanced by passive crediting`() = runTest {
        val challenge = startPacificRim()

        // Same guarantee free-form routes have: only a session scoped to this challenge moves it,
        // so a Story Mode flight that happens to land on the next waypoint changes nothing.
        repository.creditEligibleFlight("BKK", 5000.0, DAY_1)

        assertEquals(0, repository.getChallenge(challenge.id)!!.legIndex)
    }
}
