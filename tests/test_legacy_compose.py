"""Contract checks for private networking and nondestructive offline migration."""
import ast
from contextlib import nullcontext
import hashlib
import importlib.util
import json
from pathlib import Path
import socket
import socketserver
import threading
import sys
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    sys.modules[name] = module
    spec.loader.exec_module(module)
    return module


compose = load("legacy_compose_test_target", ROOT / "tools/legacy_compose.py")
store = load("legacy_compose_model_store", ROOT / "legacy/docker/model_store.py")
runtime = load("legacy_compose_runtime", ROOT / "legacy/docker/runtime.py")


def fixture(base):
    source = base / "native"
    source.mkdir()
    marker = dict(schema=1, pack="DEAD-EARTH-SRP", version="test",
                  runtime="forge-1.12.2-14.23.5.2860", kind="full-fork")
    (source / "legacy-port.json").write_text(json.dumps(marker))
    for name in (compose.FORGE, "minecraft_server.1.12.2.jar"):
        (source / name).write_bytes(b"fixture")
    (source / "eula.txt").write_text("eula=true\n")
    with socket.socket() as probe:
        probe.bind(("127.0.0.1", 0))
        port = probe.getsockname()[1]
    (source / "server.properties").write_text(
        "server-ip=127.0.0.1\nserver-port=" + str(port) +
        "\nlevel-name=world-current\nonline-mode=false\nmotd=DEAD EARTH\n")
    for folder in ("mods", "libraries", "config", "world-current", "world-previous", "director"):
        (source / folder).mkdir()
    (source / "mods/coordinator.jar").write_bytes(b"pinned mod")
    (source / "world-current/level.dat").write_bytes(b"current world")
    (source / "world-previous/level.dat").write_bytes(b"preserved previous")
    for name in ("director.example.toml", "models.lock.json"):
        (source / "director" / name).write_bytes((ROOT / "director" / name).read_bytes())
    return source


class ComposeMigrationTests(unittest.TestCase):
    def test_preparation_copies_only_active_world_and_preserves_source_rules_and_pins(self):
        with tempfile.TemporaryDirectory() as tmp:
            base = Path(tmp)
            source = fixture(base)
            original_properties = (source / "server.properties").read_bytes()
            original_world = (source / "world-current/level.dat").read_bytes()
            target, settings = base / "container", base / "settings"
            result = compose.prepare(source, target, settings)
            self.assertTrue(result["world_copied"])
            self.assertFalse(result["models_imported"])
            self.assertEqual((source / "server.properties").read_bytes(), original_properties)
            self.assertEqual((source / "world-current/level.dat").read_bytes(), original_world)
            self.assertEqual((target / "world-current/level.dat").read_bytes(), original_world)
            self.assertFalse((target / "world-previous").exists())
            self.assertTrue((source / "world-previous/level.dat").is_file())
            values = compose.read_properties(target / "server.properties")
            self.assertEqual(values["server-ip"], "")
            self.assertEqual(values["server-port"], "25565")
            self.assertEqual(values["rcon.port"], "25575")
            self.assertGreaterEqual(len(values["rcon.password"]), 32)
            self.assertEqual(values["online-mode"], "false")
            self.assertEqual(runtime.deployment(target)["level-name"], "world-current")
            old = compose.tomllib.loads((source / "director/director.example.toml").read_text())
            new = compose.tomllib.loads((target / "director/director.example.toml").read_text())
            for role in ("model", "critic"):
                self.assertEqual(new[role]["url"], "http://ollama:11434")
                self.assertEqual(new[role]["model"], old[role]["model"])
                self.assertEqual(new[role]["digest"], old[role]["digest"])
            self.assertNotIn(values["rcon.password"], json.dumps(result))

    def test_refuses_overwrite_nested_destinations_and_nonfork_sources(self):
        with tempfile.TemporaryDirectory() as tmp:
            base = Path(tmp)
            source = fixture(base)
            for target in (source, source / "nested"):
                with self.subTest(target=target), self.assertRaises(ValueError):
                    compose.prepare(source, target, base / "settings")
            (base / "target").mkdir()
            with self.assertRaises(ValueError):
                compose.prepare(source, base / "target", base / "settings")
            marker = json.loads((source / "legacy-port.json").read_text())
            marker["pack"] = "OTHER"
            (source / "legacy-port.json").write_text(json.dumps(marker))
            with self.assertRaises(ValueError):
                compose.prepare(source, base / "target-new", base / "settings")
            self.assertFalse((base / "target-new").exists())

    def test_active_source_is_rejected_before_any_world_copy(self):
        with tempfile.TemporaryDirectory() as tmp, socket.socket() as listener:
            base = Path(tmp)
            source = fixture(base)
            listener.bind(("127.0.0.1", 0))
            listener.listen()
            port = listener.getsockname()[1]
            path = source / "server.properties"
            values = path.read_text().splitlines()
            path.write_text("\n".join("server-port=" + str(port) if line.startswith("server-port=")
                                     else line for line in values) + "\n")
            with self.assertRaises(ValueError):
                compose.prepare(source, base / "target", base / "settings")
            self.assertFalse((base / "target").exists())

    def test_compose_commands_are_desktop_only_and_never_delete_volumes(self):
        with tempfile.TemporaryDirectory() as tmp:
            directory = Path(tmp)
            (directory / ".env").write_text(
                "DEAD_EARTH_SERVER_DATA=C:/private/data\nDEAD_EARTH_MODEL_VOLUME=dead-earth-srp-models\n")
            for action in ("build", "check", "status", "stop"):
                command = compose.compose_command(action, directory)
                self.assertEqual(command[:3], ["docker", "--context", "desktop-linux"])
                self.assertNotIn("--volumes", command)
                self.assertNotIn("-v", command)
                self.assertNotIn("host", command)
                self.assertNotIn("pull", command)
            self.assertIn("--quiet", compose.compose_command("check", directory))
            with self.assertRaises(ValueError):
                compose.compose_command("up", directory)
            (directory / "secrets.env").write_text("SECRET_KEY=private-fixture\n")
            command = compose.compose_command("up", directory)
            self.assertIn("--wait", command)
            self.assertNotIn("private-fixture", " ".join(command))

    def test_world_and_container_network_policy_fail_closed(self):
        with tempfile.TemporaryDirectory() as tmp:
            base = Path(tmp)
            source = fixture(base)
            target = base / "container"
            compose.prepare(source, target, base / "settings")
            for world_name in ("../outside", ".", "C:/windows", r"..\outside"):
                with self.subTest(world_name=world_name), self.assertRaises(ValueError):
                    runtime.world_directory(target, {"level-name": world_name})
            properties = target / "server.properties"
            properties.write_text(properties.read_text().replace("server-ip=\n", "server-ip=192.168.1.1\n"))
            with self.assertRaises(ValueError):
                runtime.deployment(target)


