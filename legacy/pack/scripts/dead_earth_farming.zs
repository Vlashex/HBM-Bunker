// Compact automatic crop tray: HBM metallurgy remains the metal source.
recipes.addShaped("dead_earth_crop_tray", <dead_earth:crop_tray>, [
    [<hbm:plate_iron>, null, <hbm:plate_iron>],
    [<minecraft:clay_ball>, <minecraft:flower_pot>, <minecraft:clay_ball>],
    [<ore:plankWood>, <minecraft:chest>, <ore:plankWood>]
]);
// Accessible growth boost. Native MA block, up to four beneath a Dead Earth tray.
// Keep HBM plates: neither diamonds nor Nether progression are needed for farming.
recipes.remove(<mysticalagriculture:growth_accelerator>);
recipes.addShaped("dead_earth_growth_accelerator", <mysticalagriculture:growth_accelerator>, [
    [<minecraft:stone>, <mysticalagriculture:crafting:0>, <minecraft:stone>],
    [<hbm:plate_iron>, <minecraft:redstone>, <hbm:plate_iron>],
    [<minecraft:stone>, <mysticalagriculture:crafting:0>, <minecraft:stone>]
]);
