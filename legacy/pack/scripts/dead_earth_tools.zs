// Own native HBM tools. Upgrade before applying expensive enchanted books.
recipes.remove(<dead_earth:hammer_v1>);
recipes.addShaped("dead_earth_hammer_v1", <dead_earth:hammer_v1>, [[<hbm:plate_iron>, <hbm:plate_iron>, <hbm:plate_iron>], [null, <minecraft:stick>, null], [null, <minecraft:stick>, null]]);

recipes.remove(<dead_earth:hammer_v2>);
recipes.addShaped("dead_earth_hammer_v2", <dead_earth:hammer_v2>, [[<hbm:plate_titanium>, <hbm:plate_titanium>, <hbm:plate_titanium>], [<hbm:plate_titanium>, <dead_earth:hammer_v1>, <hbm:plate_titanium>], [null, <minecraft:stick>, null]]);

recipes.remove(<dead_earth:hammer_v3>);
recipes.addShaped("dead_earth_hammer_v3", <dead_earth:hammer_v3>, [[<hbm:ingot_tungsten>, <hbm:ingot_tungsten>, <hbm:ingot_tungsten>], [<hbm:ingot_tungsten>, <dead_earth:hammer_v2>, <hbm:ingot_tungsten>], [null, <hbm:plate_titanium>, null]]);

recipes.remove(<dead_earth:mining_gadget_v1>);
recipes.addShaped("dead_earth_mining_gadget_v1", <dead_earth:mining_gadget_v1>, [[<hbm:plate_steel>, <hbm:circuit:8>, <hbm:plate_steel>], [<hbm:battery_generic>, <dead_earth:hammer_v1>, <hbm:battery_generic>], [<hbm:plate_steel>, <hbm:plate_steel>, <hbm:plate_steel>]]);

recipes.remove(<dead_earth:mining_gadget_v2>);
recipes.addShaped("dead_earth_mining_gadget_v2", <dead_earth:mining_gadget_v2>, [[<hbm:plate_titanium>, <hbm:circuit:9>, <hbm:plate_titanium>], [<hbm:plate_titanium>, <dead_earth:mining_gadget_v1>, <hbm:plate_titanium>], [<hbm:plate_titanium>, <hbm:battery_advanced>, <hbm:plate_titanium>]]);

recipes.remove(<dead_earth:mining_gadget_v3>);
recipes.addShaped("dead_earth_mining_gadget_v3", <dead_earth:mining_gadget_v3>, [[<hbm:ingot_tungsten>, <hbm:circuit:9>, <hbm:ingot_tungsten>], [<hbm:ingot_tungsten>, <dead_earth:mining_gadget_v2>, <hbm:ingot_tungsten>], [<hbm:plate_titanium>, <hbm:battery_advanced>, <hbm:plate_titanium>]]);
