"""Offline audit of every DEAD EARTH 1.12.2 block, inventory model and texture dependency.

This resolves inherited texture variables against the pinned mod/vanilla assets. It is
not a replacement for the native client bake probe in OwnAssetsProbe.java.
"""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import re
import struct
import zipfile
import zlib

ROOT = Path(__file__).resolve().parents[1]
RESOURCES = Path('legacy/coordinator/src/main/resources')
MANIFEST = 'dead_earth/asset_manifest.json'
ID = re.compile(r'[a-z0-9_]+:[a-z0-9_./]+\Z')


def strict_json(data: bytes | str):
    def pairs(rows):
        out = {}
        for key, value in rows:
            if key in out:
                raise ValueError('Duplicate JSON field: ' + key)
            out[key] = value
        return out
    return json.loads(data, object_pairs_hook=pairs)


def resource(reference: str, kind: str) -> str:
    if not isinstance(reference, str) or not reference or reference.startswith('#'):
        raise ValueError('Expected concrete resource: ' + str(reference))
    reference = reference if ':' in reference else 'minecraft:' + reference
    if not ID.fullmatch(reference) or '..' in reference.split(':', 1)[1].split('/'):
        raise ValueError('Unsafe resource: ' + reference)
    domain, name = reference.split(':', 1)
    if kind == 'texture':
        return domain + '/textures/' + name + '.png'
    if kind == 'blockstate_model' and not name.startswith('block/'):
        name = 'block/' + name
    return domain + '/models/' + name + '.json'


class AssetIndex:
    """Resources keyed relative to assets/, with own resources overriding dependencies."""
    def __init__(self, entries=None):
        self.entries = dict(entries or {})
        self._cache = {}

    def read(self, key: str) -> bytes:
        if key not in self.entries:
            raise ValueError('Missing asset: ' + key)
        if key in self._cache:
            return self._cache[key]
        value = self.entries[key]
        if isinstance(value, bytes):
            data = value
        elif isinstance(value, Path):
            data = value.read_bytes()
        else:
            jar, entry = value
            with zipfile.ZipFile(jar) as archive:
                data = archive.read(entry)
        self._cache[key] = data
        return data

    def add_jar(self, path: Path):
        with zipfile.ZipFile(path) as archive:
            for entry in archive.namelist():
                if entry.startswith('assets/') and not entry.endswith('/'):
                    self.entries[entry[len('assets/'):]] = (path, entry)

    @classmethod
    def repository(cls, root: Path, vanilla: Path | None = None):
        result = cls()
        vanilla = vanilla or root / '.cache/legacy-client/client-1.12.2.jar'
        if not vanilla.is_file():
            raise ValueError('Vanilla 1.12.2 assets unavailable; pass --vanilla-client')
        result.add_jar(vanilla)
        pins = strict_json((root / 'legacy/metadata/full.mods.lock.json').read_bytes())
        for pin in pins:
            path = root / '.cache/legacy' / pin['filename']
            if not path.is_file() or path.stat().st_size != pin['size']:
                raise ValueError('Pinned mod assets unavailable: ' + pin['filename'])
            if hashlib.sha256(path.read_bytes()).hexdigest() != pin['sha256']:
                raise ValueError('Pinned mod assets changed: ' + pin['filename'])
            result.add_jar(path)
        assets = root / RESOURCES / 'assets'
        for path in assets.rglob('*'):
            if path.is_file():
                result.entries[path.relative_to(assets).as_posix()] = path
        return result


