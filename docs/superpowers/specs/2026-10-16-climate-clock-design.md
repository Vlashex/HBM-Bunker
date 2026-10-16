# DEAD EARTH — climate clock and forecast

## Stage and authorization

Continuous autonomous roadmap work and local alpha releases are authorized by the
user. Apply brainstorming/writing-plans inline; no repeated approval menu. After
radio alpha.3, build a deterministic climate clock before weather effects/adapters.

## Chosen behavior

CALM/WARMING → HEAT/NONE → CALM/COOLING → ASH_WINTER/NONE → repeat.
Default slot duration 48000 active campaign ticks (two game days); four slots are
8 days / 160 minutes at 20 TPS. CALM is one phase with direction, not a fourth phase.
The first installation starts a fresh two-day CALM preparation slot at the current
campaign tick, including existing test worlds; it does not retroactively infer climate.

The sole time source is Campaign.activeTicks. Sleep, setDayTime and wall clock do
not drive climate. No players means no clock progress. Cycle computation is pure,
constant time and can cross many missed slots without replaying historical messages.
Forecast reports current/next phase, trend, entered/next active tick and remaining
ticks. Four future windows are available through a read-only operator command.
Arithmetic exhaustion must fail explicitly rather than wrap into a past phase.

Phase duration is stored per world and validated (200..1920000, multiple of 20).
Runtime creates only the default 48000 profile; a short profile is for unit testing,
not a new exposed operator/MCP power. No per-run environment silently changes a save.

## Notices and persistence

Own SavedData dead_earth_climate, schema1, exact campaign UUID. Persist origin,
duration, last announced absolute slot and last warned slot. Once online, announce
current phase at most once and give one forecast notice when remaining time is at
most min(6000, phaseTicks/4). Persist notice markers before broadcasting; an ambiguous
crash may lose a notice, but does not automatically replay already recorded ones.
Read-only status/forecast and MCP cache reads never mark notices or advance time.

Validate all fields/types, UUID, bounds and marker order against current campaign
time. Unknown/future/foreign/malformed data are retained without replacement and
disable the coordinator consistently with existing stores. No catch-all resets.
Normal checkpoint includes climate data. This does not make multi-file world saves
an atomic transaction.

## Integration and scope

Add climate to cached snapshot and /deadearth climate status|forecast (permission2).
Keep seven MCP tools; get_snapshot already transports the bounded projection.
No arbitrary phase control or dependence on a model. Human notices are informational
and server-authored. There is no temperature damage, snow/ice placement, sky shader,
weather override, biome change, resource/EMC change, Cold Sweat or bio-mod adapter yet.
These follow after the clock/saves/forecast have been checked.

## Acceptance

Pure boundaries, two calm directions, large jumps, short/normal profiles, warnings,
offline/pure reads, overflow, NBT preservation and restarted notice deduplication.
GameTests for day-time independence and main-thread snapshot/forecast integration.
Accumulated full pack server/client: initial notice, cached climate, offline pause,
restart origin/notice markers. Unit tests cover the complete eight-day cycle; a
short live check must not be claimed as a full 160-minute weather simulation.
One fresh final review, one fix pass, alpha.4 package/tag/report, then effects.
