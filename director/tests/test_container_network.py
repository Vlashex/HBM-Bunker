"""The Compose exception must not weaken the ordinary Windows policy."""
import http.client
import json
import os
from pathlib import Path
import tempfile
import threading
import unittest
from unittest.mock import patch

from dead_earth_director import admin
from dead_earth_director.admin_console import settings
from dead_earth_director.bridge import Descriptor, BridgeError
from dead_earth_director.model import ModelSettings, ModelError
from dead_earth_director.network import admin_bind, container_mode

PIN = "a" * 64


class ContainerPolicyTests(unittest.TestCase):
    def test_desktop_default_still_rejects_service_dns_and_public_models(self):
        for flag in ("", "0", "true"):
            with patch.dict(os.environ, {"DEAD_EARTH_CONTAINER_MODE": flag}):
                self.assertFalse(container_mode())
                self.assertEqual(admin_bind(), "127.0.0.1")
                ModelSettings("http://127.0.0.1:11434", "primary:4b", PIN)
                for endpoint in ("http://ollama:11434", "http://192.168.1.1:11434",
                                 "https://example.org", "http://localhost:11434"):
                    with self.subTest(endpoint=endpoint), self.assertRaises(ModelError):
                        ModelSettings(endpoint, "primary:4b", PIN)

    def test_container_has_exactly_one_additional_model_endpoint(self):
        with patch.dict(os.environ, {"DEAD_EARTH_CONTAINER_MODE": "1"}):
            self.assertTrue(container_mode())
            self.assertEqual(admin_bind(), "0.0.0.0")
            ModelSettings("http://ollama:11434", "primary:4b", PIN)
            for endpoint in ("http://ollama:11435", "https://ollama:11434",
                             "http://ollama:11434/", "http://ollama.evil:11434",
                             "http://admin:8766", "http://minecraft:25575",
                             "http://user@ollama:11434"):
                with self.subTest(endpoint=endpoint), self.assertRaises(ModelError):
                    ModelSettings(endpoint, "primary:4b", PIN)

    def test_bridge_remains_loopback_with_campaign_uuid_auth_in_container(self):
        value = dict(protocol=1, host="127.0.0.1", port=25589, token="b" * 64,
                     campaign_id="00000000-0000-4000-8000-000000000001",
                     world_epoch="00000000-0000-4000-8000-000000000002")
        with patch.dict(os.environ, {"DEAD_EARTH_CONTAINER_MODE": "1"}), tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "descriptor"
            path.write_text(json.dumps(value), encoding="utf-8")
            self.assertEqual(Descriptor.load(path).host, "127.0.0.1")
            for host in ("minecraft", "0.0.0.0", "172.18.0.2"):
                path.write_text(json.dumps(dict(value, host=host)), encoding="utf-8")
                with self.subTest(host=host), self.assertRaises(BridgeError):
                    Descriptor.load(path)

    def test_rcon_wildcard_is_accepted_only_in_explicit_container_mode(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            path = root / "server.properties"
            path.write_text("server-ip=\nenable-rcon=true\nrcon.port=25575\nrcon.password=private\n")
            with patch.dict(os.environ, {"DEAD_EARTH_CONTAINER_MODE": ""}):
                self.assertEqual(settings(root)[0], "unsafe_binding")
            with patch.dict(os.environ, {"DEAD_EARTH_CONTAINER_MODE": "1"}):
                self.assertEqual(settings(root)[:2], ("configured", 25575))
                path.write_text("server-ip=192.168.1.2\nenable-rcon=true\nrcon.password=private\n")
                self.assertEqual(settings(root)[0], "unsafe_binding")

    def test_container_admin_retains_host_origin_and_nonce_guards(self):
        with tempfile.TemporaryDirectory() as tmp, patch.dict(os.environ, {"DEAD_EARTH_CONTAINER_MODE": "1"}):
            root = Path(tmp)
            app = admin.Application(root / "bridge", root / "journal", root / "db", root / "catalog")
            server = admin.make_server(app, 0)
            self.assertEqual(server.server_address[0], "0.0.0.0")
            thread = threading.Thread(target=server.serve_forever, daemon=True)
            thread.start()
            port = server.server_address[1]
            def request(path="/api/health", headers=None, method="GET"):
                connection = http.client.HTTPConnection("127.0.0.1", port, timeout=3)
                connection.request(method, path, headers=headers or {})
                response = connection.getresponse()
                response.read()
                result = response.status
                connection.close()
                return result
            try:
                self.assertEqual(request(), 200)
                self.assertEqual(request(headers={"Host": "admin:" + str(port)}), 403)
                self.assertEqual(request(headers={"Host": "evil.test"}), 403)
                self.assertEqual(request(headers={"Origin": "https://evil.test"}), 403)
                self.assertEqual(request(headers={"Sec-Fetch-Site": "cross-site"}), 403)
                self.assertEqual(request("/api/console/command", method="POST",
                                         headers={"Origin": "http://127.0.0.1:" + str(port)}), 403)
            finally:
                server.shutdown()
                server.server_close()
                thread.join()
