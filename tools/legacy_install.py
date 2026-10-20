"""Plan, then explicitly apply a narrow update to owned, stopped SRP installations.

No downloads, launch/stop commands, world edits, or live-account configuration.
Only the feature files changed since 1fd5e5b are installed. All replacements and
the previous verified coordinator JAR are retained in a fresh work/ backup.
"""
import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import socket
import subprocess
import uuid

try:
    from . import legacy_port
except ImportError:
    import legacy_port

ROOT = Path(__file__).resolve().parents[1]
BASELINE_JARS = {"dead-earth-srp-coordinator-1.0.4.jar":
                 "2eea81fc81a2250def8f7f1016b140a650e97e8da99048caebe3e37798779396"}
FEATURE_FILES = frozenset({
    "config/ProjectE/custom_emc.json", "config/dead-earth/coordinator.json",
    "config/dead-earth/farming.json", "config/dead-earth/progression.json",
    "config/esm/entity_ai.json", "config/esm/epicsiegemod.cfg",
    "config/bonsaitrees/settings.cfg", "config/dead-earth/materials.json",
    "config/dead-earth/relay-siege.json", "config/dead-earth/siege.json",
    "config/mysticalagriculture.cfg", "scripts/dead_earth_bonsai.zs",
    "scripts/dead_earth_materials.zs", "scripts/dead_earth_farming.zs",
})
RECEIPT = "legacy-install-receipt.json"


def digest(data):
    return hashlib.sha256(data).hexdigest()


def encode(value):
    return (json.dumps(value, ensure_ascii=False, sort_keys=True, indent=2,
                       allow_nan=False) + "\n").encode("utf-8")


def decode(data):
    def unique(pairs):
        result = {}
        for key, value in pairs:
            if key in result:
                raise ValueError("Duplicate JSON key: " + key)
            result[key] = value
        return result
    return json.loads(data.decode("utf-8-sig"), object_pairs_hook=unique,
                      parse_constant=lambda _: (_ for _ in ()).throw(ValueError("Nonfinite JSON")))


def child(base, relative):
    """Check every existing ancestor, including Windows junctions, before reads/writes."""
    name = PurePosixPath(str(relative).replace("\\", "/"))
    if name.is_absolute() or not name.parts or ".." in name.parts or ":" in str(name):
        raise ValueError("Unsafe installation relative path")
    base = Path(base).absolute()
    path = base.joinpath(*name.parts)
    for current in [path, *path.parents]:
        if current.is_symlink() or getattr(current, "is_junction", lambda: False)():
            raise ValueError("Linked installation path")
        if current == base:
            break
    if path.resolve() == base.resolve() or not path.resolve().is_relative_to(base.resolve()):
        raise ValueError("Installation path escapes its owned directory")
    return path


def owned(path, root):
    work = Path(root).absolute() / "work"
    path = Path(path).absolute()
    try:
        relative = path.relative_to(work)
    except ValueError:
        raise ValueError("Installations must be inside this repository's work/") from None
    checked = child(work, relative.as_posix())
    if not checked.is_dir():
        raise ValueError("Owned installation directory is missing")
    return checked


def read(path):
    if not path.is_file() or path.stat().st_size > 16 * 1024 * 1024:
        raise ValueError("Missing or oversized installation JSON: " + path.name)
    return decode(path.read_bytes())


def marker(path, kind):
    data = read(child(path, "compose-server.json" if kind == "server" else "interactive-client.json"))
    if data.get("schema") != 1:
        raise ValueError("Unknown installation marker")
    if kind == "server":
        if data.get("pack") != "DEAD-EARTH-SRP" or data.get("network") != "shared-loopback":
            raise ValueError("Not an owned Compose server")
        runtime = read(child(path, "legacy-port.json"))
        if (runtime.get("pack") != "DEAD-EARTH-SRP" or runtime.get("kind") != "full-fork"
                or runtime.get("runtime") != "forge-1.12.2-14.23.5.2860"):
            raise ValueError("Wrong owned server runtime")
    elif data.get("kind") != "dead-earth-interactive-client":
        raise ValueError("Not an owned interactive client")
    return data


