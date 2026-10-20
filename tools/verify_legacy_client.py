"""Prepare/compile/run an actual isolated Forge 1.12.2 Java 8 client, never an existing profile.

Preparation is safe to perform before final verification: it does not launch a JVM game.
The `run` subcommand is reserved for the final stage. It never starts/stops the server.

Server preconditions, written ONLY by the isolated final showcase fixture:
  .dead-earth-isolated-native-test exists beneath this repository's work/;
  server.properties uses server-ip=127.0.0.1, server-port=25585, online-mode=false;
  client-showcase.json contains schema=1, kind=dead-earth-client-showcase, runId UUID,
  worldEpoch UUID, views (name,x,y,z,yaw,pitch,season,blocks[{x,y,z,id}]), requiredBlocks,
  requiredItems, optional inventoryItems. Views must cover CALM/HEAT/ASH_WINTER.
  motd is `DEAD EARTH CLIENT SHOWCASE <runId> <worldEpoch>`.
  The server gives offline player DeadEarthProbe survival + test-only flight/invulnerability and sends
  DEAD_EARTH_CLIENT_READY:<runId>:<worldEpoch> on login. It consumes test-only plain chat
  DEAD_EARTH_CLIENT_VIEW:<runId>:<worldEpoch>:<index>, prepares the matching scene/season,
  then sends DEAD_EARTH_CLIENT_VIEW_READY:<runId>:<index>. No slash commands are sent.

Examples:
  compile --forge-dir work/storage-server-20261020 --coordinator work/legacy-coordinator/mod.jar --javac <JDK>/bin/javac.exe
  prepare --server-dir work/<fresh-showcase> --forge-dir work/storage-server-20261020 --javac <JDK>/bin/javac.exe --offline
  run --run-dir work/legacy-client-<unique> --java8 <Prism>/java/jre-legacy/bin/java.exe

Only official Minecraft metadata/artifacts and the pinned Forge installer metadata are used.
Prism's libraries/assets/meta directories are read-only cache sources; accounts are never read.
"""
import argparse
from datetime import datetime, timezone
import hashlib
import json
import math
import os
from pathlib import Path, PurePosixPath
import platform
import re
import shutil
import socket
import struct
import subprocess
import sys
import time
from urllib.parse import urlparse
from urllib.request import Request, urlopen
import uuid
import zipfile

try:
    from . import legacy_port, legacy_server
except ImportError:
    import legacy_port, legacy_server

ROOT=Path(__file__).resolve().parents[1]
FORGE='1.12.2-14.23.5.2860'
PORT=25585
USER='DeadEarthProbe'
PRISM=Path(os.environ.get('APPDATA',''))/'PrismLauncher'
HOSTS={'piston-meta.mojang.com','piston-data.mojang.com','launchermeta.mojang.com','launcher.mojang.com','libraries.minecraft.net','resources.download.minecraft.net','maven.minecraftforge.net'}
HIDDEN=getattr(subprocess,'CREATE_NO_WINDOW',0)

def safe_child(base,name):
    relative=PurePosixPath(str(name).replace('\\','/'))
    if relative.is_absolute() or '..' in relative.parts or ':' in str(relative): raise ValueError('Unsafe runtime relative path')
    path=base.joinpath(*relative.parts)
    if not path.resolve().is_relative_to(base.resolve()): raise ValueError('Linked path escapes isolated runtime')
    return path

