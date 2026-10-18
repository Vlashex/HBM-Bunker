"""Local director dashboard with a separate human operator console."""
import argparse
import hashlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import secrets
from pathlib import Path
import sqlite3
import threading
import time
from urllib.parse import parse_qs, urlsplit
from urllib.request import Request, build_opener, ProxyHandler
from .bridge import BridgeClient, BridgeError, Descriptor, _uuid
from .admin_store import Telemetry, read_decisions, safe_tree
from .admin_console import OperatorConsole
from .admin_authoring import Authoring, AuthoringError, MAX_BODY
from .admin_evolution import Evolution, EvolutionError
from .bridge import _json

STATIC = Path(__file__).with_name("admin_web")
PRODUCT = "dead-earth-admin-v1"


class Observer:
    def __init__(self, bridge, store, client_factory=None):
        self.bridge=Path(bridge); self.store=store
        self.client_factory=client_factory or (lambda: BridgeClient(Descriptor.load(self.bridge)))
        self.lock=threading.Lock()
        self.current=dict(state="starting",checked_at=None,scope=None)
        self.checked_monotonic=None

    def collect(self, now=None):
        now=time.time() if now is None else now
        state,scope="offline",None
        try:
            client=self.client_factory()
            envelope=client.call("get_snapshot",{})
            snapshot=envelope.get("snapshot") if type(envelope) is dict else None
            age=envelope.get("cache_age_millis") if type(envelope) is dict else None
            if type(snapshot) is not dict or type(age) is not int or age<0: raise ValueError()
            if age>30000:
                state="stale"
            else:
                if not _uuid(snapshot.get("campaignId")) or not _uuid(snapshot.get("worldEpoch")): raise ValueError()
                if hasattr(client,"descriptor") and (snapshot["campaignId"]!=client.descriptor.campaign_id or snapshot["worldEpoch"]!=client.descriptor.world_epoch): raise ValueError()
                queue=client.call("get_cinematic_queue",{})
                if type(queue) is not dict or type(queue.get("operations")) is not list:
                    state="queue_unavailable"
                else:
                    self.store.record(snapshot,queue,now)
                    state,scope="online",snapshot["campaignId"]+":"+snapshot["worldEpoch"]
        except BridgeError:
            state="offline"
        except (OSError,ValueError,TypeError,KeyError,sqlite3.Error):
            state="data_unavailable"
        if state!="online":
            try: self.store.record(None,None,now)
            except (OSError,ValueError,sqlite3.Error): state="storage_unavailable"
        with self.lock:
            self.current=dict(state=state,checked_at=now,scope=scope)
            self.checked_monotonic=time.monotonic()

    def status(self):
        with self.lock: result=dict(self.current)
        if self.checked_monotonic is not None and time.monotonic()-self.checked_monotonic>40:
            result["state"]="stale"
        return result


