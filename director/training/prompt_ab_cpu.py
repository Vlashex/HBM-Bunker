"""Offline CPU serving confirmation. Uses production HTTP validation; no gameplay bridge."""
import argparse
import json
from pathlib import Path
import random
import time

from dead_earth_director.model import ModelError,ModelSettings,OllamaModel
from dead_earth_director.council import critique_schema,parse_critique
from dead_earth_director.tactics import parse_selection,selection_schema
from .prompt_ab import canonical,load_suite,sha,write


def prepare(case,arm,*,proposal=None):
    count=len(case['context']['options'])
    if arm['role']=='primary':
        schema=selection_schema(count);context=case['context']
        parse=lambda raw:parse_selection(raw,count)
    elif arm['role']=='critic':
        if proposal is None:raise ValueError('proposal_required')
        parse_selection(canonical(proposal),count)
        schema=critique_schema(count);context=dict(context=case['context'],proposal=proposal)
        parse=lambda raw:parse_critique(raw,count)
    else:raise ValueError('invalid_role')
    if arm['system'].count('{{schema}}')!=1:raise ValueError('schema_required')
    return dict(context=context,schema=schema,prompt=arm['system'].replace('{{schema}}',json.dumps(schema,ensure_ascii=False)),parse=parse)


class CaptureModel(OllamaModel):
    raw=None
    def _request(self,path,*args,**kwargs):
        reply=super()._request(path,*args,**kwargs)
        if path=='/api/chat' and type(reply) is dict and type(reply.get('message')) is dict:
            self.raw=reply['message'].get('content')
        return reply


def run(args):
    suite=load_suite(args.suite);config=json.loads(Path(args.protocol).read_text(encoding='utf-8'))
    cases={c['id']:c for c in suite['cases']}
    prompts={p['id']:p['system'] for p in suite['prompts']}
    for arm in config['arms']:
        if 'prompt_ref' in arm:arm['system']=prompts[arm['prompt_ref']]
    plan=[dict(case_id=case,arm_id=arm['id'],role=arm['role']) for case in config['case_ids'] for arm in config['arms']]
    random.Random(20261018).shuffle(plan)
    plan=[dict(response_id=f'C{i+1:03}',**row) for i,row in enumerate(plan)]
    output=Path(args.output);output.mkdir(parents=True,exist_ok=False)
    report=dict(schema=1,status='starting',source_commit=args.source_commit,suite_sha256=sha(args.suite),
        protocol_sha256=sha(args.protocol),runner_sha256=sha(__file__),outputs_expected=len(plan),outputs_completed=0,
        production_modified=False,semantic_judging='manual_blind_review_pending',
        settings=dict(num_gpu=0,num_ctx=4096,num_thread=4,num_predict=160,keep_alive=0,temperature=0,seed=42),
        models=config['models'])
    write(output/'key.json',plan);write(output/'run.json',report)
    arms={a['id']:a for a in config['arms']};rows=[];started=time.perf_counter()
    for job in plan:
        arm=arms[job['arm_id']];pinned=config['models'][arm['role']]
        client=CaptureModel(ModelSettings(args.url,pinned['model'],pinned['digest']))
        data=prepare(cases[job['case_id']],arm,proposal=config['proposals'].get(job['case_id']))
        before=time.perf_counter();record=dict(**job)
        try:
            value,stats=client._generate(data['context'],data['schema'],data['prompt'],data['parse'])
            record.update(technical=dict(contract_valid=True,parsed=value),runtime=stats)
        except (ModelError,ValueError) as error:
            record.update(technical=dict(contract_valid=False),error=str(error)[:160])
        record.update(raw=client.raw,seconds=time.perf_counter()-before)
        rows.append(record)
        with (output/'responses.jsonl').open('a',encoding='utf-8') as stream:stream.write(canonical(record)+'\n')
        report.update(status='generating',outputs_completed=len(rows));write(output/'run.json',report)
        print(canonical(dict(completed=len(rows),total=len(plan))),flush=True)
    blind=[]
    for case_id in config['case_ids']:
        case=cases[case_id]
        for role in ('primary','critic'):
            blind.append(dict(case_id=case_id,role=role,context=case['context'],review_notes=case['review_notes'],
                proposal=config['proposals'][case_id] if role=='critic' else None,
                responses=[dict(response_id=r['response_id'],raw=r['raw'],error=r.get('error'))
                           for r in rows if r['case_id']==case_id and r['role']==role]))
    write(output/'blind.json',blind)
    report.update(status='complete',elapsed_seconds=time.perf_counter()-started)
    write(output/'run.json',report)
    return 0


def main():
    p=argparse.ArgumentParser(description=__doc__)
    for name in ('suite','protocol','output','source-commit'):p.add_argument('--'+name,required=True)
    p.add_argument('--url',default='http://127.0.0.1:11435')
    return run(p.parse_args())


if __name__=='__main__':raise SystemExit(main())
