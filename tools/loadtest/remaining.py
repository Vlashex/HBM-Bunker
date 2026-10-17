"""Validated remaining matrix, using only an explicitly prepared disposable rig."""
from pathlib import Path
import argparse
import json
import subprocess
import time
from run import Run, lines


class Remaining(Run):
    def workload(self, count, entity='minecraft:zombie', ai=True):
        self.control('phase.txt', 'setup_'+entity.replace(':', '_')+'_'+str(count))
        self.command(f'de_bench entities {count} {entity} {str(ai).lower()}')
        sent_ms=int(time.time()*1000)
        def ready():
            row=self.last_server()
            if not row or row['wallTimeMs']<=sent_ms+1000 or row['jobsRemaining']:
                return False
            actual=row['benchEntities']
            types=row['benchEntityTypes']
            if actual!=count or (count and types!={entity:count}):
                raise RuntimeError(f'Actual tagged workload {actual}, {types}; requested {count} {entity}')
            if row['benchWithAi']!=(count if ai else 0):
                raise RuntimeError(f'AI mismatch: {row["benchWithAi"]}, wanted {count if ai else 0}')
            print('ACTUAL', json.dumps({key:row[key] for key in ('wallTimeMs','jobsRemaining','benchEntities','benchWithAi','benchWithTarget','benchEntityTypes')}), flush=True)
            return True
        self.wait(ready, 240, 'Fresh actual entity workload did not settle')

    def measure(self, name, count, entity='minecraft:zombie', ai=True, shader=None):
        if shader is not None:
            self.control('shader.txt', 'on' if shader else 'off')
            sent_ms=int(time.time()*1000)
            def shader_ready():
                rows=lines(self.output/'client-metrics.jsonl')
                row=next((row for row in reversed(rows) if row.get('type')=='window'),None)
                return (row and row['timestampMs']>sent_ms+3000 and row.get('measurementEligible')
                        and row['shader'].get('enabled') is shader and row['shader'].get('configuredEnabled') is shader
                        and row['shader'].get('fallback') is False and row['shader'].get('error') is False)
            self.wait(shader_ready, 90, 'Native shader state failed to settle')
        self.phase(name, 25)
        row=self.phases[-1]
        windows=[item for item in lines(self.output/'server-metrics.jsonl')
                 if item.get('phase')==name and row['measurementStartMs']+3000<=item['wallTimeMs']<=row['endMs']-1000]
        bad=[item for item in windows if item['jobsRemaining'] or item['benchEntities']!=count
             or item['benchWithAi']!=(count if ai else 0)
             or (count and item['benchEntityTypes']!={entity:count})]
        row['validatedTaggedWorkload']=dict(count=count,entity=entity,ai=ai,windows=len(windows))
        if bad or not windows:
            row['excludedReason']='Fresh measured windows do not retain the requested actual tagged entities and AI'
        (self.output/'phases.json').write_text(json.dumps(self.phases,indent=2),encoding='utf-8')
        if row.get('excludedReason'):
            raise RuntimeError(row['excludedReason'])

    def execute(self):
        self.wait(lambda:(self.output/'server-ready.json').is_file(),240,'Owned test server startup')
        self.wait(lambda:any(row.get('type')=='settings' for row in lines(self.output/'client-metrics.jsonl')),240,'Owned test client join')
        self.jfr_client_pid=next(row['pid'] for row in reversed(lines(self.output/'client-metrics.jsonl')) if row.get('type')=='settings')
        for text in ('gamerule doMobSpawning false','gamerule doMobLoot false','gamerule doDaylightCycle false',
                     'gamerule maxEntityCramming 0','time set midnight','de_bench season CALM',
                     'gamemode survival @a','team add de_bench_player','team modify de_bench_player collisionRule never',
                     'team join de_bench_player @a','tp @a 0.5 101 -35.5 0 15',
                     'kill @e[tag=dead_earth_anchor]',
                     'summon minecraft:armor_stand 0.5 101.6 -35.5 {Tags:["dead_earth_anchor"],Marker:1b,NoGravity:1b,Invulnerable:1b,Invisible:1b}',
                     'ride @p mount @e[tag=dead_earth_anchor,limit=1]',
                     'effect give @a minecraft:resistance infinite 10 true',
                     'effect give @a minecraft:regeneration infinite 10 true',
                     'effect give @a minecraft:saturation infinite 10 true',
                     'execute as @a run attribute @s minecraft:generic.knockback_resistance base set 1'):
            print('CONTROL',text,self.command(text),flush=True)
        registry=json.loads(self.command('de_bench registry'))
        (self.output/'registry.json').write_text(json.dumps(registry,indent=2),encoding='utf-8')
        # Regression smoke proves two successful create/cleanup cycles before any mass phase.
        self.workload(0,ai=False)
        for count in (3,0,3,0):
            self.workload(count,ai=bool(count))
        (self.output/'smoke.json').write_text(json.dumps(dict(cleanupCycles=2,sequence=[3,0,3,0],passed=True,timestampMs=int(time.time()*1000))),encoding='utf-8')
        print('SMOKE PASSED 3 -> 0 -> 3 -> 0',flush=True)
        print('JFR',self.command('jfr start'),flush=True)
        result=subprocess.run([str(self.jdk/'jcmd.exe'),str(self.jfr_client_pid),'JFR.start','name=deadearth_bench',
                               'settings=profile','maxsize=128m','filename='+str(self.output/'client.jfr')],capture_output=True,text=True,timeout=20)
        (self.output/'client-jfr-start.txt').write_text(result.stdout+result.stderr,encoding='utf-8')
        if result.returncode:
            raise RuntimeError('Owned client JFR did not start')
        for count in getattr(self,'entity_counts',None) or (250,750,1500):
            self.workload(count)
            self.measure('validated_zombies_ai_'+str(count),count,shader=True if count==250 else None)
        if getattr(self,'entity_counts',None):
            print('JFR',self.command('jfr stop'),flush=True)
            return
        self.workload(1500,ai=False)
        self.measure('validated_zombies_noai_1500_on',1500,ai=False,shader=True)
        self.measure('validated_zombies_noai_1500_off',1500,ai=False,shader=False)
        self.measure('validated_zombies_noai_1500_on_repeat',1500,ai=False,shader=True)
        parasite='mutationcraft:assimilated_human'
        assert parasite in registry['entities']
        self.workload(250,parasite)
        self.measure('validated_parasites_ai_250',250,parasite)
        self.workload(0,ai=False)
        self.control('phase.txt','warehouses_setup')
        print('CONTROL warehouses',self.command('de_bench warehouses 6'),flush=True)
        sent_ms=int(time.time()*1000)
        def warehouses_ready():
            row=self.last_server()
            return row and row['wallTimeMs']>sent_ms+1000 and not row['jobsRemaining'] and row['tickingHoppers']>=384 and row['tickingChests']>=768 and row['zones']>=6 and row['loaders']>=6
        self.wait(warehouses_ready,180,'Actual 384 ticking hoppers, 768 chests and six zones')
        (self.output/'warehouse-proof.json').write_text(json.dumps(self.last_server(),indent=2),encoding='utf-8')
        self.measure('validated_six_warehouses_active',0,ai=False)
        print('CONTROL season',self.command('de_bench season ASH_WINTER'),flush=True)
        self.measure('validated_winter_six_warehouses',0,ai=False)
        print('CONTROL season',self.command('de_bench season HEAT'),flush=True)
        self.measure('validated_heat_six_warehouses',0,ai=False)
        self.phase('validated_save_flush',20,command='save-all flush')
        print('CONTROL season',self.command('de_bench season CALM'),flush=True)
        self.command('tp @a 0.5 101 -35.5 0 15')
        self.command('ride @p mount @e[tag=dead_earth_anchor,limit=1]')
        self.measure('validated_warm_baseline_off',0,ai=False,shader=False)
        self.measure('validated_warm_baseline_on',0,ai=False,shader=True)
        self.measure('validated_warm_baseline_off_repeat',0,ai=False,shader=False)
        print('JFR',self.command('jfr stop'),flush=True)


if __name__=='__main__':
    parser=argparse.ArgumentParser()
    parser.add_argument('output',type=Path)
    parser.add_argument('--entity-counts',type=int,nargs='+',choices=(250,750,1500))
    args=parser.parse_args()
    run=Remaining(args.output.resolve())
    run.entity_counts=args.entity_counts
    try:
        run.execute()
    finally:
        run.finish()
