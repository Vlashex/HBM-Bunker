"""Native UUID/position samples outside accepted dynamic measurement windows."""
from pathlib import Path
import argparse
import json
import math
import re
import time
from run import Run


def entity_data(run,tag):
    selector=f'@e[tag={tag},limit=1]'
    position_reply=run.command(f'data get entity {selector} Pos')
    match=re.search(r'\[([-+0-9.eEdD, ]+)\]',position_reply)
    if not match:raise RuntimeError('No native position for '+tag)
    position=[float(value.strip().removesuffix('d').removesuffix('D')) for value in match[1].split(',')]
    uuid_reply=run.command(f'data get entity {selector} UUID')
    ident=re.search(r'\[I;([^\]]+)\]',uuid_reply)
    if not ident:raise RuntimeError('No native UUID for '+tag)
    return dict(tag=tag,uuid=[int(value.strip()) for value in ident[1].split(',')],position=position,timestampMs=int(time.time()*1000))


if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('output',type=Path)
    run=Run(parser.parse_args().output.resolve())
    saved=run.output/'motion-samples.json';results=json.loads(saved.read_text()) if saved.is_file() else {}
    wanted={'dynamic_zombies_ai_750','dynamic_assimilated_human_ai_250'}-results.keys()
    while wanted:
        try:phase=(run.output/'phase.txt').read_text().strip()
        except (PermissionError,FileNotFoundError):time.sleep(.1);continue
        if phase=='FINISH':break
        if phase not in wanted:time.sleep(.1);continue
        wanted.remove(phase);row=dict(phase=phase,before=[],after=[])
        phase_started=(run.output/'phase.txt').stat().st_mtime
        try:
            for index in range(2):
                tag='de_motion_'+phase+'_'+str(index)
                run.command(f'tag @e[tag=dead_earth_bench,tag=!de_motion_sampled,limit=1,sort=nearest] add {tag}')
                run.command(f'tag @e[tag={tag},limit=1] add de_motion_sampled')
                row['before'].append(entity_data(run,tag))
            row['beforeSamplesEndMs']=int(time.time()*1000)
            time.sleep(max(0,phase_started+19-time.time()))
            row['afterSamplesStartMs']=int(time.time()*1000)
            for before in row['before']:
                after=entity_data(run,before['tag']);after['sameUuid']=after['uuid']==before['uuid']
                after['displacementBlocks']=math.dist(after['position'],before['position'])
                row['after'].append(after)
            print('NATIVE MOB MOTION',phase,json.dumps(row),flush=True)
        except Exception as error:
            row['error']=str(error);print('MOTION SAMPLE ERROR',phase,str(error),flush=True)
        results[phase]=row
        (run.output/'motion-samples.json').write_text(json.dumps(results,indent=2),encoding='utf-8')
