"""Restart an already verified, explicitly marked native test world once."""
import argparse
import json
import os
from pathlib import Path
import socket
import subprocess
import sys
import time
import zipfile

try:
    from . import legacy_port, legacy_server
except ImportError:
    import legacy_port
    import legacy_server

ROOT = Path(__file__).resolve().parents[1]
HIDDEN = getattr(subprocess, 'CREATE_NO_WINDOW', 0)
SOURCE = r'''package dev.deadearth.restoreprobe;
import com.google.gson.*;
import dev.deadearth.legacy.*;
import dev.deadearth.legacy.kernel.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLServerStartedEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.common.MinecraftForge;
@Mod(modid="deadearth_restore_probe",name="Dead Earth Restore Probe",version="1.0.0",dependencies="required-after:dead_earth",acceptableRemoteVersions="*")
public final class RestoreProbe {
 private final Map<String,Object> report=new LinkedHashMap<String,Object>();
 private final List<String> checks=new ArrayList<String>();
 private JsonObject expected;private String initialCampaign,initialEpoch;private int normalTicks;private boolean done;
 private void require(boolean ok,String name){if(!ok)throw new IllegalStateException(name);checks.add(name);}
 @Mod.EventHandler public void started(FMLServerStartedEvent event)throws Exception {
  expected=new JsonParser().parse(new String(Files.readAllBytes(Paths.get("restart-expected.json")),StandardCharsets.UTF_8)).getAsJsonObject();
  LegacyRuntime r=DeadEarthLegacy.runtime;if(r==null)throw new IllegalStateException("Coordinator absent at server started");r.requireThread();
  initialCampaign=r.campaign.campaignId;initialEpoch=r.campaign.worldEpoch;MinecraftForge.EVENT_BUS.register(this);
 }
 @SubscribeEvent public void tick(TickEvent.ServerTickEvent event){
  if(done||event.phase!=TickEvent.Phase.END)return;
  if(++normalTicks<20)return;done=true;MinecraftForge.EVENT_BUS.unregister(this);checkRestored();
 }
 private void checkRestored(){
  try {
   require(Boolean.getBoolean("deadearth.testControls")&&Files.exists(Paths.get(".dead-earth-isolated-native-test")),"isolated test controls");
   LegacyRuntime r=DeadEarthLegacy.runtime;require(r!=null,"coordinator restored before probe");r.requireThread();
   require(normalTicks==20&&initialCampaign.equals(r.campaign.campaignId)&&initialEpoch.equals(r.campaign.worldEpoch),"twenty normal server ticks preserve initial identity");
   require(r.campaign.campaignId.equals(expected.get("campaign_id").getAsString()),"campaign identity preserved");
   require(r.campaign.worldEpoch.equals(expected.get("world_epoch").getAsString()),"world epoch preserved");
   require(r.campaign.activeTicks==expected.get("active_ticks").getAsLong(),"active clock preserved across real restart");
   require(r.campaign.phase==expected.get("phase").getAsInt(),"campaign phase preserved");
   require(r.campaign.points==expected.get("points").getAsDouble(),"campaign evolution points preserved");
   require(r.campaign.starterState.equals(expected.get("starter_state").getAsString()),"starter receipt preserved without reissue");
   require(r.world.players().isEmpty(),"restart has no survival players");
   long before=r.campaign.activeTicks;double points=r.campaign.points;
   r.advanceForTest(200,0);
   require(r.campaign.activeTicks==before&&r.campaign.points==points,"offline evolution and active clock remain paused");
   require(r.engine.operations().size()==expected.get("operations").getAsInt(),"saved active queue restored");
   for(CinematicEngine.Operation operation:r.engine.operations())require(operation.state()!=CinematicEngine.State.APPLYING,"no interrupted effect replays");
   require(r.campaign.zones.size()==6&&r.campaign.zones.size()==expected.get("zones").getAsInt(),"all six zones restored");
   int tickets=0;
   for(Campaign.Zone zone:r.campaign.zones){
    Object world=r.world.world(zone.dimension),pos=Reflect.pos(zone.x,zone.y,zone.z);
    require(Reflect.loaded(world,pos),"persisted relay loaded by restored Forge ticket "+zone.name);
    require(Reflect.blockId(world,pos).equals("dead_earth:zone_relay"),"persisted physical relay "+zone.name);
    Object tile=Reflect.call(world,"getTileEntity/func_175625_s",pos);require(tile!=null,"persisted relay tile "+zone.name);
    Reflect.call(tile,"update/func_73660_a");Object ticket=Reflect.get(tile,"ticket");
    require(ticket!=null&&((Collection<?>)Reflect.call(ticket,"getChunkList")).size()==9,"restored nine-chunk Forge ticket "+zone.name);tickets++;
    require(r.srp.phase(world)==r.campaign.phase,"native SRP phase restored "+zone.name);
   }
   Campaign saved=Campaign.open(r.savePath);
   require(saved.campaignId.equals(r.campaign.campaignId)&&saved.worldEpoch.equals(r.campaign.worldEpoch),"checkpoint remains valid after restart");
   CinematicEngine.restore(saved.cinematic);require(saved.starterState.equals(r.campaign.starterState),"starter receipt remains durable");
   report.put("fullPersistence",dev.deadearth.legacyprobe.RestartSentinels.verify(r,r.world.world(0)));require(true,"physical full-fork sentinels survive a different JVM launch");
   report.put("tickets",tickets);report.put("normalServerTicks",normalTicks);report.put("activeTicks",r.campaign.activeTicks);report.put("phase",r.campaign.phase);report.put("operations",r.engine.operations().size());
   if(Boolean.getBoolean("deadearth.realAiProbe")) {
    Object world=r.world.world(0);Object profile=Reflect.make("com.mojang.authlib.GameProfile",UUID.fromString("44a86b84-0d55-44de-b7af-e5986274846c"),"DeadEarthProbe");
    Object player=Reflect.call(Reflect.type("net.minecraftforge.common.util.FakePlayerFactory"),"get",world,profile);
    Reflect.call(Reflect.get(player,"interactionManager/field_71134_c"),"setGameType/func_73076_a",Reflect.get(Reflect.type("net.minecraft.world.GameType"),"SURVIVAL"));
    Campaign.Zone first=r.campaign.zones.get(0);Reflect.call(player,"setPosition/func_70107_b",first.x+.5,(double)first.y+1,first.z+.5);
    @SuppressWarnings("unchecked") List<Object> participants=(List<Object>)Reflect.get(world,"playerEntities/field_73010_i");if(!participants.contains(player))participants.add(player);
    Reflect.set(r,"observedOnline",1);((List<?>)Reflect.get(r.engine,"history")).clear();Reflect.set(r.engine,"nextDecision",0L);Reflect.set(r.engine,"recoveryUntil",0L);Reflect.set(r.engine,"heavyUntil",0L);Reflect.set(r.engine,"pressuresSinceRest",0);Reflect.set(r.engine,"goal",null);r.checkpoint();Reflect.call(Reflect.get(r,"bridge"),"publish");
    report.put("realAiFixture",true);
   }
   report.put("passed",true);
  }catch(Throwable failure){report.put("passed",false);report.put("error",failure.toString());failure.printStackTrace();}
  report.put("schema",1);report.put("checks",checks);
  try {Path temp=Paths.get("restart-probe.json.tmp");Files.write(temp,JsonFiles.JSON.toJson(report).getBytes(StandardCharsets.UTF_8));Files.move(temp,Paths.get("restart-probe.json"),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}catch(Exception failure){failure.printStackTrace();}
 }
}
'''


