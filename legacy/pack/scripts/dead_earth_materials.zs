// Generated from legacy/materials/catalog.json. Exact material + exact form only.
// Keep all old registry entries and ore piece/chunk processing. No steel/plate shortcut.

recipes.removeByRegex("(?i)mysticalagriculture:ingotcopper(?:_[0-9]+)?");
recipes.addShaped("dead_earth_material_copper", <hbm:ingot_copper:0> * 6, [[<mysticalagriculture:copper_essence>, <mysticalagriculture:copper_essence>, <mysticalagriculture:copper_essence>], [<mysticalagriculture:copper_essence>, null, <mysticalagriculture:copper_essence>], [<mysticalagriculture:copper_essence>, <mysticalagriculture:copper_essence>, <mysticalagriculture:copper_essence>]]);

recipes.removeByRegex("(?i)mysticalagriculture:ingotaluminum(?:_[0-9]+)?");
recipes.addShaped("dead_earth_material_aluminium", <hbm:ingot_aluminium:0> * 8, [[<mysticalagriculture:aluminum_essence>, <mysticalagriculture:aluminum_essence>, <mysticalagriculture:aluminum_essence>], [<mysticalagriculture:aluminum_essence>, null, <mysticalagriculture:aluminum_essence>], [<mysticalagriculture:aluminum_essence>, <mysticalagriculture:aluminum_essence>, <mysticalagriculture:aluminum_essence>]]);

recipes.removeByRegex("(?i)mysticalagriculture:ingotlead(?:_[0-9]+)?");
recipes.addShaped("dead_earth_material_lead", <hbm:ingot_lead:0> * 4, [[<mysticalagriculture:lead_essence>, <mysticalagriculture:lead_essence>, <mysticalagriculture:lead_essence>], [<mysticalagriculture:lead_essence>, null, <mysticalagriculture:lead_essence>], [<mysticalagriculture:lead_essence>, <mysticalagriculture:lead_essence>, <mysticalagriculture:lead_essence>]]);

recipes.removeByRegex("(?i)mysticalagriculture:ingottitanium(?:_[0-9]+)?");
recipes.addShaped("dead_earth_material_titanium", <hbm:ingot_titanium:0> * 2, [[<mysticalagriculture:titanium_essence>, <mysticalagriculture:titanium_essence>, <mysticalagriculture:titanium_essence>], [<mysticalagriculture:titanium_essence>, null, <mysticalagriculture:titanium_essence>], [<mysticalagriculture:titanium_essence>, <mysticalagriculture:titanium_essence>, <mysticalagriculture:titanium_essence>]]);

recipes.removeByRegex("(?i)mysticalagriculture:ingottungsten(?:_[0-9]+)?");
recipes.addShaped("dead_earth_material_tungsten", <hbm:ingot_tungsten:0> * 2, [[<mysticalagriculture:tungsten_essence>, <mysticalagriculture:tungsten_essence>, <mysticalagriculture:tungsten_essence>], [<mysticalagriculture:tungsten_essence>, null, <mysticalagriculture:tungsten_essence>], [<mysticalagriculture:tungsten_essence>, <mysticalagriculture:tungsten_essence>, <mysticalagriculture:tungsten_essence>]]);

recipes.removeByRegex("(?i)mysticalagriculture:dustsulfur(?:_[0-9]+)?");
recipes.addShaped("dead_earth_material_sulfur", <hbm:sulfur:0> * 8, [[<mysticalagriculture:sulfur_essence>, <mysticalagriculture:sulfur_essence>, <mysticalagriculture:sulfur_essence>], [null, null, null], [null, null, null]]);

recipes.removeByRegex("(?i)mysticalagriculture:dustsaltpeter(?:_[0-9]+)?");
recipes.addShaped("dead_earth_material_niter", <hbm:niter:0> * 5, [[<mysticalagriculture:saltpeter_essence>, <mysticalagriculture:saltpeter_essence>, <mysticalagriculture:saltpeter_essence>], [null, null, null], [null, null, null]]);

mods.jei.JEI.hide(<exnihilocreatio:item_ore_iron:3>);
mods.jei.JEI.hide(<exnihilocreatio:item_ore_iron:2>);
mods.jei.JEI.hide(<exnihilocreatio:item_ore_gold:3>);
mods.jei.JEI.hide(<exnihilocreatio:item_ore_gold:2>);

// Published HBM metallurgy remains authoritative; aliases convert only in the same form.
