"""Create a new isolated loopback performance installation from pinned pack files."""
from pathlib import Path
import argparse
import json
import secrets
import shutil
import sys

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))
from tools import release

def prepare(output):
    output.mkdir(parents=True, exist_ok=False)
    (output / '.dead-earth-loadtest').write_text('isolated performance test\n', encoding='utf-8')
    (output / 'phase.txt').write_text('startup', encoding='ascii')
    (output / 'shader.txt').write_text('on', encoding='ascii')
    rows = release.read_json(ROOT / 'metadata/mods.lock.json')
    settings = release.read_json(ROOT / 'release.json')
    patch = release.zip_bytes(release.tree_entries(ROOT / 'pack/patch'))
    directories = {}
    for side in ('server', 'client'):
        dest = ROOT / 'mods/dead-earth' / f'run-{side}-{output.name}'
        dest.mkdir(exist_ok=False)
        directories[side] = str(dest)
        (dest / 'mods').mkdir()
        for row in release.mods_for_side(rows, side):
            source = ROOT / '.cache/mods' / row['filename']
            release.verify_file(source, row)
            shutil.copy2(source, dest / 'mods' / row['filename'])
        (dest / 'mods' / settings['patch_filename']).write_bytes(patch)
        shutil.copytree(ROOT / 'pack/config', dest / 'config')
        # Exclude one-time intro generation; HBM structures/normal wasteland remain enabled.
        (dest / 'config/outpostzero-server.toml').write_text(
            'starterBunkerEnabled = false\nadditionalBunkersEnabled = false\n', encoding='ascii')
        if side == 'server':
            props = '\n'.join([
                'server-ip=127.0.0.1', 'server-port=25568', 'online-mode=false',
                'enforce-secure-profile=false', 'enable-rcon=true', 'rcon.port=25578',
                'rcon.password=' + secrets.token_hex(24), 'broadcast-rcon-to-ops=false',
                'level-name=performance_test', 'level-type=minecraft:normal', 'level-seed=7355319',
                'generate-structures=true', 'view-distance=8', 'simulation-distance=8',
                'spawn-protection=0', 'max-players=2', 'difficulty=normal',
                'motd=DEAD EARTH isolated performance test',
            ]) + '\n'
            (dest / 'server.properties').write_text(props, encoding='ascii')
            (dest / 'eula.txt').write_text('eula=true\n', encoding='ascii')
        else:
            for name, data in release.tree_entries(ROOT / 'pack/client-config').items():
                path = dest / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(data)
            (dest / 'shaderpacks').mkdir(exist_ok=True)
            for row in release.read_json(ROOT / 'metadata/shaders.lock.json'):
                source = ROOT / '.cache/shaders' / row['filename']
                release.verify_file(source, row)
                shutil.copy2(source, dest / 'shaderpacks' / row['filename'])
            (dest / 'options.txt').write_text('\n'.join([
                'enableVsync:false', 'maxFps:260', 'renderDistance:8', 'simulationDistance:8',
                'fullscreen:false', 'pauseOnLostFocus:false', 'guiScale:3', 'lang:ru_ru',
                'renderClouds:false', 'entityDistanceScaling:1.0', 'fov:70.0',
            ]) + '\n', encoding='ascii')
    (output / 'installation.json').write_text(json.dumps(dict(
        directories=directories, version=settings['version'], minecraft=settings['minecraft'],
        neoforge=settings['neoforge'], seed=7355319, port=25568, rconPort=25578,
        coResidentLiveGame=True, computerUse=False, screenshots=False,
    ), indent=2) + '\n', encoding='utf-8')
    print(json.dumps(dict(output=str(output), directories=directories, fresh=True)))

if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('output', type=Path)
    prepare(parser.parse_args().output.resolve())
