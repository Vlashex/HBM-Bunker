"""Final-stage actual GL evidence using an already successful, stopped, new native fixture.

The wrapper compiles only VisualShowcase.java, preserves native/restore probe JARs
outside mods, starts its own loopback server, prepares a third isolated client,
runs the existing framebuffer probe, and sends normal stdin stop in finally.
No manual server/client, launcher profile, account, AI process or production world
is read or controlled. All evidence is retained under this fork's work/ directory.

Usage:
  python tools/verify_legacy_visual.py --server-dir work/legacy-native-<new-success> \
    --forge-dir work/storage-server-20261020 --javac <JDK>/bin/javac.exe \
    --java8 <Java8>/bin/java.exe --offline
"""
import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import time
import uuid
import zipfile

try:
    from . import legacy_port, legacy_server, verify_legacy_client, verify_legacy_server
except ImportError:
    import legacy_port, legacy_server, verify_legacy_client, verify_legacy_server

ROOT=Path(__file__).resolve().parents[1]
HIDDEN=getattr(subprocess,'CREATE_NO_WINDOW',0)
COMPANION='deadearth-visual-showcase.jar'
SOURCE=ROOT/'legacy/coordinator/src/integration/java/dev/deadearth/legacyprobe/VisualShowcase.java'


def owned_native(directory):
    supplied=Path(directory).absolute();target=supplied.resolve(strict=True);work=(ROOT/'work').resolve(strict=True)
    if os.path.normcase(str(supplied))!=os.path.normcase(str(target)) or target.parent!=work or not target.name.startswith('legacy-native-'):
        raise ValueError('Use a real, directly owned work/legacy-native-* fixture from the final native run')
    if not (target/'.dead-earth-isolated-native-test').is_file():raise ValueError('Native isolation marker is absent')
    for relative in ('mods','config','server.properties','.dead-earth-isolated-native-test'):
        path=target/relative
        if path.is_symlink() or not path.resolve(strict=True).is_relative_to(target):raise ValueError('Native fixture input escapes its owned directory')
    props,world,port=legacy_server.properties(target)
    if world!=target/'probe_world' or props.get('enable-rcon')!='false' or port not in (25585,25587):raise ValueError('Unexpected native fixture world or transport')
    if not world.resolve(strict=True).is_relative_to(target):raise ValueError('Linked native fixture world')
    previous=verify_legacy_client.read_json(target/'runner-result.json')
    if previous.get('passed') is not True or previous.get('exit_code')!=0:raise ValueError('Full native fixture must have passed and stopped normally first')
    if previous.get('server_directory') and Path(previous['server_directory']).resolve()!=target:raise ValueError('Native runner evidence names a different fixture')
    report_name='native-port-probe.json' if (target/'native-port-probe.json').is_file() else 'port-probe.json'
    report=verify_legacy_client.read_json(target/report_name,8*1024*1024)
    if report.get('passed') is not True or report.get('productVersion')!=legacy_port.VERSION or previous.get('product_version')!=legacy_port.VERSION:raise ValueError('Current native gameplay/worldgen slice did not pass')
    land=report.get('fullGroups',{}).get('worldgen',{})
    if land.get('nativePass') is not True or land.get('actualDeterministicCuts',0)<=0:raise ValueError('No successful actual generated wasteland site in native evidence')
    if land.get('sampledIndependentRegions',0)<2 or land.get('unconvertedNaturalSoilColumns')!=0 or land.get('reservedPaintedSoilColumns',0)<=0:raise ValueError('Current multi-region natural/reserved surface coverage evidence is absent')
    campaign=verify_legacy_client.read_json(world/'data/dead_earth_legacy.json',8*1024*1024)
    epoch=str(uuid.UUID(campaign['worldEpoch']))
    if epoch!=campaign['worldEpoch']:raise ValueError('Noncanonical saved world epoch')
    # A stopped native result plus free old/new ports is required before any configuration/JAR move.
    for test_port in sorted({port,verify_legacy_client.PORT}):legacy_server.free_port(test_port)
    for name in ('visual-showcase-request.json','client-showcase.json','visual-verification.json',COMPANION):
        if (target/name).exists() or (target/'mods'/name).exists():raise ValueError('This fixture already has visual evidence; preserve it and use a fresh successful native run')
    return target,props,world,report_name,epoch


