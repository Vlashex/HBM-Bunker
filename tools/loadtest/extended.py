"""One bounded extension: anchored repeats, distinct native species and native camera paths."""
from pathlib import Path
import argparse
import json
import math
import time
from remaining import Remaining
from run import lines


class Extended(Remaining):
    def pose(self,x,y,z,yaw,pitch=15):
        commands=(f'tp @e[tag=dead_earth_anchor] {x:.3f} {y+.6:.3f} {z:.3f}',
                  f'tp @p {x:.3f} {y:.3f} {z:.3f} {yaw:.3f} {pitch:.3f}',
                  'ride @p mount @e[tag=dead_earth_anchor,limit=1]')
        for command in commands:
            self.command(command)

    def dynamic(self,name,count,entity='minecraft:zombie',duration=25,smoke=False,terrain=False):
        self.control('phase.txt',name)
        row=dict(name=name,startMs=int(time.time()*1000),measurementStartMs=int(time.time()*1000),
                 requestedSeconds=duration,cameraMode='planned_dynamic',smokeOnly=smoke,
                 expectedTaggedWorkload=dict(count=count,entity=entity,ai=bool(count)),
                 controller='native RCON marker teleport + player teleport/yaw + remount; target period 500ms',
                 plannedPath='loaded arena orbit' if not terrain else 'loaded terrain flight inside existing view-distance chunks')
        row['motionPlan']=dict(kind=row['plannedPath'],targetCommandStepMillis=500,commandsPerStep=3,
                               syntheticTeleportTrajectory=True,manualWalking=False)
        print('START',name,flush=True)
        started=time.monotonic();path=[];step=0
        while time.monotonic()-started<duration:
            elapsed=time.monotonic()-started
            angle=elapsed*math.tau/(10 if smoke else 20)
            if terrain:
                x=.5+72*math.sin(angle);y=135+8*math.sin(angle*.5);z=-35.5+48*math.cos(angle)
            else:
                x=.5+8*math.sin(angle);y=101;z=-35.5+4*math.cos(angle)
            yaw=40*math.sin(angle)
            stamp=int(time.time()*1000);self.pose(x,y,z,yaw)
            path.append(dict(sentAtMs=stamp,completedAtMs=int(time.time()*1000),x=x,y=y,z=z,yaw=yaw,pitch=15))
            step+=1;time.sleep(max(0,started+step*.5-time.monotonic()))
        row['endMs']=int(time.time()*1000)
        row['path']=path
        client=[item for item in lines(self.output/'client-metrics.jsonl') if item.get('type')=='window'
                and item.get('phase')==name and row['startMs']<=item['timestampMs']<=row['endMs']
                and item.get('measurementEligible') and not item.get('windowIconified')]
        server=[item for item in lines(self.output/'server-metrics.jsonl') if item.get('phase')==name
                and row['startMs']<=item['wallTimeMs']<=row['endMs']]
        ranges={key:[min(item['camera'][key] for item in client),max(item['camera'][key] for item in client)]
                for key in ('x','y','z','yaw','pitch')} if client else {}
        row['observedCameraRanges']=ranges;row['eligibleClientWindows']=len(client)
        row['observedCameraSpan']={key:bounds[1]-bounds[0] for key,bounds in ranges.items()}
        row['actualTaggedCounts']=sorted({item['benchEntities'] for item in server})
        row['actualTaggedAi']=sorted({item['benchWithAi'] for item in server})
        row['actualTaggedTargets']=sorted({item['benchWithTarget'] for item in server})
        row['lastServer']=self.last_server()
        failures=[]
        if not client or not ranges or max(ranges.get('x',[0,0]))-min(ranges.get('x',[0,0]))<1 or ranges.get('yaw',[0,0])[1]-ranges.get('yaw',[0,0])[0]<10:
            failures.append('Dynamic camera displacement/yaw was not observed in eligible non-iconified client windows')
        if not server or any(item['jobsRemaining'] or item['benchEntities']!=count or item['benchWithAi']!=(count if count else 0)
                             or (count and item['benchEntityTypes']!={entity:count}) for item in server):
            failures.append('Actual tagged dynamic workload did not retain the requested entities/AI')
        if failures:row['excludedReason']='; '.join(failures)
        self.phases.append(row)
        (self.output/'phases.json').write_text(json.dumps(self.phases,indent=2),encoding='utf-8')
        print('END',name,json.dumps(dict(camera=ranges,actual=row['actualTaggedCounts'],ai=row['actualTaggedAi'],targets=row['actualTaggedTargets'],steps=len(path),excluded=row.get('excludedReason'))),flush=True)
        self.pose(.5,101,-35.5,0)
        if failures:raise RuntimeError(row['excludedReason'])

    def execute(self):
        self.wait(lambda:(self.output/'server-ready.json').is_file(),240,'Owned test server startup')
        self.wait(lambda:any(row.get('type')=='settings' for row in lines(self.output/'client-metrics.jsonl')),240,'Owned test client join')
        # Remove only the six owned synthetic fixtures to match the pre-storage AI workload.
        for zone in range(6):
            x=400+zone*80
            print('FIXTURE CLEANUP',self.command(f'fill {x} 139 0 {x+14} 141 16 minecraft:air replace'),flush=True)
        self.command('de_bench warehouses 0')
        sent_ms=int(time.time()*1000)
        def cleared():
            row=self.last_server()
            return row and row['wallTimeMs']>sent_ms+1000 and not row['jobsRemaining'] and row['loaders']==0 and row['zones']==0 and row['tickingHoppers']==0
        self.wait(cleared,90,'Only owned synthetic warehouses removed')
        (self.output/'fixture-cleanup.json').write_text(json.dumps(self.last_server(),indent=2),encoding='utf-8')
        self.entity_counts=(250,750)
        super().execute()
        print('JFR',self.command('jfr start'),flush=True)
        self.dynamic('dynamic_smoke_zombies_750',750,duration=5,smoke=True)
        self.dynamic('dynamic_zombies_ai_750',750)
        for name,count in (('mutationcraft:assimilated_spider',250),('mutationcraft:parasitic_ram',100)):
            self.workload(count,name)
            self.measure('validated_'+name.split(':')[1]+'_ai_'+str(count),count,name)
        self.workload(250,'mutationcraft:assimilated_human')
        self.measure('validated_assimilated_human_static_250',250,'mutationcraft:assimilated_human')
        self.dynamic('dynamic_assimilated_human_ai_250',250,'mutationcraft:assimilated_human')
        self.workload(0,ai=False)
        self.measure('validated_loaded_terrain_static',0,ai=False)
        self.dynamic('dynamic_loaded_terrain',0,terrain=True)
        print('JFR',self.command('jfr stop'),flush=True)


if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('output',type=Path)
    run=Extended(parser.parse_args().output.resolve())
    try:run.execute()
    finally:run.finish()
