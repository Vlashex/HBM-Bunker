# SRP core-port 0.2.0 verification — 2026-10-20

All game/test runs began after implementation. The production checkout, server and worlds were not changed.

- Forge14.23.5.2860, Java8, fresh flat world, loopback25585, 4GiB.
- Native report: 170 assertions including group outcomes; original SRP/ESM/HBM load, phase0–10 CAS/replay/bounds, active clock pause, transformed write guard vs player edits, six real ticketed relays, finite loaded valuation, unopened loot unchanged, health modifier no stacking, locked wave exclusion, actual wave emission, native typed execution and saved campaign re-read.
- Live bridge: actual packaged BridgeClient, CinematicDirector/Journal, finite choice admission/replay, invalid secret/epoch rejection, actual admin Observer and HTTP dashboard. The selector is a fixed stub; no claim of real model inference. Local Ollama tags endpoint was unavailable.
- Python director/MCP:154 tests; release/storage/legacy/supervisor:42 tests.
- Pure Java8: KernelChecks2886 assertions, RulesChecks17; three Node DOM suites pass.
- Compiler --release8; packaged class major52; Minecraft compile-only API stubs, tests, worlds and credentials excluded. Hashes/pins checked by builder.

First native attempts found asset sound entries without registry events, a Forge/player capabilities name collision, non-inventory HBM bedrock-ore tiles, and null-bearing saved DTOs. Each was fixed at the source; each subsequent native attempt used a new world. No timer was checked by hours of waiting; controlled virtual active ticks were used.

Independent source review identified and fixed incomplete scans/stale tile references, pre-mitigation damage/duplicate attribution, integrated-client thread entry, restored ticket radius, unbounded entity scans/unloaded-wave clear, partial-save replacement and Java-properties escaping. Actual restart:46 assertions after20 normal server ticks; all6 physical relays and their9-chunk tickets restored naturally, campaign identity/epoch/activeTicks/phase/points and queue preserved, no-player pause retained and no interrupted effect replayed. The first restore probe ran before world ticks and reported an unloaded remote relay; its evidence is preserved under work/. Retesting at20 normal ticks passed without product code changes or test force-loading.

Not covered: human client GUI/multiplayer play session, real Ollama inference, migrated modern worlds, full modern mod list, AE2 grid adapter, thick winter terrain, modern wasteland generator, V1/V2/V3 defense port. This profile is the native enemy/coordinator integration, not a complete replacement of the modern pack.

Reproduce: tools/legacy_port.py compile --tests; Java8 KernelChecks and RulesChecks; unittest discover -s tests; director/.venv Python unittest discover -s director/tests; three director/tests/*ui_test.cjs; tools/verify_legacy_server.py and tools/verify_legacy_restart.py. Runtime paths and raw logs remain under ignored work/.
