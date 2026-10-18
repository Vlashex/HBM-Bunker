"""Bounded local QLoRA experiment. Importing this module requires no GPU packages.

From director/: python -m training.train --config config.json --data DATA --output NEW_RUN [--probe]
No inference pin is changed and no test result chooses a checkpoint.
"""

import argparse
import hashlib
import importlib.metadata
import json
import math
import os
from pathlib import Path, PurePosixPath, PureWindowsPath
import random
import re
import time

from dead_earth_director.tactics import parse_selection, selection_prompt, selection_schema


DEFAULTS = dict(seed=20261018, max_length=1536, max_steps=64, batch_size=1,
                gradient_accumulation_steps=4, learning_rate=1e-4, lora_rank=8, lora_alpha=16,
                attention_implementation='sdpa', memory_budget_mib=7600,
                validation_interval=16, test_rows=64, validation_rows=32, generation_rows=8)
PROJECTIONS = {'q_proj', 'k_proj', 'v_proj', 'o_proj', 'gate_proj', 'up_proj', 'down_proj',
               'in_proj_qkv', 'in_proj_z', 'in_proj_b', 'in_proj_a', 'out_proj'}


def _json(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(',', ':'), allow_nan=False)


def validate_config(raw):
    required = {'model_id', 'model_revision', 'base_commit'}
    if type(raw) is not dict or not required <= raw.keys() or raw.keys() - required - DEFAULTS.keys() - {'model_path'}:
        raise ValueError('invalid_config_fields')
    result = dict(DEFAULTS, **raw)
    if type(result['model_id']) is not str or result['model_id'] not in {'Qwen/Qwen3.5-4B', 'Qwen/Qwen3.5-2B', 'Qwen/Qwen3.5-0.8B'}:
        raise ValueError('unsupported_model_family')
    for name in ('model_revision', 'base_commit'):
        if type(result[name]) is not str or not re.fullmatch('[0-9a-f]{40}', result[name]):
            raise ValueError('immutable_' + name + '_required')
    bounds = dict(seed=(0, 2**32 - 1), max_length=(32, 1536), max_steps=(1, 64), batch_size=(1, 1),
                  gradient_accumulation_steps=(1, 16), lora_rank=(8, 8), lora_alpha=(16, 16),
                  memory_budget_mib=(512, 8192), validation_interval=(0, 64),
                  test_rows=(1, 1024), validation_rows=(1, 1024), generation_rows=(1, 8))
    for name, (low, high) in bounds.items():
        if type(result[name]) is not int or not low <= result[name] <= high:
            raise ValueError('invalid_' + name)
    if type(result['learning_rate']) not in (int, float) or not math.isfinite(result['learning_rate']) or not 0 < result['learning_rate'] <= .001:
        raise ValueError('invalid_learning_rate')
    if result['attention_implementation'] not in ('sdpa', 'eager'):
        raise ValueError('turing_attention_required')
    if 'model_path' in result:
        value = result['model_path']
        if type(value) is not str or not value or not (PurePosixPath(value).is_absolute() or PureWindowsPath(value).is_absolute()):
            raise ValueError('absolute_model_path_required')
    return result


def _count(row):
    try:
        count = len(row['context']['options'])
        selection_schema(count)
        parse_selection(_json(row['target']), count)
        return count
    except (KeyError, TypeError, ValueError):
        raise ValueError('invalid_training_row') from None


def _prefix(tokenizer, row):
    messages = [dict(role='system', content=selection_prompt(_count(row))),
                dict(role='user', content=_json(row['context']))]
    ids = tokenizer.apply_chat_template(messages, tokenize=True, add_generation_prompt=True, enable_thinking=False)
    if type(ids) is not list or not ids or any(type(token) is not int or token < 0 for token in ids):
        raise ValueError('invalid_chat_prefix_tokens')
    return ids


