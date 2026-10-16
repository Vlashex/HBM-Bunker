# Seasons, Adaptive Difficulty and Zones Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans. Execute inline continuously as explicitly requested by the user.

**Goal:** Выпустить v1 с полноценными сезонами, v2 с автоматом сложности, v3 с ценностью зон и затем выключить компьютер.

**Architecture:** Существующий координатор остаётся владельцем кампании. Климатическая политика, воздействие на игрока/поверхность, временные нативные адаптеры, статистическая политика и порционный сканер зон разделены. Новые блоки входят в собственный мод; версии внешних модов сохраняются.

**Tech Stack:** Java 21, NeoForge 21.1.262, Minecraft 1.21.1, JUnit 5, GameTest, Python 3.12 packaging.

**Spec:** `docs/superpowers/specs/2026-10-16-seasons-adaptive-zones-design.md`

## Global Constraints

- Зима parasites1.8/forest0.25; лето parasites0.5/forest1.5; затишье1.
- Здоровье и урон также масштабируются; нативные стадии не сбрасываются, коэффициенты не накапливаются.
- Не менять пользовательские миры/Prism-профили, рецепты HBM steel/plates и EMC-правила.
- Никакого ожидания целого сезона ради теста таймера; игровые прогоны группировать по выпуску.
- Новые версии сохранений сохраняют неизвестные данные и отключают неподдерживаемую подсистему.
- Локальные релизы 1.0.0, 2.0.0, 3.0.0; выключение только после завершённого 3.0.0.

## Review Focus

- Перезапуск зимой не применяет коэффициент повторно к уже изменённому значению.
- Укрытие, смена измерения и позднее подключение корректно меняют эффекты.
- Счёт игроков меняет нативную эволюцию, не сезонный календарь; изменение длительности через конфиг сохраняет текущую фазу.
- Пересечение загрузчиков и ME external storage не удваивает запасы.
- Пустая турель/отсутствующий мод/неизвестная цена не выдаются за подтверждённую защиту/нулевой запас.

## Task 1 — v1 policy and physical climate

**Files:** create `climate/SeasonPolicy.java`, `climate/SeasonWeather.java`, `climate/Exposure.java`, `climate/SeasonNetwork.java`, `client/SeasonVisuals.java`, `content/SeasonBlocks.java`; modify `CoordinatorRuntime`, `DeadEarth`, `ClimateCommands`; add policy/exposure tests and seasonal GameTests.

**Interfaces:** `SeasonPolicy.forPhase(Phase)` returns immutable forest/parasite profile; `SeasonWeather.tick(ServerLevel, Phase)` performs bounded work; `SeasonWeather.close()` clears owned temporary state.

- [x] Write policy/exposure tests asserting exact user coefficients, shelter recovery and creative protection; observe RED.
- [x] Implement policy, visual synchronization, weather, bounded fire/ice/crust and exposure; verify GREEN.
- [x] Add operator-only phase selection for quick integration checks, never MCP.
- [x] Audit actual native JAR hooks, implement isolated adapters, add real native value checks and attribute tests.
- [x] Run full Java tests, fresh GameTests, full-pack server/client seasonal checks; review/fix, package/release 1.0.0.

## Task 2 — v2 adaptive difficulty and pace

**Files:** create `difficulty/AdaptivePolicy.java`, `difficulty/AdaptiveStore.java`, `difficulty/PlayerStatistics.java`, `config/DeadEarthConfig.java`; modify native evolution/runtime/snapshot/commands and configurable seasonal duration.

**Interfaces:** policy consumes bounded statistics and produces pressure for native parasite/forest evolution and monster HP/damage; seasonal calendar stays independent. Server config controls gameplay defaults and bounds.

- [x] Write tests for bounded easing/escalation, idle players, changing roster, native development and migration; observe RED.
- [x] Implement saved statistics/policy and server config; preserve seasonal calendar and native evolutionary carry; verify GREEN.
- [x] Short native-evolution/config/calendar/restart checks, review, package/release 2.0.0.

## Patch 2.0.1 — thick winter coating (user steering before v3)

