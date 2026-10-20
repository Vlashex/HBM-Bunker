"""Client-only admission of the owned Docker shared-loopback server. No game processes."""
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import uuid

from tools import verify_legacy_client as client


class ComposeManualClientTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.repo = Path(self.temp.name)
        (self.repo / 'work').mkdir()
        self.scope = patch.object(client, 'ROOT', self.repo)
        self.scope.start()
        self.addCleanup(self.scope.stop)

    def server(self, *, compose=True, outside=False):
        directory = self.repo / ('outside' if outside else 'work/server')
        directory.mkdir(parents=True)
        (directory / 'legacy-port.json').write_text(
            json.dumps(client.legacy_server.EXPECTED_MARKER), encoding='utf-8')
        (directory / 'server.properties').write_text(
            'server-ip=' + ('' if compose else '127.0.0.1') +
            '\nserver-port=25565\nlevel-name=world-current\nonline-mode=false\n', encoding='utf-8')
        if compose:
            self.marker = dict(schema=1, pack='DEAD-EARTH-SRP', network='shared-loopback',
                               deployment_id=str(uuid.uuid4()), world_name='world-current')
            self.save_marker(directory)
        return directory

    def save_marker(self, directory):
        (directory / 'compose-server.json').write_text(json.dumps(self.marker), encoding='utf-8')

    def change_properties(self, directory, old, new):
        path = directory / 'server.properties'
        path.write_text(path.read_text(encoding='utf-8').replace(old, new), encoding='utf-8')

    def test_explicit_compose_admission_does_not_weaken_native_supervisor(self):
        server = self.server()
        root, props, port = client.interactive_server(server)
        self.assertEqual(root, server.resolve())
        self.assertEqual(port, 25565)
        self.assertEqual(props['server-ip'], '')
        with self.assertRaises(ValueError):
            client.legacy_server.properties(server)

    def test_unmarked_blank_bind_and_marked_outside_work_are_rejected(self):
        server = self.server()
        (server / 'compose-server.json').unlink()
        with self.assertRaises(ValueError):
            client.interactive_server(server)
        outside = self.server(outside=True)
        with self.assertRaises(ValueError):
            client.interactive_server(outside)

    def test_wrong_or_ambiguous_marker_never_falls_back_to_native_policy(self):
        server = self.server()
        original = dict(self.marker)
        for key, value in [('schema', True), ('schema', 2), ('pack', 'OTHER'),
                           ('network', 'host'), ('deployment_id', 'not-a-uuid'),
                           ('world_name', 'some-other-world')]:
            with self.subTest(key=key, value=value):
                self.marker = dict(original, **{key: value})
                self.save_marker(server)
                with self.assertRaises(ValueError):
                    client.interactive_server(server)

    def test_wrong_bind_port_online_mode_and_escaped_world_fail_closed(self):
        server = self.server()
        path = server / 'server.properties'
        original = path.read_text(encoding='utf-8')
        for old, new in [('server-ip=\n', 'server-ip=0.0.0.0\n'),
                         ('server-port=25565', 'server-port=25587'),
                         ('online-mode=false', 'online-mode=true'),
                         ('level-name=world-current', 'level-name=../outside'),
                         ('level-name=world-current', r'level-name=..\outside'),
                         ('server-port=25565', 'server-port=25565\nserver-port=25565')]:
            with self.subTest(new=new):
                path.write_text(original.replace(old, new), encoding='utf-8')
                with self.assertRaises(ValueError):
                    client.interactive_server(server)

    def test_native_loopback_server_policy_still_accepts_its_owned_folder(self):
        server = self.server(compose=False, outside=True)
        _, props, port = client.interactive_server(server)
        self.assertEqual(props['server-ip'], '127.0.0.1')
        self.assertEqual(port, 25565)

    @unittest.skipUnless(os.name == 'nt', 'Prepared manual client is Windows-only')
    def test_compose_client_command_still_only_connects_to_loopback(self):
        server = self.server()
        target = self.repo / 'work/legacy-interactive-client-fixture'
        target.mkdir()
        (target / 'interactive-client.json').write_text(json.dumps(dict(
            schema=1, kind='dead-earth-interactive-client', username='Vesuk',
            serverDirectory=str(server), serverPort=25565, classpath=[], assetIndex='1.12',
            inputHashes={})), encoding='utf-8')
        with patch.object(client.legacy_server, 'java_executable', return_value=Path('java8.exe')),                patch.object(client.legacy_server, 'is_java8', return_value=True):
            command, _, _ = client.interactive_command(target, 'java8.exe')
        self.assertEqual(command[command.index('--server') + 1], '127.0.0.1')
        self.assertEqual(command[command.index('--port') + 1], '25565')
        self.assertNotIn('0.0.0.0', command)


if __name__ == '__main__':
    unittest.main()
