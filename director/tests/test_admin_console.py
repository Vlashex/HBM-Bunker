import contextlib
import http.client
import json
from pathlib import Path
import socketserver
import struct
import tempfile
import threading
import unittest
import uuid

from dead_earth_director import admin


def packet(ident, kind, text):
    payload=struct.pack('<ii',ident,kind)+text.encode()+b'\0\0'
    return struct.pack('<i',len(payload))+payload


def receive(sock):
    def exact(n):
        result=b''
        while len(result)<n:
            part=sock.recv(n-len(result))
            if not part: raise EOFError()
            result+=part
        return result
    raw=exact(struct.unpack('<i',exact(4))[0])
    ident,kind=struct.unpack('<ii',raw[:8])
    return ident,kind,raw[8:-2].decode()


@contextlib.contextmanager
def rcon(mode='ok'):
    seen=[]
    class Handler(socketserver.BaseRequestHandler):
        def handle(self):
            try:
                ident,kind,text=receive(self.request)
                if mode=='auth':
                    self.request.sendall(packet(-1,2,''));return
                self.request.sendall(packet(ident,2,''))
                ident,kind,text=receive(self.request);seen.append(text)
                if mode=='lost': return
                first=packet(ident,0,'Ответ <script>')
                self.request.sendall(first[:3]);self.request.sendall(first[3:])
                self.request.sendall(packet(ident,0,' и ещё часть'))
                marker,kind,text=receive(self.request)
                self.request.sendall(packet(marker,0,'Unknown request 0'))
            except (OSError,EOFError): pass
    with socketserver.ThreadingTCPServer(('127.0.0.1',0),Handler) as server:
        worker=threading.Thread(target=server.serve_forever,daemon=True);worker.start()
        try: yield server.server_address[1],seen
        finally: server.shutdown();worker.join()


def properties(root,port=25575,enabled=True):
    (root/'server.properties').write_text('server-ip=127.0.0.1\nenable-rcon='+str(enabled).lower()+'\nrcon.port='+str(port)+'\nrcon.password=TEST_PRIVATE_PASSWORD\n',encoding='utf-8')