Existing weather flow; continuous execution remains authorized. Default top: two
full rime-ice blocks plus one snow block; sides: one rime-ice plus one snow block.
Configurable thickness, vertical scan depth and edits per tick. Original blocks
anchor coating; own coating never anchors another layer. Outdoor floor ignores
leaves and own seasonal blocks, preserving roofed interiors. Only air/thin snow
is filled; do not intersect entities or replace containers. Loaded footprint
checked before reads. New air coating thaws to air, water-origin ice to water.

- [x] RED: actual world checks for thick top/sides, bounded repeated growth,
  under-canopy floor and roofed interior.
- [x] Implement bounded coating and thaw, config and full-block assets.
- [x] GREEN plus no-force-load, collision, old crust and water-thaw regression.
- [x] Short full-pack server/client run; real forest screenshot requested by user.
- [x] Review/fix once, package, commit, local release 2.0.1, then continue v3.

## Task 3 — v3 loader and zone valuation

**Files:** create `zone/ZoneTopology.java`, `zone/ZoneScore.java`, `zone/ZoneScanner.java`, `zone/ZoneCompatibility.java`, loader block/entity/content assets; add commands/snapshot aggregate and unit/GameTests.

**Interfaces:** topology consumes block loader locations and yields disjoint chunk unions; score consumes resource EMC, coefficient and turret rank sums; scanner publishes bounded immutable aggregate with age/completeness.

- [ ] Write tests for overlap merging, negative formula, overflow/unknown values and shared storage dedup; observe RED.
- [ ] Implement own 3×3 loader with bounded persistent tickets and lifecycle cleanup.
- [ ] Implement read-only chest, ME and real turret adapters, incremental scan and aggregation; verify GREEN.
- [ ] Test loader independence from players, restart/removal, actual chest/ME/turret values; review/fix and release 3.0.0.

## Task 4 — delivery and shutdown

- [ ] Check all release artifacts, tags, hashes and source checkout; write final status and any limits.
- [ ] Gracefully stop test server/client processes, preserving all test worlds.
- [ ] Execute ordinary Windows shutdown only after v3 release is complete; never pre-schedule before success.

## Execution ledger

- Start: base908463f, clean linked worktree `feat/dead-earth-biosphere-climate`. User explicitly supplies requirements and continuous execution; extra skill approval menus waived by that instruction.
- Research: independent read-only native biology and zone API audits delegated; product implementation stays inline. Goal UI remains paused from earlier planning, but this user message explicitly resumes the work; no status-resume tool is available.
- v1: policy/exposure missing-class RED→GREEN; native counters/table-copy missing-class RED→GREEN; 89 Java tests passed before additional growth hooks. First real GameTest exposed crust thaw and unnegotiated mock-client packet, fixed. Full native GameTest force-loaded original native hooks successfully; embedded fixture lacked ProjectE channel setup, now uses official configureMockConnection. Roof exposure now uses immediate heightmap instead of delayed skylight.
- v1: actual client render found copied SNOW callback requiring missing LAYERS; replaced with carpet properties, added isViewBlocking regression. Following real client rendered ice forest successfully; screenshot delivered at outputs/Dead-Earth-ice-crust-forest.png. These are partial verification, NOT a release or completion claim.
- Ruling: own seasonal health modifier is persisted (one stable ID replaced from base) rather than transient — entity NBT must retain coherent HP/maxHP across reload — cost: removing this mod requires removing its attribute modifier from saved entities if the old seasonal value should disappear.
- Ruling: v3 counts local physical ME cells plus physical chests, not the whole touching network — aggregate includes external storage and cannot provide provenance, which otherwise duplicates resources and crosses zone boundaries — cost: remote cells outside the loaded zone are valued in their physical zone, not at the terminal.

- v1: five Important review findings fixed: scoped counter read, fern key, spawn events/cap reservations, dimension cleanup, loaded-neighbour weather. Native RED4→GREEN; final full-pack30/30, Java90/90, Python13/13, client winter/summer and offline restart pass. Evidence: verification/seasons-v1.

