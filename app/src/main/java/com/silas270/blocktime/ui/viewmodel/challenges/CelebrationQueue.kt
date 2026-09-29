package com.silas270.blocktime.ui.viewmodel.challenges

import com.silas270.blocktime.data.model.Challenge
import com.silas270.blocktime.data.model.ChallengeStatus

/**
 * The presentation queue's one step (docs/shared-challenges.md "Presentation", plan B8): given
 * the ids queued so far, the ids ever queued in this session and the latest slot rows, the new
 * queue and the new seen set.
 *
 * Every row that is terminal and not yet presented, and whose id has not been seen, is appended
 * in slot order; its id joins the seen set at the same time. Seen is what keeps a row that is
 * mid-animation from being queued twice by a later, unrelated emission (another row's
 * celebration finishing, a sync refreshing a cache). Nothing is removed here: a row leaves the
 * queue only through `celebrate` or `dismissFailed`, after its row was written, so the order
 * "persist, then dequeue" holds and the queue is rebuilt from the database if the app dies
 * mid-presentation.
 *
 * Pure, and in a file of its own with no Android import, so it is testable without the
 * ViewModel around it.
 */
internal fun nextQueue(queue: List<Int>, seen: Set<Int>, rows: List<Challenge>): Pair<List<Int>, Set<Int>> {
    val fresh = rows
        .filter { it.status != ChallengeStatus.ACTIVE && !it.celebrated && it.id !in seen }
        .map { it.id }
    if (fresh.isEmpty()) return queue to seen
    return (queue + fresh) to (seen + fresh)
}
