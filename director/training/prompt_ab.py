"""Paired full-generation prompt experiment. Semantic assessment belongs to the reviewer."""
import argparse
from contextlib import nullcontext
import hashlib
import json
from pathlib import Path
import random
import re
import time

from dead_earth_director.tactics import parse_selection,selection_schema


def canonical(value):
    return json.dumps(value,ensure_ascii=False,sort_keys=True,separators=(',',':'),allow_nan=False)


def load_suite(path):
    value=json.loads(Path(path).read_text(encoding='utf-8'))
    if type(value) is not dict or set(value)!={'schema','prompts','cases'} or value['schema']!=1:
        raise ValueError('invalid_suite')
    if not 1<=len(value['prompts'])<=4 or not 1<=len(value['cases'])<=24:raise ValueError('invalid_suite_size')
    ids=set()
    for prompt in value['prompts']:
        if (type(prompt) is not dict or set(prompt)!={'id','system'} or prompt['id'] in ids
                or not re.fullmatch('[A-Z]',prompt['id']) or type(prompt['system']) is not str
                or prompt['system'].count('{{schema}}')!=1 or len(prompt['system'])>5000):raise ValueError('invalid_prompt')
        ids.add(prompt['id'])
    ids=set()
    for case in value['cases']:
        if (type(case) is not dict or set(case)!={'id','title','review_notes','context'} or case['id'] in ids
                or not re.fullmatch('[a-z][a-z0-9_]{0,63}',case['id'])
                or not all(type(case[k]) is str and 0<len(case[k])<=2000 for k in ('title','review_notes'))
                or type(case['context']) is not dict):raise ValueError('invalid_case')
        options=case['context'].get('options');selection_schema(len(options))
        if [o.get('option') for o in options]!=list(range(len(options))):raise ValueError('invalid_option_indices')
        ids.add(case['id'])
    canonical(value)
    return value


def messages(case,prompt):
    return [dict(role='system',content=prompt['system'].replace('{{schema}}',json.dumps(selection_schema(len(case['context']['options'])),ensure_ascii=False))),
            dict(role='user',content=canonical(case['context']))]


def jobs(suite,*,seed):
    grid=[dict(model=model,prompt_id=prompt['id'],case_id=case['id'])
          for model in ('base','adapter') for prompt in suite['prompts'] for case in suite['cases']]
    random.Random(seed).shuffle(grid)
    return [dict(response_id=f'R{i+1:03}',**row) for i,row in enumerate(grid)]


def technical_response(raw,count):
    try:return dict(contract_valid=True,option=parse_selection(raw,count)['option'])
    except ValueError:return dict(contract_valid=False,option=None)


def blind_pack(suite,rows):
    return [dict(case_id=case['id'],title=case['title'],review_notes=case['review_notes'],context=case['context'],
                 responses=[dict(response_id=r['response_id'],raw=r['raw']) for r in rows if r['case_id']==case['id']])
            for case in suite['cases']]


def write(path,value):
    path=Path(path);temporary=path.with_suffix(path.suffix+'.tmp')
    temporary.write_text(json.dumps(value,ensure_ascii=False,indent=2,allow_nan=False)+'\n',encoding='utf-8')
    temporary.replace(path)


def sha(path):
    h=hashlib.sha256()
    with Path(path).open('rb') as stream:
        for block in iter(lambda:stream.read(1024*1024),b''):h.update(block)
    return h.hexdigest()