class ModelGraph:
    def __init__(self, assets: AssetIndex):
        self.assets = assets
        self.cache = {}
        self.models = set()
        self.textures = set()

    def resolve(self, key: str, visiting=()):
        if key in visiting:
            raise ValueError('Model parent cycle: ' + ' -> '.join((*visiting, key)))
        if key in self.cache:
            return self.cache[key]
        data = strict_json(self.assets.read(key))
        if not isinstance(data, dict):
            raise ValueError('Model is not an object: ' + key)
        self.models.add(key)
        parent = data.get('parent')
        if parent and not parent.startswith('builtin/') and not parent.startswith('minecraft:builtin/'):
            base = self.resolve(resource(parent, 'parent'), (*visiting, key))
        else:
            base = {'textures': {}, 'elements': [], 'generated': bool(parent and parent.endswith('generated'))}
        merged = dict(base)
        merged['textures'] = {**base.get('textures', {}), **data.get('textures', {})}
        if 'elements' in data:
            merged['elements'] = data['elements']
        self.cache[key] = merged
        return merged

    def texture(self, value, variables, visiting=()):
        if not isinstance(value, str):
            raise ValueError('Invalid texture resource')
        if value.startswith('#'):
            name = value[1:]
            if name in visiting:
                raise ValueError('Texture variable cycle: ' + ' -> '.join((*visiting, name)))
            if name not in variables:
                raise ValueError('Unbound texture variable: ' + value)
            return self.texture(variables[name], variables, (*visiting, name))
        key = resource(value, 'texture')
        self.assets.read(key)
        self.textures.add(key)
        return key

    def concrete(self, key: str):
        data = self.resolve(key)
        textures = data['textures']
        count = 0
        for element in data.get('elements', []):
            for face in element.get('faces', {}).values():
                self.texture(face['texture'], textures)
                count += 1
        if data.get('generated'):
            for name, value in textures.items():
                if re.fullmatch(r'layer[0-9]+', name):
                    self.texture(value, textures)
                    count += 1
        if count == 0:
            raise ValueError('Concrete model has no visible faces: ' + key)
        if 'particle' in textures:
            self.texture(textures['particle'], textures)
        return count


def png_info(data: bytes):
    if not data.startswith(b'\x89PNG\r\n\x1a\n'):
        raise ValueError('Invalid PNG signature')
    offset = 8
    dimensions = None
    compressed = []
    ended = False
    while offset < len(data):
        if offset + 12 > len(data):
            raise ValueError('Truncated PNG chunk')
        length = struct.unpack_from('>I', data, offset)[0]
        kind = data[offset + 4:offset + 8]
        chunk = data[offset + 8:offset + 8 + length]
        if offset + 12 + length > len(data):
            raise ValueError('Truncated PNG data')
        checksum = struct.unpack_from('>I', data, offset + 8 + length)[0]
        if zlib.crc32(kind + chunk) & 0xffffffff != checksum:
            raise ValueError('Invalid PNG CRC')
        if kind == b'IHDR':
            if dimensions is not None or length != 13:
                raise ValueError('Invalid PNG IHDR')
            width, height = struct.unpack_from('>II', chunk)
            if not 0 < width <= 2048 or not 0 < height <= 2048:
                raise ValueError('Invalid PNG dimensions')
            dimensions = [width, height]
        elif kind == b'IDAT':
            compressed.append(chunk)
        elif kind == b'IEND':
            ended = True
            offset += 12 + length
            break
        offset += 12 + length
    if not ended or dimensions is None or offset != len(data) or not compressed:
        raise ValueError('Incomplete PNG')
    if not zlib.decompress(b''.join(compressed)):
        raise ValueError('Empty PNG pixels')
    return dimensions


def read_lang(data: bytes):
    out = {}
    for line in data.decode('utf-8').splitlines():
        line = line.strip()
        if not line or line.startswith('#'):
            continue
        if '=' not in line:
            raise ValueError('Invalid language line: ' + line)
        key, value = line.split('=', 1)
        if key in out:
            raise ValueError('Duplicate translation: ' + key)
        if not value.strip():
            raise ValueError('Empty translation: ' + key)
        out[key] = value
    return out


