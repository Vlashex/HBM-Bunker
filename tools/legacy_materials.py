"""One catalog for exact raw-material forms, source recipes and safe legacy aliases.

No registry deletion, block replacement, item guessing or ingot-to-plate recipes.
"""
import argparse
import json
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
TOKEN = re.compile(r'[a-z0-9_]+:[a-z0-9_./]+:[0-9]+\Z')
FORMS = {'ingot', 'dust', 'nugget', 'plate'}


def load(root=ROOT):
    document = json.loads((root / 'legacy/materials/catalog.json').read_text(encoding='utf-8'))
    if set(document) != {'schema', 'policy', 'materials'} or type(document['schema']) is not int or document['schema'] != 1:
        raise ValueError('Unsupported material catalog')
    policy = document['policy']
    if set(policy) != {'convertTaggedStacks', 'convertCapabilityStacks', 'inventoryIntervalTicks', 'inventorySlotsPerPass', 'forms', 'retainRegistryEntries', 'excludedEquivalences'}:
        raise ValueError('Unknown material policy field')
    if (policy.get('convertTaggedStacks') is not False
            or policy.get('convertCapabilityStacks') is not False
            or policy.get('retainRegistryEntries') is not True
            or len(policy.get('forms', [])) != 4 or set(policy.get('forms', [])) != FORMS
            or type(policy.get('inventoryIntervalTicks')) is not int
            or not 20 <= policy.get('inventoryIntervalTicks', 0) <= 200
            or type(policy.get('inventorySlotsPerPass')) is not int
            or not 1 <= policy.get('inventorySlotsPerPass', 0) <= 16):
        raise ValueError('Unsafe material migration policy')
    names, groups, all_aliases, canonicals, ore_groups = set(), [], set(), set(), set()
    if not 1 <= len(document['materials']) <= 64:
        raise ValueError('Material catalog bound')
    for material in document['materials']:
        if set(material) - {'material', 'forms', 'crop'}:
            raise ValueError('Unknown material field')
        name = material['material']
        if not re.fullmatch('[a-z_]+', name) or name in names or name in {'silicon', 'certus_quartz', 'fuel'}:
            raise ValueError('Unsafe/duplicate material')
        names.add(name)
        if not 1 <= len(material['forms']) <= 4:
            raise ValueError('Material form count bound')
        used_forms = set()
        for group in material['forms']:
            if set(group) - {'form', 'canonical', 'oreNames', 'legacyAliases', 'value', 'allowEmc'}:
                raise ValueError('Unknown material form field')
            form, token = group['form'], group['canonical']
            if form not in FORMS or form in used_forms or not TOKEN.fullmatch(token):
                raise ValueError('Unsafe/duplicate material form')
            if not token.startswith('hbm:') and token not in {
                    'minecraft:iron_ingot:0', 'minecraft:gold_ingot:0',
                    'minecraft:iron_nugget:0', 'minecraft:gold_nugget:0'}:
                raise ValueError('Canonical metallurgy must be HBM (vanilla iron/gold retained)')
            if int(token.rsplit(':', 1)[1]) >= 32767:
                raise ValueError('Wildcard metadata is not a material form')
            if token in canonicals:
                raise ValueError('Duplicate canonical item')
            canonicals.add(token); used_forms.add(form)
            ores, aliases = group['oreNames'], group['legacyAliases']
            if not 1 <= len(ores) <= 4 or len(set(ores)) != len(ores):
                raise ValueError('Material ore-name bound')
            if any(not re.fullmatch(form + '[A-Z][A-Za-z0-9]*', ore) or ore in ore_groups for ore in ores):
                raise ValueError('OreDictionary name changes form or belongs to two materials')
            ore_groups.update(ores)
            for alias in aliases:
                if not TOKEN.fullmatch(alias) or alias in all_aliases or alias.startswith(('hbm:', 'minecraft:')):
                    raise ValueError('Unsafe/duplicate legacy alias')
                if int(alias.rsplit(':', 1)[1]) >= 32767:
                    raise ValueError('Wildcard metadata is not a material alias')
                if alias.startswith('exnihilocreatio:'):
                    expected = {'ingot': '3', 'dust': '2'}.get(form)
                    if expected is None or alias.rsplit(':', 1)[1] != expected:
                        raise ValueError('Ore pieces/chunks are not equal-form aliases')
                all_aliases.add(alias)
            if 'value' in group and (type(group['value']) is not int or not 1 <= group['value'] <= 1000000000):
                raise ValueError('Invalid material value')
            if type(group.get('allowEmc')) is not bool:
                raise ValueError('Material allowEmc must be a boolean')
            if group['allowEmc'] and (form not in {'ingot', 'dust'} or name in {'steel', 'uranium', 'thorium'} or 'value' not in group):
                raise ValueError('Manufactured material/nuclear fuel leaked into raw EMC')
            groups.append(dict(group, material=name))
        if 'crop' in material:
            crop = material['crop']
            if set(crop) != {'essence', 'output', 'count', 'pattern', 'nativeOreName', 'seedEnable'}:
                raise ValueError('Unknown crop output field')
            if (type(crop['seedEnable']) is not int or crop['seedEnable'] != 2
                    or name == 'steel' or crop['output'] not in {g['canonical'] for g in material['forms']}
                    or not TOKEN.fullmatch(crop['output'])
                    or not re.fullmatch(r'mysticalagriculture:[a-z_]+_essence', crop['essence'])
                    or type(crop['count']) is not int or not 1 <= crop['count'] <= 64
                    or crop['pattern'] not in (['EEE', 'E E', 'EEE'], ['EEE', '   ', '   '])
                    or not re.fullmatch(r'(ingot|dust)[A-Z][A-Za-z0-9]*', crop['nativeOreName'])):
                raise ValueError('Unsafe explicit crop output')
    if all_aliases & canonicals:
        raise ValueError('Canonical item is also a legacy alias')
    return document, groups


