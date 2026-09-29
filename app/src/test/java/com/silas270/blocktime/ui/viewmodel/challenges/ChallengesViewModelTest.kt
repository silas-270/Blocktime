package com.silas270.blocktime.ui.viewmodel.challenges

import com.silas270.blocktime.data.model.AchievementBoard
import com.silas270.blocktime.data.model.Challenge
import com.silas270.blocktime.data.model.ChallengeSource
import com.silas270.blocktime.data.model.ChallengeStatus
import com.silas270.blocktime.data.model.ChallengeType
import com.silas270.blocktime.data.model.FlightHighlights
import com.silas270.blocktime.data.model.FlightLog
import com.silas270.blocktime.data.model.FlightMode
import com.silas270.blocktime.data.model.FlightStats
import com.silas270.blocktime.data.model.RoomState
import com.silas270.blocktime.data.model.UserProfile
import com.silas270.blocktime.data.model.VisitedGeography
import com.silas270.blocktime.data.network.ServerReachability
import com.silas270.blocktime.data.network.ServerState
import com.silas270.blocktime.data.network.room.NoRoomApi
import com.silas270.blocktime.data.network.room.RoomResult
import com.silas270.blocktime.data.repository.AchievementsRepository
import com.silas270.blocktime.data.repository.ChallengeRepository
import com.silas270.blocktime.data.repository.FlightLogRepository
import com.silas270.blocktime.data.repository.JoinResult
import com.silas270.blocktime.data.repository.PausedFlightStore
import com.silas270.blocktime.data.repository.PilotProgressRepository
import com.silas270.blocktime.data.repository.PreferencesRepository
import com.silas270.blocktime.data.repository.ShareResult
import com.silas270.blocktime.data.repository.SharedChallengeSyncer
import com.silas270.blocktime.data.repository.StartChallengeResult
import com.silas270.blocktime.data.repository.SyncSummary
import com.silas270.blocktime.data.repository.UserRepository
import com.silas270.blocktime.domain.MergeResult
import com.silas270.blocktime.testutil.FakeAirportRepository
import com.silas270.blocktime.testutil.FakeSharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [ChallengesViewModel]'s presentation queue and sharing actions, with the real ViewModel on a
 * test main dispatcher (docs/shared-challenges.md "Presentation", plan B8). The queue is fed by
 * a fake repository whose slot flow the test drives; `celebrate` must persist before it
 * dequeues, which the fake observes by reading the queue from inside `markCelebrated`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChallengesViewModelTest {

    /** The slot rows as a flow the test can move, plus a record of what was persisted. */
    private class FakeChallengeRepository : ChallengeRepository {
        val slots = MutableStateFlow<List<Challenge>>(emptyList())
        val celebrated = mutableListOf<Int>()
        val dismissed = mutableListOf<Int>()
        var onMarkCelebrated: () -> Unit = {}
        var joinAnswer: JoinResult = JoinResult.NotFound
        /** The pilot's own rows by room code, for [findByRoomCode]. */
        val ownRooms = mutableMapOf<String, Challenge>()
        /** How often the server look-up ran; the real repository calls RoomApi there. */
        var lookUps = 0

        override suspend fun listActiveChallenges(): List<Challenge> = slots.value.filter { it.status == ChallengeStatus.ACTIVE }
        override fun listActiveChallengesFlow(): Flow<List<Challenge>> = slots
        override fun listSlotDisplayChallengesFlow(): Flow<List<Challenge>> = slots
        override suspend fun getChallenge(id: Int): Challenge? = slots.value.firstOrNull { it.id == id }
        override suspend fun listCompletedChallenges(): List<Challenge> = emptyList()
        override suspend fun markCelebrated(id: Int) {
            onMarkCelebrated()
            celebrated += id
            slots.update { rows -> rows.filterNot { it.id == id } }
        }
        override suspend fun startCuratedChallenge(catalogId: String): StartChallengeResult = StartChallengeResult.UnknownTemplate
        override suspend fun startCustomRouteChallenge(originIata: String, destIata: String, name: String): StartChallengeResult = throw NotImplementedError()
        override suspend fun startCustomDistanceChallenge(targetDistanceKm: Double, name: String): StartChallengeResult = throw NotImplementedError()
        override suspend fun startCustomStreakChallenge(targetDays: Int, name: String): StartChallengeResult = throw NotImplementedError()
        override suspend fun abandonChallenge(id: Int) = Unit
        override suspend fun advanceRouteChallenge(challengeId: Int, newPositionIata: String): Challenge? = null
        override fun pausedFlightStore(challengeId: Int): PausedFlightStore = throw NotImplementedError()
        override suspend fun creditEligibleFlight(destIata: String, distanceKm: Double, completedAt: Long) = Unit
        override suspend fun shareChallenge(id: Int): ShareResult = ShareResult.Unavailable(RoomResult.Unreachable)
        override suspend fun lookUpRoom(code: String): RoomResult<RoomState> {
            lookUps++
            return RoomResult.NotFound
        }
        override suspend fun findByRoomCode(code: String): Challenge? = ownRooms[code.trim().uppercase()]
        override suspend fun joinRoom(code: String): JoinResult = joinAnswer
        override suspend fun listSyncableChallenges(): List<Challenge> = emptyList()
        override suspend fun applyRoomState(id: Int, room: RoomState): MergeResult? = null
        override suspend fun markRoomGone(id: Int) = Unit
        override suspend fun confirmSynced(id: Int, generation: Long) = Unit
        override suspend fun hasPendingPresentation(): Boolean = false
        override suspend fun dismissFailed(id: Int) {
            dismissed += id
            slots.update { rows -> rows.filterNot { it.id == id } }
        }
    }

    private val users = object : UserRepository {
        private val profile = MutableStateFlow<UserProfile?>(null)
        override fun getProfileFlow(): Flow<UserProfile?> = profile
        override suspend fun getProfile(): UserProfile? = profile.value
        override suspend fun createProfile(username: String, homeAirportIata: String) = throw NotImplementedError()
        override suspend fun updateUsername(username: String) = Unit
        override suspend fun updateHomeAirport(iata: String) = Unit
    }

    private val flightLogs = object : FlightLogRepository {
        override suspend fun logFlight(flightNumber: String, originIata: String, destIata: String, durationMin: Int, distanceKm: Double, mode: FlightMode): FlightLog =
            throw NotImplementedError()
        override fun getFlightHistoryFlow(): Flow<List<FlightLog>> = MutableStateFlow(emptyList())
        override suspend fun getFlightHistory(): List<FlightLog> = emptyList()
        override suspend fun getRecentFlights(limit: Int): List<FlightLog> = emptyList()
        override suspend fun getFlightStats(homeAirportIata: String?) = FlightStats()
        override suspend fun getFlightHighlights() = FlightHighlights()
    }

    private val achievements = object : AchievementsRepository {
        override suspend fun evaluateBoard(geo: VisitedGeography, history: List<FlightLog>) = AchievementBoard(emptyList(), emptyList(), emptyList())
        override suspend fun loadBoard() = AchievementBoard(emptyList(), emptyList(), emptyList())
    }

    private val repository = FakeChallengeRepository()
    private val prefs = PreferencesRepository(FakeSharedPreferences())
    private val airports = FakeAirportRepository(emptyMap())

    @Before
    fun setMain() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun resetMain() {
        Dispatchers.resetMain()
    }

    private fun TestScope.reachability() =
        ServerReachability(false, MutableStateFlow(true), prefs, { false }, backgroundScope)

    private fun TestScope.syncer(reachability: ServerReachability = reachability()) =
        SharedChallengeSyncer(repository, users, prefs, NoRoomApi, reachability, backgroundScope)

    private fun TestScope.viewModel(syncer: SharedChallengeSyncer = syncer()): ChallengesViewModel {
        val progress = PilotProgressRepository(users, flightLogs, airports, achievements, backgroundScope)
        val vm = ChallengesViewModel(repository, airports, progress, prefs, syncer, reachability())
        // The queue is shared WhileSubscribed, as on the screen; keep one collector on it.
        backgroundScope.launch { vm.celebrationQueue.collect {} }
        return vm
    }

    private fun row(id: Int, status: ChallengeStatus = ChallengeStatus.COMPLETED, celebrated: Boolean = false) = Challenge(
        id = id, userId = 1, type = ChallengeType.DISTANCE, source = ChallengeSource.CUSTOM, name = "C$id",
        status = status, celebrated = celebrated, targetDistanceKm = 100.0
    )

    private val StateFlow<List<Challenge>>.ids: List<Int> get() = value.map { it.id }

    @Test
    fun `the queue holds the unpresented terminal rows in slot order`() = runTest(UnconfinedTestDispatcher()) {
        repository.slots.value = listOf(row(1, ChallengeStatus.ACTIVE), row(2), row(3, ChallengeStatus.FAILED))

        val vm = viewModel()

        assertEquals(listOf(2, 3), vm.celebrationQueue.ids)
    }

    @Test
    fun `celebrate persists before it dequeues, and the row is not queued again`() = runTest(UnconfinedTestDispatcher()) {
        repository.slots.value = listOf(row(2), row(5))
        val vm = viewModel()
        var queueWhilePersisting: List<Int>? = null
        repository.onMarkCelebrated = { queueWhilePersisting = vm.celebrationQueue.ids }

        vm.celebrate(2)

        assertEquals(listOf(2, 5), queueWhilePersisting)
        assertEquals(listOf(2), repository.celebrated)
        assertEquals(listOf(5), vm.celebrationQueue.ids)
        // A later emission that still carries the row (Room has not caught up yet) does not
        // re-queue it: it was seen.
        repository.slots.value = listOf(row(2), row(5))
        assertEquals(listOf(5), vm.celebrationQueue.ids)
    }

    @Test
    fun `a completion that a sync brings in while the screen is open is appended`() = runTest(UnconfinedTestDispatcher()) {
        repository.slots.value = listOf(row(1), row(4, ChallengeStatus.ACTIVE))
        val vm = viewModel()
        assertEquals(listOf(1), vm.celebrationQueue.ids)

        repository.slots.value = listOf(row(1), row(4, ChallengeStatus.COMPLETED))

        assertEquals(listOf(1, 4), vm.celebrationQueue.ids)
    }

    @Test
    fun `the presented row is resolved from the latest emission`() = runTest(UnconfinedTestDispatcher()) {
        repository.slots.value = listOf(row(1))
        val vm = viewModel()

        repository.slots.value = listOf(row(1).copy(name = "corrected"))

        assertEquals("corrected", vm.celebrationQueue.value.single().name)
    }

    @Test
    fun `dismissFailed deletes through the repository and dequeues`() = runTest(UnconfinedTestDispatcher()) {
        repository.slots.value = listOf(row(3, ChallengeStatus.FAILED), row(6))
        val vm = viewModel()

        vm.dismissFailed(3)

        assertEquals(listOf(3), repository.dismissed)
        assertEquals(listOf(6), vm.celebrationQueue.ids)
    }

    @Test
    fun `Y2 opening the screen asks for a sync`() = runTest(UnconfinedTestDispatcher()) {
        val syncer = syncer()

        viewModel(syncer)

        assertEquals(SyncSummary.Skipped(ServerState.NOT_CONFIGURED), syncer.lastSummary.value)
    }

    @Test
    fun `J4 a join is published as a started challenge`() = runTest(UnconfinedTestDispatcher()) {
        val joined = row(9, ChallengeStatus.ACTIVE).copy(type = ChallengeType.ROUTE, roomCode = "ROOM42")
        repository.joinAnswer = JoinResult.Joined(joined)
        val vm = viewModel()

        vm.joinRoom("room42")

        assertEquals(StartChallengeResult.Started(joined), vm.startResult.value)
        assertEquals(9, vm.focusedChallengeId.value)
        assertEquals(RoomLookupState.Idle, vm.roomLookup.value)
    }

    @Test
    fun `J5 a full cap after a join reuses the cap modal`() = runTest(UnconfinedTestDispatcher()) {
        repository.joinAnswer = JoinResult.CapReached
        val vm = viewModel()

        vm.joinRoom("ROOM42")

        assertEquals(StartChallengeResult.CapReached, vm.startResult.value)
    }

    @Test
    fun `J3 a refused join stays on the preview with its reason`() = runTest(UnconfinedTestDispatcher()) {
        val vm = viewModel()

        vm.joinRoom("NOPE00")
        assertEquals(RoomLookupState.Error(JoinResult.NotFound), vm.roomLookup.value)

        vm.lookUpRoom("NOPE00")
        assertEquals(RoomLookupState.Error(JoinResult.NotFound), vm.roomLookup.value)
        assertTrue(vm.startResult.value == null)
    }

    @Test
    fun `J6a looking up an own active room opens its slot without asking the server`() = runTest(UnconfinedTestDispatcher()) {
        repository.ownRooms["ROOM42"] = row(7, ChallengeStatus.ACTIVE).copy(roomCode = "ROOM42")
        val vm = viewModel()

        vm.lookUpRoom("room42")

        assertEquals(RoomLookupState.AlreadyJoined(7), vm.roomLookup.value)
        assertEquals(0, repository.lookUps)
    }

    @Test
    fun `J6a a join that finds an own active row opens its slot`() = runTest(UnconfinedTestDispatcher()) {
        repository.joinAnswer = JoinResult.AlreadyJoined(7)
        val vm = viewModel()

        vm.joinRoom("ROOM42")

        assertEquals(RoomLookupState.AlreadyJoined(7), vm.roomLookup.value)
        assertTrue(vm.startResult.value == null)
    }

    @Test
    fun `J6b looking up an own completed room is refused without asking the server`() = runTest(UnconfinedTestDispatcher()) {
        repository.ownRooms["ROOM42"] = row(7, ChallengeStatus.COMPLETED).copy(roomCode = "ROOM42")
        val vm = viewModel()

        vm.lookUpRoom("ROOM42")

        assertEquals(RoomLookupState.Error(JoinResult.AlreadyFinished), vm.roomLookup.value)
        assertEquals(0, repository.lookUps)
    }

    @Test
    fun `S6 an unavailable server reports a share error`() = runTest(UnconfinedTestDispatcher()) {
        val vm = viewModel()

        vm.shareChallenge(1)

        assertEquals(ShareUiState.Error("Server not reachable, try again"), vm.shareState.value)
        vm.clearShareState()
        assertEquals(ShareUiState.Idle, vm.shareState.value)
    }
}
