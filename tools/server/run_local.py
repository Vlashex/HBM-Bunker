from pathlib import Path
import json,msvcrt,os,socket,subprocess,threading,time,urllib.request
ROOT=Path(__file__).resolve().parent
config=json.loads((ROOT/'deployment.json').read_text(encoding='utf-8'))
HIDDEN=subprocess.CREATE_NO_WINDOW
os.chdir(ROOT)
lock=(ROOT/'launcher.lock').open('a+b');lock.write(b'0');lock.flush();lock.seek(0)
try:msvcrt.locking(lock.fileno(),msvcrt.LK_NBLCK,1)
except OSError:raise SystemExit('This server is already running.')
for marker in ['stop.request','ai.stop']:(ROOT/marker).unlink(missing_ok=True)
def status(**values):
    temp=ROOT/'status.tmp';temp.write_text(json.dumps(values,indent=2),encoding='utf-8');temp.replace(ROOT/'status.json')
def model_ready():
    try:
        with urllib.request.urlopen('http://127.0.0.1:11434/api/tags',timeout=2) as response:tags=json.load(response)
        pin=json.loads((ROOT/'director/models.lock.json').read_text(encoding='utf-8'))
        return any(row.get('name')==pin['model'] and row.get('digest')==pin['manifest_sha256'] for row in tags['models'])
    except (OSError,ValueError,KeyError):return False
probe=socket.socket()
try:probe.bind(('127.0.0.1',25565))
except OSError:raise SystemExit('Port25565 is already occupied; refusing duplicate server.')
finally:probe.close()
status(state='starting',version=config['pack_version'])
if not model_ready():
    model_log=ROOT/'logs/model-startup.log'
    model_log.parent.mkdir(exist_ok=True)
    try:
        with model_log.open('w',encoding='utf-8') as log:
            result=subprocess.run(['powershell.exe','-NoProfile','-ExecutionPolicy','Bypass','-File',str(ROOT/'director/model.ps1'),'-Action','start','-Backend',config.get('model_backend','auto')],creationflags=HIDDEN,stdout=log,stderr=subprocess.STDOUT,timeout=120)
    except subprocess.TimeoutExpired:
        status(state='error',version=config['pack_version'],reason='model_startup_timeout',model_log='logs/model-startup.log')
        raise SystemExit('CPU model startup timed out. Details: logs/model-startup.log')
    if result.returncode or not model_ready():
        status(state='error',version=config['pack_version'],reason='model_startup_failed',model_log='logs/model-startup.log')
        raise SystemExit('Pinned CPU model is unavailable. Details: logs/model-startup.log')
ready=threading.Event()
server=subprocess.Popen([config['java'],'-Xms1G','-Xmx6G','@libraries/net/neoforged/neoforge/21.1.262/win_args.txt','nogui'],cwd=ROOT,stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True,encoding='utf-8',errors='replace',creationflags=HIDDEN)
status(state='loading_world',version=config['pack_version'],server_pid=server.pid)
def pump():
    with (ROOT/'logs/server-console.log').open('w',encoding='utf-8') as log:
        for line in server.stdout:
            log.write(line);log.flush()
            if 'Done (' in line and 'For help' in line:ready.set()
threading.Thread(target=pump,daemon=True).start()
host=None
try:
    deadline=time.monotonic()+300
    while not ready.wait(.5):
        if server.poll() is not None:raise RuntimeError('Server exited during startup')
        if time.monotonic()>deadline:raise RuntimeError('Server startup exceeded five minutes')
    descriptor=ROOT/config['world']/'dead_earth_bridge.json'
    old=descriptor.stat().st_mtime_ns if descriptor.exists() else -1
    server.stdin.write('deadearth bridge start\n');server.stdin.flush();deadline=time.monotonic()+20
    while not descriptor.exists() or descriptor.stat().st_mtime_ns<=old:
        if server.poll() is not None or time.monotonic()>deadline:raise RuntimeError('Local bridge did not start')
        time.sleep(.25)
    ai_log=(ROOT/'logs/director.log').open('a',encoding='utf-8')
    host=subprocess.Popen([config['director_python'],'-u','-m','dead_earth_director.host','--bridge',str(descriptor),'--mode','live','--journal',str(ROOT/'director/runtime/decisions.sqlite3'),'--stop-file',str(ROOT/'ai.stop')],cwd=ROOT/'director',stdout=ai_log,stderr=subprocess.STDOUT,creationflags=HIDDEN)
    time.sleep(1)
    status(state='ready',version=config['pack_version'],server_pid=server.pid,director_pid=host.pid,director_running=host.poll() is None,address='127.0.0.1:25565')
    while server.poll() is None:
        if (ROOT/'stop.request').exists():
            (ROOT/'ai.stop').touch()
            if host.poll() is None:
                try:host.wait(timeout=10)
                except subprocess.TimeoutExpired:host.terminate();host.wait(timeout=10)
            server.stdin.write('save-all flush\nstop\n');server.stdin.flush();server.wait();break
        time.sleep(.5)
except Exception as error:
    status(state='error',reason=str(error),server_pid=server.pid)
    if server.poll() is None:server.stdin.write('stop\n');server.stdin.flush();server.wait()
    raise
finally:
    (ROOT/'ai.stop').touch()
    if host is not None and host.poll() is None:
        try:host.wait(timeout=10)
        except subprocess.TimeoutExpired:host.terminate();host.wait(timeout=10)
    status(state='stopped',version=config['pack_version'],server_exit=server.returncode)