def encode_row(tokenizer, row, max_length):
    """One chat prefix, then raw compact answer + EOS. Never silently truncate."""
    if type(max_length) is not int or max_length < 1:
        raise ValueError('invalid_max_length')
    prefix = _prefix(tokenizer, row)
    eos = tokenizer.eos_token_id
    if type(eos) is not int or eos < 0:
        raise ValueError('eos_token_required')
    answer = tokenizer.encode(_json(row['target']), add_special_tokens=False) + [eos]
    ids = prefix + answer
    if len(ids) > max_length:
        raise ValueError(f'row_too_long:{row.get("id", "unknown")}:{len(ids)}>{max_length}')
    return dict(input_ids=ids, attention_mask=[1] * len(ids), labels=[-100] * len(prefix) + answer)


def answer_window(encoded):
    """Last K logits of ids[:-1] predict the K supervised suffix tokens exactly."""
    ids, mask, labels = (encoded[name] for name in ('input_ids', 'attention_mask', 'labels'))
    if not (len(ids) == len(mask) == len(labels)) or not ids or any(value != 1 for value in mask):
        raise ValueError('invalid_answer_window')
    first = next((index for index, value in enumerate(labels) if value != -100), len(labels))
    if not 1 <= first < len(ids) or labels[first:] != ids[first:] or any(value == -100 for value in labels[first:]):
        raise ValueError('invalid_supervised_suffix')
    return dict(input_ids=ids[:-1], attention_mask=mask[:-1], targets=labels[first:], logits_to_keep=len(ids) - first)


def choice_tokens(tokenizer, count):
    """Validate the next-token assumption; never pretend this measures JSON validity."""
    selection_schema(count)
    prefix = tokenizer.encode('{"option":', add_special_tokens=False)
    digits = []
    for option in range(count):
        token = tokenizer.encode(str(option), add_special_tokens=False)
        if len(token) != 1 or tokenizer.encode('{"option":' + str(option), add_special_tokens=False) != prefix + token:
            raise ValueError('choice_digit_must_be_single_token_at_json_boundary')
        digits.append(token[0])
    return prefix, digits


def _sha(path):
    digest = hashlib.sha256()
    with Path(path).open('rb') as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b''):
            digest.update(chunk)
    return digest.hexdigest()


def verify_local_model(config):
    """A local from_pretrained path ignores HF revision: verify the preload receipt."""
    if 'model_path' not in config:
        return None
    root = Path(config['model_path']).resolve(strict=True)
    manifest = json.loads((root / 'base-pin.json').read_text(encoding='utf-8'))
    if manifest.get('model_id') != config['model_id'] or manifest.get('model_revision') != config['model_revision']:
        raise ValueError('local_model_pin_mismatch')
    files = manifest.get('files')
    if type(files) is not dict or 'config.json' not in files or not any(name.endswith('.safetensors') for name in files):
        raise ValueError('invalid_local_model_manifest')
    for name, expected in files.items():
        path = (root / name).resolve(strict=True)
        if root not in path.parents or type(expected) is not str or not re.fullmatch('[0-9a-f]{64}', expected) or _sha(path) != expected:
            raise ValueError('local_model_hash_mismatch')
    # Unmanifested tokenizer/config/weight files could otherwise alter the pinned model.
    actual = {path.relative_to(root).as_posix() for path in root.rglob('*')
              if path.is_file() and path.suffix in ('.json', '.safetensors', '.jinja', '.txt', '.model') and path.name != 'base-pin.json' and '.cache' not in path.relative_to(root).parts}
    if not actual <= set(files):
        raise ValueError('unmanifested_local_model_files')
    return dict(manifest_sha256=_sha(root / 'base-pin.json'), files=files)


def _write_report(output, report):
    temporary = output / 'run.json.tmp'
    with temporary.open('w', encoding='utf-8', newline='\n') as stream:
        stream.write(_json(report) + '\n'); stream.flush(); os.fsync(stream.fileno())
    os.replace(temporary, output / 'run.json')


