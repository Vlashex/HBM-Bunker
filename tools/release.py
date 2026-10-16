"""Portable, deterministic pack assembly. Python 3.11+, standard library only."""
import argparse
import hashlib
import io
import json
from pathlib import Path, PurePosixPath
import re
import ssl
import tomllib
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[1]
EPOCH = (1980, 1, 1, 0, 0, 0)


def read_json(path):
    return json.loads(path.read_text(encoding="utf-8"))


def safe_path(name):
    path = PurePosixPath(name)
    if not name or path.is_absolute() or ".." in path.parts or "\\" in name or ":" in name:
        raise ValueError(f"Unsafe archive path: {name!r}")


def validate_lock(rows):
    names = set()
    for row in rows:
        name = row["filename"]
        safe_path(name)
        if "/" in name or not name.endswith(".jar") or name.casefold() in names:
            raise ValueError(f"Invalid or duplicate mod filename: {name}")
        names.add(name.casefold())
        if not re.fullmatch(r"[0-9a-f]{64}", row["sha256"]) or row["size"] <= 0:
            raise ValueError(f"Invalid size/hash for {name}")
        if not row.get("url", "").startswith("https://"):
            raise ValueError(f"Missing HTTPS download URL for {name}")
    return rows


def verify_bytes(data, row):
    if len(data) != row["size"] or hashlib.sha256(data).hexdigest() != row["sha256"]:
        raise ValueError(f"Size/SHA256 mismatch: {row['filename']}")


def verify_file(path, row):
    verify_bytes(path.read_bytes(), row)


def validate_own_mods(rows, forbidden_names):
    names = {name.casefold() for name in forbidden_names}
    for row in rows:
        name = row["filename"]
        safe_path(name)
        safe_path(row["artifact"])
        safe_path(row["entrypoint"])
        if ("/" in name or not name.endswith(".jar") or name.casefold() in names
                or not row["artifact"].startswith("mods/") or not row["artifact"].endswith("/" + name)
                or not row["entrypoint"].endswith(".class")
                or not re.fullmatch(r"[a-z][a-z0-9_]{1,63}", row["mod_id"])
                or not re.fullmatch(r"[0-9a-f]{64}", row["sha256"]) or row["size"] <= 0):
            raise ValueError(f"Invalid own mod pin: {name}")
        names.add(name.casefold())
    return rows


def own_mod_entries(root, rows, forbidden_names):
    validate_own_mods(rows, forbidden_names)
    entries, manifest = {}, []
    for row in rows:
        path = root / row["artifact"]
        if not path.resolve().is_relative_to(root.resolve()) or not path.is_file() or path.is_symlink():
            raise ValueError(f"Own mod artifact missing or unsafe: {row['filename']}; build its source first")
        data = path.read_bytes()
        verify_bytes(data, row)
        try:
            with zipfile.ZipFile(io.BytesIO(data)) as jar:
                info = tomllib.loads(jar.read("META-INF/neoforge.mods.toml").decode("utf-8"))
                if not any(mod["modId"] == row["mod_id"] and mod["version"] == row["version"] for mod in info["mods"]):
                    raise ValueError(f"Own mod metadata mismatch: {row['filename']}")
                if row["entrypoint"] not in jar.namelist():
                    raise ValueError(f"Own mod entrypoint missing: {row['filename']}")
        except (zipfile.BadZipFile, KeyError, tomllib.TOMLDecodeError) as invalid:
            raise ValueError(f"Invalid own mod JAR: {row['filename']}") from invalid
        entries[".minecraft/mods/" + row["filename"]] = data
        manifest.append({key: row[key] for key in ["filename", "size", "sha256", "version", "mod_id"]} | {"generated": True})
    return entries, manifest


def zip_bytes(entries):
    result = io.BytesIO()
    with zipfile.ZipFile(result, "w", zipfile.ZIP_DEFLATED, compresslevel=3) as archive:
        for name, data in sorted(entries.items()):
            safe_path(name)
            info = zipfile.ZipInfo(name, date_time=EPOCH)
            info.compress_type = zipfile.ZIP_DEFLATED
            info.create_system = 3
            info.external_attr = 0o100644 << 16
            archive.writestr(info, data, compress_type=zipfile.ZIP_DEFLATED, compresslevel=3)
    return result.getvalue()


def write_zip(path, entries):
    data = zip_bytes(entries)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(data)


