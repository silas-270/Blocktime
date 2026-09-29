# Challenges

Long-running goals that run alongside normal play. A challenge is an **instance** with its own
stored state, not a query over history. That is the core difference from an achievement
([achievements.md](achievements.md#relationship-to-challenges)), and it is what makes "start
fresh", abandoning, and completing the same challenge twice meaningful.

## The four types

| Type | Metric | Own position | Sources |
|---|---|---|---|
| `ROUTE` | Progress from an origin toward a destination | ✅ its own pointer | curated or custom |
| `SET_COMPLETION` | Members reached / members in the set | none | curated only |
| `DISTANCE` | Distance flown while active / target (stored in km, shown and entered in miles) | none | curated or custom |
| `STREAK` | Consecutive local calendar days with a flight / target | none | curated or custom |

All four share one table, `challenges`, with nullable per-type columns rather than one table per
type. Every consumer (the slot row, the Hub card, the outcome screen, the completed log) wants one
list to sort, filter and render, and the per-type columns are few.

**Set completion is curated only**, because a set has to be authored to mean anything. A
player-defined "visit these five airports" would just be a checklist; the definitions in
`CuratedChallengeSets` (`ChallengeSetDefinition.kt`) are the authoring seam. A member is tested
against a landed destination by continent code, ISO country code or IATA code
(`SetMemberKind`).

Every member of a set has to be reachable through the main route network, or the set can never
complete. That is why "Visit All Continents" has six members: Antarctica's only airport in the
data has no routes ([route-network.md](route-network.md)). A row stores `setTotalMembers` when it
starts, but active rows are read, and completion is judged, against the *current* definition
(`withSetDefinitionResolved`, and "every current member credited" in `creditSetCompletion`), so
shrinking a definition can never strand a challenge in progress one unreachable member short.

**Streaks are meant to be short and unforgiving.** The curated streaks are three and five days.
They cover the final push before an exam, where breaking on a missed day is the point. They can
afford that fragility because [tours](achievements.md#tours) cover the opposite horizon: tours are
weeks long and never break. A long unforgiving streak would combine the worst of both. The custom
streak form accepts any positive number of days, so the short horizon is a property of the
curated catalog rather than a limit the code enforces.

## Where it comes from

Curated challenges are templates in `CuratedChallengeCatalog`: name, description, icon, type and
definition, but no instance state. `startCuratedChallenge` turns one into a row, assigning an id,
a position pointer where relevant, and zeroed progress. The catalog holds ten Route templates
(three of them predefined itineraries), eight sets, three distances (7,918, 24,901 and 50,000
miles: the Earth's diameter and circumference, and a frequent-flyer status tier) and two streaks.

Custom challenges come from `startCustomRouteChallenge`, `startCustomDistanceChallenge` and
`startCustomStreakChallenge`. A custom Route is named from the two cities ("Stuttgart →
Beijing") rather than their codes, falling back to the airport name or the code when the data has
no city.

### The predefined-route submode

A Route challenge is normally free-form: an origin, a destination, and whatever path the pilot
finds. The predefined submode instead names an authored itinerary in `PredefinedRouteCatalog`
through `predefined_route_id`, and tracks how many of its legs are done in `leg_index`.

**It exists because a free-form route cannot express a circuit.** With the same airport at both
ends, the straight-line formula below has a zero denominator and its guard reports 100% before a
single leg is flown; without the guard, a pilot would satisfy "around the world" by never leaving.
Scoring along a known itinerary, with completion as a leg count rather than a coordinate
comparison, is what makes `waypoints.first() == waypoints.last()` a legitimate route. Two of the
four itineraries are circuits:

| Itinerary | Waypoints | Legs (km) |
|---|---|---|
| Around the World in 80 Days | LHR → BOM → HKG → SFO → LHR | 7,220 · 4,280 · 11,144 · 8,639 |
| Great Race of Mercy | ANC → FAI → ANC → OME | 420 · 420 · 866 |
| Silk Road of Marco Polo | VCE → IST → SKD → XIY → PEK | 1,407 · 3,221 · 3,726 · 934 |
| Pacific Rim | SIN → BKK → HKG → ICN → NRT → SIN | 1,409 · 1,689 · 2,063 · 1,260 · 5,349 |

The first three are offered as curated challenges; Pacific Rim is defined in the catalog but no
template uses it.

**Progress is weighted by distance, not by legs**, because legs are not interchangeable. On the
circumnavigation, Hong Kong → San Francisco is 11,144 km and Bombay → Hong Kong is 4,280 km;
counting legs would pay both the same 25% and make the hardest leg feel like the cheapest. The leg
distances are authored next to the waypoints so that `PredefinedRoute.progressAt` stays a pure
function: the slot ring, the Hub card and the info modal all call it while composing, and none of
them can reach the database from there. `PredefinedRouteCatalogTest` checks that each authored
distance matches `flights.db`, that every leg is a real route, and that every waypoint is a real
airport. An empty distance list is legal and means "score by leg count".

A predefined row still fills the free-form columns: `originIata` and `destIata` are the first and
last waypoints, and `positionIata` is where the next leg departs. Every reader that only needs
"where is this challenge" keeps working without knowing the submode exists; only scoring and
advancing branch on it, through the single `Challenge.predefinedRoute()` accessor.

The submode also shortens the booking path. The next hop is authored, so `resolveNextLeg` books
it and the pilot lands on the boarding pass, skipping a destination picker that would have
exactly one correct answer ([navigation.md](navigation.md#shared-entry-points)). Legs keep their
real scheduled durations: the ten-hour Pacific crossing is meant to be paused and resumed across
several sittings, not shrunk.

Predefined routes are curated only; `startCustomRouteChallenge` always creates a free-form one.

## Which flights count

Applied by `processLandingForChallenges` in step 4 of the
[post-landing pipeline](core-loop.md#the-post-landing-pipeline):

| | Advances a Route challenge | Credits Distance, Set and Streak |
|---|---|---|
| `FREE` flight | ❌ | ❌ (returns before anything) |
| `STORY` flight | ❌ | ✅ every active one |
| `CHALLENGE` leg of challenge *N* | ✅ challenge *N* only | ✅ every active one |

So a Route challenge moves only when a leg was flown under it explicitly. The other three types
have no position, so they credit passively from any eligible flight, including a leg flown under
a different Route challenge.

A predefined itinerary advances only when the landing is the itinerary's own next waypoint; a
landing anywhere else is ignored rather than scored. The booking flow already guarantees this, but
keeping the rule in the repository means the row itself decides what counts. On a circuit this is
what makes landing at LHR do nothing after the first leg and complete the challenge after the last.

Crediting uses the landed flight's own `FlightLog.completedAt`, not a fresh "now". The two differ
by milliseconds in practice, and by a whole calendar day either side of midnight, which is exactly
when a streak is most likely to depend on the answer.

Streak crediting turns that timestamp into a local date in the device's zone. A second flight on
a day already counted changes nothing; a flight on the day after the last one extends the run;
anything else restarts it at one.

Two of these rules bend for a shared row ([shared-challenges.md](shared-challenges.md#landing)).
**A shared pool can be filled by the pilot's own landing**: `creditDistance` and
`creditSetCompletion` judge the team's sum or union, the local row plus the cached snapshots of
the others, so a landing that fills the pot completes the row at once rather than at the next
sync. **A shared streak never completes from a landing**: `creditStreak` writes the day and leaves
the status alone, because completion, and failure, depend on the crew's minimum, which only the
merge knows. Every credit to a shared row also bumps its `sync_generation`.

## Route progress formula

A free-form Route challenge measures progress as the fraction of the straight-line distance
already closed:

```
progress = 1 − straight_line(current, destination) / straight_line(origin, destination)
```

clamped to 0..1, with haversine great-circle distances on a 6,371 km sphere
(`ChallengeProgress.routeProgress`). Reaching the destination sets it to exactly 1 and completes
the challenge.

This is a proxy, and knowingly so: real routing is not a straight line, so progress can go *down*
when a genuine onward connection points away from the destination, for example via a hub behind
the pilot. Measuring distance along the network instead would mean shortest-path searches over
tens of thousands of edges for a number whose only job is to fill a bar. Several curated routes
(JFK → CDG, MEL → LHR) have a direct flight and complete in one leg; the longer ones, like Stuttgart
to Lubango, take three.

### Per-type metrics

`progressFraction()` computes the displayed fraction for any row:

| Type | Fraction |
|---|---|
| `ROUTE`, predefined | `PredefinedRoute.progressAt(legIndex)`, derived live from the catalog |
| `ROUTE`, free-form | the stored `route_progress_fraction` |
| `SET_COMPLETION` | credited members / `setTotalMembers` |
| `DISTANCE` | `cumulativeDistanceKm / targetDistanceKm` |
| `STREAK` | `streakDays / targetDays` |

The predefined fraction is derived from the catalog rather than trusted from the stored column,
so editing an itinerary can never leave a live challenge scored against legs that no longer
exist; the column is still written on every advance so it never becomes a stale lie for a reader
that trusts it. `progressFraction()` is an extension function rather than a member, so Room's
entity scanning never mistakes it for a column; the same goes for every computed property of
`Challenge`.

## Per-leg progress feedback

A landing does not just update a number. `resolveLandingOutcome` diffs the challenges from before
and after the credit and produces, per challenge, `Advanced(from, to)` or `Completed`. The
Challenge Outcome screen animates each bar from its old value, so the pilot sees the movement
their flight caused and not only where it ended up. When nothing changed, the result is
`LandingResult.None` and the screen is skipped.

The diff is a pure function, tested without Room or the engine. It requires the "after" list to be
read by id, because a challenge completed by this landing is no longer active and would be missing
from a re-listing of active challenges.

## Completion presentation

Reaching `COMPLETED` and being *shown* that completion are two different moments, tracked by one
column, `challenges.celebrated`, false by default. A completed but uncelebrated challenge keeps
occupying its slot on the Challenges screen, keeps counting against the cap, and is left out of
the completed log. `ChallengeDao.getSlotDisplayFlow` and `getCelebratedCompletedOrderedByCompletedAt`
are the two queries this splits. Nothing else about completion (status, `completed_at`, crediting)
waits for it; only the presentation does.

A landing that completes any challenge sends CONTINUE on the outcome screen to the Challenges
screen instead of the Hub. There, each uncelebrated completion plays in slot order: the card
duplicates out of its slot and grows to the centre while the slot row compacts to reveal the
freed slot, confetti bursts once it settles, and on a tap (or system back) it smashes down into a
new entry at the top of the completed log. At that moment `markCelebrated` flips the flag.

`ChallengesViewModel.celebrationQueue` is reactive: it holds **ids**, fed from the slot flow
through the pure `nextQueue(queue, seen, rows)`, which appends every terminal, unpresented row it
has not seen before and remembers it in a seen set, so a challenge already mid-animation is never
re-queued by a later emission and a completion that a sync brings in while the screen is open is
presented on this visit. The presented row is resolved by id from the latest emission, so a sync
that corrects a row's outcome after it was queued is presented as corrected. The database stays
the source: the queue is rebuilt from `status IN (COMPLETED, FAILED) AND celebrated = 0` however
the app was last left, whichever screen the pilot was on when the process died. Migration 9 → 10
marked every completion that already existed as celebrated, so the column's introduction did not
replay old completions.

A `FAILED` row plays a different overlay, the shatter (`ChallengeFailureOverlay`): the card lifts
to centre stage the same way, says "STREAK BROKEN" and who missed the day, and on a tap cracks and
falls apart into shards, after which `dismissFailed` deletes the row instead of marking it
celebrated ([shared-challenges.md](shared-challenges.md#presentation)).

## Isolation

A Set-completion challenge starts **fresh**. It is never seeded from Story Mode's visited set,
even though the app already knows the pilot has been to half its members. The goal is "visit these
while this challenge is running", and pre-filling it would make starting one meaningless. This is
the sharpest difference from a geographic achievement, which reads persistent history by design
([achievements.md](achievements.md#relationship-to-challenges)).

## Lifecycle

```
(start) ──▶ ACTIVE ──▶ COMPLETED      final qualifying landing, or the crew's (shared)
              │
              ├──────▶ FAILED         a shared streak broke (shared only); the row is
              │                       deleted once its shatter has played
              └──────▶ (row deleted)   abandon
```

### Shared

A row with a `room_code` is shared: the same goal runs on every crew member's phone, and it ends
for everyone at the same moment. Sharing changes what "complete" means for the three pooled types
(the team's union, sum or minimum instead of the pilot's own), adds `FAILED` for a group streak
that broke, and adds a sync step after the landing pipeline, but it changes nothing about the
lifecycle above: a shared row is credited locally exactly like a solo one and keeps working when
the server is gone. The data model, the merge, every situation and the server's contract are in
[shared-challenges.md](shared-challenges.md).

### Abandon, not reset

Abandoning deletes the row rather than storing an `ABANDONED` status. There is no in-place reset:
trying again means starting a new instance, with a new id and genuinely zeroed progress, and the
slot frees immediately.

Completed challenges are never deleted. They stay as a flat, newest-first log, one row per
completion, so completing the same curated challenge twice shows twice, like two flights on the
same route in the logbook. That log is why "challenges completed" has no achievement
representation: it is a list, not a progress bar. A `FAILED` row, which only a shared streak can
produce, is the one terminal row that is deleted: once its shatter has played it is gone, so the
log stays a log of successes ([shared-challenges.md](shared-challenges.md#presentation)).

### Active-challenge cap

At most three challenges occupy slots at once (`MAX_ACTIVE_CHALLENGES`), across all four types
and both sources. "Occupying" means active, or completed but not yet celebrated. Starting returns
`StartChallengeResult.CapReached` rather than throwing or evicting anything. The cap check is a
count followed by an insert, which two quick taps could both pass; the repository's write mutex
makes the pair atomic ([state.md](state.md#concurrent-writers)).

## Persistence & route scoping

Challenge instances live in the Room `challenges` table and are written only by
`LocalChallengeRepository`. This is genuinely mutated state: an instance starts at zero and is not
derivable from history, which is why challenges are persisted while achievements are not.

**Scoping a flight to a challenge** uses the same route-argument mechanism as everything else.
`challengeId` travels from Flight Search through Check-In into In-Flight, encoded as an `Int`
with `-1` for "none" ([navigation.md](navigation.md#argument-threading)). In-Flight uses it to
choose the paused-flight slot and to tell step 4 which Route challenge to advance.

`focused_route_challenge_id` in `SharedPreferences` is a different thing that is easy to confuse
with it: a display-only pointer to the Route challenge the Hub is showing. Starting a Route
challenge focuses it and returns to the Hub; continuing one from the Challenges screen focuses it
first. Pausing focus clears the pointer and changes nothing about the challenge itself. When the
focused challenge completes, the pointer is cleared on the way through the arrival screen.

**Each Route challenge has its own paused-flight slot**, the `paused_flight` column, independent
of the Story and Free slots. A pilot can hold a paused Story flight, a paused Free flight and a
paused leg of every active Route challenge at once ([paused-flights.md](paused-flights.md)).

### Reading through the clock

`streak_days` and `last_flown_day` record what happened, not what is true now, and nothing runs at
midnight to notice a streak dying. A pilot who flew on Monday and Tuesday and skipped Wednesday
still has `streak_days = 2` stored on Thursday, and that row is correct about the past.

The resolution happens once, on read, at the repository boundary: every active row passes through
`withStreakEvaluatedAt(today)`, which zeroes a run whose last day is older than yesterday. A last
day in the *future* (a clock moved back, or a westward time-zone change) is treated as alive, since
zeroing it would destroy real progress over something the pilot did not cause. Everything
downstream (`progressFraction()`, the Hub card, the slot row, `resolveLandingOutcome`) receives an
already-correct row and needs no clock of its own. The stored row catches up at the next credited
flight. Completed streaks are left alone: their day count is a final score. The general principle
is in [state.md](state.md#facts-and-the-clock).

## Entry & management surface

The **Challenges screen** is a full destination with two tabs. At the top sits the Free Mode row,
with Resume when a Free flight is paused. The Challenges tab shows the three slots, each opening
an info modal with continue or resume, pause focus, and abandon, followed by the completed log.
An empty slot opens the picker of curated templates and custom forms.

The Hub keeps only the focused challenge's card ([core-loop.md](core-loop.md#1--hub)).

**Achievements share this screen as its second tab**, showing only the still-unearned ones,
grouped by category and ordered closest-to-done first. From the pilot's side an achievement and a
challenge are the same kind of thing, a goal with progress, even though their implementations are
opposite. Earned achievements live on the Passport instead, so the two surfaces never show the
same item.