def raw_values(root=ROOT):
    return {g['canonical'].rsplit(':', 1)[0] + '|' + g['canonical'].rsplit(':', 1)[1]: g['value']
            for g in load(root)[1] if g['allowEmc']}


def zone_values(root=ROOT):
    result = {}
    for group in load(root)[1]:
        if 'value' not in group:
            continue
        for token in [group['canonical'], *group['legacyAliases']]:
            result[token] = group['value']
            if token.endswith(':0'):
                result[token[:-2]] = group['value']
    return result


def products(root=ROOT):
    document, groups = load(root)
    lines = ['// Generated from legacy/materials/catalog.json. Exact material + exact form only.',
             '// Keep all old registry entries and ore piece/chunk processing. No steel/plate shortcut.', '']
    for material in document['materials']:
        if 'crop' not in material:
            continue
        crop = material['crop']
        # Cucumber RecipeHelper uses this exact ore-name as the native recipe ID.
        # Remove only that Mystical Agriculture recipe, never HBM recipes by output.
        pattern = '(?i)mysticalagriculture:' + crop['nativeOreName'].lower() + '(?:_[0-9]+)?'
        lines.append('recipes.removeByRegex("' + pattern + '");')
        grid = ['[' + ', '.join('null' if c == ' ' else '<' + crop['essence'] + '>' for c in row) + ']'
                for row in crop['pattern']]
        lines.append('recipes.addShaped("dead_earth_material_%s", <%s> * %s, [%s]);' % (
            material['material'], crop['output'], crop['count'], ', '.join(grid)))
        lines.append('')
    # Only vanilla iron/gold ExN ore items are guaranteed before HBM ore registration.
    # Other optional aliases stay runtime-only; no missing bracket references.
    guaranteed_exn = {'iron', 'gold'}
    for group in groups:
        if group['material'] in guaranteed_exn:
            for alias in group['legacyAliases']:
                lines.append('mods.jei.JEI.hide(<%s>);' % alias)
    lines += ['', '// Published HBM metallurgy remains authoritative; aliases convert only in the same form.', '']
    native_seeds = ['# Generated from legacy/materials/catalog.json.',
                    '# Native MA ModChecker does not recognize HBM before ore registration.',
                    '# Force-enable only these real pinned CropType resources (2 = native force-enable).',
                    'seeds {']
    for material in document['materials']:
        if 'crop' in material:
            crop = material['crop']
            name = crop['essence'].split(':', 1)[1].removesuffix('_essence')
            native_seeds.append('    I:' + name + '_seeds=' + str(crop['seedEnable']))
    native_seeds += ['}', '']
    return {'legacy/pack/config/mysticalagriculture.cfg': '\n'.join(native_seeds).encode(),
            'legacy/pack/config/dead-earth/materials.json': (json.dumps(document, indent=2) + '\n').encode(),
            'legacy/pack/scripts/dead_earth_materials.zs': '\n'.join(lines).encode()}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--write', action='store_true')
    args = parser.parse_args()
    for name, content in products().items():
        path = ROOT / name
        if args.write:
            path.parent.mkdir(parents=True, exist_ok=True); path.write_bytes(content)
        elif path.read_bytes() != content:
            raise SystemExit('Stale material artifact: ' + name)
    print('Material catalog artifacts generated.' if args.write else 'Material artifacts match catalog.')


if __name__ == '__main__':
    main()
