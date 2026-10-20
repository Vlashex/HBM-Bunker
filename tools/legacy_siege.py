"""Expand canonical ESM overrides: native 13.169 has no per-property inheritance."""
import argparse
import json
import math
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SOURCE = Path('legacy/combat/esm-profiles.json')
TARGET = Path('legacy/pack/config/esm/entity_ai.json')
ID = re.compile(r'[a-z0-9_.-]+:[a-z0-9_/.-]+\Z')


def duplicate_free(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError('Duplicate siege field: ' + key)
        result[key] = value
    return result


def reject_constant(value):
    raise ValueError("Nonfinite siege value: " + value)


def generate(root=ROOT):
    source = root / SOURCE
    if source.is_symlink() or source.stat().st_size > 65536:
        raise ValueError('Unsafe or oversized siege profile source')
    doc = json.loads(source.read_text(encoding='utf-8'), object_pairs_hook=duplicate_free,
                     parse_constant=reject_constant)
    if set(doc) != {'schema', 'default', 'entities'} or type(doc['schema']) is not int or doc['schema'] != 1:
        raise ValueError('Siege profile source schema 1 required')
    defaults, entities = doc['default'], doc['entities']
    if not isinstance(defaults, dict) or not isinstance(entities, dict) or not 1 <= len(entities) <= 256:
        raise ValueError('Bounded siege defaults and entity profiles required')
    for key in ('Digging', 'Animal Retaliate', 'Attack Everything'):
        if defaults.get(key) is not False:
            raise ValueError('Native default must remain disabled: ' + key)
    rows = {}
    for entity, overrides in entities.items():
        if not ID.fullmatch(entity) or not isinstance(overrides, dict) or set(overrides) - set(defaults):
            raise ValueError('Invalid siege profile: ' + entity)
        row = {**defaults, **overrides}
        for key, value in row.items():
            expected = defaults[key]
            if isinstance(expected, bool):
                valid = type(value) is bool
            elif isinstance(expected, (int, float)):
                valid = type(value) in (int, float) and math.isfinite(value) and value >= 0
            elif isinstance(expected, list):
                valid = isinstance(value, list) and len(value) <= 256 and all(isinstance(item, str) for item in value)
            else:
                valid = isinstance(expected, str) and isinstance(value, str)
            if not valid:
                raise ValueError('Invalid native siege property: ' + entity + '/' + key)
        if row['Health'] <= 0 or row['Speed'] <= 0 or row['Xray Distance'] != 0:
            raise ValueError('Positive native attributes and zero bugged ESM xray required: ' + entity)
        if row['Animal Retaliate'] and (not row['Neutral Mob'] or row['Digging'] or row['Attack Everything']):
            raise ValueError('Animals must remain reactive, same-species defenders: ' + entity)
        rows[entity] = row
    # Every profile is complete: CfgProperty.get does not fall back to default
    # once an explicit native entity object exists.
    result = {'default': defaults, 'entities': rows}
    encoded = (json.dumps(result, ensure_ascii=False, indent=2) + '\n').encode('utf-8')
    if len(encoded) > 262144:
        raise ValueError('Expanded native siege profiles exceed runtime read limit')
    return encoded


def build(root=ROOT):
    target = root / TARGET
    target.write_bytes(generate(root))
    return target


def check(root=ROOT):
    if (root / TARGET).read_bytes() != generate(root):
        raise ValueError('Native ESM profiles are stale; run tools/legacy_siege.py build')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('mode', choices=('build', 'check'))
    args = parser.parse_args()
    if args.mode == 'build':
        target = build()
        print('Expanded native ESM profiles: ' + str(target))
    else:
        check()
        print('Native ESM profiles match the canonical source')


if __name__ == '__main__':
    main()
