"""Bounded synthetic workload driver. Run only against prepare.py's isolated server."""
from pathlib import Path
import argparse
import json
import os
import shutil
import subprocess
import sys
import time

ROOT = Path(__file__).resolve().parents[2]
from rcon import command as rcon_command, Failure as RconFailure

def lines(path):
    if not path.is_file():
        return []
    result = []
    for line in path.read_text(encoding='utf-8').splitlines():
        try:
            result.append(json.loads(line))
        except json.JSONDecodeError:
            pass  # A concurrent append can leave only the final line incomplete.
    return result

class Run:
    def __init__(self, output):
        self.output = output
        assert (output / '.dead-earth-loadtest').is_file()
        self.install = json.loads((output / 'installation.json').read_text())
        self.server = Path(self.install['directories']['server'])
        self.client = Path(self.install['directories']['client'])
        props = dict(line.split('=', 1) for line in (self.server / 'server.properties').read_text().splitlines()
                     if line and not line.startswith('#') and '=' in line)
        assert props['server-ip'] == '127.0.0.1' and props['server-port'] == '25568'
        assert props['rcon.port'] == '25578' and props['level-name'] == 'performance_test'
        self.password = props['rcon.password']
        self.phases = []
        self.jfr_client_pid = None
        runtime=os.environ.get('JAVA_HOME')
        java=shutil.which('java')
        self.jdk=(Path(runtime)/'bin' if runtime else Path(java).resolve().parent if java else
                  Path(os.environ.get('APPDATA',''))/'PrismLauncher/java/java-runtime-delta/bin')
        if not (self.jdk/'jcmd.exe').is_file():
            raise RuntimeError('Set JAVA_HOME to the Java 21 JDK used for this disposable test')

    def command(self, text):
        started = time.time()
        try:
            reply = rcon_command(self.password, text)
            state = 'received'
        except RconFailure as error:
            reply, state = None, error.state
        row = dict(at=started, command=text, state=state, elapsedSeconds=time.time()-started, reply=reply)
        with (self.output / 'commands.jsonl').open('a', encoding='utf-8') as log:
            log.write(json.dumps(row, ensure_ascii=False) + '\n')
        if state != 'received':
            raise RuntimeError(f'Command outcome {state}; no automatic mutation retry: {text}')
        if text.startswith('de_bench '):
            try:
                json.loads(reply)
            except ValueError:
                raise RuntimeError('Diagnostic command was rejected: '+reply[:180]) from None
        return reply

    def control(self, name, value):
        temporary = self.output / ('.' + name + '.tmp')
        temporary.write_text(value, encoding='ascii')
        temporary.replace(self.output / name)

    def last_server(self):
        path = self.output / 'server-metrics.jsonl'
        if not path.is_file():
            return None
        with path.open('rb') as stream:
            stream.seek(0,2)
            size=stream.tell()
            stream.seek(max(0,size-65536))
            tail=stream.read().decode('utf-8',errors='replace')
        for line in reversed(tail.splitlines()):
            try:
                return json.loads(line)
            except json.JSONDecodeError:
                continue
        return None

    def wait(self, predicate, timeout, description):
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            if predicate():
                return
            last = self.last_server()
            if last and last['freePhysicalBytes'] < 1_500_000_000:
                raise RuntimeError('Memory guard: free physical memory below 1.5GB')
            time.sleep(1)
        raise TimeoutError(description)

    def jobs_done(self):
        row = self.last_server()
        return row and row['jobsRemaining'] == 0 and time.time()*1000-row['wallTimeMs'] < 8000

    def phase(self, name, duration=25, command=None, jobs=False, timeout=180, shader=None):
        self.control('phase.txt', name)
        if shader is not None:
            self.control('shader.txt', 'on' if shader else 'off')
        row = dict(name=name, startMs=int(time.time()*1000), requestedSeconds=duration,
                   command=command, shaderRequested=shader, cameraMode='expected_static', requireExpectedCamera=True)
        print('START', name, flush=True)
        if command:
            row['commandReply'] = self.command(command)
        if jobs:
            after_command_ms=int(time.time()*1000)
            def settled():
                last=self.last_server()
                return self.jobs_done() and last['wallTimeMs']>after_command_ms+1000
            self.wait(settled, timeout, 'Queued setup did not settle: '+name)
        if command and command.startswith('de_bench entities '):
            parts=command.split();count=int(parts[2]);entity=parts[3]
            if ':' not in entity:entity='minecraft:'+entity
            ai=parts[4]=='true';actual=self.last_server()
            if (not actual or actual['jobsRemaining'] or actual['benchEntities']!=count
                    or actual['benchWithAi']!=(count if ai else 0)
                    or count and actual['benchEntityTypes']!={entity:count}):
                raise RuntimeError('Native entity workload did not match request before '+name)
            row['validatedTaggedWorkload']=dict(count=count,entity=entity,ai=ai)
        row['measurementStartMs']=int(time.time()*1000)
        deadline = time.monotonic() + duration
        while time.monotonic() < deadline:
            last = self.last_server()
            if last and last['freePhysicalBytes'] < 1_500_000_000:
                raise RuntimeError('Memory guard during '+name)
            time.sleep(1)
        row['endMs'] = int(time.time()*1000)
        row['lastServer'] = self.last_server()
        self.phases.append(row)
        (self.output / 'phases.json').write_text(json.dumps(self.phases, indent=2), encoding='utf-8')
        last = row['lastServer']
        print('END', name, json.dumps({key:last.get(key) for key in ('fullChunks','entityTickingChunks','entities','mobsWithAi','mobsWithTarget','heapUsed','gcMillis','jobsRemaining')}), flush=True)

    def execute(self):
        self.wait(lambda:(self.output / 'server-ready.json').is_file(), 240, 'Test server startup')
        for command in ('gamerule doMobSpawning false', 'gamerule doMobLoot false',
                        'gamerule doDaylightCycle false', 'gamerule maxEntityCramming 0', 'time set midnight'):
            self.command(command)
        # Native predicate/write-path proof runs outside performance phases.
        if not (self.output/'protection.json').is_file():
            self.command('de_bench protection')
            self.wait(lambda:(self.output/'protection.json').is_file() or (self.output/'protection-error.json').is_file(),90,'Protection probe')
        protection=json.loads((self.output/'protection.json').read_text())
        assert protection['arenaRestored'] and len(protection['rows'])==10
        print('PROTECTION native proof retained, arena restored',flush=True)
        self.command('de_bench arena')
        time.sleep(2)
        self.wait(self.jobs_done, 180, 'Arena setup')
        self.command('jfr start')
        self.wait(lambda:any(r.get('type') == 'settings' for r in lines(self.output/'client-metrics.jsonl')), 240, 'Client join')
        client_rows = lines(self.output/'client-metrics.jsonl')
        self.jfr_client_pid = next(r['pid'] for r in client_rows if r.get('type') == 'settings')
        command = [str(self.jdk/'jcmd.exe'), str(self.jfr_client_pid), 'JFR.start', 'name=deadearth_bench',
                   'settings=profile', 'maxsize=128m', 'filename='+str(self.output/'client.jfr')]
        jfr = subprocess.run(command, capture_output=True, text=True, timeout=20)
        (self.output/'client-jfr-start.txt').write_text(jfr.stdout+jfr.stderr, encoding='utf-8')
        self.command('tp @a 0.5 101 -35.5 0 15')
        self.command('effect give @a minecraft:resistance infinite 10 true')
        self.command('effect give @a minecraft:regeneration infinite 10 true')
        self.command('effect give @a minecraft:saturation infinite 10 true')
        self.command('execute as @a run attribute @s minecraft:generic.knockback_resistance base set 1')
        # Knockback resistance alone did not keep the camera fixed in native tests.
        self.command('kill @e[tag=dead_earth_anchor]')
        self.command('summon minecraft:armor_stand 0.5 101.6 -35.5 {Tags:["dead_earth_anchor"],Marker:1b,NoGravity:1b,Invulnerable:1b,Invisible:1b}')
        self.command('ride @p mount @e[tag=dead_earth_anchor,limit=1]')
        registry = json.loads(self.command('de_bench registry'))
        (self.output/'registry.json').write_text(json.dumps(registry, indent=2), encoding='utf-8')

        self.phase('baseline_shader_on', 30, shader=True)
        self.phase('baseline_shader_off', 30, shader=False)
        self.phase('baseline_shader_on_repeat', 25, shader=True)
        for count in (64,256,1024):
            self.phase('chunks_cold_'+str(count), 5, command=f'de_bench chunks {count}', jobs=True, timeout=240)
            self.phase('chunks_warm_'+str(count), 25)
        self.phase('chunks_unload', 32, command='de_bench chunks 0', jobs=True)
        for count in (250,750,1500):
            self.phase('zombies_ai_'+str(count), 30, command=f'de_bench entities {count} zombie true', jobs=True)
        self.phase('zombies_noai_1500_shader_on', 30, command='de_bench entities 1500 zombie false', jobs=True)
        self.phase('zombies_noai_1500_shader_off', 30, shader=False)
        self.phase('zombies_noai_1500_shader_on_repeat', 25, shader=True)
        parasite = next((name for name in registry['entities'] if name.endswith(':assimilated_human')), None)
        if parasite is None:
            parasite = next((name for name in registry['entities'] if 'assimilated' in name and 'cow' in name), None)
        if parasite:
            self.phase('parasites_ai_250', 30, command=f'de_bench entities 250 {parasite} true', jobs=True)
        self.phase('entities_cleanup', 10, command='de_bench entities 0 zombie false', jobs=True)
        self.phase('six_warehouses_setup', 5, command='de_bench warehouses 6', jobs=True)
        self.phase('six_warehouses_active', 35)
        self.phase('winter_six_warehouses', 35, command='de_bench season ASH_WINTER')
        self.phase('heat_six_warehouses', 25, command='de_bench season HEAT')
        self.phase('save_flush', 12, command='save-all flush')
        self.command('jfr stop')

    def finish(self):
        if self.jfr_client_pid:
            command = [str(self.jdk/'jcmd.exe'),str(self.jfr_client_pid),'JFR.stop','name=deadearth_bench']
            try:
                result = subprocess.run(command,capture_output=True,text=True,timeout=20)
                (self.output/'client-jfr-stop.txt').write_text(result.stdout+result.stderr,encoding='utf-8')
            except subprocess.TimeoutExpired:
                pass
        self.control('phase.txt','FINISH')
        time.sleep(3)
        try:
            self.command('stop')
        except Exception as error:
            print('Owned server stop outcome requires log confirmation:',type(error).__name__,flush=True)

if __name__ == '__main__':
    parser=argparse.ArgumentParser()
    parser.add_argument('output',type=Path)
    run=Run(parser.parse_args().output.resolve())
    try:
        run.execute()
    finally:
        run.finish()
