"""Small container entrypoints; models never receive process or console tools."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import signal
import socket
import subprocess
import sys
import threading
import time
import uuid

ROOT = Path("/srv/dead-earth")
STATE = Path("runtime/compose")
FORGE = "forge-1.12.2-14.23.5.2860.jar"


def atomic_json(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(path.name + "." + uuid.uuid4().hex + ".tmp")
    try:
        with temporary.open('x', encoding='utf-8') as output:
            output.write(json.dumps(value, allow_nan=False) + "\n")
            output.flush()
            os.fsync(output.fileno())
        temporary.replace(path)
    finally:
        temporary.unlink(missing_ok=True)


def properties(root):
    result = {}
    for raw in (root / "server.properties").read_text(encoding="utf-8-sig").splitlines():
        line = raw.strip()
        if not line or line.startswith(("#", "!")):
            continue
        key, separator, value = line.partition("=")
        key, value = key.strip(), value.strip()
        if not separator or key in result or "\\" in key or value.endswith("\\"):
            raise ValueError("ambiguous_server_properties")
        result[key] = value
    return result


def world_directory(root, values):
    name = values.get("level-name", "world")
    if not name or "\\" in name or ":" in name:
        raise ValueError("invalid_world_name")
    world = (root / name).resolve()
    if world == root.resolve() or not world.is_relative_to(root.resolve()):
        raise ValueError("invalid_world_name")
    return world


def deployment(root):
    value = json.loads((root / "compose-server.json").read_text(encoding="utf-8"))
    marker = json.loads((root / "legacy-port.json").read_text(encoding="utf-8"))
    if (type(value) is not dict or value.get("schema") != 1
            or value.get("pack") != "DEAD-EARTH-SRP" or value.get("network") != "shared-loopback"
            or marker.get("pack") != "DEAD-EARTH-SRP"
            or marker.get("runtime") != "forge-1.12.2-14.23.5.2860"
            or marker.get("kind") != "full-fork"):
        raise ValueError("unprepared_server_volume")
    values = properties(root)
    if (values.get("server-ip") != "" or values.get("server-port") != "25565"
            or values.get("enable-rcon") != "true"
            or values.get("rcon.port") != "25575"
            or not re.fullmatch(r"[A-Za-z0-9_-]{32,128}", values.get("rcon.password", ""))):
        raise ValueError("invalid_container_server_network")
    world_directory(root, values)
    return values


CONFIG_FILES = {key: 'config/dead-earth/' + key + '.json' for key in (
    'coordinator', 'siege', 'relay-siege', 'climate', 'farming', 'machinery',
    'defense', 'tools', 'sieving', 'worldgen', 'progression', 'srp-entities')}
CONFIG_FILES['catalog-rules'] = 'config/dead-earth/director/catalog.json'


def fixed_path(root, path):
    root = root.resolve()
    if path == root or not path.is_relative_to(root):
        raise ValueError('invalid_config_restart')
    for item in (path, *path.parents):
        if item == root:
            break
        if item.is_symlink() or (hasattr(item, 'is_junction') and item.is_junction()):
            raise ValueError('invalid_config_restart')
    if not path.resolve().is_relative_to(root):
        raise ValueError('invalid_config_restart')
    return path


def bounded_json(root, path, maximum=8192):
    fixed_path(root, path)
    with path.open('rb') as source:
        raw = source.read(maximum + 1)
    if len(raw) > maximum:
        raise ValueError('invalid_config_restart')
    def pairs(rows):
        result = {}
        for key, value in rows:
            if key in result:
                raise ValueError('invalid_config_restart')
            result[key] = value
        return result
    def constant(_):
        raise ValueError('invalid_config_restart')
    try:
        return json.loads(raw.decode('utf-8'), object_pairs_hook=pairs, parse_constant=constant)
    except (UnicodeError, RecursionError, ValueError):
        raise ValueError('invalid_config_restart') from None


def config_uuid(value):
    try:
        return type(value) is str and str(uuid.UUID(value)) == value
    except ValueError:
        return False


def config_hash(value):
    return type(value) is str and re.fullmatch(r'[a-f0-9]{64}', value) is not None


class JavaLaunchFailure(Exception):
    """A launcher failed before creating Java; later I/O errors are different."""


class ConfigRestart:
    """Only fixed owned config IDs can be restored; markers carry no paths."""
    def __init__(self, root):
        self.root = Path(root).resolve()
        self.directory = self.root / STATE
        self.marker_path = self.directory / 'config-restart-request.json'
        self.state_path = self.directory / 'config-restart-state.json'
        self.marker = None
        self.manifest = None
        self.rollback = False
        self.fallback_attempted = False

    def load(self):
        fixed_path(self.root, self.marker_path)
        if not self.marker_path.exists():
            return False
        marker = bounded_json(self.root, self.marker_path)
        if (type(marker) is not dict or set(marker) != {'schema', 'requestId', 'worldEpoch', 'revision'}
                or type(marker['schema']) is not int or marker['schema'] != 1
                or not config_uuid(marker['requestId']) or not config_uuid(marker['worldEpoch'])
                or not config_hash(marker['revision'])):
            raise ValueError('invalid_config_restart')
        manifest = bounded_json(self.root, self.directory / 'config-restart-backup.json')
        if (type(manifest) is not dict or set(manifest) != set(marker) | {'oldRevision', 'files'}
                or any(manifest.get(k) != v for k, v in marker.items())
                or not config_hash(manifest['oldRevision']) or type(manifest['files']) is not list
                or not 1 <= len(manifest['files']) <= len(CONFIG_FILES)):
            raise ValueError('invalid_config_restart')
        seen = set()
        for row in manifest['files']:
            if (type(row) is not dict or set(row) != {'id', 'sha256'} or type(row['id']) is not str
                    or row['id'] not in CONFIG_FILES or row['id'] in seen or not config_hash(row['sha256'])):
                raise ValueError('invalid_config_restart')
            seen.add(row['id'])
        previous_marker = self.marker
        self.marker, self.manifest = marker, manifest
        self.backups()  # Verify every file before queueing a stop or restoring anything.
        if previous_marker != marker:
            self.rollback = False
            self.fallback_attempted = False
        return True

    def backups(self):
        result = []
        for row in self.manifest['files']:
            key = row['id']
            path = fixed_path(self.root, self.directory / 'config-backups' / self.marker['requestId'] / (key + '.json'))
            with path.open('rb') as source:
                raw = source.read(1048577)
            if len(raw) > 1048576 or hashlib.sha256(raw).hexdigest() != row['sha256']:
                raise ValueError('invalid_config_restart')
            destination = fixed_path(self.root, self.root / CONFIG_FILES[key])
            result.append((destination, raw))
        return result

    def previous(self):
        try:
            value = bounded_json(self.root, self.state_path, 4096)
            if type(value) is dict and all(value.get(k) == v for k, v in self.marker.items()):
                return value
        except (OSError, ValueError):
            pass
        return None

    def publish(self, state, code=None):
        fixed_path(self.root, self.state_path)
        value = dict(self.marker, state=state, rollback=self.rollback, fallbackAttempted=self.fallback_attempted)
        if code:
            value['code'] = code
        atomic_json(self.state_path, value)

    def consume(self):
        if bounded_json(self.root, self.marker_path) != self.marker:
            raise ValueError('invalid_config_restart')
        self.marker_path.unlink()

    def restore(self):
        rows = self.backups()
        for destination, raw in rows:
            temporary = destination.with_name(destination.name + '.' + uuid.uuid4().hex + '.tmp')
            try:
                with temporary.open('xb') as output:
                    output.write(raw); output.flush(); os.fsync(output.fileno())
                temporary.replace(destination)
            finally:
                temporary.unlink(missing_ok=True)


class MinecraftSupervisor:
    """Keep Minecraft in its network namespace while a human config restart runs."""
    def __init__(self, root, memory, stop, launcher=None, probe=None, pause=time.sleep, clock=time.monotonic):
        self.root = Path(root).resolve(); self.memory = memory; self.stop = stop
        self.launcher = launcher or self.launch
        self.probe = probe or self.ready
        self.pause = pause; self.clock = clock
        self.receipt_path = self.root / STATE / 'minecraft.json'
        self.receipt = dict(schema=1, run_id=str(uuid.uuid4()), state='starting', port=25565, started_at=time.time())

    def launch(self):
        return subprocess.Popen(['java', '-Xms1G', '-Xmx' + self.memory + 'M', '-jar', FORGE, 'nogui'],
            cwd=self.root, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
            text=True, encoding='utf-8', errors='replace', bufsize=1)

    def ready(self, epoch, revision):
        # RCON starts after world startup. A log line or listening game socket is
        # insufficient: require the native active revision and world scope.
        from dead_earth_director.admin_console import rcon_command, settings
        from dead_earth_director.bridge import _json
        try:
            state, port, password = settings(self.root)
            if state != 'configured':
                return False
            raw = rcon_command(port, password, 'deadearth config view')
            raw = re.sub(r'\u00a7[0-9A-FK-ORa-fk-or]', '', raw).strip()
            if raw.startswith('[DeadEarth] '):
                raw = raw[len('[DeadEarth] '):]
            value = _json(raw)
            return (type(value) is dict and type(value.get('schema')) is int and value['schema'] == 1
                    and value.get('status') == 'ready' and value.get('worldEpoch') == epoch and value.get('revision') == revision)
        except Exception:
            # Connection/auth/old protocol errors carry no user-visible data.
            return False

    def copy_log(self, process):
        path = self.root / 'logs/server-console.log'
        path.parent.mkdir(parents=True, exist_ok=True)
        with path.open('a', encoding='utf-8') as output:
            for line in process.stdout:
                output.write(line); output.flush()
                print(line.rstrip('\n'), flush=True)

    def start(self):
        self.receipt.update(state='starting', started_at=time.time())
        atomic_json(self.receipt_path, self.receipt)
        try:
            process = self.launcher()
        except OSError:
            raise JavaLaunchFailure() from None
        self.receipt.update(pid=process.pid, state='running')
        atomic_json(self.receipt_path, self.receipt)
        reader = threading.Thread(target=self.copy_log, args=(process,), daemon=True)
        reader.start()
        return process, reader

    def send_stop(self, process):
        self.receipt['state'] = 'stopping'; atomic_json(self.receipt_path, self.receipt)
        try:
            process.stdin.write('save-all flush\nstop\n'); process.stdin.flush()
        except (BrokenPipeError, OSError):
            pass

    def start_configuration(self, restart):
        try:
            return self.start()
        except JavaLaunchFailure:
            if restart.rollback:
                raise ValueError('rollback_startup_failed') from None
        # Failure to create Java is a startup failure too. Restore the same
        # bounded backup set before spending the one previous-config attempt.
        restart.rollback = True
        restart.publish('starting', 'restoring_backup')
        try:
            restart.restore()
        except (OSError, ValueError):
            raise ValueError('rollback_failed') from None
        if self.stop.is_set():
            restart.publish('failed', 'shutdown_requested')
            raise ValueError('shutdown_requested')
        restart.fallback_attempted = True
        restart.publish('starting')
        try:
            return self.start()
        except JavaLaunchFailure:
            raise ValueError('rollback_startup_failed') from None

    def halted(self, restart, code):
        restart.publish('failed', code)
        # Compose restarts exited containers. Remain alive after the one fallback
        # fails so it cannot turn a bad config into an endless Java restart loop.
        while not self.stop.is_set():
            self.pause(.2)
        return 2

    def run(self):
        restart = ConfigRestart(self.root)
        active = restart.load()
        phase = 'starting' if active else None
        if active:
            prior = restart.previous()
            restart.rollback = bool(prior and prior.get('rollback') is True)
            restart.fallback_attempted = bool(prior and prior.get('fallbackAttempted') is True)
            requested_shutdown = bool(prior and prior.get('state') == 'failed' and prior.get('code') == 'shutdown_requested')
            if not requested_shutdown and (restart.fallback_attempted or (prior and prior.get('state') == 'failed')):
                return self.halted(restart, 'rollback_startup_failed')
            if restart.rollback:
                try:
                    restart.restore()
                except (OSError, ValueError):
                    return self.halted(restart, 'rollback_failed')
                restart.fallback_attempted = True
            restart.publish('starting')
        if self.stop.is_set():
            if active:
                restart.publish('failed', 'shutdown_requested')
            return 0
        try:
            process, reader = self.start_configuration(restart) if active else self.start()
        except ValueError as error:
            return 0 if self.stop.is_set() else self.halted(restart, str(error))
        deadline = self.clock() + 300; last_probe = -1e20; sent_stop = False; shutdown_recorded = False
        try:
            while True:
                if self.stop.is_set() and not active:
                    try:
                        active = restart.load()
                    except (OSError, ValueError):
                        active = False
                if self.stop.is_set() and active and not shutdown_recorded:
                    restart.publish('failed', 'shutdown_requested'); shutdown_recorded = True
                if self.stop.is_set() and not sent_stop and process.poll() is None:
                    self.send_stop(process); sent_stop = True
                exited = process.poll() is not None
                if exited:
                    reader.join(timeout=3)
                    if process.stdin:
                        process.stdin.close()
                    if self.stop.is_set() or not active:
                        self.receipt.update(state='stopped' if sent_stop else 'exited', exit_code=process.returncode, stopped_at=time.time())
                        atomic_json(self.receipt_path, self.receipt)
                        return process.returncode
                    if phase == 'stopping':
                        phase = 'starting'
                    elif not restart.rollback:
                        restart.rollback = True
                        restart.publish('starting', 'restoring_backup')
                        try:
                            restart.restore()
                        except (OSError, ValueError):
                            return self.halted(restart, 'rollback_failed')
                    else:
                        return self.halted(restart, 'rollback_startup_failed')
                    if self.stop.is_set():
                        restart.publish('failed', 'shutdown_requested')
                        return 0
                    if restart.rollback:
                        restart.fallback_attempted = True
                    restart.publish('starting')
                    try:
                        process, reader = self.start_configuration(restart)
                    except ValueError as error:
                        return 0 if self.stop.is_set() else self.halted(restart, str(error))
                    deadline = self.clock() + 300; last_probe = -1e20; sent_stop = False
                    continue
                if self.stop.is_set():
                    self.pause(.2); continue
                if not active:
                    try:
                        requested = restart.load()
                    except (OSError, ValueError):
                        # A malformed marker cannot interrupt the last good
                        # running server. Never use any of its supplied data.
                        requested = False
                    if requested:
                        active = True; phase = 'stopping'
                        restart.publish('queued'); restart.publish('stopping')
                        self.send_stop(process); sent_stop = True
                elif phase == 'starting':
                    now = self.clock()
                    if now - last_probe >= 2:
                        last_probe = now
                        wanted = restart.manifest['oldRevision'] if restart.rollback else restart.marker['revision']
                        if self.probe(restart.marker['worldEpoch'], wanted) and not self.stop.is_set():
                            restart.publish('failed' if restart.rollback else 'ready', 'rolled_back' if restart.rollback else None)
                            restart.consume(); active = False; phase = None
                    if active and now >= deadline and not sent_stop:
                        self.send_stop(process); sent_stop = True
                self.pause(.2)
        finally:
            if process.stdin and process.poll() is not None:
                process.stdin.close()


def minecraft(root=ROOT):
    import fcntl
    values = deployment(root)
    if not any(line.strip().lower() == "eula=true"
               for line in (root / "eula.txt").read_text(encoding="utf-8-sig").splitlines()):
        raise ValueError("minecraft_eula_not_accepted")
    if not (root / FORGE).is_file() or not (root / "minecraft_server.1.12.2.jar").is_file():
        raise ValueError("pinned_forge_runtime_missing")
    memory = os.environ.get("DEAD_EARTH_MEMORY_MB", "4096")
    if not memory.isdecimal() or not 2048 <= int(memory) <= 16384:
        raise ValueError("invalid_minecraft_memory")
    state_dir = root / STATE
    state_dir.mkdir(parents=True, exist_ok=True)
    # A second stack must never open the same world, including accidental up
    # from a second project name. This lock is independent of the Windows host.
    with (state_dir / "minecraft.lock").open("a+b") as lock:
        try:
            fcntl.flock(lock.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
        except OSError:
            raise ValueError("server_volume_already_running") from None
        request_stop = threading.Event()
        signal.signal(signal.SIGTERM, lambda *_: request_stop.set())
        signal.signal(signal.SIGINT, lambda *_: request_stop.set())
        return MinecraftSupervisor(root, memory, request_stop).run()


def model_pins(root):
    from dead_earth_director.host import load_config
    from dead_earth_director.model import OllamaModel
    settings, _ = load_config(root / "director/director.example.toml")
    model = OllamaModel(settings)
    result = model._request("/api/tags")
    if type(result) is not dict or type(result.get("models")) is not list:
        raise ValueError("model_catalog_unavailable")
    rows = result["models"]
    primary = any(row.get("name") == settings.model and row.get("digest") == settings.digest
                  for row in rows if type(row) is dict)
    critic = settings.critic is not None and any(
        row.get("name") == settings.critic.model and row.get("digest") == settings.critic.digest
        for row in rows if type(row) is dict)
    if not primary:
        raise ValueError("pinned_primary_not_imported")
    # Both weights must exist before enabling dual; no endpoint pulls weights.
    if settings.ai_mode == "dual" and not critic:
        raise ValueError("pinned_critic_not_imported")
    return dict(primary_ready=primary, critic_ready=critic)


def service(role, root=ROOT):
    values = deployment(root)
    state_dir = root / STATE
    state_dir.mkdir(parents=True, exist_ok=True)
    stop_file = state_dir / (role + ".stop")
    # A docker restart is a new explicit process; remove only this service's
    # transient signal from the previous process, never campaign/model state.
    stop_file.unlink(missing_ok=True)
    signal.signal(signal.SIGTERM, lambda *_: stop_file.touch(exist_ok=True))
    signal.signal(signal.SIGINT, lambda *_: stop_file.touch(exist_ok=True))
    bridge = world_directory(root, values) / "dead_earth_bridge.json"
    if role == "director":
        model_pins(root)
        from dead_earth_director import host
        sys.argv = ["host", "--mode", "live", "--bridge", str(bridge), "--config",
                    str(root / "director/director.example.toml"), "--journal",
                    str(root / "director/runtime/decisions.sqlite3"), "--stop-file",
                    str(stop_file)]
        return host.main()
    if role == "admin":
        from dead_earth_director import admin
        sys.argv = ["admin", "--server-root", str(root), "--catalog",
                    str(root / "config/dead-earth/director/catalog.json"),
                    "--port", "8766", "--stop-file", str(stop_file)]
        return admin.main()
    raise ValueError("unknown_container_role")


def health(role, root=ROOT):
    values = deployment(root)
    if role == "minecraft":
        # This graph runs on Jammy apt Python 3.10. Keep host/model/admin
        # imports lazy in their separate Python 3.12 image (host needs tomllib).
        from dead_earth_director.bridge import Descriptor, BridgeClient
        receipt = json.loads((root / STATE / "minecraft.json").read_text(encoding="utf-8"))
        if receipt.get("state") != "running":
            raise ValueError("minecraft_not_running")
        with socket.create_connection(("127.0.0.1", 25565), timeout=1):
            pass
        descriptor = Descriptor.load(world_directory(root, values) / "dead_earth_bridge.json")
        BridgeClient(descriptor).call("get_snapshot", {})
    elif role == "director":
        from dead_earth_director.ai_control import ModeStore
        report = ModeStore(root / "director/runtime/ai-control.sqlite3").host()
        if report is None or report["state"] == "stopped":
            raise ValueError("director_heartbeat_unavailable")
        # A fresh stored ack alone must not hide a dead process for two minutes.
        if time.time() - report["at"] > 110:
            raise ValueError("director_heartbeat_stale")
    elif role == "admin":
        from urllib.request import build_opener, ProxyHandler, Request
        opener = build_opener(ProxyHandler({}))
        with opener.open(Request("http://127.0.0.1:8766/api/health"), timeout=2) as response:
            if response.status != 200:
                raise ValueError("admin_unavailable")
    else:
        raise ValueError("unknown_container_role")
    return 0


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("role", choices=("minecraft", "director", "admin", "health"))
    parser.add_argument("health_role", nargs="?", choices=("minecraft", "director", "admin"))
    args = parser.parse_args()
    try:
        if args.role == "health":
            if args.health_role is None:
                parser.error("health role required")
            return health(args.health_role)
        return minecraft() if args.role == "minecraft" else service(args.role)
    except Exception as error:
        # Do not forward tokens, world descriptors, properties or HTTP response
        # contents into Docker logs. Known error codes are sufficient.
        code = str(error) if isinstance(error, ValueError) else "container_service_unavailable"
        if not re.fullmatch(r"[a-z_]{1,64}", code):
            code = "container_service_unavailable"
        print("Dead Earth: " + code, file=sys.stderr, flush=True)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