def owned(path, retry=False):
    supplied = path.absolute()
    target = supplied.resolve(strict=True)
    work = (ROOT / 'work').resolve(strict=True)
    marker = target / '.dead-earth-isolated-native-test'
    if (os.path.normcase(str(supplied)) != os.path.normcase(str(target))
            or target.parent != work or not target.name.startswith('legacy-native-')
            or marker.is_symlink() or not marker.is_file()):
        raise ValueError('Use an existing directly owned, marked work/legacy-native-* test directory')
    for name in ('mods', 'probe_world', 'probe_world/data/dead_earth_legacy.json'):
        if not (target / name).resolve(strict=True).is_relative_to(target):
            raise ValueError('Linked input escapes the isolated test directory')
    # Minecraft rewrites generator-settings with escaped colons on save. Only
    # the critical network/world values must use the supervisor's plain syntax.
    props = {}
    for raw in (target / 'server.properties').read_text(encoding='utf-8-sig').splitlines():
        line = raw.strip()
        if not line or line.startswith(('#', '!')):
            continue
        key, sep, value = line.partition('=')
        key, value = key.strip(), value.strip()
        if not sep or key in props or '\\' in key or value.endswith('\\'):
            raise ValueError('Ambiguous isolated server properties')
        props[key] = value
    critical = ('server-ip', 'server-port', 'level-name', 'enable-rcon', 'online-mode')
    if any('\\' in props.get(key, '') for key in critical) or props.get('server-ip') != '127.0.0.1':
        raise ValueError('Unsafe isolated binding properties')
    world = (target / props.get('level-name', '')).resolve(strict=True)
    port = int(props.get('server-port', '0'))
    if world != target / 'probe_world' or port not in (25585, 25587) or props.get('enable-rcon') != 'false':
        raise ValueError('Unexpected isolated world or network configuration')
    if retry:
        previous_path = target / 'runner-result-restart.json'
        if not previous_path.exists():
            previous_path = target / 'runner-result-restart.json.attempt1'
        previous = json.loads(previous_path.read_text(encoding='utf-8'))
        if previous.get('exit_code') != 0:
            raise ValueError('Previous restart did not confirm a stopped server')
        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as test:
            test.bind(('127.0.0.1', port))
        archives = ('restart-probe.json', 'runner-result-restart.json', 'native-restart.log')
        if any((target / name).exists() for name in archives):
            for name in archives:
                source, destination = target / name, target / (name + '.attempt1')
                if (source.is_symlink() or source.resolve(strict=True).parent != target
                        or destination.resolve().parent != target or destination.exists()):
                    raise ValueError('Unsafe or occupied prior restart evidence archive')
            for name in archives:
                (target / name).rename(target / (name + '.attempt1'))
        else:
            for name in archives:
                saved = target / (name + '.attempt1')
                if saved.is_symlink() or saved.resolve(strict=True).parent != target:
                    raise ValueError('Incomplete prior restart evidence archive')
    elif (target / 'restart-probe.json').exists() or (target / 'mods/deadearth-restore-probe.jar').exists():
        raise ValueError('This isolated restart probe already ran; use explicit --retry to preserve and repeat')
    return target