class MinecraftHealthCompatibilityTests(unittest.TestCase):
    def test_java_image_health_does_not_import_python_311_director_graph(self):
        # Jammy's apt Python is 3.10; director/admin use a separate 3.12 image.
        # Parse the complete startup/health import graph with the 3.10 grammar.
        paths = (ROOT / "legacy/docker/runtime.py",
                 ROOT / "director/dead_earth_director/__init__.py",
                 ROOT / "director/dead_earth_director/bridge.py")
        for path in paths:
            ast.parse(path.read_text(encoding="utf-8"), filename=str(path), feature_version=(3, 10))
        campaign = "00000000-0000-4000-8000-000000000001"
        epoch = "00000000-0000-4000-8000-000000000002"
        requests = []
        class Handler(socketserver.StreamRequestHandler):
            def handle(self):
                request = json.loads(self.rfile.readline(8194))
                requests.append(request)
                response = dict(protocol=1, campaign_id=campaign, world_epoch=epoch,
                                ok=True, result=dict(snapshot={}, cache_age_millis=0))
                self.wfile.write(json.dumps(response).encode() + b"\n")
        with tempfile.TemporaryDirectory() as tmp, socketserver.TCPServer(("127.0.0.1", 0), Handler) as bridge:
            base = Path(tmp)
            source = fixture(base)
            target = base / "container"
            compose.prepare(source, target, base / "settings")
            runtime.atomic_json(target / "runtime/compose/minecraft.json", dict(state="running"))
            descriptor = dict(protocol=1, host="127.0.0.1", port=bridge.server_address[1],
                              token="a" * 64, campaign_id=campaign, world_epoch=epoch)
            (target / "world-current/dead_earth_bridge.json").write_text(json.dumps(descriptor))
            worker = threading.Thread(target=bridge.serve_forever, daemon=True)
            worker.start()
            original_connect = socket.create_connection
            def connect(address, **kwargs):
                # No game server is started. The protocol bridge is a real TCP
                # fixture; only the unrelated game's open-port probe is mocked.
                return nullcontext() if address == ("127.0.0.1", 25565) else original_connect(address, **kwargs)
            try:
                with patch.object(sys, "path", [str(ROOT / "director")] + sys.path):
                    # A hidden import of a Python-3.11-only dependency or the
                    # inference/admin graph must make this health fixture fail.
                    with patch.dict(sys.modules, {"tomllib": None,
                            "dead_earth_director.host": None,
                            "dead_earth_director.model": None,
                            "dead_earth_director.ai_control": None}):
                        with patch.object(socket, "create_connection", connect):
                            self.assertEqual(runtime.health("minecraft", target), 0)
                self.assertEqual(len(requests), 1)
                self.assertEqual(requests[0]["operation"], "get_snapshot")
                self.assertEqual(requests[0]["campaign_id"], campaign)
                self.assertEqual(requests[0]["world_epoch"], epoch)
                self.assertEqual(requests[0]["token"], "a" * 64)
            finally:
                bridge.shutdown()
                worker.join()



