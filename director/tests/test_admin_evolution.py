import copy
import http.client
import json
from pathlib import Path
import tempfile
import threading
import unittest
from unittest.mock import patch
import uuid

from dead_earth_director import admin

CAMPAIGN='00000000-0000-4000-8000-000000000001'
EPOCH='00000000-0000-4000-8000-000000000002'
REQUEST='00000000-0000-4000-8000-000000000003'
CATALOG=Path(__file__).resolve().parents[2]/'pack/config/dead-earth/director/catalog.json'


def status():
    return dict(status='ready',enabled=True,campaignId=CAMPAIGN,worldEpoch=EPOCH,revision=7,configRevision='a'*64,
                phase=2,name='Паразиты <script>',progress=.25,points=22500.5,phaseStartPoints=18000,
                nextPhasePoints=36000,rate=.25,paused=False,migration='active_campaign_recalculation',
                unlockedEntities=['mutationcraft:assimilated_human'],abilities=['infection'],
                history=[dict(atTick=120,**{'from':1,'to':2},source='operator',requestId=None)])


def payload(**changes):
    return dict(dict(request_id=REQUEST,campaign_id=CAMPAIGN,world_epoch=EPOCH,revision=7,delta=1),**changes)


class EvolutionTests(unittest.TestCase):
    def service(self,root,reader=None):
        self.assertTrue(hasattr(admin,'Evolution'),'Evolution operator service is missing')
        console=admin.OperatorConsole(root)
        service=admin.Evolution(root,console,reader=reader or (lambda _: '[DeadEarth] '+json.dumps(status())))
        return service,console

    def test_status_reads_only_fixed_command_and_old_servers_are_graceful(self):
        with tempfile.TemporaryDirectory() as tmp:
            calls=[]
            def reader(command):calls.append(command);return '[DeadEarth] '+json.dumps(status())
            service,console=self.service(Path(tmp),reader)
            value=service.snapshot()
            self.assertEqual(value['state'],'ready');self.assertEqual(value['server'],status())
            self.assertEqual(calls,['deadearth evolution status'])
            with console.db() as db:self.assertEqual(db.execute('SELECT COUNT(*) FROM commands').fetchone()[0],0)
            for reply in ('Unknown command','{}','{"status":NaN}','{"status":"ready","status":"paused"}'):
                service.reader=lambda _,reply=reply:reply
                self.assertEqual(service.snapshot(),dict(state='unsupported',server=None))

    def test_read_transport_uses_backend_password_and_returns_no_exception_details(self):
        with tempfile.TemporaryDirectory() as tmp:
            service,_=self.service(Path(tmp));service.reader=service.read_command
            with patch('dead_earth_director.admin_evolution.settings',return_value=('configured',25575,'PRIVATE_TEST')), \
                 patch('dead_earth_director.admin_evolution.rcon_command',return_value=json.dumps(status())) as transport:
                value=service.snapshot();self.assertEqual(value['state'],'ready')
                self.assertEqual(transport.call_args.args,(25575,'PRIVATE_TEST','deadearth evolution status'))
                self.assertNotIn('PRIVATE_TEST',json.dumps(value))
            with patch('dead_earth_director.admin_evolution.settings',side_effect=OSError('PRIVATE_PATH')):
                self.assertEqual(service.snapshot(),dict(state='offline',server=None))

    def test_gson_omitted_nullable_threshold_and_automatic_request_id_are_normalized(self):
        with tempfile.TemporaryDirectory() as tmp:
            native=status();native.update(phase=5,progress=1,status='paused',paused=True)
            native.pop('nextPhasePoints');native['history'][0].pop('requestId')
            native['history'][0]['source']='automatic'
            service,_=self.service(Path(tmp),lambda _:json.dumps(native))
            result=service.snapshot()
            self.assertEqual(result['state'],'ready')
            self.assertIsNone(result['server']['nextPhasePoints'])
            self.assertIsNone(result['server']['history'][0]['requestId'])

    def test_disabled_controller_is_valid_status_with_manual_controls_unavailable(self):
        with tempfile.TemporaryDirectory() as tmp:
            service,_=self.service(Path(tmp),lambda _:json.dumps(dict(status(),status='disabled',enabled=False)))
            result=service.snapshot();self.assertEqual(result['state'],'ready')
            self.assertFalse(result['server']['enabled']);self.assertEqual(result['server']['status'],'disabled')

    def test_shift_uses_durable_console_receipt_and_replay_never_sends_twice(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp);service,console=self.service(root);calls=[]
            def transport(port,password,command):
                calls.append(command)
                with console.db() as db:
                    stored=db.execute('SELECT result FROM commands WHERE request_id=?',(REQUEST,)).fetchone()
                self.assertEqual(json.loads(stored[0])['state'],'uncertain','Receipt must be committed before network send')
                return '[DeadEarth] '+json.dumps(dict(accepted=True,code='shifted',evolution=dict(status(),phase=3,revision=8)))
            with patch('dead_earth_director.admin_console.settings',return_value=('configured',25575,'PRIVATE_TEST')), \
                 patch('dead_earth_director.admin_console.rcon_command',side_effect=transport):
                first=service.shift(payload())
                self.assertEqual(first['state'],'completed');self.assertTrue(first['result']['accepted'])
                again=admin.Evolution(root,admin.OperatorConsole(root),reader=lambda _: 'must not read for receipt replay')
                self.assertEqual(again.shift(payload()),first)
                with self.assertRaises(ValueError):again.shift(payload(delta=-1))
            self.assertEqual(calls,[f'deadearth evolution shift {CAMPAIGN} {EPOCH} 7 {REQUEST} 1'])

    def test_uncertain_and_truncated_responses_are_not_resent(self):
        from dead_earth_director.admin_console import RconFailure
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp);service,_=self.service(root);calls=[]
            def lost(*args):calls.append(args[-1]);raise RconFailure('uncertain')
            with patch('dead_earth_director.admin_console.settings',return_value=('configured',25575,'PRIVATE_TEST')), \
                 patch('dead_earth_director.admin_console.rcon_command',side_effect=lost):
                self.assertEqual(service.shift(payload())['state'],'uncertain')
                self.assertEqual(admin.Evolution(root,admin.OperatorConsole(root)).shift(payload())['state'],'uncertain')
            self.assertEqual(len(calls),1)
            service.console.execute=lambda *_:dict(state='completed',output='not JSON',truncated=True)
            self.assertEqual(service.shift(payload(request_id=str(uuid.uuid4())))['state'],'uncertain')

    def test_request_schema_blocks_injection_bool_overflow_and_extras_before_receipt(self):
        with tempfile.TemporaryDirectory() as tmp:
            service,console=self.service(Path(tmp))
            bad=[payload(delta=0),payload(delta=True),payload(delta=2),payload(revision=True),payload(revision=-1),
                 payload(revision=2**63),payload(campaign_id='x stop'),payload(world_epoch=EPOCH+'\nstop'),
                 payload(request_id='bad'),dict(payload(),command='stop'),{},[]]
            for value in bad:
                with self.subTest(value=value),self.assertRaises(ValueError):service.shift(value)
            with console.db() as db:self.assertEqual(db.execute('SELECT COUNT(*) FROM commands').fetchone()[0],0)

    def test_native_rejections_keep_code_and_new_state_without_second_mutation(self):
        with tempfile.TemporaryDirectory() as tmp:
            service,_=self.service(Path(tmp))
            for code in ('stale_revision','stale_epoch','phase_limit','journal_full'):
                service.console.execute=lambda *_:dict(state='completed',output=json.dumps(dict(accepted=False,code=code,evolution=dict(status(),revision=8))))
                result=service.shift(payload());self.assertEqual(result['result']['code'],code)
                self.assertFalse(result['result']['accepted']);self.assertEqual(result['result']['evolution']['revision'],8)

    def test_http_exact_routes_origin_nonce_and_strict_json(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp);service,_=self.service(root)
            app=admin.Application(root/'bridge',root/'journal',root/'db',CATALOG);app.evolution=service
            service.console.execute=lambda *_:dict(state='completed',output=json.dumps(dict(accepted=False,code='phase_limit',evolution=status())))
            server=admin.make_server(app,0);thread=threading.Thread(target=server.serve_forever,daemon=True);thread.start();port=server.server_address[1]
            def request(method,path,body=None,headers=None):
                conn=http.client.HTTPConnection('127.0.0.1',port,timeout=5);conn.request(method,path,body,headers or {})
                response=conn.getresponse();result=response.status,json.loads(response.read());conn.close();return result
            try:
                code,data=request('GET','/api/evolution');self.assertEqual(code,200);self.assertEqual(set(data),{'state','server','nonce'})
                headers={'Content-Type':'application/json','Origin':f'http://127.0.0.1:{port}','X-Dead-Earth-Operator':data['nonce']}
                body=json.dumps(payload())
                self.assertEqual(request('POST','/api/evolution/shift',body)[0],403)
                self.assertEqual(request('POST','/api/evolution/shift',body,dict(headers,Origin='https://evil.test'))[0],403)
                self.assertEqual(request('POST','/api/evolution/shift',body,dict(headers,**{'X-Dead-Earth-Operator':'wrong'}))[0],403)
                self.assertEqual(request('POST','/api/evolution/shift',body,headers)[1]['result']['code'],'phase_limit')
                def conflict(*_):raise ValueError('conflicting_request')
                service.console.execute=conflict
                self.assertEqual(request('POST','/api/evolution/shift',body,headers)[1]['error'],'conflicting_request')
                for bad in ('{}','{"delta":NaN}','{"request_id":"x","request_id":"y"}',json.dumps(dict(payload(),command='stop'))):
                    self.assertEqual(request('POST','/api/evolution/shift',bad,headers)[0],400)
                self.assertEqual(request('POST','/api/evolution/shift?retry=1',body,headers)[0],405)
                self.assertEqual(request('GET','/api/evolution?anything=1')[0],404)
                self.assertNotIn(data['nonce'],json.dumps(app.dashboard()))
            finally:server.shutdown();server.server_close();thread.join()
