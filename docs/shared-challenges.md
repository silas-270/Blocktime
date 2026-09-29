# Shared challenges

> **Status: design under implementation.** This file was written before the code and is the
> contract the code is built against. Every row of the situation tables below is the name of a
> unit test. Where the code and this file disagree, the code is wrong until this file is changed.

A challenge can be shared with other pilots through a six-character room code. Everyone in the
room works on the same goal, and the goal ends for everyone at the same moment. This file explains
the data model, how a room is kept in sync without a conflict ever arising, what happens in every
situation the app can be in, and what the server has to do. It builds on
[challenges.md](challenges.md) and [network.md](network.md).

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

There are no push notifications. The app asks the server at exactly these moments:

| Trigger | Reason | Debounce |
|---|---|---|
| The app comes to the foreground (`CesiumGameActivity.onStart`, which covers cold start) | `FOREGROUND` | 60 s |
| The Challenges screen opens | `SCREEN_OPEN` | 60 s |
| A landing has been credited | `LANDING` | none |
| Share, look-up, join | `USER_ACTION` | none |

A sync first uploads any row whose own data changed since the last upload, then reads every other
shared row, and applies the result. Uploading returns the fresh room state, so push and pull are one
round trip.

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

A probe (`GET /health`, 3 s) runs at the sync moments above and before any user action, and its
result is cached for 30 s. Every API call also reports its outcome, so a dead server is noticed by
the first request that hits it. Nothing polls in the background.

`onlineAvailability(state)` turns that into what the UI needs: `HIDDEN` for `NOT_CONFIGURED` and
`DISABLED`, `DISABLED_OFFLINE`, `DISABLED_UNREACHABLE`, `DISABLED_CHECKING`, or `ENABLED`. Every
sharing button asks only this function.

## Data model

### Columns added to `challenges` (schema 11)

| Column | Type | Meaning |
|---|---|---|
| `room_code` | text, nullable | Non-null means shared. Unique per pilot. Stays on completed rows so a finished room cannot be joined twice. |
| `room_state` | text, nullable | The last room state seen, as JSON, together with the pilot's own code. **A cache**: it may be thrown away without changing anything the pilot owns. |
| `sync_generation` | int | Incremented by every local change to the pilot's own data on this row. |
| `synced_generation` | int | The generation the server last confirmed. The row needs uploading iff the two differ. |
| `shared_outcome` | text, nullable | How the room ended: who completed it, whether that was this pilot, placements, or who broke the streak. **A fact**, kept for the log. |