def run(args):
    suite=load_suite(args.suite);plan=jobs(suite,seed=args.seed)
    output=Path(args.output);output.mkdir(parents=True,exist_ok=False)
    report=dict(schema=1,status='starting',source_commit=args.source_commit,seed=args.seed,
                suite_sha256=sha(args.suite),runner_sha256=sha(__file__),adapter_sha256=args.adapter_sha256,
                base_revision=args.revision,outputs_expected=len(plan),outputs_completed=0,
                semantic_judging='manual_blind_review_pending',production_modified=False)
    write(output/'run.json',report);write(output/'key.json',plan)
    started=time.perf_counter();torch=None
    try:
        from .train import verify_local_model
        config=dict(model_path=args.base,model_id='Qwen/Qwen3.5-4B',model_revision=args.revision)
        report['base_receipt']=verify_local_model(config)
        adapter=Path(args.adapter)
        if sha(adapter/'adapter_model.safetensors')!=args.adapter_sha256:raise ValueError('adapter_hash_mismatch')
        ac=json.loads((adapter/'adapter_config.json').read_text(encoding='utf-8'))
        if ac.get('revision')!=args.revision or ac.get('base_model_name_or_path')!=config['model_id']:
            raise ValueError('adapter_base_mismatch')
        import torch
        from transformers import AutoModelForImageTextToText,AutoTokenizer,BitsAndBytesConfig
        from peft import PeftModel,prepare_model_for_kbit_training
        if not torch.cuda.is_available():raise RuntimeError('cuda_required_for_offline_experiment')
        torch.cuda.set_device(0);torch.set_num_threads(6)
        prop=torch.cuda.get_device_properties(0)
        torch.cuda.set_per_process_memory_fraction(7000*2**20/prop.total_memory,0)
        random.seed(args.seed);torch.manual_seed(args.seed)
        report['gpu']=dict(name=prop.name,budget_mib=7000)
        tokenizer=AutoTokenizer.from_pretrained(args.base,trust_remote_code=False,local_files_only=True)
        if tokenizer.pad_token_id is None:tokenizer.pad_token=tokenizer.eos_token
        base=AutoModelForImageTextToText.from_pretrained(args.base,trust_remote_code=False,local_files_only=True,
            dtype=torch.float16,use_safetensors=True,device_map={'':0},attn_implementation='sdpa',
            quantization_config=BitsAndBytesConfig(load_in_4bit=True,bnb_4bit_quant_type='nf4',
                bnb_4bit_use_double_quant=True,bnb_4bit_compute_dtype=torch.float16))
        base=prepare_model_for_kbit_training(base,use_gradient_checkpointing=False)
        for embedding in (base.get_input_embeddings(),base.get_output_embeddings()):embedding.weight.data=embedding.weight.data.to(torch.float16)
        model=PeftModel.from_pretrained(base,args.adapter,is_trainable=False,local_files_only=True)
        model.eval()
        if any(p.requires_grad for p in model.parameters()):raise RuntimeError('inference_must_be_frozen')
        if any(str(v) in ('cpu','disk') for v in getattr(model,'hf_device_map',{}).values()):raise RuntimeError('no_cpu_offload')
        cases={c['id']:c for c in suite['cases']};prompts={p['id']:p for p in suite['prompts']};rows=[]
        report.update(status='generating',generation=dict(do_sample=False,thinking=False,max_new_tokens=64,
            forced_option_prefix=False,semantic_reference_labels=False,quantization='NF4/FP16'))
        write(output/'run.json',report)
        for job in plan:
            case=cases[job['case_id']]
            ids=tokenizer.apply_chat_template(messages(case,prompts[job['prompt_id']]),tokenize=True,
                add_generation_prompt=True,enable_thinking=False,return_dict=False)
            if type(ids) is not list or len(ids)>1536:raise ValueError('invalid_or_oversize_prefix')
            inputs=torch.tensor([ids],dtype=torch.long,device='cuda:0')
            start=time.perf_counter()
            with (model.disable_adapter() if job['model']=='base' else nullcontext()):
                if model.get_model_status().enabled!=(job['model']=='adapter'):raise RuntimeError('adapter_switch_failed')
                with torch.inference_mode(),torch.autocast('cuda',dtype=torch.float16):
                    result=model.generate(input_ids=inputs,attention_mask=torch.ones_like(inputs),
                        max_new_tokens=64,do_sample=False,num_beams=1,use_cache=True,
                        pad_token_id=tokenizer.pad_token_id,eos_token_id=tokenizer.eos_token_id,logits_to_keep=1)
            torch.cuda.synchronize()
            raw=tokenizer.decode(result[0,len(ids):].tolist(),skip_special_tokens=True,clean_up_tokenization_spaces=False)
            record=dict(**job,raw=raw,technical=technical_response(raw,len(case['context']['options'])),
                seconds=time.perf_counter()-start,input_tokens=len(ids),
                prefix_sha256=hashlib.sha256(canonical(ids).encode()).hexdigest())
            rows.append(record)
            with (output/'responses.jsonl').open('a',encoding='utf-8') as stream:stream.write(canonical(record)+'\n')
            del inputs,result
            report['outputs_completed']=len(rows);write(output/'run.json',report)
            print(canonical(dict(completed=len(rows),total=len(plan))),flush=True)
        write(output/'blind.json',blind_pack(suite,rows));report['status']='complete'
    except Exception as error:
        report.update(status='failed',error_type=type(error).__name__,error=str(error)[:1200])
    if torch is not None and torch.cuda.is_available():
        report['memory']=dict(peak_allocated_mib=torch.cuda.max_memory_allocated()/2**20,peak_reserved_mib=torch.cuda.max_memory_reserved()/2**20)
    report['elapsed_seconds']=time.perf_counter()-started;write(output/'run.json',report)
    print(canonical(dict(status=report['status'],completed=report['outputs_completed'])),flush=True)
    return 0 if report['status']=='complete' else 2


def main():
    p=argparse.ArgumentParser(description=__doc__)
    for arg in ('suite','output','base','adapter','adapter-sha256','revision','source-commit'):p.add_argument('--'+arg,required=True)
    p.add_argument('--seed',type=int,default=20261018)
    args=p.parse_args()
    if not re.fullmatch('[0-9a-f]{64}',args.adapter_sha256) or any(not re.fullmatch('[0-9a-f]{40}',getattr(args,k)) for k in ('revision','source_commit')):
        p.error('immutable revision/source/adapter hashes required')
    return run(args)


if __name__=='__main__':raise SystemExit(main())
