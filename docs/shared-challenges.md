# Shared challenges

A challenge can be shared with other pilots through a six-character room code. Everyone in the
room works on the same goal, and the goal ends for everyone at the same moment. This file explains
the data model, how a room is kept in sync without a conflict ever arising, what happens in every
situation the app can be in, and what the server has to do. It builds on
[challenges.md](challenges.md) and [network.md](network.md). The situation tables below double as
the test list: each row's id is the prefix of the unit tests that cover it.

## Why offline first

Every online feature needs a server, and a hobby server does not live forever. If the app depended
on it, the day it goes down would be the day the app stops working. So the rules are:

- **Room stays the source of truth** for everything the pilot owns. The server is a relay, never an
  owner. A shared challenge is a normal `challenges` row with a room code on it.
- **When the server is gone, a shared challenge keeps working as a normal challenge.** Landings are
  credited locally exactly as before, the row can complete locally, and only the "other pilots"
  column stops updating.
- **Sharing is opt-in.** With the switch off, or with no server configured in the build, nothing in
  the app contacts the server and none of the sharing UI exists. With the switch on but the server
  unreachable, sharing actions are dimmed with a reason, and the last known crew stays visible.

## State, not events

The server holds, per room, one snapshot per participant and nothing else. **Each participant only
ever writes their own snapshot.** Progress toward a shared goal is a union (set members), a sum
(distance), or a minimum (streak) over those snapshots, so there is no such thing as a conflict
between two pilots and no merge rule beyond those three operations.

The alternative, a queue of deltas that is deleted once everyone has read it, was considered and
rejected. "Deleted once everyone read it" needs per-recipient read tracking, which is more state
than a snapshot, not less; a pilot joining by code needs the full current state, which deltas
cannot give; and a pilot returning after three weeks would have to replay a chain of deltas in
order. A snapshot answers all three with one read.

The client keeps the last room state it saw on the row (`room_state`, a cache) and diffs it against
each new one. That diff is what produces "the crew finished while you were away", and it works
however long the app was closed, because it is computed from state rather than from a message that
had to be delivered.

### Sync moments

There are no push notifications. `SharedChallengeSyncer` asks the server at exactly these moments:

| Trigger | Reason | Debounce |
|---|---|---|
| The app comes to the foreground (`CesiumGameActivity.onStart`, which covers cold start) | `FOREGROUND` | 60 s |
| The Challenges screen opens (`ChallengesViewModel` init) | `SCREEN_OPEN` | 60 s |
| A landing has been credited and its outcome published (`InFlightViewModel`) | `LANDING` | none |
| A successful share or join, and abandon (`ChallengesViewModel`) | `USER_ACTION` | none |

Share, look-up and join call the server directly from the repository and report the outcome to
the reachability signal themselves. A successful share or join then requests `USER_ACTION`, so
the crew caption reads "Synced just now": without it the caption kept the time of the last sync,
often the screen-open one minutes earlier. Abandon requests it too: the repository deletes the
row and queues the room code in `pending_room_leaves`, and the sync that follows sends the leave
(A2). `USER_ACTION` is never debounced, and in the `UNKNOWN` state `check()` probes rather than
skipping, so the sync runs at once when the server answers, and a leave waits in the queue when
it does not (A3).

One sync (`syncNow`) runs to completion under a mutex, in five steps:

1. Ask `ServerReachability.check()`. Not configured, sharing off or the device offline ends in
   `Skipped(state)`, published as the last summary.
2. **Debounce the two frequent triggers against the last completed sync.** A `FOREGROUND` or
   `SCREEN_OPEN` request within 60 s of `last_room_sync_at` returns `Skipped` **without touching
   `lastSummary`**, because a debounced request is not a sync and the info modal's "Synced 3 min
   ago" line must keep its last real answer.
3. Send the pending leaves (`pending_room_leaves`). A code that still has a live local row is
   dropped silently: the pilot rejoined the room (J13). A leave the server refuses is dropped too,
   because a retry would be refused the same way.
4. For every syncable row (shared, and active or terminal-but-unpresented, and whose room is not
   marked gone): **upload when the pilot's own data changed or the row has a claim to make,
   otherwise download.** Uploading returns the fresh room state, so push and pull are one round
   trip. The reply is merged under the write lock (`applyRoomState`), the generation is confirmed
   after an upload, and a claim the merge produced is sent at once and its reply merged too. A room
   the server no longer knows is marked gone and never asked about again; an unreachable server ends
   the sync and leaves the rest for next time.
5. Stamp `last_room_sync_at` and publish `Synced(rows, at)`.

`requestSync` returns at once and launches on the syncer's scope (the Activity's `appScope`,
never a ViewModel's). A request that arrives while a sync runs is coalesced: the reason is
remembered, a never-debounced reason winning over a debounced one, and exactly one more sync runs
when the current one finishes, which is enough because a sync reads everything fresh when it
starts.

**The syncer's clock is the system default zone** (`Clock.systemDefaultZone()`), the pilot's own
calendar, because the snapshot's streak is read against "today" and that has to be the pilot's day,
not UTC's.

## Two signals