The two generation counters exist because of one race: a landing can be credited while an upload
of the previous snapshot is in flight. A boolean "needs upload" flag would be cleared by the
upload's reply and lose the landing until the next one. With counters, the upload confirms the
generation it sent, and the confirmation is a no-op if the row moved on, so the row stays pending
and the next sync sends the newer state.

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
OutcomeClaim         Completed(at) | Failed(brokenBy)
```

On the wire and in `room_state` these are **flat JSON objects with nullable fields and a `kind`
string** for the outcome and the claim, not the Kotlin sealed types. Gson cannot read a sealed
interface polymorphically and bypasses Kotlin constructors, so a missing field would become null
rather than its default. The mapping to the Kotlin types happens in code, with explicit defaults,
which is also what lets two app versions share a room.

`bySelf` is stamped on the outcome when the room state is merged, because the merge knows the
pilot's code. Everything that later needs to know "was this me" (the landing outcome, the choice of
presentation) can then read it from the row alone, without a repository or a profile.

### Team progress is derived, never stored

`progressFraction()` keeps its meaning, the pilot's own progress. For shared rows the screens call
`displayProgressFraction()`, which is the team's progress for the three pooled types and the pilot's
own for a race:

| Type | Team progress | Pilot's own part |
|---|---|---|
| Set | union of every participant's visited members, over the current definition | local `visitedSetMembers` |
| Distance | sum of every participant's kilometres | local `cumulativeDistanceKm` |
| Streak | minimum streak over the crew | local `streakDays`, after `withStreakEvaluatedAt` |
| Route | the pilot's own progress; the crew is a ranking | local route progress |

**The pilot's own values always come from the local row, never from their own snapshot in the
cache.** The row is credited at landing; the snapshot is uploaded later. Participants marked `left`
are excluded from the streak minimum and the race ranking, and their pooled contributions stay.

### Identity

There is no account. The pilot code already on the profile (`user_code`, six characters) is the
public identity, and a 32-character secret generated locally the first time sharing is switched on
is the password. The server binds a code to its secret on the first write it sees. Both live in the
app's own storage and travel with an Android backup, so a restored phone can write to the same
rooms; two phones restored from one backup both can, and the last write wins.

## The merge

`mergeRoomIntoChallenge(local, room, selfCode, today, now)` is a pure function of the row, the room
state, the pilot's code and the clock. It returns the new row, whether it changed, and an optional
claim to send back. Rules, in order:

1. Always refresh the cache with the new room state.
2. The row is terminal and already presented: nothing else.
3. The row is terminal but not yet presented, and the room has an outcome: **the server wins.**
   Status follows the outcome's kind, the outcome is copied. This is how a rejected claim resolves
   (someone finished seconds earlier: second place instead of a win) and how "completed locally
   while offline, but the group streak broke meanwhile" resolves (a failure).
4. The row is active and the room says `Completed`: the row completes, unpresented, with the
   outcome and `bySelf` stamped. A route row also drops its paused leg, because a paused leg of a
   finished race is worthless and would otherwise still offer RESUME.
5. The row is active and the room says `Failed`: the row fails, unpresented, likewise.
6. The row is active and the room has no outcome yet, per type:
   - Set: the union covers every current member: complete, claim `Completed`.
   - Distance: the sum reaches the target: complete, claim `Completed`.
   - Streak: any crew member is dead (`streakAlive == false`; for a snapshot that has not been
     refreshed, `lastFlownDay` older than the day before yesterday, one day of slack for time
     zones), or the pilot's own streak is dead by the local rule: fail, claim `Failed(brokenBy)`.
     Otherwise the minimum reaches the target: complete, claim `Completed`.
   - Route: never terminal from a merge; a race is won only by an arrival.
7. `changed` is any row change beyond the cache.

**The merge never touches the pilot's own data.** It cannot, by construction: the only inputs to
the pilot's own columns are the landing pipeline and the pilot's own actions.

Whoever sees a pool fill first claims the completion; the server keeps the first claim and ignores
the rest. A client that claimed and was refused finds the real outcome in the reply and applies rule
3. Claims are idempotent and need no coordination.

The pilot's own streak aliveness in a snapshot is `currentStreak(today) > 0`, or, before the first
flight, "joined today or yesterday". The day of joining and the day after are not yet a missed day,
which is the same tolerance `currentStreak` gives yesterday.

## Landing

The post-landing pipeline ([core-loop.md](core-loop.md#the-post-landing-pipeline)) is unchanged in
order and in what it guarantees. Two credit rules are extended for shared rows:

- **A pooled challenge can be filled by the pilot's own landing.** `creditDistance` and
  `creditSetCompletion` test the sum or union of the local row plus the cached snapshots of the
  others, not the pilot's own number alone. Otherwise a landing that fills the pot would show
  "advanced" and complete only at the next sync.
- **A shared streak never completes from a landing.** `creditStreak` writes the streak columns and
  bumps the generation, and leaves the status alone, because completion depends on the crew's
  minimum, which only the merge knows.

Every credit to a shared row bumps `sync_generation`. Whole-row updates inside the credit
functions stay as they are: they run under the repository's write mutex on a row read under that
same lock ([state.md](state.md#concurrent-writers)).

`resolveLandingOutcome` gains one condition. A row that went from active to terminal counts as
*completed by this landing* only when `shared_outcome` is absent or stamped `bySelf`. A foreign
completion that happens to arrive between the pipeline's before and after reads is therefore not
shown as the pilot's own win on the outcome screen; it is presented on the Challenges screen like
every other foreign completion. **The outcome screen only ever shows what the pilot's own landing
did.**

After the outcome is published, the landing asks for a sync (`LANDING`, no debounce) on the
syncer's own scope, never on `landingScope`, so `InFlightViewModel.onCleared` does not wait for
the network and the outcome screen is never delayed by it.

Where CONTINUE goes after arrival is one pure function, `resolveArrivalDestination(result,
hasPendingPresentation)`, used by both the arrival screen and the outcome screen: any completion in
the result, or any terminal row still waiting to be presented, sends the pilot to Challenges;
otherwise to the Hub. That second condition is what lets a completion that arrived mid-flight be
seen right after landing rather than the next time the pilot happens to open Challenges.

## Presentation

Completion presentation ([challenges.md](challenges.md#completion-presentation)) is reused
unchanged in its mechanics. What changes is what it says and what it can present:

| Presentation | When | Copy |
|---|---|---|
| Solo | not shared | as today |
| Team | shared pool, completed by anyone | "CREW ×N" badge, full confetti |
| Race won | shared route, `bySelf` | "YOU WON THE RACE", gold |
| Race placed | shared route, not `bySelf` | "ANNA WON · YOU FINISHED 2ND", half the confetti, cooler colours, button "GG" |
| Broken | shared streak, `FAILED` | a separate overlay: the card lifts to the centre under a darker scrim, "STREAK BROKEN · Anna missed a day" (or "You missed a day"), then cracks and shatters into falling shards |

**A failed row is deleted when its shatter finishes.** The completed log stays a log of successes,
and the slot frees at that moment, exactly as a celebration frees its slot. Until then the failed
row occupies its slot and counts against the cap like an unpresented completion, so the slot
queries match `ACTIVE`, or `COMPLETED`/`FAILED` with `celebrated = 0`.

The celebration queue becomes reactive. Today it is computed once when the Challenges ViewModel is
created; a foreign completion that arrives after the screen opened would then wait for the next
visit. Now the queue holds ids fed from the slot flow, with a set of ids already seen so that a row
mid-animation is never queued twice, and the currently presented row is resolved by id from the
latest emission, so a sync that corrects the outcome after enqueuing still presents the corrected
one.

## Situations

Each row is the name of a unit test. `⚑` marks a default that was chosen for the design rather
than decided by the product.

### Landing (Story or Challenge flight; a Free flight returns before anything, as today)

| # | State of the shared row | Result |
|---|---|---|
| L1 | Active pool, team target not reached | Credited locally as today; generation bumped; outcome screen shows *advanced* with team progress; then a `LANDING` sync. |
| L2 | Active pool, this landing fills the pool (local row plus cached others) | Completed locally, outcome `Completed(self, bySelf)`, unpresented; outcome screen shows *completed*; CONTINUE goes to Challenges; the sync sends the claim. If the server refuses, rule 3 replaces the outcome and the presentation shows the server's truth. |
| L3 | Active pool, a sync during the flight already completed the row | Only active rows are credited, so nothing is credited. The flight is logged. Outcome is *none*, a presentation is pending, so CONTINUE goes to Challenges. The pilot's kilometres are uploaded but were no longer needed. |
| L4 | Active shared streak, the day counts | Streak columns written and generation bumped; **not** completed. Outcome shows *advanced* or nothing. Completion or failure is decided by the merge after the upload. |
| L5 | Active race, leg flown under it, destination not reached | Advanced as today; generation bumped; the ranking updates after the sync. |
| L6 | Active race, arrival at the destination | Completed locally with `Completed(self, bySelf, placements = [self])`; the claim goes with the landing sync. Confirmed: a win. Refused: rule 3 gives a placement, presented as "2ND". |
| L7 | Race, the row turned terminal during this leg (sync) | `advanceRouteChallenge` is a no-op on a non-active row, its existing contract. The paused-flight slot is still cleared by the scoped statement. Flight logged. CONTINUE goes to Challenges. |
| L8 | A sync lands **between** the pipeline's before and after reads and completes the row for someone else | `resolveLandingOutcome` sees active to terminal with `bySelf = false` and reports nothing for this row. Presented on Challenges. No foreign win on the outcome screen. |
| L9 | Terminal and presented (any type) | Nothing: not active, not credited, not synced. |
| L10 | Device offline, server unreachable, or sharing switched off | L1 to L7 identical locally. The sync ends in *skipped* or *unreachable*; the row stays pending until the next successful sync. The outcome screen does not depend on it. |
| L11 | A credit arrives while an upload for the same row is in flight | The credit bumps the generation to g+1. The upload's reply is merged; confirming g is a no-op; the row stays pending; the next sync sends g+1. Nothing is lost. |
| L12 | The logbook write failed | As today: *none*; nothing credited, nothing synced. |

### Applying a room state

| # | Local row | Room | Result |
|---|---|---|---|
| P1 | Active | Open, only foreign progress | Cache refreshed. Slot ring and modal show the new team state. No presentation. ⚑ No toast in the first version. |
| P2 | Active pool | Open, union or sum reaches the target | Completed, unpresented, claim sent at once; the reply carries the outcome (this pilot's, or a faster one's) and rule 3 finalises it. Presented on the next visit, or at once if the screen is open. |
| P3 | Active | `Completed` by someone else | Completed, unpresented, `bySelf = false`. A race drops its paused leg. Pool: team celebration. Race: "X WON · YOU FINISHED Nth". |
| P4 | Active streak | A crew member (or this pilot, by the local rule) is dead | Failed, claim `Failed(brokenBy)`, shatter on the next visit. |
| P5 | Active streak | `Failed` from the server | Failed, outcome copied. |
| P6 | Active race with a paused leg | `Completed` (someone else won) | As P3; the paused leg is deleted, the slot loses its pause badge, the modal becomes read-only. |
| P7 | Completed, unpresented (own landing), claim refused | Someone else's outcome, same kind | Status stays, outcome replaced (rule 3). |
| P8 | Completed, unpresented | `Failed` | The server wins: status becomes failed, shatter instead of celebration (rule 3). |
| P9 | Presented | anything | Cannot happen: presented rows are not synced. The log entry freezes at presentation. |
| P10 | Active | A participant joined or left | Cache refreshed; the crew list changes. Pool: a leaver's contributions stay. Streak and race: a leaver no longer counts. |
| P11 | Active | Not found (deleted by the server's retention) | `roomGone`; the row keeps working locally; the modal says "Room closed · continuing solo"; no second share. |
| P12 | Any | Unauthorised | As P11, plus a log line. Only possible if another pilot bound the same code first. |
| P13 | Any | Unreachable | Nothing; the reachability signal is told. |
| P14 | Active set, the set's definition changed in an app update | Old catalog id | As today through `withSetDefinitionResolved`: the current definition counts and the union is filtered against it. App versions may differ within a room; snapshots carry raw data. |
| P15 | Deleted during the sync (abandoned) | Any | Applying reads a missing row and does nothing. Never re-inserts. Confirming is a no-op. |

### Sharing (info modal, "SHARE CHALLENGE")

| # | State | Result |
|---|---|---|
| S1 | Not configured, or sharing off | The button does not exist. |
| S2 | Sharing on, device offline, server unreachable, or probe running | Button dimmed with the reason. |
| S3 | Reachable, row active, not shared, zero progress (set empty; 0 km; 0 streak days; route at its origin on leg 0 with no paused leg) | Room created, then re-checked under the lock (S8), then linked. The modal shows the code large, a copy button and the share sheet ("Join my Blocktime challenge: CODE"). Crew: this pilot. |
| S4 | Reachable, progress above zero | Button dimmed: "Only a fresh challenge can be shared". |
| S5 | Already shared | Instead of the button: code, copy, share sheet and the crew list, also while offline. |
| S6 | Creating the room fails as unreachable | No local change; "Server not reachable, try again". |
| S7 | Terminal row | No sharing. |
| S8 | The row changed between creating the room and taking the lock (a landing, an abandon, a second share) | The re-check fails, the room is left again (queued if unreachable), result *not eligible*. |

### Joining (picker, "Have a code?")

| # | State | Result |
|---|---|---|
| J1 | Not configured, or sharing off | The field does not exist. |
| J2 | Sharing on, not reachable | Field dimmed with the reason. |
| J3 | Unknown code | "No challenge with that code". |
| J4 | Room open, a slot free, not a member | Look-up shows a preview (name, type, target, crew, warnings), JOIN uploads the first snapshot, then under the lock re-checks the cap and builds the row from the definition. The result is published as the existing `Started`, so the picker closes, a route is focused and the Hub opens exactly as after starting a challenge. |
| J5 | The cap is full after the server accepted | The room is left at once; the existing "CHALLENGE SLOTS FULL" modal. |
| J6a | A local active row has this code | Opens that slot's modal. |
| J6b | A local completed row has this code | "You already finished this challenge". No insert. |
| J7 | The room is completed or failed | "This challenge is already over". |
| J8 | A race with any progress | "The race has already started". The preview already warns. |
| J9 | A pool or streak already in progress | Allowed. The streak preview warns "Joining resets the group streak", because the minimum drops to zero. |
| J10 | Room full (six) | "This crew is full". |
| J11 | Unreachable after the preview | Hint; no local change. |
| J12 | The definition is unknown to this app version | "Update Blocktime to join this challenge"; the room is left again. |
| J13 | The code is in the pending-leaves list (rejoin after an offline abandon) | The code is removed from the list, then J4. The server replaces the left snapshot with the new one. |
| J14 | Process death between the server accepting and the local insert | The server has the pilot, the app has no row. The next join with the same code is an upsert and creates the row. ⚑ Accepted. |

### Abandoning

| # | State | Result |
|---|---|---|
| A1 | Not shared | As today: the row is deleted. |
| A2 | Shared, active, reachable | The modal adds "Your crew keeps the challenge. You leave the room." The row is deleted, the code goes to the pending-leaves list, and a `USER_ACTION` sync leaves the room. Leaving is one path, online or not. |
| A3 | Shared, unreachable or sharing off | As A2; the leave is sent by the next successful sync. |
| A4 | Shared race with a paused leg | The paused flight goes with the row, as today. |
| A5 | The pilot created the room | No special case. Rooms have no owner. |
| A6 | Terminal, unpresented | **Cannot abandon.** ABANDON is not shown for a non-active row; the presentation is the only way out. |
| A7 | Terminal, presented | Cannot abandon; log entries are untouchable, as today. |

### Lifecycle and visibility

| # | Situation | Result |
|---|---|---|
| Y1 | App to the foreground (cold start or from the background) | `FOREGROUND` sync, 60 s debounce. Runs during In-Flight too; In-Flight never presents challenge state, and L8 covers the window. |
| Y2 | Challenges opens | `SCREEN_OPEN` sync, debounced; the reactive queue picks up what arrives. |
| Y3 | Process death mid-sync | Each application of a room state is one scoped statement. Order: reply, apply, confirm. Death in between leaves the row pending and the upload is repeated. Idempotent. |
| Y4 | Sharing switched off | Sync stops; state `DISABLED`. Rows stay shared; nothing is left automatically ⚑. Switching on resumes. |
| Y5 | Sharing switched on for the first time | The secret is generated, an information modal explains what is shared, a probe runs. |
| Y6 | The server is gone for good | Every trigger ends unreachable, sharing UI is dimmed, the cached crew stays visible. Shared challenges keep working and can complete locally (L2 with an unconfirmed claim; the presentation shows the local state). |
| Y7 | The device clock jumps | Own streak as today (a future day is alive). Others' snapshots get one day of slack. |
| Y8 | Two phones restored from one backup, same pilot code | Both hold the secret; the last write wins. ⚑ Accepted. |
| Y9 | App update with a newer wire shape | Unknown JSON fields are ignored; missing ones get defaults in the mapping, not in a constructor. |
| Y10 | On the Hub, a sync ends the focused route | The Hub refreshes on each sync summary, and `resolveFocusedChallenge` clears the focus as it already does for a non-active row. |
| Y11 | The info modal is open and a sync changes the row | The modal holds only the id and reads the row from the live slot list, so it shows the change; a terminal row turns it read-only. |

### Per type

| Type | Can be shared | Shown progress | Ends | Presented as |
|---|---|---|---|---|
| Route | at its origin, leg 0, no paused leg; joining locks at the first progress | own; the crew is a ranking (winner first, then by progress) | the first arrival (server claim); everyone else ends at that moment with a placement, paused legs deleted | winner: "YOU WON THE RACE"; others: "ANNA WON · YOU FINISHED 2ND"; log stamp "1ST", "2ND", … |
| Set | with an empty set | union over the current definition; the checklist colours each member by who visited it | the union is complete, by own landing (L2) or merge (P2) | team celebration, log stamp "CREW ×N" |
| Distance | at 0 km | sum; a segmented bar in join order, own segment emphasised | the sum reaches the target, by own landing (L2) or merge (P2) | as set |
| Streak | at 0 days | the crew's minimum; the modal lists each pilot's days and state (alive, at risk, broken) | merge only: minimum reaches the target, or one pilot is dead | completed as set; failed: shatter, "STREAK BROKEN · Anna missed a day" |

## Protocol

The server is a key-value store with five routes. It knows nothing about flights.

| Method | Path | Body | Reply | Rules |
|---|---|---|---|---|
| GET | `/health` | | 204 | the probe |
| POST | `/rooms` | definition, own snapshot | room state | code: six characters from `ABCDEFGHJKLMNPQRSTUVWXYZ23456789`; creator gets colour 0 |
| GET | `/rooms/{code}` | | room state, or 404 | |
| PUT | `/rooms/{code}/participants/{userCode}` | snapshot, optional claim | room state; 404; 409 with `RaceLocked`, `RoomClosed` or `RoomFull`; 401 | Upsert. A stranger's first put is a join and takes the next free colour; a `left` snapshot with the same code is replaced. A route room refuses a join once any participant has progress. A claim is kept only while the room has no outcome; the first wins. Route placements: arrivals in order of arrival, then the rest by progress. The server sets `updatedAt` and `version`. |
| DELETE | `/rooms/{code}/participants/{userCode}` | | 204 | marks `left`; the snapshot stays, so pooled contributions stay |

Headers: `X-Pilot: <userCode>`, `Authorization: Bearer <secret>`. At most six participants. A room
is deleted 30 days after its outcome, or 180 days after its last write ⚑. Look-ups by code are
rate-limited against guessing. A room is under 4 KB, which is why any free tier will do, and why
the base URL is a build-time setting that anyone can point at their own server.

On the client the protocol is one interface, `RoomApi`, with three implementations: `HttpRoomApi`
for a configured server, `NoRoomApi` when none is configured (everything hidden), and
`FakeRoomApi`, an in-memory room store that unit tests drive to play the other pilots and that a
debug build without a server uses with a bot crew member, so the whole flow can be walked on a
phone before a server exists. The fake's test suite is the backend's contract test.

## Privacy

Nothing changes unless the pilot turns sharing on. Then, and only for rooms the pilot creates or
joins, the server receives the pilot's name and pilot code, the challenge's definition, and the
progress numbers above. It never receives the logbook, flight times, or anything about flights
outside the shared challenge. Members of a room see each other's name, code and progress. Leaving
marks the pilot as left; the room is deleted by the retention rule. [PRIVACY.md](../PRIVACY.md)
carries the pilot-facing version of this paragraph.
