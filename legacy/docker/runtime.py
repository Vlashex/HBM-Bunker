"""Small container entrypoints; models never receive process or console tools."""
import argparse
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
        temporary.write_text(json.dumps(value, allow_nan=False) + "\n", encoding="utf-8")
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
        receipt = dict(schema=1, run_id=str(uuid.uuid4()), state="starting",
                       port=25565, started_at=time.time())
        receipt_path = state_dir / "minecraft.json"
        atomic_json(receipt_path, receipt)
        process = subprocess.Popen(["java", "-Xms1G", "-Xmx" + memory + "M",
                                    "-jar", FORGE, "nogui"], cwd=root,
                                   stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                   stderr=subprocess.STDOUT, text=True,
                                   encoding="utf-8", errors="replace", bufsize=1)
        receipt.update(pid=process.pid, state="running")
        atomic_json(receipt_path, receipt)

        def copy_log():
            path = root / "logs/server-console.log"
            path.parent.mkdir(parents=True, exist_ok=True)
            with path.open("a", encoding="utf-8") as output:
                for line in process.stdout:
                    output.write(line)
                    output.flush()
                    print(line.rstrip("\n"), flush=True)
        reader = threading.Thread(target=copy_log, daemon=True)
        reader.start()
        sent_stop = False
        try:
            while process.poll() is None:
                if request_stop.is_set() and not sent_stop:
                    sent_stop = True
                    receipt["state"] = "stopping"
                    atomic_json(receipt_path, receipt)
                    # stdin is private to this entrypoint, never model-accessible.
                    try:
                        process.stdin.write("save-all flush\nstop\n")
                        process.stdin.flush()
                    except (BrokenPipeError, OSError):
                        pass
                time.sleep(.2)
            reader.join(timeout=3)
            receipt.update(state="stopped" if sent_stop else "exited",
                           exit_code=process.returncode, stopped_at=time.time())
            atomic_json(receipt_path, receipt)
            return process.returncode
        finally:
            if process.stdin:
                process.stdin.close()


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