def prepare(target, javac, retry=False):
    raw = json.loads((target / 'probe_world/data/dead_earth_legacy.json').read_text(encoding='utf-8'))
    operations = raw['cinematic']['operations']
    expected = dict(campaign_id=raw['campaignId'], world_epoch=raw['worldEpoch'],
                    active_ticks=raw['activeTicks'], phase=raw['phase'], points=raw['points'],
                    zones=len(raw['zones']), operations=sum(op['state'] != 'APPLYING' for op in operations),
                    starter_state=raw['starterState'])
    if expected['zones'] != 6:
        raise ValueError('Expected the successful native fixture with all six zones')
    (target / 'restart-expected.json').write_text(json.dumps(expected, indent=2) + '\n', encoding='utf-8')
    source = target / 'restart-probe-source/dev/deadearth/restoreprobe/RestoreProbe.java'
    source.parent.mkdir(parents=True, exist_ok=retry)
    source.write_text(SOURCE, encoding='utf-8')
    classes = target / 'restart-probe-classes'
    if not classes.resolve().is_relative_to(target):
        raise ValueError('Linked restart class directory')
    classes.mkdir(exist_ok=retry)
    coordinator = target / 'mods' / legacy_port.MOD_NAME
    if not coordinator.is_file():
        raise ValueError('Previously verified coordinator JAR is absent')
    cp = os.pathsep.join(str(path) for path in [coordinator, *legacy_port.forge_classpath(target)])
    response = target / 'javac-restart-probe.args'
    legacy_port.argfile(response, ['--release', '8', '-encoding', 'UTF-8', '-proc:none',
                                  '-classpath', cp, '-d', classes, source, ROOT / 'legacy/coordinator/src/integration/java/dev/deadearth/legacyprobe/RestartSentinels.java'])
    subprocess.run([str(javac.resolve(strict=True)), '-J-Dfile.encoding=UTF-8', '@' + str(response)],
                   cwd=target, check=True, timeout=45, creationflags=HIDDEN)
    # Every move is a non-recursive, exact-name move inside the verified mods folder.
    mods = target / 'mods'; probes = sorted(mods.glob('*probe*.jar'))
    expected_probes = ['deadearth-restore-probe.jar'] if retry else ['deadearth-port-probe.jar']
    if [path.name for path in probes] != expected_probes:
        raise ValueError('Unexpected probe JAR set; refusing to move any files')
    for probe in probes:
        if retry:
            if probe.is_symlink() or probe.resolve().parent != mods.resolve():
                raise ValueError('Unsafe restore probe replacement')
            continue
        disabled = probe.with_name(probe.name + '.disabled')
        if (probe.resolve().parent != mods.resolve() or disabled.resolve().parent != mods.resolve()
                or probe.is_symlink() or disabled.exists()):
            raise ValueError('Unsafe or occupied probe disable destination')
        probe.rename(disabled)
    with zipfile.ZipFile(mods / 'deadearth-restore-probe.jar', 'w', zipfile.ZIP_DEFLATED) as jar:
        for path in sorted(classes.rglob('*.class')):
            jar.write(path, path.relative_to(classes).as_posix())
        jar.writestr('mcmod.info', json.dumps([dict(modid='deadearth_restore_probe', name='Dead Earth Restore Probe',
                                                 version='1.0.0', mcversion='1.12.2', serverSideOnly=True)]))
    return expected


