"""Publish explicitly requested SRP archives; credentials remain only in memory.

The source commit/tag must already be pushed. Existing published assets are never replaced.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import urllib.error
import urllib.parse
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
API = 'https://api.github.com/repos/Vlashex/HBM-Bunker'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--version', required=True)
    parser.add_argument('--notes', required=True, type=Path)
    parser.add_argument('--publish', action='store_true')
    args = parser.parse_args()
    if not re.fullmatch(r'1\.[0-9]+\.[0-9]+', args.version):
        raise ValueError('Explicit SRP version required')
    tag = 'srp-v' + args.version
    assets = [ROOT / 'dist' / ('DEAD-EARTH-SRP-' + args.version + '-' + suffix + '.zip')
              for suffix in ('Prism', 'TLauncher', 'Server-Overlay')]
    rows = []
    for path in assets:
        data = path.read_bytes()
        if not data.startswith(b'PK'):
            raise ValueError('Not a ZIP archive')
        rows.append({'name': path.name, 'size': len(data), 'sha256': hashlib.sha256(data).hexdigest()})
    checksum = ROOT / 'dist' / ('SHA256SUMS-' + args.version + '.txt')
    checksum.write_text(''.join(row['sha256'] + '  ' + row['name'] + '\n' for row in rows), encoding='ascii')
    assets.append(checksum)
    if not args.publish:
        print(json.dumps({'tag': tag, 'archives': rows, 'published': False}, indent=2))
        return
    if subprocess.check_output(['git', 'status', '--porcelain'], cwd=ROOT, text=True).strip():
        raise ValueError('Commit all release sources before publishing')
    commit = subprocess.check_output(['git', 'rev-parse', tag + '^{commit}'], cwd=ROOT, text=True).strip()
    result = subprocess.run(['git', 'credential', 'fill'], cwd=ROOT,
        input='protocol=https\nhost=github.com\n\n', capture_output=True, text=True, check=True)
    fields = dict(line.split('=', 1) for line in result.stdout.splitlines() if '=' in line)
    token = fields['password']

    def request(url, method='GET', data=None, kind='application/json'):
        if data is not None and not isinstance(data, bytes):
            data = json.dumps(data, ensure_ascii=False).encode('utf-8')
        headers = {'Authorization': 'Bearer ' + token, 'Accept': 'application/vnd.github+json',
                   'User-Agent': 'DeadEarthSrpRelease', 'Content-Type': kind,
                   'X-GitHub-Api-Version': '2022-11-28'}
        req = urllib.request.Request(url, data=data, headers=headers, method=method)
        try:
            with urllib.request.urlopen(req, timeout=240) as response:
                return json.load(response)
        except urllib.error.HTTPError as error:
            if error.code == 404:
                return None
            # Never include request headers, credentials or an entire remote response.
            raise RuntimeError('GitHub request failed, HTTP ' + str(error.code)) from None

    release = request(API + '/releases/tags/' + tag)
    if release is None:
        release = request(API + '/releases', 'POST', {
            'tag_name': tag, 'target_commitish': commit,
            'name': 'DEAD EARTH SRP ' + args.version,
            'body': args.notes.read_text(encoding='utf-8'), 'draft': True, 'prerelease': False,
            'make_latest': 'true'})
    existing = {row['name']: row for row in release['assets']}
    for path in assets:
        data = path.read_bytes()
        digest = 'sha256:' + hashlib.sha256(data).hexdigest()
        if path.name in existing:
            row = existing[path.name]
            if row['size'] != len(data) or row.get('digest') != digest:
                raise ValueError('Existing asset differs; no replacement permitted: ' + path.name)
            continue
        if not release['draft']:
            raise ValueError('Cannot add missing files to an already published release')
        url = release['upload_url'].split('{', 1)[0]
        if urllib.parse.urlparse(url).hostname != 'uploads.github.com':
            raise ValueError('Unexpected upload host')
        row = request(url + '?' + urllib.parse.urlencode({'name': path.name}),
                      'POST', data, 'application/octet-stream')
        if row['size'] != len(data) or row.get('digest') != digest:
            raise ValueError('Uploaded asset checksum mismatch')
        print('Verified upload: ' + path.name, flush=True)
    release = request(API + '/releases/' + str(release['id']))
    if {row['name'] for row in release['assets']} != {p.name for p in assets}:
        raise ValueError('Release asset set differs from the reviewed archive set')
    if release['draft']:
        release = request(API + '/releases/' + str(release['id']), 'PATCH',
                          {'draft': False, 'make_latest': 'true'})
    print(json.dumps({'tag': tag, 'url': release['html_url'], 'draft': release['draft'],
                      'assets': len(release['assets'])}))


if __name__ == '__main__':
    main()
