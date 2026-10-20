# Native 1.12.2 wasteland policy

The native Overworld terrain, biome map and ore generators remain in control. The wasteland pass runs after native/HBM world generators and edits only its current newly populated chunk. It never repaints chunks read from disk, looks up or generates a neighbor, or adds ambient radiation. The palette contains only inert native stone/rubble and the independent `dead_earth:ash` block. Other dimensions and underwater terrain remain native.

## Craters and ordinary ores

Crater geometry is a deterministic absolute-coordinate field: many small craters and rare large bowls. Native ordinary ores do **not** reserve a column or chunk. Ordinary ores include native BlockOre subclasses and OreDictionary `ore*` blocks, including metadata variants and ores from other pinned mods; registry names alone do not decide protection. Ordinary ore blocks lying strictly above the final bowl floor and within the excavated terrain are removed together with the surrounding stone. Ore cells at the final bowl floor and below it remain their exact native state, including the thin coating band; the pass never moves ores, synthesizes new ore deposits, replaces native ore generation, or changes native underground distribution outside the excavated volume. Surface ore, mossy cobblestone and natural cobblestone cannot leave pillars or ore-rich flat patches in a bowl.

## Protected features

HBM architecture, native CUSTOM MapGenStructure bounding boxes, current phased/deferred HBM reservations and all affected block entities protect the complete local footprint. Ordinary ore is separate from this protection. Vanilla bedrock and dedicated HBM bedrock deposits (`ore_bedrock*`, including coltan/oil/deposit blocks, and `sellafield_bedrock`) remain protected. Protection is conservative at the current-chunk boundary and can leave native terrain around an actual protected feature; it does not create isolated natural-block immunity.

Only exact vanilla surface village/temple/mansion generators and vanilla TREE/GRASS decoration are suppressed. HBM custom architecture and generators, mineshafts, strongholds, ocean monuments, ores and other dimensions retain native behavior. Native HBM plants and geologic stones are classified separately from constructed architecture.

## Bounds, configuration and verification

`config/dead-earth/worldgen.json` is a restart-only settings snapshot. Every chunk is planned before the first write, checked against the whole-chunk write budget, and written through the already loaded current chunk. Crater candidates use no mutable random stream or chunk visitation history. The inert ash, bare trunks, water tint and underwater fog have independent resources/client-side hooks.

The deferred `WastelandChecks` assertions include homogeneous ore-rich multi-chunk deep bowls, absolute expected height on every border and interior column, reverse visitation order, ordinary ore preservation at/below the bowl, dedicated bedrock deposits, natural mossy/cobble blocks, HBM architecture, block entities, water and write budgets. `WastelandProbe` provides final-stage fresh-world/native one-shot checks. These assertions were authored and compiled during implementation; execution remains deferred to the full fork's final verification stage.
