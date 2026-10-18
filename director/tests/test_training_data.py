import copy
from collections import Counter, defaultdict
import hashlib
import importlib
import json
from pathlib import Path
import tempfile
import unittest


CATALOG = Path(__file__).resolve().parents[2] / 'pack/config/dead-earth/director/catalog.json'


class TrainingDataTests(unittest.TestCase):
    def modules(self):
        # An assertion makes the first RED report the missing feature explicitly.
        self.assertIsNotNone(importlib.util.find_spec('training'), 'offline curriculum package is missing')
        return importlib.import_module('training.dataset'), importlib.import_module('training.evaluation')

    def test_reproducible_files_and_family_split_before_variations(self):
        dataset, _ = self.modules()
        with tempfile.TemporaryDirectory() as root:
            first, second = Path(root) / 'first', Path(root) / 'second'
            manifest = dataset.write_dataset(CATALOG, first, seed=1907, variants_per_family=4)
            dataset.write_dataset(CATALOG, second, seed=1907, variants_per_family=4)
            for name in ('train.jsonl', 'validation.jsonl', 'test.jsonl', 'manifest.json'):
                self.assertEqual((first / name).read_bytes(), (second / name).read_bytes())
            all_rows = []
            families, ids, contexts = {}, set(), set()
            for split in ('train', 'validation', 'test'):
                rows = dataset.load_rows(first / (split + '.jsonl'))
                self.assertEqual(len(rows), manifest['splits'][split]['count'])
                self.assertEqual(hashlib.sha256((first / (split + '.jsonl')).read_bytes()).hexdigest(), manifest['splits'][split]['sha256'])
                for row in rows:
                    self.assertEqual(split, row['split'])
                    self.assertEqual(families.setdefault(row['family'], split), split)
                    self.assertNotIn(row['id'], ids)
                    self.assertNotIn(row['provenance']['context_sha256'], contexts)
                    ids.add(row['id'])
                    contexts.add(row['provenance']['context_sha256'])
                all_rows.extend(rows)
            changed = dataset.generate_rows(CATALOG, seed=1908, variants_per_family=4)
            self.assertNotEqual({r['provenance']['context_sha256'] for r in changed}, contexts)
            self.assertEqual('synthetic_policy', manifest['kind'])
            self.assertEqual('weak', manifest['label_strength'])
            self.assertEqual(len(all_rows), sum(s['count'] for s in manifest['splits'].values()))

    def test_public_context_coverage_and_balanced_permutations(self):
        dataset, _ = self.modules()
        rows = dataset.generate_rows(CATALOG, seed=72, variants_per_family=6)
        events = json.loads(CATALOG.read_text(encoding='utf-8'))['events']
        self.assertEqual({e['id'] for e in events}, {o['event'] for r in rows for o in r['context']['options']})
        self.assertEqual(set(range(6)), {r['context']['parasite_phase'] for r in rows})
        self.assertEqual({'CALM', 'ASH_WINTER', 'HEAT'}, {r['context']['season'] for r in rows})
        balances = defaultdict(Counter)
        groups = defaultdict(list)
        for row in rows:
            context = row['context']
            self.assertEqual({'season', 'pressure', 'parasite_phase', 'options', 'recent'}, set(context))
            if context['parasite_phase'] == 0:
                self.assertTrue(all(o['faction'] == 'forest' for o in context['options']))
            self.assertIn(row['target']['option'], row['acceptable_options'])
            balances[(row['split'], len(context['options']))][row['target']['option']] += 1
            groups[row['provenance']['permutation_group']].append(row)
        for (_, count), histogram in balances.items():
            self.assertEqual(set(range(count)), set(histogram))
            self.assertEqual(1, len(set(histogram.values())))
        for members in groups.values():
            chosen = {r['context']['options'][r['target']['option']]['event'] for r in members}
            self.assertEqual(1, len(chosen))
            self.assertEqual(1, len({(r['family'], r['split']) for r in members}))

    def test_teacher_avoids_repelled_event_and_ignores_unknown_history(self):
        dataset, _ = self.modules()
        events = json.loads(CATALOG.read_text(encoding='utf-8'))['events']
        options = []
        for index, event_id in enumerate(('flank_rustle', 'spore_lure')):
            event = next(e for e in events if e['id'] == event_id)
            options.append(dict(option=index, event=event_id, **{k: event[k] for k in ('title', 'tactic', 'faction', 'weight')}))
        context = dict(season='CALM', pressure=1.2, parasite_phase=2, options=options,
                       recent=[dict(event='flank_rustle', outcome='repelled')] * 2)
        label = dataset.label_context(context, events)
        self.assertEqual({'option': 1}, label['target'])
        self.assertIn('repelled', label['rationale'])
        context['recent'] = []
        baseline = dataset.label_context(context, events)
        context['recent'] = [dict(event='retired_event', outcome='repelled')]
        self.assertEqual(baseline['target'], dataset.label_context(context, events)['target'])
        context['options'] = [dict(options[0], option=0)]
        self.assertEqual({'option': 0}, dataset.label_context(context, events)['target'])

    def test_strict_loader_rejects_leaks_invalid_labels_and_duplicate_json(self):
        dataset, _ = self.modules()
        row = dataset.generate_rows(CATALOG, seed=2, variants_per_family=1)[0]
        with tempfile.TemporaryDirectory() as root:
            path = Path(root) / 'rows.jsonl'
            path.write_text(json.dumps(row) + '\n', encoding='utf-8')
            self.assertEqual([row], dataset.load_rows(path))
            invalid = []
            leaked = copy.deepcopy(row)
            leaked['context']['zoneId'] = 'private-zone'
            invalid.append(json.dumps(leaked))
            bad_label = copy.deepcopy(row)
            bad_label['target']['option'] = True
            invalid.append(json.dumps(bad_label))
            invalid.append(json.dumps(row).replace('"pressure":', '"pressure": NaN, "pressure":', 1))
            invalid.append(json.dumps(row).replace('"id":', '"id": "duplicate", "id":', 1))
            invalid.append(json.dumps(row) + '\n' + json.dumps(row))
            for raw in invalid:
                path.write_text(raw + '\n', encoding='utf-8')
                with self.subTest(raw=raw[:80]), self.assertRaises(ValueError):
                    dataset.load_rows(path)

    def test_evaluation_counts_invalid_outputs_and_acceptable_labels(self):
        dataset, evaluation = self.modules()
        rows = dataset.generate_rows(CATALOG, seed=7, variants_per_family=1)
        perfect = {r['id']: json.dumps(r['target']) for r in rows}
        report = evaluation.score_rows(rows, perfect)
        self.assertEqual(1, report['policy_agreement'])
        self.assertEqual(1, report['json_valid_rate'])
        self.assertEqual(1, report['contract_valid_rate'])
        self.assertEqual(len(rows), sum(v['total'] for v in report['per_family'].values()))
        selected = rows[:4]
        bad = {selected[0]['id']: '{"option": true}', selected[1]['id']: 'not json',
               selected[2]['id']: {'option': 100}, selected[3]['id']: {'option': 0, 'command': 'no'}}
        invalid = evaluation.score_rows(selected, bad)
        self.assertEqual(0, invalid['policy_agreement'])
        self.assertEqual(.75, invalid['json_valid_rate'])
        self.assertEqual(0, invalid['contract_valid_rate'])
        self.assertEqual(4, invalid['total'])
        oversized = evaluation.score_rows([rows[0]], {rows[0]['id']: ' ' * 513 + json.dumps(rows[0]['target'])})
        self.assertEqual(1, oversized['json_valid_rate'])
        self.assertEqual(0, oversized['contract_valid_rate'], 'runtime rejects answers over 512 UTF-8 bytes')

    def test_permutation_metric_compares_event_identity_not_index(self):
        dataset, evaluation = self.modules()
        rows = dataset.generate_rows(CATALOG, seed=7, variants_per_family=1)
        group = next(r['provenance']['permutation_group'] for r in rows if len(r['context']['options']) > 1)
        members = [r for r in rows if r['provenance']['permutation_group'] == group]
        correct = evaluation.score_rows(members, {r['id']: r['target'] for r in members})
        position_bias = evaluation.score_rows(members, {r['id']: {'option': 0} for r in members})
        self.assertEqual(1, correct['permutation']['same_event_rate'])
        self.assertEqual(0, position_bias['permutation']['same_event_rate'])
        self.assertEqual(1, correct['permutation']['groups'])


if __name__ == '__main__':
    unittest.main()
