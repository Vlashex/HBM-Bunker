import copy
import json
from decimal import Decimal
from pathlib import Path
import unittest

from tools import legacy_full_recipes, legacy_materials, legacy_sieve_pricing as pricing

ROOT = Path(__file__).resolve().parents[1]


class SievePricingTests(unittest.TestCase):
    def setUp(self):
        self.raw = dict(legacy_full_recipes.load(ROOT, 'emc.json')['allowlist'])
        self.raw.update(legacy_materials.raw_values(ROOT))
        self.spec = pricing.load(ROOT)
        self.drops = legacy_full_recipes.load(ROOT, 'sieve.json')['drops']

    def report(self, spec=None, raw=None, drops=None):
        return pricing.calculate(spec or self.spec, raw or self.raw,
                                 self.drops if drops is None else drops)

    def test_real_native_and_hbm_expected_yields(self):
        rows = self.report()['materials']
        gravel = rows['minecraft:gravel|0']
        self.assertEqual(gravel['expectedByMesh']['4'], '725.4912')
        self.assertEqual(gravel['expectedByMesh']['3'], '282.7904')
        self.assertEqual(gravel['emc'], 715)
        self.assertEqual(gravel['bestMesh'], 4)
        self.assertEqual(rows['minecraft:sand|0']['expectedByMesh']['4'], '73.3440')
        self.assertEqual(rows['minecraft:sand|0']['emc'], 72)
        self.assertEqual(rows['exnihilocreatio:block_dust|0']['emc'], 31)
        self.assertEqual(rows['exnihilocreatio:block_dust|0']['bestMesh'], 3)

    def test_red_sand_does_not_inherit_metadata_zero_hbm_drops(self):
        red = self.report()['materials']['minecraft:sand|1']
        self.assertEqual(red['expectedByMesh']['4'], '32.0000')
        self.assertEqual(red['emc'], 31)

    def test_profit_target_is_return_on_input_with_closest_positive_integer_price(self):
        for row in self.report()['materials'].values():
            expected = Decimal(row['expectedByMesh'][str(row['bestMesh'])])
            actual = expected / row['emc'] - 1
            self.assertGreater(actual, 0)
            for alternative in (row['emc'] - 1, row['emc'] + 1):
                if 0 < alternative < expected:
                    self.assertLessEqual(abs(actual - Decimal('.015')),
                                         abs(expected / alternative - 1 - Decimal('.015')))

    def test_prices_respond_to_drop_and_canonical_material_changes(self):
        raw = dict(self.raw); raw['minecraft:emerald|0'] *= 2
        self.assertGreater(self.report(raw=raw)['materials']['minecraft:gravel|0']['emc'], 715)
        drops = copy.deepcopy(self.drops)
        for row in drops:
            if row['output'] == 'hbm:fluorite': row['chance'] /= 2
        self.assertLess(self.report(drops=drops)['materials']['minecraft:sand|0']['emc'], 72)
        self.assertEqual(self.report(drops=[])['materials']['minecraft:gravel|0']
                         ['expectedByMesh']['4'], '486.0160')

    def test_unknown_rewards_fail_instead_of_silently_pricing_them_zero(self):
        drops = self.drops + [{'input': 'minecraft:gravel', 'output': 'example:new_gem',
                               'chance': .1, 'mesh': 4}]
        with self.assertRaisesRegex(ValueError, 'Unvalued sieve reward'):
            self.report(drops=drops)

    def test_sieve_inputs_have_no_emc_but_keep_zone_resource_value(self):
        outputs = legacy_full_recipes.products()
        raw = json.loads(outputs['legacy/pack/config/dead-earth/progression.json'])['rawEmc']
        zone = json.loads(outputs['legacy/pack/config/dead-earth/coordinator.json'])['resourcePrices']
        emc = {r['item']: r['emc'] for r in json.loads(
            outputs['legacy/pack/config/ProjectE/custom_emc.json'])['entries']}
        for token, price in pricing.prices(ROOT, self.raw).items():
            self.assertNotIn(token, raw)
            self.assertEqual(emc[token], 0)
            self.assertEqual(zone[token.replace('|', ':')], price)
        catalog = json.loads((ROOT / 'legacy/sieving/materials.json').read_text())
        for material in catalog['materials']:
            base = material['raw_id'] + '|' + str(material['raw_meta'])
            if not material['currently_siftable']:
                continue
            self.assertNotIn(base, raw)
            self.assertEqual(emc[base], 0)
            native = material.get('native_level1_id')
            if native:
                token = native + '|' + str(material['native_level1_meta'])
                self.assertNotIn(token, raw)
                self.assertEqual(emc[token], 0)
            for level in material['levels']:
                item = level['id'] + '|' + str(level['meta'])
                self.assertNotIn(item, raw)
                self.assertEqual(emc[item], 0)
                if base in pricing.prices(ROOT, self.raw):
                    self.assertEqual(zone[item.replace('|', ':')],
                                     pricing.prices(ROOT, self.raw)[base] * level['raw_equivalents'])
        self.assertEqual(raw['minecraft:cobblestone|0'], 1)
        self.assertEqual(raw['excompressum:compressed_block|1'], 9)
        self.assertEqual(raw['dead_earth:compressed_cobblestone_4|0'], 6561)
        self.assertEqual(raw['minecraft:iron_ingot|0'], 256)
        self.assertEqual(raw['minecraft:diamond|0'], 8192)
        self.assertNotIn('dead_earth:compact_mesh_diamond_v5|0', raw)


if __name__ == '__main__':
    unittest.main()
