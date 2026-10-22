// V0id's Smart Backpacks 1.0.6: HBM parts, no portable grinding/generation.
// Keep native backpack_upgrade recipes: ordinary replacements lose contents/modules.
recipes.remove(<v0idssmartbackpacks:backpack>);
recipes.addShaped("dead_earth_smart_backpack", <v0idssmartbackpacks:backpack>, [[<minecraft:leather>, <minecraft:chest>, <minecraft:leather>], [<minecraft:leather>, <minecraft:string>, <minecraft:leather>], [<hbm:plate_iron>, <minecraft:string>, <hbm:plate_iron>]]);

recipes.remove(<v0idssmartbackpacks:upgrade_base>);
recipes.addShaped("dead_earth_backpack_module_base", <v0idssmartbackpacks:upgrade_base>, [[null, <hbm:plate_iron>, null], [<minecraft:stone>, <minecraft:redstone>, <minecraft:stone>], [null, <hbm:plate_iron>, null]]);

recipes.remove(<v0idssmartbackpacks:upgrade_magnet>);
recipes.addShaped("dead_earth_backpack_magnet", <v0idssmartbackpacks:upgrade_magnet>, [[<minecraft:iron_ingot>, <hbm:plate_copper>, <minecraft:iron_ingot>], [<minecraft:redstone>, <v0idssmartbackpacks:upgrade_base>, <minecraft:redstone>]]);

recipes.remove(<v0idssmartbackpacks:upgrade_pulling>);
recipes.addShaped("dead_earth_backpack_pickup", <v0idssmartbackpacks:upgrade_pulling>, [[<v0idssmartbackpacks:upgrade_base>], [<minecraft:hopper>]]);

recipes.remove(<v0idssmartbackpacks:upgrade_void>);
recipes.addShaped("dead_earth_backpack_void", <v0idssmartbackpacks:upgrade_void>, [[<v0idssmartbackpacks:upgrade_base>], [<minecraft:lava_bucket>]]);

// Filter keeps its inexpensive native paper/book + HBM-gated module base recipe.
recipes.remove(<v0idssmartbackpacks:upgrade_grinding>);
recipes.remove(<v0idssmartbackpacks:upgrade_generator_em>);
recipes.remove(<v0idssmartbackpacks:upgrade_generator_furnace>);
recipes.remove(<v0idssmartbackpacks:upgrade_generator_kinetic>);
recipes.remove(<v0idssmartbackpacks:upgrade_generator_nuclear>);
recipes.remove(<v0idssmartbackpacks:upgrade_generator_solar>);
recipes.remove(<v0idssmartbackpacks:upgrade_generator_wind>);
