// Generated from legacy/machinery/recipes.json; restart after editing source.
// HBM CE2.6.1.0: plate_steel, circuit:8 BASIC, circuit:9 ADVANCED.
// Native Fortune is not a crusher: HBM ores retain their own original drops.

recipes.remove(<dead_earth:charger>);
recipes.addShaped("dead_earth_charger", <dead_earth:charger>, [[<hbm:plate_steel>, <minecraft:iron_ingot>, <hbm:plate_steel>], [<minecraft:redstone>, <hbm:circuit:8>, <minecraft:redstone>], [<hbm:plate_steel>, <minecraft:iron_ingot>, <hbm:plate_steel>]]);

recipes.remove(<dead_earth:fortune_processor>);
recipes.addShaped("dead_earth_fortune_processor", <dead_earth:fortune_processor>, [[<hbm:plate_steel>, <hbm:circuit:9>, <hbm:plate_steel>], [<minecraft:diamond>, <minecraft:diamond_pickaxe>, <minecraft:diamond>], [<hbm:plate_steel>, <hbm:circuit:9>, <hbm:plate_steel>]]);

mods.jei.JEI.addDescription(<dead_earth:charger>, ["FE input. Charges FE items and native HBM battery tools.", "Insert with right click; retrieve with an empty hand. Hopper/XNet compatible."]);
mods.jei.JEI.addDescription(<dead_earth:fortune_processor>, ["Native ore drops with Fortune V; 4000 FE per cycle by default.", "Many HBM ores and vanilla iron/gold return themselves. No invented dusts; use HBM crushing for native metallurgy.", "OreDictionary ore* blocks only. Full output keeps ore and FE; pending drops survive reload."]);
