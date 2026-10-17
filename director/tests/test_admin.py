import contextlib
import http.client
import json
from pathlib import Path
import sqlite3
import tempfile
import threading
import unittest
import time
from dead_earth_director import admin, admin_store

CAMPAIGN="00000000-0000-4000-8000-000000000001"
EPOCH="00000000-0000-4000-8000-000000000002"

def snapshot(epoch=EPOCH,tick=100):
    return dict(campaignId=CAMPAIGN,worldEpoch=epoch,activeTicks=tick,onlinePlayers=2,
                difficulty=dict(pressure=1.2,parasiteHealth=1.5),climate=dict(phase="CALM"),
                zones=[dict(id="zone",value=250,complete=True)],observations=dict(history=[]))

class StoreTests(unittest.TestCase):
    def test_offline_gaps_persistence_and_epoch_isolation(self):
        with tempfile.TemporaryDirectory() as tmp:
            path=Path(tmp)/"observer.db"; store=admin_store.Telemetry(path)
            store.record(snapshot(),dict(operations=[]),now=1000)
            store.record(None,None,now=1010)
            epoch="00000000-0000-4000-8000-000000000003"
            store.record(snapshot(epoch),dict(operations=[]),now=1020)
            old=store.view(CAMPAIGN+":"+EPOCH,since=0)
            self.assertEqual([s["online"] for s in old["samples"]],[True,False])
            self.assertEqual(store.view(since=0)["snapshot"]["worldEpoch"],epoch)
            reopened=admin_store.Telemetry(path)
            self.assertEqual(reopened.view(since=0)["snapshot"]["worldEpoch"],epoch)
            self.assertEqual(len(reopened.scopes()),2)

    def test_scenario_and_observation_history_survives_short_server_ring(self):
        with tempfile.TemporaryDirectory() as tmp:
            store=admin_store.Telemetry(Path(tmp)/"db")
            snap=snapshot();snap["observations"]["history"]=[dict(tick=50,kind="DEATH",subject="test")]
            queue=dict(operations=[],history=[dict(operationId="operation",eventId="flank_rustle",tick=70,outcome="repelled")])
            store.record(snap,queue,now=1000)
            store.record(snap,queue,now=1010)
            store.record(snapshot(),dict(operations=[],history=[]),now=1020)
            archive=store.view(since=0)["archive"]
            self.assertEqual(len(archive["scenarios"]),1)
            self.assertEqual(archive["scenarios"][0]["outcome"],"repelled")
            self.assertEqual(len(archive["observations"]),1)
            self.assertEqual(archive["observations"][0]["observed_at"],1000)

    def test_decimation_preserves_continuity_and_actual_gaps(self):
        with tempfile.TemporaryDirectory() as tmp:
            store=admin_store.Telemetry(Path(tmp)/"db")
            for i in range(1300): store.record(snapshot(tick=i),dict(operations=[]),now=1000+i*10)
            samples=store.view(since=0)["samples"]
            self.assertLess(len(samples),1300)
            self.assertTrue(all("gap_before" in s for s in samples))
            self.assertFalse(any(s["gap_before"] for s in samples))
            store.record(snapshot(),dict(operations=[]),now=20000)
            self.assertTrue(store.view(since=0)["samples"][-1]["gap_before"])

    def test_retention_and_bad_scope(self):
        with tempfile.TemporaryDirectory() as tmp:
            store=admin_store.Telemetry(Path(tmp)/"db",max_samples=3)
            for n in range(5): store.record(snapshot(tick=n),{},now=1000+n)
            self.assertEqual(len(store.view(since=0)["samples"]),3)
            self.assertIsNone(store.view("missing",since=0)["snapshot"])

    def test_journal_is_read_only_bounded_and_projects_no_secrets(self):
        with tempfile.TemporaryDirectory() as tmp:
            path=Path(tmp)/"journal.db"
            self.assertEqual(admin_store.read_decisions(path)["status"],"missing")
            self.assertFalse(path.exists())
            with contextlib.closing(sqlite3.connect(path)) as db:
                db.execute("CREATE TABLE decisions(goal_id TEXT,request_id TEXT,choice TEXT,report TEXT)")
                db.execute("PRAGMA user_version=1")
                choice=dict(kind="cinematic_v1",event_id="spore_lure",source="model",reason="model_validated",token="secret",
                    audit=dict(campaign_id=CAMPAIGN,world_epoch=EPOCH,recorded_at=1000,selected_index=0,
                    options=[dict(eventId="spore_lure",weight=1.5)],token="secret"))
                db.execute("INSERT INTO decisions VALUES(?,?,?,?)",("goal","request",json.dumps(choice),json.dumps(dict(state="queued",token="secret"))))
                db.commit()
            before=path.read_bytes()
            result=admin_store.read_decisions(path)
            self.assertEqual(result["status"],"ok")
            self.assertNotIn("secret",json.dumps(result))
            self.assertEqual(result["items"][0]["choice"]["audit"]["selected_index"],0)
            self.assertEqual(before,path.read_bytes())
            self.assertEqual(admin_store.read_decisions(path,CAMPAIGN+":other")["items"],[])

    def test_corrupt_journal_does_not_leak_exception_or_content(self):
        with tempfile.TemporaryDirectory() as tmp:
            path=Path(tmp)/"db";path.write_text("secret")
            self.assertEqual(admin_store.read_decisions(path),dict(status="unavailable",items=[],unscoped=0))

