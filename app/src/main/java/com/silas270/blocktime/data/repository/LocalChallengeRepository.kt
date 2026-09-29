package com.silas270.blocktime.data.repository

import com.silas270.blocktime.data.local.ChallengeDao
import com.silas270.blocktime.data.local.UserProfileDao
import com.silas270.blocktime.data.local.requireProfileId
import com.silas270.blocktime.data.model.Airport
import com.silas270.blocktime.data.model.Challenge
import com.silas270.blocktime.data.model.ChallengeProgress
import com.silas270.blocktime.data.model.ChallengeSource
import com.silas270.blocktime.data.model.ChallengeStatus
import com.silas270.blocktime.data.model.ChallengeType
import com.silas270.blocktime.data.model.CuratedChallengeCatalog
import com.silas270.blocktime.data.model.CuratedChallengeSets
import com.silas270.blocktime.data.model.PausedFlight
import com.silas270.blocktime.data.model.PredefinedRoute
import com.silas270.blocktime.data.model.PredefinedRouteCatalog
import com.silas270.blocktime.data.model.predefinedRoute
import com.silas270.blocktime.data.model.SetMemberKind
import com.silas270.blocktime.data.model.withSetDefinitionResolved
import com.silas270.blocktime.data.model.withStreakEvaluatedAt
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Clock
import java.time.Instant
import java.time.LocalDate

/** Shared cap across all types and both sources (curated+custom) - challenges.md's
 *  "Active-challenge cap". */
const val MAX_ACTIVE_CHALLENGES = 3

/**
 * [clock] is the pilot's own calendar, and it is a constructor parameter purely so tests can fix
 * it - a streak that decays with the date cannot be tested against an ambient
 * `System.currentTimeMillis()`. Same reasoning as
 * [HomeBaseCooldown][com.silas270.blocktime.data.model.HomeBaseCooldown] taking `now` explicitly,
 * and the opposite of `AchievementProgress`'s inline `Calendar.getInstance()`.
 */
