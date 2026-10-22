"""Pinned lightweight QoL additions must retain native backpack data and raw-only EMC."""
import json
from pathlib import Path
import unittest
import zipfile

from tools import legacy_full_recipes, legacy_port

ROOT = Path(__file__).resolve().parents[1]
ADDITIONS = {'simplybackpacks', 'simplemagnets', 'trashcans',
             'supermartijn642corelib', 'supermartijn642configlib'}


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
        pin = next(r for r in legacy_port.full_pins() if r['mod_id'] == 'simplybackpacks')
        with zipfile.ZipFile(ROOT / '.cache/legacy' / pin['filename']) as jar:
            for tier in ('uncommon', 'rare', 'epic'):
                recipe = json.loads(jar.read('assets/simplybackpacks/recipes/' + tier + '.json'))
                self.assertEqual(recipe['type'], 'simplybackpacks:copyBackpack')
        script = legacy_full_recipes.products()['legacy/pack/scripts/dead_earth_progression.zs'].decode()
        self.assertNotIn('recipes.remove(<simplybackpacks:', script)

    def test_magnet_and_bin_have_accessible_hbm_recipes_and_no_emc(self):
        recipes = legacy_full_recipes.load(ROOT, 'recipes.json')['recipes']
        by_output = {r['output']: r for r in recipes}
        for output in ('simplemagnets:basicmagnet', 'simplemagnets:advancedmagnet',
                       'trashcans:item_trash_can'):
            self.assertIn(output, by_output)
            ingredients = list(by_output[output]['key'].values())
            self.assertTrue(any(x.startswith('hbm:') for x in ingredients))
            self.assertFalse(any('ender_pearl' in x or 'nether_star' in x for x in ingredients))
        doc = json.loads(legacy_full_recipes.products()['legacy/pack/config/dead-earth/progression.json'])
        self.assertFalse(any(k.startswith(('simplybackpacks:', 'simplemagnets:', 'trashcans:'))
                             for k in doc['rawEmc']))


if __name__ == '__main__':
    unittest.main()
