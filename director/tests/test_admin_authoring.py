import copy
from concurrent.futures import ThreadPoolExecutor
import hashlib
import http.client
import json
from pathlib import Path
import tempfile
import threading
import unittest
import uuid
from unittest.mock import patch

from dead_earth_director import admin
from dead_earth_director.admin_authoring import Authoring, AuthoringError, validate_event

CATALOG = Path(__file__).resolve().parents[2]/'pack/config/dead-earth/director/catalog.json'
EPOCH = str(uuid.uuid4())
ZONE = str(uuid.uuid4())
REV = 'a'*64


def event():
    value=copy.deepcopy(json.loads(CATALOG.read_text(encoding='utf-8'))['events'][0])
    value['id']='custom_test';return value


class Native:
    def __init__(self, root): self.root=root;self.calls=[];self.results={}
    def status(self):
        raw=(self.root/'config/dead-earth/director/catalog.json').read_bytes()
        return dict(protocol=1,campaignId=str(uuid.uuid4()),worldEpoch=EPOCH,catalogRevision=REV,
                    sourceHash=hashlib.sha256(raw).hexdigest(),enabled=True,activeTicks=120,zones=[dict(id=ZONE)])
    def __call__(self, command):
        self.calls.append(command)
        if command.endswith(' status'): result=self.status()
        elif ' validate ' in command: result=dict(accepted=True,code='validated')
        else: result=dict(accepted=True,code='ready',option=dict(eventId='custom_test',zoneId=ZONE,budget=3))
        return '[DeadEarth] '+json.dumps(result)
    def execute(self, ident, command):
        if ident not in self.results:
            self.results[ident]=dict(state='completed',output=self(command),request_id=ident)
        return self.results[ident]


