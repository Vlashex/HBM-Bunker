"""Exact CraftTweaker recipe source for the compact native-crop tray."""
from pathlib import Path

def script():
    return '''// Compact automatic crop tray: HBM metallurgy remains the metal source.
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
'''

if __name__ == '__main__':
    root=Path(__file__).resolve().parents[1]
    target=root/'legacy/pack/scripts/dead_earth_farming.zs'
    target.parent.mkdir(parents=True,exist_ok=True)
    target.write_text(script(),encoding='utf-8')
    print('Wrote compact tray recipe; no tests or game were run.')
