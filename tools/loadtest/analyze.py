"""Reduce observed telemetry, keeping rejected workloads and warmup distinct."""
from pathlib import Path
import argparse
import json
import math
import statistics

def rows(path):
    result=[]
    for line in path.read_text(encoding='utf-8').splitlines():
        try: result.append(json.loads(line))
        except json.JSONDecodeError: continue
    return result

def percentile(values,fraction):
    if not values: return None
    ordered=sorted(values)
    return ordered[max(0,math.ceil(len(ordered)*fraction)-1)]

def distribution(values):
    return dict(count=len(values),mean=statistics.fmean(values) if values else None,
                p50=percentile(values,.5),p95=percentile(values,.95),p99=percentile(values,.99),max=max(values) if values else None)

def analyze(folder):
    phases=json.loads((folder/'phases.json').read_text())
    server=rows(folder/'server-metrics.jsonl')
    client=rows(folder/'client-metrics.jsonl')
    commands=rows(folder/'commands.jsonl') if (folder/'commands.jsonl').is_file() else []
    motion=json.loads((folder/'motion-samples.json').read_text()) if (folder/'motion-samples.json').is_file() else {}
    results=[]
    for phase in phases:
        if phase.get('cameraMode')=='planned_dynamic' and not phase.get('motionPlan'):
            phase['motionPlan']=dict(kind=phase.get('plannedPath'),targetCommandStepMillis=500,commandsPerStep=3,syntheticTeleportTrajectory=True,manualWalking=False)
        if phase['name']=='dynamic_loaded_terrain':
            phase['motionPlan']['kind']='terrain travel, possible edge loading'
            phase['routeWarmupConfirmed']=False
        save_transition=(phase.get('command') or '').startswith('save-all')
        transient=phase['name'].startswith('chunks_cold_') or phase['name']=='chunks_unload' or save_transition
        start=phase['startMs'] if transient else phase.get('measurementStartMs',phase['startMs'])+3000
        end=phase['endMs']-1000
        mob_motion=motion.get(phase['name'])
        if mob_motion and not mob_motion.get('error'):
            start=max(start,mob_motion['beforeSamplesEndMs']);end=min(end,mob_motion['afterSamplesStartMs'])
        sr=[r for r in server if (transient or r.get('phase')==phase['name']) and start<=r['wallTimeMs']<=end
            and (transient or r['jobsRemaining']==0)
            and (not mob_motion or r['wallTimeMs']-r['windowNanos']/1e6>=start)]
        cr=[r for r in client if r.get('type')=='window' and (transient or r.get('phase')==phase['name'])
            and start<=r['timestampMs']<=end and r.get('measurementEligible')
            and not r.get('windowIconified') and (not mob_motion or r.get('windowStartMs',r['timestampMs'])>=start)]
        all_client=[r for r in client if r.get('type')=='window' and (transient or r.get('phase')==phase['name']) and start<=r['timestampMs']<=end]
        tick=[n/1e6 for r in sr for n in r['tickNanos']]
        vanilla=[n/1e6 for r in sr for n in r['vanillaTickNanos']]
        frame=[n/1e6 for r in cr for n in r.get('frameIntervalsNanos',[])]
        raw_tps=sum(r['ticks'] for r in sr)/sum(r['windowNanos']/1e9 for r in sr) if sr else None
        result=dict(name=phase['name'],startMs=start,endMs=end,excludedReason=phase.get('excludedReason'),
                    measurementKind='save_flush_transition' if save_transition else 'forcing_generation_transition' if transient else 'settled_steady_state',
                    cameraMode=phase.get('cameraMode','expected_static'),smokeOnly=phase.get('smokeOnly',False),
                    motionPlan=phase.get('motionPlan'),
                    mobMotion=mob_motion,
                    routeWarmupConfirmed=phase.get('routeWarmupConfirmed'),
                    serverWindows=len(sr),clientWindows=len(cr),serverMspt=distribution(tick),
                    vanillaMspt=distribution(vanilla),rawTps=raw_tps,tps=min(20,raw_tps) if raw_tps is not None else None,
                    tickOver50Pct=sum(n>50 for n in tick)*100/len(tick) if tick else None,
                    frameMs=distribution(frame),fps=len(frame)*1000/sum(frame) if frame else None,
                    frameOver50Pct=sum(n>50 for n in frame)*100/len(frame) if frame else None)
        if phase.get('command'):
            match=next((r for r in commands if r['command']==phase['command'] and phase['startMs']<=int(r['at']*1000)<=phase.get('measurementStartMs',phase['endMs'])),None)
            if match:result['commandRttSeconds']=match['elapsedSeconds'];result['commandRttMeaning']='queue + command + RCON wall bound; not exclusive command CPU time'
        for key in ('fullChunks','entityTickingChunks','tickingChunks','tickingBlockEntities','entities','mobs','mobsWithAi','mobsWithTarget','items','heapUsed','freePhysicalBytes','zones','loaders'):
            values=[r[key] for r in sr]
            result[key]=dict(min=min(values),median=statistics.median(values),max=max(values)) if values else None
        if sr:
            result['gcMillisDelta']=sr[-1]['gcMillis']-sr[0]['gcMillis']
            result['gcCountDelta']=sr[-1]['gcCount']-sr[0]['gcCount']
            result['processCpuCoreEquivalent']=(sr[-1]['processCpuNanos']-sr[0]['processCpuNanos'])/max(1,(sr[-1]['wallTimeMs']-sr[0]['wallTimeMs'])*1e6)
            result['lastEntityTypes']=sr[-1]['entityTypes']
        if cr:
            result['shaderStates']=list({json.dumps(r['shader'],sort_keys=True) for r in cr})
            result['shaderNativeVerified']=all(r['shader'].get('available') is True and r['shader'].get('fallback') is False and r['shader'].get('error') is False and r['shader'].get('enabled')==r['shader'].get('configuredEnabled') for r in cr)
            result['focusedWindows']=sum(r.get('windowFocused',False) for r in cr)
            result['renderedEntities']=distribution([r.get('entitiesRendered',0) for r in cr])
            result['framebufferSizes']=sorted({(r['framebufferWidth'],r['framebufferHeight']) for r in cr})
            result['cameraStart']=cr[0].get('camera');result['cameraEnd']=cr[-1].get('camera')
            expected=dict(x=.5,y=102.619999885559,z=-35.5,yaw=0,pitch=15)
            result['expectedCamera']=expected if phase.get('cameraMode')!='planned_dynamic' else None
            result['cameraStableAtExpected']=all(all(abs(r.get('camera',{}).get(k,float('inf'))-v)<.01 for k,v in expected.items()) for r in cr) if phase.get('cameraMode')!='planned_dynamic' else None
            result['observedCameraRanges']={key:[min(r['camera'][key] for r in cr),max(r['camera'][key] for r in cr)] for key in expected}
            result['observedCameraSpan']={key:bounds[1]-bounds[0] for key,bounds in result['observedCameraRanges'].items()}
            phase['observedCameraSpan']=result['observedCameraSpan']
            if (phase['name'].startswith('validated_') or phase.get('requireExpectedCamera')) and phase.get('cameraMode')!='planned_dynamic' and not result['cameraStableAtExpected']:
                result['excludedReason']=phase['excludedReason']='Camera moved away from the fixed comparison viewpoint during measured windows'
        result['clientWindowEligibility']=dict(total=len(all_client),accepted=len(cr),iconified=sum(bool(r.get('windowIconified')) for r in all_client),ineligible=sum(not r.get('measurementEligible') for r in all_client))
        if (phase['name'].startswith('validated_') or phase.get('cameraMode')=='planned_dynamic') and (not cr or not sr):
            result['excludedReason']=phase['excludedReason']='No eligible native server/client measurement windows'
        if not sr:result['excludedReason']=phase['excludedReason']='No native server windows in the requested interval'
        for key in ('benchEntities','benchWithAi','benchWithTarget','tickingHoppers','tickingChests'):
            values=[r[key] for r in sr if key in r]
            result[key]=dict(min=min(values),median=statistics.median(values),max=max(values)) if values else None
        if sr and 'benchEntityTypes' in sr[-1]:result['lastBenchEntityTypes']=sr[-1]['benchEntityTypes']
        if sr and 'benchEntityTypes' in sr[-1]:
            observed_types={key for r in sr for key in r['entityTypes']}
            result['nonBenchEntityTypesPeak']={key:max(r['entityTypes'].get(key,0)-r['benchEntityTypes'].get(key,0) for r in sr) for key in observed_types}
        results.append(result)
    (folder/'phases.json').write_text(json.dumps(phases,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    summary=dict(pack='5.2.0',probeIsOptIn=True,computerUse=False,screenshots=False,
                 limitations=['One real client; synthetic server load, not six real clients',
                              'Live game and CPU model co-resident on same PC',
                              'Instrumentation overhead was not measured by separate A/B run',
                              'Phases without real requested entities are excluded',
                              'Server Pre-to-Post includes mod post-tick work; vanilla tally excludes it'],
                 peaks={key:max(r[key] for r in server) for key in ('fullChunks','entityTickingChunks','entities','mobsWithAi','tickingBlockEntities','heapUsed')},
                 phases=results)
    (folder/'summary.json').write_text(json.dumps(summary,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    with (folder/'phases.tsv').open('w',encoding='utf-8') as output:
        for phase in phases: output.write(f"{phase['name']}\t{phase['startMs']}\t{phase['endMs']}\n")
    with (folder/'measurement-phases.tsv').open('w',encoding='utf-8') as output:
        for phase in results:
            if not phase['excludedReason'] and not phase['smokeOnly']:output.write(f"{phase['name']}\t{phase['startMs']}\t{phase['endMs']}\n")
    print(json.dumps(dict(peaks=summary['peaks'],phases=len(results),validPhases=sum(not r['excludedReason'] and not r['smokeOnly'] for r in results)),ensure_ascii=False))
    for row in results:
        if not row['excludedReason'] and not row['smokeOnly']:
            print(row['name'],'TPS=',round(row['tps'],2) if row['tps'] else None,'MSPTp95=',round(row['serverMspt']['p95'],2) if row['serverMspt']['p95'] else None,'FPS=',round(row['fps'],1) if row['fps'] else None)

if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('folder',type=Path)
    analyze(parser.parse_args().folder)
