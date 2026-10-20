"""Prepare and operate the SRP Docker Desktop stack without deleting data.

Preparation copies a stopped, owned fork into a new directory. It never moves
or edits the Windows server, worlds, existing Docker volumes or model weights.
"""
import argparse
from contextlib import contextmanager
import json
import os
from pathlib import Path
import re
import secrets
import shutil
import socket
import subprocess
import sys
import tomllib
import uuid

ROOT = Path(__file__).resolve().parents[1]
DOCKER_DIR = ROOT / "legacy/docker"
FORGE = "forge-1.12.2-14.23.5.2860.jar"
SAFE_MODEL_VOLUME = re.compile(r"[a-z0-9][a-z0-9_.-]{0,127}\Z")
PROPERTY_UPDATES = {"server-ip": "", "server-port": "25565", "enable-rcon": "true",
                    "rcon.port": "25575", "broadcast-rcon-to-ops": "false"}
COPY_DIRECTORIES = ("mods", "config", "scripts", "libraries", "resources", "structures", "vintagefix")
COPY_FILES = (FORGE, "minecraft_server.1.12.2.jar", "eula.txt", "legacy-port.json",
              "forge-runtime.json", "full.mods.lock.json", "combat.mods.lock.json",
              "storage.mods.lock.json", "mod-map.json", "ops.json", "whitelist.json",
              "banned-ips.json", "banned-players.json", "usercache.json", "usernamecache.json")


def read_properties(path):
    result = {}
    for raw in path.read_text(encoding="utf-8-sig").splitlines():
        line = raw.strip()
        if not line or line.startswith(("#", "!")):
            continue
        key, separator, value = line.partition("=")
        key, value = key.strip(), value.strip()
        if not separator or key in result or "\\" in key or value.endswith("\\"):
            raise ValueError("Ambiguous server.properties")
        result[key] = value
    return result


def inside(root, relative):
    candidate = root / relative
    if candidate.is_symlink() or candidate == root or not candidate.resolve().is_relative_to(root.resolve()):
        raise ValueError("A server input escapes its owned directory")
    return candidate


def reject_links(path):
    if path.is_symlink() or getattr(path, "is_junction", lambda: False)():
        raise ValueError("Linked server inputs are not copied")
    if path.is_dir():
        for parent, directories, files in os.walk(path, followlinks=False):
            for name in directories + files:
                child = Path(parent) / name
                if child.is_symlink() or getattr(child, "is_junction", lambda: False)():
                    raise ValueError("Linked server inputs are not copied")


def copy_input(source, target):
    reject_links(source)
    target.parent.mkdir(parents=True, exist_ok=True)
    if source.is_dir():
        shutil.copytree(source, target)
    else:
        shutil.copy2(source, target)


