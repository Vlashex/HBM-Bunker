"""Own-folder Forge 1.12.2 supervisor. Never finds or kills processes by name/PID."""
import argparse
from contextlib import contextmanager
import json
import os
from pathlib import Path
import re
import shutil
import socket
import subprocess
import sys
import threading
import time
import uuid
import tomllib
import urllib.request

HIDDEN = getattr(subprocess, 'CREATE_NO_WINDOW', 0)
EXPECTED_MARKER = {'schema': 1, 'pack': 'DEAD-EARTH-SRP', 'version': '1.0.4',
                   'runtime': 'forge-1.12.2-14.23.5.2860', 'kind': 'full-fork'}
CRITICAL_PROPERTIES = frozenset({'server-ip', 'server-port', 'level-name', 'enable-rcon',
    'rcon.port', 'rcon.password', 'online-mode', 'white-list', 'enforce-whitelist'})


def owned_root(value):
    supplied = Path(value).absolute()
    root = supplied.resolve(strict=True)
    # Refuse junction/symlink roots, including the production server's junction aliases.
    if os.path.normcase(str(root)) != os.path.normcase(str(supplied)):
        raise ValueError('Use the real, dedicated core-port server directory, without junction aliases')
    marker = root / 'legacy-port.json'
    if marker.is_symlink() or json.loads(marker.read_text(encoding='utf-8')) != EXPECTED_MARKER:
        raise ValueError('This folder is not a DEAD EARTH SRP 1.0.4 full-fork server overlay')
    for name in ('logs', 'runtime', 'director', 'director/runtime', 'config', 'mods', 'server.properties', 'eula.txt'):
        if not (root / name).resolve().is_relative_to(root):
            raise ValueError('Server inputs and writable directories must remain inside the owned folder')
    return root


def properties(root):
    # Critical values must have identical raw meanings in Python and Java. Minecraft's
    # escaped generator-settings/motd are unread here and may remain opaque raw strings.
    result = {}
    for raw in (root / 'server.properties').read_text(encoding='utf-8-sig').splitlines():
        line = raw.strip()
        if not line or line.startswith(('#', '!')): continue
        key, sep, value = line.partition('='); key = key.strip(); value = value.strip()
        if (not sep or key in result or '\\' in key or value.endswith('\\')
                or (key in CRITICAL_PROPERTIES and '\\' in value)):
            raise ValueError('Ambiguous server.properties; no key escapes, critical value escapes or line continuations')
        result[key] = value
    if result.get('server-ip') != '127.0.0.1':
        raise ValueError('This local launcher requires server-ip=127.0.0.1')
    port = int(result.get('server-port', '25587'))
    world = (root / result.get('level-name', 'world')).resolve()
    if not 1 <= port <= 65535 or not world.is_relative_to(root) or world == root:
        raise ValueError('Unsafe world directory or server port')
    if result.get('enable-rcon') == 'true':
        rcon = int(result.get('rcon.port', '25588'))
        if not result.get('rcon.password') or not 1 <= rcon <= 65535 or rcon == port:
            raise ValueError('RCON needs a private password and a separate valid loopback port')
    return result, world, port


def runtime_dir(root):
    directory = root / 'runtime/legacy-server'
    if not directory.resolve().is_relative_to(root): raise ValueError('Linked runtime directory')
    directory.mkdir(parents=True, exist_ok=True)
    return directory


