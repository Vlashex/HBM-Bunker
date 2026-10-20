// Generated from legacy/defense/recipes.json by tools/legacy_defense_recipes.py.
// V1 fits the 2x2 inventory grid; HBM screwdriver is returned unchanged.
// Native HBM IDs verified against NTM-CE-1.12.2-2.6.1.0 ModItems bytecode.

recipes.remove(<dead_earth:defense_wall_v1>);
recipes.addShapeless("dead_earth_defense_wall_v1", <dead_earth:defense_wall_v1>, [<minecraft:iron_block>, <hbm:plate_steel>, <hbm:screwdriver>.anyDamage().reuse()]);

recipes.remove(<dead_earth:defense_wall_v2>);
recipes.addShaped("dead_earth_defense_wall_v2", <dead_earth:defense_wall_v2>, [[null, <hbm:plate_steel>, null], [<hbm:ingot_titanium>, <dead_earth:defense_wall_v1>, <hbm:ingot_titanium>], [<hbm:plate_steel>, <hbm:plate_steel>, <hbm:plate_steel>]]);

recipes.remove(<dead_earth:defense_wall_v3>);
recipes.addShaped("dead_earth_defense_wall_v3", <dead_earth:defense_wall_v3>, [[<hbm:plate_titanium>, <hbm:ingot_tungsten>, <hbm:plate_titanium>], [<hbm:ingot_tungsten>, <dead_earth:defense_wall_v2>, <hbm:ingot_tungsten>], [<hbm:plate_titanium>, <hbm:ingot_tungsten>, <hbm:plate_titanium>]]);

recipes.remove(<dead_earth:defense_door_v1>);
recipes.addShapeless("dead_earth_defense_door_v1", <dead_earth:defense_door_v1>, [<minecraft:iron_door>, <hbm:plate_steel>, <hbm:screwdriver>.anyDamage().reuse()]);

recipes.remove(<dead_earth:defense_door_v2>);
recipes.addShaped("dead_earth_defense_door_v2", <dead_earth:defense_door_v2>, [[null, <hbm:plate_steel>, null], [<hbm:ingot_titanium>, <dead_earth:defense_door_v1>, <hbm:ingot_titanium>], [<hbm:plate_steel>, <hbm:plate_steel>, <hbm:plate_steel>]]);

recipes.remove(<dead_earth:defense_door_v3>);
recipes.addShaped("dead_earth_defense_door_v3", <dead_earth:defense_door_v3>, [[<hbm:plate_titanium>, <hbm:ingot_tungsten>, <hbm:plate_titanium>], [<hbm:ingot_tungsten>, <dead_earth:defense_door_v2>, <hbm:ingot_tungsten>], [<hbm:plate_titanium>, <hbm:ingot_tungsten>, <hbm:plate_titanium>]]);

recipes.remove(<dead_earth:defense_hatch_v1>);
recipes.addShapeless("dead_earth_defense_hatch_v1", <dead_earth:defense_hatch_v1>, [<minecraft:iron_trapdoor>, <hbm:plate_steel>, <hbm:screwdriver>.anyDamage().reuse()]);

recipes.remove(<dead_earth:defense_hatch_v2>);
recipes.addShaped("dead_earth_defense_hatch_v2", <dead_earth:defense_hatch_v2>, [[null, <hbm:plate_steel>, null], [<hbm:ingot_titanium>, <dead_earth:defense_hatch_v1>, <hbm:ingot_titanium>], [<hbm:plate_steel>, <hbm:plate_steel>, <hbm:plate_steel>]]);

recipes.remove(<dead_earth:defense_hatch_v3>);
recipes.addShaped("dead_earth_defense_hatch_v3", <dead_earth:defense_hatch_v3>, [[<hbm:plate_titanium>, <hbm:ingot_tungsten>, <hbm:plate_titanium>], [<hbm:ingot_tungsten>, <dead_earth:defense_hatch_v2>, <hbm:ingot_tungsten>], [<hbm:plate_titanium>, <hbm:ingot_tungsten>, <hbm:plate_titanium>]]);
