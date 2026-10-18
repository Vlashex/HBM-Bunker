"""Tests of the release tooling, independent of a Minecraft installation."""
import hashlib
import importlib.util
import json
from pathlib import Path
import posixpath
import re
import tempfile
import unittest
import zipfile

ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location("pack_release", ROOT / "tools/release.py")
release = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(release)


class ReleaseTests(unittest.TestCase):
    def test_server_shortcut_assets_survive_packaging(self):
        entries=release.launcher_entries(ROOT,True)
        for name in ('start-dead-earth.bat','stop-dead-earth.bat','tools/install-server-shortcuts.ps1',
                     'launcher-icons/server-start.ico','launcher-icons/server-stop.ico'):
            self.assertIn(name,entries)
        for name in ('launcher-icons/server-start.ico','launcher-icons/server-stop.ico'):
            self.assertEqual(entries[name][:4],b'\x00\x00\x01\x00')
            self.assertGreaterEqual(int.from_bytes(entries[name][4:6],'little'),4)
        self.assertFalse(any(name.endswith(('.lnk','.env','.sqlite3')) for name in entries))
        self.assertEqual(release.launcher_entries(ROOT,False),{})

    def test_admin_html_scripts_and_styles_are_included_in_release(self):
        entries=release.director_entries(ROOT,True)
        html=entries['director/dead_earth_director/admin_web/index.html'].decode('utf-8')
        for name in re.findall(r'(?:src|href)="/([^"?#]+\.(?:js|css))"',html):
            self.assertTrue('director/dead_earth_director/admin_web/'+name in entries,
                          'Admin references an asset omitted from the release: '+name)

    def test_client_only_mods_are_excluded_from_server_selection(self):
        self.assertTrue(hasattr(release, "mods_for_side"), "Side-aware mod selection missing")
        common = self.row("common.jar")
        client = dict(self.row("renderer.jar"), side="client")
        server = dict(self.row("server.jar"), side="server")
        self.assertEqual(release.mods_for_side([common, client, server], "server"), [common, server])
        self.assertEqual(release.mods_for_side([common, client, server], "client"), [common, client])
        with self.assertRaises(ValueError):
            release.mods_for_side([dict(client, side="unknown")], "server")

    def test_shaderpack_export_preserves_zip_and_rejects_changed_download(self):
        self.assertTrue(hasattr(release, "shader_entries"), "Pinned shader export missing")
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / "metadata").mkdir()
            data = release.zip_bytes({"shaders/shaders.properties": b"profile.low=!DOF\n", "shaders/final.fsh": b"shader"})
            row = self.row("test-shader.zip", data)
            row["side"] = "client"
            (root / "metadata/shaders.lock.json").write_text(json.dumps([row]))
            cache = root / "cache"
            cache.mkdir()
            (cache / row["filename"]).write_bytes(data)
            entries = release.shader_entries(root, cache)
            self.assertEqual(entries[".minecraft/shaderpacks/test-shader.zip"], data)
            (cache / row["filename"]).write_bytes(b"modified")
            with self.assertRaises(ValueError):
                release.shader_entries(root, cache)

    def test_shaderpack_pins_reject_unsafe_names_and_non_shader_zip(self):
        self.assertTrue(hasattr(release, "shader_entries"), "Pinned shader export missing")
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / "metadata").mkdir()
            cache = root / "cache"
            cache.mkdir()
            data = release.zip_bytes({"accounts.json": b"unexpected"})
            row = self.row("test.zip", data)
            (cache / "test.zip").write_bytes(data)
            for filename in ("test.zip", "../escape.zip", "C:/escape.zip"):
                (root / "metadata/shaders.lock.json").write_text(json.dumps([dict(row, filename=filename)]))
                with self.assertRaises(ValueError):
                    release.shader_entries(root, cache)

    def test_director_package_excludes_runtime_credentials_and_tests(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            files = {"start-admin.bat": b"launcher", "director/dead_earth_director/__init__.py": b"", "director/dead_earth_director/__main__.py": b"print('MCP')",
                     "director/requirements.in": b"mcp==2.3.0", "director/requirements-windows-py312.lock": b"pinned",
                     "director/dead_earth_director/admin_web/index.html": b"admin",
                     "director/dead_earth_director/admin_web/admin.css": b"style",
                     "director/dead_earth_director/admin_web/admin.js": b"script",
                     "director/dead_earth_director/admin_web/authoring.css": b"authoring style",
                     "director/dead_earth_director/admin_web/authoring.js": b"authoring script",
                     "director/dead_earth_director/admin_web/ai-mode.js": b"mode controls",
                     "director/dead_earth_director/admin_web/evolution.js": b"evolution controls",
                     "director/dead_earth_director/admin_web/evolution.css": b"evolution styles",
                     "director/dead_earth_director/admin_web/private.env": b"secret",
                     "director/admin.ps1": b"launcher", "director/README.md": b"setup", "director/setup.ps1": b"setup",
                     "director/director.example.toml": b"config", "director/models.lock.json": b"pin",
                     "director/model.ps1": b"model", "director/docker/compose.cpu.yaml": b"cpu",
                     "director/runtime/decisions.sqlite3": b"private",
                     "director/.venv/private.py": b"secret", "director/tests/test.py": b"fixture",
                     "director/dead_earth_director/token.env": b"secret", "director/dead_earth_bridge.json": b"secret",
                     "director/dead_earth_director/__pycache__/module.pyc": b"cache"}
            for name, data in files.items():
                path = root / name; path.parent.mkdir(parents=True, exist_ok=True); path.write_bytes(data)
            entries = release.director_entries(root, True)
            self.assertEqual(set(entries), {name for name in files if name.endswith(("__init__.py", "__main__.py", ".in", ".lock", "README.md", "setup.ps1", "director.example.toml", "models.lock.json", "model.ps1", "compose.cpu.yaml", "admin.ps1", "index.html", "admin.css", "admin.js", "authoring.css", "authoring.js", "ai-mode.js", "evolution.js", "evolution.css", "start-admin.bat"))})
            self.assertEqual(release.director_entries(root, False), {})

    def test_director_enabled_without_entrypoint_is_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            with self.assertRaises(ValueError):
                release.director_entries(Path(tmp), True)

    def own_jar(self, root, version="0.2.0-alpha.1"):
        data = release.zip_bytes({"META-INF/neoforge.mods.toml":
            f'[[mods]]\nmodId="dead_earth"\nversion="{version}"\n'.encode(),
            "dev/deadearth/DeadEarth.class": b"compiled-class"})
        name = "dead-earth-0.2.0-alpha.1.jar"
        path = root / "mods/dead-earth/build/libs" / name
        path.parent.mkdir(parents=True)
        path.write_bytes(data)
        return dict(filename=name, size=len(data), sha256=hashlib.sha256(data).hexdigest(),
                    version="0.2.0-alpha.1", mod_id="dead_earth", artifact=path.relative_to(root).as_posix(),
                    entrypoint="dev/deadearth/DeadEarth.class")

    def test_own_mod_is_packaged_only_after_hash_and_metadata_validation(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            row = self.own_jar(root)
            entries, manifest = release.own_mod_entries(root, [row], [])
            self.assertEqual(entries[".minecraft/mods/" + row["filename"]], (root / row["artifact"]).read_bytes())
            self.assertEqual(manifest[0]["sha256"], row["sha256"])
            self.assertTrue(manifest[0]["generated"])

    def test_missing_or_changed_own_artifact_is_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            row = self.own_jar(root)
            (root / row["artifact"]).write_bytes(b"tampered")
            with self.assertRaises(ValueError):
                release.own_mod_entries(root, [row], [])
            (root / row["artifact"]).unlink()
            with self.assertRaises(ValueError):
                release.own_mod_entries(root, [row], [])

    def test_own_jar_version_and_duplicate_names_are_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            row = self.own_jar(root, version="0.1.0-dev")
            with self.assertRaises(ValueError):
                release.own_mod_entries(root, [row], [])
            row = dict(row, version="0.1.0-dev")
            with self.assertRaises(ValueError):
                release.own_mod_entries(root, [row], [row["filename"].upper()])

    def test_own_artifact_cannot_escape_source_tree(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            row = self.own_jar(root)
            for path in ["../escape.jar", "C:/outside.jar", "mods/../../escape.jar"]:
                with self.subTest(path=path), self.assertRaises(ValueError):
                    release.own_mod_entries(root, [dict(row, artifact=path)], [])

    def row(self, name="example.jar", data=b"external-mod"):
        return dict(filename=name, size=len(data), sha256=hashlib.sha256(data).hexdigest(),
                    url="https://example.org/example.jar")

    def test_manifest_rejects_duplicate_names_and_unsafe_paths(self):
        row = self.row()
        release.validate_lock([row])
        for rows in ([row, row], [self.row("../escape.jar")],
                     [self.row("C:\\escape.jar")], [self.row("nested/mod.jar")]):
            with self.subTest(rows=rows), self.assertRaises(ValueError):
                release.validate_lock(rows)

    def test_cached_file_with_wrong_bytes_is_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "example.jar"
            path.write_bytes(b"tampered-mod")
            with self.assertRaises(ValueError):
                release.verify_file(path, self.row())

    def test_zip_is_reproducible_and_rejects_escape(self):
        entries = {"pack.mcmeta": b"{}", "data/example/recipe/test.json": b"{}"}
        with tempfile.TemporaryDirectory() as tmp:
            first, second = Path(tmp) / "first.jar", Path(tmp) / "second.jar"
            release.write_zip(first, entries)
            release.write_zip(second, dict(reversed(list(entries.items()))))
            self.assertEqual(first.read_bytes(), second.read_bytes())
            with self.assertRaises(ValueError):
                release.write_zip(Path(tmp) / "unsafe.zip", {"../escape": b"x"})

    def test_import_only_verified_locked_files_and_no_account_data(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            source = root / "source.zip"
            data = b"external-mod"
            with zipfile.ZipFile(source, "w") as archive:
                archive.writestr(".minecraft/mods/example.jar", data)
                archive.writestr(".minecraft/mods/pack-recipe-probe.jar", b"probe")
                archive.writestr("accounts.json", b"secret")
            cache = root / "cache"
            release.import_cache(source, cache, [self.row()])
            self.assertEqual([p.name for p in cache.iterdir()], ["example.jar"])
            self.assertEqual((cache / "example.jar").read_bytes(), data)

    def test_repository_source_and_recorded_evidence_validate(self):
        summary = release.check(ROOT)
        self.assertGreater(summary["external_mods"], 0)
        self.assertGreater(summary["patch_files"], 0)
        release.check_evidence(ROOT)

    def test_failed_recorded_report_is_not_accepted(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            evidence = root / "verification/mvp"
            evidence.mkdir(parents=True)
            (evidence / "crafting-results.json").write_text(json.dumps({"failures": 1}))
            with self.assertRaises(ValueError):
                release.check_evidence(root)

    def test_release_docs_include_local_markdown_link_targets(self):
        self.assertTrue(hasattr(release, "release_documents"), "Release document selector missing")
        enabled=bool(release.read_json(ROOT / "release.json").get("director"))
        documents = release.release_documents(ROOT) | release.director_entries(ROOT,enabled) | release.launcher_entries(ROOT,enabled)
        for name, data in documents.items():
            if not name.endswith(".md"):
                continue
            for target in re.findall(r"\]\(([^)]+)\)", data.decode("utf-8")):
                if target.startswith(("https://", "http://", "#", "mailto:")):
                    continue
                target = target.split("#")[0]
                resolved = posixpath.normpath(posixpath.join(posixpath.dirname(name), target))
                self.assertTrue(resolved in documents, f"Broken packaged link in {name}: {target}")


if __name__ == "__main__":
    unittest.main()
