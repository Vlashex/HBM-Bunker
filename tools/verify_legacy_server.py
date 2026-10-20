"""Run the SRP core-port native probe in a brand-new, isolated Forge 1.12.2 server.

Call only during the final verification pass, after compiling the complete port.
No existing world, live server, or launcher profile is modified.
"""
import argparse
from datetime import datetime, timezone
import json
import os
from pathlib import Path, PurePosixPath
import shutil
import socket
import subprocess
import sys
import threading
import time
from urllib.request import ProxyHandler, build_opener
import uuid
import zipfile

try:
    from . import legacy_port, legacy_server
except ImportError:
    import legacy_port
    import legacy_server

ROOT = Path(__file__).resolve().parents[1]
FORGE_JAR = 'forge-1.12.2-14.23.5.2860.jar'
MINECRAFT_JAR = 'minecraft_server.1.12.2.jar'
HIDDEN = getattr(subprocess, 'CREATE_NO_WINDOW', 0)
NATIVE_WRITER = '''package com.dhanantry.scapeandrunparasites.legacyprobe;
import dev.deadearth.legacy.Reflect;
/** Test-only native call-chain fixture; never included in the shipped coordinator. */
public final class NativeWriter {
    public static boolean write(Object world,Object pos,Object state) {
        return (Boolean)Reflect.call(world,"setBlockState/func_180501_a",pos,state,3);
    }
}
'''


def free_test_port(preferred):
    choices = [preferred] + [value for value in (25585, 25587) if value != preferred]
    for port in choices:
        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as candidate:
            try:
                candidate.bind(('127.0.0.1', port))
            except OSError:
                continue
            return port
    raise ValueError('Both isolated test ports 25585 and 25587 are in use')


def write_overlay(target):
    # entries() verifies dependency pins and rejects a stale coordinator cache.
    for name, data in legacy_port.entries('server', root=ROOT).items():
        relative = PurePosixPath(name)
        if (relative.is_absolute() or '..' in relative.parts or '\\' in name
                or ':' in name or not relative.parts):
            raise ValueError('Unsafe server overlay path: ' + name)
        path = target.joinpath(*relative.parts)
        if not path.resolve().is_relative_to(target.resolve()):
            raise ValueError('Server overlay escapes the isolated directory')
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(data)


def prepare(fixture, preferred_port):
    fixture = fixture.resolve(strict=True)
    classpath = legacy_port.forge_classpath(fixture)
    if classpath[0].name != FORGE_JAR or not (fixture / MINECRAFT_JAR).is_file():
        raise ValueError('Expected installed Forge 2860 / Minecraft 1.12.2 runtime')
    work = ROOT / 'work'
    work.mkdir(exist_ok=True)
    stamp = datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%S%fZ')
    target = work / ('legacy-native-' + stamp + '-' + uuid.uuid4().hex[:8])
    if target.resolve().parent != work.resolve():
        raise ValueError('Unsafe isolated test directory')
    target.mkdir()  # Never replace or clean an existing test directory.
    # Deliberately copy only runtime files, never config, mods, accounts or worlds.
    shutil.copy2(fixture / FORGE_JAR, target / FORGE_JAR)
    shutil.copy2(fixture / MINECRAFT_JAR, target / MINECRAFT_JAR)
    shutil.copytree(fixture / 'libraries', target / 'libraries')
    write_overlay(target)
    port = free_test_port(preferred_port)
    properties = {
        'server-ip': '127.0.0.1', 'server-port': port,
        'online-mode': 'true', 'enable-rcon': 'false', 'enable-query': 'false',
        'broadcast-rcon-to-ops': 'false', 'view-distance': 3, 'max-players': 2,
        'level-name': 'probe_world', 'level-type': 'FLAT',
        'generator-settings': '3;minecraft:bedrock,2*minecraft:dirt,minecraft:grass;1;',
        'generate-structures': 'false', 'allow-nether': 'false',
        'spawn-protection': 0, 'spawn-animals': 'false', 'spawn-monsters': 'false',
        'difficulty': 2, 'gamemode': 0, 'allow-flight': 'true',
        'max-tick-time': 60000, 'motd': 'DEAD EARTH SRP isolated native verification',
    }
    (target / 'server.properties').write_text(''.join(str(k) + '=' + str(v) + '\n' for k, v in properties.items()), encoding='utf-8')
    (target / '.dead-earth-isolated-native-test').write_text('Fresh isolated verification only.\n', encoding='utf-8')
    # Acceptance is scoped to this newly created, preauthorized test directory.
    (target / 'eula.txt').write_text('eula=true\n', encoding='ascii')
    return target, port


