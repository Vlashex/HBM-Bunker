"""Regression contracts for compact sieves and native-looking compression models."""
import json
import runpy
import tempfile
from pathlib import Path
import unittest
import zipfile

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / 'legacy/coordinator/src/main/resources/assets/dead_earth'


def read(relative):
    return json.loads((ASSETS / relative).read_text(encoding='utf-8'))


class SieveVisualTests(unittest.TestCase):
    def test_frames_reuse_open_native_wood_geometry_and_each_installed_mesh(self):
        for level in range(3, 6):
            name = 'compact_sieve_v' + str(level)
            frame = read('models/block/' + name + '.json')
            self.assertEqual('excompressum:block/heavy_sieve', frame['parent'])
            self.assertEqual('minecraft:blocks/log_oak', frame['textures']['texture'])
            self.assertEqual([30, 225, 0], frame['display']['gui']['rotation'])
            self.assertNotIn('elements', frame)
            parts = read('blockstates/' + name + '.json')['multipart']
            self.assertEqual(81, len(parts))
            variants = {part['when']['mesh']: part['apply'] for part in parts if 'mesh' in part['when']}
            self.assertIn('0', variants)
            for mesh, grade in enumerate(('string', 'flint', 'iron', 'diamond'), 1):
                installed = read('models/block/' + name + '_' + grade + '.json')
                self.assertEqual('excompressum:block/heavy_sieve_with_mesh', installed['parent'])
                self.assertEqual('excompressum:blocks/' + grade + '_mesh', installed['textures']['iron_mesh'])
                self.assertEqual('dead_earth:' + name + '_' + grade, variants[str(mesh)]['model'])
            self.assertEqual('dead_earth:block/' + name, read('models/item/' + name + '.json')['parent'])

    def test_material_layers_share_models_and_lower_inside_the_rim(self):
        rows = json.loads((ROOT / 'legacy/sieving/materials.json').read_text(encoding='utf-8'))['materials']
        expected = {(str(index), str(fill)) for index in range(19) for fill in range(1, 5)}
        for level in range(3, 6):
            parts = read('blockstates/compact_sieve_v' + str(level) + '.json')['multipart']
            layers = [part for part in parts if 'fill' in part['when']]
            self.assertEqual(expected, {(part['when']['material'], part['when']['fill']) for part in layers})
            self.assertTrue(all('mesh' not in part['when'] for part in layers))
            for part in layers:
                material = rows[int(part['when']['material'])]['material']
                self.assertEqual('dead_earth:compact_sieve_content_' + material + '_' + part['when']['fill'], part['apply']['model'])
        for row in rows:
            heights = []
            for fill in range(1, 5):
                layer = read('models/block/compact_sieve_content_' + row['material'] + '_' + str(fill) + '.json')
                self.assertEqual(1, len(layer['elements']))
                bounds = layer['elements'][0]
                self.assertEqual([1, 8.55, 1], bounds['from'])
                self.assertEqual(15, bounds['to'][0])
                self.assertEqual(15, bounds['to'][2])
                self.assertTrue(8.55 < bounds['to'][1] < 12)
                self.assertTrue(all(face['tintindex'] == 1 for face in bounds['faces'].values()))
                heights.append(bounds['to'][1])
            self.assertEqual(sorted(set(heights)), heights)
        for language in ('en_us', 'ru_ru'):
            self.assertIn('tooltip.dead_earth.sieving.frame_strokes=', (ROOT / 'legacy/sieving/lang' / (language + '.lang')).read_text(encoding='utf-8'))

    def test_language_merge_replaces_owned_keys_retains_other_names_and_is_idempotent(self):
        merge = runpy.run_path(str(ROOT / 'tools/legacy_sieving_recipes.py'))['merge_lang']
        with tempfile.TemporaryDirectory() as directory:
            target = Path(directory) / 'ru_ru.lang'
            target.write_text('# Existing core names\nother.name=Важное имя\ntile.dead_earth.compact_sieve_v3.name=Old English\ntile.dead_earth.compact_sieve_v3.name=Duplicate\n', encoding='utf-8')
            entries = [('tile.dead_earth.compact_sieve_v3.name', 'Сито III'), ('tooltip.dead_earth.sieving.frame_strokes', 'Автоматическое просеивание')]
            merge(target, entries)
            result = target.read_text(encoding='utf-8')
            self.assertIn('# Existing core names\nother.name=Важное имя\n', result)
            self.assertEqual(1, result.count('tile.dead_earth.compact_sieve_v3.name='))
            self.assertIn('tile.dead_earth.compact_sieve_v3.name=Сито III\n', result)
            self.assertIn('tooltip.dead_earth.sieving.frame_strokes=Автоматическое просеивание\n', result)
            merge(target, entries)
            self.assertEqual(result, target.read_text(encoding='utf-8'))

    def test_all_sieve_names_are_complete_clear_russian(self):
        values = dict(line.split('=', 1) for line in (ROOT / 'legacy/sieving/lang/ru_ru.lang').read_text(encoding='utf-8').splitlines() if '=' in line)
        self.assertEqual('Сито III', values['tile.dead_earth.compact_sieve_v3.name'])
        self.assertEqual('Сито V', values['tile.dead_earth.compact_sieve_v5.name'])
        self.assertEqual('Алмазная сетка V', values['item.dead_earth.compact_mesh_diamond_v5.name'])
        self.assertEqual('Нитяная сетка III', values['item.dead_earth.compact_mesh_string_v3.name'])
        self.assertEqual('Сжатый гравий ×81', values['tile.dead_earth.compressed_gravel_2.name'])
        self.assertEqual('Сжатая земля ×729', values['tile.dead_earth.compressed_dirt_3.name'])
        self.assertEqual('Сжатая дубовая листва ×9', values['tile.dead_earth.compressed_oak_leaves_1.name'])
        names = [value for key, value in values.items() if key.endswith('.name')]
        self.assertEqual(83, len(names))
        self.assertTrue(all(any('А' <= character <= 'я' or character in 'Ёё' for character in name) for name in names))

    def test_mesh_items_use_native_three_dimensional_lattice(self):
        for level in range(3, 6):
            for grade in ('string', 'flint', 'iron', 'diamond'):
                self.assertEqual('exnihilocreatio:item/item_mesh_' + grade,
                                 read('models/item/compact_mesh_' + grade + '_v' + str(level) + '.json')['parent'])

    def test_compressed_cubes_have_native_material_and_six_tinted_faces_only(self):
        rows = json.loads((ROOT / 'legacy/sieving/materials.json').read_text(encoding='utf-8'))['materials']
        count = 0
        for row in rows:
            for block in row['levels']:
                name = block['id'].split(':')[1]
                model = read('models/block/' + name + '.json')
                self.assertEqual('minecraft:block/block', model['parent'])
                self.assertEqual({'particle', 'all'}, set(model['textures']))
                self.assertEqual(1, len(model['elements']), name)
                cube = model['elements'][0]
                self.assertEqual([0, 0, 0], cube['from'])
                self.assertEqual([16, 16, 16], cube['to'])
                self.assertEqual(6, len(cube['faces']))
                self.assertTrue(all(face['tintindex'] == 0 and face['texture'] == '#all'
                                    for face in cube['faces'].values()), name)
                if row['native_level1_id']:
                    self.assertEqual('excompressum:blocks/compressed_' + row['material'], model['textures']['all'])
                elif row['material'].startswith('crushed_') or row['material'] == 'skystone':
                    self.assertTrue(model['textures']['all'].startswith('exnihilocreatio:blocks/'))
                count += 1
        self.assertEqual(68, count)

    def test_color_registration_covers_blocks_and_inventory_without_client_linkage(self):
        source = (ROOT / 'legacy/coordinator/src/main/java/dev/deadearth/legacy/sieving/SieveVisuals.java').read_text(encoding='utf-8')
        self.assertIn('public static void registerColors()', source)
        self.assertIn('registerBlockColorHandler/func_186722_a', source)
        self.assertIn('registerItemColorHandler/func_186731_a', source)
        self.assertIn('ColorHandlerEvent$Block', source)
        self.assertIn('ColorHandlerEvent$Item', source)
        self.assertNotIn('Reflect.type("net.minecraft.client.Minecraft")', source)
        self.assertIn('colorMultiplier/func_186728_a', source)
        self.assertNotIn('import net.minecraft.client', source)
        self.assertNotIn('import net.minecraftforge.client', source)