@contextmanager
def exclusive(path):
    if path.is_symlink(): raise ValueError('Linked supervisor lock file')
    handle = path.open('a+b')
    try:
        handle.seek(0, 2)
        if handle.tell() == 0: handle.write(b'0'); handle.flush()
        handle.seek(0)
        try:
            if os.name == 'nt':
                import msvcrt
                msvcrt.locking(handle.fileno(), msvcrt.LK_NBLCK, 1)
            else:
                import fcntl
                fcntl.flock(handle.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
        except OSError:
            yield False
        else:
            try: yield True
            finally:
                handle.seek(0)
                if os.name == 'nt':
                    import msvcrt
                    msvcrt.locking(handle.fileno(), msvcrt.LK_UNLCK, 1)
                else:
                    import fcntl
                    fcntl.flock(handle.fileno(), fcntl.LOCK_UN)
    finally: handle.close()


def atomic_json(path, value):
    temporary = path.with_name(path.name + '.' + uuid.uuid4().hex + '.tmp')
    try:
        temporary.write_text(json.dumps(value, indent=2) + '\n', encoding='utf-8')
        temporary.replace(path)
    finally: temporary.unlink(missing_ok=True)


def java_executable(value):
    candidate = Path(value).expanduser()
    if candidate.is_dir(): candidate = candidate / 'bin' / ('java.exe' if os.name == 'nt' else 'java')
    return candidate


def is_java8(candidate):
    if not candidate.is_file(): return False
    try:
        result = subprocess.run([str(candidate), '-version'], capture_output=True, text=True,
                                errors='replace', timeout=10, creationflags=HIDDEN)
        return result.returncode == 0 and re.search(r'version\s+"(?:1\.8\.|8(?:\.|"))', result.stdout + result.stderr) is not None
    except (OSError, subprocess.TimeoutExpired): return False


def find_java8(explicit=None):
    requested = explicit or os.environ.get('DEAD_EARTH_JAVA8')
    if requested:
        candidate = java_executable(requested).resolve()
        if not is_java8(candidate): raise ValueError('The explicitly selected Java executable is not Java 8')
        return candidate
    candidates = []
    if os.environ.get('JAVA_HOME'): candidates.append(java_executable(os.environ['JAVA_HOME']))
    for variable, suffix in (('APPDATA', 'PrismLauncher/java'), ('LOCALAPPDATA', 'PrismLauncher/java'),
                             ('LOCALAPPDATA', 'Programs/PrismLauncher/java')):
        if os.environ.get(variable):
            folder = Path(os.environ[variable]) / suffix
            if folder.is_dir(): candidates.extend(sorted(folder.rglob('java.exe'))[:32])
    if os.environ.get('PRISM_LAUNCHER_PATH'):
        folder = Path(os.environ['PRISM_LAUNCHER_PATH']).resolve().parent / 'java'
        if folder.is_dir(): candidates.extend(sorted(folder.rglob('java.exe'))[:32])
    on_path = shutil.which('java')
    if on_path: candidates.append(Path(on_path))
    for candidate in candidates:
        if is_java8(candidate): return candidate.resolve()
    raise ValueError('Java 8 was not found. Set DEAD_EARTH_JAVA8 to its java.exe or JRE directory')


def free_port(port):
    with socket.socket() as probe: probe.bind(('127.0.0.1', port))


def stop_requested(path, run_id):
    try:
        value = json.loads(path.read_text(encoding='utf-8'))
        return isinstance(value, dict) and value.get('run_id') == run_id
    except (OSError, ValueError): return False


def server_jar(root):
    jars = sorted(p for p in root.glob('forge-1.12.2-14.23.5.2860*.jar')
                  if not any(word in p.name for word in ('installer', 'sources', 'javadoc')))
    if len(jars) != 1 or not (root / 'minecraft_server.1.12.2.jar').is_file() or not (root / 'libraries').is_dir():
        raise ValueError('Install the pinned Forge 2860 server in this folder first')
    return jars[0]


def stop_python(processes, stop_files):
    for path in stop_files: path.touch(exist_ok=True)
    for process in processes:
        if process.poll() is not None: continue
        try: process.wait(timeout=12)
        except subprocess.TimeoutExpired:
            process.terminate()
            try: process.wait(timeout=10)
            except subprocess.TimeoutExpired:
                process.kill(); process.wait(timeout=10)


def model_ready(director):
    # The descriptor and model pins stay backend-only; no proxy or remote endpoint.
    try:
        pin = json.loads((director / 'models.lock.json').read_text(encoding='utf-8'))
        config = tomllib.loads((director / 'director.example.toml').read_text(encoding='utf-8'))['model']
        if config['url'] != 'http://127.0.0.1:11434' or config['model'] != pin['model'] or config['digest'] != pin['manifest_sha256']:
            return False
        class NoRedirect(urllib.request.HTTPRedirectHandler):
            def redirect_request(self, *args, **kwargs): return None
        opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())
        with opener.open('http://127.0.0.1:11434/api/tags', timeout=3) as response:
            data = response.read(1048577)
        if len(data) > 1048576: return False
        tags = json.loads(data)
        return any(row.get('name') == pin['model'] and row.get('digest') == pin['manifest_sha256']
                   for row in tags.get('models', []))
    except (OSError, ValueError, KeyError, TypeError): return False


