"""Narrow pre-client visual preparation retry; no Java or game launch."""
import hashlib
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
from tools import verify_legacy_visual as visual

class VisualPreparationRetryTests(unittest.TestCase):
    def test_failed_pre_client_receipts_only_and_preserved_hashes(self):
        with tempfile.TemporaryDirectory() as folder:
            target = Path(folder)
            epoch = '5c0685d0-f238-4af0-9b4b-97f13ffbc7d7'
            run = '00f1c6d0-8020-495b-b77c-39d946af27b6'
            (target/'mods').mkdir()
            (target/'mods'/visual.legacy_port.MOD_NAME).write_bytes(b'current-game')
            (target/'mods'/visual.COMPANION).write_bytes(b'failed-showcase')
            backup=target/'visual-preserved-native-probes'; backup.mkdir()
            for name in ('deadearth-port-probe.jar.disabled','deadearth-restore-probe.jar','server.properties.before'):
                (backup/name).write_bytes(name.encode())
            docs = {
                'visual-showcase-request.json': dict(schema=1,kind='dead-earth-visual-showcase-request',runId=run,worldEpoch=epoch,serverDirectory=str(target),nativeReport='port-probe.json'),
                'visual-verification.json': dict(schema=1,runId=run,worldEpoch=epoch,serverDirectory=str(target),passed=False,serverExitCode=0,error='RuntimeError: Showcase server preparation failed; inspect visual-showcase-server.json'),
                'visual-server-process.json': dict(schema=1,runId=run,worldEpoch=epoch,state='stopped',exitCode=0),
                'visual-showcase-server.json':dict(passed=False,error='java.lang.IllegalStateException: real loaded compressed material')}
            def save():
                for name,value in docs.items(): (target/name).write_text(json.dumps(value),encoding='utf-8')
            save()
            with patch.object(visual.legacy_port,'compiled_jar',return_value=b'current-game'):
                manifest=visual.retry_preparation_inputs(target,epoch,'port-probe.json')
                self.assertEqual(manifest['preservedSha256']['server.properties.before'],hashlib.sha256(b'server.properties.before').hexdigest())
                for name,key,value in [('visual-verification.json','clientDirectory','somewhere'),('visual-server-process.json','state','saving'),('visual-server-process.json','exitCode',1),('visual-verification.json','passed',True),('visual-showcase-request.json','worldEpoch','different')]:
                    original=dict(docs[name]); docs[name][key]=value;save()
                    with self.subTest(key=key),self.assertRaises(ValueError):visual.retry_preparation_inputs(target,epoch,'port-probe.json')
                    docs[name]=original;save()
                (target/'client-showcase.json').write_text('{}',encoding='utf-8')
                with self.assertRaises(ValueError):visual.retry_preparation_inputs(target,epoch,'port-probe.json')
                (target/'client-showcase.json').unlink()
                with patch.object(visual.legacy_port,'compiled_jar',return_value=b'changed'):
                    with self.assertRaises(ValueError):visual.retry_preparation_inputs(target,epoch,'port-probe.json')
                (target/'server.properties').write_bytes(b'failed properties')
                (target/'visual-showcase-build').mkdir()
                (target/'visual-showcase-build/build.json').write_text('{}',encoding='utf-8')
                (target/'logs').mkdir(); (target/'logs/latest.log').write_text('failed preparation',encoding='utf-8')
                world=target/'probe_world'; world.mkdir(); (world/'retained').write_bytes(b'untouched platform')
                restored=visual.archive_failed_preparation(target,epoch,'port-probe.json')
                archive=Path(restored['archiveDirectory'])
                self.assertTrue(restored['restoredNativeHashesVerified'])
                self.assertEqual((target/'server.properties').read_bytes(),b'server.properties.before')
                self.assertEqual((archive/'server.properties').read_bytes(),b'failed properties')
                self.assertTrue((archive/'mods'/visual.COMPANION).is_file())
                self.assertTrue((archive/'visual-showcase-build/build.json').is_file())
                self.assertTrue((target/'logs/latest.log').is_file())
                self.assertTrue((archive/'logs/latest.log').is_file())
                self.assertEqual((world/'retained').read_bytes(),b'untouched platform')
                self.assertFalse((target/'visual-showcase-request.json').exists())
                self.assertEqual((target/'mods/deadearth-restore-probe.jar').read_bytes(),b'deadearth-restore-probe.jar')

if __name__=='__main__': unittest.main()
