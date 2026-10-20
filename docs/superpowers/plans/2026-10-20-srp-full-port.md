# SRP Full Fork Implementation Plan

> **For agentic workers:** Use superpowers:executing-plans plus dispatching-parallel-agents for isolated subsystems. User explicitly overrides per-task test runs: all verification at end.

**Goal:** Complete all approved DEAD EARTH gameplay on Forge1.12.2.
**Architecture:** Extend existing Java8 coordinator with isolated worldgen/climate/defense modules; official pinned legacy mods and generated HBM recipes; retain finite bridge/AI protocol.
**Tech Stack:** Forge14.23.5.2860, Java8, CraftTweaker4, Python3.12, current admin.
**Spec:** docs/superpowers/specs/2026-10-20-srp-full-port-design.md

## Global Constraints
- Work only isolated fork; no production installs. No OV/Mutationcraft/SC/Tinkers/shaders.
- Tests/client/server/inference deferred until all implementation is complete; compilation and source inspection permitted.
- No arbitrary model commands/coordinates/counts. Preserve durable queue/receipts and phase0-10.
- Pin exact official artifacts/size/hash; no game binaries/secrets/worlds in Git.

## Review Focus
- Chunk borders/unloaded chunks: no cascade generation or lost catchup; deterministic fields.
- HBM structures and natural mossy blocks: crater protection must preserve structures without leaving pillars.
- SRP/ESM direct World writes: V3 cannot bypass defense, player remodeling remains allowed.
- Client side callbacks must not touch server runtime; resource/model registration matches1.12.2.
- Recipes/EMC/automation must not create cheap metal plate/steel technology bypasses.

### Task1: Wasteland
Files: legacy/coordinator/src/main/java/dev/deadearth/legacy/worldgen/*, matching assets, legacy/pack/config/dead-earth/worldgen.json.
Produces LegacyWasteland.install/registerBlocks/registerItems/registerModels(Path/Object/noargs) as spec.
- [x] Port deterministic crater/surface field and native Forge hooks, dirty water/dead trunks, HBM structure exclusions.
- [x] Author final-stage assertions for border/no-pillars/structures; do not run yet.

### Task2: Thick winter and seasons
Files: legacy/coordinator/src/main/java/dev/deadearth/legacy/climate/*, assets, climate.json.
Consumes LegacyRuntime; produces LegacyClimate.install/register*/tick(runtime)/close.
- [x] Port bounded reversible top+side coating, noise/peaks, active season/weather/player effects, client presentation.
- [x] Author deterministic/loaded-only/thaw assertions for final stage.

### Task3: Defense and shelter
Files: legacy/coordinator/src/main/java/dev/deadearth/legacy/defense/*, assets, defense.json and defense recipe source.
Produces LegacyDefense install/register* and allowChange(world,pos,proposedState)/isDefense(block).
- [x] Walls/doors/hatches1000/2000/8000HP, sparse persisted damage, native siege/conversion hooks, HBM recipes.
- [x] Provide shelter generation helper and shared starter delivery integration proposal.
- [x] Author final-stage assertions for damage, conversion, player mining, door halves and reload.

### Task4: Remaining mods and progression
Files: legacy/metadata/full.mods.lock.json, legacy/metadata/mod-map.json, legacy/pack/config/*, tools/legacy_port.py and recipe generator, focused custom gameplay classes/assets as needed.
- [x] Research official legacy builds and map every original mod/dependency to retained/replaced/removed-by-request.
- [x] Download/hash native AE2/MA/ProjectE/RFTools/sieves/QOL/performance/food-water; avoid heavy dependencies for one feature.
- [x] Port HBM recipes, raw-only EMC, tools/enchanted books, automatic crops and automation chain.
- [ ] New scope after successful native snapshots: compact sieves III/IV/V for81/729/6561 blocks, nine previous meshes per next tier, placeable compressed blocks; implementation and verification pending.

### Task5: Root integration and launch
Files: DeadEarthLegacy.java, LegacyRuntime.java, MutationGuard.java/core, Reflect.java/stubs, Campaign.java/GameWorld.java, tools/legacy_server.py/build scripts and docs.
- [x] Wire subsystem lifecycle/registration without duplicated clocks/weather; AE2 inventory adapter bounded and loot safe.
- [x] Update full profile/version, isolated CPU AI startup/single-dual, admin, portable BAT and install instructions.
- [x] Build Prism/server/TLauncher artifacts after integration; CRC, pins, current coordinator and Java8 classes validated.

### Task6: Final verification/review/release
- [x] Compile Java8 code/test sources and run source suites: Python46, director154, seven Java groups, five DOM suites (root-observed); later worldgen kernel349699 passed.
- [x] Native3 fresh normal-terrain Forge full pack:282 recorded checks/12 full groups, bridge27; actual JVM save/restart46. These results predate the final vertical-reservation worldgen fix.
- [x] Repeat full native coverage after worldgen vertical-reservation fix: native fixture20261020T070730274765Z-b961f010 passed282 recorded checks/12 groups, bridge27 and sentinel preparation. This predates the new compact-sieve/compressed-block scope.
- [x] Real CPU single/dual calls against the native fixture: one context, real models, single admitted, dual primary+critic valid. Participant was a native fake survival player, not a human client.
- [x] Both real clients joined new world-wasteland;86 model texture references checked. Human visual acceptance/long playthrough remain explicit playtest limitations.
- [x] Save sanitized evidence and actual-context model review in verification/srp-full-1.0.0 (not a model-quality improvement claim).
- [x] Close final independent review, fix lifecycle/stale-handler findings, verify latest native286/bridge27/restart47 and freeze archive checksums.
- [x] Local full release gated on passing functional checks; feature and reversible reflection-cache optimization committed separately, tag srp-v1.0.0.

## Progress / rulings
- Base a216136. Existing core-port tests are historical and do not prove this full fork.
- User has already explicitly requested uninterrupted execution until full readiness; no repeated design approval pause.
- Native missing modern mods may use light equivalents/focused custom code, preserving mechanics; exact differences recorded in mod-map.

- Full implementation integrated. The HBM method-linking issue, native farming break/collision callbacks and defense batched durability fixes were closed before the successful native3 snapshot.
- Native3 functional results:282 recorded checks, bridge27, actual JVM restart46. The earlier 47-check estimate was corrected to the actual saved report count46.
- Source freeze ruling: vertical-reservation worldgen fix has pure coverage349699 and a later native repeat passed282/bridge27. Native3/restart46/real-AI remain earlier snapshots. Neither native snapshot verifies the newly requested compact-sieve/compressed-block additions; final artifacts and client-new-world results remain pending.
- Coverage limitations are explicit in verification/srp-full-1.0.0/readiness.json: no observed native HBM ore/architecture preservation samples in native3, deferred HBM ore callbacks not observed, pending crop/machine transactions not exercised through a real JVM restart. No performance benchmark is claimed.

- Compact sieves III/IV/V and all19 compression profiles integrated; latest native286/bridge27 passed, real JVM restart47 includes pending sieve conservation. Python47 passed; both updated clients joined new world-wasteland and user continued singleplayer in second client. Previous server/SP worlds preserved. No performance benchmarks.
