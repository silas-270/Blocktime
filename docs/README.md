# Blocktime documentation

A technical analysis of how Blocktime works: each file takes one subsystem and explains what
the code does, why it is built that way, and where its numbers come from. The rendering
engine itself, CesiumRS, has its own documentation in its repository.

| File | What it explains |
|---|---|
| [architecture.md](architecture.md) | What the app is, its layers and composition root, the two databases, the build and the testing seams |
| [core-loop.md](core-loop.md) | One session from Hub to landing: the timer, the holds, and the ordered post-landing pipeline |
| [navigation.md](navigation.md) | The navigation graph, route arguments, the channels that cross screens, and ViewModel lifetime |
| [modes.md](modes.md) | Story, Free and Challenge modes, the isolation matrix, the home base and its two cooldowns |
| [challenges.md](challenges.md) | The four challenge types, predefined itineraries, crediting rules, completion presentation |
| [shared-challenges.md](shared-challenges.md) | Opt-in rooms: the offline-first data model, the merge, every situation as a test list, the presentations and the server's contract |
| [achievements.md](achievements.md) | Achievements as a query over history, ladders and badge tiers, the shared derivation, tours |
| [state.md](state.md) | Every persisted value and its owner, caches, write ordering, and concurrent writers |
| [paused-flights.md](paused-flights.md) | How a flight survives backgrounding, leaving, process death and landing |
| [flight-data.md](flight-data.md) | The bundled `flights.db`: provenance, contents, data-quality filters and how it is read |
| [route-network.md](route-network.md) | The main route network, airport search, booking by duration, and the next-leg resolver |
| [time-zones.md](time-zones.md) | Local airport clocks without a time zone column, and the generator that makes them exact |
| [engine.md](engine.md) | The CesiumRS live bridge call by call, the host activity, and the native build |
| [maps.md](maps.md) | Headless globe renders and their cache, and the 2D world map and its projection |
| [network.md](network.md) | Everything that touches the network, offline mode, and destination photos |
| [engine-sound.md](engine-sound.md) | The thrust model that reads telemetry and the synthesiser that turns it into engine noise |
| [ui.md](ui.md) | The 360 dp design scale, the in-window modal convention, formatting and recomposition |