def _progress(output, kind, **fields):
    entry = dict(kind=kind, timestamp_ms=time.time_ns() // 1_000_000, **fields)
    with (output / 'progress.jsonl').open('a', encoding='utf-8', newline='\n') as stream:
        stream.write(_json(entry) + '\n'); stream.flush(); os.fsync(stream.fileno())


def _gpu_stats(torch):
    return dict(allocated_mib=torch.cuda.memory_allocated() / 2**20,
                reserved_mib=torch.cuda.memory_reserved() / 2**20,
                peak_allocated_mib=torch.cuda.max_memory_allocated() / 2**20,
                peak_reserved_mib=torch.cuda.max_memory_reserved() / 2**20)


def _tensor(torch, values):
    return torch.tensor([values], dtype=torch.long, device='cuda:0')


def _evaluate(torch, model, tokenizer, rows, encoded, *, generate=False):
    from .evaluation import score_rows
    predictions, latencies, errors = {}, [], []
    model.eval()
    with torch.inference_mode():
        for row in rows:
            record = encoded[row['id']]
            first = next(index for index, label in enumerate(record['labels']) if label != -100)
            prefix = record['input_ids'][:first]
            torch.cuda.synchronize(); started = time.perf_counter()
            with torch.autocast('cuda', dtype=torch.float16):
                if generate:
                    inputs = _tensor(torch, prefix)
                    result = model.generate(input_ids=inputs, attention_mask=torch.ones_like(inputs),
                                            max_new_tokens=24, do_sample=False, num_beams=1, use_cache=True,
                                            pad_token_id=tokenizer.pad_token_id, eos_token_id=tokenizer.eos_token_id,
                                            logits_to_keep=1)
                    raw = tokenizer.decode(result[0, len(prefix):].tolist(), skip_special_tokens=True,
                                           clean_up_tokenization_spaces=False)
                    predictions[row['id']] = raw
                    try: parse_selection(raw, len(row['context']['options']))
                    except ValueError: errors.append(row['id'])
                    del result, inputs
                else:
                    json_prefix, digits = choice_tokens(tokenizer, len(row['context']['options']))
                    inputs = _tensor(torch, prefix + json_prefix)
                    result = model(input_ids=inputs, attention_mask=torch.ones_like(inputs), use_cache=False, logits_to_keep=1)
                    logits = result.logits[0, -1, digits].float()
                    if not torch.isfinite(logits).all().item():
                        raise RuntimeError('nonfinite_evaluation_logits')
                    predictions[row['id']] = dict(option=int(logits.argmax().item()))
                    del result, inputs, logits
            torch.cuda.synchronize(); latencies.append(time.perf_counter() - started)
    metrics = score_rows(rows, predictions)
    if not generate:
        for item in [metrics, *metrics['per_family'].values()]:
            for key in ('json_valid_rate', 'contract_valid_rate', 'contract_valid'):
                item.pop(key, None)
    metrics.update(method='greedy_json_generation' if generate else 'constrained_logit_choice',
                   row_ids=[row['id'] for row in rows], predictions=predictions,
                   mean_seconds=sum(latencies) / len(latencies), max_seconds=max(latencies))
    if generate:
        metrics.update(max_new_tokens=24, do_sample=False, contract_error_ids=errors)
    return metrics


def run(config, data, output, *, probe=False):
    """Run once into a new directory. GPU failures remain failed experiments, never CPU fallback."""
    config = validate_config(config)
    data, output = Path(data), Path(output)
    output.mkdir(parents=True, exist_ok=False)
    started = time.perf_counter()
    report = dict(schema=1, status='starting', mode='probe' if probe else 'training', config=config,
                  config_sha256=hashlib.sha256(_json(config).encode()).hexdigest(),
                  base_commit=config['base_commit'], seed=config['seed'], completed_optimizer_steps=0,
                  evaluation={}, promotion='none', limitation='Weak synthetic policy agreement, not real-player success.')
    _write_report(output, report); _progress(output, 'starting', mode=report['mode'])
    torch = None
    try:
        from .dataset import load_rows, validate_rows
        rows = {split: load_rows(data / (split + '.jsonl')) for split in ('train', 'validation', 'test')}
        validate_rows(sum(rows.values(), []))
        for split, members in rows.items():
            if not members or any(row['split'] != split for row in members):
                raise ValueError('invalid_' + split + '_split')
        if not probe and (len(rows['test']) < config['test_rows'] or len(rows['validation']) < config['validation_rows'] or len(rows['test']) < config['generation_rows']):
            raise ValueError('insufficient_evaluation_rows')
        report['data'] = {split: dict(rows=len(members), sha256=_sha(data / (split + '.jsonl')),
                                     families=sorted({row['family'] for row in members})) for split, members in rows.items()}
        report['sources'] = {str(path.relative_to(Path(__file__).parent.parent)): _sha(path)
                             for path in (Path(__file__), Path(__file__).with_name('dataset.py'),
                                          Path(__file__).with_name('evaluation.py'),
                                          Path(__file__).parent.parent / 'dead_earth_director/tactics.py')}
        report['local_model'] = verify_local_model(config)
        import torch
        from transformers import AutoModelForImageTextToText, AutoTokenizer, BitsAndBytesConfig
        from peft import LoraConfig, TaskType, get_peft_model, prepare_model_for_kbit_training
        report['dependencies'] = {name: importlib.metadata.version(name) for name in
                                  ('torch', 'transformers', 'peft', 'accelerate', 'bitsandbytes', 'safetensors')}
        if not torch.cuda.is_available():
            raise RuntimeError('cuda_required_no_cpu_offload')
        torch.cuda.set_device(0)
        capability = torch.cuda.get_device_capability(0)
        if capability < (7, 5):
            raise RuntimeError('gpu_compute_capability_below_sm75')
        properties = torch.cuda.get_device_properties(0)
        budget = config['memory_budget_mib'] * 2**20
        if budget > properties.total_memory:
            raise ValueError('memory_budget_exceeds_gpu')
        torch.cuda.set_per_process_memory_fraction(budget / properties.total_memory, 0)
        torch.cuda.reset_peak_memory_stats()
        random.seed(config['seed']); torch.manual_seed(config['seed']); torch.cuda.manual_seed_all(config['seed'])
        report['gpu'] = dict(name=properties.name, capability=list(capability), total_mib=properties.total_memory / 2**20,
                             budget_mib=config['memory_budget_mib'], dtype='float16', offload=False)
        report['quantization'] = dict(bits=4, type='nf4', double_quant=True, compute_dtype='float16')
        source = config.get('model_path', config['model_id'])
        pinned = dict(revision=config['model_revision'], trust_remote_code=False, local_files_only='model_path' in config)
        tokenizer = AutoTokenizer.from_pretrained(source, **pinned)
        if tokenizer.pad_token_id is None:
            tokenizer.pad_token = tokenizer.eos_token
        encoded = {row['id']: encode_row(tokenizer, row, config['max_length']) for row in sum(rows.values(), [])}
        worst = max(rows['train'], key=lambda row: len(encoded[row['id']]['input_ids']))
        report['sequence_lengths'] = {split: dict(min=min(len(encoded[row['id']]['input_ids']) for row in members),
                                                      max=max(len(encoded[row['id']]['input_ids']) for row in members))
                                      for split, members in rows.items()}
        report['probe_row'] = dict(id=worst['id'], tokens=len(encoded[worst['id']]['input_ids']))
        report['status'] = 'loading_model'; _write_report(output, report); _progress(output, 'loading_model')
        model = AutoModelForImageTextToText.from_pretrained(source, **pinned, dtype=torch.float16, use_safetensors=True,
                    device_map={'': 0}, attn_implementation=config['attention_implementation'],
                    quantization_config=BitsAndBytesConfig(load_in_4bit=True, bnb_4bit_quant_type='nf4',
                                                          bnb_4bit_use_double_quant=True, bnb_4bit_compute_dtype=torch.float16))
        if model.config.model_type != 'qwen3_5' or any(str(device) in ('cpu', 'disk') for device in getattr(model, 'hf_device_map', {}).values()):
            raise RuntimeError('unexpected_architecture_or_cpu_offload')
        model.config.use_cache = False
        model.config.text_config.use_cache = False
        model = prepare_model_for_kbit_training(model, use_gradient_checkpointing=True,
                                                gradient_checkpointing_kwargs={'use_reentrant': False})
        # PEFT upcasts frozen embeddings/head to fp32. Keep large vocabulary matrices
        # in fp16 on 8GiB; normalization parameters remain fp32.
        for embedding in (model.get_input_embeddings(), model.get_output_embeddings()):
            if embedding.weight.requires_grad:
                raise RuntimeError('base_embeddings_must_be_frozen')
            embedding.weight.data = embedding.weight.data.to(dtype=torch.float16)
        torch.cuda.empty_cache()
        targets = sorted(name for name, module in model.named_modules()
                         if '.language_model.' in name and name.rsplit('.', 1)[-1] in PROJECTIONS and hasattr(module, 'weight'))
        if not targets or any('visual' in name for name in targets):
            raise RuntimeError('language_lora_targets_not_found')
        model = get_peft_model(model, LoraConfig(task_type=TaskType.CAUSAL_LM, r=config['lora_rank'],
                              lora_alpha=config['lora_alpha'], lora_dropout=0.0, target_modules=targets, bias='none'))
        model.peft_config['default'].base_model_name_or_path = config['model_id']
        model.peft_config['default'].revision = config['model_revision']
        named_parameters = [(name, parameter) for name, parameter in model.named_parameters() if parameter.requires_grad]
        if not named_parameters or any('lora_' not in name or 'visual' in name for name, _ in named_parameters):
            raise RuntimeError('unexpected_trainable_base_or_visual_parameters')
        parameters = [parameter for _, parameter in named_parameters]
        for parameter in parameters:
            parameter.data = parameter.data.float()
        report['lora'] = dict(target_modules=targets, trainable_parameters=sum(parameter.numel() for parameter in parameters),
                              rank=config['lora_rank'], alpha=config['lora_alpha'], gradient_checkpointing_use_reentrant=False)
        report['loaded_memory'] = _gpu_stats(torch)
        test = rows['test'][:config['test_rows']]
        generated = rows['test'][:config['generation_rows']]
        validation = rows['validation'][:config['validation_rows']]
        if not probe:
            report['status'] = 'baseline_evaluation'; _write_report(output, report); _progress(output, 'baseline_evaluation')
            report['evaluation']['baseline'] = dict(choice=_evaluate(torch, model, tokenizer, test, encoded),
                                                       generation=_evaluate(torch, model, tokenizer, generated, encoded, generate=True))
            _write_report(output, report); _progress(output, 'baseline_complete')
        else:
            report['evaluation'] = dict(status='not_run_probe_only')
        optimizer = torch.optim.AdamW(parameters, lr=config['learning_rate'], weight_decay=0)
        scaler = torch.amp.GradScaler('cuda', init_scale=128)
        order = list(rows['train']); rng = random.Random(config['seed']); rng.shuffle(order); cursor = 0
        steps, accumulation = (1, 1) if probe else (config['max_steps'], config['gradient_accumulation_steps'])
        report.update(status='training', actual_gradient_accumulation_steps=accumulation)
        _write_report(output, report); _progress(output, 'training', steps=steps, accumulation=accumulation)
        training_start = time.perf_counter()
        for step in range(1, steps + 1):
            model.train(); optimizer.zero_grad(set_to_none=True); loss_sum = 0.; step_start = time.perf_counter()
            for microstep in range(accumulation):
                if probe:
                    row = worst
                else:
                    if cursor == len(order): rng.shuffle(order); cursor = 0
                    row = order[cursor]; cursor += 1
                window = answer_window(encoded[row['id']])
                inputs = _tensor(torch, window['input_ids']); mask = _tensor(torch, window['attention_mask'])
                targets_tensor = torch.tensor(window['targets'], device='cuda:0', dtype=torch.long)
                with torch.autocast('cuda', dtype=torch.float16):
                    result = model(input_ids=inputs, attention_mask=mask, use_cache=False, logits_to_keep=window['logits_to_keep'])
                    if result.logits.shape[1] != len(window['targets']):
                        raise RuntimeError('native_logits_to_keep_not_supported')
                    loss = torch.nn.functional.cross_entropy(result.logits[0].float(), targets_tensor)
                if not torch.isfinite(loss).item():
                    raise RuntimeError('nonfinite_training_loss')
                loss_sum += loss.item()
                scaler.scale(loss / accumulation).backward()
                del inputs, mask, targets_tensor, result, loss
            scaler.unscale_(optimizer)
            norm = torch.nn.utils.clip_grad_norm_(parameters, 1.0, error_if_nonfinite=True)
            scaler.step(optimizer); scaler.update(); torch.cuda.synchronize()
            # Finite unscaled gradients above ensure GradScaler did not skip this step.
            report['completed_optimizer_steps'] = step
            report['last_loss'] = loss_sum / accumulation
            report['memory'] = _gpu_stats(torch)
            _progress(output, 'optimizer_update', step=step, loss=report['last_loss'], grad_norm=norm.item(),
                      seconds=time.perf_counter() - step_start, **report['memory'])
            if not probe and config['validation_interval'] and step % config['validation_interval'] == 0:
                report['evaluation'].setdefault('validation', []).append(dict(step=step, choice=_evaluate(torch, model, tokenizer, validation, encoded)))
            _write_report(output, report)
        report['training_seconds'] = time.perf_counter() - training_start
        report['optimizer_state_entries'] = len(optimizer.state)
        report['nonzero_lora_b'] = any('lora_B' in name and torch.count_nonzero(parameter.detach()).item() > 0 for name, parameter in named_parameters)
        if not report['optimizer_state_entries'] or not report['nonzero_lora_b']:
            raise RuntimeError('optimizer_update_did_not_change_adapter')
        optimizer.zero_grad(set_to_none=True)
        del optimizer
        model.save_pretrained(output / 'adapter', safe_serialization=True, save_embedding_layers=False)
        tokenizer.save_pretrained(output / 'tokenizer')
        report['artifacts'] = {path.relative_to(output).as_posix(): _sha(path)
                               for folder in ('adapter', 'tokenizer') for path in (output / folder).rglob('*') if path.is_file()}
        report['status'] = 'probe_complete' if probe else 'trained_pending_evaluation'
        _write_report(output, report)
        if not probe:
            report['evaluation']['after'] = dict(choice=_evaluate(torch, model, tokenizer, test, encoded),
                                                    generation=_evaluate(torch, model, tokenizer, generated, encoded, generate=True))
            if not config['validation_interval'] or steps % config['validation_interval']:
                report['evaluation'].setdefault('validation', []).append(dict(step=steps, choice=_evaluate(torch, model, tokenizer, validation, encoded)))
            report['status'] = 'trained'
        report['memory'] = _gpu_stats(torch)
    except Exception as error:
        report.update(status='oom' if torch is not None and isinstance(error, torch.cuda.OutOfMemoryError) else 'failed',
                      error_type=type(error).__name__, error=str(error)[:2000])
        if torch is not None and torch.cuda.is_available(): report['memory'] = _gpu_stats(torch)
    report['elapsed_seconds'] = time.perf_counter() - started
    _write_report(output, report); _progress(output, report['status'], completed_optimizer_steps=report['completed_optimizer_steps'])
    return report


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--config', required=True, type=Path)
    parser.add_argument('--data', required=True, type=Path)
    parser.add_argument('--output', required=True, type=Path)
    parser.add_argument('--probe', action='store_true')
    args = parser.parse_args(argv)
    try:
        from .dataset import strict_json
        config = validate_config(strict_json(args.config.read_text(encoding='utf-8')))
        report = run(config, args.data, args.output, probe=args.probe)
    except (ValueError, OSError) as error:
        parser.error(str(error))
    print(_json(dict(status=report['status'], completed_optimizer_steps=report['completed_optimizer_steps'],
                     report=str(args.output / 'run.json'))))
    return 0 if report['status'] in ('trained', 'probe_complete') else 2


if __name__ == '__main__':
    raise SystemExit(main())
