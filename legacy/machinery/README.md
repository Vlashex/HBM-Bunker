# Compact legacy machinery

`dead_earth:charger` accepts FE/RF input through Forge Energy and one chargeable item. It charges Forge Energy items or HBM `IBatteryItem` tools/batteries, respecting the native HBM charge rate. The default conversion is 1 HE per 1 FE; configure `hePerFE` in `config/dead-earth/machinery.json` and restart. FE is debited from the charge the item actually accepts. The machine never outputs energy.

`dead_earth:fortune_processor` accepts one ore input stack and has nine output slots. It uses the original ore block's native `getDrops` with Fortune V by default. This is physical Fortune harvesting, not a crusher recipe: iron/gold and many HBM ores return the ore itself and ores that ignore Fortune continue to ignore it. It creates no replacement dusts or invented HBM processing yields. The HBM crusher remains the canonical crushing/metallurgy path. Default cost: 4000 FE and 40 ticks per ore.

An ore must be an actual block registered under an OreDictionary `ore*` name. Tile-entity and unbreakable ores are rejected. The processor never places or breaks an ore in the world. Its block-access adapter substitutes the ore state only for native drop evaluation and guards every neighboring read against unloaded chunks. Three exact inspected HBM drop implementations require a real World only to read its random generator; they receive the already loaded server World, and their pinned bytecode performs no terrain access.

Right click with an item to insert it. Empty-hand right click retrieves a charged item or the first output stack. Sneak with an empty hand to retrieve an unreserved processor input. Hoppers and XNet use the standard item-handler capability: slot 0 accepts input, slots 1–9 allow output extraction; processor input cannot be extracted automatically. All sides accept FE and expose the same item inventory.

Full outputs leave the ore and FE untouched. The single pending native drop roll is saved, so waiting or reloading cannot reroll Fortune. On breaking a blocked processor, its reserved ore and FE settle once and the pending result drops together with stored inventory; nothing is silently destroyed because its output slots were full. Machines do not load or ticket chunks. Config values are restart-only snapshots.

Both recipes retain HBM steel plates and native HBM circuits (`circuit:8` BASIC / `circuit:9` ADVANCED). The generator validates the two exact recipes and emits the CraftTweaker source plus JEI descriptions. The inexpensive early storage recipe is unchanged.

Implementation-stage compilation is permitted; all machine assertions and client/server testing are deferred to the full fork's final verification stage.
