import contextlib
import json
from pathlib import Path
import socketserver
import tempfile
import threading
import unittest

from dead_earth_director.bridge import BridgeClient, BridgeError, Descriptor

SECRET = "a" * 64
CAMPAIGN = "00000000-0000-4000-8000-000000000001"
EPOCH = "00000000-0000-4000-8000-000000000002"


def descriptor(port=12345):
    return dict(protocol=1, host="127.0.0.1", port=port, token=SECRET, campaign_id=CAMPAIGN, world_epoch=EPOCH)


def success(result, **changes):
    return dict(protocol=1, campaign_id=CAMPAIGN, world_epoch=EPOCH, ok=True, result=result) | changes


@contextlib.contextmanager
def endpoint(responder):
    requests = []
    class Handler(socketserver.StreamRequestHandler):
        def handle(self):
            request = json.loads(self.rfile.readline(8194))
            requests.append(request)
            response = responder(request)
            try:
                self.wfile.write(response if isinstance(response, bytes) else json.dumps(response).encode() + b"\n")
            except (BrokenPipeError, ConnectionResetError):
                pass
    class Server(socketserver.ThreadingTCPServer):
        daemon_threads = True
    with Server(("127.0.0.1", 0), Handler) as server:
        thread = threading.Thread(target=lambda: server.serve_forever(poll_interval=.01), daemon=True)
        thread.start()
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "dead_earth_bridge.json"
            path.write_text(json.dumps(descriptor(server.server_address[1])), encoding="utf-8")
            try:
                yield path, requests
            finally:
                server.shutdown()
                thread.join(1)


class BridgeTests(unittest.TestCase):
    def test_external_hosts_unknown_fields_and_invalid_types_are_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "bridge.json"
            for changes in [dict(host="example.com"), dict(host="localhost"), dict(port=True), dict(port=65536),
                            dict(command="stop"), dict(world_epoch="1-1-1-1-1"), dict(token="x")]:
                path.write_text(json.dumps(descriptor() | changes), encoding="utf-8")
                with self.subTest(changes=changes), self.assertRaises(BridgeError):
                    Descriptor.load(path)

    def test_descriptor_is_bounded_and_secret_is_not_in_repr(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "bridge.json"
            path.write_text(json.dumps(descriptor()), encoding="utf-8")
            self.assertNotIn(SECRET, repr(Descriptor.load(path)))
            path.write_bytes(b" " * 4097)
            with self.assertRaises(BridgeError):
                Descriptor.load(path)

    def test_real_loopback_exchange_preserves_context_and_result(self):
        def respond(request):
            if request["token"] != SECRET or request["world_epoch"] != EPOCH:
                return dict(ok=False, error=dict(code="unauthorized"))
            return success(dict(activeTicks=42))
        with endpoint(respond) as (path, requests):
            client = BridgeClient(Descriptor.load(path))
            self.assertEqual(client.call("get_snapshot", {}), dict(activeTicks=42))
            self.assertEqual(requests[0]["operation"], "get_snapshot")
            self.assertNotIn(SECRET, repr(client))

    def test_response_from_other_campaign_or_epoch_is_rejected(self):
        for changes in [dict(campaign_id=EPOCH), dict(world_epoch=CAMPAIGN)]:
            with self.subTest(changes=changes), endpoint(lambda r: success(dict(secret_fact="no"), **changes)) as (path, requests):
                with self.assertRaisesRegex(BridgeError, "invalid_context"):
                    BridgeClient(Descriptor.load(path)).call("get_snapshot", {})

    def test_unknown_tools_and_invalid_arguments_do_not_reach_network(self):
        with endpoint(lambda r: success({})) as (path, requests):
            client = BridgeClient(Descriptor.load(path))
            for operation, args in [("execute_command", dict(command="stop")), ("get_history", dict(limit=101)),
                                    ("get_history", dict(limit=True)), ("propose_scenario", dict(request_id="1-1-1-1-1")),
                                    ("get_snapshot", dict(extra="ignored?"))]:
                with self.subTest(operation=operation), self.assertRaises(BridgeError):
                    client.call(operation, args)
            self.assertEqual(requests, [])

    def test_oversized_response_and_remote_error_are_sanitized(self):
        with endpoint(lambda r: b"x" * 262146 + b"\n") as (path, requests):
            with self.assertRaisesRegex(BridgeError, "response_too_large"):
                BridgeClient(Descriptor.load(path)).call("get_snapshot", {})
        with endpoint(lambda r: dict(ok=False, error=dict(code="unauthorized"))) as (path, requests):
            with self.assertRaisesRegex(BridgeError, "unauthorized"):
                BridgeClient(Descriptor.load(path)).call("get_snapshot", {})

    def test_running_client_does_not_silently_adopt_new_descriptor_epoch(self):
        with endpoint(lambda r: success({})) as (path, requests):
            client = BridgeClient(Descriptor.load(path))
            path.write_text(json.dumps(descriptor() | dict(world_epoch=CAMPAIGN)), encoding="utf-8")
            self.assertEqual(client.call("get_snapshot", {}), {})
            self.assertEqual(requests[0]["world_epoch"], EPOCH)


if __name__ == "__main__":
    unittest.main()