def merge_json(existing, canonical, mode):
    current, source = decode(existing), decode(canonical)
    if not isinstance(current, dict) or not isinstance(source, dict):
        raise ValueError("Expected configuration object")
    if mode == "coordinator":
        result = dict(source, **current)
        result["resourcePrices"] = source["resourcePrices"]
    else:
        if set(current) - set(source):
            raise ValueError("Unknown farming keys; preserve and review before installing")
        result = dict(source, **current)
    return encode(result)


def merge_ma_seeds(existing, canonical):
    """Merge only the seven generated force-seed properties, preserving other Forge categories."""
    canonical = canonical.replace("\r\n", "\n")
    wanted = dict(re.findall(r"(?m)^\s*I:([a-z_]+_seeds)\s*=\s*(2)\s*$", canonical))
    if len(wanted) != 7 or canonical.count("{") != 1 or canonical.count("}") != 1:
        raise ValueError("Unexpected generated MA force-seed configuration")
    lines = existing.splitlines(keepends=True)
    depth, start, finish = 0, None, None
    for index, line in enumerate(lines):
        content = line.split("#", 1)[0].strip()
        if re.fullmatch(r'(?i)(?:"seeds"|seeds)\s*\{', content):
            if start is not None or depth:
                raise ValueError("Duplicate or nested MA seeds category")
            start = index
        depth += content.count("{") - content.count("}")
        if depth < 0:
            raise ValueError("Unbalanced Forge configuration")
        if start is not None and finish is None and index > start and depth == 0:
            finish = index
    if depth or (start is not None and finish is None):
        raise ValueError("Unbalanced MA seeds category")
    newline = "\r\n" if "\r\n" in existing else "\n"
    if start is None:
        return existing.rstrip("\r\n") + newline + newline + canonical.strip().replace("\n", newline) + newline
    found = set()
    for index in range(start + 1, finish):
        match = re.match(r'^(\s*)I:"?([a-z_]+_seeds)"?\s*=\s*[-+]?\d+\s*(#.*)?(?:\r?\n)?$', lines[index])
        if match and match[2] in wanted:
            if match[2] in found:
                raise ValueError("Duplicate MA force-seed property")
            found.add(match[2])
            comment = match[3].rstrip("\r\n") if match[3] else ""
            lines[index] = match[1] + "I:" + match[2] + "=2" + (" " + comment if comment else "") + newline
    missing = ["    I:" + name + "=2" + newline for name in wanted if name not in found]
    lines[finish:finish] = missing
    return "".join(lines)


def _source_file(name, side):
    if side == "client":
        return name.startswith(".minecraft/mods/") or name.removeprefix(".minecraft/") in FEATURE_FILES
    if name.startswith("mods/") or name in FEATURE_FILES:
        return True
    if name.startswith("director/dead_earth_director/"):
        return Path(name).suffix in {".py", ".js", ".css", ".html"}
    if name.startswith("legacy/docker/"):
        return "/" not in name.removeprefix("legacy/docker/") and not name.endswith(("/.env", "/secrets.env"))
    return (name in {"tools/legacy_compose.py", "tools/legacy_server.py", "START-SERVER.bat",
                     "STOP-SERVER.bat", "SETUP-AI.bat", "README-PORT.md", "README-EARLY-STORAGE.md",
                     "combat.mods.lock.json", "full.mods.lock.json", "storage.mods.lock.json",
                     "mod-map.json", "legacy-port.json", "forge-runtime.json", "server.properties.example"}
            or name in {"director/models.lock.json", "director/requirements.in",
                        "director/requirements-windows-py312.lock", "director/model.ps1", "director/docker-backend.ps1",
                        "director/docker/compose.cpu.yaml"})


