"""Human-only finite scenario authoring. No command strings are accepted from HTTP."""
import hashlib
from contextlib import contextmanager
import json
import math
from pathlib import Path
import re
import sqlite3
import threading
import time

from .admin_console import RconFailure, rcon_command, settings
from .bridge import BridgeError, _json, _uuid
from .parasites import catalog_system, factions

MAX_BODY=262144
ID=re.compile(r'[a-z][a-z0-9_]{0,63}\Z')
HASH=re.compile(r'[a-f0-9]{64}\Z')
ROLES=('approach','flank','rear','distraction')
TRIGGERS=('near','far','inspect','break','burn','water','mine','repair','clear')
OUTCOMES=('repelled','contained','avoided','engaged','inconclusive')
SEASONS=('CALM','ASH_WINTER','HEAT')
ACTIONS=('sound','trace','wave','particles','move','finish')


class AuthoringError(ValueError):
    def __init__(self, code, path=''):
        super().__init__(code);self.code=code;self.path=path


def require(condition, path, code='invalid_event'):
    if not condition: raise AuthoringError(code,path)


def fields(value, names, path):
    require(type(value) is dict and set(value)==set(names.split()),path)


def number(value, low, high, path, integer=False):
    require(type(value) in ((int,) if integer else (int,float)) and low<=value<=high and math.isfinite(value),path)


def identifier(value, path):
    require(type(value) is str and ID.fullmatch(value) is not None,path)


def validate_event(event,catalog):
    """Mirror the finite Java schema for useful field errors; native validation is final."""
    fields(event,'id title tactic faction weight minScore minPlayers seasons cooldownTicks lifetimeTicks heavy affinity start nodes','event')
    identifier(event['id'],'id');identifier(event['tactic'],'tactic');identifier(event['start'],'start')
    require(type(event['title']) is str and len(event['title'])<=120,'title')
    require(type(event['faction']) is str and event['faction'] in factions(catalog_system(catalog)),'faction')
    require(type(event['heavy']) is bool,'heavy')
    number(event['weight'],.01,100,'weight');number(event['minScore'],-1e12,1e12,'minScore')
    number(event['minPlayers'],1,6,'minPlayers',True)
    for key in ('cooldownTicks','lifetimeTicks'):number(event[key],200,1728000,key,True)
    require(type(event['seasons']) is list and 1<=len(event['seasons'])<=3 and all(type(s) is str and s in SEASONS for s in event['seasons']),'seasons')
    affinity=event['affinity'];require(type(affinity) is dict and set(affinity)<={'value','defense','empty_defense','pressure','players','losses'},'affinity')
    for key,value in affinity.items():number(value,-4,4,'affinity.'+key)
    nodes=event['nodes'];require(type(nodes) is list and 1<=len(nodes)<=24,'nodes')
    graph={};waves=0
    for i,node in enumerate(nodes):
        path=f'nodes[{i}]';fields(node,'id actions waitTicks branches timeout',path);identifier(node['id'],path+'.id')
        require(node['id'] not in graph,path+'.id');graph[node['id']]=node
        require(type(node['actions']) is list and len(node['actions'])<=8,path+'.actions')
        terminal=False
        for j,action in enumerate(node['actions']):
            ap=f'{path}.actions[{j}]';fields(action,'type key role portion',ap)
            require(action['type'] in ACTIONS,ap+'.type');require(action['role'] in ROLES,ap+'.role')
            require(type(action['key']) is str and len(action['key'])<=128,ap+'.key');number(action['portion'],0,1,ap+'.portion')
            kind,key=action['type'],action['key']
            if kind in ('sound','trace','wave'):
                require(key in catalog.get({'sound':'sounds','trace':'traces','wave':'waves'}[kind],{}),ap+'.key')
            elif kind=='particles':require(key in ('spores','ash','frost','dust'),ap+'.key')
            elif kind=='move':require(key in ROLES,ap+'.key')
            elif kind=='finish':require(key in OUTCOMES,ap+'.key');terminal=True
            if kind=='wave':number(action['portion'],1e-300,1,ap+'.portion');waves+=1
        require(waves<=8,path+'.actions')
        number(node['waitTicks'],0,72000,path+'.waitTicks',True)
        require(type(node['timeout']) is str,path+'.timeout')
        require(type(node['branches']) is list and len(node['branches'])<=9,path+'.branches')
        seen=set()
        for j,branch in enumerate(node['branches']):
            bp=f'{path}.branches[{j}]';fields(branch,'when next',bp)
            when=branch['when'];require(type(when) is str,bp+'.when')
            parts=when.split(':');require(len(parts) in (1,2) and parts[0] in TRIGGERS and (len(parts)==1 or parts[1] in ROLES) and when not in seen,bp+'.when')
            seen.add(when);identifier(branch['next'],bp+'.next')
        if terminal:require(len(node['actions'])==1 and not node['branches'] and node['timeout']=='' and node['waitTicks']==0,path+'.terminal')
        else:identifier(node['timeout'],path+'.timeout');require(node['waitTicks']>0,path+'.waitTicks')
    visiting=set();done=set()
    def visit(key):
        require(key in graph,'nodes.reference');require(key not in visiting,'nodes.cycle')
        if key in done:return
        visiting.add(key);node=graph[key]
        for target in [b['next'] for b in node['branches']]+([node['timeout']] if node['timeout'] else []):visit(target)
        visiting.remove(key);done.add(key)
    visit(event['start']);require(len(done)==len(graph),'nodes.unreachable')
    require(len(encode(event))<=131072,'event.size')
    return event


