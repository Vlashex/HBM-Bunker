"""Strict loopback client. Secrets stay in the selected world's descriptor."""
from dataclasses import dataclass, field
import json
from pathlib import Path
import re
import socket
from uuid import UUID

MAX_INPUT, MAX_OUTPUT = 8192, 262144


class BridgeError(RuntimeError):
    pass


def _unique(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise BridgeError("invalid_json")
        result[key] = value
    return result


def _json(data):
    def invalid_constant(value):
        raise BridgeError("invalid_json")
    return json.loads(data, object_pairs_hook=_unique, parse_constant=invalid_constant)


def _uuid(value):
    try:
        return type(value) is str and len(value) == 36 and str(UUID(value)) == value.lower()
    except ValueError:
        return False


@dataclass(frozen=True)
class Descriptor:
    host: str
    port: int
    token: str = field(repr=False)
    campaign_id: str
    world_epoch: str

    @classmethod
    def load(cls, path):
        path = Path(path)
        try:
            if path.is_symlink() or not path.is_file():
                raise BridgeError("descriptor_unavailable")
            with path.open("rb") as source:
                data = source.read(4097)
        except OSError:
            raise BridgeError("descriptor_unavailable") from None
        try:
            raw = _json(data)
            if (len(data) > 4096 or type(raw) is not dict
                    or set(raw) != {"protocol", "host", "port", "token", "campaign_id", "world_epoch"}
                    or type(raw["protocol"]) is not int or raw["protocol"] != 1
                    or raw["host"] != "127.0.0.1" or type(raw["port"]) is not int or not 1 <= raw["port"] <= 65535
                    or type(raw["token"]) is not str or not re.fullmatch(r"[0-9a-f]{64}", raw["token"])
                    or not _uuid(raw["campaign_id"]) or not _uuid(raw["world_epoch"])):
                raise BridgeError("descriptor_invalid")
            return cls(**{key: raw[key] for key in ["host", "port", "token", "campaign_id", "world_epoch"]})
        except (ValueError, UnicodeError, RecursionError, BridgeError):
            raise BridgeError("descriptor_invalid") from None


def _arguments(operation, arguments):
    expected = {"get_snapshot": set(), "get_history": {"limit"}, "list_catalog": set(),
                "preview_scenario": {"request_id"}, "propose_scenario": {"request_id"},
                "get_scenario_status": {"scenario_id"}, "cancel_scenario": {"scenario_id", "revision"}}
    if operation not in expected:
        raise BridgeError("unknown_operation")
    if operation in {"preview_scenario", "propose_scenario"}:
        allowed = {"request_id", "catalog_id", "target_id", "variant", "delay_ticks"}
        if type(arguments) is not dict or "request_id" not in arguments or not set(arguments) <= allowed:
            raise BridgeError("invalid_arguments")
        catalog = arguments.get("catalog_id", "warning_beacon_v1")
        target = arguments.get("target_id", "test_anchor")
        variant = arguments.get("variant", "standard")
        delay = arguments.get("delay_ticks", 200)
        if (type(catalog) is not str or type(target) is not str or type(variant) is not str
                or type(delay) is not int or not 200 <= delay <= 1200 or delay % 20):
            raise BridgeError("invalid_arguments")
        if catalog == "warning_beacon_v1":
            valid = target == "test_anchor" and variant == "standard"
        elif catalog == "relay_broadcast_v1":
            valid = (target.startswith("relay:") and _uuid(target[6:]) and target[6:] == target[6:].lower()
                     and variant in {"check_in", "survey", "rally"})
        else:
            valid = False
        if not valid:
            raise BridgeError("invalid_arguments")
    elif type(arguments) is not dict or set(arguments) != expected[operation]:
        raise BridgeError("invalid_arguments")
    for key in ["request_id", "scenario_id"]:
        if key in arguments and not _uuid(arguments[key]):
            raise BridgeError("invalid_arguments")
    if "limit" in arguments and (type(arguments["limit"]) is not int or not 1 <= arguments["limit"] <= 100):
        raise BridgeError("invalid_arguments")
    if "revision" in arguments and (type(arguments["revision"]) is not int or not 0 <= arguments["revision"] <= 9223372036854775807):
        raise BridgeError("invalid_arguments")
    return dict(arguments)


class BridgeClient:
    def __init__(self, descriptor):
        self.descriptor = descriptor

    def call(self, operation, arguments):
        arguments = _arguments(operation, arguments)
        descriptor = self.descriptor
        request = dict(protocol=1, token=descriptor.token, campaign_id=descriptor.campaign_id,
                       world_epoch=descriptor.world_epoch, operation=operation, arguments=arguments)
        frame = json.dumps(request, separators=(",", ":"), ensure_ascii=False).encode("utf-8")
        if len(frame) > MAX_INPUT:
            raise BridgeError("frame_too_large")
        try:
            with socket.create_connection((descriptor.host, descriptor.port), timeout=2) as connection:
                connection.settimeout(4)
                connection.sendall(frame + b"\n")
                with connection.makefile("rb") as stream:
                    data = stream.readline(MAX_OUTPUT + 2)
        except OSError:
            raise BridgeError("bridge_unavailable") from None
        if len(data) > MAX_OUTPUT + 1:
            raise BridgeError("response_too_large")
        if not data.endswith(b"\n"):
            raise BridgeError("incomplete_response")
        try:
            reply = _json(data)
        except (ValueError, UnicodeError, RecursionError, BridgeError):
            raise BridgeError("invalid_response") from None
        if type(reply) is not dict or type(reply.get("ok")) is not bool:
            raise BridgeError("invalid_response")
        if not reply["ok"]:
            error = reply.get("error", {})
            code = error.get("code") if type(error) is dict else None
            raise BridgeError(code if type(code) is str and re.fullmatch(r"[a-z_]{1,64}", code) else "bridge_error")
        if (set(reply) != {"protocol", "campaign_id", "world_epoch", "ok", "result"}
                or type(reply["protocol"]) is not int or reply["protocol"] != 1):
            raise BridgeError("invalid_response")
        if reply["campaign_id"] != descriptor.campaign_id or reply["world_epoch"] != descriptor.world_epoch:
            raise BridgeError("invalid_context")
        return reply["result"]
