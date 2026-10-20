import json
from pathlib import Path
import unittest
from tools import legacy_siege

ROOT = Path(__file__).resolve().parents[1]


class LegacySiegeDataTests(unittest.TestCase):
    def test_native_entity_profiles_are_complete_and_current(self):
        expected = legacy_siege.generate(ROOT)
        self.assertEqual((ROOT / legacy_siege.TARGET).read_bytes(), expected)
        document = json.loads(expected)
        rows = document['entities']
        self.assertIn('minecraft:mooshroom', rows)
        self.assertNotIn('minecraft:mushroom_cow', rows)
        self.assertTrue(rows['minecraft:mooshroom']['Animal Retaliate'])
        for name, profile in rows.items():
            with self.subTest(entity=name):
                self.assertEqual(set(profile), set(document['default']))
                self.assertEqual(profile['Health'], 1)
                self.assertEqual(profile['Xray Distance'], 0)
                self.assertEqual(profile['Digging Blacklist'], document['default']['Digging Blacklist'])


if __name__ == '__main__':
    unittest.main()