def fresh(prefix):
    work=ROOT/'work';work.mkdir(exist_ok=True)
    path=work/(prefix+datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%S%fZ')+'-'+uuid.uuid4().hex[:8])
    if path.resolve().parent!=work.resolve():raise ValueError('Unsafe fresh client directory')
    path.mkdir();return path

def read_json(path,limit=4*1024*1024):
    if path.is_symlink() or path.stat().st_size>limit:raise ValueError('Unsafe/oversize JSON')
    def unique(pairs):
        value={}
        for key,item in pairs:
            if key in value:raise ValueError('Duplicate JSON key')
            value[key]=item
        return value
    return json.loads(path.read_text(encoding='utf-8'),object_pairs_hook=unique)

def remote(url,limit):
    parsed=urlparse(url)
    if parsed.scheme!='https' or parsed.hostname not in HOSTS:raise ValueError('Unapproved runtime artifact origin')
    with urlopen(Request(url,headers={'User-Agent':'DeadEarthIsolatedClientProbe/1.0'}),timeout=60) as response:
        if urlparse(response.url).hostname not in HOSTS:raise ValueError('Unapproved runtime artifact redirect')
        data=response.read(limit+1)
    if len(data)>limit:raise ValueError('Oversize runtime artifact')
    return data

def fetch(descriptor,target,candidates=(),offline=False):
    digest=descriptor['sha1'];size=descriptor.get('size')
    if not re.fullmatch('[0-9a-f]{40}',digest) or size is not None and (type(size)!=int or not 0<size<300000000):raise ValueError('Invalid official artifact descriptor')
    def valid(path):
        return path.is_file() and not path.is_symlink() and (size is None or path.stat().st_size==size) and hashlib.sha1(path.read_bytes()).hexdigest()==digest
    if valid(target):return target
    if target.exists():raise ValueError('Existing cache bytes differ: '+target.name)
    target.parent.mkdir(parents=True,exist_ok=True)
    for candidate in candidates:
        if valid(candidate):shutil.copy2(candidate,target);return target
    if offline:raise ValueError('Missing offline runtime artifact: '+target.name+' ('+digest+')')
    data=remote(descriptor['url'],size if size is not None else 4*1024*1024)
    if (size is not None and len(data)!=size) or hashlib.sha1(data).hexdigest()!=digest:raise ValueError('Official artifact hash/size mismatch')
    target.write_bytes(data);return target

def maven_path(name):
    parts=name.split(':')
    if len(parts) not in (3,4) or any(not re.fullmatch(r'[a-zA-Z0-9_.+-]+',p) for p in parts):raise ValueError('Unsafe Maven coordinate')
    group,artifact,version=parts[:3]
    return group.replace('.','/')+'/'+artifact+'/'+version+'/'+artifact+'-'+version+('-'+parts[3] if len(parts)==4 else '')+'.jar'

def metadata(cache,offline):
    version=cache/'1.12.2.json'
    if not version.exists():
        if offline:
            component=read_json(PRISM/'meta/net.minecraft/1.12.2.json')
            component['downloads']={'client':component['mainJar']['downloads']['artifact']}
            version.parent.mkdir(parents=True,exist_ok=True);version.write_text(json.dumps(component),encoding='utf-8')
        else:
            manifest=json.loads(remote('https://piston-meta.mojang.com/mc/game/version_manifest_v2.json',4*1024*1024))
            row=next(row for row in manifest['versions'] if row['id']=='1.12.2')
            fetch(row,version,offline=False)
    return read_json(version)

def permitted(library):
    rules=library.get('rules')
    if not rules:return True
    allowed=False
    for rule in rules:
        system=rule.get('os',{})
        matches=system.get('name','windows')=='windows'
        if 'arch' in system:matches &= re.search(system['arch'],'x86_64') is not None
        if 'version' in system:matches &= re.search(system['version'],platform.version()) is not None
        if any(bool(v) for v in rule.get('features',{}).values()):matches=False
        if matches:allowed=rule['action']=='allow'
    return allowed

def runtime_files(target,fixture,offline):
    cache=ROOT/'.cache/legacy-client';cache.mkdir(parents=True,exist_ok=True)
    vanilla=metadata(cache,offline)
    with zipfile.ZipFile(ROOT/'.cache/legacy'/('forge-'+FORGE+'-installer.jar')) as archive:forge=json.loads(archive.read('version.json'))
    if forge.get('id')!='1.12.2-forge-14.23.5.2860' or forge.get('mainClass')!='net.minecraft.launchwrapper.Launch':raise ValueError('Unexpected pinned Forge client metadata')
    client=fetch(vanilla['downloads']['client'],cache/'client-1.12.2.jar',[
        PRISM/'libraries/com/mojang/minecraft/1.12.2/minecraft-1.12.2-client.jar'],offline)
    cp=[];libraries={}
    for row in vanilla['libraries']+forge['libraries']:
        if permitted(row):libraries[':'.join(row['name'].split(':')[:2])]=row
    for row in libraries.values():
        downloads=row.get('downloads',{});artifact=downloads.get('artifact')
        if artifact:
            relative=artifact.get('path',maven_path(row['name']))
            local=fetch(artifact,safe_child(cache/'libraries',relative),[safe_child(fixture/'libraries',relative),safe_child(PRISM/'libraries',relative),fixture/('forge-'+FORGE+'.jar') if row['name'].startswith('net.minecraftforge:forge:') else cache/'not-a-candidate'],offline)
            cp.append(str(local.resolve()))
        native=row.get('natives',{}).get('windows')
        if native:
            native=native.replace('${arch}','64');descriptor=downloads.get('classifiers',{}).get(native)
            if descriptor is None:raise ValueError('Missing Windows native descriptor')
            relative=descriptor.get('path',maven_path(row['name']+':'+native))
            local=fetch(descriptor,safe_child(cache/'libraries',relative),[safe_child(PRISM/'libraries',relative)],offline)
            with zipfile.ZipFile(local) as archive:
                for entry in archive.infolist():
                    if entry.is_dir() or any(entry.filename.startswith(prefix) for prefix in row.get('extract',{}).get('exclude',['META-INF/'])):continue
                    if not entry.filename.lower().endswith('.dll'):continue
                    destination=safe_child(target/'natives',entry.filename);destination.parent.mkdir(parents=True,exist_ok=True)
                    data=archive.read(entry)
                    if destination.exists() and destination.read_bytes()!=data:raise ValueError('Conflicting native libraries')
                    destination.write_bytes(data)
    cp.append(str(client.resolve()))
    index=vanilla['assetIndex'];indexfile=fetch(index,safe_child(cache/'assets/indexes',index['id']+'.json'),[PRISM/'assets/indexes'/(index['id']+'.json')],offline)
    shutil.copytree(indexfile.parent,target/'assets/indexes',dirs_exist_ok=True)
    for object_ in read_json(indexfile,16*1024*1024)['objects'].values():
        h=object_['hash'];descriptor={'sha1':h,'size':object_['size'],'url':'https://resources.download.minecraft.net/'+h[:2]+'/'+h}
        cached=fetch(descriptor,safe_child(cache/'assets/objects',h[:2]+'/'+h),[PRISM/'assets/objects'/h[:2]/h],offline)
        out=safe_child(target/'assets/objects',h[:2]+'/'+h);out.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(cached,out)
    return cp,index['id']

def fixture_marker(directory):
    supplied=Path(directory).absolute();root=supplied.resolve(strict=True)
    if os.path.normcase(str(root))!=os.path.normcase(str(supplied)) or not root.is_relative_to((ROOT/'work').resolve()) or root==(ROOT/'work').resolve():raise ValueError('Showcase must be a real isolated fixture beneath this fork work/')
    if not (root/'.dead-earth-isolated-native-test').is_file():raise ValueError('Missing isolated native fixture marker')
    marker=read_json(root/'client-showcase.json',1048576)
    if marker.get('schema')!=1 or marker.get('kind')!='dead-earth-client-showcase':raise ValueError('Wrong showcase marker')
    for key in ('runId','worldEpoch'):
        if str(uuid.UUID(marker[key]))!=marker[key]:raise ValueError('Noncanonical showcase UUID')
    props,world,port=legacy_server.properties(root)
    if port!=PORT or props.get('online-mode')!='false' or props.get('motd')!='DEAD EARTH CLIENT SHOWCASE '+marker['runId']+' '+marker['worldEpoch']:raise ValueError('Showcase transport/world identity is not explicit')
    if not world.is_relative_to(root):raise ValueError('Showcase world escapes owned directory')
    campaign=read_json(world/'data/dead_earth_legacy.json',8*1024*1024)
    if campaign.get('worldEpoch')!=marker['worldEpoch']:raise ValueError('Showcase marker differs from saved campaign world')
    views=marker.get('views',[])
    seasons={v['season'] for v in views}
    if marker.get('purpose')=='sieve-surface-patch':
        if not 3<=len(views)<=8 or seasons!={'CALM'}:raise ValueError('Sieve/surface patch showcase requires 3..8 calm views')
    elif not 3<=len(views)<=24 or seasons!={'CALM','HEAT','ASH_WINTER'}:raise ValueError('Showcase views must cover all three native seasons')
    names=set()
    for view in views:
        if not re.fullmatch('[a-z0-9_-]{1,48}',view['name']) or view['name'] in names:raise ValueError('Unsafe/duplicate view name')
        names.add(view['name'])
        for key in ('x','y','z','yaw','pitch'):
            if type(view[key]) not in (int,float) or not math.isfinite(view[key]) or abs(view[key])>8192:raise ValueError('Unbounded showcase viewpoint')
        if not 1<=view['y']<=254 or abs(view['pitch'])>90:raise ValueError('Invalid native camera')
        if any(abs(view[k]-views[0][k])>128 for k in ('x','y','z')):raise ValueError('Views must remain in the local loaded showcase cluster')
    for key in ('requiredBlocks','requiredItems','inventoryItems'):
        for id_ in marker.get(key,[]):
            if not re.fullmatch('[a-z0-9_]+:[a-z0-9_./]+',id_):raise ValueError('Invalid showcase registry identifier')
    if len(marker.get('requiredBlocks',[]))<4 or len(marker.get('requiredItems',[]))<2:raise ValueError('Showcase must check substantive block/item models')
    return root,marker

def compile_probe(javac,fixture,coordinator,output):
    compiler=Path(javac).resolve(strict=True);coordinator=Path(coordinator).resolve(strict=True)
    output=Path(output).resolve();output.mkdir(parents=True,exist_ok=True)
    if not output.is_relative_to((ROOT/'work').resolve()):raise ValueError('Probe compiler output must remain beneath this fork work/')
    sources=list((ROOT/'legacy/coordinator/src/clientintegration/java').rglob('*.java'))
    cp=os.pathsep.join(map(str,[coordinator,*legacy_port.forge_classpath(fixture)]))
    classes=output/'classes';classes.mkdir(exist_ok=True)
    args=['--release','8','-encoding','UTF-8','-proc:none','-classpath',cp,'-d',classes,*sources]
    response=output/'client-probe-javac.args';legacy_port.argfile(response,args)
    subprocess.run([str(compiler),'@'+str(response)],check=True,cwd=ROOT,creationflags=HIDDEN)
    jar=output/'dead-earth-client-probe.jar'
    with zipfile.ZipFile(jar,'w',zipfile.ZIP_DEFLATED) as archive:
        for path in classes.rglob('*.class'):archive.write(path,path.relative_to(classes).as_posix())
    return jar

def prepare(args):
    server,marker=fixture_marker(args.server_dir);target=fresh('legacy-client-');game=target/'game';game.mkdir()
    for name,data in legacy_port.entries('client',root=ROOT).items():
        if not name.startswith('.minecraft/'):continue
        out=safe_child(game,name[len('.minecraft/'):]);out.parent.mkdir(parents=True,exist_ok=True);out.write_bytes(data)
    cp,index=runtime_files(target,Path(args.forge_dir).resolve(strict=True),args.offline)
    probe=compile_probe(args.javac,args.forge_dir,game/'mods'/legacy_port.MOD_NAME,target/'probe-build');shutil.copy2(probe,game/'mods'/probe.name)
    control=dict(marker,kind='dead-earth-client-probe',serverDirectory=str(server),timeoutSeconds=args.timeout)
    control['requiredMods']=[row['mod_id'] for row in legacy_port.full_pins(ROOT) if row['side'] in {'client','both'}]
    (game/'client-probe.json').write_text(json.dumps(control,indent=2),encoding='utf-8')
    (game/'options.txt').write_text('fullscreen:false\noverrideWidth:1280\noverrideHeight:720\npauseOnLostFocus:false\nlang:ru_ru\nrenderDistance:5\nmaxFps:60\ngamma:0.8\nclouds:false\nenableVsync:false\n',encoding='utf-8')
    data={'schema':1,'kind':'dead-earth-isolated-client','runId':marker['runId'],'worldEpoch':marker['worldEpoch'],'serverDirectory':str(server),'classpath':cp,'assetIndex':index,'timeoutSeconds':args.timeout}
    data['inputHashes']={str(p.resolve()):hashlib.sha256(p.read_bytes()).hexdigest() for p in [*map(Path,cp),*(game/'mods').glob('*.jar'),game/'client-probe.json']}
    (target/'client-runtime.json').write_text(json.dumps(data,indent=2),encoding='utf-8')
    print('Prepared actual Forge client; no game/server/model was started: '+str(target))
    return target

def varint(value):
    out=bytearray()
    while True:
        b=value&127;value>>=7;out.append(b|(128 if value else 0))
        if not value:return bytes(out)

def read_varint(sock):
    value=0
    for shift in range(0,35,7):
        data=sock.recv(1)
        if not data:raise ValueError('Truncated local status packet')
        value|=(data[0]&127)<<shift
        if not data[0]&128:return value
    raise ValueError('Oversize local status integer')

def status_identity(marker):
    host=b'127.0.0.1';handshake=b'\0'+varint(340)+varint(len(host))+host+struct.pack('>H',PORT)+b'\1'
    with socket.create_connection(('127.0.0.1',PORT),timeout=5) as connection:
        connection.sendall(varint(len(handshake))+handshake+b'\1\0');length=read_varint(connection)
        if not 0<length<65536:raise ValueError('Unbounded server status packet')
        if read_varint(connection)!=0:raise ValueError('Wrong status packet type')
        length=read_varint(connection)
        if not 0<length<65536:raise ValueError('Unbounded server status JSON')
        data=bytearray()
        while len(data)<length:
            part=connection.recv(length-len(data))
            if not part:raise ValueError('Truncated status JSON')
            data.extend(part)
    status=json.loads(data);description=status['description']
    text=description if isinstance(description,str) else description.get('text','')+''.join(p.get('text','') for p in description.get('extra',[]))
    if text!='DEAD EARTH CLIENT SHOWCASE '+marker['runId']+' '+marker['worldEpoch']:raise ValueError('Listening server is not the marked showcase; no client was launched')

def run(args):
    if not 0<=args.hold_seconds<=300:raise ValueError('Client hold must be 0..300 seconds')
    target=Path(args.run_dir).absolute().resolve(strict=True)
    if not target.is_relative_to((ROOT/'work').resolve()) or not target.name.startswith('legacy-client-'):raise ValueError('Not an isolated prepared client directory')
    runtime=read_json(target/'client-runtime.json');server,marker=fixture_marker(runtime['serverDirectory'])
    if runtime.get('kind')!='dead-earth-isolated-client' or runtime['runId']!=marker['runId'] or runtime['worldEpoch']!=marker['worldEpoch']:raise ValueError('Prepared client identity changed')
    game=target/'game';control=read_json(game/'client-probe.json')
    if control['runId']!=marker['runId'] or control['worldEpoch']!=marker['worldEpoch']:raise ValueError('Client control identity changed')
    if (game/'client-report.json').exists():raise ValueError('This isolated client already ran; prepare a fresh run directory')
    for filename,digest in runtime['inputHashes'].items():
        if hashlib.sha256(Path(filename).read_bytes()).hexdigest()!=digest:raise ValueError('Prepared native client input changed; prepare a fresh client')
    java=legacy_server.java_executable(args.java8)
    if not legacy_server.is_java8(java):raise ValueError('An explicit Java 8 runtime is required')
    if os.name!='nt':raise ValueError('This prepared native runtime targets Windows x64')
    status_identity(marker)
    player_uuid=uuid.UUID(bytes=hashlib.md5(('OfflinePlayer:'+USER).encode()).digest(),version=3)
    command=[str(java),'-Xms1G','-Xmx4G','-Djava.library.path='+str(target/'natives'),'-Dorg.lwjgl.librarypath='+str(target/'natives'),'-Dnet.java.games.input.librarypath='+str(target/'natives'),'-Dlog4j2.formatMsgNoLookups=true','-Ddeadearth.clientProbe=true','-Ddeadearth.clientProbeHoldSeconds='+str(args.hold_seconds),'-Ddeadearth.clientProbeControl='+str(game/'client-probe.json'),'-cp',os.pathsep.join(runtime['classpath']),'net.minecraft.launchwrapper.Launch','--username',USER,'--version','1.12.2-forge-14.23.5.2860','--gameDir',str(game),'--assetsDir',str(target/'assets'),'--assetIndex',runtime['assetIndex'],'--uuid',player_uuid.hex,'--accessToken','0','--userType','legacy','--tweakClass','net.minecraftforge.fml.common.launcher.FMLTweaker','--versionType','Forge','--server','127.0.0.1','--port',str(PORT),'--width','1280','--height','720']
    timed_out=False
    with (target/'client-console.log').open('w',encoding='utf-8') as log:
        process=subprocess.Popen(command,cwd=game,stdout=log,stderr=subprocess.STDOUT,creationflags=HIDDEN)
        try:process.wait(timeout=runtime['timeoutSeconds']+args.hold_seconds+120)
        except (subprocess.TimeoutExpired,KeyboardInterrupt):
            timed_out=True;(game/'client-quit.request').write_text('Graceful stop requested by this owning supervisor.\n',encoding='ascii')
            try:process.wait(timeout=20)
            except subprocess.TimeoutExpired:
                process.terminate()
                try:process.wait(timeout=10)
                except subprocess.TimeoutExpired:process.kill();process.wait(timeout=10)
    report=read_json(game/'client-report.json') if (game/'client-report.json').is_file() else {'passed':False,'error':'Native client did not produce a report'}
    evidence=[]
    for row in report.get('screenshots',[]):
        screenshot=safe_child(game,row['file']);data=screenshot.read_bytes()
        if data[:8]!=b'\x89PNG\r\n\x1a\n' or len(data)<1024:raise ValueError('Invalid native screenshot')
        evidence.append({'file':str(screenshot.relative_to(target)),'sha256':hashlib.sha256(data).hexdigest(),'width':struct.unpack('>I',data[16:20])[0],'height':struct.unpack('>I',data[20:24])[0]})
    result={'schema':1,'passed':bool(report.get('passed')) and process.returncode==0 and not timed_out and len(evidence)==len(marker['views']),'runId':marker['runId'],'worldEpoch':marker['worldEpoch'],'exitCode':process.returncode,'timedOut':timed_out,'screenshots':evidence,'report':'game/client-report.json'}
    (target/'verification.json').write_text(json.dumps(result,indent=2),encoding='utf-8')
    print(json.dumps(result,indent=2));return 0 if result['passed'] else 1

def interactive_compose_server(root):
    # Client-only transport admission. The Windows supervisor/RCON retains its stricter
    # legacy_server.properties() policy; the game command still connects to 127.0.0.1.
    work=(ROOT/'work').resolve(strict=True)
    if root==work or not root.is_relative_to(work):raise ValueError('Compose manual client requires an owned server beneath this fork work/')
    marker=read_json(root/'compose-server.json',16384)
    if (type(marker.get('schema')) is not int or marker['schema']!=1
            or marker.get('pack')!='DEAD-EARTH-SRP' or marker.get('network')!='shared-loopback'):
        raise ValueError('Invalid Compose local-client network marker')
    if str(uuid.UUID(marker['deployment_id']))!=marker['deployment_id']:raise ValueError('Invalid Compose deployment identity')
    props={}
    for raw in (root/'server.properties').read_text(encoding='utf-8-sig').splitlines():
        line=raw.strip()
        if not line or line.startswith(('#','!')):continue
        key,sep,value=line.partition('=');key=key.strip();value=value.strip()
        if (not sep or key in props or '\\' in key or value.endswith('\\')
                or key in legacy_server.CRITICAL_PROPERTIES and '\\' in value):
            raise ValueError('Ambiguous Compose server.properties')
        props[key]=value
    if props.get('server-ip')!='' or props.get('server-port')!='25565':raise ValueError('Compose manual client requires the shared loopback game port 25565')
    world=(root/props.get('level-name','world')).resolve()
    if world==root or not world.is_relative_to(root) or marker.get('world_name')!=props.get('level-name','world'):
        raise ValueError('Compose world differs from its owned deployment marker')
    if props.get('enable-rcon')=='true':
        rcon=int(props.get('rcon.port','25575'))
        if not props.get('rcon.password') or not 1<=rcon<=65535 or rcon==25565:raise ValueError('Invalid private Compose RCON configuration')
    return props,world,25565

def interactive_server(path):
    root=legacy_server.owned_root(path)
    if (root/'compose-server.json').exists():
        props,world,port=interactive_compose_server(root)
    else:
        props,world,port=legacy_server.properties(root)
    if props.get('online-mode')!='false':raise ValueError('Offline local manual clients require the explicitly owned server online-mode=false')
    return root,props,port

def prepare_interactive(args):
    if not re.fullmatch('[A-Za-z0-9_]{3,16}',args.username):raise ValueError('Offline player name must contain 3..16 ASCII letters/digits/underscores')
    server,props,port=interactive_server(args.server_dir)
    target=fresh('legacy-interactive-client-');game=target/'game';game.mkdir()
    for name,data in legacy_port.entries('client',root=ROOT).items():
        if not name.startswith('.minecraft/'):continue
        out=safe_child(game,name[len('.minecraft/'):]);out.parent.mkdir(parents=True,exist_ok=True);out.write_bytes(data)
    cp,index=runtime_files(target,Path(args.forge_dir).resolve(strict=True),args.offline)
    (game/'options.txt').write_text('fullscreen:false\noverrideWidth:1280\noverrideHeight:720\npauseOnLostFocus:false\nrenderDistance:6\nmaxFps:60\ngamma:0.8\nclouds:false\nenableVsync:false\n',encoding='utf-8')
    marker={'schema':1,'kind':'dead-earth-interactive-client','username':args.username,'serverDirectory':str(server),'serverPort':port,'classpath':cp,'assetIndex':index}
    marker['inputHashes']={str(p.resolve()):hashlib.sha256(p.read_bytes()).hexdigest() for p in [*map(Path,cp),*(game/'mods').glob('*.jar')]}
    (target/'interactive-client.json').write_text(json.dumps(marker,indent=2),encoding='utf-8')
    print(json.dumps({'prepared':str(target),'username':args.username,'server':'127.0.0.1:'+str(port),'probeInstalled':False,'autoQuit':False},indent=2))
    return 0

def interactive_command(target,java8):
    target=Path(target).absolute().resolve(strict=True)
    if not target.is_relative_to((ROOT/'work').resolve()) or not target.name.startswith('legacy-interactive-client-'):raise ValueError('Not an isolated manual client directory')
    marker=read_json(target/'interactive-client.json')
    if marker.get('kind')!='dead-earth-interactive-client' or marker.get('schema')!=1:raise ValueError('Not a manual client marker')
    server,props,port=interactive_server(marker['serverDirectory'])
    if port!=marker['serverPort']:raise ValueError('Manual server port changed; prepare a fresh client')
    for filename,digest in marker['inputHashes'].items():
        if hashlib.sha256(Path(filename).read_bytes()).hexdigest()!=digest:raise ValueError('Manual client runtime input changed')
    java=legacy_server.java_executable(java8)
    if not legacy_server.is_java8(java):raise ValueError('An explicit Java 8 runtime is required')
    if os.name!='nt':raise ValueError('This native runtime targets Windows x64')
    name=marker['username'];player_uuid=uuid.UUID(bytes=hashlib.md5(('OfflinePlayer:'+name).encode()).digest(),version=3)
    game=target/'game'
    command=[str(java),'-Xms512M','-Xmx4G','-Djava.library.path='+str(target/'natives'),'-Dorg.lwjgl.librarypath='+str(target/'natives'),'-Dnet.java.games.input.librarypath='+str(target/'natives'),'-Dlog4j2.formatMsgNoLookups=true','-cp',os.pathsep.join(marker['classpath']),'net.minecraft.launchwrapper.Launch','--username',name,'--version','1.12.2-forge-14.23.5.2860','--gameDir',str(game),'--assetsDir',str(target/'assets'),'--assetIndex',marker['assetIndex'],'--uuid',player_uuid.hex,'--accessToken','0','--userType','legacy','--tweakClass','net.minecraftforge.fml.common.launcher.FMLTweaker','--versionType','Forge','--server','127.0.0.1','--port',str(port),'--width','1280','--height','720']
    return command,game,marker

def run_interactive(args):
    command,game,marker=interactive_command(args.run_dir,args.java8)
    target=game.parent
    # Own independent client JVM only; no probe, deadline, screenshot automation or process-name management.
    with (target/'interactive-client-console.log').open('a',encoding='utf-8') as log:
        process=subprocess.Popen(command,cwd=game,stdout=log,stderr=subprocess.STDOUT,creationflags=HIDDEN)
    info={'schema':1,'pid':process.pid,'username':marker['username'],'startedAtUtc':datetime.now(timezone.utc).isoformat(),'gameDirectory':str(game),'autoQuit':False}
    (target/'interactive-client-process.json').write_text(json.dumps(info,indent=2),encoding='utf-8')
    print(json.dumps(info,indent=2));return 0

def main(argv=None):
    parser=argparse.ArgumentParser(description=__doc__,formatter_class=argparse.RawDescriptionHelpFormatter);actions=parser.add_subparsers(dest='action',required=True)
    compile_=actions.add_parser('compile',help='Compile probe only; no client launch or downloads')
    compile_.add_argument('--forge-dir',required=True);compile_.add_argument('--coordinator',required=True);compile_.add_argument('--javac',required=True)
    prep=actions.add_parser('prepare',help='Fresh client runtime/overlay; never starts a game')
    prep.add_argument('--server-dir',required=True);prep.add_argument('--forge-dir',required=True);prep.add_argument('--javac',required=True);prep.add_argument('--offline',action='store_true');prep.add_argument('--timeout',type=int,default=360)
    execute=actions.add_parser('run',help='FINAL STAGE ONLY: launches only its own actual GL client')
    execute.add_argument('--run-dir',required=True);execute.add_argument('--java8',required=True)
    execute.add_argument('--hold-seconds',type=int,default=0,help='Keep the real joined client alive after views for CPU AI; bounded 0..300 seconds, early release via game/client-release.request')
    manualprep=actions.add_parser('prepare-interactive',help='Prepare a separate full client for manual play, with no probe, account access, screenshots or autoquit')
    manualprep.add_argument('--server-dir',required=True);manualprep.add_argument('--forge-dir',required=True);manualprep.add_argument('--username',required=True);manualprep.add_argument('--offline',action='store_true')
    manualrun=actions.add_parser('run-interactive',help='Launch only the prepared manual-play client; leave it running for the human')
    manualrun.add_argument('--run-dir',required=True);manualrun.add_argument('--java8',required=True)
    args=parser.parse_args(argv)
    if args.action=='compile':print(compile_probe(args.javac,args.forge_dir,args.coordinator,fresh('legacy-client-probe-compile-')));return 0
    if args.action=='prepare':
        if not 120<=args.timeout<=1200:raise ValueError('Bounded client timeout must be 120..1200 seconds')
        prepare(args);return 0
    if args.action=='prepare-interactive':return prepare_interactive(args)
    if args.action=='run-interactive':return run_interactive(args)
    return run(args)

if __name__=='__main__':
    try:raise SystemExit(main())
    except (ValueError,OSError,KeyError,StopIteration,subprocess.SubprocessError) as error:
        print('Isolated client verifier: '+str(error),file=sys.stderr);raise SystemExit(2)
