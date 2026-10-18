import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile
import threading
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

ROOT = Path(__file__).resolve().parents[1]


@unittest.skipUnless(os.name == "nt", "Windows admin launcher")
class AdminLauncherTests(unittest.TestCase):
    def test_junction_uses_real_server_identity_and_rejects_another_server(self):
        with tempfile.TemporaryDirectory() as directory:
            target = Path(directory) / "actual-server"
            alias = Path(directory) / "server-alias"
            target.mkdir()
            command = "New-Item -ItemType Junction -Path '" + str(alias).replace("'", "''") + "' -Target '" + str(target).replace("'", "''") + "' | Out-Null"
            made = subprocess.run(["powershell.exe", "-NoProfile", "-Command", command], capture_output=True, text=True, timeout=15)
            self.assertEqual(made.returncode, 0, made.stderr)
            expected = hashlib.sha256(str(target.resolve()).lower().encode()).hexdigest()[:16]
            identity = [expected]

            class Handler(BaseHTTPRequestHandler):
                def do_GET(self):
                    self.send_response(200)
                    self.send_header("Content-Type", "application/json")
                    self.end_headers()
                    self.wfile.write(json.dumps({"product": "dead-earth-admin-v1", "instance_id": identity[0]}).encode())

                def log_message(self, *args):
                    pass

            server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
            worker = threading.Thread(target=server.serve_forever, daemon=True)
            worker.start()
            try:
                for actual, code, text in [(expected, 0, "admin is running"), ("other-server", 1, "another application or server")]:
                    with self.subTest(identity=actual):
                        identity[0] = actual
                        result = subprocess.run([
                            "powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", str(ROOT / "admin.ps1"),
                            "-Action", "status", "-NoOpen", "-ServerRoot", str(alias), "-Port", str(server.server_port),
                        ], capture_output=True, text=True, timeout=20)
                        self.assertEqual(result.returncode, code, result.stdout + result.stderr)
                        self.assertIn(text, result.stdout)
            finally:
                server.shutdown()
                server.server_close()
                worker.join(timeout=5)
                alias.rmdir()


if __name__ == "__main__":
    unittest.main()