def run(target, java, real_ai=False):
    report = target / 'restart-probe.json'; log = target / 'native-restart.log'
    started = time.monotonic()
    ai_passed = None
    command = [str(java), '-Xms512M', '-Xmx4096M', '-Djava.awt.headless=true',
               '-Ddeadearth.testControls=true', '-Ddeadearth.realAiProbe='+str(real_ai).lower(), '-jar', str(target / 'forge-1.12.2-14.23.5.2860.jar'), 'nogui']
    print('Restarting isolated native fixture: ' + str(target), flush=True)
    with log.open('w', encoding='utf-8') as output:
        process = subprocess.Popen(command, cwd=target, stdin=subprocess.PIPE, stdout=output,
                                   stderr=subprocess.STDOUT, text=True, encoding='utf-8', creationflags=HIDDEN)
        try:
            deadline = started + 120
            while process.poll() is None and not report.is_file() and time.monotonic() < deadline:
                time.sleep(.5)
            if real_ai and process.poll() is None and report.is_file() and json.loads(report.read_text(encoding='utf-8')).get('passed') is True:
                tested=subprocess.run([sys.executable,str(ROOT/'tools/verify_legacy_ai.py'),'--server-dir',str(target),'--native-fixture'],cwd=ROOT,timeout=240,creationflags=HIDDEN)
                ai_passed=tested.returncode==0
        finally:
            if process.poll() is None:
                try: process.stdin.write('stop\n'); process.stdin.flush()
                except (OSError, BrokenPipeError): pass
            try: code = process.wait(timeout=40)
            except subprocess.TimeoutExpired:
                raise RuntimeError('Isolated restart server still saving, left running with PID ' + str(process.pid)) from None
    native = json.loads(report.read_text(encoding='utf-8')) if report.is_file() else dict(passed=False, error='restart_report_missing')
    result = dict(schema=1, passed=native.get('passed') is True and code == 0 and (not real_ai or ai_passed is True), real_ai_passed=ai_passed,
                  exit_code=code, elapsed_seconds=round(time.monotonic() - started, 2),
                  report=str(report), log=str(log), checks=len(native.get('checks', [])), native=native)
    (target / 'runner-result-restart.json').write_text(json.dumps(result, indent=2) + '\n', encoding='utf-8')
    print(json.dumps(result, indent=2), flush=True)
    return 0 if result['passed'] else 1


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--server-dir', required=True, type=Path)
    parser.add_argument('--javac', required=True, type=Path)
    parser.add_argument('--java8', required=True, type=Path)
    parser.add_argument('--retry', action='store_true', help='Archive the first stopped attempt and repeat after twenty normal ticks')
    parser.add_argument('--real-ai',action='store_true',help='After successful restore, run actual pinned CPU primary and critic through the real bridge')
    args = parser.parse_args(argv)
    try:
        target = owned(args.server_dir, args.retry)
        java = legacy_server.find_java8(args.java8)
        expected = prepare(target, args.javac, args.retry)
        print('Expected restart state: ' + json.dumps(expected), flush=True)
        return run(target, java, args.real_ai)
    except (OSError, ValueError, RuntimeError, subprocess.SubprocessError) as failed:
        print('Isolated restart verification failed: ' + str(failed), file=sys.stderr, flush=True)
        return 1


if __name__ == '__main__':
    raise SystemExit(main())
