"""Final-stage actual GL evidence using an already successful, stopped, new native fixture.

The wrapper compiles only VisualShowcase.java, preserves native/restore probe JARs
outside mods, starts its own loopback server, prepares a third isolated client,
runs the existing framebuffer probe, and sends normal stdin stop in finally.
--retry-preparation only archives a failed pre-client preparation after normal stop;
it retains the partial test platform and reuses the world, so the showcase builds
its next platform above it (+3 native height clearance). Native gameplay is not rerun.
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


def owned_native(directory,retry_preparation=False):
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
    if retry_preparation:
        retry_preparation_inputs(target,epoch,report_name)
    else:
        for name in ('visual-showcase-request.json','client-showcase.json','visual-verification.json',COMPANION):
            if (target/name).exists() or (target/'mods'/name).exists():raise ValueError('This fixture already has visual evidence; preserve it and use a fresh successful native run')
    return target,props,world,report_name,epoch


def retry_preparation_inputs(target,epoch,report_name):
    """Only the stopped, unsuccessful pre-client showcase attempt may be reset."""
    request=verify_legacy_client.read_json(target/'visual-showcase-request.json')
    result=verify_legacy_client.read_json(target/'visual-verification.json')
    process=verify_legacy_client.read_json(target/'visual-server-process.json')
    server=verify_legacy_client.read_json(target/'visual-showcase-server.json')
    run_id=request.get('runId')
    if not run_id or str(uuid.UUID(run_id))!=run_id:raise ValueError('Invalid prior visual run identity')
    if request.get('schema')!=1 or request.get('kind')!='dead-earth-visual-showcase-request' or request.get('nativeReport')!=report_name:raise ValueError('Unexpected prior visual request')
    for receipt in (request,result,process):
        if receipt.get('schema')!=1 or receipt.get('runId')!=run_id or receipt.get('worldEpoch')!=epoch:raise ValueError('Prior visual receipts have inconsistent run/epoch')
    for receipt in (request,result):
        if Path(receipt.get('serverDirectory','')).resolve()!=target:raise ValueError('Prior visual receipt names another fixture')
    marker_path=target/'client-showcase.json'
    preparation_failed=(result.get('error')=='RuntimeError: Showcase server preparation failed; inspect visual-showcase-server.json'
                        and not marker_path.exists() and server.get('passed') is False)
    marker_admission_failed=False
    if (result.get('error')=='ValueError: Showcase views must cover all three native seasons'
            and marker_path.exists() and server.get('passed') is True):
        _,marker=verify_legacy_client.fixture_marker(target)
        marker_admission_failed=(marker.get('runId')==run_id and marker.get('worldEpoch')==epoch
            and marker.get('purpose')=='complete-assets-and-worldgen-refactor'
            and server.get('runId')==run_id and server.get('worldEpoch')==epoch)
    model_audit_failed=False
    if result.get('clientExitStatus')==1 and server.get('passed') is True and result.get('clientDirectory'):
        supplied=Path(result['clientDirectory']).absolute();client=supplied.resolve(strict=True)
        if client!=supplied or client.parent!=(ROOT/'work').resolve() or not client.name.startswith('legacy-client-'):
            raise ValueError('Model audit client is not an owned isolated runtime')
        verification=verify_legacy_client.read_json(client/'verification.json')
        audit=verify_legacy_client.read_json(client/'game/client-report.json',8*1024*1024)
        _,marker=verify_legacy_client.fixture_marker(target)
        proof=verify_legacy_client.read_json(target/'resource-only-proof.json')
        model_audit_failed=(verification==result.get('client') and verification.get('passed') is False
            and verification.get('exitCode')==0 and verification.get('timedOut') is False
            and verification.get('screenshots')==[] and audit.get('passed') is False
            and (audit.get('error')=='IllegalStateException: Missing texture dead_earth:compact_mesh_string_v3' or str(audit.get('error','')).startswith('IllegalStateException: Complete own asset bake audit failed'))
            and marker.get('runId')==run_id and marker.get('worldEpoch')==epoch
            and verification.get('runId')==run_id and verification.get('worldEpoch')==epoch
            and proof.get('passed') is True and proof.get('allNonAssetsEntriesIdentical') is True
            and proof.get('allOtherAssetsIdentical') is True
            and proof.get('finalJarSha256')==hashlib.sha256(legacy_port.compiled_jar(ROOT)).hexdigest())
    if (result.get('passed') is not False or result.get('serverExitCode')!=0
            or not (preparation_failed or marker_admission_failed or model_audit_failed)
            or not model_audit_failed and ('clientDirectory' in result or 'client' in result or 'clientExitStatus' in result)
            or process.get('state')!='stopped' or process.get('exitCode')!=0):
        raise ValueError('Retry is only allowed after failed preparation, before any client, with normal server exit 0')
    coordinator=target/'mods'/legacy_port.MOD_NAME
    current=hashlib.sha256(legacy_port.compiled_jar(ROOT)).hexdigest()
    if coordinator.is_symlink() or not coordinator.is_file() or hashlib.sha256(coordinator.read_bytes()).hexdigest()!=current:raise ValueError('Retry coordinator differs from current compiled slice')
    backup=target/'visual-preserved-native-probes'
    names={'deadearth-port-probe.jar.disabled','deadearth-restore-probe.jar','server.properties.before'}
    if backup.is_symlink() or not backup.is_dir() or {p.name for p in backup.iterdir()}!=names:raise ValueError('Unexpected preserved native probe set')
    hashes={}
    for name in sorted(names):
        path=backup/name
        if path.is_symlink() or not path.is_file() or path.resolve().parent!=backup.resolve():raise ValueError('Unsafe preserved native probe')
        hashes[name]=hashlib.sha256(path.read_bytes()).hexdigest()
        if name!='server.properties.before' and (target/'mods'/name).exists():raise ValueError('Restoration would overwrite a native probe')
    return dict(schema=1,kind='dead-earth-visual-preparation-retry',priorRunId=run_id,worldEpoch=epoch,
        coordinatorSha256=current,preservedSha256=hashes,worldRetained=True,
        limitation='Pre-client fixture preparation only; partial test platform retained. Native height/preflight builds the next platform +3 above it; no world rollback or repeated gameplay suite.')


def archive_failed_preparation(target,epoch,report_name):
    manifest=retry_preparation_inputs(target,epoch,report_name)
    names=('visual-showcase-request.json','client-showcase.json','visual-showcase-server.json','visual-verification.json',
           'visual-server-process.json','visual-server.log','visual-showcase-build','server.properties',
           'visual-preserved-native-probes','mods/'+COMPANION)
    copies=('logs','crash-reports','crafttweaker.log','mods/'+legacy_port.MOD_NAME)
    # Validate the complete small evidence set before moving anything; never traverse linked paths.
    for relative in (*names,*copies):
        source=target/relative
        if not source.exists():continue
        for path in (source,*source.rglob('*')) if source.is_dir() else (source,):
            if path.is_symlink() or not path.resolve().is_relative_to(target):raise ValueError('Unsafe linked visual evidence')
    archive=target/('visual-failed-preparation-'+datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%S%fZ')+'-'+uuid.uuid4().hex[:8])
    archive.mkdir()
    for relative in copies:
        source=target/relative
        if not source.exists():continue
        destination=archive/relative;destination.parent.mkdir(parents=True,exist_ok=True)
        if source.is_dir():shutil.copytree(source,destination)
        else:shutil.copy2(source,destination)
    for relative in names:
        source=target/relative
        if source.exists():
            destination=archive/relative;destination.parent.mkdir(parents=True,exist_ok=True);source.rename(destination)
    manifest['archivedSha256']={path.relative_to(archive).as_posix():hashlib.sha256(path.read_bytes()).hexdigest() for path in sorted(archive.rglob('*')) if path.is_file()}
    backup=archive/'visual-preserved-native-probes'
    for name,expected in manifest['preservedSha256'].items():
        source=backup/name
        if hashlib.sha256(source.read_bytes()).hexdigest()!=expected:raise ValueError('Preserved native input hash changed during archive')
        destination=target/'server.properties' if name=='server.properties.before' else target/'mods'/name
        shutil.copy2(source,destination)
        if hashlib.sha256(destination.read_bytes()).hexdigest()!=expected:raise ValueError('Restored native input hash mismatch')
    prior=verify_legacy_client.read_json(archive/'visual-verification.json')
    if prior.get('clientDirectory'):
        client=Path(prior['clientDirectory']).resolve(strict=True)
        for relative in ('verification.json','game/client-report.json'):
            destination=archive/'failed-client'/relative;destination.parent.mkdir(parents=True,exist_ok=True)
            shutil.copy2(client/relative,destination)
        shutil.copy2(target/'resource-only-proof.json',archive/'resource-only-proof.json')
    manifest['archiveDirectory']=str(archive);manifest['restoredNativeHashesVerified']=True
    legacy_server.atomic_json(archive/'retry-manifest.json',manifest)
    return manifest


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
    request=dict(schema=1,kind='dead-earth-visual-showcase-request',purpose='complete-assets-and-worldgen-refactor',runId=run_id,worldEpoch=epoch,serverDirectory=str(target),repositoryWork=str((ROOT/'work').resolve()),nativeReport=report_name,username=verify_legacy_client.USER,preparedAtUtc=datetime.now(timezone.utc).isoformat())
    legacy_server.atomic_json(target/'visual-showcase-request.json',request)
    return request


def run(args):
    if not 120<=args.timeout<=1200 or not 1024<=args.memory_mb<=8192:raise ValueError('Timeout must be 120..1200 seconds; server memory must be 1024..8192 MiB')
    target,props,world,report_name,epoch=owned_native(args.server_dir,args.retry_preparation)
    java=legacy_server.java_executable(args.java8).resolve(strict=True)
    if not legacy_server.is_java8(java):raise ValueError('Explicit Java 8 runtime is required')
    lock=target/'runtime/legacy-visual';lock.mkdir(parents=True,exist_ok=True)
    with legacy_server.exclusive(lock/'showcase.lock') as acquired:
        if not acquired:raise ValueError('This native fixture already has an active visual supervisor')
        # Revalidate immediately before mutation, including both transport ports.
        target,props,world,report_name,epoch=owned_native(target,args.retry_preparation)
        retry=None
        if args.retry_preparation:
            retry=archive_failed_preparation(target,epoch,report_name)
            target,props,world,report_name,epoch=owned_native(target)
        companion=compile_companion(target,args.forge_dir,args.javac)
        request=prepare_server(target,props,report_name,epoch,companion)
        result=dict(schema=1,purpose='complete-assets-and-worldgen-refactor',runId=request['runId'],worldEpoch=epoch,serverDirectory=str(target),passed=False,realAiInferenceTested=False)
        if retry is not None:result['preparationRetry']=retry
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
    parser.add_argument('--retry-preparation',action='store_true',help='Archive only a normally stopped pre-client preparation failure, retain test world and restore hash-checked native inputs')
    parser.add_argument('--retry-model-audit',dest='retry_preparation',action='store_true',help='Retry only the known missing mesh-particle audit after a verified resources-only patch')
    parser.add_argument('--offline',action='store_true');parser.add_argument('--timeout',type=int,default=480);parser.add_argument('--memory-mb',type=int,default=4096)
    return run(parser.parse_args(argv))


if __name__=='__main__':
    try:raise SystemExit(main())
    except (ValueError,OSError,KeyError,RuntimeError,subprocess.SubprocessError) as failure:
        print('Isolated visual verifier: '+str(failure),file=sys.stderr);raise SystemExit(2)
