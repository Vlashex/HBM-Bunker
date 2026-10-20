// Compact early tree production; native Bonsai content and loot stay unchanged.
// Two HBM iron plates + five clay balls; no steel, circuits, power or diamonds.
recipes.remove(<bonsaitrees:bonsaipot:0>);
recipes.remove(<bonsaitrees:bonsaipot:1>);

recipes.addShaped("dead_earth_bonsai_pot", <bonsaitrees:bonsaipot:0>, [
    [<hbm:plate_iron>, null, <hbm:plate_iron>],
    [<minecraft:clay_ball>, null, <minecraft:clay_ball>],
    [<minecraft:clay_ball>, <minecraft:clay_ball>, <minecraft:clay_ball>]
]);
recipes.addShapeless("dead_earth_bonsai_hopping", <bonsaitrees:bonsaipot:1>, [
    <bonsaitrees:bonsaipot:0>, <minecraft:hopper>
]);
