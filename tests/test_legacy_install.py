import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from tools import legacy_install as install


SEEDS = ('copper', 'aluminum', 'lead', 'titanium', 'tungsten', 'sulfur', 'saltpeter')
MA = 'seeds {\n' + ''.join('    I:' + name + '_seeds=2\n' for name in SEEDS) + '}\n'


class Merges(unittest.TestCase):
    def test_ma_preserves_unrelated_categories_and_seed_settings(self):
        before = '# preserved header\r\ngeneral {\r\n    B:enabled=false\r\n}\r\nseeds {\r\n    I:iron_seeds=0\r\n    I:copper_seeds=1 # operator note\r\n}\r\nother {\r\n    I:value=42\r\n}\r\n'
        result = install.merge_ma_seeds(before, MA)
        self.assertIn('I:iron_seeds=0', result)
        self.assertIn('B:enabled=false', result)
        self.assertIn('I:value=42', result)
        self.assertIn('I:copper_seeds=2 # operator note', result)
        self.assertNotIn('\r\r\n', result)
        for name in SEEDS:
            self.assertEqual(result.count('I:' + name + '_seeds='), 1)

    def test_ma_missing_category_appends_and_duplicate_category_fails(self):
        result = install.merge_ma_seeds('general {\n B:enabled=false\n}\n', MA)
        self.assertIn('B:enabled=false', result)
        self.assertEqual(result.count('seeds {'), 1)
        with self.assertRaises(ValueError):
            install.merge_ma_seeds('seeds {\n}\nseeds {\n}\n', MA)

    def test_preserve_coordinator_knobs_but_replace_generated_prices(self):
        result = install.decode(install.merge_json(install.encode({'seasonTicks': 123, 'resourcePrices': {'wrong': 1}}),
            install.encode({'seasonTicks': 500, 'newKnob': 9, 'resourcePrices': {'hbm:ingot_copper': 128}}), 'coordinator'))
        self.assertEqual(result, {'seasonTicks': 123, 'newKnob': 9, 'resourcePrices': {'hbm:ingot_copper': 128}})

    def test_farming_defaults_merge_and_unknown_keys_refused(self):
        result = install.decode(install.merge_json(b'{"cycleTicks":333}', b'{"cycleTicks":1200,"acceleratorDepth":8}', 'farming'))
        self.assertEqual(result, {'cycleTicks': 333, 'acceleratorDepth': 8})
        with self.assertRaises(ValueError):
            install.merge_json(b'{"unrecognized":true}', b'{"cycleTicks":1200}', 'farming')


