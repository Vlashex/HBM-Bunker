# Performance and protection audit implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Find measured client/server bottlenecks in the 5.2.0 pack and assess three protection classes.

**Architecture:** Opt-in development probes live in tools/loadtest and are included only with a Gradle property. A loopback-only disposable server and its client run bounded scenarios. A separate audit checks actual native destruction paths and protection metadata.

**Tech Stack:** Java 21, NeoForge 21.1.262, Python standard library, JFR, native RCON.

**Spec:** User requests mass tests for chunks/entities/other scenarios, a bottleneck report, and checking protected blocks plus possible V1/V2/V3 classes.

## Global constraints

- Minecraft 1.21.1, pinned pack 5.2.0; no live-world changes or main-server interruption.
- Computer Use and screenshots remain disabled.
- No heavy permanent profiling dependencies; no changes to gameplay balance as part of measurement.
- Measurements use real wall time, bounded phases, and actual observed counts; no tick sprint for TPS claims.
- Keep raw large logs/JFR/worlds outside Git. Commit summarized evidence, harness, and reproduction steps.

## Review focus

- Record cold generation separately from warm loaded chunks and entity ticking.
- Count actual loaded/ticking chunks and surviving entities, not only requested load.
- Measure client shader state, frame times, resolution and distance; settle after reload.
- Report co-resident live server/client/model and instrumentation limitations.
- Direct setBlock/removal paths can bypass break events; hardness does not establish tier protection.

## Task 1: Reproducible server and client measurements

**Files:** tools/loadtest/, mods/dead-earth/build.gradle, verification/performance-2026-10/

- [x] Add gated server tick/heap/GC/chunk/entity telemetry and gated client frame telemetry.
- [x] Compile opt-in harness, prove no probes in ordinary release JAR.
- [x] Prepare a fresh normal terrain server and client with matching 5.2.0 mods.
- [x] Baseline, cold/warm chunk loads, 250/750/1500 entity loads with/without AI, biology, six zones/container inventories, winter, save/unload.
- [x] Compare shaders enabled/disabled in repeated stable scenes; capture CPU samples and GC through JFR.
- [x] Stop owned processes and summarize p50/p95/p99, achieved TPS, heap/GC and hot stacks.

## Task 2: Protection audit

**Files:** docs/PROTECTION-AUDIT.md, verification/protection/

- [x] Inspect SecurityCraft/HBM hardness/resistance and tier-related mechanics.
- [x] Inspect ImprovedMobs/Mutationcraft/Overgrown native break/replace/explosion paths.
- [x] Run bounded native checks where feasible; distinguish structural audit from tested gameplay.
- [x] Recommend native configuration/mapping or minimal custom block logic for V1/V2/V3; do not silently add a new progression system.

User subsequently authorized the custom defense progression and confirmed the
Outpost Zero steel appearance. Its implementation is tracked separately in
`2026-10-18-defense-materials.md` and is excluded from this baseline benchmark.

User also requested multiple parasite species and key camera/mob movement
measurements. Keep fixed comparisons and separately labelled native synthetic
trajectories, recording actual camera spans and sampled mob displacement. A
teleport trajectory is not manual walking or a six-client cooperative session.

## Task 3: Reports and commits

- [x] Validate metrics and document confidence/limitations without invented throughput.
- [x] Commit instrumentation separately from results; do not publish or deploy automatically.
