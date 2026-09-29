# Achievements, the passport, and tours

Everything in this file is **derived from the flight log**, not stored. An achievement is a query
over history; a challenge is an instance with its own row. That distinction is why there are two
systems, and it decides which one a new kind of goal belongs in.

## The four categories

Three categories are shaped like progress bars and are modelled by `AchievementCategory`:

| Category | Reads | Catalog |
|---|---|---|
| Geographic sets | `VisitedGeography`: countries and continents visited | `GeographicAchievementCatalog` |
| Distance milestones | cumulative Story distance | `DistanceAchievementCatalog` |
| Behavioral | properties of individual flights | inline in `AchievementProgress` |

The fourth, **challenges completed**, is not in that enum and has no `AchievementStatus` at all.
It is the flat log of completed challenges ([challenges.md](challenges.md#abandon-not-reset)): a
list without a denominator, since custom challenges and repeat completions mean there is no fixed
total to be a fraction of. A shared streak that broke never reaches it: its `FAILED` row is
deleted after its presentation, so the log holds successes only.

### Geographic sets

| Achievement | Target |
|---|---|
| Border Crosser · Diplomat · World Citizen | 10 · 50 · 100 countries |
| World Traveler | every country the network reaches |
| Globetrotter | at least one country on every continent the network reaches |
| Master of *continent* | every reachable country on that continent (six of them) |

Every geographic goal must be able to **enumerate its members**, not just count them.
`memberProgress` is abstract so that a goal is always a set with a checklist behind it; a goal that
could only report "12 / 54" would be a dead end for a pilot trying to find out what is missing. The
progress bar is a summary of the checklist rather than the only thing the goal knows. Nothing about
membership is stored: "missing" is always the definition minus what `VisitedGeography` reports,
computed on every read.

A visit is the origin or destination of a Story flight, or the home base
([modes.md](modes.md#story-mode)).

**Only reachable geography counts.** The denominators come from the continent → countries map
built from airports in the main route network only, with every country under exactly **one**
continent: the continent most of its network airports are on, with Russia overridden to Europe
([route-network.md](route-network.md#countries-and-continents)). The raw airport data puts six
countries (the US, Russia, Spain, Turkey, Greece and Egypt) on two continents each; listing them
twice would put Spain in "Master of Africa" and make World Traveler's target larger than the number
of countries that exist. With the one-continent rule the network reaches 234 countries on six
continents:

| Continent | Countries |
|---|---|
| Africa | 58 |
| Asia | 52 |
| Europe | 46 |
| North America | 41 |
| Oceania | 23 |
| South America | 14 |

There is no "Master of Antarctica", because the one Antarctic airport in the data has no routes.
A continent counts as *reached* for Globetrotter if a visited country counts under it **or** a
visited airport physically sits on it, so landing in Honolulu reaches Oceania even though the US
counts under North America.

### Distance milestones

A few large, thematic targets rather than linear counters:

| Achievement | Cumulative Story distance |
|---|---|
| Cleared for Long-Haul | 5,000 mi |
| Around the Earth | 24,901 mi, the equatorial circumference |
| To the Moon and Back | 477,710 mi |
| Million Miler | 1,000,000 mi |
| The Stuker Record | 23,000,000 mi, the frequent-flyer world record |

Targets are stored in kilometres, like every distance in the flight log, and reported in miles,
like every distance in the UI.

### Behavioral

| Achievement | Condition |
|---|---|
| Marathon Flight | one Story flight of at least 480 minutes |
| Red-Eye Pilot | a Story landing between 00:00 and 04:59 device-local time |
| High Altitude Club | a Story landing at an airport at or above 10,000 ft |
| Equator Crossing | a Story flight between the northern and the southern hemisphere |

Red-Eye uses the device's time zone, the same basis as every other `completedAt`-derived display
and as the pilot's own sense of "late at night", not UTC and not the destination's clock.

High Altitude and Equator Crossing need airport data rather than the flight log alone, so
`AirportRepository.getVisitedGeography` derives them alongside the visited countries, from
`flights.db`'s `elevation_ft` and latitude columns. The equator test needs only the endpoints: a
great circle from a point north of the equator to one south of it must cross it, so a flight
crosses exactly when its two airports' latitudes have opposite signs. High Altitude counts
destinations only, matching "land at"; a departure from a high airport, or a high home base, does
not earn it. Of the 25 airports in the database at or above 10,000 ft, those outside the main
route network (see [route-network.md](route-network.md)) cannot be reached in Story mode, which
the rule handles without a list to keep in sync.

## Ladders and families

Some achievements supersede each other. The five distance milestones read the same number, so
passing the furthest means having passed all the nearer ones; the three country counts and World
Traveler form the same kind of ladder over visited countries. `AchievementStatus.familyId` and
`familyRank` express this, and the Passport folds each family into a single stacked tile showing
its furthest earned tier (`buildAchievementStacks`). Parallel siblings like "Master of Europe" and
"Master of Africa" share no family, since neither is a better version of the other.

Badges are coloured by difficulty tier, derived in the UI layer rather than stored:
bronze, silver, gold and diamond form a ladder, and ruby marks the behavioral achievements, which
have no difficulty rank at all ("landed at 3 am" is not a harder or easier version of "flew eight
hours"). Distance tiers come from the target value; geographic tiers are assigned per goal. The
Passport shows the hardest tiers first and the newest first within a tier.

## Reveal style

`AchievementStatus` is always fully populated, locked or not. There is no hidden or mystery
variant: a locked achievement shows its name, description and progress. The app is a study tool,
and a mystery box that rewards poking around is friction pointed the wrong way.

`current` and `target` share the unit in `unitLabel`. Yes/no achievements use `target == 1.0`
with an empty unit, so a renderer special-cases `target <= 1.0` instead of printing "0 / 1".
`members`, `familyId` and `familyRank` are nullable fields on this one shape rather than
subclasses, for the same reason `Challenge` keeps its four types in one row: every consumer wants
one list to sort, filter and render.

## Scope & isolation

Achievements are Story-only. Geographic goals read `VisitedGeography`, which is Story-filtered where
it is built; the distance and behavioral evaluators take the **full** history and filter to Story
themselves, so a caller passing the all-modes list (as the Passport does) still gets the right
answer. A Free Mode flight or a challenge leg can never move an achievement.

## Evaluation is pure; unlock time is not

`AchievementProgress` has no Room, repository or JNI dependency, which is what makes it
unit-testable. Evaluation always leaves `unlockedAt` null.

`LocalAchievementsRepository` fills it in afterwards from the `achievement_unlocks` table: every
evaluation inserts a row for each unlocked achievement with `INSERT OR IGNORE` against the
`(user_id, achievement_id)` primary key, then merges the stored timestamps back in. First write
wins, with no read-then-write race, so re-evaluating on every change never moves an earlier and
truer timestamp. The table holds **only** the timestamp; whether an achievement is unlocked is
always re-derived, and the timestamp exists solely to order badges newest-first. A null
`unlockedAt` means "time unknown", never "locked".

Because the stamp is written on the read path, the landing pipeline has no achievement step
([core-loop.md](core-loop.md#the-post-landing-pipeline)). It also makes the scheme self-healing: an
achievement earned before the table existed is stamped the first time the board is evaluated. Its
timestamp is then "first seen" rather than the true historical moment, which no stored data could
recover, and nothing in the UI claims otherwise.

## The shared derivation

Everything in this file, together with the stats and tours below, is computed **once** by
`PilotProgressRepository` and shared by the Passport, the Challenges screen, the Hub and Flight
Search. Each of those screens' ViewModels is destroyed when the screen is popped, so a per-screen
derivation would re-read the whole flight log, re-derive the geography and re-evaluate every
achievement on every visit, for an answer identical to the one just thrown away.

`PilotProgress` carries `history`, `homeAirportIata`, `geography`, `achievements`, `stats`,
`highlights` and `tours`.

**This is a cache, not a second source of truth.** Nothing in it is persisted. The chain is rooted
in two Room flows, the profile row and the flight log, and Room re-emits both on any write to their
tables, so the cached value's lifetime is tied to the same mechanism that keeps the underlying data
correct. It cannot go stale unless Room is wrong, and there is no second writer that could disagree
with it. Discarding it at any moment and rebuilding it gives identical values, which is the test of
whether something is a cache at all.

The profile flow is narrowed to "does a profile exist, and what is its home airport" before it
keys anything, because a username edit changes the row but no derived number.

A failed derivation **skips the emission** (`mapNotNull`) instead of publishing a blank or partial
result, so the last good snapshot stays on screen and the next write retries automatically. The
flow is started eagerly on an Activity-level scope, so it is warm before the pilot opens a screen
that needs it. Before onboarding there is no profile, and the flow emits null rather than calling
repositories that would throw.

## Passport surfaces

The Account screen, the passport, is a read-only view of everything derived:

| Surface | Source |
|---|---|
| Visited-country map | `VisitedGeography` drawn over `world-map.svg` ([maps.md](maps.md#the-2d-world-map)) |
| Stats | flights and airports visited, plus the running tour once it is worth reporting |
| Highlights | longest flight, and total distance as laps of the equator (40,075 km) |
| Badges | earned achievements only, folded into ladder stacks |
| Logbook | every flight, sortable, grouped under collapsible tour headers when sorted by date |

Unearned achievements are on the Challenges screen's Achievements tab instead, and completed
challenges in the Challenges tab's log, so the two screens are strictly complementary.

Airports visited counts the distinct origins and destinations of Story flights, plus the home
base exactly once, which is the same set the map is drawn from; the DAO excludes home from its
count and the repository adds it back only when a home base exists, so home contributes one or
zero, never two. Total flights and flight time (shown on the Hub) and the highlights count every
mode. The full rules are in the [isolation matrix](modes.md#the-isolation-matrix).

## Tours

A **tour** is one campaign of flying: a run of flights with no gap longer than `TOUR_GAP_DAYS`
(10) between consecutive ones, measured in calendar days in the device's zone. `TourSegmentation`
is a pure fold over the flight log, run inside the shared derivation, and it counts every mode.

Tours exist because a daily streak is the wrong shape for how the app is used. Sessions come in
bursts of a few weeks before an exam and then stop for months, so a streak counter spends most of
the year at zero and punishes an off-season that is not a failure. A tour **ends** instead of
breaking: it closes, keeps its numbers, and the next one starts fresh with nothing lost.

The ten-day gap is generous on purpose, because the two possible errors are not symmetric.
Splitting one real exam period into two tours makes both halves look shorter and less impressive
than the work was, which is the thing tours exist to avoid. Merging two separate periods is the
milder mistake.

`activeDays` over `spanDays` is the pairing that matters for display: "12" alone reads as a small
number, "12 / 14 days" reads as near-perfect attendance, which is what it is. The passport shows
the running tour only once it has at least three active days, since "1 / 1" on the first day is
noise.

**Whether a tour is still running is not a field.** The answer changes with the clock rather than
the data, and the shared snapshot recomputes only when Room writes, so a stored `isOpen` would keep
claiming a tour was running for exactly as long as the pilot did not fly. It is a function,
`Tour.isOpenAt(now, zone)`, instead ([state.md](state.md#facts-and-the-clock)).

## Relationship to challenges

The two systems look similar and are not.

| | Achievement | Challenge |
|---|---|---|
| Nature | a query over history | an instance with its own row |
| Persisted | only an unlock timestamp | full progress state |
| Starting point | all existing Story progress | zero |
| Can be abandoned | no | yes, which deletes the row |
| Limit | none | three at a time |
| Counts | Story flights only | Story flights and challenge legs |

**A geographic achievement and a Set-completion challenge can name the same set and still behave
differently, correctly.** "Globetrotter" credits continents the pilot reached long ago, because an
achievement recognises persistent progress. "Visit All Continents" starts empty, because its goal
is "reach these while this is running"; pre-filling it from the achievement's data would make
starting one meaningless.

The only crossing point runs the other way: completed challenges are recognised in their own log,
which grants no achievement progress.
