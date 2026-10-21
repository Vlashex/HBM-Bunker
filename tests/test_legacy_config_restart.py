"""Exercise restart supervision using real bounded marker files and fake Java processes."""
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import sys
import threading
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT/'director'))
spec = importlib.util.spec_from_file_location('config_restart_runtime', ROOT/'legacy/docker/runtime.py')
runtime = importlib.util.module_from_spec(spec);spec.loader.exec_module(runtime)
REQUEST = '00000000-0000-4000-8000-000000000001'
EPOCH = '00000000-0000-4000-8000-000000000002'
OLD = 'a'*64
NEW = 'b'*64


def pending(root,request=REQUEST):
    directory=root/'runtime/compose';directory.mkdir(parents=True,exist_ok=True)
    path=root/'config/dead-earth/coordinator.json';path.parent.mkdir(parents=True,exist_ok=True);path.write_bytes(b'{"new":true}')
    backup=directory/'config-backups'/request/'coordinator.json';backup.parent.mkdir(parents=True,exist_ok=True)
    backup.write_bytes(b'{"old":true}')
    marker=dict(schema=1,requestId=request,worldEpoch=EPOCH,revision=NEW)
    manifest=dict(marker,oldRevision=OLD,files=[dict(id='coordinator',sha256=hashlib.sha256(b'{"old":true}').hexdigest())])
    (directory/'config-restart-request.json').write_text(json.dumps(marker))
    (directory/'config-restart-backup.json').write_text(json.dumps(manifest))
    return marker,manifest


class Input(io.StringIO):
    def close(self):pass


class Java:
    def __init__(self,fail=False):
        self.stdin=Input();self.stdout=io.StringIO('');self.pid=10;self.returncode=None;self.fail=fail
    def poll(self):
        if self.fail:self.returncode=1
        elif 'stop\n' in self.stdin.getvalue():self.returncode=0
        return self.returncode


