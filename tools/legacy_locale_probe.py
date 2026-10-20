"""Headless native Forge 1.12.2 language-loader verification for a packaged coordinator JAR.

Uses real FMLFileResourcePack, native metadata parsing, LegacyV2Adapter selection,
SimpleReloadableResourceManager and Locale. It never starts Minecraft.main, loads
mods into a running game, opens a display, or changes an installed profile.
Requires the existing pinned .cache/legacy-client JAR/libraries and a JDK.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[1]


def main():
    if hasattr(sys.stdout, 'reconfigure'):
        sys.stdout.reconfigure(encoding='utf-8')
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('jar', type=Path)
    parser.add_argument('--language', default='ru_ru', choices=['ru_ru', 'en_us'])
    parser.add_argument('--java', type=Path)
    parser.add_argument('--javac', type=Path)
    parser.add_argument('--expect', choices=['translated', 'legacy-missing'], default='translated')
    args = parser.parse_args()
    jar = args.jar.resolve(strict=True)
    java = args.java or shutil.which('java')
    javac = args.javac or shutil.which('javac')
    if not java or not javac:
        parser.error('A JDK is required; pass --java and --javac explicitly.')
    cache = ROOT / '.cache/legacy-client'
    client = cache / 'client-1.12.2.jar'
    forge = cache / 'libraries/net/minecraftforge/forge/1.12.2-14.23.5.2860/forge-1.12.2-14.23.5.2860.jar'
    libraries = sorted((cache / 'libraries').rglob('*.jar'))
    if not client.is_file() or not forge.is_file() or not libraries:
        parser.error('Existing pinned client/Forge library cache is required; this helper downloads nothing.')
    work = ROOT / 'work/legacy-locale-probe'
    work.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='run-', dir=work) as temporary:
        output = Path(temporary)
        if output.resolve().parent != work.resolve() or output.is_symlink():
            raise ValueError("Temporary probe output escaped its owned work folder")
        classpath = os.pathsep.join(str(path) for path in [client, *libraries])
        compile_result = subprocess.run([str(javac), '--release', '8', '-Xlint:-options', '-encoding', 'UTF-8',
                                         '-proc:none', '-classpath', classpath, '-d', str(output),
                                         str(ROOT / 'tools/LegacyLocaleProbe.java')], cwd=output,
                                        stdout=subprocess.PIPE, stderr=subprocess.PIPE, encoding='utf-8', errors='replace')
        if compile_result.returncode:
            print(compile_result.stdout + compile_result.stderr)
            return compile_result.returncode
        run = subprocess.run([str(java), '-Djava.awt.headless=true', '-Dfile.encoding=UTF-8', '-classpath',
                              os.pathsep.join([str(output), classpath]), 'dev.deadearth.legacyverify.LegacyLocaleProbe', str(jar), args.language],
                             cwd=output, stdout=subprocess.PIPE, stderr=subprocess.PIPE, encoding='utf-8', errors='replace')
        if run.returncode:
            print(run.stdout + run.stderr)
            return run.returncode
        lines = [line for line in run.stdout.splitlines() if line.startswith('{"schema":1,')]
        if len(lines) != 1:
            print(run.stdout + run.stderr)
            raise RuntimeError('Native probe did not produce one result record')
        report = json.loads(lines[0])
        report['jarSha256'] = hashlib.sha256(jar.read_bytes()).hexdigest()
        report['expectedResult'] = args.expect
        accepted = (report['passed'] if args.expect == 'translated' else
                    report['packFormat'] == 2 and report['resolvedNames'] == 0 and report['unresolvedNames'] > 0)
        report['expectationMet'] = accepted
        print(json.dumps(report, ensure_ascii=False, indent=2))
        return 0 if accepted else 1


if __name__ == '__main__':
    raise SystemExit(main())