def _build(server=None, client=None, connect_server=None, *, root=ROOT):
    root = Path(root).absolute()
    if server is None and client is None:
        raise ValueError("Select a Compose server and/or one interactive client")
    if server is not None and connect_server is not None and Path(server).absolute() != Path(connect_server).absolute():
        raise ValueError("Client connection must use the selected Compose data")
    connection = owned(server or connect_server, root) if server or connect_server else None
    if client and connection is None:
        raise ValueError("Specify the owned Compose server for the client connection")
    if connection:
        marker(connection, "server")
    targets, operations, removals, payloads = [], [], [], {}
    source_hash = digest(legacy_port.compiled_jar(root))
    for side, argument in (("server", server), ("client", client)):
        if argument is None:
            continue
        target = owned(argument, root)
        current_marker = marker(target, side)
        base = target if side == "server" else child(target, "game")
        if not base.is_dir():
            raise ValueError("Client game directory is missing")
        current_receipt = child(target, RECEIPT)
        old_managed = read(current_receipt).get("managedHashes", {}) if current_receipt.exists() else {}
        contents = legacy_port.entries(side, root=root)
        updated = {}
        for source_name, data in contents.items():
            if not _source_file(source_name, side):
                continue
            name = source_name.removeprefix(".minecraft/") if side == "client" else source_name
            destination = child(base, name)
            if name == "config/mysticalagriculture.cfg" and destination.exists():
                data = merge_ma_seeds(destination.read_text(encoding="utf-8-sig"), data.decode("utf-8")).encode("utf-8")
            elif name in {"config/dead-earth/coordinator.json", "config/dead-earth/farming.json"} and destination.exists():
                data = merge_json(destination.read_bytes(), data, "coordinator" if "coordinator" in name else "farming")
            updated[name] = data
        for name in updated:
            if name.startswith("mods/"):
                destination = child(base, name)
                if destination.exists() and digest(destination.read_bytes()) != digest(updated[name]):
                    previous = digest(destination.read_bytes())
                    if old_managed.get(name) != previous:
                        raise ValueError("Existing pinned mod differs without an installation receipt: " + destination.name)
        old_jars = []
        for path in child(base, "mods").glob("dead-earth-srp-coordinator-*.jar"):
            safe = child(base, "mods/" + path.name)
            name = "mods/" + safe.name
            if name in updated:
                continue
            actual = digest(safe.read_bytes())
            if BASELINE_JARS.get(safe.name) != actual and old_managed.get(name) != actual:
                raise ValueError("Unrecognized previous coordinator JAR: " + safe.name)
            old_jars.append(name)
            removals.append(dict(target=side, path=name, sha256=actual))
        if side == "client":
            old_hashes = current_marker.get("inputHashes", {})
            hashes = {}
            for filename, expected in old_hashes.items():
                path = Path(filename).absolute()
                if not path.is_relative_to(root):
                    raise ValueError("Client input hash escapes the repository")
                path = child(root, path.relative_to(root).as_posix())
                if not path.is_file() or digest(path.read_bytes()) != expected:
                    raise ValueError("Client runtime input changed: " + path.name)
                if path.parent != child(base, "mods"):
                    hashes[str(path)] = expected
            for path in child(base, "mods").glob("*.jar"):
                name = "mods/" + path.name
                if name in old_jars or name in updated:
                    continue
                path = child(base, name)
                actual = digest(path.read_bytes())
                if old_hashes.get(str(path)) != actual:
                    raise ValueError("Unreceipted extra client mod: " + path.name)
                hashes[str(path)] = actual
            hashes.update({str(child(base, name)): digest(data) for name, data in updated.items() if name.startswith("mods/")})
            current_marker.update(inputHashes=hashes, serverDirectory=str(connection), serverPort=25565,
                                  packVersion=legacy_port.VERSION)
            payloads[(side, "interactive-client.json")] = encode(current_marker)
        else:
            current_marker["source_version"] = legacy_port.VERSION
            payloads[(side, "compose-server.json")] = encode(current_marker)
        for name, data in updated.items():
            relative = name if side == "server" else "game/" + name
            payloads[(side, relative)] = data
        targets.append(dict(kind=side, directory=str(target)))
    for (side, relative), data in sorted(payloads.items()):
        target = Path(next(t["directory"] for t in targets if t["kind"] == side))
        destination = child(target, relative)
        before = digest(destination.read_bytes()) if destination.exists() else None
        if before != digest(data):
            operations.append(dict(target=side, path=relative, before=before, after=digest(data), bytes=len(data)))
    # Removals were relative to game/, whereas operation paths are relative to marker roots.
    for removal in removals:
        if removal["target"] == "client":
            removal["path"] = "game/" + removal["path"]
    result = dict(schema=1, version=legacy_port.VERSION, coordinatorSha256=source_hash,
                  targets=targets, connectServer=str(connection) if connection else None,
                  writes=operations, archivePreviousJars=removals)
    result["planId"] = digest(encode(result))
    return result, payloads


