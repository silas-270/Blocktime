# State ownership

Every value the app persists, which store owns it, who writes and reads it, and the rules that
keep two writers from disagreeing.

The organising principle is that **every value has exactly one owning store**. A cache may be
thrown away and rebuilt at any moment without changing anything the pilot sees; if that is not
true of something, it is not a cache but a second source of truth. The home airport is the
cautionary example: when a value like that lives in two stores written by different paths, one
failed write leaves the pilot teleporting to one airport while the passport credits another
country, and nothing can tell which store is right. So the home airport lives only in Room, the
current airport only in `SharedPreferences`, and `domain/AirportPosition.kt` is the one place that
knows which store answers which question.

## SharedPreferences: `blocktime_prefs`

Short-lived, fast-changing, per-device values, and small preferences. Nothing derived lives here.

| Key | Writers | Readers | Notes |
|---|---|---|---|
| `onboarding_completed` | `OnboardingViewModel.saveHomeAirport()`; `CesiumGameActivity` (reset to false when no profile exists) | `CesiumGameActivity` start destination | Written last during onboarding, see [write ordering](#write-ordering). |
| `current_airport_iata` | onboarding; the landing pipeline (Story only, only after the logbook write succeeded); `AccountViewModel.returnHome()`; `FlightSearchViewModel` (dead-end rescue) | `resolveCurrentAirportIata()` only | Four writers, one store. |
| `last_return_home_at` | `AccountViewModel.returnHome()` | `AccountViewModel` | 7-day cooldown. |
| `last_home_base_changed_at` | onboarding (seeded 31 days in the past); `AccountViewModel.changeHomeBase()` | `AccountViewModel` | 30-day cooldown, stamped only after the Room write succeeds. |
| `focused_route_challenge_id` | `ChallengesViewModel` (start, continue, pause); `HubViewModel` (exit, and self-healing clear); `CesiumGameActivity` (clear when the focused challenge completes) | `HubViewModel`, `ChallengesViewModel` | Display-only pointer; never changes a challenge row. |
| `paused_flight` | Check-In (fresh record); `InFlightViewModel` (elapsed time, camera, clear on landing); `AccountViewModel.returnHome()` (clear) | `HubViewModel`, `InFlightViewModel`, `AccountViewModel` | The Story slot. |
| `paused_free_flight` | Check-In; `InFlightViewModel` | `ChallengesViewModel`, `InFlightViewModel` | The Free slot, separate so the two can coexist. |
| `theme_mode` | Settings | `CesiumGameActivity`, via `ThemeModeHolder` | `SYSTEM` until the pilot picks light or dark. |
| `offline_data_saver` | `OfflineModeController` | `OfflineModeController` | The "Offline maps" switch. |
| `map_style` | `InFlightViewModel` | `InFlightViewModel` | The pilot's preferred style; the offline override never writes it. |
| `route_line_mode` | `InFlightViewModel` | `InFlightViewModel` | 0 full (default), 1 window, 2 hidden. |
| `engine_sound_enabled` | `InFlightViewModel` | `InFlightViewModel` | Off by default. |
| `logbook_sort_order` | `AccountViewModel.setSortOrder()` | `AccountViewModel` | A `FlightSortOrder` name; an unknown name falls back to newest first. |
| `online_features_enabled` | `ServerReachability.setOnlineFeaturesEnabled()` (from the Settings switch) | `ServerReachability`, at construction | The "Shared challenges" opt-in. Off by default. Read through the controller, not the preference, so the switch and the server state cannot disagree. |
| `room_secret` | `getOrCreateRoomSecret()`, on the first switch-on, once | nothing yet; the HTTP client of the next stage sends it as the bearer token | 32 characters; the password to the pilot's public code. Never regenerated, never shown ([shared-challenges.md](shared-challenges.md#identity)). |
| `pending_room_leaves` | `LocalChallengeRepository` (a share whose re-check failed, a join the cap refused); `SharedChallengeSyncer` (removed once the server confirms or a live row has the code) | `SharedChallengeSyncer` | Room codes still to be left. A comma-separated string, because codes never contain a comma. |
| `last_room_sync_at` | `SharedChallengeSyncer`, after each completed sync | `SharedChallengeSyncer` | The 60 s debounce of the foreground and screen-open triggers. |

The two paused-flight slots are serialised `PausedFlight` records; see
[paused-flights.md](paused-flights.md) for the format and the rules around writing them.

## Room: `user_data.db`

Durable, user-meaningful data.

| Column or table | Writers | Readers | Notes |
|---|---|---|---|
| `user_profile.home_airport_iata` | onboarding; `AccountViewModel.changeHomeBase()` | `resolveHomeAirportIata()`, and the profile flow | **Sole owner** of the home base. It counts as visited, so a wrong value silently changes the map and geographic achievements. |
| `user_profile.username` | onboarding (a generated "adjective noun" name); `AccountViewModel.updateUsername()` | Passport, Hub greeting, boarding pass | |
| `flight_log` | the landing pipeline | everything derived | The only durable record that a flight happened. |
| `challenges.*` | `LocalChallengeRepository` only | Hub, Challenges, the landing pipeline | Genuinely mutated state, not derivable from history. One owner, but several concurrent writers. |
| `challenges.paused_flight` | Check-In; `InFlightViewModel` through `SessionPausedFlightStore` | Hub, Challenges, In-Flight | Per-challenge slot, written by a scoped statement. |
| `challenges.streak_days`, `last_flown_day` | `creditStreak` only | through `withStreakEvaluatedAt` | The run as of the last day flown, not as of today. |
| `challenges.celebrated` | `markCelebrated`, from the completion overlay | slot and completed-log queries | Whether a completion has been shown yet; written by a scoped statement. |
| `challenges.room_code` | `shareChallenge` (`updateRoomLink`); `joinRoom` (on insert) | everything that asks `isShared()` | Non-null means shared. Unique per pilot; stays on terminal rows so a room is joined once. |
| `challenges.room_state` | `applyRoomState`, `markRoomGone` (`updateRoomState`, `updateSharedFields*`); the first cache on share and join | `crew()`, team progress, the presentation choice | **A cache**: the last room state seen, plus the pilot's own code. May be dropped and rebuilt by the next sync; a corrupt one is dropped on read. |
| `challenges.sync_generation` | the credit paths on a shared row (`bumpSyncGeneration`, or `nextGeneration()` in the credit's own statement) | `isSyncPending()` | Moves with every change to the pilot's own data. |
| `challenges.synced_generation` | `confirmSynced`, from the syncer after an upload | `isSyncPending()` | The generation the server confirmed; the write is a no-op if the row moved on. |
| `challenges.shared_outcome` | the credit paths (own completion); `applyRoomState` (`updateSharedFields*`) | the presentation, the log stamp, `resolveLandingOutcome` | **A fact**: how the room ended and whether that was this pilot. Kept after the room itself is gone. |
| `achievement_unlocks` | `LocalAchievementsRepository` (`INSERT OR IGNORE`) | badge ordering | The only persisted achievement state, and only a timestamp. |

## Derived, never persisted

`VisitedGeography`, `AchievementBoard`, `FlightStats`, `FlightHighlights` and the list of `Tour`s
are derived once by `PilotProgressRepository` from the flight log and the home airport, and shared
by every screen that shows them. Why that derivation is a cache and not a second source of truth is
explained in [achievements.md](achievements.md#the-shared-derivation).

## Process-level caches

All discardable; each is rebuilt on demand and none is a source of truth.

| Cache | Scope | Why it is safe |
|---|---|---|
| `AirportRouteSqliteDataSource.connection` | process | read-only asset that nothing writes |
| `airportIndex` (every IATA airport and the search index) | process | immutable within a build; assigned only on success |
| `cachedNetwork` (the main route network) | process | pure function of the immutable routes table; assigned only on success |
| `cachedContinentCountryMap` | process | same; a failure throws instead of caching an empty map |
| `PendingFlightLoader.loadedFlightKey` | process | only answers "is this flight already in the engine" |
| `FlightClock` zone cache | process | `ZoneId` lookups by name |
| `WorldMapParser.cachedMap` | process | parsed from a bundled SVG, warmed at start-up |
| `MergedMapPathsCache` | process, one entry | pure function of its key |
| `PaperGrainTexture` | process | deterministic, seeded |
| `hub_route_map_v2_<IATA>.png` | disk, five files plus pinned | see [maps.md](maps.md#the-cache) |

"Assigned only on success" is load-bearing for the three `flights.db` caches. Those queries throw
`AirportDataException` on failure instead of returning an empty result, precisely so that a
transient error cannot be memoised as "the world has no countries" for the rest of the process
([flight-data.md](flight-data.md#failing-loudly-where-it-matters)).

## Write ordering

Sequences within one path whose order is part of their correctness:

1. **Landing** (`InFlightViewModel.completeFlight`): logbook → current airport (Story only) →
   clear the paused flight. The last two happen only if the logbook write succeeded. A pilot at
   their destination with no flight explaining it cannot be repaired; a pilot still at the origin
   with a resumable flight can ([core-loop.md](core-loop.md#the-post-landing-pipeline)).
2. **Onboarding** (`OnboardingViewModel.saveHomeAirport`): profile row → current airport →
   cooldown seed → `onboarding_completed`. The flag is last, so the only state a failure can leave
   behind is "not yet onboarded", which is both true and recoverable. The reverse order would leave
   the app permanently onboarded with no profile, and every screen that resolves the pilot would
   throw. The whole sequence is behind a mutex, and an existing profile is updated rather than a
   second one inserted, so a double tap or a retry after a partial failure cannot create two rows.
3. **Change home base** (`AccountViewModel.changeHomeBase`): Room write → cooldown stamp.
4. **Return home** (`AccountViewModel.returnHome`): clear a stranded Story flight → move the pilot →
   cooldown stamp ([modes.md](modes.md#the-two-cooldowns)).
5. **Starting a flight** (Check-In): write the fresh paused-flight record → navigate
   ([paused-flights.md](paused-flights.md#starting-a-flight)).
6. **Sharing a challenge** (`shareChallenge`): create the room on the server, outside the lock →
   under the lock, re-read the row and re-check that it is still shareable → link it
   (`updateRoomLink`, code and first cache in one statement). A row that changed in between is
   not linked and the room's code goes to `pending_room_leaves`, so a room is never linked to a
   row with progress and never leaked ([shared-challenges.md](shared-challenges.md#sharing-info-modal-share-challenge), S8).
7. **Joining a room** (`joinRoom`): put the first snapshot, which is the join on the server,
   outside the lock → under the lock, re-check the cap and insert the row. The server has the
   pilot before the row exists, so a cap that filled in between leaves the room again, and a
   process death in between is repaired by the next join with the same code, which is an upsert
   (J5, J14).
8. **Abandoning a shared row** (`abandonChallenge`): the row is deleted, as for a solo row, and
   nothing else happens; the server is not told and the pilot's snapshot stays in the room until
   its retention ([shared-challenges.md](shared-challenges.md#abandoning), A2).
9. **Syncing a row** (`SharedChallengeSyncer.syncNow`): reply → apply (`applyRoomState`, one
   scoped statement under the lock on a freshly read row) → confirm (`confirmSynced`, guarded by
   the generation). A process death between any two leaves the row pending and the upload is
   repeated; applying a room state to a row deleted meanwhile does nothing and never re-inserts
   ([shared-challenges.md](shared-challenges.md#sync-moments), Y3, P15).

## Concurrent writers

Write ordering is about the sequence inside one path. The other half is two paths writing the same
thing at the same time, which "one owning store" says nothing about.

**A whole-row `@Update` writes every column, not just the one that changed.** Two of them racing is
a lost update, never a merge: whichever writer read first and wrote last silently reverts the
other's columns. The `challenges` row has three writers that can overlap: the landing clearing the
paused-flight slot, the landing crediting a Route leg, and `InFlightViewModel` saving the camera
pose and elapsed time.

The overlap is not hypothetical. Inside a `NavHost` the lifecycle owner of a screen is its
back-stack entry, so popping In-Flight for the arrival screen fires In-Flight's `ON_STOP`, and with
it a camera save, while the landing is still writing
([navigation.md](navigation.md#viewmodel-lifetime-and-one-consequence)). A camera save that read the
row before the landing and wrote it back afterwards would put the challenge back at its previous
leg and make the just-landed flight resumable again.

Three mechanisms, at three layers, rule that out:

| Layer | Mechanism | What it makes impossible |
|---|---|---|
| Schema | `ChallengeDao.updatePausedFlight`, `updateRouteProgress`, `markCelebrated`, and the sync path's `updateSharedFields`, `updateSharedFieldsAndClearPausedFlight`, `updateRoomState`, `bumpSyncGeneration`, `confirmSynced`, `updateRoomLink` | one concern's writer reverting another concern's columns |
| Repository | `LocalChallengeRepository.writeMutex` | two read-modify-writes of the *same* concern interleaving; the cap check passing twice |
| Session | `SessionPausedFlightStore` | a landed flight's slot being written again after the landing clears it |

The mutex is a plain in-process `Mutex`, which is sound only because the repository is a singleton
built once in the Activity and is the table's only writer. It is not reentrant, so only the public
entry points take it and every private helper beneath them is lock-free. Distance, set and streak
credits still use a whole-row `update`, but only inside the mutex and only for their own row's
concern, while the paused-flight and Route writers use scoped statements.

`SessionPausedFlightStore` is worth reading for its shape. The seal is on `clear()` itself, not on
a separate "mark landed" call, because "the landing has taken this slot" is exactly the condition,
and coupling the two removes the ordering question instead of moving it somewhere else: a save that
arrives before the clear is overwritten by it, and one that arrives after is refused. It wraps the
store per **session**, not per store, because the Story and Free slots are process-wide objects and
sealing them directly would also refuse the next flight's writes. `InFlightViewModel` also skips
assembling a write once `landingStarted` is set, but that is a courtesy; the seal is the guarantee,
and it does not depend on the flag being observed before the racing read.

`ChallengeRowConcurrentWriteTest` exercises both directions of the race (a paused-flight write
straddling the landing, and a Route advance carrying a stale slot), the resurrect case, and an
uncontended control, against a fake DAO with Room's real write granularity.

Shared challenges add a fourth writer to the row, the sync, and two rules keep it from reverting
a landing. **The sync path uses only scoped statements**: `applyRoomState` writes the cache alone
(`updateRoomState`) when only foreign progress arrived, the shared fields (`updateSharedFields`)
when the merge ended the challenge, and the shared fields plus `paused_flight = NULL` in one
statement (`updateSharedFieldsAndClearPausedFlight`) when a foreign win ends a race with a paused
leg, so no camera save can slip a flight back in between the status flip and the clear. It runs
under the same mutex on a row read under it, and **no network call ever runs under the mutex**:
the sharing operations read, call the server, and only then take the lock to re-read and write, so
a round trip never stalls a landing credit behind the server's latency.

**A generation pair, not a flag, records what the server has.** A landing can be credited while an
upload of the previous snapshot is in flight; a "needs upload" boolean would be cleared by that
upload's reply and lose the landing until the next one. Instead the credit paths move
`sync_generation` (`bumpSyncGeneration` is `sync_generation + 1` in SQL, so two racing credits both
count whatever either read, and the whole-row credits write `nextGeneration()` in their own
statement), and the syncer's `confirmSynced(id, generation)` writes `synced_generation` only
`WHERE sync_generation = :generation`. A row that moved on during the upload stays pending, and
the next sync sends the newer state ([shared-challenges.md](shared-challenges.md#data-model), L11).
`LocalChallengeRepositorySharedTest` and `SharedChallengeSyncerTest` cover the race and the
abandon-mid-sync case against the same fake DAO.

## Facts and the clock

A third category, separate from both "owned" and "derived": state that is a correct record of the
past but not an answer about the present.

A streak row says "the run was N days long as of this date". Whether the run is still alive depends
on today's date, and nothing runs at midnight to notice when it dies, so the stored row is not a
stale write to repair. The resolution happens on read, once, at the repository boundary
([challenges.md](challenges.md#reading-through-the-clock)).

The rule this generalises to: **if answering a question needs the current time, the answer is not
state.** Store the facts and resolve them on read. The same rule shapes `Tour.isOpenAt(now, zone)`
on the derived side ([achievements.md](achievements.md#tours)) and
`HomeBaseCooldown.isEligible(now, ...)`, which takes the time as a parameter rather than reading a
clock. It is also why the derived snapshot can be a pure function of Room's data: nothing in it
depends on when it was computed.

Shared streaks follow it twice. Whether a crew member's streak is alive is derived when the room
state is merged, from their snapshot's `lastFlownDay` and `updatedAt` against today with two days
of slack for time zones, and the pilot's own from `isOwnStreakAlive(today, zone)`; nothing stores
"alive" as a column, and the same function feeds both the uploaded snapshot and the merge's own
verdict ([shared-challenges.md](shared-challenges.md#the-merge)). And the sync debounce is a
stored fact plus a comparison: `last_room_sync_at` records when the last sync completed, and the
syncer decides on each request whether 60 s have passed, rather than keeping a timer.

## Migrations

`AppDatabase` is at version 11 with `exportSchema = true`. Versions 6 → 7 (streak columns), 7 → 8
(challenge icon), 8 → 9 (predefined-route columns), 9 → 10 (`celebrated`, with existing
completions backfilled as already celebrated) and 10 → 11 (the five shared-challenge columns and
the unique `(user_id, room_code)` index; existing rows stay solo) are explicit `Migration`
objects. Versions 1 to 5
have no exported schema and are listed in `fallbackToDestructiveMigrationFrom`, the only case in
which the database is rebuilt.

Two details recur in these migrations. SQLite cannot add a `NOT NULL` column without a default, so
such columns get `DEFAULT 0` in the migration SQL, and the entity has to declare the same
`defaultValue`, because Room compares declared defaults when it validates a migrated schema and
would otherwise reject a migration that is actually correct. And every migration leaves existing
rows meaning what they meant before: pre-existing Route challenges stay free-form, pre-existing
completions do not replay their celebration.

A schema change without a matching migration fails at launch instead of wiping data; a crash is
fixable, deleted flights are not. `MigrationTest` checks each migration against the exported schema
of the version it migrates from, which is why those JSON files are kept in `app/schemas/`.

## Two sharp edges

- **The dead-end rescue writes the current airport from a screen.** `FlightSearchViewModel` moves a
  Story pilot whose origin has no routes at all to the nearest large network airport, and persists
  it. It fires only on a genuinely empty result, never on a failed query, and the screen says what
  happened ([route-network.md](route-network.md#the-dead-end-rescue)).
- **A blank home airport means "no home base".** Callers reach the value both through
  `resolveHomeAirportIata`, which normalises blank to null, and by reading the profile field
  directly; `getVisitedGeography` and `PilotProgressRepository` normalise it themselves so the two
  routes cannot disagree.