def prepare_model(root, args):
    director = root / 'director'
    if model_ready(director): return True
    if not args.no_model_start and os.name == 'nt':
        script = director / 'model.ps1'
        if script.is_file():
            with (root / 'logs/legacy-model-startup.log').open('a', encoding='utf-8') as output:
                try:
                    subprocess.run(['powershell.exe', '-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass',
                        '-File', str(script), '-Action', 'start', '-Backend', args.model_backend], cwd=director,
                        stdout=output, stderr=subprocess.STDOUT, creationflags=HIDDEN, timeout=150, check=False)
                except subprocess.TimeoutExpired: output.write('Existing CPU backend startup timed out.\n')
    ready = model_ready(director)
    if not ready:
        if args.require_ai: raise ValueError('Pinned CPU model unavailable; inspect logs/legacy-model-startup.log or run SETUP-AI.bat')
        print('CPU model unavailable: the server will run with bounded automatic fallback. See logs/legacy-model-startup.log.', flush=True)
    return ready


def run(args, root):
    state_dir = runtime_dir(root)
    with exclusive(state_dir / 'supervisor.lock') as acquired:
        if not acquired: raise ValueError('This core-port server already has an active supervisor')
        eula = root / 'eula.txt'
        if not eula.is_file() or not any(line.strip().lower() == 'eula=true'
                for line in eula.read_text(encoding='utf-8-sig').splitlines()):
            raise ValueError('Review the Minecraft EULA and create eula.txt with eula=true yourself')
        config, world, port = properties(root)
        jar = server_jar(root); java = find_java8(args.java)
        free_port(port)
        if not args.no_admin:
            if args.admin_port == port or (config.get('enable-rcon') == 'true' and args.admin_port == int(config.get('rcon.port', '25588'))):
                raise ValueError('Admin, game and RCON ports must differ')
            free_port(args.admin_port)
        director = root / 'director'
        if not args.no_ai or not args.no_admin:
            if not (director / 'dead_earth_director/host.py').is_file(): raise ValueError('The packaged Python director is missing')
        logs = root / 'logs'; logs.mkdir(exist_ok=True)
        run_id = str(uuid.uuid4())
        receipt_path = state_dir / 'receipt.json'
        request_path = state_dir / 'stop.request.json'
        ai_stop, admin_stop = state_dir / 'director.stop', state_dir / 'admin.stop'
        for path in (ai_stop, admin_stop): path.unlink(missing_ok=True)
        receipt = dict(schema=1, root=str(root), run_id=run_id, supervisor_pid=os.getpid(),
                       started_at=time.time(), state='starting', version='1.0.4', model_ready=False)
        def status(state, **values):
            receipt.update(state=state, **values); atomic_json(receipt_path, receipt)
        descriptor = world / 'dead_earth_bridge.json'
        previous = descriptor.stat().st_mtime_ns if descriptor.exists() else None
        status('preparing_environment')
        if not args.no_ai:
            receipt['model_ready'] = prepare_model(root, args)
        status('starting')
        ready = threading.Event()
        children, handles = [], []
        server = None
        try:
            server = subprocess.Popen([str(java), '-Xms1G', '-Xmx' + str(args.memory_mb) + 'M',
                '-jar', str(jar), 'nogui'], cwd=root, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT, text=True, encoding='utf-8', errors='replace',
                creationflags=HIDDEN)
            status('loading_world', server_pid=server.pid)
            def pump():
                with (logs / 'legacy-server-console.log').open('a', encoding='utf-8') as output:
                    for line in server.stdout:
                        output.write(line); output.flush()
                        if 'Done (' in line and 'For help' in line: ready.set()
            threading.Thread(target=pump, name='legacy-console', daemon=True).start()
            deadline = time.monotonic() + args.startup_timeout
            while True:
                if stop_requested(request_path, run_id): break
                if server.poll() is not None: raise ValueError('Forge exited during startup; inspect logs/legacy-server-console.log')
                try: fresh = descriptor.stat().st_mtime_ns != previous
                except OSError: fresh = False
                if ready.is_set() and (fresh or (args.no_ai and args.no_admin)): break
                if time.monotonic() > deadline: raise ValueError('Forge/bridge startup timed out; inspect the local server log')
                time.sleep(.25)
            if not stop_requested(request_path, run_id):
                runtime = director / 'runtime'; runtime.mkdir(exist_ok=True)
                env = os.environ.copy(); env['PYTHONDONTWRITEBYTECODE'] = '1'
                if not args.no_ai:
                    output = (logs / 'legacy-director.log').open('a', encoding='utf-8'); handles.append(output)
                    host = subprocess.Popen([sys.executable, '-u', '-m', 'dead_earth_director.host',
                        '--mode', 'live', '--bridge', str(descriptor), '--config', str(director / 'director.example.toml'),
                        '--journal', str(runtime / 'decisions.sqlite3'), '--stop-file', str(ai_stop)],
                        cwd=director, env=env, stdout=output, stderr=subprocess.STDOUT, creationflags=HIDDEN)
                    children.append(host); receipt['director_pid'] = host.pid
                if not args.no_admin:
                    output = (logs / 'legacy-admin.log').open('a', encoding='utf-8'); handles.append(output)
                    admin = subprocess.Popen([sys.executable, '-u', '-m', 'dead_earth_director.admin',
                        '--server-root', str(root), '--catalog', str(root / 'config/dead-earth/director/catalog.json'),
                        '--port', str(args.admin_port), '--stop-file', str(admin_stop)], cwd=director, env=env,
                        stdout=output, stderr=subprocess.STDOUT, creationflags=HIDDEN)
                    children.append(admin); receipt['admin_pid'] = admin.pid
                status('ready', address='127.0.0.1:' + str(port), admin_port=None if args.no_admin else args.admin_port)
                print('DEAD EARTH SRP server ready: 127.0.0.1:' + str(port), flush=True)
                if not args.no_admin: print('Admin: http://127.0.0.1:' + str(args.admin_port), flush=True)
                last_children = None
                while server.poll() is None and not stop_requested(request_path, run_id):
                    exits = {str(child.pid): child.poll() for child in children}
                    if exits != last_children:
                        status('ready' if all(code is None for code in exits.values()) else 'external_service_exited', child_exits=exits)
                        last_children = exits
                    time.sleep(.5)
        except KeyboardInterrupt:
            print('Stopping this server through its owned stdin...', flush=True)
        except Exception:
            status('error', reason='startup_or_supervision_failed')
            raise
        finally:
            stop_python(children, (ai_stop, admin_stop))
            if server is not None and server.poll() is None:
                status('stopping')
                try:
                    server.stdin.write('save-all flush\nstop\n'); server.stdin.flush()
                except (OSError, ValueError):
                    status('stop_attention', reason='owned_stdin_unavailable')
                    print('Owned server stdin is unavailable; stop this server from its local operator console.', flush=True)
                deadline = time.monotonic() + 120
                while server.poll() is None:
                    if time.monotonic() > deadline and receipt['state'] != 'stop_attention':
                        status('stop_attention', reason='waiting_for_graceful_server_exit')
                        print('Still waiting for graceful Forge shutdown; no Java process will be killed.', flush=True)
                    try: server.wait(timeout=.5)
                    except subprocess.TimeoutExpired: pass
                    except KeyboardInterrupt:
                        print('This supervisor is retaining ownership while Forge shuts down.', flush=True)
            for handle in handles: handle.close()
            if server is not None:
                status('stopped', server_exit=server.returncode, stopped_at=time.time())
            else: status('stopped', server_exit=None, stopped_at=time.time())
    return 0 if server is not None and server.returncode == 0 else 2