def plan(server=None, client=None, connect_server=None, *, root=ROOT):
    """Read-only dry plan. It is valid even while applications are running."""
    return _build(server, client, connect_server, root=root)[0]


def _pid_running(pid):
    if type(pid) is not int or pid <= 0:
        raise ValueError("Invalid client process receipt")
    if os.name == "nt":
        import ctypes
        from ctypes import wintypes
        api = ctypes.WinDLL("kernel32", use_last_error=True)
        api.OpenProcess.argtypes = [wintypes.DWORD, wintypes.BOOL, wintypes.DWORD]
        api.OpenProcess.restype = wintypes.HANDLE
        api.GetExitCodeProcess.argtypes = [wintypes.HANDLE, ctypes.POINTER(wintypes.DWORD)]
        api.CloseHandle.argtypes = [wintypes.HANDLE]
        handle = api.OpenProcess(0x1000, False, pid)
        if not handle:
            if ctypes.get_last_error() == 87:  # ERROR_INVALID_PARAMETER: PID no longer exists.
                return False
            raise ValueError("Cannot verify client process exit")
        try:
            code = wintypes.DWORD()
            if not api.GetExitCodeProcess(handle, ctypes.byref(code)):
                raise ValueError("Cannot verify client process exit")
            return code.value == 259
        finally:
            api.CloseHandle(handle)
    try:
        os.kill(pid, 0)
        return True
    except ProcessLookupError:
        return False


def _mount_key(value):
    value = str(value).replace("\\", "/").rstrip("/").casefold()
    for prefix in ("/run/desktop/mnt/host/", "/host_mnt/"):
        if value.startswith(prefix) and len(value) > len(prefix) + 1 and value[len(prefix) + 1] == "/":
            value = value[len(prefix)] + ":" + value[len(prefix) + 1:]
    return value


