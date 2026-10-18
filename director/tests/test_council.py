import json
from pathlib import Path
import tempfile
import time
import unittest
from types import SimpleNamespace
from unittest.mock import patch

from dead_earth_director.council import CouncilModel, CouncilSettings, parse_critique
from dead_earth_director.host import CinematicDirector, Journal, DirectorError, build_model, council_deadline, load_config
from dead_earth_director.bridge import BridgeError
from dead_earth_director.model import ModelError, ModelSettings, OllamaModel
from test_director import DIGEST, model_endpoint


CRITIC_DIGEST = 'b' * 64
CONTEXT = dict(season='CALM', pressure=.5, parasite_phase=2,
               options=[dict(option=0, event='scout', tactic='reconnaissance'),
                        dict(option=1, event='trap', tactic='feint')], recent=[])


def settings(url='http://127.0.0.1:11434', **changes):
    return CouncilSettings(url, 'qwen3.5:4b', DIGEST, ai_mode='dual',
                           critic=ModelSettings(url, 'qwen3.5:2b-q4_K_M'.lower(), CRITIC_DIGEST), **changes)


class Clock:
    def __init__(self): self.value = 100.
    def __call__(self): return self.value


class FakeModel:
    def __init__(self, model, digest, clock, answers):
        self.settings = ModelSettings('http://127.0.0.1:11434', model, digest)
        self.clock, self.answers, self.calls = clock, list(answers), []

    def invoke(self, kind, context, deadline):
        self.calls.append((kind, context, deadline))
        answer, seconds = self.answers.pop(0)
        self.clock.value += seconds
        if isinstance(answer, Exception): raise answer
        return answer, dict(prompt_tokens=100, output_tokens=5, raw_chat='PRIVATE', thinking='PRIVATE')

    def select(self, context, *, deadline=None): return self.invoke('proposal', context, deadline)
    def critique(self, context, proposal, *, deadline=None): return self.invoke('critique', context, deadline)
    def revise(self, context, proposal, critique, *, deadline=None): return self.invoke('revision', context, deadline)


