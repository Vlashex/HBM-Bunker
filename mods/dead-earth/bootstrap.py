"""Fetch only the pinned Gradle wrapper JAR, which is intentionally absent from Git."""
from pathlib import Path
import hashlib
import json
import urllib.request

ROOT = Path(__file__).resolve().parent


def main():
    source = json.loads((ROOT / "toolchain-source.json").read_text(encoding="utf-8"))
    relative = "gradle/wrapper/gradle-wrapper.jar"
    target = ROOT / relative
    expected = source["files"][relative]
    if target.exists():
        data = target.read_bytes()
    else:
        url = ("https://raw.githubusercontent.com/NeoForgeMDKs/MDK-1.21.1-ModDevGradle/"
               + source["mdk_commit"] + "/" + relative)
        with urllib.request.urlopen(url, timeout=60) as response:
            data = response.read(128 * 1024)
    if hashlib.sha256(data).hexdigest() != expected:
        raise SystemExit("Gradle wrapper checksum mismatch; no file was written")
    if not target.exists():
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(data)
    print("Pinned Gradle wrapper verified. Use Java 21 and gradlew.bat test build.")


if __name__ == "__main__":
    main()