def tree_entries(folder):
    entries = {}
    for path in sorted(folder.rglob("*")):
        if path.is_symlink():
            raise ValueError(f"Source symlinks are unsupported: {path}")
        if path.is_file():
            entries[path.relative_to(folder).as_posix()] = path.read_bytes()
    return entries


def import_cache(archive_path, cache, rows):
    validate_lock(rows)
    # Check everything before writing; never extract arbitrary archive members.
    verified = {}
    with zipfile.ZipFile(archive_path) as archive:
        for row in rows:
            data = archive.read(".minecraft/mods/" + row["filename"])
            verify_bytes(data, row)
            verified[row["filename"]] = data
    cache.mkdir(parents=True, exist_ok=True)
    for name, data in verified.items():
        (cache / name).write_bytes(data)


def fetch(cache, rows, ca_bundle=None):
    validate_lock(rows)
    cache.mkdir(parents=True, exist_ok=True)
    context = ssl.create_default_context(cafile=ca_bundle)
    for row in rows:
        target = cache / row["filename"]
        if target.exists():
            verify_file(target, row)
        else:
            request = urllib.request.Request(row["url"], headers={"User-Agent": "HBM-Bunker-Pack-Builder/0.1"})
            with urllib.request.urlopen(request, context=context, timeout=120) as response:
                data = response.read()
            verify_bytes(data, row)
            temporary = target.with_suffix(".jar.download")
            temporary.write_bytes(data)
            temporary.replace(target)
        print("Verified", row["filename"])


def check(root):
    release = read_json(root / "release.json")
    rows = validate_lock(read_json(root / "metadata/mods.lock.json"))
    own_pins = root / "metadata/own-mods.lock.json"
    validate_own_mods(read_json(own_pins) if own_pins.is_file() else [],
                      [row["filename"] for row in rows] + [release["patch_filename"]])
    for directory in ["pack", "metadata"]:
        for path in (root / directory).rglob("*.json"):
            read_json(path)
    for path in (root / "pack").rglob("*.toml"):
        tomllib.loads(path.read_text(encoding="utf-8"))
    patch = root / "pack/patch"
    mod_info = tomllib.loads((patch / "META-INF/neoforge.mods.toml").read_text(encoding="utf-8"))
    if mod_info["mods"][0]["version"] != release["patch_version"]:
        raise ValueError("Patch version differs from release.json")
    components = {row["uid"]: row["version"] for row in read_json(root / "pack/mmc-pack.json")["components"]}
    if components["net.minecraft"] != release["minecraft"] or components["net.neoforged"] != release["neoforge"]:
        raise ValueError("Minecraft/NeoForge versions differ from release.json")
    safe_path(release["patch_filename"])
    if "/" in release["patch_filename"] or not release["patch_filename"].endswith(".jar"):
        raise ValueError("Patch filename must be a JAR basename")
    if any(row["filename"] == release["patch_filename"] for row in rows):
        raise ValueError("Own generated patch must not appear in external mod lock")
    if not re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+(?:-[a-z0-9.]+)?", release["version"]):
        raise ValueError("Invalid release version")
    entries = tree_entries(patch)
    if not entries or any(name.endswith((".jar", ".zip", ".log")) for name in entries):
        raise ValueError("Patch source must contain source data only")
    return dict(external_mods=len(rows), patch_files=len(entries), version=release["version"])


def check_evidence(root):
    """Validate the recorded MVP evidence, without implying a fresh game run."""
    folder = root / "verification/mvp"
    for name in ["crafting-results", "automation-emc-results", "mining-crafting-results", "real-plane-results"]:
        report = read_json(folder / f"{name}.json")
        if report["failures"] != 0:
            raise ValueError(f"Recorded MVP check failed: {name}")
    planes = read_json(folder / "real-plane-results.json")
    if len(planes["real_plane_results"]) != 32:
        raise ValueError("Recorded plane evidence does not contain 32 ores")
    for row in planes["real_plane_results"]:
        base, fortune3, fortune5, silk = row["planes"]
        if (any(p["blocks"] != 32 for p in row["planes"])
                or fortune3["total"] <= base["total"] or fortune5["total"] <= base["total"]
                or silk["drops"] != {row["ore"]: 32}):
            raise ValueError(f"Invalid recorded plane result: {row['ore']}")
    return "Recorded MVP evidence validated; Minecraft was not run by this command."


def release_documents(root):
    paths = [root / name for name in ["README.md", "CHANGELOG.md", "CONTRIBUTING.md", "THIRD-PARTY.md"]]
    paths += sorted((root / "docs").rglob("*.md"))
    return {path.relative_to(root).as_posix(): path.read_bytes() for path in paths}


