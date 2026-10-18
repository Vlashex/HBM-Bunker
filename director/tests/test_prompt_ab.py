import importlib
import json
from pathlib import Path
import tempfile
import unittest


class PromptAbTests(unittest.TestCase):
    def module(self):
        self.assertIsNotNone(importlib.util.find_spec('training.prompt_ab'),'Paired prompt experiment runner is missing')
        return importlib.import_module('training.prompt_ab')

    def test_grid_pairs_every_model_prompt_case_and_blind_pack_has_no_identity(self):
        m=self.module()
        suite=dict(schema=1,prompts=[dict(id='A',system='Alpha {{schema}}'),dict(id='B',system='Beta {{schema}}')],
            cases=[dict(id='new_case',title='Fresh',review_notes='PRIVATE REVIEW CRITERIA',
                context=dict(season='CALM',pressure=.3,options=[dict(option=0,event='one')],recent=[]))])
        jobs=m.jobs(suite,seed=17)
        self.assertEqual(len(jobs),4)
        self.assertEqual({(j['model'],j['prompt_id'],j['case_id']) for j in jobs},
            {('base','A','new_case'),('base','B','new_case'),('adapter','A','new_case'),('adapter','B','new_case')})
        self.assertEqual(jobs,m.jobs(suite,seed=17))
        rows=[dict(response_id=j['response_id'],case_id=j['case_id'],model=j['model'],prompt_id=j['prompt_id'],raw='{"option":0}') for j in jobs]
        blind=m.blind_pack(suite,rows)
        self.assertEqual(len(blind[0]['responses']),4)
        self.assertTrue(all(set(r)=={'response_id','raw'} for r in blind[0]['responses']))
        self.assertIn('review_notes',blind[0])

    def test_generation_messages_exclude_review_notes_and_do_not_prefill_answer(self):
        m=self.module()
        case=dict(context=dict(options=[dict(option=0,event='a'),dict(option=1,event='b')]),review_notes='PRIVATE REVIEW CRITERIA')
        messages=m.messages(case,dict(system='Choose. {{schema}}'))
        self.assertEqual([item['role'] for item in messages],['system','user'])
        self.assertNotIn('PRIVATE',json.dumps(messages))
        self.assertEqual(json.loads(messages[1]['content']),case['context'])
        self.assertIn('"enum": [0, 1]',messages[0]['content'])

    def test_collected_responses_have_only_technical_validation_and_no_policy_grade(self):
        m=self.module()
        valid=m.technical_response('{"option":1}',2)
        invalid=m.technical_response('{"option":99}',2)
        self.assertEqual(valid,dict(contract_valid=True,option=1))
        self.assertEqual(invalid,dict(contract_valid=False,option=None))
        self.assertNotIn('score',valid)

    def test_suite_cannot_smuggle_reference_answers_to_the_model(self):
        m=self.module()
        with tempfile.TemporaryDirectory() as tmp:
            p=Path(tmp)/'suite.json'
            value=dict(schema=1,prompts=[dict(id='A',system='{{schema}}')],cases=[dict(id='case',title='Title',review_notes='Notes',context=dict(options=[dict(option=0,event='one')]),target=dict(option=0))])
            p.write_text(json.dumps(value),encoding='utf-8')
            with self.assertRaises(ValueError):m.load_suite(p)


if __name__=='__main__':unittest.main()
