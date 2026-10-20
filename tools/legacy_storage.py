"""Build only the isolated 1.12.2 early-storage slice; never touch a live profile."""
import argparse
import hashlib
import io
import json
from pathlib import Path
import re
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[1]
CACHE = ROOT / '.cache' / 'legacy'
PACK_NAME = 'DEAD-EARTH-SRP-Early-Storage-0.1.0'
ITEM = re.compile(r'[a-z0-9_]+:[a-z0-9_./]+(?::[0-9]+)?\Z')


def load(path):
    return json.loads(path.read_text(encoding='utf-8'))


def pins(root=ROOT):
    rows = load(root / 'legacy/metadata/storage.mods.lock.json')
    seen = set()
    for row in rows:
        name = row['filename']
        if Path(name).name != name or '/' in name or '\\' in name or ':' in name or not name.endswith('.jar') or name.casefold() in seen:
            raise ValueError('Unsafe/duplicate mod filename: ' + name)
        if row.get('minecraft') != '1.12.2' or row.get('loader') != 'forge':
            raise ValueError('Modern/wrong-loader mod in legacy lock: ' + name)
        if row.get('mod_id') in {'overgrown_vegetation', 'mutationcraft', 'securitycraft'}:
            raise ValueError('Excluded mod in legacy slice: ' + name)
        if row.get('side') not in {'client', 'server', 'both'} or not re.fullmatch('[0-9a-f]{64}', row['sha256']) or row['size'] <= 0:
            raise ValueError('Invalid pin: ' + name)
        if not row['url'].startswith('https://'):
            raise ValueError('Non-HTTPS URL')
        seen.add(name.casefold())
    required = {'storagenetwork', 'hbm', 'crafttweaker', 'mixinbooter', 'jei', 'ctm'}
    if not required <= {r['mod_id'] for r in rows}:
        raise ValueError('Missing required legacy dependency')
    return rows


def checked_bytes(path, row):
    b = path.read_bytes()
    if len(b) != row['size'] or hashlib.sha256(b).hexdigest() != row['sha256']:
        raise ValueError('Pin mismatch: ' + row['filename'])
    with zipfile.ZipFile(io.BytesIO(b)) as z:
        if z.testzip() is not None or 'mcmod.info' not in z.namelist():
            raise ValueError('Invalid mod archive: ' + row['filename'])
    return b


def recipe_data(root=ROOT):
    data = load(root / 'legacy/storage/recipes.json')
    if data.get('schema') != 1:
        raise ValueError('Unknown recipe schema')
    names, outputs = set(), set()
    for r in data['recipes']:
        if not re.fullmatch('[a-z_]+', r['name']) or r['name'] in names or r['output'] in outputs:
            raise ValueError('Duplicate/unsafe recipe')
        if not ITEM.fullmatch(r['output']) or not r['output'].startswith('storagenetwork:') or not 1 <= r['count'] <= 64:
            raise ValueError('Invalid recipe output')
        if len(r['pattern']) != 3 or any(len(line) != 3 for line in r['pattern']):
            raise ValueError('Expected 3x3 pattern')
        symbols = set(''.join(r['pattern'])) - {' '}
        if symbols != set(r['key']) or any(len(k) != 1 for k in r['key']):
            raise ValueError('Unused/missing recipe symbol')
        if any(not (ITEM.fullmatch(v) or re.fullmatch(r'ore:[A-Za-z][A-Za-z0-9]*', v)) for v in r['key'].values()):
            raise ValueError('Invalid ingredient')
        names.add(r['name']); outputs.add(r['output'])
    return data['recipes']


def script(root=ROOT):
    lines = ['// Generated from legacy/storage/recipes.json; restart Minecraft after changes.', '// No power, Nether materials, circuits or gems in the base network.', '']
    for r in recipe_data(root):
        lines.append('recipes.remove(<%s>);' % r['output'])
        grid = []
        for row in r['pattern']:
            grid.append('[' + ', '.join('null' if c == ' ' else '<%s>' % r['key'][c] for c in row) + ']')
        lines.append('recipes.addShaped("dead_earth_early_storage_%s", <%s> * %s, [%s]);' % (r['name'], r['output'], r['count'], ', '.join(grid)))
        lines.append('')
    return '\n'.join(lines).encode('utf-8')


def entries(side, root=ROOT, cache=None):
    if side not in {'client', 'server'}:
        raise ValueError('Invalid target')
    cache = cache or root / '.cache/legacy'
    result = {}
    prefix = '.minecraft/' if side == 'client' else ''
    for row in pins(root):
        if row['side'] in {'both', side}:
            result[prefix + 'mods/' + row['filename']] = checked_bytes(cache / row['filename'], row)
    for p in sorted((root / 'legacy/storage/pack').rglob('*')):
        if p.is_file():
            if p.is_symlink():
                raise ValueError('Symlink in pack')
            result[prefix + p.relative_to(root / 'legacy/storage/pack').as_posix()] = p.read_bytes()
    result[prefix + 'scripts/dead_earth_early_storage.zs'] = script(root)
    result['README-EARLY-STORAGE.md'] = (root / 'legacy/README.md').read_bytes()
    result['storage.mods.lock.json'] = (root / 'legacy/metadata/storage.mods.lock.json').read_bytes()
    runtime = load(root / 'legacy/metadata/runtime.json')
    if side == 'client':
        result['mmc-pack.json'] = json.dumps({'formatVersion': 1, 'components': [
            {'uid': 'net.minecraft', 'version': runtime['minecraft']},
            {'uid': 'net.minecraftforge', 'version': runtime['forge']}]}, indent=2).encode()
        result['instance.cfg'] = ('[General]\nInstanceType=OneSix\nname=DEAD EARTH SRP — Early Storage Test\nOverrideMemory=true\nMinMemAlloc=1024\nMaxMemAlloc=4096\n').encode('utf-8')
    else:
        result['forge-runtime.json'] = (root / 'legacy/metadata/runtime.json').read_bytes()
    return result


def zip_bytes(data):
    b = io.BytesIO()
    with zipfile.ZipFile(b, 'w', zipfile.ZIP_DEFLATED, compresslevel=6) as z:
        for name, value in sorted(data.items()):
            info = zipfile.ZipInfo(name, (1980, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o100644 << 16
            z.writestr(info, value)
    return b.getvalue()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=['fetch', 'build', 'check'])
    action = parser.parse_args().action
    if action == 'fetch':
        CACHE.mkdir(parents=True, exist_ok=True)
        for row in pins():
            p = CACHE / row['filename']
            if not p.exists():
                req = urllib.request.Request(row['url'], headers={'User-Agent': 'DeadEarthFork/1.0'})
                with urllib.request.urlopen(req, timeout=120) as response:
                    data = response.read()
                temp = p.with_suffix('.download'); temp.write_bytes(data)
                checked_bytes(temp, row); temp.replace(p)
            checked_bytes(p, row)
        print('Pinned downloads ready')
    else:
        for side in ['client', 'server']:
            data = entries(side)
            if action == 'build':
                suffix = 'Prism' if side == 'client' else 'Server-Overlay'
                output = ROOT / 'dist' / (PACK_NAME + '-' + suffix + '.zip')
                output.parent.mkdir(exist_ok=True)
                output.write_bytes(zip_bytes(data))
                print(output)
            else:
                print(side, len(data), 'entries verified')


if __name__ == '__main__':
    main()