class Installer(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.server = self.root / 'work/server'
        self.client = self.root / 'work/client'
        self.server.mkdir(parents=True)
        (self.client / 'game').mkdir(parents=True)
        self.old_name = 'dead-earth-srp-coordinator-1.0.4.jar'
        self.new_name = install.legacy_port.MOD_NAME
        self.old = b'old coordinator fixture'
        self.new = b'new coordinator fixture'
        for base in (self.server, self.client / 'game'):
            (base / 'mods').mkdir()
            (base / 'mods' / self.old_name).write_bytes(self.old)
            (base / 'config/dead-earth').mkdir(parents=True)
            (base / 'config/dead-earth/coordinator.json').write_bytes(install.encode({'seasonTicks': 123, 'resourcePrices': {'wrong': 1}}))
            (base / 'config/dead-earth/farming.json').write_bytes(b'{"cycleTicks":333}')
            (base / 'config/mysticalagriculture.cfg').write_text('general {\n B:keep=true\n}\nseeds {\n I:iron_seeds=0\n}\n')
            (base / 'private-sentinel.txt').write_bytes(b'private data remains')
        (self.server / 'world/region').mkdir(parents=True)
        (self.server / 'world/region/region.mca').write_bytes(b'unmodified world')
        (self.server / 'server.properties').write_bytes(b'private rcon key')
        (self.server / 'compose-server.json').write_bytes(install.encode(dict(schema=1, pack='DEAD-EARTH-SRP', network='shared-loopback', source_version='1.0.4')))
        (self.server / 'legacy-port.json').write_bytes(install.encode(dict(schema=1, pack='DEAD-EARTH-SRP', runtime='forge-1.12.2-14.23.5.2860', kind='full-fork', version='1.0.4')))
        library = self.root / '.cache/client/runtime.jar'
        library.parent.mkdir(parents=True)
        library.write_bytes(b'unchanged runtime')
        self.client_marker = dict(schema=1, kind='dead-earth-interactive-client', username='Tester',
            serverDirectory=str(self.server), serverPort=25565, classpath=[str(library)],
            inputHashes={str(library): install.digest(library.read_bytes()),
                         str(self.client / 'game/mods' / self.old_name): install.digest(self.old)})
        (self.client / 'interactive-client.json').write_bytes(install.encode(self.client_marker))
        source = {
            'mods/' + self.new_name: self.new,
            'mods/new-dependency.jar': b'new pinned mod fixture',
            'config/dead-earth/coordinator.json': install.encode({'seasonTicks': 72000, 'newKnob': 9, 'resourcePrices': {'hbm:ingot_copper': 128}}),
            'config/dead-earth/farming.json': install.encode({'cycleTicks': 1200, 'acceleratorDepth': 8}),
            'config/mysticalagriculture.cfg': MA.encode(),
            'director/dead_earth_director/host.py': b'# updated source\n',
            'director/director.example.toml': b'MUST NOT REPLACE DOCKER ENDPOINT',
            'config/unrelated.cfg': b'MUST NOT OVERWRITE',
        }
        def entries(side, root):
            return source if side == 'server' else {'.minecraft/' + key: value for key, value in source.items()}
        self.addCleanup(patch.stopall)
        patch.object(install.legacy_port, 'entries', side_effect=entries).start()
        patch.object(install.legacy_port, 'compiled_jar', return_value=self.new).start()
        patch.object(install, 'BASELINE_JARS', {self.old_name: install.digest(self.old)}).start()

    def plan(self):
        return install.plan(self.server, self.client, root=self.root)

    def test_dry_plan_does_not_mutate_or_include_private_files(self):
        before = {str(p): p.read_bytes() for p in self.root.rglob('*') if p.is_file()}
        prepared = self.plan()
        after = {str(p): p.read_bytes() for p in self.root.rglob('*') if p.is_file()}
        self.assertEqual(before, after)
        names = {op['path'] for op in prepared['writes']}
        self.assertNotIn('server.properties', names)
        self.assertNotIn('director/director.example.toml', names)
        self.assertNotIn('config/unrelated.cfg', names)

    def test_apply_updates_owned_data_and_archives_only_verified_old_jar(self):
        prepared = self.plan()
        with patch.object(install, 'assert_stopped'):
            result = install.apply(prepared, root=self.root)
        backup = Path(result['backup'])
        self.assertTrue(backup.is_relative_to(self.root / 'work'))
        self.assertEqual((backup / 'server/mods' / self.old_name).read_bytes(), self.old)
        self.assertEqual((backup / 'client/game/mods' / self.old_name).read_bytes(), self.old)
        self.assertFalse((self.server / 'mods' / self.old_name).exists())
        self.assertEqual((self.server / 'mods' / self.new_name).read_bytes(), self.new)
        self.assertEqual((self.server / 'server.properties').read_bytes(), b'private rcon key')
        self.assertEqual((self.server / 'world/region/region.mca').read_bytes(), b'unmodified world')
        self.assertEqual((self.server / 'private-sentinel.txt').read_bytes(), b'private data remains')
        cfg = install.read(self.server / 'config/dead-earth/coordinator.json')
        self.assertEqual(cfg['seasonTicks'], 123)
        self.assertEqual(cfg['resourcePrices'], {'hbm:ingot_copper': 128})
        client = install.read(self.client / 'interactive-client.json')
        self.assertEqual(client['serverDirectory'], str(self.server))
        self.assertIn(str(self.client / 'game/mods' / self.new_name), client['inputHashes'])
        self.assertNotIn(str(self.client / 'game/mods' / self.old_name), client['inputHashes'])
        for filename, expected in client['inputHashes'].items():
            self.assertEqual(install.digest(Path(filename).read_bytes()), expected)
        self.assertEqual(len(self.plan()['archivePreviousJars']), 0)

    def test_modified_old_jar_is_never_archived(self):
        (self.server / 'mods' / self.old_name).write_bytes(b'unrecognized bytes')
        with self.assertRaisesRegex(ValueError, 'Unrecognized previous'):
            self.plan()

    def test_plan_tampering_or_target_drift_refused_before_apply(self):
        prepared = self.plan()
        prepared['writes'][0]['path'] = 'world/region/region.mca'
        with self.assertRaisesRegex(ValueError, 'changed'):
            install.apply(prepared, root=self.root)
        prepared = self.plan()
        (self.server / 'config/dead-earth/farming.json').write_bytes(b'{"cycleTicks":444}')
        with self.assertRaisesRegex(ValueError, 'changed'):
            install.apply(prepared, root=self.root)

    def test_stopped_guard_uses_desktop_context_for_list_and_inspect(self):
        prepared = install.plan(server=self.server, root=self.root)
        from types import SimpleNamespace
        class ClosedSocket:
            def __enter__(self): return self
            def __exit__(self, *args): pass
            def settimeout(self, value): pass
            def connect_ex(self, address): return 1
        results = [SimpleNamespace(returncode=0, stdout='container123\n'),
                   SimpleNamespace(returncode=0, stdout='[]')]
        with patch.object(install.socket, 'socket', return_value=ClosedSocket()), \
             patch.object(install.subprocess, 'run', side_effect=results) as run:
            install.assert_stopped(prepared)
        self.assertEqual(run.call_args_list[0].args[0], ['docker', '--context', 'desktop-linux', 'ps', '-q'])
        self.assertEqual(run.call_args_list[1].args[0], ['docker', '--context', 'desktop-linux', 'inspect', 'container123'])

    def test_escape_and_live_client_refused(self):
        with self.assertRaises(ValueError):
            install.owned(self.root, self.root)
        with self.assertRaises(ValueError):
            install.child(self.server, '../outside')
        prepared = install.plan(client=self.client, connect_server=self.server, root=self.root)
        (self.client / 'interactive-client-process.json').write_bytes(install.encode({'pid': 123}))
        with patch.object(install, '_pid_running', return_value=True):
            with self.assertRaisesRegex(ValueError, 'Close'):
                install.assert_stopped(prepared)


if __name__ == '__main__':
    unittest.main()