class LocalChallengeRepository(
    private val challengeDao: ChallengeDao,
    private val userProfileDao: UserProfileDao,
    private val airportRepository: AirportRepository,
    private val clock: Clock = Clock.systemDefaultZone()
) : ChallengeRepository {

    /**
     * Serialises every write that is a read-modify-write, or a check-then-act, of the
     * `challenges` table.
     *
     * The scoped DAO updates (`updatePausedFlight`, `updateRouteProgress`) make two writers of
     * *different* concerns unable to revert each other. This covers the other half: two writers of
     * the *same* concern, where the read and the write are separate statements and an interleaving
     * still loses one of them. A single landing can reach here three times in a row
     * ([advanceRouteChallenge] then [creditEligibleFlight]'s distance/set/streak passes), and the
     * cap check in the `start*` calls is a count-then-insert that two taps can both pass.
     *
     * Safe as a plain in-process lock because this repository is a singleton constructed once in
     * `CesiumGameActivity` and is the only writer of the table. It is **not reentrant** - every
     * private helper below is deliberately lock-free, and only the public entry points take it.
     */
    private val writeMutex = Mutex()

    /**
     * Every read of an active challenge goes through here.
     *
     * A STREAK row records the run as of the last day flown, and nothing runs at midnight to
     * notice when that run dies - so resolving it against today has to happen somewhere, and
     * doing it once at the database boundary means `progressFraction()`, the Hub card, the slot
     * row and `resolveLandingOutcome` are all handed an already-correct row and need no clock of
     * their own. The stored row catches up on the next credited flight.
     */
    private fun List<Challenge>.withStreaksEvaluated(): List<Challenge> {
        val today = LocalDate.now(clock)
        return map { it.withStreakEvaluatedAt(today).withSetDefinitionResolved() }
    }

    override suspend fun listActiveChallenges(): List<Challenge> =
        challengeDao.getByStatus(userProfileDao.requireProfileId(), ChallengeStatus.ACTIVE)
            .withStreaksEvaluated()

    /**
     * The user lookup happens when the flow is *collected*, not when it is constructed.
     *
     * It used to be a `runBlocking` in the function body - and `ChallengesViewModel` calls this
     * from a property initialiser, which runs on the main thread while the screen is being
     * composed. So opening Challenges blocked the main thread on a Room query every single time.
     * Deferring it into the flow moves that query onto whatever dispatcher collects, which is
     * always a background one.
     */
    override fun listActiveChallengesFlow(): Flow<List<Challenge>> = flow {
        val userId = userProfileDao.requireProfileId()
        emitAll(challengeDao.getByStatusFlow(userId, ChallengeStatus.ACTIVE).map { it.withStreaksEvaluated() })
    }

    /** Same "collect-time user lookup" reasoning as [listActiveChallengesFlow] above. */
    override fun listSlotDisplayChallengesFlow(): Flow<List<Challenge>> = flow {
        val userId = userProfileDao.requireProfileId()
        emitAll(challengeDao.getSlotDisplayFlow(userId).map { it.withStreaksEvaluated() })
    }

    override suspend fun getChallenge(id: Int): Challenge? =
        challengeDao.getById(id)?.withStreakEvaluatedAt(LocalDate.now(clock))?.withSetDefinitionResolved()

    override suspend fun listCompletedChallenges(): List<Challenge> =
        challengeDao.getCelebratedCompletedOrderedByCompletedAt(userProfileDao.requireProfileId())

    override suspend fun markCelebrated(id: Int) = writeMutex.withLock {
        challengeDao.markCelebrated(id)
    }

    private suspend fun hasCapSlot(userId: Int): Boolean =
        challengeDao.countOccupyingSlots(userId) < MAX_ACTIVE_CHALLENGES

    override suspend fun startCuratedChallenge(catalogId: String): StartChallengeResult = writeMutex.withLock {
        val template = CuratedChallengeCatalog.find(catalogId) ?: return StartChallengeResult.UnknownTemplate
        val userId = userProfileDao.requireProfileId()
        if (!hasCapSlot(userId)) return StartChallengeResult.CapReached

        val challenge = when (template.type) {
            ChallengeType.ROUTE -> {
                // A curated Route template is either a free-form endpoint pair or a predefined
                // itinerary. Both produce the same row shape - the endpoint fields stay populated
                // for a predefined route too (first/last waypoint), so nothing downstream needs to
                // know which kind it got unless it is scoring or advancing it.
                val predefined = template.predefinedRouteId?.let {
                    PredefinedRouteCatalog.find(it) ?: return StartChallengeResult.UnknownTemplate
                }
                Challenge(
                    userId = userId,
                    type = ChallengeType.ROUTE,
                    source = ChallengeSource.CURATED,
                    name = template.name,
                    description = template.description,
                    iconName = template.iconName,
                    originIata = predefined?.waypoints?.first() ?: template.originIata,
                    destIata = predefined?.waypoints?.last() ?: template.destIata,
                    positionIata = predefined?.waypoints?.first() ?: template.originIata,
                    routeProgressFraction = 0f,
                    predefinedRouteId = predefined?.id,
                    legIndex = 0
                )
            }
            ChallengeType.SET_COMPLETION -> {
                val def = template.setDefinition ?: return StartChallengeResult.UnknownTemplate
                Challenge(
                    userId = userId,
                    type = ChallengeType.SET_COMPLETION,
                    source = ChallengeSource.CURATED,
                    name = template.name,
                    description = template.description,
                    iconName = template.iconName,
                    setCatalogId = def.catalogId,
                    setMemberKind = def.memberKind,
                    setTotalMembers = def.members.size,
                    // Starts fresh at zero, every instance - never seeded from Story Mode's
                    // visited-set. See challenges.md#isolation.
                    visitedSetMembers = emptySet()
                )
            }
            ChallengeType.DISTANCE -> Challenge(
                userId = userId,
                type = ChallengeType.DISTANCE,
                source = ChallengeSource.CURATED,
                name = template.name,
                description = template.description,
                iconName = template.iconName,
                targetDistanceKm = template.targetDistanceKm,
                cumulativeDistanceKm = 0.0
            )
            ChallengeType.STREAK -> Challenge(
                userId = userId,
                type = ChallengeType.STREAK,
                source = ChallengeSource.CURATED,
                name = template.name,
                description = template.description,
                iconName = template.iconName,
                targetDays = template.targetDays ?: return StartChallengeResult.UnknownTemplate
            )
        }
        val id = challengeDao.insert(challenge)
        return StartChallengeResult.Started(challenge.copy(id = id.toInt()))
    }

    override suspend fun startCustomRouteChallenge(originIata: String, destIata: String, name: String): StartChallengeResult = writeMutex.withLock {
        val userId = userProfileDao.requireProfileId()
        if (!hasCapSlot(userId)) return StartChallengeResult.CapReached
        val challenge = Challenge(
            userId = userId,
            type = ChallengeType.ROUTE,
            source = ChallengeSource.CUSTOM,
            name = name,
            originIata = originIata,
            destIata = destIata,
            positionIata = originIata,
            routeProgressFraction = 0f
        )
        val id = challengeDao.insert(challenge)
        return StartChallengeResult.Started(challenge.copy(id = id.toInt()))
    }

    override suspend fun startCustomDistanceChallenge(targetDistanceKm: Double, name: String): StartChallengeResult = writeMutex.withLock {
        val userId = userProfileDao.requireProfileId()
        if (!hasCapSlot(userId)) return StartChallengeResult.CapReached
        val challenge = Challenge(
            userId = userId,
            type = ChallengeType.DISTANCE,
            source = ChallengeSource.CUSTOM,
            name = name,
            targetDistanceKm = targetDistanceKm,
            cumulativeDistanceKm = 0.0
        )
        val id = challengeDao.insert(challenge)
        return StartChallengeResult.Started(challenge.copy(id = id.toInt()))
    }

    override suspend fun startCustomStreakChallenge(targetDays: Int, name: String): StartChallengeResult = writeMutex.withLock {
        val userId = userProfileDao.requireProfileId()
        if (!hasCapSlot(userId)) return StartChallengeResult.CapReached
        val challenge = Challenge(
            userId = userId,
            type = ChallengeType.STREAK,
            source = ChallengeSource.CUSTOM,
            name = name,
            targetDays = targetDays
        )
        val id = challengeDao.insert(challenge)
        return StartChallengeResult.Started(challenge.copy(id = id.toInt()))
    }

    override suspend fun abandonChallenge(id: Int) = writeMutex.withLock {
        // Deletes the row entirely - no ABANDONED status - so the cap slot frees immediately and
        // retrying later is a fresh instance, per challenges.md's "Abandon, not reset".
        challengeDao.deleteById(id)
    }

    override suspend fun advanceRouteChallenge(challengeId: Int, newPositionIata: String): Challenge? = writeMutex.withLock {
        val challenge = challengeDao.getById(challengeId) ?: return null
        if (challenge.type != ChallengeType.ROUTE || challenge.status != ChallengeStatus.ACTIVE) return challenge

        // Predefined itineraries are scored by legs, so they never touch the geometric proxy
        // below - and, unlike a free-form route, they have an *expected* next airport.
        challenge.predefinedRoute()?.let { return advancePredefinedRoute(challenge, it, newPositionIata) }

        val originIata = challenge.originIata ?: return challenge
        val destIata = challenge.destIata ?: return challenge
        val origin = airportRepository.getAirportByIata(originIata) ?: return challenge
        val dest = airportRepository.getAirportByIata(destIata) ?: return challenge
        val current = airportRepository.getAirportByIata(newPositionIata) ?: return challenge

        val reachedDest = newPositionIata.equals(destIata, ignoreCase = true)
        val progress = if (reachedDest) {
            1f
        } else {
            ChallengeProgress.routeProgress(
                originLat = origin.lat, originLon = origin.lon,
                destLat = dest.lat, destLon = dest.lon,
                currentLat = current.lat, currentLon = current.lon
            )
        }

        val updated = challenge.copy(
            positionIata = newPositionIata,
            routeProgressFraction = progress,
            status = if (reachedDest) ChallengeStatus.COMPLETED else ChallengeStatus.ACTIVE,
            completedAt = if (reachedDest) System.currentTimeMillis() else null
        )
        persistRouteProgress(updated)
        return updated
    }

    /** Writes only the Route columns of [updated] - see [ChallengeDao.updateRouteProgress] for why
     *  this is not a whole-row write. Lock-free: both callers already hold [writeMutex]. */
    private suspend fun persistRouteProgress(updated: Challenge) {
        challengeDao.updateRouteProgress(
            id = updated.id,
            positionIata = updated.positionIata,
            routeProgressFraction = updated.routeProgressFraction,
            legIndex = updated.legIndex,
            status = updated.status,
            completedAt = updated.completedAt,
            // Unchanged for now: bumping it on a shared row is the sync stage's job.
            syncGeneration = updated.syncGeneration
        )
    }

    /**
     * The leg-counting half of [advanceRouteChallenge], for a challenge following a
     * [PredefinedRoute].
     *
     * Only the itinerary's *own* next waypoint advances it. A landing anywhere else is ignored
     * rather than scored, which is the difference that makes a circuit work: on
     * `LHR -> ... -> LHR`, landing at LHR completes the challenge on the last leg and does nothing
     * on the first, because what is being compared is a leg index, never a coordinate. The check
     * is defensive - a predefined session's destination is chosen by `resolveNextLeg`, not by the
     * pilot - but "the row decides what counts" is the property worth keeping locally true.
     *
     * `routeProgressFraction` is kept written even though [progressFraction] derives it directly:
     * it is the cached column the rest of the schema expects to be truthful, and a stale one would
     * be a lie waiting for the next reader that trusts it.
     */
    private suspend fun advancePredefinedRoute(
        challenge: Challenge,
        route: PredefinedRoute,
        newPositionIata: String
    ): Challenge {
        val expected = route.destOf(challenge.legIndex) ?: return challenge
        if (!newPositionIata.equals(expected, ignoreCase = true)) return challenge

        val newLegIndex = challenge.legIndex + 1
        val completed = newLegIndex >= route.legCount
        val updated = challenge.copy(
            positionIata = expected,
            legIndex = newLegIndex,
            routeProgressFraction = route.progressAt(newLegIndex),
            status = if (completed) ChallengeStatus.COMPLETED else ChallengeStatus.ACTIVE,
            completedAt = if (completed) System.currentTimeMillis() else null
        )
        persistRouteProgress(updated)
        return updated
    }

    /**
     * Both writes are a single scoped statement rather than a read-then-whole-row-write. That is
     * the point: the previous form read the row, copied it, and wrote every column back, so a
     * camera/elapsed-time save that straddled the landing's own write reverted the leg, position
     * and status the landing had just credited. A missing row is a silent no-op here, which is the
     * same outcome the `getById(...) ?: return` guard produced.
     */
    override fun pausedFlightStore(challengeId: Int): PausedFlightStore = object : PausedFlightStore {
        override suspend fun get(): PausedFlight? = challengeDao.getById(challengeId)?.pausedFlight

        override suspend fun save(flight: PausedFlight) =
            challengeDao.updatePausedFlight(challengeId, flight)

        override suspend fun clear() =
            challengeDao.updatePausedFlight(challengeId, null)
    }

    override suspend fun creditEligibleFlight(destIata: String, distanceKm: Double, completedAt: Long): Unit = writeMutex.withLock {
        val userId = userProfileDao.requireProfileId()
        val active = challengeDao.getByStatus(userId, ChallengeStatus.ACTIVE)
        if (active.isEmpty()) return
        val destAirport = airportRepository.getAirportByIata(destIata)

        for (challenge in active) {
            when (challenge.type) {
                ChallengeType.DISTANCE -> creditDistance(challenge, distanceKm)
                ChallengeType.SET_COMPLETION -> creditSetCompletion(challenge, destAirport)
                ChallengeType.STREAK -> creditStreak(challenge, completedAt)
                ChallengeType.ROUTE -> Unit // Route only advances via advanceRouteChallenge's own scoped session
            }
        }
    }

    /**
     * Note this reads [Challenge.streakDays] straight off the row rather than through
     * [withStreakEvaluatedAt] - and it has to, because the row is the record of what happened
     * while the resolved value is a view of it. The `else -> 1` branch below is what collapses a
     * dead run, and it produces the same answer either way: a stale run cannot be one day old, so
     * it can never match `day.minusDays(1)`.
     */
    private suspend fun creditStreak(challenge: Challenge, completedAt: Long) {
        val target = challenge.targetDays ?: return
        val day = Instant.ofEpochMilli(completedAt).atZone(clock.zone).toLocalDate()
        val lastDay = challenge.lastFlownDay?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

        // Already counted today. Return before writing - a second flight on the same day must not
        // advance the streak, and must not restamp completedAt either.
        if (lastDay == day) return

        val newStreak = if (lastDay == day.minusDays(1)) challenge.streakDays + 1 else 1
        val completed = newStreak >= target
        challengeDao.update(
            challenge.copy(
                streakDays = newStreak,
                lastFlownDay = day.toString(),
                status = if (completed) ChallengeStatus.COMPLETED else ChallengeStatus.ACTIVE,
                completedAt = if (completed) System.currentTimeMillis() else null
            )
        )
    }

    private suspend fun creditDistance(challenge: Challenge, distanceKm: Double) {
        val target = challenge.targetDistanceKm ?: return
        val newCumulative = challenge.cumulativeDistanceKm + distanceKm
        val completed = newCumulative >= target
        challengeDao.update(
            challenge.copy(
                cumulativeDistanceKm = newCumulative,
                status = if (completed) ChallengeStatus.COMPLETED else ChallengeStatus.ACTIVE,
                completedAt = if (completed) System.currentTimeMillis() else null
            )
        )
    }

    private suspend fun creditSetCompletion(challenge: Challenge, destAirport: Airport?) {
        val kind = challenge.setMemberKind ?: return
        // Look up the full member list from the curated catalog (rather than trusting only the
        // stored count) so "is this actually a relevant member" is checked against the real
        // definition, not just against whatever's already been credited.
        val definition = CuratedChallengeSets.find(challenge.setCatalogId ?: return) ?: return
        val memberValue = destAirport?.let { memberValueFor(kind, it) } ?: return
        val credited = challenge.visitedSetMembers.filterTo(LinkedHashSet()) { it in definition.members }
        val updatedMembers = if (memberValue in definition.members) credited + memberValue else credited
        // Judged as "every current member credited" rather than by count, and checked even when
        // this landing adds nothing new: a row whose definition shrank (Visit All Continents lost
        // Antarctica) may already hold every remaining member, and must complete on its next
        // eligible landing rather than stay stuck one unreachable member short forever.
        val completed = updatedMembers.containsAll(definition.members)
        if (updatedMembers == challenge.visitedSetMembers && !completed) return // nothing new
        challengeDao.update(
            challenge.copy(
                visitedSetMembers = updatedMembers,
                setTotalMembers = definition.members.size,
                status = if (completed) ChallengeStatus.COMPLETED else ChallengeStatus.ACTIVE,
                completedAt = if (completed) System.currentTimeMillis() else null
            )
        )
    }

    private fun memberValueFor(kind: SetMemberKind, airport: Airport): String = when (kind) {
        SetMemberKind.CONTINENT -> airport.continent
        SetMemberKind.COUNTRY -> airport.isoCountry
        SetMemberKind.IATA -> airport.iataCode
    }
}
