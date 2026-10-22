import contextlib
import io
import json
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
from unittest import mock
from tools import legacy_port as port, legacy_server as server

ROOT=Path(__file__).resolve().parents[1]

class FullForkTests(unittest.TestCase):
    def test_mod_map_covers_original_and_references_packaged_dependencies(self):
        original=json.loads((ROOT/'metadata/mods.lock.json').read_text(encoding='utf-8'))
        rows=json.loads((ROOT/'legacy/metadata/mod-map.json').read_text(encoding='utf-8'))['mappings']
        originals={row['filename'] for row in original}
        self.assertEqual({row['original'] for row in rows},originals)
        self.assertEqual(len(rows),len(originals))
        ids={row['mod_id'] for row in port.full_pins()}|{'dead_earth'}
        for row in rows:self.assertTrue(set(row['legacy_mods'])<=ids,row['original'])

    def test_client_only_mods_do_not_enter_dedicated_server(self):
        server_files=port.entries('server');client_files=port.entries('client')
        for row in port.full_pins():
            if row['side'] == 'server': self.assertNotIn('.minecraft/mods/'+row['filename'],client_files.keys())
            else: self.assertIn('.minecraft/mods/'+row['filename'],client_files.keys())
            if row['side']=='client':self.assertNotIn('mods/'+row['filename'],server_files)
            else:self.assertIn('mods/'+row['filename'],server_files)
        self.assertIn('director/model.ps1',server_files)
        self.assertIn('director/docker/compose.cpu.yaml',server_files)
        self.assertEqual(json.loads(server_files['legacy-port.json'])['kind'],'full-fork')

    def test_compression_keeps_raw_value_without_granting_technology_emc(self):
        from tools import legacy_full_recipes
        products=legacy_full_recipes.products()
        progression=json.loads(products['legacy/pack/config/dead-earth/progression.json'])
        values=progression['rawEmc']
        self.assertNotIn('dead_earth:compressed_gravel_4|0',values)
        self.assertNotIn('excompressum:compressed_block|2',values)
        self.assertNotIn('dead_earth:compact_sieve_v5|0',values)
        self.assertNotIn('dead_earth:compact_mesh_diamond_v5|0',values)
        self.assertFalse(any('plate_' in token or 'circuit' in token for token in values))
        heavy=json.loads(products['legacy/pack/config/ExCompressum/HeavySieve.json'])
        self.assertEqual(heavy['options']['loss for default generated rewards (out of 9)'],0)
        for row in heavy['custom']['entries']:self.assertEqual(int(row['sourceCount']),9)

    def test_unavailable_model_keeps_bounded_fallback_and_strict_mode_refuses(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);(root/'logs').mkdir()
            args=SimpleNamespace(no_model_start=True,model_backend='auto',require_ai=False)
            with mock.patch.object(server,'model_ready',return_value=False),mock.patch.object(server.subprocess,'run') as spawn,contextlib.redirect_stdout(io.StringIO()):
                self.assertFalse(server.prepare_model(root,args));spawn.assert_not_called()
                args.require_ai=True
                with self.assertRaisesRegex(ValueError,'Pinned CPU model unavailable'):server.prepare_model(root,args)

    def test_model_readiness_requires_exact_local_config_and_digest(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);pin={'model':'test:4b','manifest_sha256':'a'*64}
            (root/'models.lock.json').write_text(json.dumps(pin),encoding='utf-8')
            def config(url='http://127.0.0.1:11434'):
                (root/'director.example.toml').write_text('[model]\nurl="'+url+'"\nmodel="test:4b"\ndigest="'+'a'*64+'"\n',encoding='utf-8')
            response=mock.MagicMock();response.__enter__.return_value=response
            opener=mock.Mock();opener.open.return_value=response
            with mock.patch.object(server.urllib.request,'build_opener',return_value=opener):
                config();response.read.return_value=json.dumps({'models':[{'name':'test:4b','digest':'a'*64}]}).encode()
                self.assertTrue(server.model_ready(root))
                response.read.return_value=json.dumps({'models':[{'name':'test:4b','digest':'b'*64}]}).encode()
                self.assertFalse(server.model_ready(root))
                opener.reset_mock();config('http://example.invalid:11434')
                self.assertFalse(server.model_ready(root));opener.open.assert_not_called()

if __name__=='__main__':unittest.main()