def build(root, cache, dist):
    summary = check(root)
    settings = read_json(root / "release.json")
    rows = read_json(root / "metadata/mods.lock.json")
    entries = {}
    manifest = []
    for row in rows:
        path = cache / row["filename"]
        if not path.is_file():
            raise ValueError(f"Missing {path.name}; run fetch or import-cache first")
        verify_file(path, row)
        entries[".minecraft/mods/" + row["filename"]] = path.read_bytes()
        manifest.append(row)
    patch = zip_bytes(tree_entries(root / "pack/patch"))
    patch_name = settings["patch_filename"]
    entries[".minecraft/mods/" + patch_name] = patch
    manifest.append(dict(filename=patch_name, size=len(patch), sha256=hashlib.sha256(patch).hexdigest(), version=settings["patch_version"], generated=True))
    own_pins = root / "metadata/own-mods.lock.json"
    own_entries, own_manifest = own_mod_entries(root, read_json(own_pins) if own_pins.is_file() else [],
                                               [row["filename"] for row in rows] + [patch_name])
    entries.update(own_entries)
    manifest.extend(own_manifest)
    entries["mmc-pack.json"] = (root / "pack/mmc-pack.json").read_bytes()
    entries["instance.cfg"] = ("[General]\nConfigVersion=1.2\nInstanceType=OneSix\n"
        f"name={settings['name']} {settings['version']}\niconKey=default\n"
        f"OverrideMemory=true\nMinMemAlloc=1024\nMaxMemAlloc={settings['memory_mb']}\n").encode()
    for name, data in tree_entries(root / "pack/config").items():
        entries[".minecraft/config/" + name] = data
    release_notes = root / "docs/releases" / f"{settings['version']}.md"
    if not release_notes.is_file():
        raise ValueError(f"Release notes missing: {release_notes.name}")
    entries.update(release_documents(root))
    entries["mods-manifest.json"] = (json.dumps(sorted(manifest, key=lambda row: row["filename"]), ensure_ascii=False, indent=2) + "\n").encode()
    entries["release.json"] = (root / "release.json").read_bytes()
    dist.mkdir(parents=True, exist_ok=True)
    archive = dist / f"HBM-Bunker-{settings['version']}-Prism.zip"
    write_zip(archive, entries)
    (dist / patch_name).write_bytes(patch)
    checksums = {archive.name: hashlib.sha256(archive.read_bytes()).hexdigest(), patch_name: hashlib.sha256(patch).hexdigest()}
    for name, data in own_entries.items():
        filename = PurePosixPath(name).name
        (dist / filename).write_bytes(data)
        checksums[filename] = hashlib.sha256(data).hexdigest()
    (dist / "SHA256SUMS.txt").write_text("".join(f"{digest}  {name}\n" for name, digest in sorted(checksums.items())), encoding="ascii")
    with zipfile.ZipFile(archive) as built:
        if built.testzip() is not None:
            raise ValueError("Corrupt generated archive")
    return dict(**summary, total_mods=len(manifest), archive=str(archive), sha256=checksums[archive.name])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=["check", "check-evidence", "build", "fetch", "import-cache"])
    parser.add_argument("archive", nargs="?", type=Path)
    parser.add_argument("--cache", type=Path, default=ROOT / ".cache/mods")
    parser.add_argument("--dist", type=Path, default=ROOT / "dist")
    parser.add_argument("--ca-bundle", help="Optional CA bundle for HTTPS downloads")
    args = parser.parse_args()
    try:
        if args.command == "check":
            print(json.dumps(check(ROOT), ensure_ascii=False))
        elif args.command == "check-evidence":
            print(check_evidence(ROOT))
        elif args.command == "build":
            print(json.dumps(build(ROOT, args.cache, args.dist), ensure_ascii=False))
        elif args.command == "fetch":
            fetch(args.cache, read_json(ROOT / "metadata/mods.lock.json"), args.ca_bundle)
        else:
            if args.archive is None:
                parser.error("import-cache requires an existing Prism ZIP")
            import_cache(args.archive, args.cache, read_json(ROOT / "metadata/mods.lock.json"))
            print("Verified external mods imported into ignored local cache")
    except (ValueError, OSError, KeyError, zipfile.BadZipFile) as error:
        parser.exit(1, f"Error: {error}\n")


if __name__ == "__main__":
    main()
