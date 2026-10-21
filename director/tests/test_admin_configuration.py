import http.client
import json
from pathlib import Path
import tempfile
import threading
import unittest
import uuid
from unittest.mock import patch

from dead_earth_director import admin

EPOCH = '00000000-0000-4000-8000-000000000001'
CAMPAIGN = '00000000-0000-4000-8000-000000000002'
REQUEST = '00000000-0000-4000-8000-000000000003'
REVISION = 'a' * 64
CATALOG = Path(__file__).resolve().parents[2] / 'pack/config/dead-earth/director/catalog.json'


def snapshot():
    return dict(schema=1,status='ready',revision=REVISION,worldEpoch=EPOCH,campaignId=CAMPAIGN,
                sections=[dict(id='coordinator',label='Coordinator',fields=[dict(key='sightRadius',label='Sight',
                    type='integer',value=64,policy='hot',help='Loaded mobs update gradually.',min=1,max=128,step=1)])])


def payload(**updates):
    return dict(dict(request_id=REQUEST,world_epoch=EPOCH,revision=REVISION,mode='hot',
                     changes={'coordinator':{'sightRadius':80}}),**updates)


def reply(state='applied',accepted=True,errors=None):
    return dict(schema=1,accepted=accepted,state=state,requestId=REQUEST,revision='b'*64,errors=errors or [])


