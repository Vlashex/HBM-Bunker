"""Human-only durable mode requests; host acknowledgements never imply an Ollama unload."""
from contextlib import contextmanager
from dataclasses import replace
import json
from pathlib import Path
import re
import sqlite3
import time
from .bridge import _uuid
from .model import ModelError, OllamaModel


class ControlError(RuntimeError):
    pass


class ModeStore:
    def __init__(self,path):
        self.path=Path(path)
        if self.path.is_symlink() or self.path.exists() and self.path.stat().st_size>1048576:
            raise ControlError('ai_control_unavailable')
        self.path.parent.mkdir(parents=True,exist_ok=True)
        with self.db() as db:
            db.execute('BEGIN IMMEDIATE')
            version=db.execute('PRAGMA user_version').fetchone()[0]
            tables=db.execute("SELECT name FROM sqlite_master WHERE type='table'").fetchall()
            if version not in (0,1) or version==0 and tables:
                raise ControlError('ai_control_unsupported')
            if version==0:
                db.execute('CREATE TABLE desired (id INTEGER PRIMARY KEY CHECK(id=1), mode TEXT NOT NULL, revision TEXT NOT NULL)')
                db.execute('CREATE TABLE host (id INTEGER PRIMARY KEY CHECK(id=1), report TEXT NOT NULL)')
                db.execute('PRAGMA user_version=1')

    @contextmanager
    def db(self):
        db=None
        try:
            db=sqlite3.connect(self.path,timeout=1)
            with db:yield db
        except sqlite3.Error:
            raise ControlError('ai_control_unavailable') from None
        finally:
            if db is not None:db.close()

    @staticmethod
    def value(db,default):
        row=db.execute('SELECT mode,revision FROM desired WHERE id=1').fetchone()
        if row is None:return dict(mode=default,revision=None)
        if row[0] not in ('single','dual') or not _uuid(row[1]) or row[1]!=row[1].lower():
            raise ControlError('ai_control_unavailable')
        return dict(mode=row[0],revision=row[1])

    def desired(self,default):
        with self.db() as db:return self.value(db,default)

    def request(self,payload,settings):
        if (type(payload) is not dict or set(payload)!={'request_id','expected_revision','mode'}
                or not _uuid(payload['request_id']) or payload['request_id']!=payload['request_id'].lower()
                or payload['expected_revision'] is not None and not _uuid(payload['expected_revision'])
                or payload['mode'] not in ('single','dual')):
            raise ControlError('invalid_ai_mode_request')
        try:replace(settings,ai_mode=payload['mode'])
        except (ModelError,TypeError):raise ControlError('ai_mode_not_configured') from None
        with self.db() as db:
            db.execute('BEGIN IMMEDIATE')
            current=self.value(db,settings.ai_mode)
            if current['revision']==payload['request_id']:
                if current['mode']!=payload['mode']:raise ControlError('conflicting_request')
                return current
            if current['revision']!=payload['expected_revision']:raise ControlError('stale_revision')
            db.execute('INSERT OR REPLACE INTO desired VALUES(1,?,?)',(payload['mode'],payload['request_id']))
        return dict(mode=payload['mode'],revision=payload['request_id'])

    def acknowledge(self,desired,mode,state,run_mode):
        report=dict(revision=desired['revision'],mode=mode,state=state,run_mode=run_mode,at=time.time())
        with self.db() as db:db.execute('INSERT OR REPLACE INTO host VALUES(1,?)',(json.dumps(report,allow_nan=False),))

    def host(self):
        with self.db() as db:row=db.execute('SELECT report FROM host WHERE id=1').fetchone()
        try:
            report=json.loads(row[0]) if row else None
            if report is None:return None
            if (type(report) is not dict or set(report)!={'revision','mode','state','run_mode','at'}
                    or report['mode'] not in ('single','dual') or report['state'] not in ('applied','unload_pending','stopped')
                    or report['run_mode'] not in ('preview','live') or type(report['at']) not in (int,float)
                    or not 0<=time.time()-report['at']<=120):return None
            return report
        except (TypeError,ValueError):return None