class RestartTests(unittest.TestCase):
    def coordinator(self,root):
        self.assertTrue(hasattr(runtime,'ConfigRestart'),'Restart coordinator is missing')
        return runtime.ConfigRestart(root)

    def test_manifest_restore_uses_fixed_ids_and_verifies_every_backup_before_writing(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp);_,manifest=pending(root);restart=self.coordinator(root)
            restart.load();restart.restore()
            self.assertEqual((root/'config/dead-earth/coordinator.json').read_bytes(),b'{"old":true}')
            (root/'config/dead-earth/coordinator.json').write_bytes(b'{"new":true}')
            manifest['files'].append(dict(id='../server.properties',sha256='c'*64))
            (root/'runtime/compose/config-restart-backup.json').write_text(json.dumps(manifest))
            with self.assertRaises(ValueError):self.coordinator(root).load()
            self.assertEqual((root/'config/dead-earth/coordinator.json').read_bytes(),b'{"new":true}')
            manifest['files']=manifest['files'][:1];manifest['files'][0]['sha256']='c'*64
            (root/'runtime/compose/config-restart-backup.json').write_text(json.dumps(manifest))
            with self.assertRaises(ValueError):self.coordinator(root).load()
            self.assertEqual((root/'config/dead-earth/coordinator.json').read_bytes(),b'{"new":true}')

    def test_marker_rejects_extra_fields_unbounded_files_and_non_uuid_scope(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp);marker,_=pending(root)
            for bad in (dict(marker,command='stop'),dict(marker,requestId='../elsewhere'),dict(marker,worldEpoch='x'),dict(marker,revision='A'*64)):
                (root/'runtime/compose/config-restart-request.json').write_text(json.dumps(bad))
                with self.subTest(bad=bad),self.assertRaises(ValueError):self.coordinator(root).load()
            (root/'runtime/compose/config-restart-request.json').write_text(' '*8193)
            with self.assertRaises(ValueError):self.coordinator(root).load()

    def test_restart_flushes_stops_and_waits_for_matching_native_readiness(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp);stop=threading.Event();processes=[];ticks=[0];seen=[]
            def launch():processes.append(Java());return processes[-1]
            def pause(_seconds):
                ticks[0]+=1
                if ticks[0]==1:pending(root)
                state=root/'runtime/compose/config-restart-state.json'
                if state.exists():
                    value=json.loads(state.read_text());seen.append(value['state'])
                    if value['state']=='ready':stop.set()
                self.assertLess(ticks[0],30,'Supervisor did not finish a bounded fixture')
            probes=[]
            def probe(epoch,revision):probes.append((epoch,revision));return len(probes)>1
            self.assertTrue(hasattr(runtime,'MinecraftSupervisor'),'Minecraft supervisor is missing')
            supervisor=runtime.MinecraftSupervisor(root,'4096',stop,launcher=launch,probe=probe,pause=pause,clock=lambda:ticks[0])
            self.assertEqual(supervisor.run(),0)
            self.assertEqual(len(processes),2)
            self.assertEqual(processes[0].stdin.getvalue(),'save-all flush\nstop\n')
            self.assertTrue(probes and all(p==(EPOCH,NEW) for p in probes))
            self.assertIn('starting',seen);self.assertIn('ready',seen)
            self.assertFalse((root/'runtime/compose/config-restart-request.json').exists())

    def test_failed_new_start_restores_old_config_once_and_reports_failure(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp);pending(root);stop=threading.Event();processes=[];ticks=[0]
            def launch():
                if processes:self.assertEqual((root/'config/dead-earth/coordinator.json').read_bytes(),b'{"old":true}')
                processes.append(Java(fail=len(processes)==0));return processes[-1]
            def pause(_):
                ticks[0]+=1
                state=json.loads((root/'runtime/compose/config-restart-state.json').read_text())
                if state['state']=='failed':stop.set()
                self.assertLess(ticks[0],20)
            self.assertTrue(hasattr(runtime,'MinecraftSupervisor'),'Minecraft supervisor is missing')
            value=runtime.MinecraftSupervisor(root,'4096',stop,launcher=launch,
                probe=lambda epoch,revision:revision==OLD,pause=pause)
            self.assertEqual(value.run(),0);self.assertEqual(len(processes),2)
            state=json.loads((root/'runtime/compose/config-restart-state.json').read_text())
            self.assertEqual(state['state'],'failed');self.assertTrue(state['rollback']);self.assertEqual(state['code'],'rolled_back')

    def test_external_sigterm_cancels_queued_restart_without_launching_second_java(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp);stop=threading.Event();processes=[]
            def launch():processes.append(Java());return processes[-1]
            def pause(_):
                pending(root);stop.set()
            self.assertTrue(hasattr(runtime,'MinecraftSupervisor'),'Minecraft supervisor is missing')
            value=runtime.MinecraftSupervisor(root,'4096',stop,launcher=launch,probe=lambda *_:False,pause=pause)
            self.assertEqual(value.run(),0);self.assertEqual(len(processes),1)
            self.assertEqual(processes[0].stdin.getvalue(),'save-all flush\nstop\n')
            state=json.loads((root/'runtime/compose/config-restart-state.json').read_text())
            self.assertEqual(state['state'],'failed');self.assertEqual(state['code'],'shutdown_requested')

    def test_invalid_marker_cannot_interrupt_running_java(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp);stop=threading.Event();processes=[];ticks=[0]
            def launch():processes.append(Java());return processes[-1]
            def pause(_):
                ticks[0]+=1
                if ticks[0]==1:
                    pending(root)
                    path=root/'runtime/compose/config-restart-request.json'
                    marker=json.loads(path.read_text());marker['command']='stop';path.write_text(json.dumps(marker))
                if ticks[0]==3:stop.set()
                self.assertLess(ticks[0],10)
            self.assertTrue(hasattr(runtime,'MinecraftSupervisor'),'Minecraft supervisor is missing')
            value=runtime.MinecraftSupervisor(root,'4096',stop,launcher=launch,probe=lambda *_:False,pause=pause)
            self.assertEqual(value.run(),0);self.assertEqual(len(processes),1)
            self.assertEqual(processes[0].stdin.getvalue(),'save-all flush\nstop\n')

    def test_failed_rollback_stays_in_same_container_and_never_launches_again(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp);pending(root);stop=threading.Event();processes=[]
            def launch():processes.append(Java(fail=True));return processes[-1]
            def pause(_):
                state=json.loads((root/'runtime/compose/config-restart-state.json').read_text())
                if state['state']=='failed':stop.set()
            self.assertTrue(hasattr(runtime,'MinecraftSupervisor'),'Minecraft supervisor is missing')
            value=runtime.MinecraftSupervisor(root,'4096',stop,launcher=launch,probe=lambda *_:False,pause=pause)
            self.assertEqual(value.run(),2);self.assertEqual(len(processes),2)
            state=json.loads((root/'runtime/compose/config-restart-state.json').read_text())
            self.assertEqual(state['state'],'failed');self.assertEqual(state['code'],'rollback_startup_failed')
            # A supervisor restart must not reset the already consumed fallback.
            again=runtime.MinecraftSupervisor(root,'4096',stop,launcher=lambda:self.fail('No third startup'),pause=pause)
            self.assertEqual(again.run(),2)

    def test_sigterm_during_readiness_never_reports_restart_ready(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp);pending(root);stop=threading.Event();processes=[]
            def launch():processes.append(Java());return processes[-1]
            def probe(*_):stop.set();return True
            value=runtime.MinecraftSupervisor(root,'4096',stop,launcher=launch,probe=probe,pause=lambda _:None)
            self.assertEqual(value.run(),0);self.assertEqual(len(processes),1)
            state=json.loads((root/'runtime/compose/config-restart-state.json').read_text())
            self.assertEqual(state['state'],'failed');self.assertEqual(state['code'],'shutdown_requested')

    def test_sigterm_during_restore_never_launches_fallback_java(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp);pending(root);stop=threading.Event();processes=[]
            def launch():processes.append(Java(fail=True));return processes[-1]
            original=runtime.ConfigRestart.restore
            def restore(restart):original(restart);stop.set()
            value=runtime.MinecraftSupervisor(root,'4096',stop,launcher=launch,probe=lambda *_:False,pause=lambda _:None)
            with patch.object(runtime.ConfigRestart,'restore',restore):
                self.assertEqual(value.run(),0)
            self.assertEqual(len(processes),1)

    def test_native_readiness_requires_exact_revision_scope_and_strict_reply(self):
        with tempfile.TemporaryDirectory() as tmp:
            value=runtime.MinecraftSupervisor(Path(tmp),'4096',threading.Event())
            with patch('dead_earth_director.admin_console.settings',return_value=('configured',25575,'PRIVATE')), \
                 patch('dead_earth_director.admin_console.rcon_command') as transport:
                for raw in ('{}','{"schema":1,"status":"ready","worldEpoch":"'+EPOCH+'","revision":"'+OLD+'","revision":"'+NEW+'"}',
                            json.dumps(dict(schema=1,status='ready',worldEpoch=EPOCH,revision=OLD))):
                    transport.return_value=raw
                    self.assertFalse(value.ready(EPOCH,NEW))
                transport.return_value=json.dumps(dict(schema=1,status='ready',worldEpoch=EPOCH,revision=NEW))
                self.assertTrue(value.ready(EPOCH,NEW))
                self.assertEqual(transport.call_args.args,(25575,'PRIVATE','deadearth config view'))

    def test_supervisor_crash_after_fallback_dispatch_never_resets_retry_budget(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp);marker,_=pending(root);stop=threading.Event()
            state=dict(marker,state='starting',rollback=True,fallbackAttempted=True)
            (root/'runtime/compose/config-restart-state.json').write_text(json.dumps(state))
            value=runtime.MinecraftSupervisor(root,'4096',stop,launcher=lambda:self.fail('Fallback already attempted'),pause=lambda _:stop.set())
            self.assertEqual(value.run(),2)

    def test_new_request_after_recovered_rollback_gets_its_own_startup_attempt(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp);pending(root);restart=self.coordinator(root);restart.load()
            restart.rollback=True;restart.fallback_attempted=True;restart.consume()
            pending(root,'00000000-0000-4000-8000-000000000003')
            restart.load()
            self.assertFalse(restart.rollback);self.assertFalse(restart.fallback_attempted)

    def test_java_launch_failure_restores_old_config_and_retries_once(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp);pending(root);stop=threading.Event();calls=[]
            def launch():
                calls.append(True)
                if len(calls)==1:raise OSError('Process cannot start')
                self.assertEqual((root/'config/dead-earth/coordinator.json').read_bytes(),b'{"old":true}')
                return Java()
            def pause(_):
                state=json.loads((root/'runtime/compose/config-restart-state.json').read_text())
                if state['state']=='failed':stop.set()
            value=runtime.MinecraftSupervisor(root,'4096',stop,launcher=launch,probe=lambda epoch,revision:revision==OLD,pause=pause)
            self.assertEqual(value.run(),0);self.assertEqual(len(calls),2)
            state=json.loads((root/'runtime/compose/config-restart-state.json').read_text())
            self.assertEqual(state['state'],'failed');self.assertEqual(state['code'],'rolled_back')

    def test_explicit_start_after_sigterm_resumes_fallback_without_unlocking_real_failures(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp);marker,_=pending(root);stop=threading.Event();processes=[]
            state=dict(marker,state='failed',code='shutdown_requested',rollback=True,fallbackAttempted=True)
            (root/'runtime/compose/config-restart-state.json').write_text(json.dumps(state))
            def launch():
                self.assertEqual((root/'config/dead-earth/coordinator.json').read_bytes(),b'{"old":true}')
                processes.append(Java());return processes[-1]
            def pause(_):
                value=json.loads((root/'runtime/compose/config-restart-state.json').read_text())
                if value['state']=='failed':stop.set()
            value=runtime.MinecraftSupervisor(root,'4096',stop,launcher=launch,probe=lambda epoch,revision:revision==OLD,pause=pause)
            self.assertEqual(value.run(),0);self.assertEqual(len(processes),1)
            value=json.loads((root/'runtime/compose/config-restart-state.json').read_text())
            self.assertEqual(value['code'],'rolled_back')

    def test_post_launch_receipt_io_error_cannot_restore_config_under_running_java(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp);pending(root);stop=threading.Event();processes=[]
            def launch():processes.append(Java());return processes[-1]
            original=runtime.atomic_json
            def persist(path,value):
                if path.name=='minecraft.json' and value.get('state')=='running':raise OSError('Disk unavailable')
                original(path,value)
            value=runtime.MinecraftSupervisor(root,'4096',stop,launcher=launch,probe=lambda *_:False,pause=lambda _:stop.set())
            with patch.object(runtime,'atomic_json',persist),self.assertRaises(OSError):value.run()
            self.assertEqual(len(processes),1)
            self.assertEqual((root/'config/dead-earth/coordinator.json').read_bytes(),b'{"new":true}')