def assert_stopped(prepared):
    """Only inspect processes/sockets/Docker; never stop or kill anything."""
    for target in prepared["targets"]:
        directory = Path(target["directory"])
        if target["kind"] == "client":
            process = child(directory, "interactive-client-process.json")
            if process.exists() and _pid_running(read(process).get("pid")):
                raise ValueError("Close the selected Minecraft client before applying")
            continue
        for relative in ("runtime/compose/minecraft.json", "runtime/legacy-server/receipt.json"):
            receipt = child(directory, relative)
            if receipt.exists() and read(receipt).get("state") not in {"stopped", "exited"}:
                raise ValueError("The server receipt is not stopped: " + relative)
        with socket.socket() as probe:
            probe.settimeout(.5)
            if probe.connect_ex(("127.0.0.1", 25565)) == 0:
                raise ValueError("Minecraft port is active; stop the server before applying")
        result = subprocess.run(["docker", "--context", "desktop-linux", "ps", "-q"], capture_output=True, text=True, timeout=20,
                                creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
        if result.returncode:
            raise ValueError("Cannot verify stopped Docker containers")
        ids = result.stdout.split()
        if ids:
            result = subprocess.run(["docker", "--context", "desktop-linux", "inspect", *ids], capture_output=True, text=True, timeout=20,
                                    creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
            if result.returncode:
                raise ValueError("Cannot inspect Docker mounts")
            key = _mount_key(directory)
            for container in json.loads(result.stdout):
                for mount in container.get("Mounts", []):
                    source = _mount_key(mount.get("Source", ""))
                    if source and (key == source or key.startswith(source + "/")):
                        raise ValueError("A running container still mounts the selected server data")


def apply(prepared, *, root=ROOT):
    """Rebuild and verify the reviewed plan, then apply it with a fresh complete file backup."""
    selected = {item["kind"]: item["directory"] for item in prepared["targets"]}
    fresh, payloads = _build(selected.get("server"), selected.get("client"),
                             None if selected.get("server") else prepared.get("connectServer"), root=root)
    if fresh != prepared:
        raise ValueError("Plan/source/installation changed; review a fresh dry plan")
    assert_stopped(fresh)
    work = Path(root).absolute() / "work"
    backup = child(work, "legacy-update-backup-" + datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S%fZ") + "-" + uuid.uuid4().hex[:8])
    backup.mkdir(exist_ok=False)
    locations = {item["kind"]: owned(item["directory"], root) for item in fresh["targets"]}
    staging = child(backup, ".staging")
    staging.mkdir()
    # Stage every new byte and copy every replaced byte before the first installation write.
    for index, operation in enumerate(fresh["writes"]):
        destination = child(locations[operation["target"]], operation["path"])
        staged = child(staging, str(index))
        staged.write_bytes(payloads[(operation["target"], operation["path"])])
        if operation["before"] is not None:
            saved = child(backup, operation["target"] + "/" + operation["path"])
            saved.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(destination, saved)
    (backup / "plan.json").write_bytes(encode(fresh))
    # Check again immediately before changing mods/configs. No world files are ever touched.
    assert_stopped(fresh)
    for index, operation in enumerate(fresh["writes"]):
        destination = child(locations[operation["target"]], operation["path"])
        before = digest(destination.read_bytes()) if destination.exists() else None
        if before != operation["before"]:
            raise ValueError("Installation changed during staging: " + operation["path"])
        destination.parent.mkdir(parents=True, exist_ok=True)
        child(staging, str(index)).replace(destination)
    for operation in fresh["archivePreviousJars"]:
        source = child(locations[operation["target"]], operation["path"])
        if digest(source.read_bytes()) != operation["sha256"]:
            raise ValueError("Previous JAR changed during installation")
        saved = child(backup, operation["target"] + "/" + operation["path"])
        saved.parent.mkdir(parents=True, exist_ok=True)
        source.replace(saved)
    for kind, directory in locations.items():
        managed = {name.removeprefix("game/") if kind == "client" else name: digest(data)
                   for (side, name), data in payloads.items() if side == kind and
                   (name.startswith("mods/") if kind == "server" else name.startswith("game/mods/"))}
        previous = child(directory, RECEIPT)
        if previous.exists():
            saved = child(backup, kind + "/" + RECEIPT)
            saved.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(previous, saved)
        previous.write_bytes(encode(dict(schema=1, version=legacy_port.VERSION, planId=fresh["planId"],
                                        backup=str(backup), managedHashes=managed)))
    result = dict(schema=1, state="installed", version=legacy_port.VERSION,
                  planId=fresh["planId"], backup=str(backup), replacedFiles=len(fresh["writes"]),
                  archivedJars=len(fresh["archivePreviousJars"]), worldAndPrivateDataUntouched=True)
    (backup / "result.json").write_bytes(encode(result))
    return result


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    actions = parser.add_subparsers(dest="action", required=True)
    prepare = actions.add_parser("plan", help="Read-only dry plan, no installation")
    prepare.add_argument("--server-data", type=Path)
    prepare.add_argument("--client-dir", type=Path)
    prepare.add_argument("--connect-server", type=Path, help="Compose data for client-only updates")
    install = actions.add_parser("apply", help="Explicitly apply a previously saved dry-plan JSON")
    install.add_argument("--plan", type=Path, required=True)
    args = parser.parse_args(argv)
    try:
        result = plan(args.server_data, args.client_dir, args.connect_server) if args.action == "plan" else apply(read(args.plan))
        print(json.dumps(result, indent=2, ensure_ascii=False))
        return 0
    except (ValueError, OSError, KeyError, subprocess.SubprocessError) as error:
        parser.exit(1, "Update refused: " + str(error) + "\n")


if __name__ == "__main__":
    raise SystemExit(main())
