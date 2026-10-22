"""Derive substrate EMC from the pinned native sieve plus our additive HBM rewards.

This is an offline build calculation, not a runtime simulation or an EMC recipe mapper.
Ore pieces are valued through their explicit four-pieces -> one-smelted-ingot path.
"""
import json
from decimal import Decimal, ROUND_FLOOR, ROUND_CEILING
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
RAW = re.compile(r'[a-z0-9_]+:[a-z0-9_./]+\|[0-9]+\Z')


def key(token):
    parts = token.split(':')
    if len(parts) not in (2, 3):
        raise ValueError('Invalid sieve pricing item')
    result = ':'.join(parts[:2]) + '|' + (parts[2] if len(parts) == 3 else '0')
    if not RAW.fullmatch(result):
        raise ValueError('Invalid sieve pricing item')
    return result


def load(root=ROOT):
    spec = json.loads((root / 'legacy/progression/sieve-pricing.json').read_text(encoding='utf-8'))
    if (spec.get('schema') != 1 or spec.get('fortuneLevel') != 0
            or spec.get('flattenSieveRecipes') is not False
            or type(spec.get('targetReturnPercent')) not in (int, float)
            or not 1 <= spec['targetReturnPercent'] <= 50):
        raise ValueError('Unsupported sieve pricing policy')
    pins = json.loads((root / 'legacy/metadata/full.mods.lock.json').read_text(encoding='utf-8'))
    pin = next(row for row in pins if row['mod_id'] == spec['nativeSource']['modId'])
    if pin['sha256'] != spec['nativeSource']['sha256']:
        raise ValueError('Re-audit native sieve drops for the new pinned Ex Nihilo jar')
    server_pins = {row['mod_id']: row['sha256'] for row in pins if row['side'] != 'client'}
    if server_pins != spec['nativeSource']['serverModPins']:
        raise ValueError('Re-audit optional native sieve recipes for the changed server mod set')
    return spec


def calculate(spec, raw, custom_drops):
    inputs = spec['inputs']
    if len(set(inputs)) != len(inputs) or not all(RAW.fullmatch(x) for x in inputs):
        raise ValueError('Invalid sieve pricing inputs')
    totals = {token: {mesh: Decimal(0) for mesh in range(1, 5)} for token in inputs}
    breakdown = {token: [] for token in inputs}
    for origin, drops in [('native', spec['nativeDrops']), ('HBM provider', custom_drops)]:
        for row in drops:
            token = key(row['input'])
            if token not in totals:
                continue
            output, mesh, chance = key(row['output']), row['mesh'], Decimal(str(row['chance']))
            if type(mesh) is not int or mesh not in range(1, 5) or not 0 < chance <= 1:
                raise ValueError('Invalid sieve pricing probability/mesh')
            # Never read a substrate's own derived price: there is no circular EMC propagation.
            if output in inputs:
                raise ValueError('Circular sieve substrate reward')
            if output in raw:
                value, route = Decimal(raw[output]), 'raw EMC'
            elif output in spec['conversions']:
                conversion = spec['conversions'][output]
                value = (Decimal(raw[conversion['output']]) * conversion['outputCount']
                         / conversion['inputCount'])
                route = '4 pieces -> chunk -> furnace ingot'
            elif output in spec['unpricedRewards']:
                value, route = Decimal(0), spec['unpricedRewards'][output]
            else:
                raise ValueError('Unvalued sieve reward: ' + output)
            expected = chance * value
            totals[token][mesh] += expected
            breakdown[token].append({'source': origin, 'output': output, 'mesh': mesh,
                                     'chance': str(chance), 'valuePerItem': str(value),
                                     'expectedEmc': str(expected), 'route': route})
    materials = {}
    target = Decimal(str(spec['targetReturnPercent'])) / 100
    divisor = 1 + target
    for token, meshes in totals.items():
        best = max(meshes, key=meshes.get)
        ideal = meshes[best] / divisor
        candidates = {int(ideal.to_integral_value(rounding=rounding))
                      for rounding in (ROUND_FLOOR, ROUND_CEILING)}
        candidates = {p for p in candidates if 0 < p < meshes[best]}
        if not candidates:
            raise ValueError('Sieve yield cannot support a positive substrate price')
        price = min(candidates, key=lambda p: (abs(meshes[best] / p - 1 - target), -p))
        materials[token] = {'bestMesh': best, 'emc': price,
                            'expectedByMesh': {str(m): format(v, '.4f') for m, v in meshes.items()},
                            'grossReturnPercent': format((meshes[best] / price - 1) * 100, '.3f'),
                            'breakdown': breakdown[token]}
    return {'schema': 1, 'fortuneLevel': 0, 'targetReturnPercent': spec['targetReturnPercent'],
            'materials': materials}


def report(root, raw):
    custom = json.loads((root / 'legacy/progression/sieve.json').read_text(encoding='utf-8'))['drops']
    return calculate(load(root), raw, custom)


def prices(root, raw):
    return {token: row['emc'] for token, row in report(root, raw)['materials'].items()}
