import contextlib
import http.client
import json
from pathlib import Path
import tempfile
import threading
import unittest
from uuid import uuid4
from unittest.mock import patch
from concurrent.futures import ThreadPoolExecutor
import sqlite3

from dead_earth_director import admin, host
from dead_earth_director.ai_control import ModeStore,RuntimeModels,ControlError,release_critic
from test_director import model_endpoint, DIGEST

CRITIC = 'b' * 64


def configuration(path, url):
    path.write_text('poll_seconds=30\nai_mode="dual"\n[model]\nurl="'+url+'"\n'
                    'model="qwen3.5:4b"\ndigest="'+DIGEST+'"\n[critic]\nurl="'+url+'"\n'
                    'model="dead-earth-critic:2b-q4"\ndigest="'+CRITIC+'"\n', encoding='utf-8')


@contextlib.contextmanager
def panel(root, config):
    app = admin.Application(root/'bridge',root/'journal',root/'admin.db',root/'catalog',config)
    server=admin.make_server(app,0)
    worker=threading.Thread(target=server.serve_forever,daemon=True);worker.start()
    port=server.server_address[1]
    def request(method='GET', payload=None, headers=None, path='/api/ai-mode'):
        conn=http.client.HTTPConnection('127.0.0.1',port,timeout=5)
        body=None if payload is None else json.dumps(payload)
        conn.request(method,path,body,headers or {})
        response=conn.getresponse();raw=response.read();conn.close()
        return response.status,json.loads(raw)
    try:yield app,port,request
    finally:server.shutdown();server.server_close();worker.join()


class ModeHttpTests(unittest.TestCase):
    def test_operator_can_persist_single_and_confirm_critic_unload(self):
        loaded=True;calls=[]
        def ollama(path, body):
            nonlocal loaded
            calls.append((path,body))
            if path=='/api/ps':return 200,dict(models=[dict(name='dead-earth-critic:2b-q4',digest=CRITIC)] if loaded else [])
            if path=='/api/generate':
                loaded=False
                return 200,dict(done=True,done_reason='unload')
            return 200,dict(models=[])
        with model_endpoint(ollama) as (url,_),tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp);config=root/'model.toml';configuration(config,url)
            with panel(root,config) as (app,port,request):
                code,before=request();self.assertEqual(code,200)
                self.assertEqual(before['desired_mode'],'dual')
                headers={'Content-Type':'application/json','Origin':f'http://127.0.0.1:{port}',
                         'X-Dead-Earth-Operator':before['nonce']}
                command=dict(request_id=str(uuid4()),expected_revision=before['revision'],mode='single')
                code,after=request('POST',command,headers)
                self.assertEqual(code,200)
                self.assertEqual(after['desired_mode'],'single')
                self.assertEqual(after['critic_memory'],'released')
                self.assertNotEqual(after['state'],'applied') # No director process acknowledged it.
                self.assertFalse(loaded)
                self.assertEqual([body for path,body in calls if path=='/api/generate'],
                                 [dict(model='dead-earth-critic:2b-q4',keep_alive=0,stream=False)])
                self.assertNotIn('/api/chat',[path for path,_ in calls])
                self.assertEqual(request('POST',command,headers)[1]['revision'],command['request_id'])
            with panel(root,config) as (_,_,request):
                self.assertEqual(request()[1]['desired_mode'],'single')

    def test_mode_mutations_require_operator_origin_nonce_and_valid_revision(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp);config=root/'model.toml';configuration(config,'http://127.0.0.1:11434')
            with panel(root,config) as (_,port,request):
                code,status=request();self.assertEqual(code,200)
                command=dict(request_id=str(uuid4()),expected_revision=status['revision'],mode='dual')
                self.assertEqual(request('POST',command,{'Content-Type':'application/json'})[0],403)
                headers={'Content-Type':'application/json','Origin':f'http://127.0.0.1:{port}',
                         'X-Dead-Earth-Operator':status['nonce']}
                self.assertEqual(request('POST',dict(command,mode='gpu'),headers)[0],400)
                self.assertEqual(request('POST',dict(command,model='arbitrary'),headers)[0],400)
                self.assertEqual(request('POST',dict(command,expected_revision=str(uuid4())),headers)[0],409)
                self.assertEqual(request()[1]['desired_mode'],'dual')


