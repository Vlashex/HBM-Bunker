import unittest
from training.prompt_ab_cpu import prepare


class CpuPromptAbTests(unittest.TestCase):
    def test_primary_and_critic_share_case_but_have_distinct_strict_contracts(self):
        case=dict(context=dict(options=[dict(option=0),dict(option=1)]),review_notes='PRIVATE')
        primary=prepare(case,dict(role='primary',system='Pick {{schema}}'))
        critic=prepare(case,dict(role='critic',system='Check {{schema}}'),proposal=dict(option=1))
        self.assertEqual(primary['context'],case['context'])
        self.assertEqual(critic['context'],dict(context=case['context'],proposal=dict(option=1)))
        self.assertEqual(primary['schema']['required'],['option'])
        self.assertEqual(critic['schema']['required'],['option','reason_codes'])
        self.assertNotIn('PRIVATE',str(critic))
        self.assertNotIn('{{schema}}',critic['prompt'])

    def test_unknown_role_and_missing_or_disallowed_proposal_are_rejected(self):
        case=dict(context=dict(options=[dict(option=0)]))
        for arm,proposal in [(dict(role='other',system='{{schema}}'),None),
                             (dict(role='critic',system='{{schema}}'),None),
                             (dict(role='critic',system='{{schema}}'),dict(option=9))]:
            with self.assertRaises(ValueError):prepare(case,arm,proposal=proposal)


if __name__=='__main__':unittest.main()
