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
- Адаптивные часы не скачут назад и не перепрыгивают фазу после перезапуска.
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

**Files:** create `difficulty/AdaptivePolicy.java`, `difficulty/AdaptiveStore.java`, `difficulty/PlayerStatistics.java`, `climate/ClimatePace.java`; modify climate store/runtime/snapshot/commands.

**Interfaces:** policy consumes bounded per-window statistics, produces pressure and phase pace. ClimatePace consumes campaign deltas and rate, persists fractional carry.

- [ ] Write tests for bounded easing/escalation, idle players, changing roster, fractional time and migration; observe RED.
- [ ] Implement statistics, saved policy and clock; preserve base seasonal coefficients and pause semantics; verify GREEN.
- [ ] Short phase-crossing/restart checks, review, package/release 2.0.0.

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
