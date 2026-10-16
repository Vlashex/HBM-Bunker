# DEAD EARTH — bounded radio catalog

## Intent and stage decision

The user authorized continuous roadmap development, commits, batched game runs
and local alpha releases. Stage decisions are made inline; external publication
and changing user profiles remain excluded. After alpha.2, extend the catalog
before giving a model more choices. Use the brainstorming and writing-plans
workflow; no approval menu is needed under this standing authorization.

## Chosen slice

Keep warning_beacon_v1 and its old wire request valid. Add relay_broadcast_v1
with only three server-authored variants: check_in, survey, rally. Only a discovered
relay UUID can be selected; there are no arbitrary coordinates or free-form text.
Delay is 200..1200 active ticks in steps of 20; expiry is 1000 ticks after earliest.
Messages identify the chosen station and its already discovered coordinates.
They do not invent weather, combat, or rewards.

One pending radio per station, at most four pending radios globally. Non-cancelled
accepted radio receipts reserve a station until 2400 active ticks after earliest;
this conservative cooldown also survives uncertain/expired attempts and restarts.
Legacy warning tests keep their existing rules. UUID reuse with another selection
is rejected; the same selection returns its original receipt even as time/epoch
advance. Cancellation does not roll back an effect.

## Execution boundary

WorldView supplies immutable known/loaded target IDs. A station must have been
discovered to enter the known set. Loaded is tested with hasChunksAt over the
whole footprint, without loading chunks. The final executor verifies READY and
physical blueprint again on the server thread; missing blocks yield UNCERTAIN
without a message. No block scans of all stations on every tick.

Proposal stores catalogId, variant and requested delay explicitly. Schema 2
migrates schema 1 warnings without changing IDs, times or states; unsupported or
malformed content is retained and disables the store, never silently discarded.
RUNNING still becomes UNCERTAIN on load.

MCP keeps seven tools. preview/propose receive optional catalog_id, target_id,
variant and delay_ticks, with legacy defaults. Both Python and Java reject unknown
fields, invalid choices and out-of-range delays. Catalog entries explain variants,
limits and target source. Cached snapshots remain observations rather than authority.

## Alternatives and exclusions

Free-text narrative was rejected because it adds prompt/content authority before
there are real event adapters. Destructive incidents and automatic structure
placement wait for tested permissions and climate/worldgen slices. This slice
adds no LLM, scheduler, entity spawn, item rewards or new external dependencies.

## Acceptance

RED/GREEN tests for choices, duplicate conflicts, target/cooldown/queue gates,
schema migration and actual SDK parameter forwarding. GameTests for discovery,
final damaged-station veto and main-thread dispatch. After accumulated changes:
fresh GameTest world, full pack server + dev client + SDK, restart persistence,
fresh final review, local alpha.3. No full game run per commit.
