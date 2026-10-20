"""Authoritative HBM-only finite defense recipes for the isolated Forge 1.12.2 fork."""
import argparse
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SOURCE = Path('legacy/defense/recipes.json')
TARGET = Path('legacy/pack/scripts/dead_earth_defense.zs')
NATIVE = {'hbm:screwdriver', 'hbm:plate_steel', 'hbm:plate_titanium', 'hbm:ingot_titanium', 'hbm:ingot_tungsten'}


def script(root=ROOT):
    data = json.loads((root / SOURCE).read_text(encoding='utf-8'))
    if data.get('schema') != 1 or len(data['recipes']) != 9:
        raise ValueError('Expected nine defense recipes')
    lines = ['// Generated from legacy/defense/recipes.json by tools/legacy_defense_recipes.py.',
             '// V1 fits the 2x2 inventory grid; HBM screwdriver is returned unchanged.',
             '// Native HBM IDs verified against NTM-CE-1.12.2-2.6.1.0 ModItems bytecode.', '']
    seen = set()
    for recipe in data['recipes']:
        name = recipe['name']
        if name not in {k + '_v' + str(t) for k in ('wall', 'door', 'hatch') for t in (1, 2, 3)} or name in seen:
            raise ValueError('Unexpected/duplicate defense recipe')
        seen.add(name)
        output = 'dead_earth:defense_' + name
        if recipe['output'] != output:
            raise ValueError('Defense recipe output mismatch')
        lines.append('recipes.remove(<%s>);' % output)
        if name.endswith('_v1'):
            ingredients = recipe['shapeless']
            base = 'minecraft:' + {'wall': 'iron_block', 'door': 'iron_door', 'hatch': 'iron_trapdoor'}[name[:-3]]
            if ingredients != [base, 'hbm:plate_steel', 'hbm:screwdriver'] or recipe['reusable'] != 'hbm:screwdriver':
                raise ValueError('V1 must retain its native HBM tool and metal cost')
            text = ', '.join('<%s>' % i + ('.anyDamage().reuse()' if i == recipe['reusable'] else '') for i in ingredients)
            lines.append('recipes.addShapeless("dead_earth_defense_%s", <%s>, [%s]);' % (name, output, text))
        else:
            pattern, key = recipe['pattern'], recipe['key']
            lower = 'dead_earth:defense_' + name[:-1] + str(int(name[-1]) - 1)
            expected = {'S': 'hbm:plate_steel', 'T': 'hbm:ingot_titanium', 'B': lower} if name.endswith('_v2') else {'T': 'hbm:plate_titanium', 'W': 'hbm:ingot_tungsten', 'B': lower}
            expected_pattern = [' S ', 'TBT', 'SSS'] if name.endswith('_v2') else ['TWT', 'WBW', 'TWT']
            if key != expected or pattern != expected_pattern:
                raise ValueError('Defense upgrades must consume the previous tier and HBM metallurgy')
            grid = ['[' + ', '.join('null' if c == ' ' else '<%s>' % key[c] for c in row) + ']' for row in pattern]
            lines.append('recipes.addShaped("dead_earth_defense_%s", <%s>, [%s]);' % (name, output, ', '.join(grid)))
        lines.append('')
    return '\n'.join(lines).encode('utf-8')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--write', action='store_true')
    args = parser.parse_args()
    rendered = script()
    if args.write:
        (ROOT / TARGET).parent.mkdir(parents=True, exist_ok=True)
        (ROOT / TARGET).write_bytes(rendered)
        print('Generated ' + TARGET.as_posix())
    elif (ROOT / TARGET).read_bytes() != rendered:
        raise SystemExit('Defense recipes are stale; run --write')
    else:
        print('Defense recipe source matches packaged CraftTweaker script.')


if __name__ == '__main__':
    main()
