# Architecture

How the app is put together: what it is for, how its layers depend on each other, where state
lives, and how the native engine is built into it.

## What Blocktime is

A focus timer shaped like a flight. The pilot books a real scheduled route between two real
airports, and the flight's scheduled duration *is* the study session. While the timer runs, a
native 3D globe renders the aircraft flying that route. On landing the flight goes into a
logbook, the pilot's position moves to the destination, and everything derived from that
history (visited countries, achievements, challenge progress) follows.

Two properties shape most of the decisions described in these documents.

**It is a study tool first and a game second.** Every mechanic is measured against how much
friction it puts in front of starting a session. The one-tap path from the Hub to a running
timer is deliberately kept short: in Flight Search, tapping the centred route card books it
directly, and the Hub globe itself is a booking button.

**Everything real-world stays real-world.** The bundled database holds real scheduled routes
between real airports, and there are no invented ones. Because position only moves by flying,
getting across the world means finding a real path across it, which is the game. The authored
itineraries in `PredefinedRouteCatalog` obey the same rule: every leg is a route that exists in
`flights.db`, and `PredefinedRouteCatalogTest` checks each one leg by leg against the database
(see [challenges.md](challenges.md)).

The app has no account and, unless the pilot opts in, no server. The network is used for
live-globe map tiles and one optional destination photo per flight, and the app is fully usable
without it ([network.md](network.md)). The one opt-in online feature, shared challenges, treats
the server as a relay and keeps working as a local challenge when it is gone
([shared-challenges.md](shared-challenges.md)).

## Stack

| Layer | Technology |
|---|---|
| UI | Jetpack Compose, Material 3, Navigation Compose |
| Presentation | One ViewModel per screen exposing `StateFlow`s, each built by a hand-written `ViewModelProvider.Factory` |
| Persistence | Room (`user_data.db`), a bundled read-only SQLite asset (`flights.db`), `SharedPreferences` |
| 3D engine | CesiumRS, a Rust/wgpu globe renderer built into `libcesium_rs.so` |
| Native bridges | JNI for the live engine, JNA for offscreen renders |
| Host activity | `GameActivity` from androidx.games |
| Build | Gradle Kotlin DSL, KSP for Room, `cargo-ndk` for the Rust library |

`minSdk` 26, `targetSdk` 36, `compileSdk` 37. Only `arm64-v8a` and `x86_64` are built, the two
ABIs the native library is compiled for ([engine.md](engine.md)).

## Layers

```
CesiumGameActivity            composition root · NavHost · engine lifecycle
   │
   ├── ui/screens/            Compose screens, one package per destination
   ├── ui/components/         cross-screen composables (world map, modals, buttons)
   ├── ui/viewmodel/          one ViewModel per screen
   ├── ui/map/, ui/theme/     world-map parsing and projection, colours, design scale
   │
   ├── domain/                orchestration and pure logic: route network, search,
   │                          next-leg resolution, map-style policy, thrust model
   ├── audio/                 real-time engine-sound synthesis
   │
   ├── data/repository/       an interface plus a Local* implementation per concern
   ├── data/model/            entities, enums, catalogs, pure progress math
   ├── data/local/            Room DAOs and migrations, the flights.db data source
   ├── data/network/          connectivity and offline mode, server reachability, the room API
   │
   ├── engine/live/           JNI bridge to the running engine
   ├── engine/headless/       JNA bridge to the offscreen renderer, render cache
   └── util/                  units, search normalisation, country names, airport clocks
```

Dependencies point downward. The pure parts of `data/model` (`ChallengeProgress`,
`AchievementProgress`, `Tour`, `HomeBaseCooldown`, `PredefinedRoute`) and most of `domain/`
(`RouteNetwork`, `AirportSearchIndex`, `EnginePowerModel`) carry no Android, Room or JNI
dependency, which is what lets them run as plain JVM unit tests.