class CollectorTests(unittest.TestCase):
    def test_observer_only_reads_snapshot_and_queue_and_rejects_stale(self):
        calls=[]
        class Client:
            def call(self,op,args):
                calls.append(op)
                return dict(snapshot=snapshot(),cache_age_millis=50) if op=="get_snapshot" else dict(operations=[])
        with tempfile.TemporaryDirectory() as tmp:
            observer=admin.Observer(Path(tmp)/"bridge",admin_store.Telemetry(Path(tmp)/"db"),client_factory=lambda:Client())
            observer.collect(now=1000)
            self.assertEqual(calls,["get_snapshot","get_cinematic_queue"])
            self.assertEqual(observer.status()["state"],"online")
            class Stale:
                def call(self,op,args): return dict(snapshot=snapshot(),cache_age_millis=60000)
            observer.client_factory=lambda:Stale();observer.collect(now=1010)
            self.assertEqual(observer.status()["state"],"stale")
            self.assertFalse(observer.store.view(since=0)["samples"][-1]["online"])

    def test_bad_snapshot_does_not_kill_collector_and_queue_failure_is_a_gap(self):
        calls=[]
        class Client:
            def call(self,op,args):
                calls.append(op)
                if op=="get_snapshot":return dict(snapshot=snapshot(),cache_age_millis=5)
                return dict(status="unsupported_save")
        with tempfile.TemporaryDirectory() as tmp:
            store=admin_store.Telemetry(Path(tmp)/"db")
            store.record(snapshot(),dict(operations=[]),now=time.time()-10)
            observer=admin.Observer(Path(tmp)/"bridge",store,client_factory=lambda:Client())
            observer.collect()
            self.assertEqual(observer.status()["state"],"queue_unavailable")
            self.assertFalse(store.view(since=0)["samples"][-1]["online"])
            class Broken:
                def call(self,op,args):return dict(snapshot=dict(snapshot(),zones=["bad"]),cache_age_millis=5) if op=="get_snapshot" else dict(operations=[])
            observer.client_factory=lambda:Broken()
            observer.collect()
            self.assertEqual(observer.status()["state"],"data_unavailable")
            observer.client_factory=lambda:Client()
            observer.collect()
            self.assertEqual(observer.status()["state"],"queue_unavailable")

