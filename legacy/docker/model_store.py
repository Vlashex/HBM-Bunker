"""Offline import of exact pinned runnable manifests, including native Ollama v2."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import shutil
import sys
import uuid


def sha256(path):
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(4 * 1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def source_root(source):
    source = Path(source).resolve(strict=True)
    for candidate in (source / "models", source):
        if ((candidate / "manifests").is_dir() or (candidate / "manifests-v2").is_dir()) and (candidate / "blobs").is_dir():
            return candidate
    raise ValueError("model_store_layout_missing")


def manifest_name(model):
    match = re.fullmatch(r"([a-z0-9._-]{1,100}):([a-z0-9._-]{1,100})", model)
    if not match:
        raise ValueError("unsupported_pinned_model_name")
    return Path("manifests/registry.ollama.ai/library") / match[1] / match[2]


def v2_manifest_name(model):
    legacy = manifest_name(model)
    return Path("manifests-v2/ollama.com/library") / legacy.parts[-2] / legacy.parts[-1]


def selected_manifest(source, pin):
    """Resolve content identity, never the mutable hash of a downgrade anchor.

    Ollama 0.40.6 stores runnable manifests as blobs and may append manifest/list
    retention layers to the old named file (WriteLegacyAnchor). That wrapper's
    SHA is not the configured runnable pin. The byte-exact pinned blob is the
    authority; a regular named-file fallback supports older Ollama exports.
    Restoring the configured alias does not change its pinned model content.
    """
    model, digest = pin
    if type(digest) is not str or not re.fullmatch(r"[0-9a-f]{64}", digest):
        raise ValueError("invalid_model_pin")
    named = manifest_name(model)
    candidates = (Path("blobs") / ("sha256-" + digest), named, v2_manifest_name(model))
    for relative in candidates:
        path = source / relative
        try:
            # Native Linux refs can arrive on NTFS as opaque LX reparse points.
            # Do not follow or reproduce them; copy their exact pinned content
            # into portable regular-file refs in the new store.
            if (path.is_symlink() or not path.resolve().is_relative_to(source)
                    or not path.is_file() or path.stat().st_size > 1048576):
                continue
            if sha256(path) == digest:
                return relative, path
        except (OSError, ValueError):
            continue
    raise ValueError("pinned_runnable_manifest_missing")


def manifest_inputs(source, pin):
    model, digest = pin
    relative, manifest = selected_manifest(source, pin)
    value = json.loads(manifest.read_text(encoding="utf-8"))
    if (type(value) is not dict or type(value.get("schemaVersion")) is not int
            or value["schemaVersion"] != 2
            or value.get("mediaType") not in (None, "application/vnd.docker.distribution.manifest.v2+json")):
        raise ValueError("pinned_manifest_not_runnable")
    layers, config = value.get("layers"), value.get("config")
    if type(layers) is not list or len(layers) > 64 or type(config) is not dict:
        raise ValueError("invalid_model_manifest")
    # Official OpenVerifiedManifest accepts regular-file v2 refs. Publish both
    # layouts from the exact same bytes, including the configured critic alias.
    # Map each destination to its actual source path and required SHA.
    result = {manifest_name(model): (relative, digest),
              v2_manifest_name(model): (relative, digest)}
    for layer in [config] + layers:
        if (type(layer) is not dict or not re.fullmatch(r"sha256:[0-9a-f]{64}", layer.get("digest", ""))
                or type(layer.get("size")) is not int or layer["size"] < 0):
            raise ValueError("invalid_model_manifest")
        blob = Path("blobs") / layer["digest"].replace(":", "-")
        path = source / blob
        if (path.is_symlink() or not path.resolve().is_relative_to(source)
                or not path.is_file() or path.stat().st_size != layer["size"]
                or sha256(path) != layer["digest"][7:]):
            raise ValueError("pinned_model_blob_mismatch")
        result[blob] = (blob, layer["digest"][7:])
    return result


def import_models(source, target, pins):
    source = source_root(source)
    target = Path(target).resolve() / "models"
    inputs = {}
    for pin in pins:
        inputs.update(manifest_inputs(source, pin))
    if source == target:
        raise ValueError("source_and_target_are_the_same")
    # Validate every source and existing destination before writing anything.
    for relative, (_, digest) in inputs.items():
        destination = target / relative
        if destination.is_symlink() or destination.exists() and sha256(destination) != digest:
            raise ValueError("existing_model_store_conflict")
        if not destination.resolve().is_relative_to(target):
            raise ValueError("linked_model_store")
    written = 0
    # Publish both named layouts only after every model/config blob is present.
    ordered = sorted(inputs.items(), key=lambda item: item[0].parts[0] in ("manifests", "manifests-v2"))
    for relative, (source_relative, digest) in ordered:
        destination = target / relative
        if destination.exists():
            continue
        destination.parent.mkdir(parents=True, exist_ok=True)
        temporary = destination.with_name(destination.name + "." + uuid.uuid4().hex + ".import")
        try:
            with temporary.open("xb") as output, (source / source_relative).open("rb") as input_file:
                shutil.copyfileobj(input_file, output, length=4 * 1024 * 1024)
            if sha256(temporary) != digest:
                raise ValueError("model_import_copy_mismatch")
            temporary.replace(destination)
            written += 1
        finally:
            temporary.unlink(missing_ok=True)
    return dict(state="imported", pinned_models=len(pins), files_validated=len(inputs),
                files_written=written, manifest_layout="classic_and_v2", identity="exact_runnable_sha256")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--source", type=Path, required=True)
    parser.add_argument("--target", type=Path, default=Path("/models"))
    parser.add_argument("--config", type=Path, required=True)
    args = parser.parse_args()
    try:
        from dead_earth_director.host import load_config
        settings, _ = load_config(args.config)
        pins = [(settings.model, settings.digest)]
        if settings.critic is not None:
            pins.append((settings.critic.model, settings.critic.digest))
        print(json.dumps(import_models(args.source, args.target, pins)))
        return 0
    except Exception:
        print('{"state":"offline_model_import_failed"}', file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