def compile_probe(target, javac):
    own_jar = target / 'mods' / legacy_port.MOD_NAME
    if not own_jar.is_file():
        raise ValueError('Compiled coordinator is absent from the verified server overlay')
    compiler = javac.resolve(strict=True)
    if not compiler.is_file():
        raise ValueError('--javac must name a JDK 9+ compiler executable supporting --release 8')
    source = ROOT / 'legacy/coordinator/src/integration/java/dev/deadearth/legacyprobe/PortProbe.java'
    if not source.is_file():
        raise ValueError('Native PortProbe source is missing')
    generated = target / 'probe-source/com/dhanantry/scapeandrunparasites/legacyprobe/NativeWriter.java'
    generated.parent.mkdir(parents=True)
    generated.write_text(NATIVE_WRITER, encoding='utf-8')
    classes = target / 'probe-classes'; classes.mkdir()
    cp = os.pathsep.join(str(path) for path in [own_jar, *legacy_port.forge_classpath(target)])
    arguments = ['--release', '8', '-encoding', 'UTF-8', '-proc:none', '-classpath', cp,
                 '-d', classes, source, generated]
    response = target / 'javac-probe.args'; legacy_port.argfile(response, arguments)
    command = [str(compiler), '-J-Dfile.encoding=UTF-8', '@' + str(response)]
    print('Compiling isolated probe: ' + subprocess.list2cmdline(command), flush=True)
    subprocess.run(command, cwd=target, check=True, timeout=60, creationflags=HIDDEN)
    jar = target / 'mods/deadearth-port-probe.jar'
    with zipfile.ZipFile(jar, 'w', zipfile.ZIP_DEFLATED) as archive:
        for path in sorted(classes.rglob('*.class')):
            archive.write(path, path.relative_to(classes).as_posix())
        archive.writestr('mcmod.info', json.dumps([{
            'modid': 'deadearth_port_probe', 'name': 'DEAD EARTH SRP Native Port Probe',
            'version': '0.2.0', 'mcversion': '1.12.2', 'clientSideOnly': False,
            'serverSideOnly': True, 'description': 'Isolated verification fixture; never ship.',
        }]))
    (target / 'probe-build.json').write_text(json.dumps({
        'schema': 1, 'command': command, 'coordinator': str(own_jar),
        'test_fixture_only': True, 'sources': [str(source), str(generated)],
    }, indent=2) + '\n', encoding='utf-8')


def stop_with_save(process):
    if process.poll() is not None:
        return process.returncode
    try:
        process.stdin.write('stop\n'); process.stdin.flush()
    except (BrokenPipeError, OSError):
        pass
    try:
        return process.wait(timeout=45)
    except subprocess.TimeoutExpired:
        # Never force-kill a server while it may be saving. This PID belongs
        # solely to this invocation; the caller can inspect its own log.
        raise RuntimeError('Isolated server is still saving; left running with PID ' + str(process.pid)) from None