class ComposeNetworkTests(unittest.TestCase):
    def test_all_backend_services_are_one_private_stack_without_host_network_or_docker_socket(self):
        spec = json.loads((ROOT / "legacy/docker/compose.yaml").read_text())
        services = spec["services"]
        self.assertEqual(set(services), {"minecraft", "director", "admin", "ollama", "playit"})
        self.assertEqual(services["minecraft"]["ports"],
                         ["127.0.0.1:25565:25565", "127.0.0.1:8766:8766"])
        for name, service in services.items():
            self.assertNotEqual(service.get("network_mode"), "host")
            self.assertFalse(service.get("privileged", False))
            self.assertNotIn("docker.sock", json.dumps(service))
            self.assertNotIn("gpus", service)
            if name != "minecraft":
                self.assertNotIn("ports", service)
        for name in ("director", "admin", "playit"):
            self.assertEqual(services[name]["network_mode"], "service:minecraft")
            self.assertEqual(services[name]["depends_on"]["minecraft"]["condition"], "service_healthy")
        self.assertFalse(spec["networks"]["campaign"].get("internal", False))
        ollama = services["ollama"]
        self.assertEqual(ollama["environment"]["CUDA_VISIBLE_DEVICES"], "-1")
        self.assertEqual(ollama["environment"]["OLLAMA_MAX_LOADED_MODELS"], "1")
        self.assertEqual(ollama["environment"]["OLLAMA_KEEP_ALIVE"], "0")
        self.assertEqual(ollama["mem_limit"], "6g")
        self.assertIn("@sha256:", ollama["image"])
        self.assertEqual(services["playit"]["env_file"][0]["path"], "secrets.env")


