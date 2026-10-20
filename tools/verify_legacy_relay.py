"""Narrow native relay regression on loopback25587 in a NEW stopped-template copy.

The complete pinned server mod set is retained. The template, existing evidence,
user worlds, server/client installations and production AI settings are never changed.
"""
import argparse
from datetime import datetime, timezone
import hashlib
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

ROOT = Path(__file__).resolve().parents[1]
PORT = 25587
HIDDEN = getattr(subprocess, 'CREATE_NO_WINDOW', 0)


def prepare(template, javac):
    given = Path(template).absolute()
    template = given.resolve(strict=True)
    if (os.path.normcase(str(given)) != os.path.normcase(str(template))
            or template.parent != (ROOT / 'work').resolve()
            or not (template / '.dead-earth-isolated-native-test').is_file()):
        raise ValueError('Use a real stopped isolated native template beneath repository work')
    receipt = json.loads((template / 'runner-result.json').read_text(encoding='utf-8'))
    if receipt.get('exit_code') != 0 or receipt.get('timed_out') is not False:
        raise ValueError('Template did not finish and save normally')
    _, world, oldport = legacy_server.properties(template)
    if not world.is_dir():
        raise ValueError('Stopped template world is missing')
    # Reject linked runtime/world inputs before copying. Reads never mutate the template.
    legacy_port.forge_classpath(template)
    legacy_port.files(world)
    with socket.socket() as listener:
        listener.bind(('127.0.0.1', PORT))  # Fixed port: no fallback.
    with socket.socket() as probe:
        probe.settimeout(.5)
        if probe.connect_ex(('127.0.0.1', oldport)) == 0:
            raise ValueError('Isolated template server port is still active')
    target = ROOT / 'work' / ('legacy-relay-'
        + datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%S%fZ') + '-' + uuid.uuid4().hex[:8])
    if target.resolve().parent != (ROOT / 'work').resolve():
        raise ValueError('Unsafe isolated relay directory')
    target.mkdir()  # Never replace or clean any previous run.
    for name in (verify_legacy_server.FORGE_JAR, verify_legacy_server.MINECRAFT_JAR):
        shutil.copy2(template / name, target / name)
    shutil.copytree(template / 'libraries', target / 'libraries')
    verify_legacy_server.write_overlay(target)  # Verifies full pins and coordinator freshness.
    shutil.copytree(world, target / 'probe_world')
    values = {'server-ip': '127.0.0.1', 'server-port': PORT, 'online-mode': 'true',
        'enable-rcon': 'false', 'enable-query': 'false', 'view-distance': 3, 'max-players': 2,
        'level-name': 'probe_world', 'level-type': 'DEFAULT', 'allow-nether': 'false',
        'spawn-protection': 0, 'spawn-animals': 'false', 'spawn-monsters': 'false',
        'difficulty': 2, 'gamemode': 0, 'allow-flight': 'true', 'max-tick-time': 60000,
        'motd': 'DEAD EARTH isolated relay-only verification'}
    (target / 'server.properties').write_text(
        ''.join(str(key) + '=' + str(value) + '\n' for key, value in values.items()), encoding='utf-8')
    for marker in ('.dead-earth-isolated-native-test', '.dead-earth-relay-only'):
        (target / marker).write_text('Explicit new relay-only fixture; template untouched.\n', encoding='utf-8')
    (target / 'eula.txt').write_text('eula=true\n', encoding='ascii')
    compile_probe(target, javac)
    full = legacy_port.full_pins(ROOT)
    expected = [row for row in full if row['side'] in {'server', 'both'}]
    (target / 'relay-run.json').write_text(json.dumps(dict(schema=1, port=PORT,
        template=str(template), template_read_only=True, scope='native_relay_only',
        full_pack_retained=True, full_lock_count=len(full), server_native_pin_count=len(expected),
        server_mods=[dict(mod_id=row['mod_id'], filename=row['filename'], sha256=row['sha256'])
                     for row in expected]), indent=2) + '\n', encoding='utf-8')
    return target


def compile_probe(target, javac):
    source = ROOT / 'legacy/coordinator'
    sources = [source / 'src/relaytest/java/dev/deadearth/legacyprobe/combat/RelayOnlyProbe.java',
               source / 'src/integration/java/dev/deadearth/legacyprobe/combat/RelaySiegeProbe.java']
    stubs = sorted((source / 'compile-only-stubs').rglob('*.java'))
    inputs = sources + stubs
    before = {str(path.relative_to(ROOT)): hashlib.sha256(path.read_bytes()).hexdigest()
              for path in inputs}
    classes = target / 'relay-classes'
    classes.mkdir()
    cp = os.pathsep.join(map(str, [target / 'mods' / legacy_port.MOD_NAME,
                                  *legacy_port.forge_classpath(target)]))
    response = target / 'javac-relay.args'
    legacy_port.argfile(response, ['--release', '8', '-encoding', 'UTF-8', '-proc:none',
        '-classpath', cp, '-d', classes, *inputs])
    subprocess.run([str(javac), '@' + str(response)], cwd=target, check=True,
                   timeout=90, creationflags=HIDDEN)
    after = {str(path.relative_to(ROOT)): hashlib.sha256(path.read_bytes()).hexdigest()
             for path in inputs}
    if before != after:
        raise ValueError('Relay probe sources changed during compilation; compile again')
    packaged = []
    with zipfile.ZipFile(target / 'mods/deadearth-relay-probe.jar', 'w', zipfile.ZIP_DEFLATED) as jar:
        for path in sorted(classes.rglob('*.class')):
            name = path.relative_to(classes).as_posix()
            if name.startswith('dev/deadearth/legacyprobe/combat/'):
                jar.write(path, name)
                packaged.append(name)
        jar.writestr('mcmod.info', json.dumps([dict(modid='deadearth_relay_probe',
            name='Dead Earth Relay Only Probe', version='1.0', mcversion='1.12.2', serverSideOnly=True)]))
    if not {'dev/deadearth/legacyprobe/combat/RelayOnlyProbe.class',
            'dev/deadearth/legacyprobe/combat/RelaySiegeProbe.class'}.issubset(packaged):
        raise ValueError('Relay-only entry point or session class is absent')
    (target / 'relay-probe-build.json').write_text(json.dumps(dict(schema=1, javac=str(javac),
        sources_sha256=before, packaged_classes=packaged, compile_only_stubs_packaged=False),
        indent=2) + '\n', encoding='utf-8')


def run(target, java, timeout):
    report = target / 'relay-probe.json'
    log = target / 'relay-server.log'
    command = [str(java), '-Xms512M', '-Xmx3072M', '-Djava.awt.headless=true',
        '-Ddeadearth.testControls=true', '-jar', str(target / verify_legacy_server.FORGE_JAR), 'nogui']
    started = time.monotonic()
    timed_out = False
    print(json.dumps(dict(stage='starting_narrow_relay', directory=str(target), port=PORT)), flush=True)
    with log.open('w', encoding='utf-8') as output:
        process = subprocess.Popen(command, cwd=target, stdin=subprocess.PIPE, stdout=output,
            stderr=subprocess.STDOUT, text=True, encoding='utf-8', creationflags=HIDDEN)
        try:
            while process.poll() is None and not report.is_file():
                if time.monotonic() - started > timeout:
                    timed_out = True
                    break
                time.sleep(.5)
        finally:
            exit_code = verify_legacy_server.stop_with_save(process)
    result = dict(schema=1, scope='native_relay_only', exit_code=exit_code, timed_out=timed_out,
        elapsed_seconds=round(time.monotonic() - started, 2), directory=str(target),
        report=str(report), passed=False)
    if report.is_file():
        value = json.loads(report.read_text(encoding='utf-8'))
        result.update(passed=value.get('passed') is True and exit_code == 0 and not timed_out, relay=value)
    else:
        result['error'] = 'Native relay report absent'
    (target / 'relay-runner-result.json').write_text(
        json.dumps(result, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(json.dumps({key: value for key, value in result.items() if key != 'relay'},
                     ensure_ascii=False), flush=True)
    return 0 if result['passed'] else 1


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--template', type=Path, required=True)
    parser.add_argument('--javac', type=Path, required=True)
    parser.add_argument('--java8', type=Path)
    parser.add_argument('--timeout', type=int, default=180)
    parser.add_argument('--compile-only', action='store_true')
    args = parser.parse_args()
    if not 60 <= args.timeout <= 180:
        parser.error('Use bounded timeout60..180seconds')
    target = prepare(args.template, args.javac.resolve(strict=True))
    if args.compile_only:
        print(json.dumps(dict(prepared=str(target), server_started=False)))
        return 0
    return run(target, legacy_server.find_java8(args.java8), args.timeout)


if __name__ == '__main__':
    raise SystemExit(main())