class ConfigurationTests(unittest.TestCase):
    def service(self,root,reader=None):
        self.assertTrue(hasattr(admin,'Configuration'),'Configuration service is missing')
        console=admin.OperatorConsole(root)
        return admin.Configuration(root,console,reader=reader or (lambda _:json.dumps(snapshot()))),console

    def test_snapshot_projects_native_schema_and_rejects_unbounded_or_unknown_values(self):
        with tempfile.TemporaryDirectory() as tmp:
            calls=[]
            def read(command):calls.append(command);return json.dumps(dict(snapshot(),password='PRIVATE'))
            service,_=self.service(Path(tmp),read)
            result=service.snapshot()
            self.assertEqual(result['state'],'ready')
            self.assertNotIn('PRIVATE',json.dumps(result))
            self.assertEqual(result['server']['sections'],snapshot()['sections'])
            self.assertEqual(calls,['deadearth config view'])
            broken=snapshot();broken['sections'][0]['fields'][0]['policy']='execute'
            service.reader=lambda _:json.dumps(broken)
            self.assertEqual(service.snapshot(),dict(schema=1,state='unsupported',server=None))

    def test_stages_validates_then_applies_once_and_changed_uuid_payload_conflicts(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp);calls=[]
            def read(command):
                calls.append(command)
                stage=json.loads((root/'config/dead-earth/config-staging'/f'{REQUEST}.json').read_text())
                self.assertEqual(stage,dict(schema=1,requestId=REQUEST,worldEpoch=EPOCH,baseRevision=REVISION,
                                           mode='hot',changes={'coordinator':{'sightRadius':80}}))
                return json.dumps(reply('validated'))
            service,_=self.service(root,read)
            def send(_port,_password,command):calls.append(command);return json.dumps(reply())
            with patch('dead_earth_director.admin_console.settings',return_value=('configured',25575,'PRIVATE')), \
                 patch('dead_earth_director.admin_console.rcon_command',side_effect=send):
                first=service.apply(payload())
                again,_=self.service(root,lambda _:self.fail('Replay must not reach server'))
                self.assertEqual(again.apply(payload()),first)
                self.assertEqual(again.receipt(REQUEST),first)
                with self.assertRaises(ValueError):again.apply(payload(changes={'coordinator':{'sightRadius':81}}))
            self.assertEqual(first['result']['state'],'applied')
            self.assertEqual(calls,[f'deadearth config validate {EPOCH} {REVISION} {REQUEST}',
                                    f'deadearth config apply {EPOCH} {REVISION} {REQUEST}'])

    def test_validation_errors_do_not_dispatch_and_uncertain_send_is_never_retried(self):
        from dead_earth_director.admin_console import RconFailure
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp)
            errors=[dict(section='coordinator',key='sightRadius',code='range',message='Expected 1..128')]
            service,console=self.service(root,lambda _:json.dumps(reply('rejected',False,errors)))
            result=service.apply(payload(changes={'coordinator':{'sightRadius':129}}))
            self.assertEqual(result['result']['errors'],errors)
            with console.db() as db:self.assertEqual(db.execute('SELECT COUNT(*) FROM commands').fetchone()[0],0)
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp);service,_=self.service(root,lambda _:json.dumps(reply('validated')));calls=[]
            def lost(*args):calls.append(args[-1]);raise RconFailure('uncertain')
            with patch('dead_earth_director.admin_console.settings',return_value=('configured',25575,'PRIVATE')), \
                 patch('dead_earth_director.admin_console.rcon_command',side_effect=lost):
                self.assertEqual(service.apply(payload())['state'],'uncertain')
                again,_=self.service(root,lambda _:self.fail('Uncertain replay must not send'))
                self.assertEqual(again.apply(payload())['state'],'uncertain')
            self.assertEqual(len(calls),1)

    def test_invalid_envelopes_do_not_create_stage_or_receipt(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp);service,_=self.service(root)
            for bad in ({},payload(command='stop'),payload(request_id='../x'),payload(revision='A'*64),
                        payload(world_epoch=EPOCH+'\nstop'),payload(mode='other'),payload(changes={}),
                        payload(changes={'../config':{'a':1}}),payload(changes={'coordinator':{'a':float('nan')}})):
                with self.subTest(bad=bad),self.assertRaises(ValueError):service.apply(bad)
            self.assertFalse((root/'config').exists())

    def test_nested_catalog_keys_remain_typed_and_huge_numbers_are_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp);value=snapshot();field=value['sections'][0]['fields'][0]
            field['key']='coefficients.valueNormalization';value['sections'][0]['id']='catalog-rules'
            service,_=self.service(root,lambda _:json.dumps(value))
            self.assertEqual(service.snapshot()['state'],'ready')
            service.validate_payload(payload(changes={'catalog-rules':{'coefficients.valueNormalization':2.5}}))
            with self.assertRaises(ValueError):service.validate_payload(payload(changes={'coordinator':{'a':10**1000}}))

    def test_restart_saved_is_not_reported_running_without_native_acknowledgement(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp);service,_=self.service(root)
            path=root/'runtime/compose/config-restart-state.json';path.parent.mkdir(parents=True)
            marker=dict(schema=1,state='ready',requestId=REQUEST,worldEpoch=EPOCH,revision='b'*64)
            path.write_text(json.dumps(marker))
            self.assertEqual(service.restart()['state'],'starting')
            service.reader=lambda _:json.dumps(dict(snapshot(),revision='b'*64))
            self.assertEqual(service.restart()['state'],'ready')
            marker['state']='starting';path.write_text(json.dumps(marker))
            self.assertEqual(service.restart()['state'],'starting')

    def test_unconfigured_transport_is_offline_and_conflicting_console_uuid_is_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp);service,_=self.service(root);service.reader=service.read_command
            with patch('dead_earth_director.admin_configuration.settings',return_value=('not_configured',None,'')):
                self.assertEqual(service.snapshot()['state'],'offline')
            service.reader=lambda _:json.dumps(reply('validated'))
            def conflict(*_):raise ValueError('conflicting_request')
            service.console.execute=conflict
            with self.assertRaisesRegex(ValueError,'conflicting_request'):service.apply(payload())

    def test_unknown_native_status_and_missing_receipt_do_not_claim_definitive_outcome(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp);service,_=self.service(root,lambda _:json.dumps(dict(snapshot(),status='unknown')))
            self.assertEqual(service.snapshot(),dict(schema=1,state='unknown',server=None))
            service.reader=lambda _:json.dumps(reply('validated'))
            service.console.execute=lambda *_:dict(state='uncertain')
            self.assertEqual(service.apply(payload())['state'],'uncertain')
            errors=[dict(section='',key='',code='unknown_request',message='Unknown request')]
            service.reader=lambda _:json.dumps(reply('rejected',False,errors))
            self.assertEqual(service.receipt(REQUEST)['state'],'uncertain')
            service.reader=lambda _:json.dumps(reply())
            self.assertEqual(service.receipt(REQUEST)['result']['state'],'applied')

    def test_console_busy_before_dispatch_is_a_durable_recoverable_rejection(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp);service,console=self.service(root,lambda _:json.dumps(reply('validated')))
            console.lock.acquire()
            try:result=service.apply(payload())
            finally:console.lock.release()
            self.assertEqual(result['state'],'completed')
            self.assertFalse(result['result']['accepted']);self.assertEqual(result['result']['state'],'rejected')
            self.assertEqual(result['result']['errors'][0]['code'],'busy')
            with console.db() as db:self.assertEqual(db.execute('SELECT COUNT(*) FROM commands').fetchone()[0],0)
            again,_=self.service(root,lambda _:self.fail('Known rejection must not resend'))
            self.assertEqual(again.apply(payload()),result);self.assertEqual(again.receipt(REQUEST),result)

    def test_full_console_journal_before_dispatch_is_a_durable_recoverable_rejection(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp);service,console=self.service(root,lambda _:json.dumps(reply('validated')))
            with console.db() as db:
                db.executemany('INSERT INTO commands VALUES(?,?,?,?)',
                               [(str(uuid.UUID(int=i+100)),'list','{}',0) for i in range(5000)])
                db.commit()
            result=service.apply(payload())
            self.assertEqual(result['state'],'completed')
            self.assertFalse(result['result']['accepted']);self.assertEqual(result['result']['errors'][0]['code'],'journal_full')
            with console.db() as db:self.assertIsNone(db.execute('SELECT request_id FROM commands WHERE request_id=?',(REQUEST,)).fetchone())
            self.assertEqual(service.receipt(REQUEST),result)

    def test_read_only_validation_outage_is_recoverable_without_an_apply_receipt(self):
        from dead_earth_director.admin_console import RconFailure
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp)
            def lost(_):raise RconFailure('uncertain')
            service,console=self.service(root,lost)
            result=service.apply(payload())
            self.assertEqual(result['state'],'completed');self.assertFalse(result['result']['accepted'])
            self.assertEqual(result['result']['errors'][0]['code'],'validation_unavailable')
            with console.db() as db:self.assertEqual(db.execute('SELECT COUNT(*) FROM commands').fetchone()[0],0)
            again,_=self.service(root,lambda _:self.fail('Do not retry rejected validation'))
            self.assertEqual(again.apply(payload()),result);self.assertEqual(again.receipt(REQUEST),result)

    def test_staging_failure_is_recoverable_and_never_changes_existing_files(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp);service,console=self.service(root,lambda _:self.fail('No native command before staging'))
            (root/'config').write_bytes(b'preserved file')
            result=service.apply(payload())
            self.assertEqual(result['state'],'completed');self.assertFalse(result['result']['accepted'])
            self.assertEqual(result['result']['errors'][0]['code'],'staging_unavailable')
            self.assertEqual((root/'config').read_bytes(),b'preserved file')
            with console.db() as db:self.assertEqual(db.execute('SELECT COUNT(*) FROM commands').fetchone()[0],0)

    def test_http_enforces_origin_nonce_strict_body_and_receipt_query(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp);service,_=self.service(root,lambda _:json.dumps(reply('rejected',False)))
            app=admin.Application(root/'bridge',root/'journal',root/'db',CATALOG);app.configuration=service
            server=admin.make_server(app,0);thread=threading.Thread(target=server.serve_forever,daemon=True);thread.start()
            port=server.server_address[1]
            def request(method,path,body=None,headers=None):
                conn=http.client.HTTPConnection('127.0.0.1',port,timeout=5);conn.request(method,path,body,headers or {})
                response=conn.getresponse();result=response.status,json.loads(response.read());conn.close();return result
            try:
                service.reader=lambda _:json.dumps(snapshot())
                code,data=request('GET','/api/config');self.assertEqual(code,200)
                self.assertEqual(set(data),{'schema','state','server','nonce'})
                headers={'Content-Type':'application/json','Origin':f'http://127.0.0.1:{port}',
                         'X-Dead-Earth-Operator':data['nonce']}
                body=json.dumps(payload())
                self.assertEqual(request('POST','/api/config/apply',body)[0],403)
                self.assertEqual(request('POST','/api/config/apply',body,dict(headers,Origin='https://evil.test'))[0],403)
                for bad in ('{}','{"a":NaN}','{"request_id":"x","request_id":"y"}'):
                    self.assertEqual(request('POST','/api/config/apply',bad,headers)[0],400)
                service.reader=lambda _:json.dumps(reply('rejected',False))
                self.assertEqual(request('POST','/api/config/apply',body,headers)[1]['result']['state'],'rejected')
                self.assertEqual(request('GET','/api/config/receipt?request_id='+REQUEST)[1]['result']['state'],'rejected')
                self.assertEqual(request('GET','/api/config/receipt?request_id='+REQUEST+'&request_id='+REQUEST)[0],400)
                self.assertEqual(request('GET','/api/config?extra=1')[0],404)
                self.assertEqual(request('POST','/api/config/apply?retry=1',body,headers)[0],405)
            finally:server.shutdown();server.server_close();thread.join()
