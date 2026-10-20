"""Narrow native combat regression on port25587 in a NEW copy of a stopped isolated test world.

Never changes the template, user server/client, existing evidence, or production AI settings.
"""
import argparse
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import shutil
import socket
import subprocess
import time
import uuid
import zipfile
try:
    from . import legacy_port, legacy_server, verify_legacy_server
except ImportError:
    import legacy_port, legacy_server, verify_legacy_server

ROOT=Path(__file__).resolve().parents[1]
PORT=25587
HIDDEN=getattr(subprocess,'CREATE_NO_WINDOW',0)


def prepare(template,javac):
    given=Path(template).absolute()
    template=given.resolve(strict=True)
    if (os.path.normcase(str(given))!=os.path.normcase(str(template))
            or template.parent!=(ROOT/'work').resolve()
            or not (template/'.dead-earth-isolated-native-test').is_file()):
        raise ValueError('Use a real stopped isolated native template beneath repository work')
    receipt=json.loads((template/'runner-result.json').read_text(encoding='utf-8'))
    if receipt.get('exit_code')!=0 or receipt.get('timed_out') is not False:
        raise ValueError('Template did not finish and save normally')
    props,world,oldport=legacy_server.properties(template)
    with socket.socket() as listener:
        listener.bind(('127.0.0.1',PORT))
    with socket.socket() as probe:
        probe.settimeout(.5)
        if probe.connect_ex(('127.0.0.1',oldport))==0:
            raise ValueError('Isolated template server port is still active')
    target=ROOT/'work'/('legacy-combat-'+datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%S%fZ')+'-'+uuid.uuid4().hex[:8])
    target.mkdir()
    for name in (verify_legacy_server.FORGE_JAR,verify_legacy_server.MINECRAFT_JAR):
        shutil.copy2(template/name,target/name)
    shutil.copytree(template/'libraries',target/'libraries')
    verify_legacy_server.write_overlay(target)
    shutil.copytree(world,target/'probe_world')
    values={'server-ip':'127.0.0.1','server-port':PORT,'online-mode':'true','enable-rcon':'false',
            'enable-query':'false','view-distance':3,'max-players':2,'level-name':'probe_world',
            'level-type':'DEFAULT','allow-nether':'false','spawn-protection':0,'spawn-animals':'false',
            'spawn-monsters':'false','difficulty':2,'gamemode':0,'allow-flight':'true',
            'max-tick-time':60000,'motd':'DEAD EARTH isolated combat-only verification'}
    (target/'server.properties').write_text(''.join(str(k)+'='+str(v)+'\n' for k,v in values.items()),encoding='utf-8')
    for marker in ('.dead-earth-isolated-native-test','.dead-earth-combat-only'):
        (target/marker).write_text('Explicit new combat-only fixture; template untouched.\n',encoding='utf-8')
    (target/'eula.txt').write_text('eula=true\n',encoding='ascii')
    compile_probe(target,javac)
    (target/'combat-run.json').write_text(json.dumps(dict(schema=1,port=PORT,template=str(template),
        template_read_only=True,scope='native_combat_only',full_pack_retained=True),indent=2)+'\n',encoding='utf-8')
    return target


def compile_probe(target,javac):
    source=ROOT/'legacy/coordinator'
    sources=[source/'src/combattest/java/dev/deadearth/legacyprobe/combat/CombatOnlyProbe.java',
             source/'src/integration/java/dev/deadearth/legacyprobe/combat/SiegeProbe.java',
             source/'src/integration/java/dev/deadearth/legacyprobe/combat/ForgeSiegeProbe.java']
    stubs=sorted((source/'compile-only-stubs').rglob('*.java'))
    classes=target/'combat-classes';classes.mkdir()
    cp=os.pathsep.join(map(str,[target/'mods'/legacy_port.MOD_NAME,*legacy_port.forge_classpath(target)]))
    response=target/'javac-combat.args'
    legacy_port.argfile(response,['--release','8','-encoding','UTF-8','-proc:none','-classpath',cp,'-d',classes,*sources,*stubs])
    subprocess.run([str(javac),'@'+str(response)],cwd=target,check=True,timeout=90,creationflags=HIDDEN)
    with zipfile.ZipFile(target/'mods/deadearth-combat-probe.jar','w',zipfile.ZIP_DEFLATED) as jar:
        for path in sorted(classes.rglob('*.class')):
            name=path.relative_to(classes).as_posix()
            if name.startswith('dev/deadearth/legacyprobe/combat/'):
                jar.write(path,name)
        jar.writestr('mcmod.info',json.dumps([dict(modid='deadearth_combat_probe',name='Dead Earth Combat Only Probe',
            version='1.0',mcversion='1.12.2',serverSideOnly=True)]))


def run(target,java,timeout):
    report=target/'combat-probe.json'
    log=target/'combat-server.log'
    command=[str(java),'-Xms512M','-Xmx3072M','-Djava.awt.headless=true','-Ddeadearth.testControls=true',
             '-jar',str(target/verify_legacy_server.FORGE_JAR),'nogui']
    started=time.monotonic();timed_out=False
    print(json.dumps(dict(stage='starting_narrow_combat',directory=str(target),port=PORT)),flush=True)
    with log.open('w',encoding='utf-8') as output:
        process=subprocess.Popen(command,cwd=target,stdin=subprocess.PIPE,stdout=output,stderr=subprocess.STDOUT,
            text=True,encoding='utf-8',creationflags=HIDDEN)
        try:
            while process.poll() is None and not report.is_file():
                if time.monotonic()-started>timeout:
                    timed_out=True;break
                time.sleep(.5)
        finally:
            exit_code=verify_legacy_server.stop_with_save(process)
    result=dict(schema=1,scope='native_combat_only',exit_code=exit_code,timed_out=timed_out,
                elapsed_seconds=round(time.monotonic()-started,2),directory=str(target),report=str(report),passed=False)
    if report.is_file():
        value=json.loads(report.read_text(encoding='utf-8'))
        result.update(passed=value.get('passed') is True and exit_code==0 and not timed_out,
                      combat=value)
    else:result['error']='Native combat report absent'
    (target/'combat-runner-result.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print(json.dumps({k:v for k,v in result.items() if k!='combat'},ensure_ascii=False),flush=True)
    return 0 if result['passed'] else 1


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--template',type=Path,required=True)
    parser.add_argument('--javac',type=Path,required=True)
    parser.add_argument('--java8',type=Path)
    parser.add_argument('--timeout',type=int,default=150)
    parser.add_argument('--compile-only',action='store_true')
    args=parser.parse_args()
    if not 60<=args.timeout<=180:parser.error('Use bounded timeout60..180seconds')
    target=prepare(args.template,args.javac.resolve(strict=True))
    if args.compile_only:
        print(json.dumps(dict(prepared=str(target),server_started=False)));return 0
    return run(target,legacy_server.find_java8(args.java8),args.timeout)

if __name__=='__main__':
    raise SystemExit(main())
