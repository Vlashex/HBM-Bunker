"""Narrow, explicitly enabled networking for the SRP Compose deployment.

The game bridge and RCON still use loopback in a shared network namespace.
Only the pinned Ollama service gains a DNS endpoint; normal desktop behavior
and the model/MCP permission set do not change.
"""
import os
import re


def container_mode():
    return os.environ.get("DEAD_EARTH_CONTAINER_MODE") == "1"


def model_endpoint_allowed(url):
    match = re.fullmatch(r"http://127\.0\.0\.1:([0-9]{1,5})", url)
    if match and 1 <= int(match[1]) <= 65535:
        return True
    return container_mode() and url == "http://ollama:11434"


def admin_bind():
    return "0.0.0.0" if container_mode() else "127.0.0.1"


def server_binding_allowed(value):
    return value == "127.0.0.1" or container_mode() and value in ("", "0.0.0.0")
