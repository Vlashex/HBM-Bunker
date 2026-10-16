# DEAD EARTH Radio Catalog Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans.

**Goal:** A useful bounded catalog of relay radio events, before model integration.
**Architecture:** Immutable choice → server validation/receipt → final physical check;
MCP only supplies catalog/variant/discovered target/delay.
**Tech Stack:** Java 21, NeoForge 21.1.262, existing Python MCP SDK 2.3.0.
**Spec:** [radio catalog](../specs/2026-10-16-radio-catalog-design.md).

## Constraints and review focus

Retain warning compatibility and no secret/world access off-thread. No arbitrary
text, coordinates, spawns or commands. Preserve corrupt/future saves. No chunk
loads; validate full footprint at execution. User-authorized autonomous inline
execution, focused tests per task and game runs only at slice boundary.

### Task 1: choices, engine gates and save migration
- [ ] RED: choice bounds, unknown targets, per-site/global limits, cooldown, restore.
- [ ] Implement catalog selection, extended proposal/view, schema 1→2 migration.
- [ ] Focused tests and commit.

### Task 2: runtime and MCP integration
- [ ] RED: changed selection cannot reuse UUID; SDK forwarding and invalid choices.
- [ ] Add final relay executor, runtime view and bridge/gateway optional selection.
- [ ] Add GameTests; focused tests and commit without launching games yet.

### Task 3: accumulated verification and local release
- [ ] Full unit/packaging/gateway suites and fresh GameTest world.
- [ ] Full server + dev client/SDK radio, damaged relay, duplicate/cancel/restart.
- [ ] One fresh final reviewer and one fix pass with evidence.
- [ ] Alpha.3 notes/pins/ZIP, local tag/report; continue roadmap.