class ScopeTests(unittest.TestCase):
    def test_journal_only_epochs_are_selectable_and_never_mixed(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp); journal=root/"journal.db"
            other="00000000-0000-4000-8000-000000000003"
            with contextlib.closing(sqlite3.connect(journal)) as db:
                db.execute("CREATE TABLE decisions(goal_id TEXT,request_id TEXT,choice TEXT,report TEXT)")
                db.execute("PRAGMA user_version=1")
                for i,epoch in enumerate((EPOCH,other)):
                    choice=dict(kind="cinematic_v1",audit=dict(campaign_id=CAMPAIGN,world_epoch=epoch,recorded_at=1000+i))
                    db.execute("INSERT INTO decisions VALUES(?,?,?,NULL)",(str(i),str(i),json.dumps(choice)))
                db.commit()
            app=admin.Application(root/"bridge",journal,root/"db",root/"catalog")
            result=app.dashboard()
            self.assertEqual(len(result["scopes"]),2)
            self.assertEqual(result["scope"],CAMPAIGN+":"+other)
            self.assertEqual(len(result["decisions"]["items"]),1)
            app.store.record(snapshot(),dict(operations=[]))
            result=app.dashboard()
            self.assertEqual(len(result["scopes"]),2)
            self.assertEqual(len(result["decisions"]["items"]),1)
            self.assertEqual(len(app.dashboard(CAMPAIGN+":"+other)["decisions"]["items"]),1)

class HttpTests(unittest.TestCase):
    def test_loopback_guards_fixed_routes_and_read_only_export(self):
        with tempfile.TemporaryDirectory() as tmp:
            app=admin.Application(Path(tmp)/"bridge",Path(tmp)/"journal",Path(tmp)/"db",Path(tmp)/"catalog")
            server=admin.make_server(app,0)
            thread=threading.Thread(target=server.serve_forever,daemon=True);thread.start()
            port=server.server_address[1]
            def request(path="/api/dashboard",method="GET",headers=None):
                c=http.client.HTTPConnection("127.0.0.1",port,timeout=3)
                c.request(method,path,headers=headers or {})
                r=c.getresponse(); body=r.read(); code=r.status; h=dict(r.getheaders());c.close()
                return code,body,h
            try:
                code,body,headers=request()
                self.assertEqual(code,200)
                self.assertEqual(json.loads(body)["connection"]["state"],"starting")
                self.assertIn("default-src 'self'",headers["Content-Security-Policy"])
                self.assertNotIn("Access-Control-Allow-Origin",headers)
                self.assertEqual(request(headers={"Host":"evil.test"})[0],403)
                self.assertEqual(request(headers={"Origin":"https://evil.test"})[0],403)
                self.assertEqual(request(headers={"Sec-Fetch-Site":"cross-site"})[0],403)
                self.assertEqual(request(method="POST")[0],405)
                self.assertEqual(request("/../../journal")[0],404)
                self.assertEqual(request("/api/dashboard?hours=nan")[0],400)
                self.assertEqual(request("/api/export")[0],200)
                self.assertEqual(request("/")[0],200)
                self.assertEqual(request("/admin.js")[0],200)
            finally:
                server.shutdown();server.server_close();thread.join()

class ModelStatusTests(unittest.TestCase):
    def test_passive_model_check_only_lists_tags(self):
        from test_director import model_endpoint, DIGEST
        requests_seen=[]
        def response(path,body):
            requests_seen.append(path)
            return 200,dict(models=[dict(name="qwen3.5:4b",digest=DIGEST)])
        with model_endpoint(response) as (url,requests), tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp)
            config=root/"model.toml"
            config.write_text('poll_seconds = 30\n[model]\nurl = "'+url+'"\nmodel = "qwen3.5:4b"\ndigest = "'+DIGEST+'"\n',encoding="utf-8")
            app=admin.Application(root/"bridge",root/"journal",root/"db",root/"catalog",config)
            app.check_model()
            self.assertEqual(app.model["state"],"ready")
            self.assertEqual(requests_seen,["/api/tags"])