def validate(assets: AssetIndex):
    contract = strict_json(assets.read(MANIFEST))
    if contract.get('schema') != 1 or contract.get('namespace') != 'dead_earth':
        raise ValueError('Invalid own asset manifest')
    groups = {}
    for name in ('blocks', 'items', 'environmentalBlocks'):
        rows = contract[name]
        if rows != sorted(set(rows)) or any(not ID.fullmatch(row) or not row.startswith('dead_earth:') for row in rows):
            raise ValueError('Invalid registry contract: ' + name)
        groups[name] = set(rows)
    environmental = groups['environmentalBlocks']
    if environmental != groups['blocks'] - groups['items'] or environmental & groups['items']:
        raise ValueError('Environmental inventory contract mismatch')
    dynamic = contract['nativeDynamicItems']
    if not set(dynamic) <= groups['items'] - groups['blocks']:
        raise ValueError('Dynamic model contract mismatch')
    specialized = set(contract.get('specializedBlockModels', []))
    if not specialized <= groups['blocks'] & groups['items']:
        raise ValueError('Specialized model contract mismatch')
    graph = ModelGraph(assets)
    concrete = set()
    errors = []
    def check(key):
        try:
            graph.concrete(key)
            concrete.add(key)
        except (ValueError, KeyError, TypeError, json.JSONDecodeError) as error:
            errors.append(key + ': ' + str(error))
    def models(node):
        if isinstance(node, dict):
            for key, value in node.items():
                if key == 'model':
                    yield value
                else:
                    yield from models(value)
        elif isinstance(node, list):
            for value in node:
                yield from models(value)
    for identifier in contract['blocks']:
        key = identifier.replace(':', '/blockstates/') + '.json'
        try:
            variants = strict_json(assets.read(key))
            targets = list(models(variants))
            if not targets:
                raise ValueError('No blockstate models')
            for target in targets:
                check(resource(target, 'blockstate_model'))
        except (ValueError, KeyError, TypeError, json.JSONDecodeError) as error:
            errors.append(key + ': ' + str(error))
    for identifier in specialized:
        key = identifier.replace(':', '/models/block/') + '.json'
        try:
            data = strict_json(assets.read(key))
            if len(data.get('elements', [])) <= 1:
                raise ValueError('Specialized machine model has reverted to a placeholder cube')
            if any('redstone_block' in value or 'furnace_front' in value or 'dispenser_front' in value for value in data.get('textures', {}).values()):
                raise ValueError('Specialized machine uses a generic block placeholder skin')
        except (ValueError, KeyError, TypeError) as error:
            errors.append(key + ': ' + str(error))
    for identifier in contract['items']:
        if identifier in dynamic:
            row = dynamic[identifier]
            if row.get('provider') != 'com.hbm.items.tool.ItemToolAbility':
                errors.append(identifier + ': unknown native model provider')
            try:
                graph.texture(row['sprite'], {})
            except ValueError as error:
                errors.append(identifier + ': ' + str(error))
        else:
            check(identifier.replace(':', '/models/item/') + '.json')
    for locale in ('en_us', 'ru_ru'):
        try:
            lang = read_lang(assets.read('dead_earth/lang/' + locale + '.lang'))
            for identifier in groups['blocks'] | groups['items']:
                prefix = 'tile.' if identifier in groups['blocks'] else 'item.'
                key = prefix + identifier.replace(':', '.') + '.name'
                label = lang.get(key)
                if not label or label.startswith(('tile.', 'item.')) or label.endswith('.name'):
                    errors.append(locale + ': untranslated registry name ' + key)
                elif locale == 'ru_ru' and not re.search('[А-Яа-яЁё]', label):
                    errors.append(locale + ': non-Russian registry name ' + key)
        except ValueError as error:
            errors.append(locale + ': ' + str(error))
    png_count = 0
    for key, source in assets.entries.items():
        if not key.endswith('.png') or not isinstance(source, Path):
            continue
        try:
            png_info(assets.read(key))
            png_count += 1
        except (ValueError, zlib.error) as error:
            errors.append(key + ': ' + str(error))
    return {'schema': 1, 'passed': not errors, 'scope': 'offline_asset_dependency_graph_and_localization',
            'registeredBlocks': len(groups['blocks']), 'registeredItems': len(groups['items']),
            'environmentalBlocksWithoutItems': len(environmental), 'nativeDynamicItems': len(dynamic), 'specializedBlockModels': sorted(specialized),
            'concreteModels': len(concrete), 'resolvedModels': len(graph.models),
            'referencedTextures': len(graph.textures), 'validatedOwnPngFiles': png_count,
            'runtimeBakeVerified': False, 'errors': sorted(set(errors))}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=Path, default=ROOT)
    parser.add_argument('--vanilla-client', type=Path)
    parser.add_argument('--output', type=Path)
    args = parser.parse_args()
    report = validate(AssetIndex.repository(args.root.resolve(), args.vanilla_client))
    rendered = json.dumps(report, indent=2, ensure_ascii=False) + '\n'
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(rendered, encoding='utf-8')
    print(rendered, end='')
    return 0 if report['passed'] else 1


if __name__ == '__main__':
    raise SystemExit(main())
