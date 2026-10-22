"""Pinned lightweight QoL additions must retain native backpack data and raw-only EMC."""
import json
from pathlib import Path
import unittest
import zipfile

from tools import legacy_full_recipes, legacy_port

ROOT = Path(__file__).resolve().parents[1]
ADDITIONS = {'v0idssmartbackpacks'}


class BackpackAdditionTests(unittest.TestCase):
    def test_small_pinned_dependency_set_is_present_for_both_sides(self):
        pins = {row['mod_id']: row for row in legacy_port.full_pins()}
        self.assertTrue(ADDITIONS <= pins.keys())
        self.assertLess(sum(pins[name]['size'] for name in ADDITIONS), 3 * 1024 * 1024)
        for name in ADDITIONS:
            self.assertEqual(pins[name]['side'], 'both')
            legacy_port.checked_mod(ROOT / '.cache/legacy' / pins[name]['filename'], pins[name])
        self.assertIn('forgelin', pins)

    def test_bags_keep_native_nbt_copying_upgrade_recipes(self):
        pin = next(r for r in legacy_port.full_pins() if r['mod_id'] == 'v0idssmartbackpacks')
        with zipfile.ZipFile(ROOT / '.cache/legacy' / pin['filename']) as jar:
            for tier in ('reinforced', 'advanced', 'ultimate'):
                recipe = json.loads(jar.read('assets/v0idssmartbackpacks/recipes/backpack_' + tier + '.json'))
                self.assertEqual(recipe['type'], 'v0idssmartbackpacks:backpack_upgrade')
        script = (ROOT / 'legacy/pack/scripts/dead_earth_backpacks.zs').read_text()
        for tier in ('reinforced', 'advanced', 'ultimate'):
            self.assertNotIn('recipes.remove(<v0idssmartbackpacks:backpack_' + tier + '>', script)

    def test_backpack_has_hbm_gate_and_no_grinder_or_generators(self):
        script = (ROOT / 'legacy/pack/scripts/dead_earth_backpacks.zs').read_text()
        for name in ('backpack', 'upgrade_base', 'upgrade_magnet', 'upgrade_pulling', 'upgrade_void'):
            self.assertIn('<v0idssmartbackpacks:' + name + '>', script)
        self.assertIn('<hbm:plate_iron>', script)
        for name in ('upgrade_grinding', 'upgrade_generator_em', 'upgrade_generator_furnace',
                     'upgrade_generator_kinetic', 'upgrade_generator_nuclear',
                     'upgrade_generator_solar', 'upgrade_generator_wind'):
            self.assertIn('recipes.remove(<v0idssmartbackpacks:' + name + '>);', script)

    def test_backpack_technology_has_no_emc(self):
        doc = json.loads(legacy_full_recipes.products()['legacy/pack/config/dead-earth/progression.json'])
        self.assertFalse(any(k.startswith('v0idssmartbackpacks:') for k in doc['rawEmc']))


if __name__ == '__main__':
    unittest.main()