class CouncilTests(unittest.TestCase):
    def council(self, primary, critic, **kwargs):
        clock = Clock()
        a = FakeModel('qwen3.5:4b', DIGEST, clock, primary)
        b = FakeModel('qwen3.5:2b-q4_k_m', CRITIC_DIGEST, clock, critic)
        return CouncilModel(a, b, settings(), clock=clock), a, b, clock

    def test_one_bounded_revision_uses_same_deadline_and_no_raw_reasoning(self):
        model, a, b, _ = self.council([({'option': 0}, 12), ({'option': 1}, 10)],
                                    [({'option': 1, 'reason_codes': ['repetition']}, 8)])
        result, metrics = model.select(CONTEXT, deadline=150)
        self.assertEqual(result, {'option': 1})
        self.assertEqual([call[0] for call in a.calls], ['proposal', 'revision'])
        self.assertEqual(len(b.calls), 1)
        self.assertEqual([call[2] for call in a.calls + b.calls], [150, 150, 150])
        self.assertEqual([stage['remaining_ms'] for stage in metrics['stages']], [38000, 30000, 20000])
        self.assertEqual(metrics['revision_count'], 1)
        self.assertNotIn('PRIVATE', json.dumps(metrics))

    def test_agreement_skips_revision(self):
        model, a, _, _ = self.council([({'option': 0}, 1)], [({'option': 0, 'reason_codes': []}, 1)])
        value, metrics = model.select(CONTEXT)
        self.assertEqual(value, {'option': 0})
        self.assertEqual(len(a.calls), 1)
        self.assertEqual(metrics['revision_count'], 0)

    def test_bad_or_timed_out_critic_retains_first_valid_proposal(self):
        for bad in (ModelError('model_timeout'), ModelError('PRIVATE'), {'option': 99, 'reason_codes': []},
                    {'option': 1, 'reason_codes': ['send_command']}):
            with self.subTest(bad=bad):
                model, a, _, _ = self.council([({'option': 0}, 1)], [(bad, 2)])
                value, metrics = model.select(CONTEXT)
                self.assertEqual(value, {'option': 0}); self.assertEqual(len(a.calls), 1)
                self.assertNotIn('PRIVATE', json.dumps(metrics))
                self.assertEqual(metrics['stages'][-1]['status'], 'error')

    def test_late_revision_or_invalid_revision_retains_a_not_critic_choice(self):
        for answer, seconds in (({'option': 1}, 89), ({'option': True}, 1), (ModelError('model_unavailable'), 1)):
            model, _, _, _ = self.council([({'option': 0}, 1), (answer, seconds)],
                                          [({'option': 1, 'reason_codes': ['pressure']}, 1)])
            value, metrics = model.select(CONTEXT)
            self.assertEqual(value, {'option': 0})
            self.assertEqual(metrics['revision_count'], 1)

    def test_primary_failure_stays_modelerror_for_existing_server_fallback(self):
        model, _, b, _ = self.council([(ModelError('model_timeout'), 1)], [])
        with self.assertRaisesRegex(ModelError, 'model_timeout') as failed: model.select(CONTEXT)
        self.assertEqual(b.calls, [])
        self.assertEqual(failed.exception.metrics['ai_mode'], 'dual')
        self.assertEqual(failed.exception.metrics['stages'][0]['error_code'], 'model_timeout')

    def test_exhausted_deadline_does_not_start_another_model(self):
        model, a, b, _ = self.council([({'option': 0}, 5)], [])
        value, metrics = model.select(CONTEXT, deadline=105)
        self.assertEqual(value, {'option': 0}); self.assertEqual(b.calls, [])
        self.assertEqual(metrics['stages'][-1]['status'], 'skipped')
        self.assertEqual(metrics['stages'][-1]['remaining_ms'], 0)

    def test_critique_json_rejects_extra_authority_duplicate_keys_and_unbounded_reasons(self):
        self.assertEqual(parse_critique('{"option":1,"reason_codes":["season"]}', 2)['option'], 1)
        for raw in ('{"option":0,"option":1,"reason_codes":[]}', '{"option":true,"reason_codes":[]}',
                    '{"option":1,"reason_codes":["season","season"]}', '{"option":1,"reason_codes":[],"command":"x"}',
                    '{"option":2,"reason_codes":[]}', '{"option":1,"reason_codes":["PRIVATE"]}'):
            with self.subTest(raw=raw), self.assertRaises(ModelError): parse_critique(raw, 2)

    def test_real_http_round_uses_pinned_cpu_models_schema_and_unload(self):
        chats = []
        def respond(path, body):
            if path == '/api/tags': return 200, dict(models=[dict(name='qwen3.5:4b', digest=DIGEST), dict(name='qwen3.5:2b-q4_k_m', digest=CRITIC_DIGEST)])
            chats.append(body)
            content = ({'option': 0} if len(chats) == 1 else
                       {'option': 1, 'reason_codes': ['better_alternative']} if len(chats) == 2 else {'option': 1})
            return 200, dict(done=True, done_reason='stop', message=dict(content=json.dumps(content), thinking='PRIVATE'), prompt_eval_count=100, eval_count=8)
        with model_endpoint(respond) as (url, _):
            model = build_model(settings(url), 'live')
            value, metrics = model.select(CONTEXT)
            self.assertEqual(value, {'option': 1})
            self.assertEqual([body['model'] for body in chats], ['qwen3.5:4b', 'qwen3.5:2b-q4_k_m', 'qwen3.5:4b'])
            for body in chats:
                self.assertEqual(body['keep_alive'], 0); self.assertEqual(body['options']['num_gpu'], 0)
                self.assertEqual(body['options']['num_ctx'], 4096); self.assertFalse(body['think'])
            self.assertEqual(chats[1]['format']['properties']['option']['enum'], [0, 1])
            self.assertNotIn('PRIVATE', json.dumps(metrics))

    def test_external_deadline_reaches_tags_and_chat_http_timeouts(self):
        model = OllamaModel(ModelSettings('http://127.0.0.1:11434', 'qwen3.5:4b', DIGEST))
        seen = []
        def request(path, payload=None, deadline=None):
            seen.append(deadline)
            if path == '/api/tags': return dict(models=[dict(name='qwen3.5:4b', digest=DIGEST)])
            return dict(done=True, done_reason='stop', message=dict(content='{"option":0}'), prompt_eval_count=1, eval_count=1)
        with patch.object(model, '_request', request), patch('dead_earth_director.model.time.monotonic', return_value=100):
            model.select(CONTEXT, deadline=103)
        self.assertEqual(seen, [103, 103])

    def test_real_http_critic_timeout_within_common_deadline_retains_proposal(self):
        from http.server import ThreadingHTTPServer
        chats = []
        def respond(path, body):
            if path == '/api/tags': return 200, dict(models=[dict(name='qwen3.5:4b', digest=DIGEST), dict(name='qwen3.5:2b-q4_k_m', digest=CRITIC_DIGEST)])
            chats.append(body['model'])
            if len(chats) == 2:
                time.sleep(.12)
                return 200, b''
            return 200, dict(done=True, done_reason='stop', message=dict(content='{"option":0}'), prompt_eval_count=1, eval_count=1)
        with patch.object(ThreadingHTTPServer, 'daemon_threads', False), patch.object(ThreadingHTTPServer, 'handle_error'), model_endpoint(respond) as (url, _):
            model = build_model(settings(url), 'live'); start = time.monotonic()
            value, metrics = model.select(CONTEXT, deadline=start+.06)
            elapsed = time.monotonic()-start
        self.assertEqual(value, {'option': 0}); self.assertLess(elapsed, .3)
        self.assertEqual(chats, ['qwen3.5:4b', 'qwen3.5:2b-q4_k_m'])
        self.assertEqual(metrics['stages'][-1]['error_code'], 'model_timeout')

    def test_unknown_final_delivery_reuses_durable_choice_without_new_council(self):
        from test_cinematic import brief, OP
        from test_bridge import CAMPAIGN, EPOCH
        model, a, b, _ = self.council([({'option': 0}, 1), ({'option': 1}, 1)], [({'option': 1, 'reason_codes': ['pressure']}, 1)])
        class Bridge:
            descriptor = SimpleNamespace(campaign_id=CAMPAIGN, world_epoch=EPOCH)
            def __init__(self): self.sent = []
            def call(self, operation, args):
                if operation == 'get_cinematic_brief': return brief()
                self.sent.append(args.copy())
                if len(self.sent) == 1: raise BridgeError('request_expired')
                return dict(requestId=args['request_id'], goalId=args['goal_id'], accepted=True, code='queued', operationId=OP)
        bridge = Bridge()
        with tempfile.TemporaryDirectory(dir=Path(__file__).parent) as temporary:
            path = Path(temporary) / 'decisions.db'; journal = Journal(path)
            try:
                with self.assertRaises(BridgeError): CinematicDirector(bridge, model, journal).once()
            finally: journal.close()
            journal = Journal(path)
            try: self.assertEqual(CinematicDirector(bridge, model, journal).once()['state'], 'queued')
            finally: journal.close()
        self.assertEqual(bridge.sent[0], bridge.sent[1])
        self.assertEqual((len(a.calls), len(b.calls)), (2, 1))