def bridge_native_checks(target):
    """Exercise the real transport/admin with a deliberately mocked selector."""
    checks = []
    result = {'schema': 1, 'passed': False, 'checks': checks,
              'python_source': 'packaged_server_overlay',
              'model_kind': 'fixed_selection_stub', 'real_inference_tested': False,
              'model_selection_tested': False}
    journal = None
    http_server = None
    http_thread = None

    def require(condition, name):
        checks.append({'name': name, 'passed': bool(condition)})
        if not condition:
            raise AssertionError(name)

    try:
        # Validate the Python code actually included in this server overlay.
        sys.path.insert(0, str(target / 'director'))
        from dead_earth_director.bridge import BridgeClient, Descriptor, MAX_OUTPUT
        from dead_earth_director.host import CinematicDirector, Journal
        from dead_earth_director import admin

        descriptor_path = target / 'probe_world/dead_earth_bridge.json'
        descriptor = Descriptor.load(descriptor_path)
        client = BridgeClient(descriptor)
        envelope = client.call('get_snapshot', {})
        snapshot = envelope.get('snapshot') if type(envelope) is dict else None
        require(type(snapshot) is dict, 'real cached snapshot received')
        require(snapshot.get('campaignId') == descriptor.campaign_id
                and snapshot.get('worldEpoch') == descriptor.world_epoch,
                'real snapshot matches campaign and epoch')
        require(snapshot.get('parasiteSystem') == 'srp'
                and snapshot.get('parasitePhaseMaximum') == 10
                and type(snapshot.get('parasitePhase')) is int
                and 0 <= snapshot['parasitePhase'] <= 10,
                'real snapshot exposes SRP phases zero through ten')
        require(type(envelope.get('cache_age_millis')) is int
                and 0 <= envelope['cache_age_millis'] < 30000,
                'real snapshot cache age is bounded and current')
        require(snapshot.get('onlinePlayers', 0) >= 1, 'retained survival player remains online')
        queue = client.call('get_cinematic_queue', {})
        require(type(queue) is dict and type(queue.get('operations')) is list,
                'real native queue received')
        brief = client.call('get_cinematic_brief', {})
        result['brief_status'] = brief.get('status') if type(brief) is dict else 'invalid'
        require(type(brief) is dict and brief.get('status') == 'ready',
                'real native brief is ready for model selection')

        class FixedSelection:
            calls = 0

            def select(self, context):
                self.calls += 1
                require(context.get('parasite_system') == 'srp'
                        and context.get('parasite_phase_maximum') == 10
                        and type(context.get('parasite_phase')) is int
                        and 0 <= context['parasite_phase'] <= 10,
                        'actual director projects native SRP phase into model context')
                options = context.get('options')
                require(type(options) is list and 1 <= len(options) <= 8,
                        'actual director passes bounded native options')
                require(all(option.get('faction') in {'parasites', 'siege', 'mixed'} for option in options),
                        'actual native model options contain no forest faction')
                serialized = json.dumps(context, ensure_ascii=False)
                require(descriptor.token not in serialized
                        and all('budget' not in option and 'zoneId' not in option for option in options),
                        'actual director removes credentials and physical allocation from model context')
                return {'option': 0}, {'elapsed_ms': 0, 'mocked': True}

        runtime = target / 'runtime/native-bridge-probe'
        journal_path = runtime / 'decisions.sqlite3'
        journal = Journal(journal_path)
        model = FixedSelection()
        selected = CinematicDirector(client, model, journal).once()
        result['director_state'] = selected.get('state')
        result['model_selection_tested'] = model.calls == 1
        require(model.calls == 1 and selected.get('source') == 'model'
                and selected.get('state') in {'queued', 'already_queued', 'already_recorded'},
                'mocked selector admits a real native operation through CinematicDirector')
        saved = journal.read(selected['goal_id'])
        require(type(saved) is dict and saved.get('report', {}).get('operation_id') == selected['operation_id'],
                'real director journal records native operation receipt')
        choice = saved['choice']
        replay = client.call('choose_cinematic', {
            'request_id': saved['request_id'], 'goal_id': selected['goal_id'],
            'event_id': choice['event_id'], 'zone_id': choice['zone_id'],
        })
        require(replay.get('accepted') is True and replay.get('operationId') == selected['operation_id']
                and replay.get('code') in {'already_queued', 'already_recorded'},
                'real protocol replay preserves the operation UUID')
        after = client.call('get_cinematic_queue', {})
        ids = {item.get('id', item.get('operationId')) for item in after.get('operations', []) + after.get('history', [])}
        require(selected['operation_id'] in ids, 'real selected operation appears in native queue or history')

        def rejected_request(**overrides):
            request = dict(protocol=1, token=descriptor.token, campaign_id=descriptor.campaign_id,
                           world_epoch=descriptor.world_epoch, operation='get_snapshot', arguments={})
            request.update(overrides)
            with socket.create_connection((descriptor.host, descriptor.port), timeout=2) as connection:
                connection.settimeout(4)
                connection.sendall(json.dumps(request, separators=(',', ':')).encode('utf-8') + b'\n')
                with connection.makefile('rb') as incoming:
                    data = incoming.readline(MAX_OUTPUT + 2)
            require(data.endswith(b'\n') and len(data) <= MAX_OUTPUT + 1,
                    'rejected real protocol request has bounded complete response')
            response = json.loads(data)
            return response.get('error', {}).get('code') if response.get('ok') is False else None

        wrong_token = ('0' if descriptor.token[0] != '0' else '1') + descriptor.token[1:]
        require(rejected_request(token=wrong_token) == 'unauthorized', 'real socket rejects invalid credentials')
        require(rejected_request(world_epoch=str(uuid.uuid4())) == 'stale_context', 'real socket rejects stale world epoch')

        app = admin.Application(descriptor_path, journal_path, runtime / 'admin.sqlite3',
                                target / 'config/dead-earth/director/catalog.json')
        app.observer.collect()
        require(app.observer.status().get('state') == 'online', 'actual admin observer collects native bridge data')
        http_server = admin.make_server(app, port=0)
        http_thread = threading.Thread(target=http_server.serve_forever,
                                       kwargs={'poll_interval': .1}, daemon=True)
        http_thread.start()
        origin = 'http://127.0.0.1:' + str(http_server.server_address[1])
        opener = build_opener(ProxyHandler({}))

        def get(path):
            with opener.open(origin + path, timeout=5) as response:
                require(response.status == 200, 'actual admin HTTP ' + path + ' succeeds')
                data = response.read(4194305)
            require(len(data) <= 4194304, 'actual admin HTTP response is bounded')
            return json.loads(data)

        health = get('/api/health')
        require(health.get('product') == admin.PRODUCT, 'actual admin health identifies its product')
        dashboard = get('/api/dashboard')
        dashboard_snapshot = dashboard.get('snapshot') or {}
        require(dashboard.get('connection', {}).get('state') == 'online'
                and dashboard_snapshot.get('parasiteSystem') == 'srp'
                and dashboard_snapshot.get('parasitePhaseMaximum') == 10,
                'actual HTTP dashboard exposes the collected native SRP snapshot')
        require(type((dashboard.get('queue') or {}).get('operations')) is list,
                'actual HTTP dashboard exposes native queue')
        result['passed'] = True
    except Exception as failed:
        result['error'] = str(failed) if isinstance(failed, AssertionError) else type(failed).__name__
    finally:
        if http_server is not None:
            http_server.shutdown(); http_server.server_close()
        if http_thread is not None:
            http_thread.join(timeout=2)
        if journal is not None:
            journal.close()
        report_path = target / 'bridge-native-checks.json'
        report_path.write_text(json.dumps(result, indent=2) + '\n', encoding='utf-8')
    return result


