"""Human operator console. Deliberately absent from the model/MCP tool surface."""
import argparse
import contextlib
import json
from pathlib import Path
import re
import secrets
import socket
import sqlite3
import struct
import threading
import time
import uuid
from .network import server_binding_allowed


def read_properties(path):
    # DedicatedServer writes ordinary key=value properties. Reject ambiguous keys,
    # escapes/continuations instead of guessing a different bind/password than Java.
    result={}
    for raw in Path(path).read_text(encoding='utf-8-sig').splitlines():
        line=raw.strip()
        if not line or line.startswith(('#','!')): continue
        key,sep,value=line.partition('=')
        if not sep: raise ValueError('properties_format')
        key=key.strip();value=value.strip()
        if key in result or '\\' in key or value.endswith('\\'): raise ValueError('properties_format')
        result[key]=value
    return result


def settings(root):
    p=read_properties(root/'server.properties')
    password=p.get('rcon.password','')
    if not server_binding_allowed(p.get('server-ip')): return 'unsafe_binding',None,password
    if p.get('enable-rcon')!='true' or not password: return 'not_configured',None,password
    port=int(p.get('rcon.port','25575'))
    if not 1<=port<=65535 or '\\' in password: raise ValueError('properties_format')
    return 'configured',port,password


def prepare(root,port=25575):
    """Explicit CLI step: prepare properties; never stop/start a running server."""
    root=Path(root);path=root/'server.properties';p=read_properties(path)
    if p.get('server-ip')!='127.0.0.1': raise ValueError('server_must_bind_127.0.0.1')
    if not 1<=port<=65535 or port==int(p.get('server-port','25565')): raise ValueError('invalid_port')
    if p.get('enable-rcon')=='true' and p.get('rcon.password'):
        state,existing,_=settings(root)
        if state!='configured': raise ValueError('invalid_existing_configuration')
        return dict(state='already_configured',port=existing,restart_required=True)
    # Refuse an occupied port before changing the file.
    with socket.socket() as probe: probe.bind(('127.0.0.1',port))
    updates={'enable-rcon':'true','rcon.port':str(port),'rcon.password':secrets.token_urlsafe(32),'broadcast-rcon-to-ops':'false'}
    lines=path.read_text(encoding='utf-8-sig').splitlines()
    output=[]
    for line in lines:
        key=line.partition('=')[0].strip()
        output.append(key+'='+updates.pop(key) if key in updates else line)
    output.extend(k+'='+v for k,v in updates.items())
    backup=root/'director/runtime/server.properties.before-console'
    backup.parent.mkdir(parents=True,exist_ok=True)
    with backup.open('xb') as dest: dest.write(path.read_bytes())
    temp=path.with_suffix('.console.tmp')
    temp.write_text('\n'.join(output)+'\n',encoding='utf-8');temp.replace(path)
    return dict(state='prepared',port=port,restart_required=True)


class RconFailure(Exception):
    def __init__(self,state): self.state=state


def rcon_command(port,password,command):
    """Bounded Source RCON, including Minecraft's multi-packet responses.

    Send the non-command terminator only AFTER the first command response:
    Minecraft 1.21.1 rejects coalesced incoming RCON packets in a single read.
    """
    sent=False;deadline=time.monotonic()+6
    try:
        with socket.create_connection(('127.0.0.1',port),timeout=1) as sock:
            def send(ident,kind,text):
                body=struct.pack('<ii',ident,kind)+text.encode('utf-8')+b'\0\0'
                sock.sendall(struct.pack('<i',len(body))+body)
            def exact(size):
                data=b''
                while len(data)<size:
                    remaining=deadline-time.monotonic()
                    if remaining<=0: raise TimeoutError()
                    sock.settimeout(remaining)
                    chunk=sock.recv(size-len(data))
                    if not chunk: raise EOFError()
                    data+=chunk
                return data
            def receive():
                size=struct.unpack('<i',exact(4))[0]
                if not 10<=size<=65536: raise ValueError()
                body=exact(size)
                if body[-2:]!=b'\0\0': raise ValueError()
                return (*struct.unpack('<ii',body[:8]),body[8:-2])
            send(1,3,password)
            ident,kind,_=receive()
            if ident!=1 or kind!=2: raise RconFailure('rejected')
            # Mark uncertain BEFORE write: a partial send can still reach the server.
            sent=True;send(2,2,command)
            ident,kind,body=receive()
            if ident!=2 or kind!=0: raise ValueError()
            output=[body];total=len(body)
            send(3,0,'')
            for _ in range(128):
                ident,kind,body=receive()
                if ident==3 and kind==0: return b''.join(output).decode('utf-8',errors='replace')
                if ident!=2 or kind!=0: raise ValueError()
                total+=len(body)
                if total>262144: raise ValueError()
                output.append(body)
            raise ValueError()
    except RconFailure: raise
    except (OSError,EOFError,ValueError,struct.error):
        raise RconFailure('uncertain' if sent else 'unavailable') from None