def compile_companion(target,forge_dir,javac):
    compiler=Path(javac).resolve(strict=True);coordinator=target/'mods'/legacy_port.MOD_NAME
    if not compiler.is_file() or not coordinator.is_file():raise ValueError('Explicit javac and installed verified coordinator JAR are required')
    if hashlib.sha256(coordinator.read_bytes()).digest()!=hashlib.sha256(legacy_port.compiled_jar(ROOT)).digest():raise ValueError('Native fixture coordinator differs from the current compiled slice')
    build=target/'visual-showcase-build';build.mkdir();classes=build/'classes';classes.mkdir()
    cp=os.pathsep.join(map(str,[coordinator,*legacy_port.forge_classpath(forge_dir)]))
    arguments=['--release','8','-encoding','UTF-8','-proc:none','-classpath',cp,'-d',classes,SOURCE]
    argfile=build/'javac.args';legacy_port.argfile(argfile,arguments)
    subprocess.run([str(compiler),'-J-Dfile.encoding=UTF-8','@'+str(argfile)],cwd=ROOT,check=True,timeout=90,creationflags=HIDDEN)
    output=build/COMPANION
    with zipfile.ZipFile(output,'w',zipfile.ZIP_DEFLATED) as archive:
        for file in sorted(classes.rglob('*.class')):
            relative=file.relative_to(classes).as_posix()
            if not relative.startswith('dev/deadearth/legacyprobe/VisualShowcase'):raise ValueError('Unexpected class in standalone showcase compilation')
            archive.write(file,relative)
        archive.writestr('mcmod.info',json.dumps([dict(modid='deadearth_visual_showcase',name='Dead Earth Isolated Visual Showcase',version='1.0.0',mcversion='1.12.2',serverSideOnly=True,description='Explicit isolated framebuffer fixture; never ship.')]))
    legacy_server.atomic_json(build/'build.json',dict(schema=1,source=str(SOURCE),source_sha256=hashlib.sha256(SOURCE.read_bytes()).hexdigest(),jar_sha256=hashlib.sha256(output.read_bytes()).hexdigest(),standalone_showcase_class_only=True,game_started=False))
    return output


def prepare_server(target,props,report_name,epoch,companion):
    archive=target/'visual-preserved-native-probes';archive.mkdir()
    probes=sorted((target/'mods').glob('*probe*.jar'))
    allowed={'deadearth-port-probe.jar','deadearth-restore-probe.jar'}
    if not probes or any(path.name not in allowed for path in probes):raise ValueError('Unexpected native probe JAR set; no files were moved')
    preserved=probes+[path for path in (target/'mods').glob('*probe*.jar.disabled') if path.name.removesuffix('.disabled') in allowed]
    for path in preserved:
        if path.is_symlink() or path.resolve().parent!=(target/'mods').resolve() or (archive/path.name).exists():raise ValueError('Unsafe native probe archive path')
    for path in preserved:path.rename(archive/path.name)
    shutil.copy2(companion,target/'mods'/COMPANION)
    shutil.copy2(target/'server.properties',archive/'server.properties.before')
    run_id=str(uuid.uuid4())
    updated=dict(props,**{'server-ip':'127.0.0.1','server-port':verify_legacy_client.PORT,'online-mode':'false','enable-rcon':'false','enable-query':'false','spawn-monsters':'false','spawn-animals':'false','allow-flight':'true','gamemode':0,'view-distance':5,'motd':'DEAD EARTH CLIENT SHOWCASE '+run_id+' '+epoch})
    (target/'server.properties').write_text(''.join(str(k)+'='+str(v)+'\n' for k,v in updated.items()),encoding='utf-8')
    request=dict(schema=1,kind='dead-earth-visual-showcase-request',purpose='sieve-surface-patch',runId=run_id,worldEpoch=epoch,serverDirectory=str(target),repositoryWork=str((ROOT/'work').resolve()),nativeReport=report_name,username=verify_legacy_client.USER,preparedAtUtc=datetime.now(timezone.utc).isoformat())
    legacy_server.atomic_json(target/'visual-showcase-request.json',request)
    return request