def run_server(target, java, timeout, memory_mb):
    report = target / 'port-probe.json'
    log = target / 'native-server.log'
    command = [str(java), '-Xms512M', '-Xmx' + str(memory_mb) + 'M',
               '-Djava.awt.headless=true', '-Ddeadearth.testControls=true', '-Ddeadearth.bridgeProbe=true',
               '-jar', str(target / FORGE_JAR), 'nogui']
    started = time.monotonic()
    print('Starting isolated Forge server: ' + str(target), flush=True)
    timed_out = False
    bridge_result = None
    with log.open('w', encoding='utf-8') as output:
        process = subprocess.Popen(command, cwd=target, stdin=subprocess.PIPE,
                                   stdout=output, stderr=subprocess.STDOUT,
                                   text=True, encoding='utf-8', creationflags=HIDDEN)
        try:
            deadline = started + timeout
            while process.poll() is None and not report.is_file():
                if time.monotonic() >= deadline:
                    timed_out = True
                    break
                time.sleep(.5)
            if report.is_file() and process.poll() is None and not timed_out:
                native = json.loads(report.read_text(encoding='utf-8'))
                if native.get('passed') is True:
                    print('Native probe passed; checking real bridge/director/admin with a fixed selector.', flush=True)
                    bridge_result = bridge_native_checks(target)
        finally:
            exit_code = stop_with_save(process)
    result = {
        'schema': 1, 'server_directory': str(target), 'log': str(log),
        'report': str(report), 'elapsed_seconds': round(time.monotonic() - started, 2),
        'exit_code': exit_code, 'timed_out': timed_out, 'passed': False,
        'bridge_report': str(target / 'bridge-native-checks.json'),
        'bridge_validation': bridge_result or {'passed': False, 'state': 'not_run'},
    }
    if report.is_file():
        probe = json.loads(report.read_text(encoding='utf-8'))
        result.update(passed=probe.get('passed') is True and exit_code == 0 and not timed_out
                      and bridge_result is not None and bridge_result.get('passed') is True,
                      assertion_count=len(probe.get('checks', [])),
                      failures=[item for item in probe.get('checks', []) if item.get('passed') is not True])
    else:
        result['error'] = 'Native probe report missing' + (' after startup timeout' if timed_out else '')
        print('\n'.join(log.read_text(encoding='utf-8', errors='replace').splitlines()[-30:]), flush=True)
    (target / 'runner-result.json').write_text(json.dumps(result, indent=2) + '\n', encoding='utf-8')
    print(json.dumps(result, indent=2), flush=True)
    return 0 if result['passed'] else 1


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--fixture', type=Path, default=ROOT / 'work/storage-server-20261020',
                        help='Installed Forge fixture; only Forge, Minecraft and libraries are read')
    parser.add_argument('--javac', type=Path, required=True, help='JDK 9+ javac executable (--release 8)')
    parser.add_argument('--java8', type=Path, help='Java 8 executable/JRE; otherwise use the own-folder supervisor discovery')
    parser.add_argument('--port', type=int, choices=(25585, 25587), default=25585)
    parser.add_argument('--timeout', type=int, default=180, help='Bounded server startup/report deadline, 30..180 seconds')
    parser.add_argument('--memory-mb', type=int, default=4096)
    args = parser.parse_args(argv)
    if not 30 <= args.timeout <= 180 or not 1024 <= args.memory_mb <= 6144:
        parser.error('Use timeout 30..180 seconds and memory 1024..6144 MiB')
    try:
        java = legacy_server.find_java8(args.java8)
        target, port = prepare(args.fixture, args.port)
        print('Test binding configuration: 127.0.0.1:' + str(port), flush=True)
        compile_probe(target, args.javac)
        return run_server(target, java, args.timeout, args.memory_mb)
    except (OSError, ValueError, RuntimeError, subprocess.SubprocessError) as failed:
        print('Native verification failed: ' + str(failed), file=sys.stderr, flush=True)
        return 1


if __name__ == '__main__':
    raise SystemExit(main())
