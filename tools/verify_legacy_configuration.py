"""Exercise the real config editor + same-container restart in an isolated test world.
Never accepts production/user worlds. No release archives are produced.
"""
import argparse,copy,hashlib,http.client,json,secrets,subprocess,time,uuid
from datetime import datetime,timezone
from pathlib import Path
from urllib.request import Request,urlopen,build_opener,ProxyHandler
from urllib.error import HTTPError
try:
 from . import legacy_compose,legacy_port
except ImportError:
 import legacy_compose,legacy_port
ROOT=Path(__file__).resolve().parents[1]
DOCKER=Path.home()/'AppData/Local/Programs/DockerDesktop/resources/bin/docker.exe'

def load(p):return json.loads(p.read_text(encoding='utf-8'))
def write(p,d):p.parent.mkdir(parents=True,exist_ok=True);p.write_text(json.dumps(d,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
def run(source):
 source=source.resolve(strict=True)
 if source.parent!=ROOT/'work' or not source.name.startswith('legacy-native-') or not (source/'.dead-earth-isolated-native-test').is_file() or load(source/'runner-result.json').get('passed') is not True:
  raise ValueError('Only a passed isolated native test fixture may be copied')
 parent=ROOT/'work'/('config-e2e-'+datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%SZ')+'-'+uuid.uuid4().hex[:8]);parent.mkdir()
 data=parent/'data';settings=parent/'settings'
 legacy_compose.prepare(source,data,settings)
 artifact=legacy_port.compiled_jar();coordinators=list((data/'mods').glob('dead-earth-srp-coordinator-*.jar'))
 if len(coordinators)!=1:raise ValueError('Expected exactly one coordinator in isolated copy')
 coordinators[0].write_bytes(artifact)
 for name in ['deadearth-port-probe.jar','deadearth-restart-probe.jar']:
  path=data/'mods'/name
  if path.is_file():path.unlink()
 assert not any('probe' in p.name for p in (data/'mods').glob('*.jar'))
 spec=load(ROOT/'legacy/docker/compose.yaml');spec['name']='dead-earth-config-check'
 spec['services']={key:value for key,value in spec['services'].items() if key in ['minecraft','admin']}
 spec.pop('volumes',None)
 for name,service in spec['services'].items():
  if 'build' in service:service['build']['context']=str(ROOT)
  service['restart']='no'
  if 'depends_on' in service:service['depends_on'].pop('ollama',None)
 spec['services']['minecraft']['ports']=['127.0.0.1:25585:25565','127.0.0.1:8767:8767']
 spec['services']['admin']['entrypoint']=['python3','-m','dead_earth_director.admin']
 spec['services']['admin']['command']=['--server-root','/srv/dead-earth','--catalog','/srv/dead-earth/config/dead-earth/director/catalog.json','--port','8767','--stop-file','/tmp/config-test-admin.stop']
 spec['services']['admin']['healthcheck']['test']=['CMD','python3','-c',"import urllib.request,json; r=json.load(urllib.request.urlopen('http://127.0.0.1:8767/api/health',timeout=5)); assert r['product']=='dead-earth-admin-v1'"]
 compose=parent/'compose.json';write(compose,spec)
 base=[str(DOCKER),'--context','desktop-linux','compose','--env-file',str(settings/'.env'),'-f',str(compose)]
 report={'schema':1,'scope':'isolated native world copy, real admin HTTP, real RCON, same-container saved restart','passed':False,'checks':[],'directory':str(parent),'jar_sha256':hashlib.sha256(artifact).hexdigest()}
 def check(ok,name):
  report['checks'].append({'name':name,'passed':bool(ok)})
  if not ok:raise AssertionError(name)
 def docker(args,timeout=300):
  with (parent/'compose-control.log').open('a',encoding='utf-8') as log:
   code=subprocess.run(base+args,stdout=log,stderr=subprocess.STDOUT,timeout=timeout).returncode
  if code:raise RuntimeError('Compose operation failed: '+args[0])
 opener=build_opener(ProxyHandler({}));origin='http://127.0.0.1:8767'
 def http(path,payload=None,nonce=None,wrong_origin=False):
  headers={}
  if payload is not None:headers={'Content-Type':'application/json','Origin':'http://wrong.example' if wrong_origin else origin,'X-Dead-Earth-Operator':nonce}
  request=Request(origin+path,data=None if payload is None else json.dumps(payload,allow_nan=False).encode(),headers=headers)
  try:
   with opener.open(request,timeout=20) as response:return response.status,json.loads(response.read(1048577))
  except HTTPError as error:return error.code,json.loads(error.read(1048577))
 def snapshot():
  status,body=http('/api/config');report['snapshotStatus']={'http':status,'state':body.get('state')};check(status==200 and body.get('state')=='ready','live native-backed config snapshot')
  return body
 def value(s,section,key):return next(f['value'] for sec in s['server']['sections'] if sec['id']==section for f in sec['fields'] if f['key']==key)
 def payload(s,changes,mode='hot'):
  return {'request_id':str(uuid.uuid4()),'world_epoch':s['server']['worldEpoch'],'revision':s['server']['revision'],'mode':mode,'changes':changes}
 def submit(s,p):
  status,body=http('/api/config/apply',p,s['nonce']);check(status==200 and body.get('state')=='completed','operator request gets determinate native receipt')
  return body
 try:
  docker(['up','-d','--build','--wait','--wait-timeout','240'])
  old=snapshot();check(len(old['server']['sections'])>=10,'own config sections exposed')
  count=sum(len(sec['fields']) for sec in old['server']['sections']);report['fieldCount']=count;report['sectionCount']=len(old['server']['sections'])
  check(value(old,'siege','sightRadius')==64,'initial native detection radius64')
  rejected_status,bad=http('/api/config/apply',payload(old,{'siege':{'sightRadius':32}}),old['nonce'],True)
  check(rejected_status==403 and bad.get('error')=='local_only','wrong Origin cannot mutate configs')
  invalid=submit(old,payload(old,{'siege':{'sightRadius':129}}));check(invalid['result']['accepted'] is False,'radius above native bound rejected without crash')
  after=snapshot();check(after['server']['revision']==old['server']['revision'] and value(after,'siege','sightRadius')==64,'invalid draft preserves native config')
  p=payload(after,{'siege':{'sightRadius':32},'coordinator':{'resourceCoefficient':2}})
  applied=submit(after,p);check(applied['result']['state']=='applied','multi-section hot apply succeeds')
  changed=snapshot();check(value(changed,'siege','sightRadius')==32 and value(changed,'coordinator','resourceCoefficient')==2,'native active values changed together')
  repeated=submit(after,p);check(repeated==applied,'same UUID returns receipt without repeat apply')
  stale=submit(after,payload(after,{'siege':{'sightRadius':48}}));check(stale['result']['accepted'] is False,'stale editor revision rejected')
  mixed=submit(changed,payload(changed,{'siege':{'sightRadius':48},'coordinator':{'maximumZones':7}}));check(mixed['result']['accepted'] is False,'hot request containing restart field rejected atomically')
  stable=snapshot();check(value(stable,'siege','sightRadius')==32 and value(stable,'coordinator','maximumZones')==6,'mixed rejection keeps both values unchanged')
  container_before=subprocess.check_output(base+['ps','-q','minecraft'],text=True).strip()
  initial=load(data/'runtime/compose/minecraft.json')
  req=payload(stable,{'siege':{'sightRadius':48},'coordinator':{'maximumZones':7}},'restart')
  restarted=submit(stable,req);check(restarted['result']['state']=='restart_pending','restart apply queued with durable native receipt')
  deadline=time.monotonic()+300;states=[]
  while time.monotonic()<deadline:
   _,state=http('/api/config/restart')
   if state.get('requestId')==req['request_id']:
    states.append(state.get('state'))
    if state.get('state') in ['ready','failed']:break
   time.sleep(.5)
  report['restartStates']=sorted(set(states))
  check(state.get('state')=='ready' and state.get('requestId')==req['request_id'],'actual saved restart acknowledges requested native revision')
  final=snapshot();check(value(final,'siege','sightRadius')==48 and value(final,'coordinator','maximumZones')==7,'new JVM loads hot and restart-only values')
  check(final['server']['worldEpoch']==old['server']['worldEpoch'] and final['server']['campaignId']==old['server']['campaignId'],'world epoch and campaign identity preserved')
  check(subprocess.check_output(base+['ps','-q','minecraft'],text=True).strip()==container_before,'Minecraft container/network namespace preserved')
  check(load(data/'runtime/compose/minecraft.json')['pid']!=initial['pid'],'Java process actually restarted')
  log=(data/'logs/server-console.log').read_text(encoding='utf-8',errors='replace')
  check('Saving chunks' in log and 'Saving worlds' in log,'real world-save markers present')
  report.update(passed=True,revision=final['server']['revision'],campaignId=final['server']['campaignId'],worldEpoch=final['server']['worldEpoch'])
 except Exception as error:
  report['error']=str(error) if isinstance(error,AssertionError) else type(error).__name__
 finally:
  try:docker(['down'],timeout=160);report['stopped']=True
  except Exception:report['stopped']=False
  write(parent/'report.json',report)
 print(json.dumps(report,ensure_ascii=True,indent=2),flush=True)
 return 0 if report['passed'] and report.get('stopped') else 1

def main():
 parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--source',type=Path,required=True);args=parser.parse_args();return run(args.source)
if __name__=='__main__':raise SystemExit(main())