class Application:
    def __init__(self,bridge,journal,database,catalog,model_config=None,server_root=None):
        self.store=Telemetry(database); self.observer=Observer(bridge,self.store)
        self.journal=Path(journal); self.catalog_path=Path(catalog)
        self.instance_id=hashlib.sha256(str(self.journal.parent.parent.parent.resolve()).lower().encode("utf-8")).hexdigest()[:16]
        self.model_config=model_config
        self.model=dict(state="unchecked")
        self.model_checked=0
        self.console=OperatorConsole(server_root) if server_root else None
        self.console_nonce=secrets.token_urlsafe(32)
        self.authoring=Authoring(server_root,self.console) if server_root else None
        self.authoring_nonce=secrets.token_urlsafe(32)
        self.evolution=Evolution(server_root,self.console) if server_root else None
        self.evolution_nonce=secrets.token_urlsafe(32)

    def check_model(self):
        # Tags only: never load/unload a model or request generation from the panel.
        if not self.model_config: return
        result=dict(state="unavailable")
        try:
            from .host import load_config
            from .model import NoRedirect
            settings,_=load_config(self.model_config)
            result.update(name=settings.model,device="CPU")
            opener=build_opener(ProxyHandler({}),NoRedirect())
            with opener.open(Request(settings.url+"/api/tags"),timeout=2) as response:
                raw=response.read(131073)
            if len(raw)>131072: raise ValueError()
            body=json.loads(raw)
            rows=body.get("models",[])
            match=next((m for m in rows if type(m) is dict and m.get("name")==settings.model),None)
            result["state"]="ready" if match and match.get("digest")==settings.digest else "revision_mismatch"
        except Exception:
            # Never forward config contents or endpoint exceptions to the browser.
            pass
        self.model=result;self.model_checked=time.time()

    def catalog(self):
        try:
            if not self.catalog_path.is_file() or self.catalog_path.stat().st_size>4194304: raise ValueError()
            data=json.loads(self.catalog_path.read_text(encoding="utf-8"))
            if type(data) is not dict or type(data.get("events")) is not list: raise ValueError()
            return dict(status="ok",source="configured_file",rules=safe_tree(data.get("rules",{})),
                        sounds=len(data.get("sounds",{})),traces=len(data.get("traces",{})),events=safe_tree(data["events"][:128]))
        except (OSError,ValueError,TypeError,RecursionError):
            return dict(status="unavailable",events=[])

    def dashboard(self,scope=None,hours=6):
        now=time.time()
        connection=self.observer.status()
        journal_index=read_decisions(self.journal,limit=0)
        telemetry_scopes=self.store.scopes()
        merged={s["id"]:s for s in journal_index.get("scopes",[])}
        merged.update({s["id"]:s for s in telemetry_scopes})
        scopes=sorted(merged.values(),key=lambda s:s["at"] or 0,reverse=True)
        if scope is None:
            scope=(connection["scope"] if connection["state"]=="online" else None)
            if scope is None and telemetry_scopes: scope=telemetry_scopes[0]["id"]
            if scope is None and scopes: scope=scopes[0]["id"]
            if scope is None: scope="unscoped"
        view=self.store.view(scope,since=now-hours*3600)
        decisions=read_decisions(self.journal,scope)
        return dict(product=PRODUCT,generated_at=now,connection=connection,model=self.model,
                    scopes=scopes,decisions=decisions,catalog=self.catalog(),**view)

    def observe(self,stop):
        while not stop.is_set():
            self.observer.collect()
            if time.time()-self.model_checked>30: self.check_model()
            stop.wait(10)


