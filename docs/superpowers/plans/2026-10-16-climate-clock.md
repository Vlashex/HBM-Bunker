# DEAD EARTH Climate Clock Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans.

**Goal:** Persisted climate timeline and bounded forecast with no physical effects.
**Architecture:** Campaign active ticks → pure cycle → SavedData notice markers →
server-thread notices; cached observation exposes current phase to existing MCP.
**Tech Stack:** Java21, NeoForge21.1.262, existing MCP gateway.
**Spec:** [climate clock](../specs/2026-10-16-climate-clock-design.md).

## Constraints/review

No dayTime/wall-clock dependencies, hidden duration remapping, world mutation,
new mods, LLM or phase-setting MCP tool. Preserve invalid/future saves. Checkpoint
markers before notices. Read-only API cannot advance clocks or notices.
Autonomous inline execution authorized; tests per logic change, games per slice.

### Task 1: pure cycle and persistence
- [x] RED/GREEN: boundaries/directions, forecast, notices/offline, restore/invalid NBT.
- [x] Implement constant-time cycle and schema1 climate store; focused tests/commit.

### Task 2: runtime and observation integration
- [x] Add runtime/store/checkpoint, read-only operator commands, cached climate.
- [x] Add GameTests; focused unit/build checks, then commit.

### Task 3: accumulated checks and local release
- [x] Fresh GameTests; full pack server/client/MCP initial notice/pause/restart.
- [x] One fresh final reviewer, fix pass and relevant checks.
- [x] Notes/pins/ZIP/tag alpha.4; report and continue effects/adapters.