`OfflineModeController` decides whether the *device* is offline ([network.md](network.md)). It is
not the right question for sharing, because the map tiles must keep working when our server is
dead but the internet is fine, and sharing must keep working when data saver is on. So there is a
second, separate signal, `ServerReachability`, which is the only place that decides whether *our
server* answers:

```
!configured        -> NOT_CONFIGURED    (no ROOM_SERVER_URL in the build)
!optIn             -> DISABLED          (the "Shared challenges" switch is off)
!connected         -> DEVICE_OFFLINE    (raw ConnectivityMonitor value, not NetworkMode)
else               -> last probe result: UNKNOWN, REACHABLE or UNREACHABLE
```

It reads the raw connectivity value rather than `NetworkMode` on purpose: `NetworkMode` reports
`OFFLINE_DATA_SAVER` before it looks at connectivity, and would make an offline device look like
"server not reachable". **Data saver does not block sync.** A room is under 4 KB; the switch is
about map tiles.

`ServerReachability` also owns the opt-in, the way `OfflineModeController` owns the data saver, so
the Settings row and the server state can never disagree. Switching sharing on creates the room
secret (see [Identity](#identity)) before the switch is stored.

**A probe (`ping`, the `GET /health` of the protocol) runs at the start of every sync and nowhere
else.** `check()` returns without probing when the state does not depend on the server at all (the
first three lines above), otherwise it trusts a result younger than 30 s (`PROBE_TTL_MS`) and asks
again after that; `check(force = true)` ignores the cache. A probe that throws counts as
unreachable. Every API call also reports its own outcome through `report(success)`, which restarts
the cache window, so a dead server is noticed by the first request that hits it. Nothing polls in
the background, and the sharing actions do not probe first: they call, and report what they got.

`onlineAvailability(state)` turns the state into what the UI needs: `HIDDEN` for `NOT_CONFIGURED`
and `DISABLED`, `DISABLED_OFFLINE`, `DISABLED_UNREACHABLE`, `DISABLED_CHECKING` (opted in and
connected, but nothing has asked the server yet) or `ENABLED`. Every sharing button asks only this
function, and `onlineHint(availability)` is the one line under a dimmed control ("No connection",
"Server not reachable", "Checking…").

## Data model

### Columns added to `challenges` (schema 11)

| Column | Type | Meaning |
|---|---|---|
| `room_code` | text, nullable | Non-null means shared. Unique per pilot (`index_challenges_user_id_room_code`; SQLite treats NULLs as distinct, so unshared rows never collide). Stays on terminal rows so a finished room cannot be joined twice. |
| `room_state` | text, nullable | The last room state seen, as JSON, together with the pilot's own code (`RoomStateCache`). **A cache**: it may be thrown away without changing anything the pilot owns, and a cache that fails to parse is dropped, never thrown. |
| `sync_generation` | int, `DEFAULT 0` | Incremented by every local change to the pilot's own data on this row. |
| `synced_generation` | int, `DEFAULT 0` | The generation the server last confirmed. The row needs uploading iff the two differ. |
| `shared_outcome` | text, nullable | How the room ended: who completed it, whether that was this pilot, placements, or who broke the streak. **A fact**, kept for the log and read after the room itself is gone. |

`MIGRATION_10_11` adds the five columns and the unique index; the two `NOT NULL` counters get
`DEFAULT 0` in SQL and the same `defaultValue` on the entity, for the reason in
[state.md](state.md#migrations). Existing rows stay solo.

The two generation counters exist because of one race: a landing can be credited while an upload
of the previous snapshot is in flight. A boolean "needs upload" flag would be cleared by the
upload's reply and lose the landing until the next one. With counters, the upload confirms the
generation it sent (`confirmSynced` is guarded by `WHERE sync_generation = :generation`), the
confirmation is a no-op if the row moved on, and the row stays pending so the next sync sends the
newer state. The credit paths bump the counter in SQL (`sync_generation + 1`) or write
`nextGeneration()` in the same statement as the credit, never from a value read earlier.

`ChallengeStatus` gains `FAILED`, terminal like `COMPLETED`. The converter's fallback to `ACTIVE`
for an unknown name is a guard against a corrupted value, not a downgrade path: the database has no
destructive fallback on downgrade, so an older app never opens a schema-11 database.

### Wire and cache shapes

```
ParticipantSnapshot  userCode, username, colorIndex, left,
                     positionIata, legIndex, routeProgress        (route)
                     visitedMembers                                (set)
                     distanceKm                                    (distance)
                     streakDays, lastFlownDay, streakAlive         (streak)
                     updatedAt                                     (server time)
RoomDefinition       type, source, name, description, iconName, catalogId, predefinedRouteId,
                     originIata, destIata, setCatalogId, setMemberKind, targetDistanceKm, targetDays
SharedOutcome        Completed(byUserCode, bySelf, at, placements) | Failed(brokenByUserCode, bySelf, at)
RoomState            code, definition, createdAt, participants, outcome, version, roomGone
RoomStateCache       selfCode, room
OutcomeClaim         Completed(at) | Failed(brokenBy)
```

On the wire and in `room_state` these are **flat JSON objects with nullable fields and a `kind`
string** for the outcome and the claim (`RoomDto.kt`), not the Kotlin sealed types. Gson cannot
read a sealed interface polymorphically and bypasses Kotlin constructors, so a missing field would
become null rather than its default. The mapping to the Kotlin types happens in code, with explicit
defaults (`toDomain()`), which is also what lets two app versions share a room: unknown fields are
ignored, a snapshot without a `userCode` is dropped, an outcome with an unknown `kind` reads as no
outcome, and a definition with an unknown `type` is refused as a template this version cannot
build. One `Gson` (`RoomJson`) serves the wire, the cache and the outcome column, and the release
build keeps the DTO field names for it.

`bySelf` lives in three places with three rules. **On the wire it does not exist**: the server does
not know who is asking and always sends false. **In the cache it is ignored and re-derived** from
the cache's own `selfCode` on every read. **In `shared_outcome` it is written explicitly**
(`RoomJson.encodeOutcome` fills `OutcomeDto.bySelf`), because that column has no `selfCode` next
to it and the stamp has to survive the round trip through the row; on the way back the stored stamp
wins, then a comparison with the caller's code, and with neither it is false. Everything that later
needs to know "was this me" (the landing outcome, the choice of presentation, the log stamp) reads
it from the row alone, without a repository or a profile. `roomGone` is likewise client-side only:
the server never sends it.

### Team progress is derived, never stored

`progressFraction()` keeps its meaning, the pilot's own progress. For shared rows the screens call
`displayProgressFraction()`, which is the team's progress for the three pooled types and the pilot's
own for a race (`SharedProgress.kt`):

| Type | Team progress | Pilot's own part |
|---|---|---|
| Set | union of every participant's visited members, filtered against the current definition | local `visitedSetMembers` |
| Distance | sum of every participant's kilometres | local `cumulativeDistanceKm` |
| Streak | minimum streak over self and the crew still in it | local `streakDays`, after `withStreakEvaluatedAt` |
| Route | the pilot's own progress; the crew is a ranking | local route progress |

**The pilot's own values always come from the local row, never from their own snapshot in the
cache.** The row is credited at landing; the snapshot is uploaded later. Three views of the cache
keep the leaver rule straight: `crew()` is everyone, self first, then the others in join order,
then those who left; `others()` is the crew without self and without leavers, what a streak
minimum and a race ranking count; `contributors()` is everyone but self, leavers included, what a
pool sums over, because a pilot who leaves takes nothing back. `crewSize()` is `others()` plus
one, so it is right even before the cache holds the pilot's own snapshot, and 1 for a solo row.

`progressSegments()` cuts the team bar into one slice per participant in join order: a pilot's
share of the distance target, or the set members that pilot was the first, in join order, to bring
in, so the slices add up to the union. `racePlacement()` is the server's placement once the room is
decided, and while the race is open the pilot's rank by route progress among the crew still in it,
**ties broken by join order**, self's progress from the row and the others' from the cache.

### Identity

There is no account. The pilot code already on the profile (`user_code`, six characters) is the
public identity, and a 32-character secret from the same alphabet (`room_secret`), generated with
`SecureRandom` the first time sharing is switched on and never regenerated, is the password. The
server binds a code to its secret on the first write it sees. Both live in the app's own storage
and travel with an Android backup, so a restored phone can write to the same rooms; two phones
restored from one backup both can, and the last write wins.

The HTTP client will send the code as `X-Pilot` and the secret as `Authorization: Bearer` on every
request. Because the in-memory fake has no request to read a header from, the repository and the
syncer call `bindCallerIdentity(userCode)` before their first call; it sets `FakeRoomApi.callerUserCode`
and is a no-op for every other implementation. The fake then answers `Unauthorized` for a snapshot
or a claim whose code is not the bound caller's, as the server would.

## The merge

`mergeRoomIntoChallenge(local, room, selfCode, today, now, zone)` is a pure function of the row,
the room state, the pilot's code and the clock. It returns the new row, whether anything beyond the
cache changed, and an optional claim to send back. Rules, in order:

1. Always refresh the cache with the new room state.
2. The row is terminal and already presented: nothing else. Its log entry is frozen.
3. The row is terminal but not yet presented, and the room has an outcome: **the server wins.**
   Status follows the outcome's kind, the outcome is copied with `bySelf` stamped, and
   `completedAt` becomes the outcome's time so every pilot's log orders the same event the same
   way. No claim: the server already has its answer. This is how a rejected claim resolves
   (someone finished seconds earlier: second place instead of a win) and how "completed locally
   while offline, but the group streak broke meanwhile" resolves (a failure).
4. The row is active and the room says `Completed`: the row completes, unpresented, with the
   outcome stamped. A route row also drops its paused leg, because a paused leg of a finished race
   is worthless and would otherwise still offer RESUME.
5. The row is active and the room says `Failed`: the row fails, unpresented, likewise.
6. The row is active and the room has no outcome yet, per type:
   - Set: the union covers every member of the current definition: complete, claim `Completed`.
     An unknown definition never completes, as on the solo path.
   - Distance: the sum reaches the target: complete, claim `Completed`.
   - Streak: any crew member is dead, or the pilot's own streak is dead by the local rule: fail,
     claim `Failed(brokenBy)`. The others are checked first, in join order, then self, so every
     phone that merges the same room state names the same pilot. Otherwise the minimum reaches the
     target: complete, claim `Completed`.
   - Route: never terminal from a merge; a race is won only by an arrival.
7. `changed` is any row change beyond the cache.

**The merge never touches the pilot's own data.** It cannot, by construction: the only inputs to
the pilot's own columns are the landing pipeline and the pilot's own actions.

Whoever sees a pool fill first claims the completion; the server keeps the first claim and ignores
the rest. A client that claimed and was refused finds the real outcome in the reply and applies rule
3. Claims are idempotent and need no coordination.

A crew member's streak is judged from their snapshot alone. Their own verdict (`streakAlive`) is
taken when it says dead, because the owner computed it on their own row. When it says alive it is
only as fresh as their last upload, so a stale snapshot is checked against the calendar with **two
days of slack**: a `lastFlownDay` before the day before yesterday is dead, and a snapshot that has
never flown and has not been written for over two days is dead by the joiner rule. Two days rather
than the one `currentStreak` gives yesterday, because the peer's day is their local calendar day
and `today` is this pilot's: two pilots can sit up to 26 hours apart, so a run that is in fact
alive is never called dead over the clock, and a run that died is called dead one day late at
worst, by which time the owner's own upload has usually said so.

The pilot's own streak aliveness (`isOwnStreakAlive`) is `currentStreak(today) > 0`, or, before the
first credited flight, "the row was started today or yesterday" in the pilot's zone. The day of
joining and the day after are not yet a missed day, which is the same tolerance `currentStreak`
gives yesterday; without it a pilot who joined in the evening would be dead at midnight. The one
function feeds both the uploaded `streakAlive` and the merge's own check, so the pilot can never
report one thing and act on another.

`applyRoomState` runs the merge under the write mutex on a freshly read row and writes with a
scoped statement: `updateRoomState` (the cache alone) when nothing else changed,
`updateSharedFieldsAndClearPausedFlight` when a route row with a paused leg was ended, and
`updateSharedFields` otherwise ([state.md](state.md#concurrent-writers)). A row that was deleted
meanwhile is skipped and never re-inserted (P15).

## Landing

The post-landing pipeline ([core-loop.md](core-loop.md#the-post-landing-pipeline)) is unchanged in
order and in what it guarantees. Two credit rules are extended for shared rows:

- **A pooled challenge can be filled by the pilot's own landing.** `creditDistance` and
  `creditSetCompletion` test the sum or union of the local row plus the cached snapshots of the
  others, not the pilot's own number alone. Otherwise a landing that fills the pot would show
  "advanced" and complete only at the next sync. The completion is stamped as the pilot's own
  (`Completed(self, bySelf = true)`), and the claim goes with the landing sync.
- **A shared streak never completes from a landing.** `creditStreak` writes the streak columns and
  bumps the generation, and leaves the status alone, because completion depends on the crew's
  minimum, which only the merge knows.

Every credit to a shared row bumps `sync_generation`. Whole-row updates inside the credit
functions stay as they are: they run under the repository's write mutex on a row read under that
same lock ([state.md](state.md#concurrent-writers)).

**An arrival on a shared race completes the row without a local outcome.** `advanceRouteChallenge`
writes the route columns, the status and the generation in one statement, and nothing else. The
claim (`Completed(completedAt)`) goes with the landing sync, and the server's reply supplies the
outcome: a win, or a placement if someone was faster. Until then `presentationFor` treats a shared
route without a foreign completion as won, which is the truth the pilot has.

`resolveLandingOutcome` gains one condition. A row that went from active to terminal counts as
*completed by this landing* only when `shared_outcome` is absent or stamped `bySelf`. A foreign
completion that happens to arrive between the pipeline's before and after reads is therefore not
shown as the pilot's own win on the outcome screen; it is presented on the Challenges screen like
every other foreign completion. **The outcome screen only ever shows what the pilot's own landing
did.** Its bars are `displayProgressFraction()`, so a shared pool animates the team's total, under
a "CREW ×N" label.

After the outcome is published, the landing asks for a sync (`LANDING`, no debounce) on the
syncer's own scope, never on `landingScope`, so `InFlightViewModel.onCleared` does not wait for
the network and the outcome screen is never delayed by it.

Where CONTINUE goes after arrival is one pure function, `resolveArrivalDestination(result,
hasPendingPresentation, fromOutcomeScreen)`, used by both the arrival screen and the outcome
screen. From the arrival screen a result with outcomes goes to the outcome screen; otherwise, and
from the outcome screen after any completion, a terminal row still waiting to be presented sends
the pilot to Challenges, and nothing at all goes to the Hub. That second condition is what lets a
completion that arrived mid-flight be seen right after landing rather than the next time the pilot
happens to open Challenges. `hasPendingPresentation` counts `COMPLETED` or `FAILED` rows with
`celebrated = 0`.

## Presentation

Completion presentation ([challenges.md](challenges.md#completion-presentation)) is reused
unchanged in its mechanics. `presentationFor(challenge)` and `failurePresentationFor(challenge)`
choose what it says, from the row alone:

| Presentation | When | Copy |
|---|---|---|
| Solo | not shared | as today |
| Team | shared pool, completed by anyone | "CREW ×N" badge above the card, full confetti |
| Race won | shared route, no foreign completion on the row | "YOU WON THE RACE" in gold, "CREW ×N" |
| Race placed | shared route, `Completed` by someone else | "ANNA WON" over "YOU FINISHED 2ND", "CREW ×N", half the confetti in cooler colours, button "GG" |
| Broken | shared streak, `FAILED` | the shatter, below |

A placed pilot whose code the placements do not list (joined after the finish, an older server)
is shown second, and a winner the cache has no name for is named by code. Every caption sits on
its own dark pill above the card, because the tab labels show through the scrim exactly there,
and each headline line is one line: the placed headline is two deliberate lines rather than one
that wraps wherever the name is long enough. A solo completion draws no caption at all.

**The shatter** (`ChallengeFailureOverlay`) shares the opening beat with the celebration
(`ChallengePresentationSupport`: 325 ms before the first card, 200 ms before each further one,
a 460 ms lift from the slot to centre stage), but under a darker scrim (80%) and without confetti,
glow or flash. The caption reads "STREAK BROKEN" with "Anna missed a day" or "You missed a day"
under it, and the button says "DAMN". On a tap (scrim, button or system back) the card shakes and
cracks for 220 ms, three to five jagged cracks spreading from its middle, then shatters for
700 ms into nine shards, a 3 by 3 grid whose inner vertices are nudged so no two shards are the
same shape, each one the whole card clipped to its polygon and falling under 2,400 dp/s², fully
opaque until 60% of the way and fading to nothing after. The cracks and shards are seeded from the
row id, so the same card always breaks the same way.

**A failed row is deleted when its shatter finishes** (`dismissFailed`, and only if the row is
still failed). The completed log stays a log of successes, and the slot frees at that moment,
exactly as a celebration frees its slot. Until then the failed row occupies its slot, drawn in
Haze under "BROKEN", and counts against the cap like an unpresented completion, so the slot queries
match `ACTIVE`, or `COMPLETED`/`FAILED` with `celebrated = 0`.

The celebration queue is reactive. It holds **ids**, fed from the slot flow through the pure
`nextQueue(queue, seen, rows)`: every emission appends the terminal, unpresented rows it has not
seen before, in slot order, and the seen set keeps a row mid-animation from being queued twice by a
later, unrelated emission (another celebration finishing, a sync refreshing a cache). The currently
presented row is resolved by id from the latest emission, so a sync that corrects the outcome after
enqueuing (a refused claim turning a win into a placement) still presents the corrected one.
`celebrate` and `dismissFailed` persist first and dequeue second, because the other order would let
a slot emission re-queue the row between the two. The database stays the source: the queue is
rebuilt from `status IN (COMPLETED, FAILED) AND celebrated = 0` whenever the ViewModel is created.

## Situations

Each row id is the prefix of the unit tests that cover it (`LocalChallengeRepositorySharedTest`,
`SharedChallengeSyncerTest`, `SharedChallengeMergeTest`, `LandingResultTest`,
`ArrivalRoutingTest`, `ChallengesViewModelTest`). A row without a test of its own is UI-only or
follows from its neighbours' tests; it keeps its id so the tables stay the one list. `⚑` marks a
default that was chosen for the design rather than decided by the product.

### Landing (Story or Challenge flight; a Free flight returns before anything, as today)

| # | State of the shared row | Result |
|---|---|---|
| L1 | Active pool, team target not reached | Credited locally as today; generation bumped; outcome screen shows *advanced* with team progress; then a `LANDING` sync. A set landing that adds nothing new writes nothing. |
| L2 | Active pool, this landing fills the pool (local row plus cached others) | Completed locally, outcome `Completed(self, bySelf)`, unpresented; outcome screen shows *completed*; CONTINUE goes to Challenges; the sync sends the claim. If the server refuses, rule 3 replaces the outcome and the presentation shows the server's truth. |
| L3 | Active pool, a sync during the flight already completed the row | Only active rows are credited, so nothing is credited. The flight is logged. Outcome is *none*, a presentation is pending, so CONTINUE goes to Challenges. The pilot's kilometres are uploaded but were no longer needed. |
| L4 | Active shared streak, the day counts | Streak columns written and generation bumped; **not** completed. Outcome shows *advanced* or nothing. Completion or failure is decided by the merge after the upload. |
| L5 | Active race, leg flown under it, destination not reached | Advanced as today; generation bumped in the same statement; the ranking updates after the sync. |
| L6 | Active race, arrival at the destination | Completed locally **without an outcome**; the claim goes with the landing sync and the reply supplies it. Confirmed: a win. Refused: rule 3 gives a placement, presented as "2ND". |
| L7 | Race, the row turned terminal during this leg (sync) | `advanceRouteChallenge` is a no-op on a non-active row, its existing contract. The paused-flight slot is still cleared by the scoped statement. Flight logged. CONTINUE goes to Challenges. |
| L8 | A sync lands **between** the pipeline's before and after reads and completes the row for someone else | `resolveLandingOutcome` sees active to terminal with `bySelf = false` and reports nothing for this row. Presented on Challenges. No foreign win on the outcome screen. |
| L9 | Terminal and presented (any type) | Nothing: not active, not credited, not synced. |
| L10 | Device offline, server unreachable, or sharing switched off | L1 to L7 identical locally. The sync ends in *skipped* or *unreachable*; the row stays pending until the next successful sync. The outcome screen does not depend on it. |
| L11 | A credit arrives while an upload for the same row is in flight | The credit bumps the generation to g+1. The upload's reply is merged; confirming g is a no-op; the row stays pending; the next sync sends g+1. Nothing is lost. |
| L12 | The logbook write failed | As today: *none*; nothing credited, nothing synced. |

### Applying a room state

| # | Local row | Room | Result |
|---|---|---|---|
| P1 | Active | Open, only foreign progress | Cache refreshed with `updateRoomState` alone; not a change. Slot ring and modal show the new team state. No presentation. ⚑ No toast in the first version. |
| P2 | Active pool | Open, union or sum reaches the target | Completed, unpresented, claim sent at once; the reply carries the outcome (this pilot's, or a faster one's) and rule 3 finalises it. Presented on the next visit, or at once if the screen is open. |
| P3 | Active | `Completed` by someone else | Completed, unpresented, `bySelf = false`, written with `updateSharedFields`. A race drops its paused leg. Pool: team celebration. Race: "X WON · YOU FINISHED Nth". |
| P4 | Active streak | A crew member (or this pilot, by the local rule) is dead | Failed, claim `Failed(brokenBy)`, shatter on the next visit. Self is judged by the local row, never by the cached self snapshot. |
| P5 | Active streak | `Failed` from the server | Failed, outcome copied. |
| P6 | Active race with a paused leg | `Completed` (someone else won) | As P3; the paused leg is deleted in the same statement as the status, the slot loses its pause badge, the modal becomes read-only. |
| P7 | Completed, unpresented (own landing), claim refused | Someone else's outcome, same kind | Status stays, outcome replaced (rule 3). |
| P8 | Completed, unpresented | `Failed` | The server wins: status becomes failed, shatter instead of celebration (rule 3). |
| P9 | Presented | anything | Cannot happen: presented rows are not synced. The log entry freezes at presentation. |
| P10 | Active | A participant joined or left | Cache refreshed; the crew list changes. Pool: a leaver's contributions stay. Streak and race: a leaver no longer counts, and a leaver's dead streak does not break the group. |
| P11 | Active | Not found (deleted by the server's retention) | `roomGone` in the cache; the row keeps working locally, drops out of the syncable list, and the modal says "Room closed · continuing solo" under a code that has lost its copy and share buttons (nobody can join any more); no second share. |
| P12 | Any | Unauthorised | As P11, plus a log line. Only possible if another pilot bound the same code first. |
| P13 | Any | Unreachable | Nothing local; the reachability signal is told and the sync ends. |
| P14 | Active set, the set's definition changed in an app update | Old catalog id | As today through `withSetDefinitionResolved`: the current definition counts and the union is filtered against it. App versions may differ within a room; snapshots carry raw data. |
| P15 | Deleted during the sync (abandoned) | Any | Applying reads a missing row and does nothing. Never re-inserts. Confirming is a no-op. |

### Sharing (info modal, "SHARE CHALLENGE")

| # | State | Result |
|---|---|---|
| S1 | Not configured, or sharing off | The button does not exist. |
| S2 | Sharing on, device offline, server unreachable, or probe pending | Button dimmed with the reason. |
| S3 | Reachable, row active, not shared, zero progress (set empty; 0 km; 0 streak days; route at its origin on leg 0 with no paused leg) | The caller identity is bound, the room is created, then the row is re-read and re-checked under the lock (S8), then linked with `updateRoomLink` (code and first cache in one statement). The modal shows the code large (24sp monospace under "ROOM CODE", with `contentDescription` "Room code CODE"), a copy button and the share sheet beside it (the text is `Join my Blocktime challenge "Pacific Rim": CODE`). Crew: this pilot. |
| S4 | Reachable, progress above zero | Button dimmed: "Only a fresh challenge can be shared". |
| S5 | Already shared | Instead of the button: code, copy, share sheet and the crew list, also while offline. With sharing switched off the code stays and copy and share go, as for a closed room (P11). |
| S6 | Creating the room fails as unreachable | No local change; "Server not reachable, try again". |
| S7 | Terminal row | No sharing. |
| S8 | The row changed between creating the room and taking the lock (a landing, an abandon, a second share) | The re-check fails, the room's code goes to `pending_room_leaves` and the next sync leaves it, result *not eligible* with the fresh reason (or *unknown* for a deleted row). |

### Joining (picker, "Have a code?")

| # | State | Result |
|---|---|---|
| J1 | Not configured, or sharing off | The field does not exist. |
| J2 | Sharing on, not reachable | Field dimmed with the reason. |
| J3 | Unknown code | "No challenge with that code". Every refusal stays on the preview with its reason. |
| J4 | Room open, a slot free, not a member | LOOK UP shows a preview (name, type, target, crew, warnings). JOIN checks locally what the server would refuse anyway, builds the row template from the definition, uploads the first snapshot (the join), then under the lock re-checks the cap and inserts. The code is normalised (trimmed, upper case) first. The result is published as the existing `Started`, so the picker closes, a route is focused and the Hub opens exactly as after starting a challenge. |
| J5 | The cap is full after the server accepted | The room is left at once (queued if the leave fails); the existing "CHALLENGE SLOTS FULL" modal. A cap already full is refused before anything is sent. |
| J6a | A local active row has this code | LOOK UP answers from the local row (`findByRoomCode`, the code normalised as for a join) before asking the server: it publishes `AlreadyJoined(id)`, the picker closes, the look-up is cleared and that slot's info modal opens. No network call. A JOIN that finds the row under the lock is published the same way. |
| J6b | A local completed row has this code | LOOK UP answers from the local row: "You already finished this challenge" under the field, the join's `AlreadyFinished` reason. No preview, no insert, no network call. |
| J7 | The room is completed or failed | "This challenge is already over". |
| J8 | A race with any progress | "The race has already started". The preview already warns. |
| J9 | A pool or streak already in progress | Allowed. The streak preview warns "Joining resets the group streak", because the minimum drops to zero. |
| J10 | Room full (six, leavers not counted locally) | "This crew is full". |
| J11 | Unreachable after the preview | Hint; no local change. |
| J12 | The definition is unknown to this app version | "Update Blocktime to join this challenge". Refused before the put, so nothing has to be left. |
| J13 | The code is in the pending-leaves list (rejoin after S8 or J5) | The code is removed from the list on a successful join, and a pending leave for a code with a live local row is dropped by the syncer without leaving. The server replaces the left snapshot with the new one. |
| J14 | Process death between the server accepting and the local insert | The server has the pilot, the app has no row. The next join with the same code is an upsert and creates the row. ⚑ Accepted. |

### Abandoning

| # | State | Result |
|---|---|---|
| A1 | Not shared | As today: the row is deleted. |
| A2 | Shared, active, reachable | The modal adds "Your crew keeps the challenge; you leave the room." The row is deleted under the lock and its code is queued in `pending_room_leaves`; the `USER_ACTION` sync that follows sends the leave. Leaving is one path, online or not, and the network never runs under the lock. |
| A3 | Shared, unreachable or sharing off | As A2; the queued leave is sent by the next successful sync. |
| A4 | Shared race with a paused leg | The paused flight goes with the row, as today. |
| A5 | The pilot created the room | No special case. Rooms have no owner. |
| A6 | Terminal, unpresented | **Cannot abandon.** ABANDON is not shown for a non-active row; the presentation is the only way out. |
| A7 | Terminal, presented | Cannot abandon; log entries are untouchable, as today. |

### Lifecycle and visibility

| # | Situation | Result |
|---|---|---|
| Y1 | App to the foreground (cold start or from the background) | `FOREGROUND` sync, 60 s debounce; a landing sync in the same window is not debounced. Runs during In-Flight too; In-Flight never presents challenge state, and L8 covers the window. |
| Y2 | Challenges opens | `SCREEN_OPEN` sync, debounced; the reactive queue picks up what arrives. |
| Y3 | Process death mid-sync | Each application of a room state is one scoped statement. Order: reply, apply, confirm. Death in between leaves the row pending and the upload is repeated. Idempotent. |
| Y4 | Sharing switched off | Sync stops; state `DISABLED`. Rows stay shared; nothing is left automatically ⚑. Switching on resumes. |
| Y5 | Sharing switched on | The first time, the secret is generated. Every time, an information modal says what the crew will see. No probe runs then; the state is `UNKNOWN` ("Checking…") until the next sync moment asks. |
| Y6 | The server is gone for good | Every trigger ends unreachable, sharing UI is dimmed, the cached crew stays visible. Shared challenges keep working and can complete locally (L2 with an unconfirmed claim; the presentation shows the local state). |
| Y7 | The device clock jumps | Own streak as today (a future day is alive). Others' snapshots get two days of slack. |
| Y8 | Two phones restored from one backup, same pilot code | Both hold the secret; the last write wins. ⚑ Accepted. |
| Y9 | App update with a newer wire shape | Unknown JSON fields are ignored; missing ones get defaults in the mapping, not in a constructor. |
| Y10 | On the Hub, a sync ends the focused route | The Hub refreshes on each `Synced` summary, and `resolveFocusedChallenge` clears the focus as it already does for a non-active row. |
| Y11 | The info modal is open and a sync changes the row | The modal holds only the id and reads the row from the live slot list, so it shows the change; a terminal row turns it read-only. |

### Per type

| Type | Can be shared | Shown progress | Ends | Presented as |
|---|---|---|---|---|
| Route | at its origin, leg 0, no paused leg; joining locks at the first progress | own; the crew is a ranking (server placements once decided; before that by progress, ties by join order) | the first arrival (server claim); everyone else ends at that moment with a placement, paused legs deleted | winner: "YOU WON THE RACE"; others: "ANNA WON · YOU FINISHED 2ND"; log stamp "1ST", "2ND", … |
| Set | with an empty set | union over the current definition; the team bar credits each member to the first pilot, in join order, who visited it | the union is complete, by own landing (L2) or merge (P2) | team celebration, log stamp "CREW ×N" |
| Distance | at 0 km | sum; a segmented bar in join order, own segment emphasised | the sum reaches the target, by own landing (L2) or merge (P2) | as set |
| Streak | at 0 days | the crew's minimum; the modal lists each pilot's days and state (alive, at risk, broken) | merge only: minimum reaches the target, or one pilot is dead | completed as set; failed: shatter, "STREAK BROKEN · Anna missed a day" |

## Protocol

The server is a key-value store with five routes. It knows nothing about flights.

| Method | Path | Body | Reply | Rules |
|---|---|---|---|---|
| GET | `/health` | | 204 | the probe |
| POST | `/rooms` | definition, own snapshot | room state | code: six characters from `ABCDEFGHJKLMNPQRSTUVWXYZ23456789`; creator gets colour 0 |
| GET | `/rooms/{code}` | | room state, or 404 | |
| PUT | `/rooms/{code}/participants/{userCode}` | snapshot, optional claim | room state; 404; 409 with `RaceLocked`, `RoomClosed` or `RoomFull`; 401 | Upsert. A stranger's first put is a join and takes the next free colour; a `left` snapshot with the same code is replaced. Only a join can be refused: a route room refuses one once any participant has progress, a room with an outcome refuses one, and so does a full room (leavers still count). A claim is kept only while the room has no outcome; the first wins. Route placements: the claimer first, then the rest by progress, leavers unranked. The server sets `updatedAt` and `version`, and always sends `bySelf` false. |
| DELETE | `/rooms/{code}/participants/{userCode}` | | 204 | marks `left`; the snapshot stays, so pooled contributions stay. Idempotent, and succeeds for a pilot who was never in the room. |

Headers: `X-Pilot: <userCode>`, `Authorization: Bearer <secret>`. At most six participants. A room
is deleted 30 days after its outcome, or 180 days after its last write ⚑. Look-ups by code are
rate-limited against guessing. A room is under 4 KB, which is why any free tier will do, and why
the base URL is a build-time setting (`ROOM_SERVER_URL` in `local.properties`, compiled into
`BuildConfig`) that anyone can point at their own server.

On the client the protocol is one interface, `RoomApi`, whose calls never throw for a server-side
condition and instead return a `RoomResult`: `Ok`, `Unreachable` (timeout, DNS, connection error
or a 5xx: the server did not answer, nothing changes locally), `NotFound`, `RoomClosed`,
`RaceLocked`, `RoomFull` or `Unauthorized`. Two implementations exist today: `NoRoomApi`, whose
`isConfigured` is false and which hides every sharing surface, and `FakeRoomApi`, an in-memory
room store that applies every rule in the table above, that unit tests drive to play the other
pilots, and that a debug build without a server uses with a bot crew member. **The fake's test
suite (`FakeRoomApiTest`) is the backend's contract test.** The HTTP client, `HttpRoomApi`, is the
next stage: until it exists, `RoomApiProvider` in `src/release` maps a configured URL to
`NoRoomApi` as well, so a release build can never talk to a server that has not been contract-tested,
and `RoomApiProvider` in `src/debug` uses the fake when the URL is blank and exposes it as
`RoomApiProvider.fake` for the debug receiver.

## Debugging without a server

A debug build with a blank `ROOM_SERVER_URL` runs against `FakeRoomApi`, and `DebugRoomReceiver`
(registered in the debug manifest for `com.silas270.blocktime.DEBUG_ROOM`) plays a second pilot,
"Bot Pilot", by mutating that fake directly, exactly as a second phone's uploads would. Nothing in
it syncs: after each broadcast, bring the app to the foreground or open Challenges, and the real
merge, presentation and log paths run on the result. Its KDoc is the manual checklist; the lines
are:

```
adb shell am broadcast -a com.silas270.blocktime.DEBUG_ROOM --es op join      # the bot joins every room
adb shell am broadcast -a com.silas270.blocktime.DEBUG_ROOM --es op advance   # one step per type
adb shell am broadcast -a com.silas270.blocktime.DEBUG_ROOM --es op break     # the bot's streak dies
adb shell am broadcast -a com.silas270.blocktime.DEBUG_ROOM --es op win       # the bot wins a race
adb shell am broadcast -a com.silas270.blocktime.DEBUG_ROOM --es op present --es mode team|won|placed|broken
```

`advance` moves the bot one step by the room's type (500 km, the next unvisited member, one more
day, or a third of the route). `present` drives one presentation on the newest room that has no
outcome yet and whose type fits the mode (a pool, a race, or a streak): `team` fills the pool and claims, `won` only joins so the pilot's own arrival wins, `placed` joins
and claims the finish, `broken` joins and reports a dead streak. The bot's claims go through
`putSnapshot` with the caller identity switched to the bot for that one call, so the fake resolves
the placements itself.

## Privacy

Nothing changes unless the pilot turns sharing on. Then, and only for rooms the pilot creates or
joins, the server receives the pilot's name and pilot code, the challenge's definition, and the
progress numbers above. It never receives the logbook, flight times, or anything about flights
outside the shared challenge. Members of a room see each other's name, code and progress. Leaving
marks the pilot as left; the room is deleted by the retention rule. [PRIVACY.md](../PRIVACY.md)
carries the pilot-facing version of this paragraph.
