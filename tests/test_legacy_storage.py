import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

ROOT=Path(__file__).resolve().parents[1]
SPEC=importlib.util.spec_from_file_location('legacy_storage',ROOT/'tools/legacy_storage.py')
storage=importlib.util.module_from_spec(SPEC);SPEC.loader.exec_module(storage)

class EarlyStorageTests(unittest.TestCase):
    def test_base_network_has_only_two_HBM_plates_and_no_late_materials(self):
        recipes={r['name']:r for r in storage.recipe_data()}
        totals={}
        for name in ['master','request','cable','link']:
            r=recipes[name]
            for symbol in ''.join(r['pattern']):
                if symbol!=' ':
                    item=r['key'][symbol];totals[item]=totals.get(item,0)+1
        self.assertEqual(totals['hbm:plate_iron'],2)
        self.assertEqual(totals['minecraft:iron_nugget'],8)
        self.assertEqual(totals['minecraft:redstone'],1)
        self.assertEqual(totals['minecraft:glass'],3)
        self.assertEqual(totals['storagenetwork:kabel'],3)
        self.assertFalse(any(x in str(totals) for x in ['diamond','quartz','steel','circuit','ender']))
        self.assertEqual(recipes['cable']['count'],32)
        self.assertEqual(recipes['link']['count'],8)

    def test_remote_is_optional_and_uses_only_two_steel_plates(self):
        r=next(r for r in storage.recipe_data() if r['name']=='remote')
        self.assertEqual(''.join(r['pattern']).count('S'),2)
        self.assertEqual(r['key']['S'],'hbm:plate_steel')
        self.assertEqual(r['output'],'storagenetwork:remote:0')

    def test_overrides_remove_old_recipes_and_only_touch_selected_remote(self):
        text=storage.script().decode()
        for r in storage.recipe_data():self.assertIn('recipes.remove(<'+r['output']+'>);',text)
        self.assertNotIn('<storagenetwork:remote:*>',text)
        self.assertNotIn('recipes.removeAll',text)

    def test_client_server_archives_do_not_mix_modern_dependencies_or_worlds(self):
        for side in ['client','server']:
            entries=storage.entries(side)
            self.assertFalse(any(x in name.lower() for name in entries for x in ['securitycraft','mutationcraft','overgrown','neoforge','world/','accounts','runtime/','probe']))
            self.assertTrue(any(n.endswith('scripts/dead_earth_early_storage.zs') for n in entries))
            has_ctm=any('/CTM-' in n or n.startswith('mods/CTM-') for n in entries)
            self.assertEqual(has_ctm,side=='client')

    def test_generated_zip_is_deterministic(self):
        data={'b.txt':b'b','a.txt':b'a'}
        self.assertEqual(storage.zip_bytes(data),storage.zip_bytes(dict(reversed(list(data.items())))))

    def test_tampered_download_is_rejected(self):
        row=storage.pins()[0]
        with tempfile.TemporaryDirectory() as d:
            p=Path(d)/row['filename'];p.write_bytes(b'wrong jar')
            with self.assertRaisesRegex(ValueError,'Pin mismatch'):storage.checked_bytes(p,row)

    def test_config_has_no_emc_for_all_outputs(self):
        data=json.loads((ROOT/'legacy/storage/pack/config/ProjectE/custom_emc.json').read_text())
        values={r['item']:r['emc'] for r in data['entries']}
        for r in storage.recipe_data():
            bits=r['output'].split(':');key=':'.join(bits[:2])+'|'+(bits[2] if len(bits)>2 else '0')
            self.assertEqual(values[key],0)

    def test_unsafe_recipe_ingredients_and_mod_versions_are_rejected(self):
        with tempfile.TemporaryDirectory() as d:
            r=Path(d);(r/'legacy/storage').mkdir(parents=True)
            data={'schema':1,'recipes':[{'name':'bad','output':'storagenetwork:master','count':1,'pattern':[' R ','   ','   '],'key':{'R':'bad>); injected'}}]}
            (r/'legacy/storage/recipes.json').write_text(json.dumps(data))
            with self.assertRaisesRegex(ValueError,'Invalid ingredient'):storage.recipe_data(r)

if __name__=='__main__':unittest.main()
