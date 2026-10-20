import contextlib
import io
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest import mock
import uuid

from tools import legacy_server as server


class LegacyServerTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix='dead-earth-owned-launcher-')
        self.boundary = Path(self.temporary.name).resolve()
        self.root = self.boundary / 'server'
        self.root.mkdir()
        (self.root / 'legacy-port.json').write_text(json.dumps(server.EXPECTED_MARKER), encoding='utf-8')

    def tearDown(self):
        # The fixture is the only recursively removed directory, never a repository/server path.
        self.assertEqual(Path(self.temporary.name).resolve(), self.boundary)
        self.assertEqual(self.root.resolve().parent, self.boundary)
        self.temporary.cleanup()

    def write_properties(self, extra='', game_port=True):
        text = 'server-ip=127.0.0.1\n'
        if game_port: text += 'server-port=25587\n'
        (self.root / 'server.properties').write_text(text + extra + '\n', encoding='utf-8')

    def test_java_unicode_escaped_world_is_rejected(self):
        self.write_properties(r'level-name=world/\u002e\u002e/\u002e\u002e/other-world')
        with self.assertRaisesRegex(ValueError, 'Ambiguous server.properties'):
            server.properties(self.root)

    def test_minecraft_escaped_generator_settings_and_motd_are_accepted(self):
        generator = r'3;minecraft\:bedrock,2*minecraft\:dirt,minecraft\:grass;1;village'
        motd = r'DEAD EARTH\: local\nworld'
        self.write_properties('level-name=world\ngenerator-settings=' + generator + '\nmotd=' + motd)
        values, world, port = server.properties(self.root)
        self.assertEqual(world, self.root / 'world')
        self.assertEqual(port, 25587)
        self.assertEqual(values['generator-settings'], generator)
        self.assertEqual(values['motd'], motd)

    def test_escaped_values_in_all_critical_settings_are_rejected(self):
        critical = ('server-ip', 'server-port', 'level-name', 'enable-rcon', 'rcon.port',
                    'rcon.password', 'online-mode', 'white-list', 'enforce-whitelist')
        baseline = {'server-ip': '127.0.0.1', 'server-port': '25587', 'level-name': 'world',
                    'enable-rcon': 'true', 'rcon.port': '25588', 'rcon.password': 'private',
                    'online-mode': 'true', 'white-list': 'false', 'enforce-whitelist': 'false'}
        for key in critical:
            with self.subTest(key=key):
                values = dict(baseline)
                values[key] += r'\u0031'
                (self.root / 'server.properties').write_text(
                    '\n'.join(name + '=' + value for name, value in values.items()) + '\n', encoding='utf-8')
                with self.assertRaisesRegex(ValueError, 'Ambiguous server.properties'):
                    server.properties(self.root)

    def test_escaped_keys_are_rejected_even_for_noncritical_properties(self):
        self.write_properties(r'generator\u002dsettings=ordinary')
        with self.assertRaisesRegex(ValueError, 'Ambiguous server.properties'):
            server.properties(self.root)

    def test_unread_value_line_continuation_is_rejected_before_key_injection(self):
        self.write_properties('motd=welcome' + '\\' + '\nlevel-name=../other-world')
        with self.assertRaisesRegex(ValueError, 'Ambiguous server.properties'):
            server.properties(self.root)

    def test_plain_world_outside_owned_root_is_rejected(self):
        self.write_properties('level-name=../other-world')
        with self.assertRaisesRegex(ValueError, 'Unsafe world directory'):
            server.properties(self.root)

    def test_real_junction_or_symlink_root_is_rejected(self):
        alias = self.boundary / 'server-alias'
        if os.name == 'nt':
            import _winapi
            _winapi.CreateJunction(str(self.root), str(alias))
        else:
            alias.symlink_to(self.root, target_is_directory=True)
        try:
            with self.assertRaisesRegex(ValueError, 'without junction aliases'):
                server.owned_root(alias)
            self.assertTrue((self.root / 'legacy-port.json').is_file())
        finally:
            # Remove only the alias itself; never recursively traverse a junction.
            if os.name == 'nt': alias.rmdir()
            else: alias.unlink()

    def test_stop_with_stale_receipt_does_not_signal_any_pid(self):
        directory = server.runtime_dir(self.root)
        stale = dict(schema=1, root=str(self.root), run_id=str(uuid.uuid4()),
                     state='ready', supervisor_pid=os.getpid(), server_pid=os.getpid())
        receipt = directory / 'receipt.json'
        receipt.write_text(json.dumps(stale), encoding='utf-8')
        with mock.patch.object(server.subprocess, 'Popen') as spawn, \
                mock.patch.object(server.os, 'kill') as signal, \
                mock.patch.object(server, 'atomic_json') as write, \
                contextlib.redirect_stdout(io.StringIO()):
            result = server.main(['stop', '--root', str(self.root), '--timeout', '1'])
        self.assertEqual(result, 1)
        spawn.assert_not_called(); signal.assert_not_called(); write.assert_not_called()
        self.assertEqual(json.loads(receipt.read_text(encoding='utf-8')), stale)
        self.assertFalse((directory / 'stop.request.json').exists())

    def test_stop_without_any_session_returns_without_request(self):
        with contextlib.redirect_stdout(io.StringIO()):
            result = server.main(['stop', '--root', str(self.root), '--timeout', '1'])
        self.assertEqual(result, 1)
        self.assertFalse((self.root / 'runtime/legacy-server/stop.request.json').exists())

    def test_missing_game_port_uses_isolated_fork_default(self):
        self.write_properties('level-name=world', game_port=False)
        _, world, port = server.properties(self.root)
        self.assertEqual(port, 25587)
        self.assertEqual(world, self.root / 'world')

    def test_default_rcon_port_conflict_is_rejected(self):
        self.write_properties('server-port=25588\nlevel-name=world\nenable-rcon=true\nrcon.password=private', game_port=False)
        with self.assertRaisesRegex(ValueError, 'separate valid loopback port'):
            server.properties(self.root)


if __name__ == '__main__':
    unittest.main()
