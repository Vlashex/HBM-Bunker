import importlib
import json
import math
from pathlib import Path
import hashlib
import tempfile
import unittest
from unittest.mock import patch

from training.train import answer_window, choice_tokens, encode_row, validate_config, verify_local_model
from dead_earth_director.tactics import selection_prompt


class FakeTokenizer:
    eos_token_id = 2

    def __init__(self):
        self.calls = []

    def apply_chat_template(self, messages, **kwargs):
        self.calls.append((messages, kwargs))
        return [100, 101, 102]

    def encode(self, value, *, add_special_tokens):
        if add_special_tokens:
            raise AssertionError('answer must not add another chat/BOS template')
        return [ord(char) + 200 for char in value]


def row():
    return dict(id='example', family='family', split='train',
                context=dict(season='calm', pressure=0.5, parasite_phase=2,
                             options=[dict(option=0, event='a'), dict(option=1, event='b')], recent=[]),
                target=dict(option=1), acceptable_options=[1],
                provenance=dict(kind='synthetic_policy', policy='v1'))


def config():
    return dict(model_id='Qwen/Qwen3.5-4B', model_revision='a' * 40, base_commit='b' * 40)


class TrainEncodingTests(unittest.TestCase):
    def test_new_tokenizer_dictionary_default_is_explicitly_disabled(self):
        class DictionaryDefault(FakeTokenizer):
            def apply_chat_template(self, messages, **kwargs):
                ids = super().apply_chat_template(messages, **kwargs)
                return ids if kwargs.get('return_dict') is False else {'input_ids': ids, 'attention_mask': [1] * len(ids)}
        encoded = encode_row(DictionaryDefault(), row(), 128)
        self.assertEqual(encoded['input_ids'][:3], [100, 101, 102])

    def test_only_canonical_answer_and_eos_are_supervised(self):
        tokenizer = FakeTokenizer()
        encoded = encode_row(tokenizer, row(), 128)
        answer = tokenizer.encode('{"option":1}', add_special_tokens=False) + [2]
        self.assertEqual(encoded['input_ids'], [100, 101, 102] + answer)
        self.assertEqual(encoded['labels'], [-100] * 3 + answer)
        self.assertEqual(encoded['attention_mask'], [1] * (3 + len(answer)))
        messages, kwargs = tokenizer.calls[0]
        self.assertEqual([message['role'] for message in messages], ['system', 'user'])
        self.assertEqual(messages[0]['content'], selection_prompt(2))
        self.assertEqual(json.loads(messages[1]['content']), row()['context'])
        self.assertEqual(kwargs, dict(tokenize=True, add_generation_prompt=True, enable_thinking=False, return_dict=False))
        self.assertEqual(len(tokenizer.calls), 1)

    def test_overlength_is_rejected_instead_of_truncating_prefix_or_answer(self):
        with self.assertRaisesRegex(ValueError, 'too_long'):
            encode_row(FakeTokenizer(), row(), 4)

    def test_bad_target_and_missing_eos_cannot_enter_training(self):
        for target in ({'option': True}, {'option': 2}, {'option': 0, 'command': 'x'}):
            broken = row(); broken['target'] = target
            with self.subTest(target=target), self.assertRaises(ValueError):
                encode_row(FakeTokenizer(), broken, 128)
        tokenizer = FakeTokenizer(); tokenizer.eos_token_id = None
        with self.assertRaisesRegex(ValueError, 'eos'):
            encode_row(tokenizer, row(), 128)

    def test_answer_window_aligns_last_logits_with_answer_including_eos(self):
        encoded = dict(input_ids=[10, 11, 20, 21, 2], attention_mask=[1] * 5,
                       labels=[-100, -100, 20, 21, 2])
        window = answer_window(encoded)
        self.assertEqual(window, dict(input_ids=[10, 11, 20, 21], attention_mask=[1] * 4,
                                      targets=[20, 21, 2], logits_to_keep=3))
        positions = list(range(len(window['input_ids'])))[-window['logits_to_keep']:]
        self.assertEqual([encoded['input_ids'][position + 1] for position in positions], window['targets'])

    def test_answer_window_rejects_non_suffix_or_empty_supervision(self):
        for labels in ([-100, -100, -100], [-100, 11, -100], [10, 11, 2], [-100, 77, 2]):
            with self.subTest(labels=labels), self.assertRaises(ValueError):
                answer_window(dict(input_ids=[10, 11, 2], attention_mask=[1] * 3, labels=labels))

    def test_constrained_choice_requires_single_digit_tokens_at_json_boundary(self):
        tokenizer = FakeTokenizer()
        prefix, digits = choice_tokens(tokenizer, 8)
        self.assertEqual(prefix, tokenizer.encode('{"option":', add_special_tokens=False))
        self.assertEqual(digits, [ord(str(i)) + 200 for i in range(8)])
        class SplitDigits(FakeTokenizer):
            def encode(self, value, *, add_special_tokens):
                return [1, 2] if value.isdigit() else super().encode(value, add_special_tokens=add_special_tokens)
        with self.assertRaisesRegex(ValueError, 'single_token'):
            choice_tokens(SplitDigits(), 2)

    def test_import_does_not_import_gpu_libraries(self):
        original = __import__
        def guarded(name, *args, **kwargs):
            if name.split('.')[0] in {'torch', 'transformers', 'peft', 'bitsandbytes', 'accelerate'}:
                raise AssertionError('GPU import during pure module import: ' + name)
            return original(name, *args, **kwargs)
        with patch('builtins.__import__', guarded):
            importlib.reload(importlib.import_module('training.train'))