@contextmanager
def stopped_source(source, values):
    receipt = source / "runtime/legacy-server/receipt.json"
    if receipt.exists():
        current = json.loads(receipt.read_text(encoding="utf-8"))
        if current.get("state") not in ("stopped", "exited"):
            raise ValueError("Stop the Windows server before copying its world")
    port = int(values.get("server-port", "25565"))
    if not 1 <= port <= 65535:
        raise ValueError("Invalid source game port")
    probe = socket.socket()
    try:
        probe.settimeout(.5)
        if probe.connect_ex(("127.0.0.1", port)) == 0:
            raise ValueError("Source game port is active; stop the Windows server first")
    finally:
        probe.close()
    # Use the same supervisor lock as the native launcher, held for the copy.
    # A server cannot restart halfway through the world/database snapshot.
    lock_path = source / "runtime/legacy-server/supervisor.lock"
    lock_path.parent.mkdir(parents=True, exist_ok=True)
    handle = lock_path.open("a+b")
    locked = False
    try:
        handle.seek(0, 2)
        if handle.tell() == 0:
            handle.write(b"0")
            handle.flush()
        handle.seek(0)
        if os.name == "nt":
            import msvcrt
            try:
                msvcrt.locking(handle.fileno(), msvcrt.LK_NBLCK, 1)
            except OSError:
                raise ValueError("The Windows supervisor still owns this server") from None
        else:
            import fcntl
            try:
                fcntl.flock(handle.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
            except OSError:
                raise ValueError("The native supervisor still owns this server") from None
        locked = True
        yield
    finally:
        if locked:
            handle.seek(0)
            if os.name == "nt":
                msvcrt.locking(handle.fileno(), msvcrt.LK_UNLCK, 1)
            else:
                fcntl.flock(handle.fileno(), fcntl.LOCK_UN)
        handle.close()


def container_config(source):
    config = source / "director/director.example.toml"
    text = config.read_text(encoding="utf-8")
    parsed = tomllib.loads(text)
    pin = json.loads((source / "director/models.lock.json").read_text(encoding="utf-8"))
    primary = parsed["model"]
    if primary["model"] != pin["model"] or primary["digest"] != pin["manifest_sha256"]:
        raise ValueError("Source CPU model differs from its pinned lock")
    models = [primary] + ([parsed["critic"]] if "critic" in parsed else [])
    if any(model["url"] != "http://127.0.0.1:11434" for model in models):
        raise ValueError("Source must use the pinned local CPU backend")
    text, replacements = re.subn(r'(?m)^url\s*=\s*"http://127\.0\.0\.1:11434"\s*$',
                                 'url = "http://ollama:11434"', text)
    if replacements != len(models):
        raise ValueError("Unsupported model endpoint formatting")
    return text


def prepare(source, target, settings_dir=DOCKER_DIR, model_volume="dead-earth-srp-models"):
    source = Path(source).absolute()
    if source.resolve() != source or source.is_symlink():
        raise ValueError("Use the real dedicated SRP server path")
    marker = json.loads((source / "legacy-port.json").read_text(encoding="utf-8"))
    if (marker.get("schema") != 1 or marker.get("pack") != "DEAD-EARTH-SRP"
            or marker.get("kind") != "full-fork"
            or marker.get("runtime") != "forge-1.12.2-14.23.5.2860"):
        raise ValueError("Source is not a full SRP fork server")
    if not SAFE_MODEL_VOLUME.fullmatch(model_volume):
        raise ValueError("Invalid destination model volume name")
    target = Path(target).absolute()
    settings_dir = Path(settings_dir).resolve()
    if (target.resolve() != target or target.exists() or target.is_relative_to(source)
            or source.is_relative_to(target)):
        raise ValueError("Choose a new empty server-data path outside the source server")
    if (settings_dir / ".env").exists():
        raise ValueError("Compose .env already exists; preserve it and choose settings deliberately")
    values = read_properties(source / "server.properties")
    world_name = values.get("level-name", "world")
    if not world_name or ":" in world_name or "\\" in world_name:
        raise ValueError("Unsafe source level-name")
    world = inside(source, world_name)
    if not (world / "level.dat").is_file():
        raise ValueError("The selected current world is not initialized")
    if not (source / FORGE).is_file() or not (source / "minecraft_server.1.12.2.jar").is_file():
        raise ValueError("Pinned Forge server runtime is missing")
    if not any(line.strip().lower() == "eula=true"
               for line in (source / "eula.txt").read_text(encoding="utf-8-sig").splitlines()):
        raise ValueError("The source Minecraft EULA has not been accepted")
    config = container_config(source)
    with stopped_source(source, values):
        target.mkdir(parents=True, exist_ok=False)
        for name in COPY_FILES:
            item = inside(source, name)
            if item.is_file():
                copy_input(item, target / name)
        for name in COPY_DIRECTORIES:
            item = inside(source, name)
            if item.is_dir():
                copy_input(item, target / name)
        copy_input(world, target / world_name)
        for relative in ("director/models.lock.json", "director/runtime"):
            item = inside(source, relative)
            if item.exists():
                copy_input(item, target / relative)
        (target / "director").mkdir(exist_ok=True)
        (target / "director/director.example.toml").write_text(config, encoding="utf-8", newline="\n")
        updates = dict(PROPERTY_UPDATES, **{"rcon.password": secrets.token_urlsafe(32)})
        lines = []
        for raw in (source / "server.properties").read_text(encoding="utf-8-sig").splitlines():
            key = raw.partition("=")[0].strip()
            lines.append(key + "=" + updates.pop(key) if key in updates else raw)
        lines.extend(key + "=" + value for key, value in updates.items())
        (target / "server.properties").write_text("\n".join(lines) + "\n", encoding="utf-8", newline="\n")
        (target / "compose-server.json").write_text(json.dumps(dict(
            schema=1, pack="DEAD-EARTH-SRP", network="shared-loopback",
            deployment_id=str(uuid.uuid4()), source_version=marker["version"],
            world_name=world_name), indent=2) + "\n", encoding="utf-8")
        (target / "runtime/compose").mkdir(parents=True, exist_ok=True)
        settings_dir.mkdir(parents=True, exist_ok=True)
        with (settings_dir / ".env").open("x", encoding="utf-8", newline="\n") as output:
            output.write("DEAD_EARTH_SERVER_DATA=" + target.as_posix() + "\n"
                         "DEAD_EARTH_MODEL_VOLUME=" + model_volume + "\n"
                         "DEAD_EARTH_MINECRAFT_MEMORY_MB=4096\n"
                         "DEAD_EARTH_MINECRAFT_CONTAINER_MEMORY=5g\n")
    return dict(state="prepared", source_untouched=True, world_copied=True,
                data_directory=str(target), models_imported=False,
                secrets_file_ready=(settings_dir / "secrets.env").is_file())


def local_settings(directory):
    result = {}
    for line in (directory / ".env").read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if line and not line.startswith("#"):
            key, separator, value = line.partition("=")
            if not separator or key in result:
                raise ValueError("Invalid local Compose .env")
            result[key] = value
    required = {"DEAD_EARTH_SERVER_DATA", "DEAD_EARTH_MODEL_VOLUME"}
    if not required <= set(result) or not SAFE_MODEL_VOLUME.fullmatch(result["DEAD_EARTH_MODEL_VOLUME"]):
        raise ValueError("Run prepare first")
    return result


def compose_command(action, directory=DOCKER_DIR):
    directory = Path(directory).resolve()
    local_settings(directory)
    common = ["docker", "--context", "desktop-linux", "compose", "--project-name", "dead-earth-srp",
              "--env-file", str(directory / ".env"), "-f", str(directory / "compose.yaml")]
    actions = {"check": ["config", "--quiet"], "up": ["up", "-d", "--build", "--wait", "--wait-timeout", "240"],
               "stop": ["down"], "status": ["ps", "--format", "json"],
               "build": ["build"]}
    if action not in actions:
        raise ValueError("Unsupported Compose action")
    if action == "up":
        secrets_file = directory / "secrets.env"
        if not secrets_file.is_file():
            raise ValueError("Put the existing Playit key into local secrets.env first")
        values = read_properties(secrets_file)
        if set(values) != {"SECRET_KEY"} or not values["SECRET_KEY"]:
            raise ValueError("The local Playit key is not configured")
    # No shell interpolation, socket mount, volume deletion, pull or remote context.
    return common + actions[action]


def offline_import_command(source, directory=DOCKER_DIR):
    settings = local_settings(Path(directory))
    source = Path(source).resolve(strict=True)
    data = Path(settings["DEAD_EARTH_SERVER_DATA"]).resolve(strict=True)
    if not source.is_dir() or any("," in str(path) for path in (source, data)):
        raise ValueError("Use a plain local model-export directory")
    return ["docker", "--context", "desktop-linux", "run", "--rm", "--network", "none",
            "--read-only", "--cap-drop", "ALL", "--security-opt", "no-new-privileges:true",
            "--mount", "type=bind,source=" + str(source) + ",target=/source,readonly",
            "--mount", "type=bind,source=" + str(data) + ",target=/srv/dead-earth,readonly",
            "--mount", "type=volume,source=" + settings["DEAD_EARTH_MODEL_VOLUME"] + ",target=/models",
            "--entrypoint", "python3", "dead-earth-srp-director:local",
            "/opt/dead-earth/model_store.py", "--source", "/source", "--config",
            "/srv/dead-earth/director/director.example.toml"]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="action", required=True)
    init = commands.add_parser("prepare")
    init.add_argument("--server-root", type=Path, required=True)
    init.add_argument("--data-dir", type=Path, default=ROOT / "work/compose-server")
    init.add_argument("--model-volume", default="dead-earth-srp-models")
    init.add_argument("--settings-dir", type=Path, default=DOCKER_DIR)
    for action in ("check", "up", "stop", "status", "build"):
        commands.add_parser(action)
    models = commands.add_parser("import-models")
    models.add_argument("--source", type=Path, required=True,
                        help="Offline .ollama or models export; existing source is read-only")
    args = parser.parse_args()
    try:
        if args.action == "prepare":
            print(json.dumps(prepare(args.server_root, args.data_dir, args.settings_dir, args.model_volume)))
            return 0
        command = offline_import_command(args.source) if args.action == "import-models" else compose_command(args.action)
        return subprocess.run(command, cwd=DOCKER_DIR, creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0)).returncode
    except (OSError, ValueError, KeyError, TypeError, json.JSONDecodeError) as error:
        # Paths may be included in preparation errors, never file/secret contents.
        print("Dead Earth Compose: " + (str(error) if isinstance(error, ValueError) else "operation unavailable"), file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