def cleaned(text,password=''):
    text=re.sub(r'\x1b\[[0-?]*[ -/]*[@-~]','',text)
    text=''.join(c for c in text if c in '\n\t' or ord(c)>=32)
    return text.replace(password,'[скрыто]') if password else text


class OperatorConsole:
    def __init__(self,root):
        self.root=Path(root);self.lock=threading.Lock()
        self.probe_at=0;self.probe_port=None;self.probe_state='restart_or_server_required'
        self.database=self.root/'director/runtime/console.sqlite3'
        self.database.parent.mkdir(parents=True,exist_ok=True)
        with self.db() as db:
            db.execute('CREATE TABLE IF NOT EXISTS commands(request_id TEXT PRIMARY KEY, command TEXT NOT NULL, result TEXT NOT NULL, at REAL NOT NULL)')
            db.commit()

    def db(self): return contextlib.closing(sqlite3.connect(self.database,timeout=2))

    def snapshot(self):
        password='';port=None
        try: state,port,password=settings(self.root)
        except (OSError,ValueError): state='config_unavailable'
        if state=='configured':
            # A TCP probe causes a RCON connection log entry. Cache it so the
            # live tail does not fill the server journal with its own polling.
            if port!=self.probe_port or time.monotonic()-self.probe_at>30:
                try:
                    with socket.create_connection(('127.0.0.1',port),timeout=.2): pass
                    self.probe_state='ready'
                except OSError: self.probe_state='restart_or_server_required'
                self.probe_at=time.monotonic();self.probe_port=port
            state=self.probe_state
        source=None;lines=[]
        for name in ('server-console.log','latest.log'):
            path=self.root/'logs'/name
            try:
                with path.open('rb') as log:
                    log.seek(0,2);size=log.tell();log.seek(max(0,size-131072));raw=log.read(131072)
                if size>131072: raw=raw.partition(b'\n')[2]
                lines=cleaned(raw.decode('utf-8',errors='replace'),password).splitlines()[-600:];source=name;break
            except OSError: continue
        with self.db() as db:
            history=[json.loads(row[0]) for row in db.execute('SELECT result FROM commands ORDER BY at DESC LIMIT 50')]
        return dict(state=state,lines=lines,source=source,history=history,checked_at=time.time())

    def execute(self,request_id,command):
        if type(request_id) is not str or str(uuid.UUID(request_id))!=request_id: raise ValueError('invalid_request')
        if type(command) is not str or any(ord(c)<32 or ord(c)==127 for c in command): raise ValueError('invalid_command')
        command=command.strip().removeprefix('/')
        if not command or len(command.encode('utf-8'))>1000: raise ValueError('invalid_command')
        if not self.lock.acquire(blocking=False): return dict(state='busy')
        try:
            with self.db() as db:
                row=db.execute('SELECT command,result FROM commands WHERE request_id=?',(request_id,)).fetchone()
                if row:
                    if row[0]!=command: raise ValueError('conflicting_request')
                    return json.loads(row[1])
                # Do not evict receipts: an old browser retry must never re-run a command.
                if db.execute('SELECT COUNT(*) FROM commands').fetchone()[0]>=5000: return dict(state='journal_full')
                result=dict(request_id=request_id,command=command,state='uncertain',output='',at=time.time())
                db.execute('INSERT INTO commands VALUES(?,?,?,?)',(request_id,command,json.dumps(result),result['at']));db.commit()
            try:
                state,port,password=settings(self.root)
                if state!='configured': raise RconFailure('unavailable')
                output=rcon_command(port,password,command)
                result.update(state='completed',output=cleaned(output,password)[:16384],truncated=len(output)>16384)
            except RconFailure as error: result['state']=error.state
            except (OSError,ValueError): result['state']='unavailable'
            with self.db() as db:
                db.execute('UPDATE commands SET result=? WHERE request_id=?',(json.dumps(result),request_id));db.commit()
            return result
        finally: self.lock.release()


def main():
    parser=argparse.ArgumentParser(description='Prepare loopback operator RCON; no restart')
    parser.add_argument('--server-root',required=True,type=Path)
    parser.add_argument('--port',type=int,default=25575)
    args=parser.parse_args()
    try: print(json.dumps(prepare(args.server_root,args.port)))
    except (OSError,ValueError):
        print(json.dumps(dict(state='preparation_failed')));return 2
    return 0

if __name__=='__main__': raise SystemExit(main())
