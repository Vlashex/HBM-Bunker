# SRP / Epic Siege native port

Approved scope: continue the isolated 1.12.2 fork, add original SRP and Epic Siege, adapt the coordinator, automatic difficulty and AI. User explicitly requires tests only after all implementation changes. No installation over the live 1.21.1 server or existing world.

## Design
Forge 14.23.5.2860 / Java 8; SRP 1.9.21 (stable beta main file), Epic Siege 13.169. Keep the working inexpensive HBM/SSN slice. Separate legacy coordinator; reuse the existing typed cinematic catalog/engine as Java8 sources and the Python model host/administration. No forest or Mutationcraft IDs in target content. Native SRP phases 0–10 are the only parasite evolution authority. Pause campaign and progression without survival players. Coordinator scales controlled evolution by active time, player performance and season, writes through SRP API, and locks native autonomous point gain; no double progression. Configurable score multipliers and limits; preserve native SRP forms/abilities. Epic Siege keeps native pathfinding/digging; disable its independent boss/hard-day stat escalation.

Bridge keeps the existing loopback, world-bound token protocol. Model selects finite offered events/zones, never arbitrary commands, coordinates, or creature counts. Existing strict finite scenario graph/queue and checkpoint-before-effects retained. Native capability markers separate phase10/siege from modern phase5/forest. Registry validate all wave/sound references. Loaded-only targeting and bounded per-tick work; unknown/unopened loot not valued.

## Tasks
1. Pin enemy mods and provide configurable SRP phase entity pools / 15 cinematic SRP+siege events; preserve HBM storage costs.
2. Port pure cinematic kernel to Java8; preserve queue, cooldown, freeze, restart safety and finite budgets.
3. Implement Forge lifecycle/runtime adapter: persistent campaign, active clock, seasons/intro/adaptive difficulty, native SRP bridge, loaded zone scan, environmental actions/waves/analytics and operator commands.
4. Adapt Python capability validation, model digest and phase admin, preserve original modern protocol behavior.
5. Add reproducible Java8 compiler/packaging tools and isolated server/AI launch scripts. Include no saves/secrets/probes in archives.
6. Final verification only: unit suites (release/director/kernel), actual Forge fresh-world checks for native phases, pause/restart, queue admission/replay, entity spawning/attributes, Epic Siege dig AI and HBM/SSN interoperability. Use accelerated injected ticks for long timers, never wait hours. Record exact covered scope and limitations, commit/tag local artifacts.

## Review focus
Native SRP API booleans and absolute writes; phase10 max; entity spawn phase gating; no-player time; duplicate wave after restart; unknown loot exclusions; bridge token/epoch and server-thread ownership; script paths must select legacy artifacts only.

## Work log
- Existing storage slice commit 278f0c5 is base. Separate checkout already exists.
- User instruction overrides per-task test/TDD runs: all verification deferred to final stage.
- Independent pure-kernel and Python capability changes assigned to separate agents, no overlapping files.

- Native integration implemented as Core Port0.2.0: original SRP/ESM pins, Java8 runtime/kernel/bridge, six persisted relays, active-time native phases0–10, adaptive score/stats, finite phase-aware catalog, shared starter supply and isolated supervisor.
- Python host/MCP/admin gained explicit SRP capabilities; modern phase0–5 behavior retained. Fifteen events use73 registered sounds and48 inert trace patterns.
- Independent source review and final tests completed:42 packaging/storage/supervisor tests,154 director/MCP tests,2886 kernel assertions,17 rules assertions,3 DOM suites. Actual Forge fresh-world proof:170 native assertions plus27 live bridge/admin checks; actual saved-world restart:46 assertions.
- Test timing uses virtual active ticks for long timers and20 real ticks for restored chunk tickets; no hour-long waits. No production installation/world changed. Native test servers stopped normally.
- Real local Ollama was unavailable; end-to-end selection used a fixed selector, with real bridge admission/queue/admin. Human client GUI, full modern mod list/worldgen/thick ice/defense tiers/AE2 integration remain outside this core-port artifact.
- Deterministic Prism and Server Overlay artifacts are built locally under dist/. Local branch/tag only; no public release or push.
