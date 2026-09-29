package com.silas270.blocktime.data.model

/**
 * Team progress as a pure derivation from one row (docs/shared-challenges.md "Team progress is
 * derived, never stored"). Nothing here touches a repository, a profile or the clock: the row
 * carries the pilot's own values, and its [Challenge.roomState] cache carries everyone else's
 * together with the pilot's own code, so every question about the crew is a function of the row.
 *
 * **The pilot's own values always come from the local row, never from their own snapshot in the
 * cache.** The row is credited at landing; the snapshot is uploaded later, so the cached copy is
 * the stale one. Every function below reads self from the row and the others from [others] or
 * [contributors].
 *
 * Extension functions rather than members, like [progressFraction], so Room's entity scanning
 * never mistakes one for a column.
 */

/** Shared is orthogonal to type and source: a row is shared iff it is linked to a room. */
fun Challenge.isShared(): Boolean = roomCode != null

/** The row needs uploading iff the server has not confirmed its latest generation. */
fun Challenge.isSyncPending(): Boolean = syncGeneration != syncedGeneration

/**
 * Everyone in the room: self first, then the others in join order, and those who left at the
 * end. Empty when there is no cache yet, which the screens read as "crew unknown", not as
 * "nobody": the row is still shared.
 */
fun Challenge.crew(): List<ParticipantSnapshot> {
    val cache = roomState ?: return emptyList()
    val (self, rest) = cache.room.participants.partition { it.userCode == cache.selfCode }
    val (left, present) = rest.partition { it.left }
    return self + present.sortedBy { it.colorIndex } + left.sortedBy { it.colorIndex }
}

/** The crew without self and without those who left: the pilots a streak or a race still counts. */
fun Challenge.others(): List<ParticipantSnapshot> {
    val cache = roomState ?: return emptyList()
    return cache.room.participants
        .filter { it.userCode != cache.selfCode && !it.left }
        .sortedBy { it.colorIndex }
}

/**
 * Everyone but self, leavers included, in join order. What a pool sums over: a pilot who leaves
 * takes nothing back (docs/shared-challenges.md, decided question 2), so their kilometres and
 * visited members stay in the team total even though [others] no longer lists them.
 */
fun Challenge.contributors(): List<ParticipantSnapshot> {
    val cache = roomState ?: return emptyList()
    return cache.room.participants.filter { it.userCode != cache.selfCode }.sortedBy { it.colorIndex }
}

/**
 * The union of every participant's visited members, over the current definition.
 *
 * Self is read through [withSetDefinitionResolved], so a member the definition no longer has is
 * dropped from the pilot's own set the same way it is on every other read; the others' members
 * are filtered against the same definition here, because a peer on an older app version may
 * report members that no longer exist (situation P14). A finished row is left alone on both
 * counts, exactly as [withSetDefinitionResolved] leaves it alone: its stored total is the final
 * score, not a live count.
 */
fun Challenge.teamVisitedMembers(): Set<String> {
    val self = withSetDefinitionResolved()
    val definition = self.setCatalogId
        ?.let { CuratedChallengeSets.find(it) }
        ?.takeIf { self.status == ChallengeStatus.ACTIVE }
    val union = LinkedHashSet(self.visitedSetMembers)
    for (participant in contributors()) {
        participant.visitedMembers.filterTo(union) { definition == null || it in definition.members }
    }
    return union
}

/** The pool's kilometres: the pilot's own from the row plus every other contributor's. */
fun Challenge.teamDistanceKm(): Double = cumulativeDistanceKm + contributors().sumOf { it.distanceKm }

/**
 * The group streak: the minimum over self and [others]. Self's [Challenge.streakDays] is taken
 * as the row carries it, which is already resolved against today for everything downstream of
 * the repository ([withStreakEvaluatedAt]); a pilot who left is not part of the group any more
 * and cannot hold it back.
 */
fun Challenge.teamStreakDays(): Int = (others().map { it.streakDays } + streakDays).min()

/**
 * The team's 0f..1f progress by the room's own type (docs/shared-challenges.md "Team progress is
 * derived, never stored"): union over the definition for a set, sum over the target for a
 * distance pool, the group minimum over the target for a streak, and the pilot's own progress
 * for a race, where the crew is a ranking and not a pool. Without a cache every pooled value
 * collapses to the pilot's own, so a shared row whose room was never seen still renders.
 */
fun Challenge.teamProgressFraction(): Float = when (type) {
    ChallengeType.ROUTE -> progressFraction()
    ChallengeType.SET_COMPLETION -> {
        val total = withSetDefinitionResolved().setTotalMembers
        if (total > 0) (teamVisitedMembers().size.toFloat() / total).coerceIn(0f, 1f) else 0f
    }
    ChallengeType.DISTANCE ->
        targetDistanceKm?.takeIf { it > 0 }
            ?.let { (teamDistanceKm() / it).toFloat().coerceIn(0f, 1f) }
            ?: 0f
    ChallengeType.STREAK ->
        targetDays?.takeIf { it > 0 }
            ?.let { (teamStreakDays().toFloat() / it).coerceIn(0f, 1f) }
            ?: 0f
}

