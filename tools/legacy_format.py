"""Pinned development-only Java formatter. Never downloads tools or changes game data."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[1]


def source_files(root=ROOT):
    base = root / 'legacy/coordinator/src'
    result = []
    for group in ('main', 'test', 'integration', 'clientintegration', 'combattest', 'relaytest'):
        folder = base / group / 'java'
        if not folder.is_dir():
            continue
        for path in sorted(folder.rglob('*.java')):
            if path.is_symlink() or not path.resolve().is_relative_to(folder.resolve()):
                raise ValueError('Linked formatter input')
            result.append(path)
    return result


def run(java, write=False, root=ROOT):
    lock = json.loads((root / 'legacy/metadata/formatter.lock.json').read_text(encoding='utf-8'))
    jar = root / '.cache/tools' / (lock['name'] + '-' + lock['version'] + '-all-deps.jar')
    data = jar.read_bytes()
    if len(data) != lock['size'] or hashlib.sha256(data).hexdigest() != lock['sha256']:
        raise ValueError('Formatter hash mismatch')
    java = Path(java).resolve(strict=True)
    paths = source_files(root)
    # Bound command length on Windows, preserving one formatter version for the whole tree.
    for offset in range(0, len(paths), 20):
        subprocess.run([str(java), '-jar', str(jar),
                        *(['--replace'] if write else ['--dry-run', '--set-exit-if-changed']),
                        *map(str, paths[offset:offset + 20])], check=True, cwd=root)
    print(('Formatted ' if write else 'Checked ') + str(len(paths)) + ' Java sources')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--java', type=Path, required=True, help='Explicit JDK 17+ Java executable')
    parser.add_argument('--write', action='store_true')
    args = parser.parse_args()
    run(args.java, args.write)


if __name__ == '__main__':
    main()