def make_server(app, port=8765):
    class Handler(BaseHTTPRequestHandler):
        server_version="DeadEarthAdmin/1"
        sys_version=""
        def log_message(self,*args): pass
        def setup(self):
            super().setup(); self.connection.settimeout(5)
        def send_body(self,code,body=b"",kind="application/json; charset=utf-8",download=False):
            self.send_response(code)
            self.send_header("Content-Type",kind)
            self.send_header("Content-Length",str(len(body)))
            self.send_header("Cache-Control","no-store")
            self.send_header("X-Content-Type-Options","nosniff")
            self.send_header("Referrer-Policy","no-referrer")
            self.send_header("Content-Security-Policy","default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self'; connect-src 'self'; object-src 'none'; base-uri 'none'; frame-ancestors 'none'; form-action 'none'")
            self.send_header("Cross-Origin-Resource-Policy","same-origin")
            self.send_header("X-Frame-Options","DENY")
            if download: self.send_header("Content-Disposition",'attachment; filename="dead-earth-observer.json"')
            self.end_headers()
            if self.command!="HEAD": self.wfile.write(body)
        def allowed(self):
            host="127.0.0.1:"+str(self.server.server_address[1])
            return (self.headers.get_all("Host")==[host]
                    and self.headers.get("Origin") in (None,"http://"+host)
                    and self.headers.get("Sec-Fetch-Site") in (None,"same-origin","none"))
        def handle_read(self):
            if not self.allowed(): return self.send_body(403,b'{"error":"local_only"}')
            if len(self.path)>2048: return self.send_body(400,b'{"error":"invalid_query"}')
            parsed=urlsplit(self.path)
            routes={"/":("index.html","text/html; charset=utf-8"),"/admin.css":("admin.css","text/css; charset=utf-8"),"/admin.js":("admin.js","text/javascript; charset=utf-8")}
            routes.update({"/authoring.js":("authoring.js","text/javascript; charset=utf-8"),"/authoring.css":("authoring.css","text/css; charset=utf-8")})
            routes.update({"/evolution.js":("evolution.js","text/javascript; charset=utf-8"),"/evolution.css":("evolution.css","text/css; charset=utf-8")})
            if parsed.path in routes:
                name,kind=routes[parsed.path]
                try: return self.send_body(200,(STATIC/name).read_bytes(),kind)
                except OSError: return self.send_body(503,b'{"error":"assets_unavailable"}')
            if parsed.path=="/api/health":
                return self.send_body(200,json.dumps(dict(product=PRODUCT,instance_id=app.instance_id)).encode())
            if parsed.path=="/api/evolution" and not parsed.query:
                payload=app.evolution.snapshot() if app.evolution is not None else dict(state='offline',server=None)
                return self.send_body(200,json.dumps(dict(payload,nonce=app.evolution_nonce),ensure_ascii=False,allow_nan=False).encode('utf-8'))
            if parsed.path=="/api/authoring" and not parsed.query:
                if app.authoring is None:return self.send_body(503,b'{"error":"authoring_unavailable"}')
                try:
                    payload=dict(app.authoring.snapshot(),nonce=app.authoring_nonce)
                    return self.send_body(200,json.dumps(payload,ensure_ascii=False,allow_nan=False).encode('utf-8'))
                except (OSError,ValueError,sqlite3.Error):return self.send_body(503,b'{"error":"authoring_unavailable"}')
            if parsed.path=="/api/console" and not parsed.query:
                if app.console is None: return self.send_body(503,b'{"error":"console_unavailable"}')
                try:
                    payload=dict(app.console.snapshot(),nonce=app.console_nonce)
                    return self.send_body(200,json.dumps(payload,ensure_ascii=False).encode('utf-8'))
                except (OSError,ValueError,sqlite3.Error): return self.send_body(503,b'{"error":"console_unavailable"}')
            if parsed.path not in {"/api/dashboard","/api/export"}: return self.send_body(404,b'{"error":"not_found"}')
            try:
                q=parse_qs(parsed.query,keep_blank_values=True,max_num_fields=3)
                if not set(q)<={"scope","hours"} or any(len(v)!=1 for v in q.values()): raise ValueError()
                hours=int(q.get("hours",["6"])[0])
                if hours not in (1,6,24,168): raise ValueError()
                scope=q.get("scope",[None])[0]
                if scope is not None and scope!="unscoped":
                    parts=scope.split(":")
                    if len(parts)!=2 or not all(_uuid(p) for p in parts): raise ValueError()
            except ValueError: return self.send_body(400,b'{"error":"invalid_query"}')
            try:
                payload=json.dumps(app.dashboard(scope,hours),ensure_ascii=False,allow_nan=False).encode("utf-8")
                return self.send_body(200,payload,download=parsed.path=="/api/export")
            except (OSError,ValueError,sqlite3.Error): return self.send_body(503,b'{"error":"data_unavailable"}')
        def do_GET(self): self.handle_read()
        def do_HEAD(self): self.handle_read()
        def do_POST(self):
            if not self.allowed(): return self.send_body(403,b'{"error":"local_only"}')
            if self.path=="/api/evolution/shift":
                return self.evolution_shift()
            if self.path.startswith('/api/authoring/'):
                return self.authoring_action()
            if self.path!="/api/console/command": return self.reject()
            host="http://127.0.0.1:"+str(self.server.server_address[1])
            if (self.headers.get_all('Origin')!=[host]
                or self.headers.get_all('X-Dead-Earth-Console')!=[app.console_nonce]):
                return self.send_body(403,b'{"error":"operator_session_required"}')
            try:
                if self.headers.get('Content-Type')!='application/json' or self.headers.get('Transfer-Encoding'): raise ValueError()
                lengths=self.headers.get_all('Content-Length') or []
                if len(lengths)!=1: raise ValueError()
                length=int(lengths[0])
                if not 1<=length<=8192: raise ValueError()
                payload=json.loads(self.rfile.read(length))
                if type(payload) is not dict or set(payload)!={'request_id','command'}: raise ValueError()
                if app.console is None: return self.send_body(503,b'{"error":"console_unavailable"}')
                result=app.console.execute(payload['request_id'],payload['command'])
                return self.send_body(200,json.dumps(result,ensure_ascii=False).encode('utf-8'))
            except (ValueError,TypeError,AttributeError,RecursionError): return self.send_body(400,b'{"error":"invalid_command_request"}')
            except (OSError,sqlite3.Error): return self.send_body(503,b'{"error":"console_unavailable_outcome_unknown"}')
        def authoring_action(self):
            action=self.path.removeprefix('/api/authoring/')
            if action not in {'save','validate','publish','preview','run','cancel'}:return self.reject()
            origin='http://127.0.0.1:'+str(self.server.server_address[1])
            if self.headers.get_all('Origin')!=[origin] or self.headers.get_all('X-Dead-Earth-Operator')!=[app.authoring_nonce]:
                return self.send_body(403,b'{"error":"operator_session_required"}')
            try:
                if self.headers.get_all('Content-Type')!=['application/json'] or self.headers.get_all('Transfer-Encoding') is not None:raise ValueError()
                lengths=self.headers.get_all('Content-Length') or []
                if len(lengths)!=1:raise ValueError()
                length=int(lengths[0])
                if not 1<=length<=MAX_BODY:raise ValueError()
                payload=_json(self.rfile.read(length).decode('utf-8'))
                if app.authoring is None:return self.send_body(503,b'{"error":"authoring_unavailable"}')
                result=app.authoring.action(action,payload)
                return self.send_body(200,json.dumps(result,ensure_ascii=False,allow_nan=False).encode('utf-8'))
            except AuthoringError as ex:
                return self.send_body(400,json.dumps(dict(error=ex.code,path=ex.path)).encode('utf-8'))
            except (ValueError,TypeError,AttributeError,RecursionError,BridgeError):return self.send_body(400,b'{"error":"invalid_authoring_request"}')
            except (OSError,sqlite3.Error):return self.send_body(503,b'{"error":"authoring_unavailable_outcome_unknown"}')
        def evolution_shift(self):
            origin='http://127.0.0.1:'+str(self.server.server_address[1])
            if self.headers.get_all('Origin')!=[origin] or self.headers.get_all('X-Dead-Earth-Operator')!=[app.evolution_nonce]:
                return self.send_body(403,b'{"error":"operator_session_required"}')
            try:
                if self.headers.get_all('Content-Type')!=['application/json'] or self.headers.get_all('Transfer-Encoding') is not None:raise ValueError()
                lengths=self.headers.get_all('Content-Length') or []
                if len(lengths)!=1:raise ValueError()
                length=int(lengths[0])
                if not 1<=length<=8192:raise ValueError()
                payload=_json(self.rfile.read(length).decode('utf-8'))
                if app.evolution is None:return self.send_body(503,b'{"error":"evolution_unavailable"}')
                result=app.evolution.shift(payload)
                return self.send_body(200,json.dumps(result,ensure_ascii=False,allow_nan=False).encode('utf-8'))
            except EvolutionError as error:return self.send_body(400,json.dumps(dict(error=error.code)).encode('utf-8'))
            except (ValueError,TypeError,AttributeError,RecursionError,BridgeError):return self.send_body(400,b'{"error":"invalid_evolution_request"}')
            except (OSError,sqlite3.Error):return self.send_body(503,b'{"error":"evolution_unavailable_outcome_unknown"}')
        def reject(self): self.send_body(405,b'{"error":"read_only"}')
        do_PUT=do_DELETE=do_PATCH=do_OPTIONS=reject
    return ThreadingHTTPServer(("127.0.0.1",port),Handler)


