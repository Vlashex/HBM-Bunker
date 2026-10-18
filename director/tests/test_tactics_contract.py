import unittest
import importlib

class TacticsContractTests(unittest.TestCase):
    def test_training_and_inference_share_strict_option_contract(self):
        spec=importlib.util.find_spec('dead_earth_director.tactics')
        self.assertIsNotNone(spec,'shared training/inference contract is missing')
        mod=importlib.import_module('dead_earth_director.tactics')
        self.assertEqual(mod.parse_selection('{"option":2}',3),dict(option=2))
        for raw in ('{"option":true}','{"option":3}','{"option":-1}','{"option":0,"option":1}','{"option":1,"command":"stop"}'):
            with self.subTest(raw=raw),self.assertRaises(ValueError):mod.parse_selection(raw,3)
        self.assertEqual(mod.selection_schema(3)['properties']['option']['enum'],[0,1,2])
        self.assertIn('event',mod.selection_prompt(3))
        for count in (True,0,9):
            with self.subTest(count=count),self.assertRaises(ValueError):mod.selection_schema(count)