**Repositories are interfaces with a single `Local*` implementation.** The interface is not a
placeholder for a remote backend; it is the seam that lets a JVM test substitute a fake without
Room or the NDK. The same reasoning pulled several pieces of logic out of ViewModels into
standalone functions: `InFlightViewModel` loads the native library the moment it touches
`CesiumLiveJniBridge`, so it cannot be instantiated in a unit test, and anything that needs
testing is moved out of it. That is where `processLandingForChallenges`,
`resolveLandingOutcome`, `loadRouteContext` and `resolveNextLeg` come from, and why the
landing's concurrency guarantee lives in `SessionPausedFlightStore` rather than in a flag on
the ViewModel ([state.md](state.md#concurrent-writers)).

`ChallengesViewModel` is the counter-example: it touches no JNI, so `ChallengesViewModelTest`
runs the real ViewModel on a test main dispatcher (`Dispatchers.setMain` from coroutines-test,
with `android.util.Log` neutralised by `isReturnDefaultValues`) and pins that `celebrate`
persists before it dequeues and that a completion a sync brings in while the screen is open is
presented on that visit. The queue's one step, `nextQueue(queue, seen, rows)`, is still a pure
function in a file of its own, tested without the ViewModel around it, for the same reason the
other seams exist ([shared-challenges.md](shared-challenges.md#presentation)).

## The composition root

There is no dependency-injection framework. `CesiumGameActivity.onCreate()` constructs every
repository once and hands them to screens through the ViewModel factories. The graph is small
enough to read in one screen, and the order in which it is built carries meaning:

1. The `flights.db` data source and airport repository, the `PendingFlightLoader`, the
   preferences repository, the process-wide `OfflineModeController`, and next to it the
   process-wide `ServerReachability`, the second signal, together with the `RoomApi` that
   `RoomApiProvider.roomApi` chose for this build ([network.md](network.md#the-second-signal)).
2. The Room database and the user, flight-log, challenge and achievement repositories. The
   challenge repository takes the room API, the reachability signal and the preferences, which
   is what makes share and join repository operations rather than screen logic.
3. `PilotProgressRepository`, on an `appScope` that outlives every ViewModel, so the shared
   derivation of the pilot's progress stays warm across navigation
   ([achievements.md](achievements.md#the-shared-derivation)), and `SharedChallengeSyncer` on the
   same scope, so a landing's sync request never waits on a ViewModel's scope. `onStart` asks the
   syncer for a `FOREGROUND` sync, which covers cold start and is debounced, and while the
   Activity is started `keepFresh` adds the 5-minute and reconnect syncs
   ([shared-challenges.md](shared-challenges.md#sync-moments)).
4. `ensureDatabaseCopied()`, which refreshes the bundled database if the app was installed or
   updated since the last copy ([flight-data.md](flight-data.md#copying-and-opening-it)).
5. `CesiumEngineManager`, attached to the Activity lifecycle before any Compose content exists.
6. Two background jobs: one keeps the home base's globe render pinned in the image cache
   ([maps.md](maps.md#the-cache)); the other parses the world-map SVG ahead of the first screen
   that draws it.
7. A startup self-heal: if `onboarding_completed` is set but no profile row exists, the flag is
   reset, so the start destination is Onboarding rather than a Hub whose repositories would all
   throw on `requireProfileId()`.

Two Activity-scoped channels, `LandingResultChannel` and `DestinationPhotoChannel`, are built as
plain fields. They carry per-landing results to screens that exist after `InFlightViewModel` has
been destroyed ([navigation.md](navigation.md#what-travels-outside-the-route)).

## Where each concept lives

| Concept | Where |
|---|---|
| App entry, nav graph, repository construction | `CesiumGameActivity.kt` |
| Route patterns and argument encoding | `ui/Screen.kt`, see [navigation.md](navigation.md) |
| A completed flight | `data/model/FlightLog.kt`, the only durable record that a flight happened |
| Session mode tag | `data/model/FlightMode.kt`, see [modes.md](modes.md) |
| Landing pipeline | `InFlightViewModel.completeFlight()`, see [core-loop.md](core-loop.md#the-post-landing-pipeline) |
| Paused flights | `data/model/PausedFlight.kt`, `data/repository/PausedFlightStore.kt`, see [paused-flights.md](paused-flights.md) |
| Challenge instances and progress | `LocalChallengeRepository`, `ChallengeProgress`, see [challenges.md](challenges.md) |
| Achievement evaluation | `AchievementProgress`, see [achievements.md](achievements.md) |
| Everything derived from history | `PilotProgressRepository` |
| Persisted-value ownership | [state.md](state.md) |
| Route network and search | `domain/RouteNetwork.kt`, `domain/AirportSearchIndex.kt`, see [route-network.md](route-network.md) |
| Airport local time | `util/FlightClock.kt`, `util/AirportTimeZones.kt`, see [time-zones.md](time-zones.md) |
| Native engine | `engine/live/`, see [engine.md](engine.md); `engine/headless/`, see [maps.md](maps.md) |
| Offline mode | `data/network/`, `domain/OfflineMapPolicy.kt`, see [network.md](network.md) |
| Shared challenges | `data/network/room/`, `data/repository/SharedChallengeSyncer.kt`, `domain/SharedChallengeMerge.kt`, see [shared-challenges.md](shared-challenges.md) |
| Engine sound | `domain/EnginePowerModel.kt`, `audio/`, see [engine-sound.md](engine-sound.md) |
| Design scale | `ui/theme/DesignScale.kt`, see [ui.md](ui.md) |

## The two databases

They are unrelated and have opposite lifecycles.

**`flights.db`** is reference data: 4,170 airports, 57,570 routes and 5,670 runways, bundled in
`assets/`, copied into the app's databases directory and opened read-only. Nothing in the app
writes it, which is what makes the several process-lifetime caches on top of it safe. Its
provenance, contents and quirks are in [flight-data.md](flight-data.md).

**`user_data.db`** is the pilot's own data: a Room database at schema version 11 with four
entities, `user_profile`, `flight_log`, `challenges` and `achievement_unlocks`. Migrations from
version 6 onward are explicit `Migration` objects in `AppDatabase.kt`, each validated by the
instrumented `MigrationTest` against the schema JSON exported to `app/schemas/`. Versions 1 to 5
predate the exported schemas and are listed in `fallbackToDestructiveMigrationFrom`, so only
those are ever rebuilt from scratch; any other schema change without a migration fails at launch
rather than silently deleting the logbook ([state.md](state.md#migrations)).

A third store, `SharedPreferences` (`blocktime_prefs`), holds short-lived, fast-changing values
such as the pilot's current airport and the Story and Free paused-flight slots. Which value lives
where, and why, is the subject of [state.md](state.md).

## Assets

| Asset | Used by |
|---|---|
| `flights.db` | `AirportRouteSqliteDataSource`, see [flight-data.md](flight-data.md) |
| `world-map.svg` | `WorldMapParser` and `InteractiveWorldMap`, see [maps.md](maps.md#the-2d-world-map) |
| `Boeing787Cockpit.glb` | CesiumRS, cockpit camera mode |

The offline vector world map that the engine rasterises is compiled into `libcesium_rs.so`
itself, not shipped as an asset.

## Debug-only code

Debug tooling lives in the `app/src/debug/` source set and is never compiled into a release
build. Where main code has to call into it, `app/src/release/` provides a same-signature
stand-in that does nothing. The In-Flight `FlightDebugMenu` is the main example: a collapsed
"DBG" chip that opens seek, flight speed (0.1× to 500×), pause/resume and skip-to-landing. The
ViewModel hooks it drives (`debugSeek`, `setDebugTimeScale`, `skipFlight`) are in main code, but
nothing in a release build calls them, so release flights always run at real time.

`RoomApiProvider` is the other pair. The release one maps a `ROOM_SERVER_URL` to `HttpRoomApi`,
the client of the server in `backend/`, and a blank one to `NoRoomApi`, which hides every sharing
surface; the debug one does the same with a URL, and without one uses the in-memory
`FakeRoomApi`, exposed as `RoomApiProvider.fake`, so the whole sharing flow can be walked on a
phone without a server ([shared-challenges.md](shared-challenges.md#debugging-without-a-server)).

The debug manifest also registers three broadcast receivers: `PerfScenarioReceiver`, which tags a
Perfetto capture for `tools/run_perf_scenario.sh`, `DebugSeedReceiver`, which inserts a set of
demonstration flights, and `DebugRoomReceiver`, which plays a bot pilot in every room the fake
holds. `CesiumGameActivity` registers a debug-only receiver for navigation and screen capture,
used by `tools/capture_all_screens.sh`.

## Build

```
./gradlew :app:assembleDebug        # preBuild runs cargoNdkBuild first
./gradlew test                      # JVM unit tests
./gradlew connectedAndroidTest      # instrumented: MigrationTest, PredefinedRouteCatalogTest
```

`preBuild` depends on `cargoNdkBuild`, which cross-compiles CesiumRS with `cargo ndk` for both
ABIs and copies each `libcesium_rs.so` into `src/main/jniLibs/<abi>/`. The environment it needs
and the profiling variant are described in [engine.md](engine.md#building-the-native-library).

Machine-local configuration lives in the untracked `local.properties`: SDK paths, the optional
`PEXELS_API_KEY` and `ROOM_SERVER_URL` (both compiled into `BuildConfig`; a blank server URL
means no sharing UI in the build), `CARTO_API_KEY` and `ESRI_API_KEY` (passed to the Rust build as
environment variables), and the four `RELEASE_*` signing properties. Without all
four signing properties the release build is produced unsigned.

## Testing

JVM unit tests cover the pure layers and the seams extracted for them: progress math, tour
segmentation, cooldowns, achievement evaluation and stacking, route-network and search logic,
airport clocks, the world-map projection, offline-mode resolution, the render cache and route
selection, the landing outcome diff, challenge crediting, the two audio classes, and the shared
challenges: the server-state derivation, the room merge, team progress, the repository's share and
join paths and the syncer against `FakeRoomApi`, whose own test suite is the backend's contract
test ([shared-challenges.md](shared-challenges.md#situations)).

A few of them test a property rather than a function, and are worth knowing about:

- `ChallengeRowConcurrentWriteTest` reproduces the concurrent writes to a challenge row that a
  landing and an in-flight save can produce. Its fake DAO models Room's real write granularity
  (a whole-row `@Update` rewrites every column, a scoped `UPDATE` does not), so it cannot pass
  against a DAO that could not exist ([state.md](state.md#concurrent-writers)).
- `PilotProgressRepositoryTest` pins that a failed derivation keeps the previous snapshot and that
  a username edit does not trigger a recompute.
- `EngineSoundSynthTest` renders the synthesised audio and measures it, so "does this still sound
  like a jet" is a spectral check rather than a listening session
  ([engine-sound.md](engine-sound.md#testing)).
- `MigrationTest` (instrumented) runs every migration against its exported schema. The build adds
  `app/schemas` to the androidTest assets, because `MigrationTestHelper` loads the old schema from
  there at runtime.
- `PredefinedRouteCatalogTest` (instrumented) checks every authored itinerary against the real
  `flights.db`.
