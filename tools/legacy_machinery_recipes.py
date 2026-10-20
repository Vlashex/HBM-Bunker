"""Pinned HBM metallurgy recipes for the isolated compact FE/native Fortune machines."""
import argparse
import json
from pathlib import Path

ROOT=Path(__file__).resolve().parents[1]
SOURCE=Path('legacy/machinery/recipes.json')
TARGET=Path('legacy/pack/scripts/dead_earth_machinery.zs')
EXPECTED={
 'charger':(['SIS','RCR','SIS'],{'S':'hbm:plate_steel','I':'minecraft:iron_ingot','R':'minecraft:redstone','C':'hbm:circuit#8'}),
 'fortune_processor':(['SCS','DPD','SCS'],{'S':'hbm:plate_steel','C':'hbm:circuit#9','D':'minecraft:diamond','P':'minecraft:diamond_pickaxe'})}

def stack(item):
    name,sep,meta=item.partition('#')
    return '<'+name+(':'+meta if sep else '')+'>'

def script(root=ROOT):
    data=json.loads((root/SOURCE).read_text(encoding='utf-8'))
    if data.get('schema')!=1 or len(data.get('recipes',[]))!=2:
        raise ValueError('Expected exactly two machinery recipes')
    lines=['// Generated from legacy/machinery/recipes.json; restart after editing source.',
           '// HBM CE2.6.1.0: plate_steel, circuit:8 BASIC, circuit:9 ADVANCED.',
           '// Native Fortune is not a crusher: HBM ores retain their own original drops.','']
    seen=set()
    for recipe in data['recipes']:
        name=recipe['name']
        if name not in EXPECTED or name in seen or recipe['output']!='dead_earth:'+name:
            raise ValueError('Unexpected or duplicate machinery recipe')
        seen.add(name)
        pattern,key=EXPECTED[name]
        if recipe['pattern']!=pattern or recipe['key']!=key:
            raise ValueError('Machine recipe must retain exact HBM steel/circuit progression')
        lines.append('recipes.remove(<%s>);'%recipe['output'])
        grid=['['+', '.join('null' if c==' ' else stack(key[c]) for c in row)+']' for row in pattern]
        lines.append('recipes.addShaped("dead_earth_%s", <%s>, [%s]);'%(name,recipe['output'],', '.join(grid)))
        lines.append('')
    lines += [
      'mods.jei.JEI.addDescription(<dead_earth:charger>, ["FE input. Charges FE items and native HBM battery tools.", "Insert with right click; retrieve with an empty hand. Hopper/XNet compatible."]);',
      'mods.jei.JEI.addDescription(<dead_earth:fortune_processor>, ["Native ore drops with Fortune V; 4000 FE per cycle by default.", "Many HBM ores and vanilla iron/gold return themselves. No invented dusts; use HBM crushing for native metallurgy.", "OreDictionary ore* blocks only. Full output keeps ore and FE; pending drops survive reload."]);','']
    return '\n'.join(lines).encode('utf-8')

def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--write',action='store_true');args=parser.parse_args()
    data=script()
    if args.write:
        p=ROOT/TARGET;p.parent.mkdir(parents=True,exist_ok=True);p.write_bytes(data);print('Generated '+TARGET.as_posix())
    elif (ROOT/TARGET).read_bytes()!=data:
        raise SystemExit('Machinery script is stale; regenerate with --write')
    else:
        print('Machinery recipe source matches packaged script.')

if __name__=='__main__':main()
