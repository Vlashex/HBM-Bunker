import copy
import json
from pathlib import Path
import tempfile
import unittest
import zipfile
from tools import legacy_port as port

ROOT=Path(__file__).resolve().parents[1]

class LegacyPortTests(unittest.TestCase):
    def test_pinned_native_enemies_and_exclusions(self):
        rows=port.combat_pins();ids={row['mod_id'] for row in rows}
        self.assertEqual(len(rows),8)
        self.assertTrue({'srparasites','epicsiegemod','hbm','storagenetwork'}<=ids)
        self.assertFalse({'mutationcraft','overgrown_vegetation','securitycraft'}&ids)

    def test_real_catalog_native_and_finite(self):
        doc=json.loads((ROOT/'legacy/pack/config/dead-earth/director/catalog.json').read_text(encoding='utf-8'))
        phases=json.loads((ROOT/'legacy/pack/config/dead-earth/srp-entities.json').read_text(encoding='utf-8'))['entities']
        self.assertEqual(len(doc['events']),15)
        self.assertGreaterEqual(len(doc['sounds']),60)
        self.assertGreaterEqual(len(doc['traces']),20)
        self.assertLessEqual(doc['rules']['maxActive'],3)
        self.assertLessEqual(doc['rules']['maxWave'],32)
        for wave in doc['waves'].values():
            for entity in wave['entities']:
                self.assertIn(entity,phases)
                self.assertTrue(0<=phases[entity]<=10)
        text=json.dumps(doc)
        self.assertNotIn('mutationcraft:',text)
        self.assertNotIn('overgrown_vegetation:',text)
        self.assertTrue(all(e['faction'] in {'parasites','siege','mixed'} for e in doc['events']))

    def test_digger_has_allowlist_and_no_independent_scaling(self):
        cfg=json.loads((ROOT/'legacy/pack/config/esm/entity_ai.json').read_text())
        for name in ('minecraft:zombie','minecraft:husk'):
            row=cfg['entities'][name]
            self.assertIs(row['Digging'],True)
            self.assertIs(row['Invert Blacklist'],True)
            self.assertNotIn('minecraft:chest',row['Digging Blacklist'])
            self.assertFalse(any(x.startswith('hbm:') for x in row['Digging Blacklist']))
            self.assertEqual(row['Health'],1)
            self.assertEqual(row['Damage'],1)
            self.assertIs(row['Boss Mod Damage'],False)

    def test_packaging_excludes_stubs_probes_worlds_and_secrets(self):
        for side in ('client','server'):
            data=port.entries(side)
            names=set(data)
            self.assertFalse(any('PortProbe' in n or 'world/' in n or 'dead_earth_bridge' in n or 'accounts.json' in n for n in names))
            own=next(value for name,value in data.items() if name.endswith(port.MOD_NAME))
            import io
            with zipfile.ZipFile(io.BytesIO(own)) as jar:
                self.assertFalse(any(n.startswith('net/') or n.endswith('KernelChecks.class') for n in jar.namelist()))
                self.assertIn('dev/deadearth/legacy/DeadEarthLegacy.class',jar.namelist())
                self.assertIn('dev/deadearth/legacy/core/LegacyCore.class',jar.namelist())
                self.assertTrue(any(n.endswith('.png') for n in jar.namelist()))
        self.assertNotEqual(port.entries('client').keys(),port.entries('server').keys())

    def test_coordinator_sources_use_java8_runtime_classfiles(self):
        import io,struct
        with zipfile.ZipFile(io.BytesIO(port.compiled_jar())) as jar:
            for name in jar.namelist():
                if name.endswith('.class'):
                    self.assertEqual(struct.unpack('>H',jar.read(name)[6:8])[0],52,name)

if __name__=='__main__':unittest.main()