def encode(value):return json.dumps(value,ensure_ascii=False,allow_nan=False,separators=(',',':')).encode('utf-8')


def native_reply(raw):
    if type(raw) is not str:raise AuthoringError('unsupported')
    raw=re.sub(r'\u00a7[0-9A-FK-ORa-fk-or]','',raw).strip()
    if raw.startswith('[DeadEarth] '):raw=raw[len('[DeadEarth] '):]
    try:value=_json(raw)
    except (BridgeError,ValueError,TypeError,RecursionError):raise AuthoringError('unsupported') from None
    if type(value) is not dict:raise AuthoringError('unsupported')
    return value


class Authoring:
    def __init__(self,root,console,reader=None):
        self.root=Path(root).resolve();self.console=console;self.lock=threading.Lock()
        self.path=self.root/'config/dead-earth/director/catalog.json'
        self.database=self.root/'director/runtime/authoring.sqlite3'
        self.safe_path(self.database);self.database.parent.mkdir(parents=True,exist_ok=True)
        self.reader=reader or self.read_command
        with self.db() as db:
            db.execute('CREATE TABLE IF NOT EXISTS drafts(id TEXT PRIMARY KEY,event TEXT NOT NULL,revision INTEGER NOT NULL,updated_at REAL NOT NULL)')
            db.execute('CREATE TABLE IF NOT EXISTS requests(id TEXT PRIMARY KEY,digest TEXT NOT NULL,result TEXT NOT NULL,at REAL NOT NULL)')

    def safe_path(self,path):
        require(path.is_relative_to(self.root) and path!=self.root,'path','unsafe_path')
        for parent in (path,*path.parents):
            if parent==self.root:break
            require(not parent.is_symlink() and not (hasattr(parent,'is_junction') and parent.is_junction()),'path','unsafe_path')
        require(path.resolve().is_relative_to(self.root),'path','unsafe_path')

    @contextmanager
    def db(self):
        db=sqlite3.connect(self.database,timeout=2)
        try:
            with db:yield db
        finally:db.close()

    def drafts(self):
        with self.db() as db:
            return [dict(event=json.loads(e),revision=r,updated_at=t) for e,r,t in db.execute('SELECT event,revision,updated_at FROM drafts ORDER BY updated_at DESC')]

    def local_catalog(self):
        self.safe_path(self.path);require(self.path.stat().st_size<=1048576,'catalog','invalid_catalog')
        raw=self.path.read_bytes()
        try:value=_json(raw.decode('utf-8'))
        except (BridgeError,ValueError,UnicodeError,RecursionError):raise AuthoringError('invalid_catalog') from None
        fields(value,'schema rules sounds traces waves events','catalog')
        require(type(value['events']) is list and all(type(v) is dict for v in value['events']),'catalog','invalid_catalog')
        return value,hashlib.sha256(raw).hexdigest()

    def read_command(self,command):
        state,port,password=settings(self.root)
        if state!='configured':raise AuthoringError('offline')
        try:return rcon_command(port,password,command)
        except (RconFailure,OSError,ValueError):raise AuthoringError('offline') from None

    def server_status(self):
        value=native_reply(self.reader('deadearth operator status'))
        require(value.get('protocol')==1 and _uuid(value.get('worldEpoch')) and _uuid(value.get('campaignId'))
                and type(value.get('catalogRevision')) is str and HASH.fullmatch(value['catalogRevision'])
                and type(value.get('sourceHash')) is str and HASH.fullmatch(value['sourceHash'])
                and type(value.get('zones')) is list,'status','unsupported')
        return value

    def snapshot(self):
        result=dict(state='offline',server=None,catalog=None,drafts=self.drafts(),receipts=[])
        try:
            result['catalog'],source_hash=self.local_catalog()
            result['server']=self.server_status()
            result['state']='ready' if result['server']['sourceHash']==source_hash else 'catalog_mismatch'
        except AuthoringError as ex:result['state']=ex.code
        except (OSError,ValueError,sqlite3.Error):result['state']='offline'
        with self.db() as db:
            result['receipts']=[dict(request_id=i,**json.loads(r)) for i,r in db.execute('SELECT id,result FROM requests ORDER BY at DESC LIMIT 30')]
        return result

    def scope(self,payload):
        require(_uuid(payload.get('world_epoch')),'world_epoch','invalid_request')
        require(type(payload.get('catalog_revision')) is str and HASH.fullmatch(payload['catalog_revision']),'catalog_revision','invalid_request')
        catalog,source_hash=self.local_catalog();status=self.server_status()
        require(status['worldEpoch']==payload['world_epoch'],'world_epoch','stale_epoch')
        require(status['catalogRevision']==payload['catalog_revision'],'catalog_revision','stale_catalog')
        require(status['sourceHash']==source_hash,'catalog','catalog_mismatch')
        return catalog,status

    def action(self,action,payload):
        schemas={'save':'request_id event draft_revision','preview':'request_id event_id zone_id world_epoch catalog_revision',
                 'run':'request_id event_id zone_id world_epoch catalog_revision','cancel':'request_id operation_id world_epoch',
                 'validate':'request_id draft_id draft_revision world_epoch catalog_revision','publish':'request_id draft_id draft_revision world_epoch catalog_revision'}
        require(action in schemas,'action','invalid_request');fields(payload,schemas[action],'request')
        require(_uuid(payload.get('request_id')),'request_id','invalid_request')
        digest=hashlib.sha256(json.dumps(dict(action=action,payload=payload),sort_keys=True,ensure_ascii=False,allow_nan=False,separators=(',',':')).encode('utf-8')).hexdigest();ident=payload['request_id']
        if not self.lock.acquire(blocking=False):raise AuthoringError('busy')
        try:
            with self.db() as db:
                prior=db.execute('SELECT digest,result FROM requests WHERE id=?',(ident,)).fetchone()
                if prior:
                    require(prior[0]==digest,'request_id','conflicting_request');return json.loads(prior[1])
                require(db.execute('SELECT COUNT(*) FROM requests').fetchone()[0]<5000,'requests','journal_full')
            # All shape/content checks precede the durable uncertain receipt.
            prepared=self.prepare(action,payload)
            with self.db() as db:
                db.execute('BEGIN IMMEDIATE')
                prior=db.execute('SELECT digest,result FROM requests WHERE id=?',(ident,)).fetchone()
                if prior:
                    require(prior[0]==digest,'request_id','conflicting_request');return json.loads(prior[1])
                require(db.execute('SELECT COUNT(*) FROM requests').fetchone()[0]<5000,'requests','journal_full')
                db.execute('INSERT INTO requests VALUES(?,?,?,?)',(ident,digest,json.dumps(dict(state='uncertain',code='outcome_unknown')),time.time()))
            try:result=self.perform(action,payload,prepared)
            except AuthoringError as ex:result=dict(state='rejected',code=ex.code,path=ex.path)
            except (OSError,ValueError,sqlite3.Error,BridgeError):result=dict(state='uncertain',code='outcome_unknown')
            with self.db() as db:db.execute('UPDATE requests SET result=? WHERE id=?',(json.dumps(result,ensure_ascii=False),ident))
            return result
        finally:self.lock.release()

    def prepare(self,action,p):
        if action=='save':
            catalog,_=self.local_catalog();validate_event(p['event'],catalog)
            require(p['event']['id'].startswith('custom_'),'id','custom_id_required')
            number(p['draft_revision'],0,2147483647,'draft_revision',True)
            with self.db() as db:
                row=db.execute('SELECT revision FROM drafts WHERE id=?',(p['event']['id'],)).fetchone()
                require((row[0] if row else 0)==p['draft_revision'],'draft_revision','stale_draft')
                require(row is not None or db.execute('SELECT COUNT(*) FROM drafts').fetchone()[0]<64,'drafts','draft_limit')
            # A custom name cannot overwrite an installed built-in event.
            return None
        if action=='cancel':
            require(_uuid(p['operation_id']) and _uuid(p['world_epoch']),'operation_id','invalid_request')
            require(self.server_status()['worldEpoch']==p['world_epoch'],'world_epoch','stale_epoch');return None
        catalog,status=self.scope(p)
        if action in ('preview','run'):
            identifier(p['event_id'],'event_id');require(_uuid(p['zone_id']),'zone_id','invalid_request')
            return None
        identifier(p['draft_id'],'draft_id');number(p['draft_revision'],1,2147483647,'draft_revision',True)
        with self.db() as db:row=db.execute('SELECT event,revision FROM drafts WHERE id=?',(p['draft_id'],)).fetchone()
        require(row is not None and row[1]==p['draft_revision'],'draft_revision','stale_draft')
        event=json.loads(row[0]);validate_event(event,catalog)
        require(event['id'].startswith('custom_'),'id','custom_id_required')
        catalog['events']=[e for e in catalog['events'] if e.get('id')!=event['id']]+[event]
        require(len(catalog['events'])<=64,'events','catalog_full')
        raw=encode(catalog);require(len(raw)<=1048576,'catalog','catalog_full')
        return raw

    def command(self,action,p):
        prefix='deadearth operator '+action+' '+p['world_epoch']
        if action=='cancel':return prefix+' '+p['operation_id']
        if action=='apply':return prefix+' '+p['catalog_revision']+' '+p['request_id']
        if action=='validate':return prefix+' '+p['request_id']
        suffix=p['event_id']+' '+p['zone_id']
        return prefix+' '+p['catalog_revision']+' '+(p['request_id']+' ' if action=='run' else '')+suffix

    def perform(self,action,p,prepared):
        if action=='save':
            at=time.time();revision=p['draft_revision']+1
            with self.db() as db:
                # The process lock does not cover a second admin on another port.
                # Check the version and capacity while holding SQLite's write reservation.
                db.execute('BEGIN IMMEDIATE')
                row=db.execute('SELECT revision FROM drafts WHERE id=?',(p['event']['id'],)).fetchone()
                require((row[0] if row else 0)==p['draft_revision'],'draft_revision','stale_draft')
                require(row is not None or db.execute('SELECT COUNT(*) FROM drafts').fetchone()[0]<64,'drafts','draft_limit')
                db.execute('INSERT OR REPLACE INTO drafts VALUES(?,?,?,?)',(p['event']['id'],encode(p['event']).decode(),revision,at))
            return dict(state='saved',draft=dict(event=p['event'],revision=revision,updated_at=at))
        if action=='preview':return dict(state='completed',result=native_reply(self.reader(self.command('preview',p))))
        if action in ('validate','publish'):
            stage=self.path.parent/'staging'/(p['request_id']+'.json');self.safe_path(stage);stage.parent.mkdir(parents=True,exist_ok=True)
            with stage.open('xb') as file:file.write(prepared)
            validation=native_reply(self.reader(self.command('validate',p)))
            if action=='validate':
                stage.unlink();return dict(state='completed',result=validation)
            if not validation.get('accepted'):return dict(state='completed',result=validation)
            command=self.command('apply',p)
        else:command=self.command(action,p)
        receipt=self.console.execute(p['request_id'],command)
        result=dict(state=receipt.get('state','uncertain'))
        if result['state']=='completed':
            try:result['result']=native_reply(receipt.get('output'))
            except AuthoringError:result.update(state='uncertain',code='unsupported_response')
        return result