def critic_residents(settings,*,deadline):
    if settings.critic is None:return []
    if (settings.critic.digest==settings.digest or settings.critic.model==settings.model
            or settings.critic.url!=settings.url):
        raise ModelError('model_invalid_settings')
    result=OllamaModel(settings.critic)._request('/api/ps',deadline=deadline)
    if type(result) is not dict or type(result.get('models')) is not list or len(result['models'])>64:
        raise ModelError('model_invalid_response')
    rows=[]
    for row in result['models']:
        if type(row) is not dict:raise ModelError('model_invalid_response')
        if row.get('digest')==settings.critic.digest:
            name=row.get('name',row.get('model'))
            if type(name) is not str or not re.fullmatch(r'[A-Za-z0-9._:/-]{1,160}',name):
                raise ModelError('model_invalid_response')
            rows.append(name)
        elif row.get('name')==settings.critic.model:
            raise ModelError('model_revision_mismatch')
    return rows


def release_critic(settings,*,seconds=3):
    end=time.monotonic()+seconds
    try:
        model=OllamaModel(settings.critic) if settings.critic else None
        for name in critic_residents(settings,deadline=end):
            reply=model._request('/api/generate',dict(model=name,keep_alive=0,stream=False),deadline=end)
            if type(reply) is not dict or reply.get('done') is not True:raise ModelError('model_invalid_response')
        return 'loaded' if critic_residents(settings,deadline=end) else 'released'
    except ModelError:return 'unknown'


class ModeControl:
    def __init__(self,path,config):
        self.store=ModeStore(path);self.config=Path(config)

    def settings(self):
        from .host import load_config,DirectorError
        try:return load_config(self.config)[0]
        except (DirectorError,ModelError):raise ControlError('ai_mode_not_configured') from None

    def snapshot(self):
        settings=self.settings();desired=self.store.desired(settings.ai_mode);report=self.store.host()
        try:replace(settings,ai_mode='dual');dual_available=True
        except ModelError:dual_available=False
        try:memory='loaded' if critic_residents(settings,deadline=time.monotonic()+2) else 'released'
        except ModelError:memory='unknown'
        fresh=report is not None and report['state']!='stopped'
        applied=(fresh and report['revision']==desired['revision'] and report['mode']==desired['mode']
                 and report['state']=='applied' and (desired['mode']=='dual' or memory=='released'))
        return dict(desired_mode=desired['mode'],revision=desired['revision'],
                    active_mode=report['mode'] if fresh else None,host_state='online' if fresh else 'unconfirmed',
                    host_run_mode=report['run_mode'] if fresh else None,
                    state='applied' if applied else 'pending' if fresh else 'saved',critic_memory=memory,
                    dual_available=dual_available)

    def request(self,payload):
        settings=self.settings();desired=self.store.request(payload,settings)
        if desired['mode']=='single':release_critic(settings)
        return self.snapshot()


class RuntimeModels:
    """Refresh at stage boundaries and at most one second apart while idle."""
    def __init__(self,settings,store,run_mode):
        self.settings,self.store,self.run_mode=settings,store,run_mode
        self.current=None;self.model=None;self.last_release=-float('inf');self.last_report=-float('inf')
        self.memory='unknown'

    def matches(self,expected):
        try:return self.store.desired(self.settings.ai_mode)==expected
        except ControlError:return False

    def refresh(self):
        from .host import build_model
        desired=self.store.desired(self.settings.ai_mode);changed=desired!=self.current
        active=desired['mode'] if self.run_mode=='live' else 'single'
        if changed:
            settings=replace(self.settings,ai_mode=desired['mode'])
            self.model=build_model(settings,self.run_mode,continue_dual=lambda:self.matches(desired))
            self.current=desired
        if active=='single' and (changed or time.monotonic()-self.last_release>=5):
            self.memory=release_critic(self.settings);self.last_release=time.monotonic()
        state='applied' if active=='dual' or self.memory=='released' else 'unload_pending'
        if changed or time.monotonic()-self.last_report>=2:
            self.store.acknowledge(desired,active,state,self.run_mode);self.last_report=time.monotonic()
        return self.model

    def close(self):
        desired=self.store.desired(self.settings.ai_mode)
        if desired['mode']=='single' or self.run_mode=='preview':release_critic(self.settings)
        self.store.acknowledge(desired,desired['mode'] if self.run_mode=='live' else 'single','stopped',self.run_mode)
