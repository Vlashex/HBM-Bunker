"""Reproducible Minecraft 1.12.2 / Forge 2860 core-port builder (Python 3.11+)."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import urllib.request
import zipfile

try:
    from . import legacy_storage as storage
except ImportError:
    import legacy_storage as storage

ROOT = Path(__file__).resolve().parents[1]
VERSION = '0.2.0'
PACK_NAME = 'DEAD-EARTH-SRP-' + VERSION
MOD_NAME = 'dead-earth-srp-coordinator-' + VERSION + '.jar'
FORGE = '1.12.2-14.23.5.2860'


def combat_pins(root=ROOT):
    rows = storage.load(root / 'legacy/metadata/combat.mods.lock.json')
    original = {row['mod_id']: row for row in storage.pins(root)}
    names, ids = set(), set()
    for row in rows:
        name = row['filename']
        if (Path(name).name != name or any(c in name for c in '/\\:') or not name.endswith('.jar')
                or name.casefold() in names or row['mod_id'] in ids):
            raise ValueError('Unsafe or duplicate combat pin: ' + name)
        if row.get('minecraft') != '1.12.2' or row.get('loader') != 'forge':
            raise ValueError('Wrong runtime in combat lock: ' + name)
        if (row.get('side') not in {'client', 'server', 'both'}
                or type(row.get('size')) is not int or row['size'] <= 0
                or not re.fullmatch('[0-9a-f]{64}', row.get('sha256', ''))
                or not row.get('url', '').startswith('https://')):
            raise ValueError('Invalid combat pin: ' + name)
        if row['mod_id'] in {'overgrown_vegetation', 'mutationcraft', 'securitycraft'}:
            raise ValueError('Excluded mod in combat lock')
        if row['mod_id'] in original and row != original[row['mod_id']]:
            raise ValueError('Inherited storage pin changed: ' + row['mod_id'])
        names.add(name.casefold()); ids.add(row['mod_id'])
    if ids != set(original) | {'srparasites', 'epicsiegemod'}:
        raise ValueError('Combat lock must contain the storage pins plus SRP and Epic Siege')
    return rows


def fetch(root=ROOT):
    cache = root / '.cache/legacy'; cache.mkdir(parents=True, exist_ok=True)
    for row in combat_pins(root):
        target = cache / row['filename']
        if not target.exists():
            request = urllib.request.Request(row['url'], headers={'User-Agent': 'DeadEarthSrpCorePort/' + VERSION})
            temp = target.with_suffix('.download')
            try:
                with urllib.request.urlopen(request, timeout=120) as response:
                    with temp.open('wb') as output:
                        remaining = row['size'] + 1
                        while remaining:
                            part = response.read(min(1048576, remaining))
                            if not part: break
                            output.write(part); remaining -= len(part)
                storage.checked_bytes(temp, row)
                temp.replace(target)
            finally:
                temp.unlink(missing_ok=True)
        storage.checked_bytes(target, row)
    print('Pinned combat downloads ready; storage lock unchanged.')


def files(base):
    if not base.is_dir(): raise ValueError('Missing source directory: ' + str(base))
    result = []
    for path in sorted(base.rglob('*')):
        if path.is_symlink() or not path.resolve().is_relative_to(base.resolve()):
            raise ValueError('Linked path in build inputs: ' + str(path))
        if path.is_file(): result.append(path)
    return result


def source_hash(root=ROOT):
    digest = hashlib.sha256()
    for folder in ('src/main/java', 'src/main/resources', 'compile-only-stubs'):
        base = root / 'legacy/coordinator' / folder
        for path in files(base):
            if path.suffix in {'.pyc', '.log'}: continue
            name = path.relative_to(root).as_posix().encode('utf-8')
            data = path.read_bytes()
            digest.update(len(name).to_bytes(4, 'big')); digest.update(name)
            digest.update(len(data).to_bytes(8, 'big')); digest.update(data)
    return digest.hexdigest()


def forge_classpath(forge_dir):
    folder = Path(forge_dir).resolve(strict=True)
    forge = sorted(path for path in folder.glob('forge-' + FORGE + '*.jar')
                   if not any(part in path.name for part in ('installer', 'sources', 'javadoc')))
    minecraft = folder / 'minecraft_server.1.12.2.jar'
    if len(forge) != 1 or not minecraft.is_file() or not (folder / 'libraries').is_dir():
        raise ValueError('Use an installed Forge 2860 server folder with its Minecraft server JAR and libraries')
    jars = [forge[0], minecraft] + [path for path in files(folder / 'libraries') if path.suffix == '.jar']
    if len(jars) < 3: raise ValueError('Forge libraries are missing')
    return jars


def clean_output(path, work):
    # Validate absolute containment before recursive deletion on Windows.
    resolved, boundary = path.resolve(), work.resolve()
    if resolved.parent != boundary or resolved == boundary or path.is_symlink():
        raise ValueError('Refusing unsafe build output cleanup')
    if path.exists(): shutil.rmtree(path)
    path.mkdir(parents=True)


def argfile(path, arguments):
    # javac @files support quoted paths; forward slashes avoid Windows backslash escapes.
    text = '\n'.join('"' + str(value).replace('\\', '/').replace('"', '\\"') + '"' for value in arguments)
    path.write_text(text + '\n', encoding='utf-8')


def compile_coordinator(javac, forge_dir, tests=False, root=ROOT):
    compiler = Path(javac).resolve(strict=True)
    if not compiler.is_file(): raise ValueError('--javac must name the compiler executable')
    classpath = forge_classpath(forge_dir)
    work = root / 'work/legacy-coordinator'; work.mkdir(parents=True, exist_ok=True)
    classes = work / 'classes'; clean_output(classes, work)
    before = source_hash(root)
    sources = [p for base in ('src/main/java', 'compile-only-stubs')
               for p in files(root / 'legacy/coordinator' / base) if p.suffix == '.java']
    if not sources: raise ValueError('No coordinator sources')
    cp = os.pathsep.join(str(p) for p in classpath)
    arguments = ['--release', '8', '-encoding', 'UTF-8', '-proc:none', '-classpath', cp, '-d', classes, *sources]
    response = work / 'javac-main.args'; argfile(response, arguments)
    command = [str(compiler), '-J-Dfile.encoding=UTF-8', '@' + str(response)]
    print(subprocess.list2cmdline(command), flush=True)
    subprocess.run(command, cwd=root, check=True)
    entries = {}
    for path in files(classes):
        name = path.relative_to(classes).as_posix()
        # External compile-only API stubs can never become shipped classes.
        if path.suffix == '.class' and name.startswith('dev/deadearth/legacy/'):
            entries[name] = path.read_bytes()
    if 'dev/deadearth/legacy/DeadEarthLegacy.class' not in entries:
        raise ValueError('Coordinator entry point was not compiled')
    if 'dev/deadearth/legacy/core/LegacyCore.class' not in entries:
        raise ValueError('Core plugin was not compiled')
    for path in files(root / 'legacy/coordinator/src/main/resources'):
        name = path.relative_to(root / 'legacy/coordinator/src/main/resources').as_posix()
        if path.suffix == '.class' or name.startswith('net/minecraft/'):
            raise ValueError('Class/stub in main resources')
        if name.upper() != 'META-INF/MANIFEST.MF': entries[name] = path.read_bytes()
    entries['META-INF/MANIFEST.MF'] = ("Manifest-Version: 1.0\r\n"
        "FMLCorePlugin: dev.deadearth.legacy.core.LegacyCore\r\n"
        "FMLCorePluginContainsFMLMod: true\r\n"
        "Implementation-Title: DEAD EARTH SRP Core Port\r\n"
        "Implementation-Version: " + VERSION + "\r\n\r\n").encode('ascii')
    if source_hash(root) != before:
        raise ValueError('Sources changed during compilation; repeat the complete compile step')
    jar = storage.zip_bytes(entries)
    (work / 'mod.jar').write_bytes(jar)
    receipt = {'schema': 1, 'version': VERSION, 'sources_sha256': before,
               'jar_sha256': hashlib.sha256(jar).hexdigest(), 'javac': str(compiler),
               'forge_dir': str(Path(forge_dir).resolve()), 'command': command}
    (work / 'build.json').write_text(json.dumps(receipt, indent=2) + '\n', encoding='utf-8')
    print('Coordinator JAR: ' + str(work / 'mod.jar'))
    if tests:
        test_classes = work / 'test-classes'; clean_output(test_classes, work)
        test_sources = [p for p in files(root / 'legacy/coordinator/src/test/java') if p.suffix == '.java']
        if not test_sources: raise ValueError('No test sources')
        arguments = ['--release', '8', '-encoding', 'UTF-8', '-proc:none', '-classpath',
                     str(classes) + os.pathsep + cp, '-d', test_classes, *test_sources]
        response = work / 'javac-tests.args'; argfile(response, arguments)
        test_command = [str(compiler), '-J-Dfile.encoding=UTF-8', '@' + str(response)]
        print(subprocess.list2cmdline(test_command), flush=True)
        subprocess.run(test_command, cwd=root, check=True)
        print('Test classes compiled separately; no tests were run and none are packaged.')


def compiled_jar(root=ROOT):
    work = root / 'work/legacy-coordinator'
    receipt = storage.load(work / 'build.json')
    data = (work / 'mod.jar').read_bytes()
    if (receipt.get('schema') != 1 or receipt.get('version') != VERSION
            or receipt.get('sources_sha256') != source_hash(root)
            or receipt.get('jar_sha256') != hashlib.sha256(data).hexdigest()):
        raise ValueError('Coordinator cache is stale or altered; compile current sources first')
    with zipfile.ZipFile(work / 'mod.jar') as jar:
        if any(name.startswith('net/') or '/test/' in name or '/verification/' in name
               or name.endswith('KernelChecks.class') for name in jar.namelist()):
            raise ValueError('Compile-only/test classes must not be packaged')
    return data


def relay_script():
    return ('// DEAD EARTH SRP core port: cheap physical zone anchor.\n'
            'recipes.remove(<dead_earth:zone_relay>);\n'
            'recipes.addShaped("dead_earth_zone_relay", <dead_earth:zone_relay>, '
            '[[null, <minecraft:redstone>, null], [<ore:plankWood>, <hbm:plate_iron>, <ore:plankWood>], '
            '[null, <minecraft:cobblestone>, null]]);\n').encode('utf-8')


def entries(side, root=ROOT):
    # Reuse the established storage pack and generated ZS without rewriting that slice.
    result = storage.entries(side, root=root)
    prefix = '.minecraft/' if side == 'client' else ''
    for name in list(result):
        if name.startswith(prefix + 'mods/'): del result[name]
    for row in combat_pins(root):
        if row['side'] in {'both', side}:
            result[prefix + 'mods/' + row['filename']] = storage.checked_bytes(root / '.cache/legacy' / row['filename'], row)
    result[prefix + 'mods/' + MOD_NAME] = compiled_jar(root)
    for path in files(root / 'legacy/pack'):
        result[prefix + path.relative_to(root / 'legacy/pack').as_posix()] = path.read_bytes()
    result[prefix + 'scripts/dead_earth_zone_relay.zs'] = relay_script()
    result['README-PORT.md'] = (root / 'legacy/README-PORT.md').read_bytes()
    result['combat.mods.lock.json'] = (root / 'legacy/metadata/combat.mods.lock.json').read_bytes()
    if side == 'client':
        result['instance.cfg'] = ('[General]\nInstanceType=OneSix\n'
            'name=DEAD EARTH SRP 0.2.0 - Core Port (1.12.2)\nOverrideMemory=true\n'
            'MinMemAlloc=1024\nMaxMemAlloc=4096\n').encode('utf-8')
    else:
        for path in files(root / 'director/dead_earth_director'):
            relative = path.relative_to(root / 'director/dead_earth_director')
            if any(part in {'runtime', '__pycache__', '.venv'} for part in relative.parts): continue
            if path.suffix not in {'.py', '.js', '.css', '.html'}: continue
            result['director/dead_earth_director/' + relative.as_posix()] = path.read_bytes()
        for name in ('director.example.toml', 'models.lock.json', 'README.md', 'requirements.in', 'requirements-windows-py312.lock'):
            result['director/' + name] = (root / 'director' / name).read_bytes()
        for name in ('START-SERVER.bat', 'STOP-SERVER.bat'):
            result[name] = (root / 'legacy' / name).read_bytes()
        result['tools/legacy_server.py'] = (root / 'tools/legacy_server.py').read_bytes()
        result['legacy-port.json'] = json.dumps({'schema': 1, 'pack': 'DEAD-EARTH-SRP',
            'version': VERSION, 'runtime': 'forge-' + FORGE, 'kind': 'core-port'}, indent=2).encode('utf-8')
        result['server.properties.example'] = ("# Copy to server.properties and review before launch.\n"
            "server-ip=127.0.0.1\nserver-port=25587\nlevel-name=world\n"
            "enable-rcon=false\nrcon.port=25588\nrcon.password=\n"
            "broadcast-rcon-to-ops=false\n").encode('utf-8')
    return result


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    actions = parser.add_subparsers(dest='action', required=True)
    actions.add_parser('fetch', help='Download and verify the exact combat lock')
    compiler = actions.add_parser('compile', help='Compile current Java sources; never launch a server')
    compiler.add_argument('--javac', required=True, type=Path, help='Explicit JDK 9+ javac executable supporting --release 8')
    compiler.add_argument('--forge-dir', required=True, type=Path, help='Existing Forge 2860 dedicated-server installation')
    compiler.add_argument('--tests', action='store_true', help='Also compile src/test/java into a separate directory; do not run tests')
    actions.add_parser('build', help='Build deterministic client/server ZIPs from verified pins and a fresh compiled JAR')
    args = parser.parse_args(argv)
    try:
        if args.action == 'fetch': fetch()
        elif args.action == 'compile': compile_coordinator(args.javac, args.forge_dir, args.tests)
        else:
            for side, suffix in (('client', 'Prism'), ('server', 'Server-Overlay')):
                data = storage.zip_bytes(entries(side))
                output = ROOT / 'dist' / (PACK_NAME + '-' + suffix + '.zip')
                output.parent.mkdir(parents=True, exist_ok=True); output.write_bytes(data)
                print(str(output) + '  sha256=' + hashlib.sha256(data).hexdigest())
        return 0
    except (OSError, ValueError, KeyError, zipfile.BadZipFile, subprocess.CalledProcessError) as error:
        print('Legacy port: ' + str(error), file=sys.stderr); return 2


if __name__ == '__main__':
    if sys.version_info < (3, 11): raise SystemExit('Python 3.11 or newer is required')
    raise SystemExit(main())