/**
 * What the screens show: the team's progress for a shared pool, the pilot's own for a race and
 * for every solo row. [progressFraction] keeps its meaning (own progress) for the credit paths
 * and the outcome screen.
 */
fun Challenge.displayProgressFraction(): Float =
    if (isShared() && type != ChallengeType.ROUTE) teamProgressFraction() else progressFraction()

/** One slice of the multi-coloured team bar: whose, how much of the target, and whether it is
 *  the pilot's own slice (drawn in the pilot's colour and emphasised). */
data class ProgressSegment(
    val participant: ParticipantSnapshot,
    val fraction: Float,
    val isSelf: Boolean,
)

/**
 * The team bar's slices in join order, leavers included (their contribution stays). For a
 * distance pool each slice is that pilot's share of the target; for a set each slice counts the
 * members that pilot was the first, in join order, to bring in, so the slices add up to the
 * union rather than double-counting a member two pilots both visited. A streak and a race have
 * no pooled bar, so they yield one self slice at [displayProgressFraction]. Empty without a
 * cache: there is nothing to slice, and the caller falls back to the plain bar.
 *
 * Self's slice reads the row, never the cached self snapshot; the snapshot only lends its name
 * and colour. If the cache has no snapshot for self yet, a placeholder with the pilot's code
 * stands in so the pilot's own slice is never missing from their own bar.
 */
fun Challenge.progressSegments(): List<ProgressSegment> {
    val cache = roomState ?: return emptyList()
    val self = selfSnapshot(cache)
    if (type != ChallengeType.SET_COMPLETION && type != ChallengeType.DISTANCE) {
        return listOf(ProgressSegment(self, displayProgressFraction(), isSelf = true))
    }
    // A stable sort keeps self ahead of a contributor with the same index, which only happens
    // for the placeholder above.
    val ordered = (listOf(self) + contributors()).sortedBy { it.colorIndex }
    return when (type) {
        ChallengeType.DISTANCE -> {
            val target = targetDistanceKm?.takeIf { it > 0 } ?: return emptyList()
            ordered.map { participant ->
                val isSelf = participant.userCode == cache.selfCode
                val km = if (isSelf) cumulativeDistanceKm else participant.distanceKm
                ProgressSegment(participant, (km / target).toFloat().coerceIn(0f, 1f), isSelf)
            }
        }
        else -> {
            val resolved = withSetDefinitionResolved()
            val total = resolved.setTotalMembers.takeIf { it > 0 } ?: return emptyList()
            val definition = resolved.setCatalogId
                ?.let { CuratedChallengeSets.find(it) }
                ?.takeIf { resolved.status == ChallengeStatus.ACTIVE }
            val counted = HashSet<String>()
            ordered.map { participant ->
                val isSelf = participant.userCode == cache.selfCode
                val members = if (isSelf) resolved.visitedSetMembers else participant.visitedMembers
                val fresh = members.count { (definition == null || it in definition.members) && counted.add(it) }
                ProgressSegment(participant, (fresh.toFloat() / total).coerceIn(0f, 1f), isSelf)
            }
        }
    }
}

/**
 * The pilot's place in a race, 1-based, or null for anything that is not a shared route.
 *
 * Once the room is decided the server's placements are the truth: the winner first, the rest by
 * how far they got (docs/shared-challenges.md "Protocol"). While the race is open the place is
 * the pilot's rank by route progress among the crew still in it, self's progress from the row
 * and the others' from the cache, ties broken by join order. Pilots who left are out of the
 * ranking. A decided room whose placements do not list the pilot (joined after the finish, or
 * an older server) falls back to the open-race rank rather than reporting nothing.
 */
fun Challenge.racePlacement(): Int? {
    if (type != ChallengeType.ROUTE || !isShared()) return null
    val cache = roomState ?: return null
    val outcome = sharedOutcome
    if (outcome is SharedOutcome.Completed) {
        val index = outcome.placements.indexOf(cache.selfCode)
        if (index >= 0) return index + 1
    }
    val self = selfSnapshot(cache)
    val standings = (others() + self)
        .map { participant ->
            val progress = if (participant.userCode == cache.selfCode) progressFraction() else participant.routeProgress
            Triple(participant.userCode, progress, participant.colorIndex)
        }
        .sortedWith(compareByDescending<Triple<String, Float, Int>> { it.second }.thenBy { it.third })
    return standings.indexOfFirst { it.first == cache.selfCode } + 1
}

/** The cached snapshot of self for its name and colour, or a placeholder carrying only the
 *  pilot's code when the cache has none yet (a room seen before the first upload landed). */
private fun Challenge.selfSnapshot(cache: RoomStateCache): ParticipantSnapshot =
    cache.room.participants.firstOrNull { it.userCode == cache.selfCode }
        ?: ParticipantSnapshot(userCode = cache.selfCode, username = "", colorIndex = 0)
