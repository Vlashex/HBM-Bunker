"""Local-only bunker gallery. Builds a fresh single-player client; never deploys a server."""
import argparse
from collections import Counter, deque
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import uuid
import zipfile

ROOT = Path(__file__).resolve().parents[1]
WALL = 'dead_earth:defense_wall_v1'
AIR = 'minecraft:air'
STAIRS = 'minecraft:stone_brick_stairs'
DOOR = 'dead_earth:defense_door_v1'

class Gallery:
    def __init__(self):
        self.blocks = {}
        self.signs = []
        self.chests = []
        self.variants = []
        self.checks = []

    def put(self, x, y, z, block, meta=0):
        self.blocks[(x, y, z)] = (block, meta)

    def fill(self, x1, y1, z1, x2, y2, z2, block, meta=0):
        for x in range(x1, x2 + 1):
            for y in range(y1, y2 + 1):
                for z in range(z1, z2 + 1):
                    self.put(x, y, z, block, meta)

    def room(self, cx, x1, z1, x2, z2, floor, roof):
        self.fill(cx+x1, floor, z1, cx+x2, roof, z2, WALL)
        self.fill(cx+x1+1, floor+1, z1+1, cx+x2-1, roof-1, z2-1, AIR)
        self.fill(cx+x1+1, floor, z1+1, cx+x2-1, floor, z2-1, 'minecraft:stone', 6)
        for x in range(x1+2, x2-1, 4):
            for z in range(z1+2, z2-1, 5):
                self.put(cx+x, roof, z, 'minecraft:sea_lantern')
        for x in (x1, x2):
            for z in (z1, z2):
                self.fill(cx+x, floor, z, cx+x, roof, z, 'minecraft:concrete', 7)

    def door(self, cx, x, y, z, double=False):
        for side in range(2 if double else 1):
            self.put(cx+x+side, y, z, DOOR, 3)
            self.put(cx+x+side, y+1, z, DOOR, 8+side)
            self.checks.extend([(cx+x+side, y, z), (cx+x+side, y+1, z)])

    def stairs(self, cx, z0, top_floor, bottom_floor, width=1):
        for i in range(top_floor-bottom_floor):
            z, y = z0+i, top_floor-i
            self.fill(cx-width-1, y-1, z, cx+width+1, y+4, z, WALL)
            self.fill(cx-width, y+1, z, cx+width, y+3, z, AIR)
            for x in range(-width, width+1):
                self.put(cx+x, y, z, STAIRS, 3)
                self.checks.append((cx+x, y+2, z))
            self.put(cx, y+4, z, 'minecraft:sea_lantern' if i%3==1 else WALL)

    def sign(self, x, y, z, lines, meta=8):
        self.put(x, y, z, 'minecraft:standing_sign', meta)
        self.signs.append(dict(x=x,y=y,z=z,lines=lines))

    def kit(self, x, y, z):
        self.put(x,y,z,'minecraft:chest',2)
        self.chests.append(dict(x=x,y=y,z=z,items=[
            dict(id='dead_earth:zone_relay',count=6),
            dict(id='minecraft:dirt',count=16),dict(id='minecraft:sapling',count=8)]))

    def bed(self, x, y, z):
        self.put(x,y,z,'minecraft:bed',0)
        self.put(x,y,z+1,'minecraft:bed',8)

    def fit_out(self, cx, floor, back, beds=6):
        y=floor+1
        for n in range(beds):
            self.bed(cx-6+(n%3)*2,y,4+(n//3)*5)
        for x,z,block,meta in [(5,4,'minecraft:crafting_table',0),(5,5,'minecraft:furnace',4),
                                (5,7,'minecraft:chest',4),(5,8,'minecraft:chest',4),
                                (4,back-2,'minecraft:cauldron',0)]:
            self.put(cx+x,y,z,block,meta)
        self.kit(cx+3,y,back-1)
        for x in (-7,7):
            for z in range(3,back,4):
                self.put(cx+x,floor+3,z,'minecraft:sea_lantern')
        # Open circulation: sleeping and workshop bays stay on either side of the stair landing.
        self.sign(cx+3,y,3,['МАСТЕРСКАЯ','место под HBM','и хранение',''])
        self.sign(cx-5,y,2,['ЖИЛОЙ ОТСЕК','6 мест','',''])
        self.sign(cx+3,y,back-3,['ОБЩИЙ ЗАПАС','6 загрузчиков','земля / саженцы',''])

    def upper(self, cx, half, front, back, floor=63, roof=68, double=False):
        self.room(cx,-half,front,half,back,floor,roof)
        self.door(cx,0,floor+1,front,double)
        for x in (-half+1,half-1):
            self.put(cx+x,floor+2,front,'minecraft:stained_glass',15)
        for z in range(front+2,back,3):
            for x in (-half,half):
                self.put(cx+x,floor+2,z,'minecraft:stained_glass',15)
        # Shallow roof overhang, vents, steel corner ribs and a clearly marked doorway.
        for x in range(-half-1,half+2):
            for z in range(front-1,back+2):
                if x in (-half-1,half+1) or z in (front-1,back+1):
                    self.put(cx+x,roof,z,'minecraft:stone_slab',5)
        for x in (-half,half):
            self.fill(cx+x,roof+1,back-1,cx+x,roof+2,back-1,'minecraft:iron_bars')
            self.put(cx+x,roof+3,back-1,'minecraft:stone_slab',5)
        for x in range(-2,3):
            self.put(cx+x,floor,front-1,'minecraft:concrete',4 if x%2 else 15)
        self.put(cx,floor+3,front,'minecraft:sea_lantern')

    def escape(self,cx,floor,z):
        self.fill(cx+5,floor,z-1,cx+7,63,z+1,WALL)
        for y in range(floor+1,63):
            self.put(cx+6,y,z,'minecraft:ladder',4)
        self.door(cx,6,floor+1,z-1)
        self.put(cx+6,63,z,'dead_earth:defense_hatch_v1',3)
        self.sign(cx+4,64,z+2,['АВАРИЙНЫЙ','ВЫХОД','',''])

    def terrain(self):
        palette=[('minecraft:stone',0),('dead_earth:ash',0),('minecraft:gravel',0),
                 ('minecraft:stone',5),('minecraft:cobblestone',0)]
        for x in range(-83,84):
            for z in range(-24,37):
                value=((x*73428767)^(z*912931))&0xffff
                block,meta=palette[(value//97)%len(palette)]
                self.put(x,63,z,block,meta)
                if -19<=z<=-17:
                    self.put(x,63,z,'minecraft:stone',6)
        # Small dead-world hollows outside the gallery buildings and walking route.
        for cx,cz,r in [(-75,24,3),(-29,29,4),(30,29,3),(76,25,3)]:
            for x in range(cx-r,cx+r+1):
                for z in range(cz-r,cz+r+1):
                    d=(x-cx)**2+(z-cz)**2
                    if d<r*r:
                        self.put(x,63,z,AIR)
                        if d<r*r//3:self.put(x,62,z,AIR)
        for cx in (-56,0,56):
            self.fill(cx-13,64,-14,cx+13,74,24,AIR)

    def build(self):
        self.terrain()
        self.room(-56,-7,-1,7,14,55,60)
        self.upper(-56,3,-9,-3)
        self.stairs(-56,-4,63,55)
        self.fit_out(-56,55,14)
        self.escape(-56,55,12)
        self.sign(-60,64,-12,['A / КПП-17','вход 7 x 7','подвал 15 x 16','лестница / выход'])
        self.variants.append(dict(id='A',name='КПП-17',centerX=-56,upper='7 x 7',lower='15 x 16',
            entrance=[-56,64,-12],landing=[-56,56,7],description='Компактный входной пост; общий зал с жилой и рабочей сторонами.'))

        self.room(0,-8,-2,8,11,55,60)
        self.room(0,0,9,9,17,55,60)
        # Open the overlap into the small rear annex, keeping a broad passage.
        self.fill(2,56,9,7,59,11,AIR)
        self.upper(0,4,-10,-4,double=True)
        self.stairs(0,-5,63,55)
        self.fit_out(0,55,11)
        # Dedicated rear growing/utility bay; soil is inert until the player uses it.
        self.fill(2,55,13,4,55,15,'minecraft:dirt')
        self.put(7,56,15,'minecraft:crafting_table')
        self.sign(6,56,13,['ТЕХНИЧЕСКИЙ','ОТСЕК','грядки / станки',''])
        self.escape(0,55,15)
        self.sign(-5,64,-13,['B / УЗЕЛ-6','вход 9 x 7','два крыла','подземная ниша'])
        self.variants.append(dict(id='B',name='Узел-6',centerX=0,upper='9 x 7',lower='18 x 20 (Г-образный)',
            entrance=[0,64,-13],landing=[0,56,7],description='Павильон с широким входом, жилым крылом и техническим карманом.'))

        self.room(56,-7,1,7,15,54,59)
        self.upper(56,5,-6,2,floor=61,roof=66)
        self.stairs(56,-2,61,54)
        self.fit_out(56,54,15)
        self.escape(56,54,13)
        # The upper room is two blocks below grade; a real descending stair leads to it.
        for i in range(2):
            z,y=-10+i,63-i
            self.fill(54,y-1,z,58,65,z,'minecraft:concrete',7)
            self.fill(55,y+1,z,57,66,z,AIR)
            for x in (55,56,57):self.put(x,y,z,STAIRS,3)
        self.fill(55,61,-8,57,61,-7,'minecraft:stone',6)
        self.fill(55,62,-8,57,66,-7,AIR)
        for side in (-1,1):
            for distance in range(6,10):
                h=66-(distance-6)
                for z in range(-6,4):
                    self.fill(56+side*distance,63,z,56+side*distance,h,z,'minecraft:stone')
                    self.put(56+side*distance,h,z,'dead_earth:ash')
        self.sign(48,64,-12,['C / РЕДУТ','заглублённый вход','верх 11 x 9','подвал 15 x 15'])
        self.variants.append(dict(id='C',name='Редут',centerX=56,upper='11 x 9 (полузаглублённый)',lower='15 x 15',
            entrance=[56,64,-13],landing=[56,55,7],description='Низкий силуэт с земляными бортами, входным спуском и нижним убежищем.'))
        for v in self.variants:
            self.checks.append((v['centerX'],v['landing'][1]-1,v['landing'][2]))
        return self

    def state(self,x,y,z):
        return self.blocks.get((x,y,z),('minecraft:stone',0) if y<=63 else (AIR,0))

    def route(self,v):
        # Verify walkable two-block clearance and <=1-block steps from outside to the lower hall.
        cx=v['centerX'];start=tuple(v['entrance']);end=tuple(v['landing'])
        passable={AIR,DOOR,'minecraft:standing_sign','minecraft:ladder'}
        def free(p):
            x,y,z=p
            return (cx-11<=x<=cx+11 and -15<=z<=19 and 53<=y<=70
                    and self.state(x,y,z)[0] in passable and self.state(x,y+1,z)[0] in passable
                    and self.state(x,y-1,z)[0] not in passable)
        assert free(start) and free(end),('Blocked route endpoint',v['id'])
        todo=deque([start]);seen={start}
        while todo:
            p=todo.popleft()
            if p==end:return len(seen)
            x,y,z=p
            for dx,dz in [(1,0),(-1,0),(0,1),(0,-1)]:
                for dy in (0,1,-1):
                    q=(x+dx,y+dy,z+dz)
                    if q not in seen and free(q):seen.add(q);todo.append(q)
        raise AssertionError('No walking route into bunker '+v['id'])

    def document(self,run_id,save_folder):
        routes={v['id']:self.route(v) for v in self.variants}
        assert len(self.blocks)<100000
        assert all(self.state(c['x'],c['y'],c['z'])[0]=='minecraft:chest' for c in self.chests)
        assert all(self.state(c['x'],c['y'],c['z'])[0]=='minecraft:standing_sign' for c in self.signs)
        checks=[]
        for x,y,z in dict.fromkeys(self.checks):
            block,meta=self.state(x,y,z);checks.append(dict(x=x,y=y,z=z,id=block))
        return dict(schema=1,kind='dead-earth-bunker-preview',runId=run_id,saveFolder=save_folder,
            title='DEAD EARTH - Bunker Gallery',seed=172021,variants=self.variants,
            blocks=[dict(x=x,y=y,z=z,id=b,meta=m) for (x,y,z),(b,m) in self.blocks.items()],
            signs=self.signs,chests=self.chests,checks=checks,walkability=routes)


def prepare(source_client,javac):
    from tools import legacy_port
    source_client=source_client.resolve(strict=True)
    if source_client.parent!=ROOT/'work' or not source_client.name.startswith('legacy-interactive-client-'):
        raise ValueError('Use an owned isolated interactive client as read-only runtime source')
    src=json.loads((source_client/'interactive-client.json').read_text(encoding='utf-8'))
    if src.get('kind')!='dead-earth-interactive-client':raise ValueError('Unknown client runtime')
    for path in map(Path,src['classpath']):
        expected=src['inputHashes'].get(str(path.resolve()))
        if expected and hashlib.sha256(path.read_bytes()).hexdigest()!=expected:raise ValueError('Client library hash changed')
    run_id=str(uuid.uuid4());save='bunker-gallery-'+run_id[:8]
    target=ROOT/'work'/('bunker-preview-'+datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%SZ')+'-'+run_id[:8])
    game=target/'game';game.mkdir(parents=True,exist_ok=False)
    for name,data in legacy_port.entries('client').items():
        if name.startswith('.minecraft/'):
            dest=game/name[len('.minecraft/'):];dest.parent.mkdir(parents=True,exist_ok=True);dest.write_bytes(data)
    # Only this private client uses the calm, creative gallery configuration.
    for name in ['coordinator','climate','worldgen']:
        path=game/'config/dead-earth'/f'{name}.json';data=json.loads(path.read_text(encoding='utf-8'));data['enabled']=False
        path.write_text(json.dumps(data,indent=2)+'\n',encoding='utf-8')
    controls=Gallery().build().document(run_id,save)
    (game/'bunker-preview.json').write_text(json.dumps(controls,ensure_ascii=False,separators=(',',':')),encoding='utf-8')
    world=game/'saves'/save;world.mkdir(parents=True)
    (world/'.dead-earth-bunker-preview').write_text(run_id,encoding='utf-8')
    (game/'options.txt').write_text('fullscreen:false\noverrideWidth:1440\noverrideHeight:900\npauseOnLostFocus:false\nrenderDistance:8\nmaxFps:60\ngamma:0.8\nclouds:false\nenableVsync:false\nlang:ru_ru\ndifficulty:0\n',encoding='utf-8')
    classes=target/'preview-classes';classes.mkdir()
    forge=ROOT/'work/storage-server-20261020'
    cp=os.pathsep.join([str(ROOT/'work/legacy-coordinator/mod.jar')]+[str(p) for p in forge.rglob('*.jar')])
    args=['--release','8','-encoding','UTF-8','-proc:none','-classpath',cp,'-d',str(classes),str(ROOT/'legacy/bunker-preview/Preview.java')]
    argfile=target/'javac.args';argfile.write_text('\n'.join('"'+a.replace('\\','/')+'"' for a in args),encoding='utf-8')
    subprocess.run([str(javac),'-J-Dfile.encoding=UTF-8','@'+str(argfile)],cwd=ROOT,check=True)
    jar=game/'mods/dead-earth-bunker-preview.jar'
    with zipfile.ZipFile(jar,'w',zipfile.ZIP_DEFLATED) as archive:
        for p in classes.rglob('*.class'):archive.write(p,p.relative_to(classes).as_posix())
    marker=dict(schema=1,kind='dead-earth-local-bunker-preview',runId=run_id,gameDirectory=str(game),
        saveFolder=save,sourceClient=str(source_client),classpath=src['classpath'],assetIndex=src['assetIndex'],
        assetsDirectory=str(source_client/'assets'),nativesDirectory=str(source_client/'natives'),
        variants=controls['variants'],blockCount=len(controls['blocks']),walkability=controls['walkability'],
        inputHashes={str(p):hashlib.sha256(p.read_bytes()).hexdigest() for p in (game/'mods').glob('*.jar')},
        dedicatedServerChanged=False,autoQuit=False)
    (target/'preview-client.json').write_text(json.dumps(marker,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    (ROOT/'work/current-bunker-preview.json').write_text(json.dumps(dict(directory=str(target),**marker),ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print(json.dumps({'prepared':str(target),'blockCount':marker['blockCount'],'variants':len(marker['variants']),'walkability':marker['walkability'],'serverChanged':False}))
    return target


def launch(target,java8):
    target=target.resolve(strict=True)
    if target.parent!=ROOT/'work' or not target.name.startswith('bunker-preview-'):raise ValueError('Not an isolated preview')
    marker=json.loads((target/'preview-client.json').read_text(encoding='utf-8'))
    prior=target/'client-process.json'
    if prior.is_file():
        from tools.legacy_install import _pid_running
        old=json.loads(prior.read_text(encoding='utf-8'))
        if old.get('gameDirectory')==str(target/'game') and _pid_running(old.get('pid')):
            print(json.dumps(dict(state='already_running',pid=old['pid'],singleplayer=True)));return
    from tools.legacy_server import is_java8
    if not is_java8(java8):raise ValueError('Preview requires an explicit Java 8 runtime')
    if marker.get('kind')!='dead-earth-local-bunker-preview':raise ValueError('Unknown preview marker')
    for name,h in marker['inputHashes'].items():
        if hashlib.sha256(Path(name).read_bytes()).hexdigest()!=h:raise ValueError('Prepared preview changed')
    game=target/'game';native=marker['nativesDirectory'];name='Vesuk'
    player_id=uuid.UUID(bytes=hashlib.md5(('OfflinePlayer:'+name).encode()).digest(),version=3)
    command=[str(java8),'-Xms512M','-Xmx4G','-Djava.library.path='+native,'-Dorg.lwjgl.librarypath='+native,
        '-Dnet.java.games.input.librarypath='+native,'-Dlog4j2.formatMsgNoLookups=true','-Ddeadearth.bunkerPreview=true',
        '-cp',os.pathsep.join(marker['classpath']),'net.minecraft.launchwrapper.Launch','--username',name,
        '--version','1.12.2-forge-14.23.5.2860','--gameDir',str(game),'--assetsDir',marker['assetsDirectory'],
        '--assetIndex',marker['assetIndex'],'--uuid',player_id.hex,'--accessToken','0','--userType','legacy',
        '--tweakClass','net.minecraftforge.fml.common.launcher.FMLTweaker','--versionType','Forge','--width','1440','--height','900']
    with (target/'client-console.log').open('a',encoding='utf-8') as log:
        p=subprocess.Popen(command,cwd=game,stdout=log,stderr=subprocess.STDOUT,creationflags=0x08000000)
    result=dict(schema=1,pid=p.pid,gameDirectory=str(game),singleplayer=True,autoQuit=False,serverChanged=False)
    (target/'client-process.json').write_text(json.dumps(result,indent=2)+'\n',encoding='utf-8');print(json.dumps(result))


def main():
    parser=argparse.ArgumentParser(description=__doc__);sub=parser.add_subparsers(dest='action',required=True)
    sub.add_parser('layout-check')
    p=sub.add_parser('prepare');p.add_argument('--source-client',type=Path,required=True);p.add_argument('--javac',type=Path,required=True)
    p=sub.add_parser('launch');p.add_argument('--directory',type=Path,required=True);p.add_argument('--java8',type=Path,required=True)
    args=parser.parse_args()
    if args.action=='layout-check':
        doc=Gallery().build().document(str(uuid.uuid4()),'test-gallery');print(json.dumps(dict(blockCount=len(doc['blocks']),walkability=doc['walkability'],variants=3)));return
    if args.action=='prepare':prepare(args.source_client,args.javac)
    if args.action=='launch':launch(args.directory,args.java8)

if __name__=='__main__':main()
