"""Asset regression checks with deliberately broken inheritance, PNGs and registry contracts."""
import json
from pathlib import Path
import struct
import unittest
import zlib
from tools import legacy_assets as assets

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / assets.RESOURCES / 'assets'


def encoded(value):
    return json.dumps(value).encode('utf-8')


def chunk(kind, data):
    return struct.pack('>I', len(data)) + kind + data + struct.pack('>I', zlib.crc32(kind + data) & 0xffffffff)


def png():
    return b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', 1, 1, 8, 6, 0, 0, 0)) + chunk(b'IDAT', zlib.compress(b'\x00\xff\xff\xff\xff')) + chunk(b'IEND', b'')


class LegacyAssetsTests(unittest.TestCase):
    def model_graph(self, overrides=None):
        rows = {
            'test/models/block/base.json': encoded({'textures': {'face': 'test:blocks/base', 'particle': '#face'}, 'elements': [{'faces': {'north': {'texture': '#face'}}}]}),
            'test/models/block/child.json': encoded({'parent': 'test:block/base', 'textures': {'face': 'test:blocks/child'}}),
            'test/textures/blocks/base.png': png(), 'test/textures/blocks/child.png': png(),
        }
        rows.update(overrides or {})
        return assets.ModelGraph(assets.AssetIndex(rows))

    def test_child_overrides_are_resolved_in_inherited_faces_and_particle(self):
        graph = self.model_graph()
        self.assertEqual(1, graph.concrete('test/models/block/child.json'))
        self.assertEqual({'test/textures/blocks/child.png'}, graph.textures)

    def test_valid_particle_does_not_hide_missing_face_texture(self):
        graph = self.model_graph({'test/models/block/child.json': encoded({'parent': 'test:block/base', 'textures': {'particle': 'test:blocks/base', 'face': 'test:blocks/missing'}})})
        with self.assertRaisesRegex(ValueError, 'Missing asset.*missing'):
            graph.concrete('test/models/block/child.json')

    def test_child_alias_can_use_a_parent_texture_slot(self):
        graph = self.model_graph({'test/models/block/child.json': encoded({'parent': 'test:block/base', 'textures': {'alias': 'test:blocks/child', 'face': '#alias'}})})
        self.assertEqual(1, graph.concrete('test/models/block/child.json'))
        self.assertEqual({'test/textures/blocks/child.png'}, graph.textures)

    def test_parent_and_texture_cycles_are_rejected(self):
        graph = self.model_graph({'test/models/block/base.json': encoded({'parent': 'test:block/child'})})
        with self.assertRaisesRegex(ValueError, 'parent cycle'):
            graph.concrete('test/models/block/child.json')
        graph = self.model_graph({'test/models/block/child.json': encoded({'parent': 'test:block/base', 'textures': {'face': '#alias', 'alias': '#face'}})})
        with self.assertRaisesRegex(ValueError, 'Texture variable cycle'):
            graph.concrete('test/models/block/child.json')

    def test_unbound_variables_and_empty_concrete_models_are_rejected(self):
        graph = self.model_graph({'test/models/block/child.json': encoded({'parent': 'test:block/base', 'textures': {'face': '#absent'}})})
        with self.assertRaisesRegex(ValueError, 'Unbound texture variable'):
            graph.concrete('test/models/block/child.json')
        graph = self.model_graph({'test/models/block/child.json': encoded({'elements': []})})
        with self.assertRaisesRegex(ValueError, 'no visible faces'):
            graph.concrete('test/models/block/child.json')

    def test_png_checks_pixels_crc_and_truncation(self):
        self.assertEqual([1, 1], assets.png_info(png()))
        broken = bytearray(png()); broken[20] ^= 1
        with self.assertRaisesRegex(ValueError, 'CRC'):
            assets.png_info(bytes(broken))
        with self.assertRaisesRegex(ValueError, 'Truncated|Incomplete'):
            assets.png_info(png()[:-3])

    def test_strict_json_and_translation_duplicates_cannot_silently_override(self):
        with self.assertRaisesRegex(ValueError, 'Duplicate JSON'):
            assets.strict_json(b'{"textures":{},"textures":{}}')
        with self.assertRaisesRegex(ValueError, 'Duplicate translation'):
            assets.read_lang(b'tile.test.name=A\ntile.test.name=B\n')

    def test_registered_contract_has_exact_blockstates_and_inventory_roles(self):
        contract = json.loads((ASSETS / assets.MANIFEST).read_text(encoding='utf-8'))
        actual = {'dead_earth:' + path.stem for path in (ASSETS / 'dead_earth/blockstates').glob('*.json')}
        self.assertEqual(actual, set(contract['blocks']))
        self.assertEqual(set(contract['blocks']) - set(contract['items']), set(contract['environmentalBlocks']))
        for identifier in contract['items']:
            path = identifier.split(':', 1)[1]
            if identifier in contract['nativeDynamicItems']:
                self.assertTrue((ASSETS / assets.resource(contract['nativeDynamicItems'][identifier]['sprite'], 'texture')).is_file())
            else:
                self.assertTrue((ASSETS / 'dead_earth/models/item' / (path + '.json')).is_file(), identifier)

    def test_telemetry_and_machines_have_specialized_geometry_instead_of_foreign_cube_skins(self):
        contract = json.loads((ASSETS / assets.MANIFEST).read_text(encoding='utf-8'))
        self.assertEqual({'dead_earth:zone_relay', 'dead_earth:charger', 'dead_earth:fortune_processor'}, set(contract['specializedBlockModels']))
        for identifier in contract['specializedBlockModels']:
            data = json.loads((ASSETS / identifier.replace(':', '/models/block/')).with_suffix('.json').read_text(encoding='utf-8'))
            self.assertGreater(len(data.get('elements', [])), 1)
            self.assertIn('gui', data['display'])
            for texture in data['textures'].values():
                self.assertNotIn('redstone_block', texture)
                self.assertNotIn('furnace_front', texture)
                self.assertNotIn('dispenser_front', texture)
        for identifier in ('charger', 'fortune_processor'):
            data = json.loads((ASSETS / 'dead_earth/models/block' / (identifier + '.json')).read_text(encoding='utf-8'))
            self.assertTrue(any(element['from'] == [0, 0, 0] and element['to'] == [16, 16, 16] for element in data['elements']), identifier)

    def test_runtime_configured_sieves_and_trace_patterns_have_assets_and_names(self):
        contract = json.loads((ASSETS / assets.MANIFEST).read_text(encoding='utf-8'))
        ids = set(contract['blocks'])
        sieve = json.loads((ROOT / 'legacy/pack/config/dead-earth/sieving.json').read_text(encoding='utf-8'))
        for name in sieve['materials']:
            for depth in range(1 if name in sieve['ownFirstCompression'] else 2, 5):
                self.assertIn(f'dead_earth:compressed_{name}_{depth}', ids)
        traces = json.loads((ROOT / 'legacy/pack/config/dead-earth/director/catalog.json').read_text(encoding='utf-8'))['traces']
        for locale in ('en_us', 'ru_ru'):
            names = assets.read_lang((ASSETS / 'dead_earth/lang' / (locale + '.lang')).read_bytes())
            for pattern in traces:
                self.assertIn('dead_earth:trace_' + pattern, ids)
                self.assertIn('tile.dead_earth.trace_' + pattern + '.name', names)


if __name__ == '__main__':
    unittest.main()
