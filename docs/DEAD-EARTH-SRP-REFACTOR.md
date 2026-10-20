# SRP refactor 1.1.0

User scope: legacy Forge1.12.2 mod and GAME datasets only. Training corpora, model weight contents and the modern installation remain unchanged.
Infrastructure now copies the pinned CPU weights into Docker Desktop. Existing registry IDs,
campaign/save formats and finite V1/V2/V3 defense remain compatible.

Implementation slices:
1. One strict config reader, immutable entity/phase roster, separate zone scanner
   and scenario analytics; retain main-thread world access and bounded work.
2. One material catalog drives canonical HBM outputs, equal-form legacy aliases,
   recipes, EMC and valuation. Unique materials stay. Tagged/capability-bearing
   stacks are never discarded or blindly converted; metallurgy keeps its gates.
3. Explicit ESM entity profiles replace blanket exclusions; group retaliation
   follows player provocation. Selected ground SRP gain siege work while keeping
   native AI. Cached digging rechecks current block, world rules and protection.
4. Global all-block player visibility (64 blocks by default, up to128), with native
   aggression predicates retained. Hostiles use real loaded relay block objectives;
   finite HP, griefing, V1-V3, starter grace and offline suspension apply.
5. Audit own registry against static resource graph AND actual baked models;
   repair real missing paths/bindings and readable names.
6. One Docker Desktop Compose owns Minecraft, CPU inference, director, admin and
   Playit; only25565 and8766 bind Windows loopback. No docker socket, arbitrary
   model commands or GPU inference. Existing world/weight sources are preserved.
7. Only after edits: pure/config tests, full isolated native pack/restart,
   functional ESM actors and one isolated GL client with registry-wide bake check.
   No stress benchmark. Never run a second client alongside the human's window.

Final report will distinguish static validation, native gameplay evidence and
limits. Changes get independent commits. The user authorized the stable V1 GitHub release
on 2026-10-20 after successful final checks; the tag is srp-v1.1.0.

Development formatting: tools/legacy_format.py uses the pinned development-only
formatter in legacy/metadata/formatter.lock.json. No formatter or test classes ship.
Generated material/progression and full native ESM rows are checked before packaging.