- User correction: v2 phase pace means native evolution/forest phases, not seasons. Unreleased ClimatePace removed; old virtual-clock review findings are obsolete. Logout baseline finding is fixed by event lifecycle; campaign/adaptive pairing and strict checkpoint matching retained. New server-config requirement incorporated into v2 before release.

- v2: corrected native-evolution scope delivered;99 Java,39 full-pack GameTests,13 packaging tests pass. Native outbreak/forest points verified1.5x with unchanged season. Actual client synced custom35min/.25cap/.5HP; dedicated v1 migration and restart preserve6533 active ticks, climate and1.2pressure.
- v2 final review3Important fixed: putrid spread channel, elapsed exposure damage cooldown, truly neutral disabled state. Native RED2→GREEN and exposure helper RED→GREEN. Protocol2 sends inactive phase sentinel on failure. Earlier virtual-calendar review obsolete after user correction; logout/pairing checks retained and verified.
- Ruling: server-config duration and forecast changes apply at the next phase boundary — preserve current season and notice validity — cost: editing phaseMinutes does not shorten the currently running phase. Explained to user while implementing.
- Ruling: idle players excluded from statistics by default, configurable — avoid AFK players diluting active group score — cost: stationary passive farming without combat/low health does not count as activity.
- v2 package selector now includes verification documents/images and TOML examples; fixed historical v1 packaged link without rewriting old release/tag. No v3 product code yet; native audits and work/v3-design-addendum.txt ready.

- Patch2.0.1 RED: two actual full-pack world failures: thin carpet instead of thick top, no under-canopy floor. User additionally requested gradual visual growth and bounded loaded-only work; eight directional shape stages (default two layers/pass), shared24 edits/2 sections per tick. Screenshot after final client check.

- User addition: smooth height relief plus rare ice peaks. Algorithm1 derives a height field from world seed and saved climate cycle number, evaluated lazily, not an allocated global map. Same winter/restart stays stable; next cycle changes seed. Config defaults relief ice+0..2, snow+0..1, side ice+0..1; peak cells48, chance.25/cell, extra height4..8/radius3..6, bare ice tips. No terrain reads/no chunks needed for pure field sampling. Source-only tests verify negative coords, chunk-order independence, smoothness, bounds, rarity and next winter difference. Peak output shares ordinary growth/collision/loaded/budget rules.
- Natural client forest exposed common grass preventing ground coating: actual-world RED reproduced1 failure. Whitelist ordinary vanilla grass/ferns/dead bush for burial; preserve saplings, other mod plants, containers. Common cover does not regrow automatically at thaw. This changes the earlier blanket plant-preservation assumption to satisfy continuous snow cover in ordinary forest.

- New relief review: snow-only configuration lost snow at peaks; conversion extracted to pure WinterTerrain.thickness and covered by zero-material test.103 unit tests passed;46 full-pack GameTests passed before subsequent scheduler regression.
- Real maturity check found deferred new-air layer could disappear from active queue after section-budget refusal. Corrected fixture RED:1/10 review tests fails specifically with 'Deferred next layer was dropped instead of retried'. Growth now retains pending work after refused write. Test camera helper can promote its bounded loaded area without changing production FIFO/caps; no MCP exposure.

- Deferred-growth regression is GREEN after pending-write fix. New full47 run exposed a test fixture error: 'east1' crossed a chunk boundary for that random GameTest origin. Budget correctly rejected a different section. Fixture now selects an adjacent block inside the same section; product code unchanged. Re-running full GameTests, keeping passed103 pure tests for the same product logic.

- Tall-tree RED:80-block crown leaves lower trunk and floor uncoated with old48 depth. Default depth now384, skip below exterior floor, reserve at most2048 vertical cells/tick across work,16 discovery columns/tick. Deferred cells stay queued; missing cell-budget API RED then implementation. This covers tall forest without an unbounded vertical scan per tick.

- Patch2.0.1 final:103 Java,49 full-pack GameTests (17.35s),13 release tests; final dedicated server/client passed and stopped cleanly. Real forest and rare peak frames in verification/winter-2.0.1. Local2.0.1 package verified; continue Task3.
