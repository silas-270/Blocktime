package com.silas270.blocktime.data.repository

import com.silas270.blocktime.data.local.ChallengeDao
import com.silas270.blocktime.data.local.UserProfileDao
import com.silas270.blocktime.data.local.requireProfile
import com.silas270.blocktime.data.local.requireProfileId
import com.silas270.blocktime.data.model.Airport
import com.silas270.blocktime.data.model.Challenge
import com.silas270.blocktime.data.model.ChallengeProgress
import com.silas270.blocktime.data.model.ChallengeSource
import com.silas270.blocktime.data.model.ChallengeStatus
import com.silas270.blocktime.data.model.ChallengeType
import com.silas270.blocktime.data.model.CuratedChallengeCatalog
import com.silas270.blocktime.data.model.CuratedChallengeSets
import com.silas270.blocktime.data.model.MAX_ROOM_PARTICIPANTS
import com.silas270.blocktime.data.model.PausedFlight
import com.silas270.blocktime.data.model.PredefinedRoute
import com.silas270.blocktime.data.model.PredefinedRouteCatalog
import com.silas270.blocktime.data.model.RoomDefinition
import com.silas270.blocktime.data.model.RoomState
import com.silas270.blocktime.data.model.RoomStateCache
import com.silas270.blocktime.data.model.SetMemberKind
import com.silas270.blocktime.data.model.SharedOutcome
import com.silas270.blocktime.data.model.UserProfile
import com.silas270.blocktime.data.model.nextFreeColorIndex
import com.silas270.blocktime.data.model.predefinedRoute
import com.silas270.blocktime.data.model.teamDistanceKm
import com.silas270.blocktime.data.model.teamVisitedMembers
import com.silas270.blocktime.data.model.toParticipantSnapshot
import com.silas270.blocktime.data.model.toRoomDefinition
import com.silas270.blocktime.data.model.withSetDefinitionResolved
import com.silas270.blocktime.data.model.withStreakEvaluatedAt
import com.silas270.blocktime.data.network.ServerReachability
import com.silas270.blocktime.data.network.room.NoRoomApi
import com.silas270.blocktime.data.network.room.RoomApi
import com.silas270.blocktime.data.network.room.RoomResult
import com.silas270.blocktime.data.network.room.bindCallerIdentity
import com.silas270.blocktime.domain.MergeResult
import com.silas270.blocktime.domain.mergeRoomIntoChallenge
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
 * The row a challenge starts as, for [definition]: the one builder behind `startCuratedChallenge`
 * and `joinRoom`, so a joined room produces exactly the row a started challenge would. Null when
 * this app version cannot resolve the definition (an unknown predefined route or set, or a
 * definition missing its target), which the callers report as an unknown template.
 *
 * Every instance starts fresh at zero, never seeded from Story Mode's visited set or anything
 * else (challenges.md#isolation): that is what makes a challenge repeatable, and what makes a
 * joiner's contribution their own.
 */
internal fun buildChallengeRow(definition: RoomDefinition, userId: Int): Challenge? = when (definition.type) {
    ChallengeType.ROUTE -> {
        // A Route is either a free-form endpoint pair or a predefined itinerary. Both produce
        // the same row shape - the endpoint fields stay populated for a predefined route too
        // (first/last waypoint), so nothing downstream needs to know which kind it got unless it
        // is scoring or advancing it.
        val predefined = definition.predefinedRouteId?.let { PredefinedRouteCatalog.find(it) }
        if (definition.predefinedRouteId != null && predefined == null) {
            null
        } else {
            val origin = predefined?.waypoints?.first() ?: definition.originIata
            val dest = predefined?.waypoints?.last() ?: definition.destIata
            if (origin == null || dest == null) {
                null
            } else {
                Challenge(
                    userId = userId,
                    type = ChallengeType.ROUTE,
                    source = definition.source,
                    name = definition.name,
                    description = definition.description,
                    iconName = definition.iconName,
                    originIata = origin,
                    destIata = dest,
                    positionIata = origin,
                    routeProgressFraction = 0f,
                    predefinedRouteId = predefined?.id,
                    legIndex = 0
                )
            }
        }
    }
    ChallengeType.SET_COMPLETION -> {
        val def = definition.setCatalogId?.let { CuratedChallengeSets.find(it) }
        if (def == null) {
            null
        } else {
            Challenge(
                userId = userId,
                type = ChallengeType.SET_COMPLETION,
                source = definition.source,
                name = definition.name,
                description = definition.description,
                iconName = definition.iconName,
                setCatalogId = def.catalogId,
                setMemberKind = def.memberKind,
                setTotalMembers = def.members.size,
                visitedSetMembers = emptySet()
            )
        }
    }
    ChallengeType.DISTANCE -> definition.targetDistanceKm?.let { target ->
        Challenge(
            userId = userId,
            type = ChallengeType.DISTANCE,
            source = definition.source,
            name = definition.name,
            description = definition.description,
            iconName = definition.iconName,
            targetDistanceKm = target,
            cumulativeDistanceKm = 0.0
        )
    }
    ChallengeType.STREAK -> definition.targetDays?.let { target ->
        Challenge(
            userId = userId,
            type = ChallengeType.STREAK,
            source = definition.source,
            name = definition.name,
            description = definition.description,
            iconName = definition.iconName,
            targetDays = target
        )
    }
}

/** Room codes are typed by hand, so case and surrounding whitespace are forgiven. */
internal fun normaliseRoomCode(code: String): String = code.trim().uppercase()

/**
 * [clock] is the pilot's own calendar, and it is a constructor parameter purely so tests can fix
 * it - a streak that decays with the date cannot be tested against an ambient
 * `System.currentTimeMillis()`. Same reasoning as
 * [HomeBaseCooldown][com.silas270.blocktime.data.model.HomeBaseCooldown] taking `now` explicitly,
 * and the opposite of `AchievementProgress`'s inline `Calendar.getInstance()`.
 *
 * The trailing three parameters are the sharing side (docs/shared-challenges.md). [roomApi]
 * defaults to [NoRoomApi], so a repository built without them behaves as a build without a
 * server: sharing and joining answer unreachable and nothing else changes. [serverReachability]
 * is told the outcome of every call when present. [preferencesRepository] holds the rooms still
 * to be left; without it a leave that could not be sent is dropped, which only a test of the solo
 * paths may accept.
 */
class LocalChallengeRepository(
    private val challengeDao: ChallengeDao,
    private val userProfileDao: UserProfileDao,
    private val airportRepository: AirportRepository,
    private val clock: Clock = Clock.systemDefaultZone(),
    private val roomApi: RoomApi = NoRoomApi,
    private val serverReachability: ServerReachability? = null,
    private val preferencesRepository: PreferencesRepository? = null
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
     *
     * **No network call ever runs under it.** The sharing operations read, call the server, and
     * only then take the lock to re-read and write; a round trip under the lock would stall every
     * landing credit behind the server's latency.
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

        val challenge = buildChallengeRow(template.toRoomDefinition(), userId)
            ?: return StartChallengeResult.UnknownTemplate
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
        //
        // A shared row also leaves its room, and leaving is one path online or not (A2, A3 in
        // docs/shared-challenges.md): the code is queued here, and the next sync sends the leave,
        // because the network must never run under this lock and a leave that fails while the
        // pilot is offline must not be lost with the row.
        val roomCode = challengeDao.getById(id)?.roomCode
        challengeDao.deleteById(id)
        if (roomCode != null) preferencesRepository?.addPendingRoomLeave(roomCode)
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

    /**
     * Writes only the Route columns of [updated] - see [ChallengeDao.updateRouteProgress] for why
     * this is not a whole-row write. Lock-free: both callers already hold [writeMutex].
     *
     * A credited leg on a shared row is a change to the pilot's own data, so the generation moves
     * in the same statement (L5, L6). An arrival on a shared race completes the row without an
     * outcome: the server's reply to the claim supplies it (a win, or a placement if someone was
     * faster), and until then the row is presented as won, which is the truth the pilot has.
     */
    private suspend fun persistRouteProgress(updated: Challenge) {
        challengeDao.updateRouteProgress(
            id = updated.id,
            positionIata = updated.positionIata,
            routeProgressFraction = updated.routeProgressFraction,
            legIndex = updated.legIndex,
            status = updated.status,
            completedAt = updated.completedAt,
            syncGeneration = updated.nextGeneration()
        )
    }

    /** The generation to write with a change to the row's own data: one up on a shared row,
     *  unchanged on a solo row, which has nothing to upload. */
    private fun Challenge.nextGeneration(): Long = if (roomCode != null) syncGeneration + 1 else syncGeneration

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
        val profile = userProfileDao.requireProfile()
        val active = challengeDao.getByStatus(profile.id, ChallengeStatus.ACTIVE)
        if (active.isEmpty()) return
        val destAirport = airportRepository.getAirportByIata(destIata)

        for (challenge in active) {
            when (challenge.type) {
                ChallengeType.DISTANCE -> creditDistance(challenge, distanceKm, profile.userCode)
                ChallengeType.SET_COMPLETION -> creditSetCompletion(challenge, destAirport, profile.userCode)
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
     *
     * A shared streak never completes here (L4). Completion and failure depend on the crew's
     * minimum, which only the merge knows, so the landing writes the streak columns and the
     * generation and leaves the status alone.
     */
    private suspend fun creditStreak(challenge: Challenge, completedAt: Long) {
        val target = challenge.targetDays ?: return
        val day = Instant.ofEpochMilli(completedAt).atZone(clock.zone).toLocalDate()
        val lastDay = challenge.lastFlownDay?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

        // Already counted today. Return before writing - a second flight on the same day must not
        // advance the streak, and must not restamp completedAt either.
        if (lastDay == day) return

        val newStreak = if (lastDay == day.minusDays(1)) challenge.streakDays + 1 else 1
        val completed = challenge.roomCode == null && newStreak >= target
        challengeDao.update(
            challenge.copy(
                streakDays = newStreak,
                lastFlownDay = day.toString(),
                status = if (completed) ChallengeStatus.COMPLETED else ChallengeStatus.ACTIVE,
                completedAt = if (completed) System.currentTimeMillis() else null,
                syncGeneration = challenge.nextGeneration()
            )
        )
    }

    /**
     * A shared pool is judged against the team's total, the pilot's own new kilometres plus the
     * cached others', not the pilot's number alone (L2). Otherwise a landing that fills the pot
     * would show "advanced" and complete only at the next sync. The completion is stamped as the
     * pilot's own; the claim goes with the landing sync, and a refused claim is corrected by the
     * merge before the row is presented.
     */
    private suspend fun creditDistance(challenge: Challenge, distanceKm: Double, selfCode: String) {
        val target = challenge.targetDistanceKm ?: return
        val credited = challenge.copy(
            cumulativeDistanceKm = challenge.cumulativeDistanceKm + distanceKm,
            syncGeneration = challenge.nextGeneration()
        )
        val reached = if (challenge.roomCode != null) credited.teamDistanceKm() else credited.cumulativeDistanceKm
        challengeDao.update(credited.completedIf(reached >= target, selfCode))
    }

    /** Same team rule as [creditDistance]: the union of the pilot's own members and the cached
     *  others' must cover the current definition (L2). */
    private suspend fun creditSetCompletion(challenge: Challenge, destAirport: Airport?, selfCode: String) {
        val kind = challenge.setMemberKind ?: return
        // Look up the full member list from the curated catalog (rather than trusting only the
        // stored count) so "is this actually a relevant member" is checked against the real
        // definition, not just against whatever's already been credited.
        val definition = CuratedChallengeSets.find(challenge.setCatalogId ?: return) ?: return
        val memberValue = destAirport?.let { memberValueFor(kind, it) } ?: return
        val credited = challenge.visitedSetMembers.filterTo(LinkedHashSet()) { it in definition.members }
        val updatedMembers = if (memberValue in definition.members) credited + memberValue else credited
        val updated = challenge.copy(
            visitedSetMembers = updatedMembers,
            setTotalMembers = definition.members.size,
            syncGeneration = challenge.nextGeneration()
        )
        // Judged as "every current member credited" rather than by count, and checked even when
        // this landing adds nothing new: a row whose definition shrank (Visit All Continents lost
        // Antarctica) may already hold every remaining member, and must complete on its next
        // eligible landing rather than stay stuck one unreachable member short forever.
        val covered = if (challenge.roomCode != null) updated.teamVisitedMembers() else updatedMembers
        val completed = covered.containsAll(definition.members)
        if (updatedMembers == challenge.visitedSetMembers && !completed) return // nothing new
        challengeDao.update(updated.completedIf(completed, selfCode))
    }

    /**
     * This row completed by the pilot's own landing, or unchanged. A shared row also records the
     * outcome as the pilot's own, so the outcome screen and the presentation can tell it from a
     * foreign completion without a profile (docs/shared-challenges.md "Landing", L2).
     */
    private fun Challenge.completedIf(completed: Boolean, selfCode: String): Challenge {
        if (!completed) return copy(status = ChallengeStatus.ACTIVE, completedAt = null)
        val at = System.currentTimeMillis()
        return copy(
            status = ChallengeStatus.COMPLETED,
            completedAt = at,
            sharedOutcome = if (roomCode != null) SharedOutcome.Completed(byUserCode = selfCode, bySelf = true, at = at) else sharedOutcome
        )
    }

    private fun memberValueFor(kind: SetMemberKind, airport: Airport): String = when (kind) {
        SetMemberKind.CONTINENT -> airport.continent
        SetMemberKind.COUNTRY -> airport.isoCountry
        SetMemberKind.IATA -> airport.iataCode
    }

    // ── Shared challenges (docs/shared-challenges.md) ───────────────────────────────────────

    /** Tells the reachability signal, when there is one, what a call answered. Only a server
     *  that did not answer counts as unreachable; a refusal is an answer. */
    private fun report(result: RoomResult<*>) {
        serverReachability?.report(result !is RoomResult.Unreachable)
    }

    /** The pilot's room secret. Blank only for a repository built without preferences (tests),
     *  whose api is never the HTTP one. */
    private fun roomSecret(): String = preferencesRepository?.getOrCreateRoomSecret() ?: ""

    /** The pilot's own snapshot of [this] row, as the server should see it. */
    private fun Challenge.ownSnapshot(profile: UserProfile, colorIndex: Int) =
        toParticipantSnapshot(profile.username, colorIndex, LocalDate.now(clock), clock.zone)
            .copy(userCode = profile.userCode)

    override suspend fun shareChallenge(id: Int): ShareResult {
        val profile = userProfileDao.requireProfile()
        val row = challengeDao.getById(id) ?: return ShareResult.NotEligible(ShareIneligibility.UNKNOWN)
        row.shareIneligibility()?.let { return ShareResult.NotEligible(it) }

        // The round trip, outside the lock. The creator is colour 0 by the protocol.
        roomApi.bindCallerIdentity(profile.userCode, roomSecret())
        val created = roomApi.createRoom(row.toRoomDefinition(), row.ownSnapshot(profile, colorIndex = 0))
        report(created)
        val room = when (created) {
            is RoomResult.Ok -> created.value
            else -> return ShareResult.Unavailable(created.asFailure())
        }

        // Re-read and re-check under the lock: a landing, an abandon or a second share can have
        // changed the row while the server was creating the room (S8). The room then has to be
        // left again, which is the syncer's job through the pending leaves, so leaving is one
        // path whether the server answers now or later.
        val linked: Challenge? = writeMutex.withLock {
            val fresh = challengeDao.getById(id)
            if (fresh == null || fresh.shareIneligibility() != null) {
                null
            } else {
                val cache = RoomStateCache(profile.userCode, room)
                challengeDao.updateRoomLink(id, room.code, cache)
                fresh.copy(roomCode = room.code, roomState = cache)
            }
        }
        if (linked == null) {
            preferencesRepository?.addPendingRoomLeave(room.code)
            val reason = challengeDao.getById(id)?.shareIneligibility() ?: ShareIneligibility.UNKNOWN
            return ShareResult.NotEligible(reason)
        }
        return ShareResult.Shared(room.code, linked)
    }

    override suspend fun lookUpRoom(code: String): RoomResult<RoomState> {
        val result = roomApi.getRoom(normaliseRoomCode(code))
        report(result)
        return result
    }

    override suspend fun findByRoomCode(code: String): Challenge? =
        challengeDao.getByRoomCode(userProfileDao.requireProfileId(), normaliseRoomCode(code))

    override suspend fun joinRoom(code: String): JoinResult {
        val roomCode = normaliseRoomCode(code)
        val profile = userProfileDao.requireProfile()

        // A code the pilot already has a row for needs no server: an active row is the one to
        // open (J6a), a finished one is final, because a room is joined once per pilot (J6b).
        challengeDao.getByRoomCode(profile.id, roomCode)?.let { existing ->
            return if (existing.status == ChallengeStatus.ACTIVE) JoinResult.AlreadyJoined(existing.id) else JoinResult.AlreadyFinished
        }

        roomApi.bindCallerIdentity(profile.userCode, roomSecret())
        val looked = roomApi.getRoom(roomCode)
        report(looked)
        val room = when (looked) {
            is RoomResult.Ok -> looked.value
            else -> return looked.asJoinFailure()
        }

        // What the server would refuse anyway, answered without the put so the preview and the
        // join agree; the server's own answer still wins if the room moved meanwhile.
        if (room.outcome != null) return JoinResult.RoomClosed
        if (room.definition.type == ChallengeType.ROUTE && room.participants.any { it.routeProgress > 0f || it.legIndex > 0 }) {
            return JoinResult.RaceLocked
        }
        if (room.participants.count { !it.left } >= MAX_ROOM_PARTICIPANTS) return JoinResult.RoomFull

        // Nothing has been sent yet, so an unknown definition needs no leave (J12).
        val template = buildChallengeRow(room.definition, profile.id) ?: return JoinResult.UnknownTemplate
        // A cheap pre-check; the one that counts runs under the lock after the put.
        if (!hasCapSlot(profile.id)) return JoinResult.CapReached

        // The put is the join on the server.
        val put = roomApi.putSnapshot(roomCode, template.ownSnapshot(profile, room.nextFreeColorIndex()), claim = null)
        report(put)
        val joined = when (put) {
            is RoomResult.Ok -> put.value
            else -> return put.asJoinFailure()
        }

        val inserted: JoinResult = writeMutex.withLock {
            // The unique (user, room) index would refuse a second row anyway; answering here keeps
            // two taps on JOIN from surfacing as a crash.
            challengeDao.getByRoomCode(profile.id, roomCode)?.let { return@withLock JoinResult.AlreadyJoined(it.id) }
            if (!hasCapSlot(profile.id)) {
                JoinResult.CapReached
            } else {
                val row = template.copy(roomCode = roomCode, roomState = RoomStateCache(profile.userCode, joined))
                val id = challengeDao.insert(row)
                JoinResult.Joined(row.copy(id = id.toInt()))
            }
        }
        when (inserted) {
            is JoinResult.Joined -> preferencesRepository?.removePendingRoomLeave(roomCode)
            // The server has the pilot but the cap is full after all (J5): leave at once, outside
            // the lock, and queue the leave if the server has gone away in the meantime.
            JoinResult.CapReached -> {
                val left = roomApi.leaveRoom(roomCode)
                report(left)
                if (left !is RoomResult.Ok && left != RoomResult.NotFound) preferencesRepository?.addPendingRoomLeave(roomCode)
            }
            else -> Unit
        }
        return inserted
    }

    override suspend fun listSyncableChallenges(): List<Challenge> =
        challengeDao.getSyncable(userProfileDao.requireProfileId())
            .filter { it.roomState?.room?.roomGone != true }
            // A presented completion stays only while the server has not confirmed it: its claim
            // is still owed, and the reply may correct the log (merge rule 2). Whether the cache
            // holds an outcome lives inside the JSON column, so this half of the rule is here.
            .filter { it.status == ChallengeStatus.ACTIVE || !it.celebrated || it.roomState?.room?.outcome == null }

    override suspend fun applyRoomState(id: Int, room: RoomState): MergeResult? = writeMutex.withLock {
        val fresh = challengeDao.getById(id) ?: return null
        val selfCode = userProfileDao.requireProfile().userCode
        val result = mergeRoomIntoChallenge(fresh, room, selfCode, LocalDate.now(clock), clock.millis(), clock.zone)
        val merged = result.challenge
        when {
            // Only the cache moved: the one column this writer owns in that case.
            !result.changed -> challengeDao.updateRoomState(id, merged.roomState)
            // A foreign completion ended a race with a paused leg: the leg goes in the same
            // statement as the status, so no camera save can slip it back in between.
            fresh.type == ChallengeType.ROUTE && fresh.pausedFlight != null && merged.pausedFlight == null ->
                challengeDao.updateSharedFieldsAndClearPausedFlight(id, merged.status, merged.completedAt, merged.roomState, merged.sharedOutcome)
            else ->
                challengeDao.updateSharedFields(id, merged.status, merged.completedAt, merged.roomState, merged.sharedOutcome)
        }
        result
    }

    override suspend fun markRoomGone(id: Int): Unit = writeMutex.withLock {
        val cache = challengeDao.getById(id)?.roomState ?: return
        challengeDao.updateRoomState(id, cache.copy(room = cache.room.copy(roomGone = true)))
    }

    override suspend fun confirmSynced(id: Int, generation: Long) =
        challengeDao.confirmSynced(id, generation)

    override suspend fun hasPendingPresentation(): Boolean =
        challengeDao.countPendingPresentation(userProfileDao.requireProfileId()) > 0

    override suspend fun dismissFailed(id: Int): Unit = writeMutex.withLock {
        val row = challengeDao.getById(id) ?: return
        if (row.status == ChallengeStatus.FAILED) challengeDao.deleteById(id)
    }
}
