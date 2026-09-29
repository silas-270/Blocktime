package com.silas270.blocktime.ui.viewmodel.challenges

import com.silas270.blocktime.data.model.Challenge
import com.silas270.blocktime.data.model.ChallengeSource
import com.silas270.blocktime.data.model.ChallengeStatus
import com.silas270.blocktime.data.model.ChallengeType
import org.junit.Assert.assertEquals
import org.junit.Test

/** [nextQueue], the presentation queue's one step (docs/shared-challenges.md "Presentation"). */
class CelebrationQueueTest {

    private fun row(id: Int, status: ChallengeStatus = ChallengeStatus.COMPLETED, celebrated: Boolean = false) = Challenge(
        id = id, userId = 1, type = ChallengeType.DISTANCE, source = ChallengeSource.CUSTOM, name = "C$id",
        status = status, celebrated = celebrated, targetDistanceKm = 100.0
    )

    @Test
    fun `terminal unpresented rows are queued in slot order and remembered as seen`() {
        val rows = listOf(row(1, ChallengeStatus.ACTIVE), row(2), row(3, ChallengeStatus.FAILED), row(4, celebrated = true))

        val (queue, seen) = nextQueue(emptyList(), emptySet(), rows)

        assertEquals(listOf(2, 3), queue)
        assertEquals(setOf(2, 3), seen)
    }

    @Test
    fun `a running presentation is not queued again by a later emission`() {
        val rows = listOf(row(2), row(5))
        val (queue, seen) = nextQueue(emptyList(), emptySet(), rows)

        // Both rows are still unpresented in the database, so a fresh emission (another row's
        // cache moved, say) carries them again; neither may be queued twice.
        val (again, seenAgain) = nextQueue(queue, seen, rows)
        assertEquals(listOf(2, 5), again)
        assertEquals(setOf(2, 5), seenAgain)

        // And once dequeued by celebrate, it stays out even though the emission still has it.
        val (afterDequeue, _) = nextQueue(queue - 2, seen, rows)
        assertEquals(listOf(5), afterDequeue)
    }

    @Test
    fun `a completion that arrives later is appended behind what is queued`() {
        val (queue, seen) = nextQueue(emptyList(), emptySet(), listOf(row(1)))

        val (later, seenLater) = nextQueue(queue, seen, listOf(row(1), row(2, ChallengeStatus.ACTIVE), row(7)))

        assertEquals(listOf(1, 7), later)
        assertEquals(setOf(1, 7), seenLater)
    }

    @Test
    fun `a failed row is queued like a completed one`() {
        val (queue, _) = nextQueue(emptyList(), emptySet(), listOf(row(3, ChallengeStatus.FAILED)))
        assertEquals(listOf(3), queue)
    }

    @Test
    fun `an emission with nothing new returns the same queue and seen set`() {
        val queue = listOf(4)
        val seen = setOf(4)
        val (same, sameSeen) = nextQueue(queue, seen, listOf(row(4), row(9, ChallengeStatus.ACTIVE)))
        assertEquals(queue, same)
        assertEquals(seen, sameSeen)
    }
}