def request_stop(args, root):
    state_dir = runtime_dir(root)
    with exclusive(state_dir / 'supervisor.lock') as acquired:
        if acquired:
            print('No active supervisor owns this folder. No process was signalled.'); return 1
    receipt_path = state_dir / 'receipt.json'
    receipt = json.loads(receipt_path.read_text(encoding='utf-8'))
    if receipt.get('schema') != 1 or receipt.get('root') != str(root): raise ValueError('Invalid ownership receipt')
    run_id = str(uuid.UUID(receipt['run_id']))
    atomic_json(state_dir / 'stop.request.json', {'run_id': run_id, 'requested_at': time.time()})
    print('Graceful stop requested for this owned run.', flush=True)
    deadline = time.monotonic() + args.timeout
    while time.monotonic() < deadline:
        current = json.loads(receipt_path.read_text(encoding='utf-8'))
        if current.get('run_id') != run_id: raise ValueError('Ownership changed while waiting; no new run was stopped')
        if current.get('state') == 'stopped': print('Owned server stopped.'); return 0
        time.sleep(.5)
    print('Stop remains pending; inspect the supervisor and local log. No PID was killed.'); return 2


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    actions = parser.add_subparsers(dest='action', required=True)
    start = actions.add_parser('start')
    start.add_argument('--root', type=Path, required=True)
    start.add_argument('--java', help='Explicit Java 8 java.exe or JRE home')
    start.add_argument('--memory-mb', type=int, default=4096)
    start.add_argument('--admin-port', type=int, default=8766)
    start.add_argument('--startup-timeout', type=int, default=600)
    start.add_argument('--no-ai', action='store_true')
    start.add_argument('--no-model-start', action='store_true', help='Use an existing CPU backend only')
    start.add_argument('--require-ai', action='store_true', help='Refuse startup if the pinned CPU model is unavailable')
    start.add_argument('--model-backend', choices=('auto','wsl','windows'), default='auto')
    start.add_argument('--no-admin', action='store_true')
    stop = actions.add_parser('stop')
    stop.add_argument('--root', type=Path, required=True); stop.add_argument('--timeout', type=int, default=150)
    view = actions.add_parser('status'); view.add_argument('--root', type=Path, required=True)
    args = parser.parse_args(argv)
    try:
        root = owned_root(args.root)
        if args.action == 'start':
            if not 1024 <= args.memory_mb <= 32768 or not 1 <= args.admin_port <= 65535 or not 30 <= args.startup_timeout <= 1800:
                raise ValueError('Invalid memory, admin port or startup timeout')
            return run(args, root)
        if args.action == 'stop':
            if not 1 <= args.timeout <= 600: raise ValueError('Invalid stop timeout')
            return request_stop(args, root)
        state_dir = runtime_dir(root)
        with exclusive(state_dir / 'supervisor.lock') as acquired:
            value = json.loads((state_dir / 'receipt.json').read_text(encoding='utf-8')) if (state_dir / 'receipt.json').exists() else {}
            print(json.dumps({'supervisor_active': not acquired, 'receipt': value}, indent=2))
        return 0
    except (OSError, ValueError, KeyError) as error:
        print('Legacy server: ' + str(error), file=sys.stderr); return 2


if __name__ == '__main__':
    if sys.version_info < (3, 11): raise SystemExit('Python 3.11 or newer is required')
    raise SystemExit(main())