class TrainConfigTests(unittest.TestCase):
    def test_fp16_loss_scale_is_explicit_bounded_and_can_be_below_one(self):
        self.assertEqual(validate_config(dict(config(), initial_loss_scale=0.25))['initial_loss_scale'], 0.25)
        for value in (True, 0, -1, math.nan, math.inf, 100000):
            with self.subTest(value=value), self.assertRaises(ValueError):
                validate_config(dict(config(), initial_loss_scale=value))

    def test_model_inside_cache_parent_still_rejects_unmanifested_files(self):
        with tempfile.TemporaryDirectory(dir=Path(__file__).resolve().parent) as temporary:
            root = Path(temporary) / '.cache' / 'huggingface' / 'snapshot'
            root.mkdir(parents=True)
            contents = {'config.json': b'{}', 'model.safetensors': b'pinned-test-weights'}
            for name, content in contents.items():
                (root / name).write_bytes(content)
            manifest = dict(model_id=config()['model_id'], model_revision=config()['model_revision'],
                            files={name: hashlib.sha256(content).hexdigest() for name, content in contents.items()})
            (root / 'base-pin.json').write_text(json.dumps(manifest), encoding='utf-8')
            (root / 'tokenizer_config.json').write_text('{}', encoding='utf-8')
            with self.assertRaisesRegex(ValueError, 'unmanifested'):
                verify_local_model(validate_config(dict(config(), model_path=str(root))))

    def test_defaults_are_small_fp16_qlora_and_revision_is_immutable(self):
        parsed = validate_config(config())
        self.assertEqual((parsed['max_steps'], parsed['max_length'], parsed['batch_size'],
                          parsed['gradient_accumulation_steps'], parsed['lora_rank'], parsed['lora_alpha']),
                         (64, 1536, 1, 4, 8, 16))
        self.assertEqual(parsed['attention_implementation'], 'sdpa')
        self.assertEqual(parsed['test_rows'], 64)
        self.assertEqual(parsed['generation_rows'], 8)

    def test_unsafe_or_unbounded_settings_fail_before_gpu_load(self):
        changes = [dict(model_revision='main'), dict(base_commit='HEAD'), dict(trust_remote_code=True),
                   dict(attention_implementation='flash_attention_2'), dict(batch_size=2),
                   dict(max_length=4096), dict(max_steps=0), dict(seed=True),
                   dict(learning_rate=math.nan), dict(memory_budget_mib=-1),
                   dict(test_rows=0), dict(generation_rows=65)]
        changes.append(dict(model_id=[]))
        for change in changes:
            with self.subTest(change=change), self.assertRaises(ValueError):
                validate_config(dict(config(), **change))

    def test_local_model_requires_matching_hashes_and_covers_all_loadable_files(self):
        with tempfile.TemporaryDirectory(dir=Path(__file__).resolve().parent) as temporary:
            root = Path(temporary)
            contents = {'config.json': b'{}', 'model.safetensors': b'pinned-test-weights', 'README.md': b'attribution'}
            for name, content in contents.items():
                (root / name).write_bytes(content)
            manifest = dict(model_id=config()['model_id'], model_revision=config()['model_revision'],
                            files={name: hashlib.sha256(content).hexdigest() for name, content in contents.items()})
            (root / 'base-pin.json').write_text(json.dumps(manifest), encoding='utf-8')
            local = validate_config(dict(config(), model_path=str(root)))
            self.assertEqual(verify_local_model(local)['files'], manifest['files'])
            (root / 'tokenizer_config.json').write_text('{}', encoding='utf-8')
            with self.assertRaisesRegex(ValueError, 'unmanifested'):
                verify_local_model(local)
            (root / 'tokenizer_config.json').unlink()
            (root / 'model.safetensors').write_bytes(b'changed')
            with self.assertRaisesRegex(ValueError, 'hash_mismatch'):
                verify_local_model(local)


if __name__ == '__main__':
    unittest.main()
