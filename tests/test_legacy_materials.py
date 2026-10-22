import copy
import json
from pathlib import Path
import tempfile
import unittest

from tools import legacy_materials as materials, legacy_full_recipes

ROOT = Path(__file__).resolve().parents[1]


class MaterialUnificationTests(unittest.TestCase):
    def setUp(self):
        self.document = json.loads((ROOT / 'legacy/materials/catalog.json').read_text(encoding='utf-8'))

    def invalid(self, document):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory); folder = root / 'legacy/materials'; folder.mkdir(parents=True)
            (folder / 'catalog.json').write_text(json.dumps(document), encoding='utf-8')
            with self.assertRaises(ValueError):
                materials.load(root)

    def test_piece_chunk_and_silicon_cannot_be_equated_by_catalog(self):
        for meta in (0, 1):
            with self.subTest(meta=meta):
                bad = copy.deepcopy(self.document)
                bad['materials'][0]['forms'][0]['legacyAliases'] = ['exnihilocreatio:item_ore_iron:' + str(meta)]
                self.invalid(bad)
        for name in ('silicon', 'certus_quartz', 'fuel'):
            with self.subTest(material=name):
                bad = copy.deepcopy(self.document); bad['materials'][0]['material'] = name; self.invalid(bad)

    def test_ore_dictionary_alias_cannot_join_two_materials_or_different_forms(self):
        bad = copy.deepcopy(self.document)
        bad['materials'][1]['forms'][0]['oreNames'] = ['ingotIron']; self.invalid(bad)
        bad = copy.deepcopy(self.document)
        bad['materials'][0]['forms'][0]['oreNames'] = ['plateIron']; self.invalid(bad)

    def test_nbt_capability_registry_and_budget_guards_are_mandatory(self):
        for key, value in [('convertTaggedStacks', True), ('convertCapabilityStacks', True),
                           ('retainRegistryEntries', False), ('inventoryIntervalTicks', 1),
                           ('inventorySlotsPerPass', 4096)]:
            with self.subTest(policy=key):
                bad = copy.deepcopy(self.document); bad['policy'][key] = value; self.invalid(bad)

    def test_steel_plates_and_radioactive_materials_do_not_gain_emc(self):
        for name in ('steel', 'uranium', 'thorium'):
            bad = copy.deepcopy(self.document)
            row = next(row for row in bad['materials'] if row['material'] == name)
            row['forms'][0].update(value=512, allowEmc=True); self.invalid(bad)
        bad = copy.deepcopy(self.document)
        row = next(row for row in bad['materials'][0]['forms'] if row['form'] == 'plate')
        row.update(value=256, allowEmc=True); self.invalid(bad)
        values = materials.raw_values()
        self.assertFalse(any('plate_' in key or 'steel' in key or 'fuel' in key for key in values))

    def test_unknown_fields_and_wildcard_aliases_are_not_silently_accepted(self):
        bad = copy.deepcopy(self.document); bad['materials'][0]['forms'][0]['rawEMCC'] = 256; self.invalid(bad)
        bad = copy.deepcopy(self.document); bad['materials'][0]['forms'][0]['legacyAliases'] = ['example:ingot:32767']; self.invalid(bad)
        bad = copy.deepcopy(self.document); bad['schema'] = True; self.invalid(bad)

    def test_material_emc_prices_and_legacy_storage_prices_agree(self):
        prices = materials.zone_values()
        for key, emc in materials.raw_values().items():
            self.assertEqual(prices[key.replace('|', ':')], emc)
        for group in materials.load()[1]:
            if 'value' in group:
                for alias in group['legacyAliases']:
                    self.assertEqual(prices[alias], prices[group['canonical']])

    def test_crop_outputs_are_explicit_without_removing_hbm_metallurgy(self):
        script = materials.products()['legacy/pack/scripts/dead_earth_materials.zs'].decode()
        self.assertIn('<hbm:ingot_copper:0> * 6', script)
        self.assertIn('<hbm:ingot_aluminium:0> * 8', script)
        self.assertIn('<hbm:ingot_lead:0> * 4', script)
        self.assertIn('<hbm:ingot_titanium:0> * 2', script)
        self.assertIn('<hbm:ingot_tungsten:0> * 2', script)
        self.assertIn('<hbm:sulfur:0> * 8', script)
        self.assertIn('<hbm:niter:0> * 5', script)
        self.assertIn('mysticalagriculture:ingotaluminum(?:_[0-9]+)?', script)
        self.assertNotIn('mysticalagriculture:ingotaluminium', script)
        self.assertNotIn('JEI.hide(<exnihilocreatio:item_ore_copper', script)
        self.assertNotIn('JEI.hide(<exnihilocreatio:item_ore_aluminium', script)
        self.assertNotIn('JEI.hide(<exnihilocreatio:item_ore_lead', script)
        self.assertIn('JEI.hide(<exnihilocreatio:item_ore_iron:3>)', script)
        self.assertNotIn('recipes.remove(<hbm:', script)
        self.assertNotIn('dead_earth_material_steel', script)
        self.assertNotIn('addShapeless', script)

    def test_native_supported_crops_are_enabled_before_item_registration(self):
        products = materials.products()
        config = products['legacy/pack/config/mysticalagriculture.cfg'].decode()
        for name in ('copper', 'aluminum', 'lead', 'titanium', 'tungsten', 'sulfur', 'saltpeter'):
            self.assertIn('I:' + name + '_seeds=2', config)
        self.assertNotIn('steel_seeds', config)
        bad = copy.deepcopy(self.document)
        next(row for row in bad['materials'] if row['material'] == 'copper')['crop']['seedEnable'] = 1
        self.invalid(bad)

    def test_full_progression_packages_materials_and_keeps_compression_raw_only(self):
        products = legacy_full_recipes.products()
        self.assertIn('legacy/pack/config/dead-earth/materials.json', products)
        self.assertIn('legacy/pack/scripts/dead_earth_materials.zs', products)
        data = json.loads(products['legacy/pack/config/dead-earth/progression.json'])
        self.assertEqual(data['rawEmc']['hbm:powder_copper|0'], 128)
        self.assertNotIn('dead_earth:compressed_gravel_4|0', data['rawEmc'])
        self.assertNotIn('hbm:powder_steel|0', data['rawEmc'])
        self.assertNotIn('hbm:plate_copper|0', data['rawEmc'])
        self.assertIn('hbm:powder_copper:0', data['requiredItems'])


if __name__ == '__main__':
    unittest.main()