def main():
    parser=argparse.ArgumentParser(description="DEAD EARTH local AI admin and operator console")
    parser.add_argument("--server-root",type=Path,required=True)
    parser.add_argument("--catalog",type=Path,default=Path(__file__).resolve().parents[2]/"pack/config/dead-earth/director/catalog.json")
    parser.add_argument("--port",type=int,default=8765)
    parser.add_argument("--stop-file",type=Path)
    args=parser.parse_args()
    if not 1<=args.port<=65535: parser.error("invalid port")
    root=args.server_root.resolve()
    stop_file=args.stop_file or root/"director/runtime/admin.stop"
    # Only the level-name property is needed; never expose other server settings.
    world="world"
    try:
        for line in (root/"server.properties").read_text(encoding="utf-8").splitlines():
            if line.startswith("level-name="): world=line.partition("=")[2].strip()
        world_path=(root/world).resolve()
        if not world or not world_path.is_relative_to(root) or world_path==root: raise ValueError()
        app=Application(world_path/"dead_earth_bridge.json",root/"director/runtime/decisions.sqlite3",
                        root/"director/runtime/admin.sqlite3",args.catalog,root/"director/director.example.toml",server_root=root)
        server=make_server(app,args.port)
    except (OSError,ValueError,sqlite3.Error):
        print("Dead Earth admin: startup_unavailable",flush=True);return 2
    stop=threading.Event()
    worker=threading.Thread(target=app.observe,args=(stop,),daemon=True);worker.start()
    server.timeout=.5
    print("Dead Earth admin: http://127.0.0.1:"+str(args.port),flush=True)
    try:
        while not stop_file.exists(): server.handle_request()
    except KeyboardInterrupt: pass
    finally:
        stop.set();server.server_close();worker.join(timeout=12)
    return 0

if __name__=="__main__": raise SystemExit(main())