class ConsoleTests(unittest.TestCase):
    def console(self,root):
        self.assertTrue(hasattr(admin,'OperatorConsole'),'Operator console is missing')
        return admin.OperatorConsole(root)

    def test_multipart_rcon_and_durable_at_most_once_receipt(self):
        with tempfile.TemporaryDirectory() as tmp, rcon() as (port,seen):
            root=Path(tmp);properties(root,port)
            console=self.console(root);ident=str(uuid.uuid4())
            first=console.execute(ident,'/list')
            self.assertEqual(first['state'],'completed')
            self.assertEqual(first['output'],'Ответ <script> и ещё часть')
            self.assertEqual(self.console(root).execute(ident,'/list'),first)
            self.assertEqual(seen,['list'])
            with self.assertRaises(ValueError):console.execute(ident,'say other')

    def test_auth_failure_and_lost_response_are_not_retried(self):
        for mode,state in [('auth','rejected'),('lost','uncertain')]:
            with self.subTest(mode=mode),tempfile.TemporaryDirectory() as tmp,rcon(mode) as (port,seen):
                root=Path(tmp);properties(root,port);console=self.console(root);ident=str(uuid.uuid4())
                result=console.execute(ident,'list')
                self.assertEqual(result['state'],state)
                self.assertEqual(console.execute(ident,'list'),result)
                self.assertEqual(len(seen),0 if mode=='auth' else 1)
                self.assertNotIn('TEST_PRIVATE_PASSWORD',json.dumps(result))

    def test_tail_is_bounded_redacted_and_follows_rotation(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp);properties(root);logs=root/'logs';logs.mkdir()
            path=logs/'server-console.log'
            path.write_text('old\n'*100000+'TEST_PRIVATE_PASSWORD <img>\n',encoding='utf-8')
            console=self.console(root);view=console.snapshot()
            self.assertLessEqual(len(view['lines']),600)
            self.assertNotIn('TEST_PRIVATE_PASSWORD',json.dumps(view))
            self.assertIn('<img>',view['lines'][-1])
            path.write_text('new session\n',encoding='utf-8')
            self.assertEqual(console.snapshot()['lines'],['new session'])

    def test_unsafe_host_disabled_and_multiline_rejected_without_send(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp);properties(root,enabled=False);console=self.console(root)
            self.assertEqual(console.execute(str(uuid.uuid4()),'list')['state'],'unavailable')
            for text in ['say a\nstop','say a\rstop','say \0x','', 'я'*1000]:
                with self.subTest(text=text[:12]),self.assertRaises(ValueError):console.execute(str(uuid.uuid4()),text)
            properties(root);p=root/'server.properties';p.write_text(p.read_text().replace('127.0.0.1','0.0.0.0'))
            self.assertEqual(console.snapshot()['state'],'unsafe_binding')

    def test_http_commands_require_nonce_origin_and_valid_body(self):
        with tempfile.TemporaryDirectory() as tmp,rcon() as (port,seen):
            root=Path(tmp);properties(root,port)
            self.assertTrue(hasattr(admin,'OperatorConsole'),'Operator console is missing')
            app=admin.Application(root/'bridge',root/'journal',root/'db',root/'catalog',server_root=root)
            server=admin.make_server(app,0);thread=threading.Thread(target=server.serve_forever,daemon=True);thread.start()
            webport=server.server_address[1]
            def request(method,path,body=None,headers=None):
                conn=http.client.HTTPConnection('127.0.0.1',webport,timeout=4)
                conn.request(method,path,body=body,headers=headers or {})
                response=conn.getresponse();result=(response.status,json.loads(response.read()));conn.close();return result
            try:
                code,session=request('GET','/api/console')
                self.assertEqual(code,200);nonce=session['nonce']
                body=json.dumps(dict(request_id=str(uuid.uuid4()),command='list'))
                headers={'Content-Type':'application/json','X-Dead-Earth-Console':nonce,'Origin':f'http://127.0.0.1:{webport}'}
                self.assertEqual(request('POST','/api/console/command',body)[0],403)
                self.assertEqual(request('POST','/api/console/command',body,dict(headers,Origin='https://evil.test'))[0],403)
                self.assertEqual(request('POST','/api/console/command',body,dict(headers,**{'X-Dead-Earth-Console':'wrong'}))[0],403)
                self.assertEqual(request('POST','/api/console/command','{}',headers)[0],400)
                self.assertEqual(request('POST','/api/console/command',body,headers)[1]['state'],'completed')
                self.assertEqual(request('POST','/api/console/command',body,headers)[1]['state'],'completed')
                self.assertEqual(seen,['list'])
                self.assertNotIn(nonce,json.dumps(app.dashboard()))
            finally:server.shutdown();server.server_close();thread.join()

    def test_prepare_preserves_world_settings_and_is_idempotent(self):
        from dead_earth_director.admin_console import prepare
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp);properties(root,enabled=False)
            path=root/'server.properties'
            path.write_text(path.read_text()+'level-name=existing_world\nserver-port=25565\n')
            before=path.read_bytes()
            with __import__('socket').socket() as probe:
                probe.bind(('127.0.0.1',0));port=probe.getsockname()[1]
            self.assertEqual(prepare(root,port)['state'],'prepared')
            self.assertIn('level-name=existing_world',path.read_text())
            self.assertIn('enable-rcon=true',path.read_text())
            self.assertEqual((root/'director/runtime/server.properties.before-console').read_bytes(),before)
            after=path.read_bytes();self.assertEqual(prepare(root,port)['state'],'already_configured')
            self.assertEqual(path.read_bytes(),after)

    def test_busy_full_journal_and_bad_properties_fail_without_network(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp);properties(root);console=self.console(root)
            console.lock.acquire()
            try:self.assertEqual(console.execute(str(uuid.uuid4()),'list')['state'],'busy')
            finally:console.lock.release()
            with console.db() as db:
                db.executemany('INSERT INTO commands VALUES(?,?,?,?)',[(str(n),'list','{}',n) for n in range(5000)]);db.commit()
            self.assertEqual(console.execute(str(uuid.uuid4()),'list')['state'],'journal_full')
            path=root/'server.properties';path.write_text(path.read_text()+'server-ip=0.0.0.0\n')
            self.assertEqual(console.snapshot()['state'],'config_unavailable')


if __name__=='__main__': unittest.main()