class RuntimeModeTests(unittest.TestCase):
    def test_first_admin_and_host_start_share_one_atomic_initialization(self):
        barrier=threading.Barrier(2)
        original=sqlite3.connect
        class Connection(sqlite3.Connection):
            def execute(self,sql,*args,**kwargs):
                # Force the unsafe DDL interleaving, but let a native SQL transaction serialize it.
                if sql.startswith('CREATE TABLE desired') and not self.in_transaction:barrier.wait(timeout=2)
                return super().execute(sql,*args,**kwargs)
        def connect(*args,**kwargs):return original(*args,factory=Connection,**kwargs)
        with tempfile.TemporaryDirectory() as tmp,patch('dead_earth_director.ai_control.sqlite3.connect',connect):
            path=Path(tmp)/'control.db'
            def open_store(_):
                try:ModeStore(path);return 'ready'
                except ControlError as error:return str(error)
            with ThreadPoolExecutor(max_workers=2) as pool:results=list(pool.map(open_store,range(2)))
            self.assertEqual(results,['ready','ready'])
            self.assertEqual(ModeStore(path).desired('single'),dict(mode='single',revision=None))

    def test_interrupted_schema_creation_rolls_back_before_retry(self):
        original=sqlite3.connect
        class Interrupted(sqlite3.Connection):
            def execute(self,sql,*args,**kwargs):
                if sql=='PRAGMA user_version=1':raise sqlite3.OperationalError('injected initialization failure')
                return super().execute(sql,*args,**kwargs)
        def connect(*args,**kwargs):return original(*args,factory=Interrupted,**kwargs)
        with tempfile.TemporaryDirectory() as tmp:
            path=Path(tmp)/'control.db'
            with patch('dead_earth_director.ai_control.sqlite3.connect',connect):
                with self.assertRaises(ControlError):ModeStore(path)
            with contextlib.closing(original(path)) as db:
                self.assertEqual(db.execute("SELECT name FROM sqlite_master WHERE type='table'").fetchall(),[])
            self.assertEqual(ModeStore(path).desired('dual'),dict(mode='dual',revision=None))

    def test_switch_during_each_council_stage_preserves_proposal_and_releases_critic(self):
        context=dict(season='CALM',pressure=.5,options=[dict(option=0,event='scout',tactic='lure'),
                       dict(option=1,event='trap',tactic='feint')],recent=[])
        for switch_at in (1,2,3):
            with self.subTest(stage=switch_at),tempfile.TemporaryDirectory() as tmp:
                root=Path(tmp);store=ModeStore(root/'control.db');chats=[];loaded=False;unloaded=[]
                def ollama(path,body):
                    nonlocal loaded
                    if path=='/api/tags':return 200,dict(models=[dict(name='qwen3.5:4b',digest=DIGEST),dict(name='dead-earth-critic:2b-q4',digest=CRITIC)])
                    if path=='/api/ps':return 200,dict(models=[dict(name='dead-earth-critic:2b-q4',digest=CRITIC)] if loaded else [])
                    if path=='/api/generate':
                        unloaded.append(body['model']);loaded=False;return 200,dict(done=True)
                    chats.append(body['model'])
                    if body['model']=='dead-earth-critic:2b-q4':loaded=True
                    if len(chats)==switch_at:
                        store.request(dict(request_id=str(uuid4()),expected_revision=None,mode='single'),settings)
                    answer=dict(option=1,reason_codes=['repetition']) if body['model']=='dead-earth-critic:2b-q4' else dict(option=0 if len(chats)==1 else 1)
                    return 200,dict(done=True,done_reason='stop',message=dict(content=json.dumps(answer)),prompt_eval_count=20,eval_count=5)
                with model_endpoint(ollama) as (url,_):
                    config=root/'model.toml';configuration(config,url);settings,_=host.load_config(config)
                    runtime=RuntimeModels(settings,store,'live');model=runtime.refresh()
                    decision,metrics=model.select(context)
                    self.assertEqual(decision,dict(option=0))
                    self.assertEqual(metrics['outcome'],'mode_changed')
                    self.assertEqual(len(chats),switch_at)
                    self.assertIsInstance(runtime.refresh(),host.OllamaModel)
                    self.assertFalse(loaded)
                    self.assertEqual(unloaded,[] if switch_at==1 else ['dead-earth-critic:2b-q4'])
                    report=store.host();self.assertEqual(report['mode'],'single');self.assertEqual(report['state'],'applied')
                    runtime.close()

    def test_unload_checks_actual_digest_alias_and_never_unloads_primary(self):
        present=True;unloads=[]
        def ollama(path,body):
            nonlocal present
            if path=='/api/ps':return 200,dict(models=[dict(name='qwen3.5:4b',digest=DIGEST)]+([dict(name='qwen3.5:2b-q4_K_M',digest=CRITIC)] if present else []))
            unloads.append(body);present=False;return 200,dict(done=True)
        with model_endpoint(ollama) as (url,_),tempfile.TemporaryDirectory() as tmp:
            config=Path(tmp)/'model.toml';configuration(config,url);settings,_=host.load_config(config)
            self.assertEqual(release_critic(settings),'released')
            self.assertEqual(unloads,[dict(model='qwen3.5:2b-q4_K_M',keep_alive=0,stream=False)])

    def test_unload_ack_without_absent_runner_is_not_confirmation(self):
        def ollama(path,body):
            return (200,dict(done=True)) if path=='/api/generate' else (200,dict(models=[dict(name='dead-earth-critic:2b-q4',digest=CRITIC)]))
        with model_endpoint(ollama) as (url,_),tempfile.TemporaryDirectory() as tmp:
            config=Path(tmp)/'model.toml';configuration(config,url);settings,_=host.load_config(config)
            self.assertEqual(release_critic(settings),'loaded')

    def test_misconfigured_critic_with_primary_digest_cannot_unload_primary(self):
        seen=[]
        def ollama(path,body):
            seen.append(path)
            return 200,dict(models=[dict(name='qwen3.5:4b',digest=DIGEST)])
        with model_endpoint(ollama) as (url,_),tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp);config=root/'model.toml';configuration(config,url)
            config.write_text(config.read_text().replace('ai_mode="dual"','ai_mode="single"').replace(CRITIC,DIGEST))
            settings,_=host.load_config(config)
            self.assertEqual(release_critic(settings),'unknown')
            self.assertNotIn('/api/generate',seen)
            with panel(root,config) as (_,_,request):self.assertFalse(request()[1]['dual_available'])

    def test_unavailable_ollama_does_not_claim_unload_or_applied_single(self):
        def ollama(path,body):return 503,dict(error='private backend detail')
        with model_endpoint(ollama) as (url,_),tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp);config=root/'model.toml';configuration(config,url)
            with panel(root,config) as (app,port,request):
                before=request()[1]
                headers={'Content-Type':'application/json','Origin':f'http://127.0.0.1:{port}','X-Dead-Earth-Operator':before['nonce']}
                code,after=request('POST',dict(request_id=str(uuid4()),expected_revision=None,mode='single'),headers)
                self.assertEqual(code,200);self.assertEqual(after['critic_memory'],'unknown')
                self.assertNotEqual(after['state'],'applied');self.assertNotIn('private',json.dumps(after))

    def test_switch_back_to_dual_constructs_council_without_loading_models(self):
        seen=[]
        def ollama(path,body):seen.append(path);return 200,dict(models=[])
        with model_endpoint(ollama) as (url,_),tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp);config=root/'model.toml';configuration(config,url);settings,_=host.load_config(config)
            store=ModeStore(root/'control.db');a=str(uuid4());b=str(uuid4())
            store.request(dict(request_id=a,expected_revision=None,mode='single'),settings)
            runtime=RuntimeModels(settings,store,'live');self.assertIsInstance(runtime.refresh(),host.OllamaModel)
            store.request(dict(request_id=b,expected_revision=a,mode='dual'),settings)
            self.assertIsInstance(runtime.refresh(),host.CouncilModel)
            self.assertEqual(store.host()['mode'],'dual')
            self.assertTrue(all(path=='/api/ps' for path in seen))
            runtime.close()

    def test_old_ui_request_cannot_undo_a_newer_switch_and_unknown_database_is_preserved(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp);config=root/'model.toml';configuration(config,'http://127.0.0.1:11434');settings,_=host.load_config(config)
            store=ModeStore(root/'control.db');a=str(uuid4());b=str(uuid4())
            store.request(dict(request_id=a,expected_revision=None,mode='single'),settings)
            store.request(dict(request_id=b,expected_revision=a,mode='dual'),settings)
            with self.assertRaisesRegex(ControlError,'stale_revision'):
                store.request(dict(request_id=a,expected_revision=None,mode='single'),settings)
            self.assertEqual(store.desired('single'),dict(mode='dual',revision=b))
            import sqlite3
            with contextlib.closing(sqlite3.connect(root/'unknown.db')) as db:
                db.execute('PRAGMA user_version=9');db.commit()
            original=(root/'unknown.db').read_bytes()
            with self.assertRaises(ControlError):ModeStore(root/'unknown.db')
            self.assertEqual((root/'unknown.db').read_bytes(),original)


if __name__=='__main__':unittest.main()
