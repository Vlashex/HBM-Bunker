# Server configuration editor implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans to implement task by task.

**Goal:** Human-editable validated server configuration with safe live application and explicit saved-world restart.
**Architecture:** Java owns config schema/validation/runtime transaction, Python owns human HTTP/receipts, JS renders existing visual panels, Compose supervisor handles fixed restart marker without Docker privileges.
**Tech Stack:** Forge1.12.2 Java8, Python3.12 admin / Python3.10 supervisor stdlib, vanilla JS/CSS, Docker Desktop Compose.
**Spec:** ../specs/2026-10-21-config-editor-design.md

## Global constraints
- Preserve modern project, worlds, AI context and private files.
- No package, tag, push or release before human has tried admin.
- Existing isolated SRP checkout, branch codex/live-config-admin; separate source ownership per task.
- No model/MCP configuration or console powers, Docker socket, force chunk load, client/performance testing.

## Review focus
- Partial batch file write or invalid nested configuration must leave runtime unchanged and recover disk state.
- Dirty UI values survive telemetry refresh and errors; hot apply never smuggles restart edits.
- POST timeout/repeated UUID must not apply twice; changed server revision rejects stale editors.
- Restart saves world, remains in same namespace, honors external shutdown and rolls back failed startup once.
- Wrong types/unknown keys/nonfinite/oversize values never reach live consumers or expose secret data.

### Task1 Java config contract and runtime adapters
Files: new legacy/coordinator/src/main/java/dev/deadearth/legacy/config/*, config schema resource, LegacyRuntime, owned config subsystem adapters, DeadEarthLegacy recovery hook; focused tests under coordinator/src/test and integration.
Interfaces: native commands/DTO/staging/marker exactly in spec.
- [x] Write validation/policy/transaction tests, observe failing.
- [x] Implement schema, whitelist, revisioned staged transactions, atomic runtime swaps, recovery and fixed restart marker.
- [x] Implement bounded live adapters; structural fields restart-only, document policy.
- [x] Run focused tests and compile, report evidence; root commits.

### Task2 Human admin API and Compose restart
Files: admin_configuration.py, admin.py route integration, runtime.py restart supervisor, focused director tests.
Interfaces: GET/POST/native commands as spec. Integrate receipt via existing OperatorConsole; no arbitrary process API.
- [x] Write DTO/nonce/stale/uncertain/restart tests and observe failing.
- [x] Implement API and fixed supervisor marker workflow, bounded state polling and rollback/recovery handshake with Task1.
- [x] Run focused Python tests; report evidence; root commits.

### Task3 Typed frontend in existing design
Files: admin_web/configuration.js and css, index.html/admin.js integration, JS tests. No Python/Java edits.
Interfaces: HTTP DTO exactly in spec. Fields refresh on Edit, three exact buttons, split policy groups.
- [x] Write state/dirty/error/restart-isolation tests and observe failing.
- [x] Implement typed form/help/errors, navigation and existing rule-list integration, preserve visual tokens.
- [x] Run focused and existing DOM checks; report evidence; root commits.

### Task4 Root integration and handoff
- [x] Resolve contract mismatches; independent whole-change code review and targeted regressions.
- [x] Run combined relevant tests then isolated native config apply/restart, build no release archives.
- [x] Back up and update local server/admin, safely restart if needed, verify real HTTP/config/console status.
- [ ] Open http://127.0.0.1:8766/#configuration for user; report ready for hands-on review and STOP before packaging.

Deployment: all five canonical Compose services running; four configured service health checks healthy; live config HTTP ready13/209, console ready, installed JAR matches final tested artifact. Browser open is queued. Screenshots/human visual handoff remain pending because computer-use inventory has zero apps/browsers; asked user to open the task on PC. No release archives/tags/push.