class NativeAssetTests(unittest.TestCase):
    def test_all_visual_model_parents_and_material_textures_resolve(self):
        cache = ROOT / '.cache'
        paths = [cache / 'legacy/ExCompressum_1.12.2-3.0.32.jar',
                 cache / 'legacy/exnihilocreatio-1.12.2-0.4.7.2.jar',
                 cache / 'legacy-client/client-1.12.2.jar']
        if not all(path.exists() for path in paths):
            self.skipTest('Pinned native client JAR cache is unavailable')
        content = {}
        for path in paths:
            with zipfile.ZipFile(path) as jar:
                content.update((name, jar.read(name)) for name in jar.namelist()
                               if name.startswith('assets/') and (name.endswith('.json') or name.endswith('.png')))
        own = []
        for folder in ('models/block', 'models/item'):
            for path in (ASSETS / folder).glob('*.json'):
                if path.stem.startswith(('compact_sieve_', 'compact_mesh_', 'compressed_')):
                    ref = 'assets/dead_earth/' + folder + '/' + path.name
                    content[ref] = path.read_bytes()
                    own.append(ref)

        def asset(ref, folder, extension):
            namespace, value = ref.split(':', 1) if ':' in ref else ('minecraft', ref)
            return 'assets/' + namespace + '/' + folder + '/' + value + extension

        def resolve_model(path, visited=()):
            self.assertNotIn(path, visited, 'Parent model cycle: ' + path)
            self.assertIn(path, content, 'Missing native model: ' + path)
            model = json.loads(content[path])
            textures, elements = {}, []
            if 'parent' in model:
                textures, elements = resolve_model(asset(model['parent'], 'models', '.json'), visited + (path,))
            textures.update(model.get('textures', {}))
            return textures, model.get('elements', elements)

        for path in own:
            textures, elements = resolve_model(path)
            for texture in [face['texture'] for element in elements for face in element['faces'].values()] + [textures.get('particle', textures.get('texture', '#all'))]:
                visited = set()
                while texture.startswith('#'):
                    self.assertNotIn(texture, visited, 'Texture alias cycle in ' + path)
                    visited.add(texture)
                    self.assertIn(texture[1:], textures, 'Missing texture binding in ' + path)
                    texture = textures[texture[1:]]
                self.assertIn(asset(texture, 'textures', '.png'), content, path + ': ' + texture)
        self.assertEqual(242, len(own))

    def test_pinned_native_parents_meshes_and_crushed_textures_exist(self):
        cache = ROOT / '.cache/legacy'
        paths = [cache / 'ExCompressum_1.12.2-3.0.32.jar', cache / 'exnihilocreatio-1.12.2-0.4.7.2.jar']
        if not all(path.exists() for path in paths):
            self.skipTest('Pinned native JAR cache is unavailable')
        with zipfile.ZipFile(paths[0]) as jar:
            frame = json.loads(jar.read('assets/excompressum/models/block/heavy_sieve.json'))
            self.assertEqual(8, len(frame['elements']))
            self.assertEqual(4, sum(e['to'][1] == 9 and e['from'][1] == 0 for e in frame['elements']))
            self.assertTrue(all(not (e['from'] == [0, 0, 0] and e['to'] == [16, 16, 16]) for e in frame['elements']))
            mesh = json.loads(jar.read('assets/excompressum/models/block/heavy_sieve_with_mesh.json'))
            self.assertEqual(9, len(mesh['elements']))
            for grade in ('string', 'flint', 'iron', 'diamond'):
                self.assertIn('assets/excompressum/textures/blocks/' + grade + '_mesh.png', jar.namelist())
        with zipfile.ZipFile(paths[1]) as jar:
            for grade in ('string', 'flint', 'iron', 'diamond'):
                self.assertIn('assets/exnihilocreatio/models/item/item_mesh_' + grade + '.json', jar.namelist())
            for texture in ('crushed_andesite', 'crushed_diorite', 'crushed_granite', 'blockskystonecrushed'):
                self.assertIn('assets/exnihilocreatio/textures/blocks/' + texture + '.png', jar.namelist())


if __name__ == '__main__':
    unittest.main()
