"""Docker ps distinguishes exposed container ports from published host mappings."""
import json
import unittest
from tools.verify_legacy_compose import Compose, CheckFailure


class PublishedPrivatePortTests(unittest.TestCase):
    def status(self, published):
        probe = object.__new__(Compose)
        probe.health_services = {'minecraft', 'ollama', 'director', 'admin'}
        rows = [dict(Service=name, State='running',
                     Health='healthy' if name != 'playit' else '', Publishers=[])
                for name in ('minecraft', 'ollama', 'director', 'admin', 'playit')]
        rows[1]['Publishers'] = [dict(URL='' if published == 0 else '127.0.0.1',
                                     TargetPort=11434, PublishedPort=published)]
        probe.call = lambda arguments: json.dumps(rows)
        return probe.status()

    def test_dockerfile_expose_without_host_mapping_is_private(self):
        result = self.status(0)
        self.assertEqual(result['running'], 5)
        self.assertEqual(result['healthy'], 4)
        self.assertTrue(result['private_backend_and_rcon'])

    def test_real_or_unknown_host_mapping_is_refused_even_on_loopback(self):
        for port in (11434, 25575, None, '0', False):
            with self.subTest(port=port), self.assertRaises(CheckFailure):
                self.status(port)


if __name__ == '__main__':
    unittest.main()
