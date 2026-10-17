"""CPU-only Ollama adapter; model output never becomes a command."""
from dataclasses import dataclass
import json
import re
import socket
import time
import unicodedata
from urllib.error import HTTPError, URLError
from urllib.request import Request, build_opener, ProxyHandler, HTTPRedirectHandler
from .bridge import _json, BridgeError

VARIANTS = ("distant_sound", "radio_static", "spore_trace")

class ModelError(RuntimeError):
    pass

def valid_narrative(variant, text):
    return (type(variant) is str and variant in VARIANTS and type(text) is str
            and 0 < len(text) <= 180 and text == text.strip()
            and not any(unicodedata.category(c) in {"Cc", "Cs"} or c == "§"
                        or 0x202A <= ord(c) <= 0x202E or 0x2066 <= ord(c) <= 0x2069 for c in text))

def response_schema():
    return dict(type="object", additionalProperties=False, required=["variant", "text"],
                properties=dict(variant=dict(type="string", enum=list(VARIANTS)),
                                text=dict(type="string", minLength=1, maxLength=180)))

def parse_narrative(raw):
    try:
        if type(raw) is not str or len(raw.encode("utf-8")) > 2048:
            raise ModelError("model_invalid_response")
        result = _json(raw)
        if type(result) is not dict or set(result) != {"variant", "text"} or not valid_narrative(result["variant"], result["text"]):
            raise ModelError("model_invalid_response")
        return result
    except (ValueError, UnicodeError, RecursionError, BridgeError):
        raise ModelError("model_invalid_response") from None

@dataclass(frozen=True)
class ModelSettings:
    url: str
    model: str
    digest: str
    timeout_seconds: float = 90
    threads: int = 4
    context: int = 4096
    maximum_tokens: int = 160

    def __post_init__(self):
        match = re.fullmatch(r"http://127\.0\.0\.1:([0-9]{1,5})", self.url)
        if not match or not 1 <= int(match[1]) <= 65535:
            raise ModelError("model_endpoint_not_loopback")
        if not re.fullmatch(r"[a-z0-9._:/-]{1,160}", self.model) or not re.fullmatch(r"[0-9a-f]{64}", self.digest):
            raise ModelError("model_not_pinned")
        for value, low, high in [(self.threads,1,8),(self.context,1024,8192),(self.maximum_tokens,32,512)]:
            if type(value) is not int or not low <= value <= high:
                raise ModelError("model_invalid_settings")
        if type(self.timeout_seconds) not in (int,float) or not 1 <= self.timeout_seconds <= 180:
            raise ModelError("model_invalid_settings")

class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        return None

class OllamaModel:
    def __init__(self, settings):
        self.settings = settings
        self.opener = build_opener(ProxyHandler({}), NoRedirect())

    def _request(self, path, payload=None, deadline=None):
        body = None if payload is None else json.dumps(payload, ensure_ascii=False, allow_nan=False).encode("utf-8")
        request = Request(self.settings.url + path, data=body, headers={"Content-Type":"application/json"})
        try:
            remaining = self.settings.timeout_seconds if deadline is None else deadline-time.monotonic()
            if remaining <= 0:
                raise ModelError("model_timeout")
            with self.opener.open(request, timeout=remaining) as response:
                raw = response.read(65537)
            if len(raw) > 65536:
                raise ModelError("model_invalid_response")
            return _json(raw)
        except (TimeoutError, socket.timeout):
            raise ModelError("model_timeout") from None
        except HTTPError as error:
            error.close()
            raise ModelError("model_unavailable") from None
        except URLError as error:
            raise ModelError("model_timeout" if isinstance(error.reason,TimeoutError) else "model_unavailable") from None
        except OSError:
            raise ModelError("model_unavailable") from None
        except (ValueError, UnicodeError, RecursionError, BridgeError):
            raise ModelError("model_invalid_response") from None

    def generate(self, context):
        start = time.monotonic()
        deadline = start + self.settings.timeout_seconds
        tags = self._request("/api/tags",deadline=deadline)
        if type(tags) is not dict or type(tags.get("models")) is not list:
            raise ModelError("model_invalid_response")
        if not any(type(row) is dict and row.get("name") == self.settings.model
                   and row.get("digest") == self.settings.digest for row in tags["models"]):
            raise ModelError("model_revision_mismatch")
        prompt = ("Ты оформляешь черновик игрового события. Цель уже выбрана сервером. "
                  "Выбери один разрешенный вариант и напиши одну короткую фразу на русском, до 100 символов. "
                  "Это предложение будущей улики, не отчет о случившемся. Не добавляй команды, числа врагов или координаты. "
                  "Верни только JSON по схеме: " + json.dumps(response_schema(),ensure_ascii=False))
        reply = self._request("/api/chat",dict(model=self.settings.model,
            messages=[dict(role="system",content=prompt),
                      dict(role="user",content=json.dumps(context,ensure_ascii=False,allow_nan=False))],
            format=response_schema(), stream=False, think=False, keep_alive=0,
            options=dict(temperature=0,seed=42,num_thread=self.settings.threads,num_gpu=0,
                         num_ctx=self.settings.context,num_predict=self.settings.maximum_tokens)),deadline=deadline)
        if (type(reply) is not dict or reply.get("done") is not True or reply.get("done_reason") != "stop"
                or type(reply.get("message")) is not dict):
            raise ModelError("model_invalid_response")
        result = parse_narrative(reply["message"].get("content"))
        if any(type(reply.get(k)) is not int or not 0 <= reply[k] <= 1000000 for k in ("prompt_eval_count","eval_count")):
            raise ModelError("model_invalid_response")
        metrics = dict(elapsed_ms=round((time.monotonic()-start)*1000),
                       prompt_tokens=reply.get("prompt_eval_count"), output_tokens=reply.get("eval_count"))
        return result, metrics