class CouncilConfigTests(unittest.TestCase):
    def test_example_has_real_pins_and_dual_toml_wires_the_same_cpu_endpoint(self):
        source = Path(__file__).resolve().parents[1] / 'director.example.toml'
        original = source.read_text(encoding='utf-8')
        single, _ = load_config(source)
        self.assertEqual(single.ai_mode, 'single')
        with tempfile.TemporaryDirectory(dir=Path(__file__).parent) as temporary:
            path = Path(temporary) / 'dual.toml'; path.write_text(original.replace('ai_mode = "single"', 'ai_mode = "dual"'), encoding='utf-8')
            dual, _ = load_config(path)
        self.assertEqual(dual.critic.model, 'dead-earth-critic:2b-q4')
        self.assertEqual(dual.critic.digest, '1e2d21a4f03ab9e4a9c1976a4b7e88f21de61d65f2c5485d8fde8942be73f1e7')
        self.assertEqual(dual.url, dual.critic.url)
        self.assertIsInstance(build_model(dual, 'live'), CouncilModel)

    def test_old_config_is_single_and_preview_never_constructs_council(self):
        with tempfile.TemporaryDirectory(dir=Path(__file__).parent) as temporary:
            path = Path(temporary) / 'config.toml'
            path.write_text('poll_seconds=30\n[model]\nurl="http://127.0.0.1:11434"\nmodel="qwen3.5:4b"\ndigest="' + DIGEST + '"\n')
            parsed, poll = load_config(path)
            self.assertEqual(parsed.ai_mode, 'single'); self.assertEqual(poll, 30)
            self.assertIsInstance(build_model(parsed, 'live'), OllamaModel)
        self.assertIsInstance(build_model(settings(), 'preview'), OllamaModel)
        self.assertIsInstance(build_model(settings(), 'live'), CouncilModel)

    def test_dual_needs_two_pins_same_endpoint_and_maximum_90_seconds(self):
        for change in (dict(ai_mode='other'), dict(ai_mode='dual'), dict(ai_mode='dual', critic=ModelSettings('http://127.0.0.1:11435', 'critic', CRITIC_DIGEST)), dict(budget_seconds=91)):
            with self.subTest(change=change), self.assertRaises(ModelError):
                CouncilSettings('http://127.0.0.1:11434', 'qwen3.5:4b', DIGEST, **change)
        with self.assertRaises(ModelError): ModelSettings('http://127.0.0.1:11434', 'main', DIGEST, timeout_seconds=91)

    def test_goal_ttl_and_margin_bound_common_budget(self):
        self.assertEqual(council_deadline(dict(activeTicks=20, goal=dict(expires=2400)), settings(), now=100), 190)
        self.assertEqual(council_deadline(dict(activeTicks=20, goal=dict(expires=420)), settings(), now=100), 115)


if __name__ == '__main__': unittest.main()
