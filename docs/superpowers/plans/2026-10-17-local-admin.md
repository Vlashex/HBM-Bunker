# Dead Earth Admin Implementation Plan

**Goal:** Локальная наблюдаемость ИИ без воздействия на игру.
**Architecture:** Python stdlib HTTP + SQLite observer; существующий мост и журнал. Buildless русский UI.
**Spec:** ../../DEAD-EARTH-ADMIN.md

## Constraints
- Loopback only, GET/HEAD only; no credentials, no arbitrary commands.
- Read-only bridge operations only; no get_cinematic_brief polling.
- Distinct campaigns/epochs, bounded retention, honest offline/empty states.
- Preserve installed server/world and immutable v4 release.

## Review Focus
- Missing/corrupt/locked journal must not break page or mutate journal.
- Restarts and changed epochs must not join chart lines or misattribute decisions.
- Host/Origin rejection and untrusted text must prevent remote reads/script injection.
- Queue outages and stale snapshot must not appear as live successful samples.
- Packaged static assets must be included without runtime/secrets.

## Tasks
- [x] Add failing tests: observer persistence/retention/scope, read-only journal and projection, bridge operations, HTTP guard/exports, future decision audit, packaging.
- [x] Implement admin_store.py, admin.py and host decision audit; run focused and gateway tests.
- [x] Add static index.html/admin.css/admin.js, launcher and package assets; run root tests/check.
- [x] Launch observer against installed server, inspect API and UI logic, record verification. Visual GUI check explicitly deferred; commit/fast-forward closes this slice.

Execution: inline, continuing existing autonomous authorization. No full Minecraft rerun: game code is unchanged.

## Review / verification ledger

- Gateway suite: 41/41; root packaging suite: 13/13; release.py check passed.
- DOM harness: five views, live charts, audit details/search, persisted archive, catalog and literal untrusted text passed. This is not a browser visual check.
- Independent admin_review: three Important findings, no Critical/Minor. Each reproduced RED and fixed: explicit gap_before before decimation; journal/telemetry scope union and one selected epoch; packaged root BAT.
- History archive added with a RED→GREEN test: server ring rotation does not erase already observed outcomes/events.
- Ruling: native UI/browser visual verification is deferred because Computer Use could not determine browser URL, then the user explicitly asked to stop Computer Use. No further GUI actions. Cost: layout/responsive appearance needs human review in the already open panel.
- Ruling: running Minecraft and AI processes are preserved. Updated host audit applies on the next normal AI start; historic missing context is never reconstructed. Cost: existing AI process continues writing the older audit format until restart.