def run(args):
    if not 120<=args.timeout<=1200 or not 1024<=args.memory_mb<=8192:raise ValueError('Timeout must be 120..1200 seconds; server memory must be 1024..8192 MiB')
    target,props,world,report_name,epoch=owned_native(args.server_dir)
    java=legacy_server.java_executable(args.java8).resolve(strict=True)
    if not legacy_server.is_java8(java):raise ValueError('Explicit Java 8 runtime is required')
    lock=target/'runtime/legacy-visual';lock.mkdir(parents=True,exist_ok=True)
    with legacy_server.exclusive(lock/'showcase.lock') as acquired:
        if not acquired:raise ValueError('This native fixture already has an active visual supervisor')
        # Revalidate immediately before mutation, including both transport ports.
        target,props,world,report_name,epoch=owned_native(target)
        companion=compile_companion(target,args.forge_dir,args.javac)
        request=prepare_server(target,props,report_name,epoch,companion)
        result=dict(schema=1,purpose='sieve-surface-patch',runId=request['runId'],worldEpoch=epoch,serverDirectory=str(target),passed=False,realAiInferenceTested=False)
        client_directory=None;process=None;exit_code=None;error=None
        command=[str(java),'-Xms512M','-Xmx'+str(args.memory_mb)+'M','-Djava.awt.headless=true','-Ddeadearth.testControls=true','-Ddeadearth.visualShowcase=true','-jar',str(legacy_server.server_jar(target)),'nogui']
        with (target/'visual-server.log').open('w',encoding='utf-8') as output:
            try:
                process=subprocess.Popen(command,cwd=target,stdin=subprocess.PIPE,stdout=output,stderr=subprocess.STDOUT,text=True,encoding='utf-8',creationflags=HIDDEN)
                legacy_server.atomic_json(target/'visual-server-process.json',dict(schema=1,pid=process.pid,runId=request['runId'],worldEpoch=epoch,owningSupervisorPid=os.getpid(),state='starting'))
                deadline=time.monotonic()+args.timeout
                while not (target/'client-showcase.json').is_file():
                    if process.poll() is not None:raise RuntimeError('Owned showcase server exited before preparing its marker')
                    status=target/'visual-showcase-server.json'
                    if status.is_file() and verify_legacy_client.read_json(status).get('passed') is False:raise RuntimeError('Showcase server preparation failed; inspect visual-showcase-server.json')
                    if time.monotonic()>=deadline:raise TimeoutError('Showcase server marker deadline exceeded')
                    time.sleep(.25)
                verify_legacy_client.fixture_marker(target)
                client_directory=verify_legacy_client.prepare(argparse.Namespace(server_dir=target,forge_dir=args.forge_dir,javac=args.javac,offline=args.offline,timeout=args.timeout))
                result['clientDirectory']=str(client_directory)
                code=verify_legacy_client.run(argparse.Namespace(run_dir=client_directory,java8=java,hold_seconds=0))
                client_result=verify_legacy_client.read_json(client_directory/'verification.json')
                server_result=verify_legacy_client.read_json(target/'visual-showcase-server.json')
                result.update(clientExitStatus=code,client=client_result,server=server_result)
            except BaseException as failure:
                error=type(failure).__name__+': '+str(failure);result['error']=error
            finally:
                if process is not None:
                    # No terminate/kill fallback: a saving server remains owned, visible in its receipt.
                    try:exit_code=verify_legacy_server.stop_with_save(process)
                    except RuntimeError as saving:
                        result['error']=str(saving);result['serverStillSavingPid']=process.pid
                    legacy_server.atomic_json(target/'visual-server-process.json',dict(schema=1,pid=process.pid,runId=request['runId'],worldEpoch=epoch,owningSupervisorPid=os.getpid(),state='stopped' if process.poll() is not None else 'saving',exitCode=process.poll()))
        result['serverExitCode']=exit_code
        result['passed']=error is None and exit_code==0 and result.get('client',{}).get('passed') is True and result.get('server',{}).get('passed') is True
        legacy_server.atomic_json(target/'visual-verification.json',result)
        print(json.dumps(result,indent=2),flush=True)
        return 0 if result['passed'] else 1


def main(argv=None):
    parser=argparse.ArgumentParser(description=__doc__,formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('--server-dir',required=True,type=Path,help='Successful, stopped, newly generated final native fixture')
    parser.add_argument('--forge-dir',required=True,type=Path,help='Read-only installed pinned Forge server runtime for compilation/client libraries')
    parser.add_argument('--javac',required=True,type=Path);parser.add_argument('--java8',required=True,type=Path)
    parser.add_argument('--offline',action='store_true');parser.add_argument('--timeout',type=int,default=480);parser.add_argument('--memory-mb',type=int,default=4096)
    return run(parser.parse_args(argv))


if __name__=='__main__':
    try:raise SystemExit(main())
    except (ValueError,OSError,KeyError,RuntimeError,subprocess.SubprocessError) as failure:
        print('Isolated visual verifier: '+str(failure),file=sys.stderr);raise SystemExit(2)