class AuthoringTests(unittest.TestCase):
    def setup_service(self, tmp):
        root=Path(tmp);dest=root/'config/dead-earth/director/catalog.json';dest.parent.mkdir(parents=True)
        dest.write_bytes(CATALOG.read_bytes());native=Native(root)
        service=Authoring(root,native,reader=native)
        return service,native

    def test_graph_validation_rejects_cycles_unknown_commands_and_references(self):
        catalog=json.loads(CATALOG.read_text(encoding='utf-8'));good=event();validate_event(good,catalog)
        for change in ['cycle','unknown','reference','bool','duplicate','terminal']:
            bad=copy.deepcopy(good)
            if change=='cycle': bad['nodes'][0]['timeout']=bad['nodes'][0]['id']
            if change=='unknown': bad['nodes'][0]['actions'][0]['type']='command'
            if change=='reference': bad['nodes'][0]['actions'][0].update(type='sound',key='missing')
            if change=='bool': bad['minPlayers']=True
            if change=='duplicate': bad['nodes'].append(copy.deepcopy(bad['nodes'][0]))
            if change=='terminal': bad['nodes'][-1]['waitTicks']=20
            with self.subTest(change=change),self.assertRaises(AuthoringError): validate_event(bad,catalog)

    def test_all_builtin_graphs_remain_editable_and_old_servers_allow_drafts(self):
        catalog=json.loads(CATALOG.read_text(encoding='utf-8'))
        for value in catalog['events']:validate_event(value,catalog)
        with tempfile.TemporaryDirectory() as tmp:
            service,native=self.setup_service(tmp);service.reader=lambda _: 'Unknown command'
            self.assertEqual(service.snapshot()['state'],'unsupported')
            result=service.action('save',dict(request_id=str(uuid.uuid4()),event=event(),draft_revision=0))
            self.assertEqual(result['state'],'saved')
            self.assertEqual(len(service.snapshot()['drafts']),1)

    def test_draft_names_limits_and_stage_validation_never_write_live_catalog(self):
        with tempfile.TemporaryDirectory() as tmp:
            service,native=self.setup_service(tmp);before=service.path.read_bytes()
            bad=event();bad['id']='builtin_name'
            with self.assertRaises(AuthoringError):service.action('save',dict(request_id=str(uuid.uuid4()),event=bad,draft_revision=0))
            good=event();service.action('save',dict(request_id=str(uuid.uuid4()),event=good,draft_revision=0))
            ident=str(uuid.uuid4());p=dict(request_id=ident,draft_id=good['id'],draft_revision=1,world_epoch=EPOCH,catalog_revision=REV)
            self.assertTrue(service.action('validate',p)['result']['accepted'])
            self.assertEqual(service.path.read_bytes(),before)
            self.assertFalse((service.path.parent/f'staging/{ident}.json').exists())
            with service.db() as db:
                for i in range(63):db.execute('INSERT INTO drafts VALUES(?,?,?,?)',(f'custom_{i}',json.dumps(dict(good,id=f'custom_{i}')),1,0))
            with self.assertRaises(AuthoringError):service.action('save',dict(request_id=str(uuid.uuid4()),event=dict(good,id='custom_excess'),draft_revision=0))

    def test_huge_json_integer_is_a_field_error_without_receipt_or_crash(self):
        catalog=json.loads(CATALOG.read_text(encoding='utf-8'));bad=event();bad['minScore']=10**400
        with self.assertRaises(AuthoringError):validate_event(bad,catalog)

    def test_two_admin_instances_cannot_overwrite_the_same_draft_revision(self):
        with tempfile.TemporaryDirectory() as tmp:
            first,native=self.setup_service(tmp);first.action('save',dict(request_id=str(uuid.uuid4()),event=event(),draft_revision=0))
            second=Authoring(Path(tmp),native,reader=native);barrier=threading.Barrier(2)
            for service in (first,second):
                prepare=service.prepare
                def synchronized(action,payload,prepare=prepare):
                    result=prepare(action,payload);barrier.wait(timeout=5);return result
                service.prepare=synchronized
            with ThreadPoolExecutor(2) as pool:
                futures=[pool.submit(service.action,'save',dict(request_id=str(uuid.uuid4()),event=dict(event(),title=title),draft_revision=1)) for service,title in ((first,'one'),(second,'two'))]
                results=[future.result(timeout=8) for future in futures]
            self.assertEqual(sorted(r['state'] for r in results),['rejected','saved'])
            self.assertEqual(first.drafts()[0]['revision'],2)
            self.assertEqual(next(r for r in results if r['state']=='rejected')['code'],'stale_draft')

    def test_drafts_are_offline_idempotent_and_revision_checked(self):
        with tempfile.TemporaryDirectory() as tmp:
            service,native=self.setup_service(tmp);payload=dict(request_id=str(uuid.uuid4()),event=event(),draft_revision=0)
            first=service.action('save',payload);self.assertEqual(first['draft']['revision'],1)
            self.assertEqual(service.action('save',payload),first);self.assertEqual(native.calls,[])
            stale=dict(payload,request_id=str(uuid.uuid4()))
            with self.assertRaises(AuthoringError):service.action('save',stale)
            with self.assertRaises(AuthoringError):service.action('save',dict(payload,event=dict(event(),title='changed')))
            self.assertEqual(Authoring(Path(tmp),native,reader=native).drafts()[0]['event']['id'],'custom_test')

    def test_status_is_read_only_and_source_hash_mismatch_blocks_execution(self):
        with tempfile.TemporaryDirectory() as tmp:
            service,native=self.setup_service(tmp)
            self.assertEqual(service.snapshot()['state'],'ready');self.assertEqual(native.calls,['deadearth operator status'])
            status=native.status();status['sourceHash']='b'*64;service.reader=lambda _:json.dumps(status)
            self.assertEqual(service.snapshot()['state'],'catalog_mismatch')
            with self.assertRaises(AuthoringError):service.action('run',dict(request_id=str(uuid.uuid4()),event_id='custom_test',zone_id=ZONE,world_epoch=EPOCH,catalog_revision=REV))
            self.assertEqual(len(native.results),0)

    def test_real_reader_uses_configured_rcon_and_keeps_credentials_backend_only(self):
        with tempfile.TemporaryDirectory() as tmp:
            service,native=self.setup_service(tmp);service.reader=service.read_command
            with patch('dead_earth_director.admin_authoring.settings',return_value=('configured',25575,'PRIVATE_TEST')), \
                 patch('dead_earth_director.admin_authoring.rcon_command',return_value=json.dumps(native.status())) as command:
                value=service.snapshot();self.assertEqual(value['state'],'ready')
                command.assert_called_once_with(25575,'PRIVATE_TEST','deadearth operator status')
                self.assertNotIn('PRIVATE_TEST',json.dumps(value))

    def test_publish_preserves_libraries_and_requires_exact_saved_revision(self):
        with tempfile.TemporaryDirectory() as tmp:
            service,native=self.setup_service(tmp);service.action('save',dict(request_id=str(uuid.uuid4()),event=event(),draft_revision=0))
            ident=str(uuid.uuid4());payload=dict(request_id=ident,draft_id='custom_test',draft_revision=1,world_epoch=EPOCH,catalog_revision=REV)
            first=service.action('publish',payload)
            self.assertEqual(first['state'],'completed')
            stage=Path(tmp)/f'config/dead-earth/director/staging/{ident}.json'
            candidate=json.loads(stage.read_text(encoding='utf-8'));original=json.loads(CATALOG.read_text(encoding='utf-8'))
            for key in ['rules','sounds','traces','waves']:self.assertEqual(candidate[key],original[key])
            self.assertEqual(len(candidate['events']),len(original['events'])+1)
            self.assertEqual(service.action('publish',payload),first)
            self.assertEqual(sum(' apply ' in c for c in native.calls),1)
            with self.assertRaises(AuthoringError):service.action('publish',dict(payload,request_id=str(uuid.uuid4()),draft_revision=2))

    def test_uncertain_is_durable_and_never_retried(self):
        with tempfile.TemporaryDirectory() as tmp:
            service,native=self.setup_service(tmp);calls=[]
            def execute(ident,command):calls.append(command);return dict(state='uncertain',request_id=ident)
            native.execute=execute
            p=dict(request_id=str(uuid.uuid4()),event_id='custom_test',zone_id=ZONE,world_epoch=EPOCH,catalog_revision=REV)
            self.assertEqual(service.action('run',p)['state'],'uncertain')
            again=Authoring(Path(tmp),native,reader=native)
            self.assertEqual(again.action('run',p)['state'],'uncertain');self.assertEqual(len(calls),1)

    def test_http_operator_guards_strict_json_and_fixed_routes(self):
        with tempfile.TemporaryDirectory() as tmp:
            service,native=self.setup_service(tmp);root=Path(tmp)
            app=admin.Application(root/'bridge',root/'journal',root/'db',CATALOG)
            app.authoring=service
            server=admin.make_server(app,0);thread=threading.Thread(target=server.serve_forever,daemon=True);thread.start();port=server.server_address[1]
            def request(method,path,body=None,headers=None):
                conn=http.client.HTTPConnection('127.0.0.1',port,timeout=5);conn.request(method,path,body,headers or {})
                response=conn.getresponse();result=response.status,json.loads(response.read());conn.close();return result
            try:
                status,data=request('GET','/api/authoring');self.assertEqual(status,200)
                headers={'Content-Type':'application/json','Origin':f'http://127.0.0.1:{port}','X-Dead-Earth-Operator':data['nonce']}
                payload=json.dumps(dict(request_id=str(uuid.uuid4()),event=event(),draft_revision=0))
                self.assertEqual(request('POST','/api/authoring/save',payload)[0],403)
                self.assertEqual(request('POST','/api/authoring/save',payload,headers)[0],200)
                for body in ['{"request_id":"x","request_id":"y"}','{"value":NaN}', '{}']:
                    self.assertEqual(request('POST','/api/authoring/save',body,headers)[0],400)
                self.assertEqual(request('POST','/api/authoring/eval',payload,headers)[0],405)
                self.assertNotIn(data['nonce'],json.dumps(app.dashboard()))
            finally:server.shutdown();server.server_close();thread.join()