class OfflineModelImportTests(unittest.TestCase):
    def model_source(self, base):
        source = base / "export/models"
        (source / "blobs").mkdir(parents=True)
        blob = b"tiny synthetic fixture, not model weights"
        digest = hashlib.sha256(blob).hexdigest()
        (source / "blobs" / ("sha256-" + digest)).write_bytes(blob)
        manifest = json.dumps(dict(schemaVersion=2, config=dict(digest="sha256:" + digest, size=len(blob)),
                                   layers=[dict(digest="sha256:" + digest, size=len(blob))])).encode()
        pins = []
        for name in ("primary:4b", "critic:2b"):
            path = source / store.manifest_name(name)
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(manifest)
            pins.append((name, hashlib.sha256(manifest).hexdigest()))
        return source, pins, digest

    def test_offline_import_validates_exact_manifests_and_reuses_identical_blobs(self):
        with tempfile.TemporaryDirectory() as tmp:
            base = Path(tmp)
            source, pins, digest = self.model_source(base)
            target = base / "volume"
            result = store.import_models(source, target, pins)
            self.assertEqual(result["pinned_models"], 2)
            self.assertEqual(result["files_validated"], 5)
            self.assertEqual(result["files_written"], 5)
            self.assertEqual(store.import_models(source, target, pins)["files_written"], 0)
            self.assertEqual(store.sha256(target / "models/blobs" / ("sha256-" + digest)), digest)

    def test_manifest_is_published_only_after_its_blobs(self):
        with tempfile.TemporaryDirectory() as tmp:
            base = Path(tmp)
            source, pins, _ = self.model_source(base)
            writes = []
            original = store.shutil.copyfileobj
            def copy(input_file, output_file, **kwargs):
                writes.append(Path(output_file.name).relative_to(base / "target/models").parts[0])
                return original(input_file, output_file, **kwargs)
            with patch.object(store.shutil, "copyfileobj", copy):
                store.import_models(source, base / "target", pins)
            self.assertEqual(writes, ["blobs", "manifests", "manifests-v2", "manifests", "manifests-v2"])

    def test_native_downgrade_anchor_restores_exact_runnable_pin_and_critic_alias(self):
        with tempfile.TemporaryDirectory() as tmp:
            base = Path(tmp)
            source, pins, _ = self.model_source(base)
            origin = source / store.manifest_name(pins[0][0])
            value = json.loads(origin.read_bytes())
            value.update(mediaType="application/vnd.docker.distribution.manifest.v2+json",
                         runner="llamacpp", format="gguf")
            # Exact native runnable bytes are retained, never reserialized on import.
            runnable = json.dumps(value, indent=2).encode() + b"\n"
            digest = hashlib.sha256(runnable).hexdigest()
            (source / "blobs" / ("sha256-" + digest)).write_bytes(runnable)
            anchor = dict(value, layers=value["layers"] + [dict(
                mediaType="application/vnd.docker.distribution.manifest.v2+json",
                digest="sha256:" + digest, size=len(runnable))])
            wrapped = json.dumps(anchor).encode()
            origin.write_bytes(wrapped)
            self.assertNotEqual(hashlib.sha256(wrapped).hexdigest(), digest)
            # Alias is bound by its configured full digest; no name-based
            # quantization guesses or raw copied WSL symlinks are needed.
            alias = "dead-earth-critic:2b-q4"
            target = base / "target"
            report = store.import_models(source, target, [(alias, digest)])
            for relative in (store.manifest_name(alias), store.v2_manifest_name(alias)):
                output = target / "models" / relative
                self.assertEqual(output.read_bytes(), runnable)
                self.assertEqual(store.sha256(output), digest)
                self.assertFalse(output.is_symlink())
            self.assertEqual(origin.read_bytes(), wrapped)
            self.assertEqual((source / "blobs" / ("sha256-" + digest)).read_bytes(), runnable)
            self.assertEqual(report["identity"], "exact_runnable_sha256")
            self.assertFalse((source / store.manifest_name(alias)).exists())

    def test_wrong_wrapper_without_exact_pinned_blob_is_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            base = Path(tmp)
            source, pins, _ = self.model_source(base)
            source_manifest = source / store.manifest_name(pins[0][0])
            source_manifest.write_bytes(source_manifest.read_bytes() + b" ")
            with self.assertRaisesRegex(ValueError, "pinned_runnable_manifest_missing"):
                store.import_models(source, base / "target", [pins[0]])
            self.assertFalse((base / "target").exists())

    def test_runnable_pin_cannot_be_replaced_by_a_manifest_list(self):
        with tempfile.TemporaryDirectory() as tmp:
            base = Path(tmp)
            source, _, _ = self.model_source(base)
            data = json.dumps(dict(schemaVersion=2,
                mediaType="application/vnd.ollama.manifest.list.v2+json",
                manifests=[])).encode()
            digest = hashlib.sha256(data).hexdigest()
            (source / "blobs" / ("sha256-" + digest)).write_bytes(data)
            with self.assertRaisesRegex(ValueError, "pinned_manifest_not_runnable"):
                store.import_models(source, base / "target", [("primary:4b", digest)])
            self.assertFalse((base / "target").exists())

    def test_corrupt_source_is_rejected_before_import_and_existing_conflict_is_preserved(self):
        with tempfile.TemporaryDirectory() as tmp:
            base = Path(tmp)
            source, pins, digest = self.model_source(base)
            target = base / "volume"
            blob = source / "blobs" / ("sha256-" + digest)
            original = blob.read_bytes()
            blob.write_bytes(b"corrupt")
            with self.assertRaises(ValueError):
                store.import_models(source, target, pins)
            self.assertFalse(target.exists())
            blob.write_bytes(original)
            conflict = target / "models/blobs" / blob.name
            conflict.parent.mkdir(parents=True)
            conflict.write_bytes(b"preserve this existing store")
            with self.assertRaises(ValueError):
                store.import_models(source, target, pins)
            self.assertEqual(conflict.read_bytes(), b"preserve this existing store")
            self.assertFalse((target / "models" / store.manifest_name("primary:4b")).exists())


if __name__ == "__main__":
    unittest.main()
