import contextlib
import http.client
import json
from pathlib import Path
import sqlite3
import tempfile
import unittest
import threading
from unittest.mock import patch

from dead_earth_director import admin_store
from test_admin import snapshot, CAMPAIGN, EPOCH
from dead_earth_director import admin
from dead_earth_director.bridge import BridgeError, Descriptor, _arguments
from test_bridge import endpoint, success


def operation(operation_id="op", hp=4):
    return dict(operationId=operation_id, eventId="spore_lure", zoneId="zone", createdTick=100,
                expiresTick=500, endedTick=None, state="WAITING", coverage="complete",
                participants=2, participantsTruncated=False,
                damage=dict(hp=hp, absorbedHp=1, deaths=1, ownedAttributedHp=3,
                            spatialAttributedHp=1, points=9),
                observations=dict(signalEmissions=1, tracePlacements=1, traceInteractions=1,
                                  nearEntries=1, approaches=1, trapEntries=0, retreats=0),
                proxy=dict(distraction="observed", deception="insufficient_data", score=1,
                           reason="trace_interaction"),
                timeline=[dict(tick=120, kind="damage", role="lure", detail="owned_wave",
                               count=1, hp=4, attribution="owned")], droppedTimeline=0)


def queue(*operations, status="ready"):
    return dict(operations=[], history=[], analytics=dict(schema=1, status=status, sampleTicks=20,
                maxOperations=128, operations=list(operations), totals=dict(hp=999999)))


class AnalyticsStoreTests(unittest.TestCase):
    def test_stale_wire_tail_never_rolls_back_known_full_sequence_or_totals(self):
        with tempfile.TemporaryDirectory() as tmp:
            store=admin_store.Telemetry(Path(tmp)/"db")
            full=operation(hp=9);full.update(wireTruncated=False,omittedTimeline=0)
            full["timeline"]=[dict(seq=i,tick=100+i,kind="damage",hp=1) for i in range(9)]
            store.record(snapshot(),queue(full),now=1000)
            stale=operation(hp=3);stale.update(wireTruncated=True,omittedTimeline=2)
            stale["timeline"]=[dict(seq=2,tick=102,kind="damage",hp=1)]
            store.record(snapshot(),queue(stale),now=1010)
            row=store.view(since=0)["analytics"]["operations"][0]
            self.assertEqual(row["damage"]["hp"],9)
            self.assertEqual(row["timeline"][-1]["seq"],8)
            self.assertFalse(row["wireTruncated"])

    def test_omitted_server_operations_are_explicit_and_bad_unicode_is_isolated(self):
        with tempfile.TemporaryDirectory() as tmp:
            store=admin_store.Telemetry(Path(tmp)/"db")
            op=operation();op["proxy"]["reason"]="bad\ud800text"
            payload=queue(op);payload["analytics"]["omittedOperations"]=5
            store.record(snapshot(),payload,now=1000)
            view=store.view(since=0)
            self.assertIsNotNone(view["snapshot"])
            self.assertEqual(view["analytics"]["omittedOperations"],5)
            self.assertTrue(view["analytics"]["operationsTruncated"])
            json.dumps(view,ensure_ascii=False).encode("utf-8")

    def test_dashboard_uses_bounded_summaries_and_full_dossier_stays_archived(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp);app=admin.Application(root/"bridge",root/"journal",root/"db",root/"catalog")
            operations=[]
            for i in range(150):
                op=operation("op-"+str(i));op.update(wireTruncated=False,omittedTimeline=0)
                op["timeline"]=[dict(seq=n,tick=n,kind="damage",detail="detail"*30,hp=1) for n in range(80)]
                operations.append(op)
            app.store.record(snapshot(),queue(*operations))
            result=app.dashboard(CAMPAIGN+":"+EPOCH)
            self.assertLessEqual(len(json.dumps(result,ensure_ascii=False).encode("utf-8")),524288)
            self.assertEqual(result["analytics"]["totals"]["operations"],150)
            self.assertEqual(len(result["analytics"]["operations"]),128)
            self.assertTrue(result["analytics"]["operationsTruncated"])
            self.assertTrue(all(not op["timeline"] for op in result["analytics"]["operations"]))
            self.assertEqual(len(app.store.analytics_operation(CAMPAIGN+":"+EPOCH,"op-0")["timeline"]),80)

    def test_summary_updates_do_not_extend_first_seen_retention(self):
        with tempfile.TemporaryDirectory() as tmp:
            store=admin_store.Telemetry(Path(tmp)/"db")
            store.record(snapshot(),queue(operation()),now=1000)
            store.record(snapshot(),queue(operation(hp=8)),now=2000)
            self.assertEqual(store.view(since=0)["analytics"]["operations"][0]["observed_at"],1000)
            store.record(snapshot(),queue(operation(hp=8)),now=1001+admin_store.RETENTION)
            self.assertEqual(store.view(since=0)["archive"]["analytics"],[])
            store=admin_store.Telemetry(Path(tmp)/"db")
            store.record(snapshot(),queue(operation(hp=8)),now=1002+admin_store.RETENTION)
            self.assertEqual(store.view(since=0)["archive"]["analytics"],[],"Unchanged server rows must not resurrect expired dossiers after restart")

    def test_wire_summaries_preserve_full_timeline_and_equal_tick_sequences(self):
        with tempfile.TemporaryDirectory() as tmp:
            store=admin_store.Telemetry(Path(tmp)/"db")
            full=operation();full.update(wireTruncated=False,omittedTimeline=0)
            full["timeline"]=[dict(seq=i,tick=120,kind="damage",hp=1) for i in range(4)]
            store.record(snapshot(),queue(full),now=1000)
            summary=operation(hp=5);summary.update(wireTruncated=True,omittedTimeline=4)
            summary["timeline"]=[dict(seq=4,tick=120,kind="damage",hp=1)]
            store.record(snapshot(),queue(summary),now=1010)
            row=store.view(since=0)["analytics"]["operations"][0]
            self.assertEqual([t["seq"] for t in row["timeline"]],[0,1,2,3,4])
            self.assertFalse(row["wireTruncated"])
            self.assertEqual(row["damage"]["hp"],5)

    def test_full_record_can_be_read_offline_but_other_epoch_never_queries_bridge(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp);app=admin.Application(root/"bridge",root/"journal",root/"db",root/"catalog")
            scope=CAMPAIGN+":"+EPOCH
            op=operation(CAMPAIGN);op.update(wireTruncated=False,omittedTimeline=0)
            app.store.record(snapshot(),queue(op),now=1000)
            with patch.object(Descriptor,"load",side_effect=BridgeError("descriptor_unavailable")):
                result=app.event_analytics(CAMPAIGN,scope)
            self.assertEqual(result["status"],"ready")
            self.assertEqual(result["operation"]["damage"]["hp"],4)
            app.store.record(snapshot(),queue(operation(EPOCH)),now=1010)
            with patch.object(Descriptor,"load",return_value=Descriptor("127.0.0.1",12345,"private",CAMPAIGN,CAMPAIGN)):
                with patch("dead_earth_director.admin.BridgeClient.call",side_effect=AssertionError("wrong scope queried")):
                    result=app.event_analytics(EPOCH,scope)
            self.assertEqual(result["status"],"scope_mismatch")

    def test_event_read_tool_only_accepts_one_canonical_operation_uuid(self):
        self.assertEqual(_arguments("get_event_analytics",dict(operation_id=CAMPAIGN)),dict(operation_id=CAMPAIGN))
        for bad in ({},{"operation_id":"bad"},{"operation_id":CAMPAIGN,"x":1},{"operation_id":True},{"operation_id":"AAAAAAAA-0000-4000-8000-000000000001"}):
            with self.subTest(bad=bad),self.assertRaises(BridgeError):
                _arguments("get_event_analytics",bad)

    def test_operation_upsert_survives_restart_without_double_counting(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp)/"db"
            store = admin_store.Telemetry(path)
            store.record(snapshot(), queue(operation()), now=1000)
            newer = operation(hp=7)
            newer.update(state="COMPLETED", endedTick=180)
            store.record(snapshot(tick=180), queue(newer), now=1010)
            store = admin_store.Telemetry(path)
            store.record(snapshot(tick=200), queue(), now=1020)
            view = store.view(since=0)
            self.assertEqual(len(view["archive"]["analytics"]), 1)
            self.assertEqual(view["analytics"]["totals"], dict(operations=1, hp=7, absorbedHp=1, deaths=1, points=9))
            self.assertEqual(view["analytics"]["operations"][0]["endedTick"], 180)

    def test_bad_analytics_never_rejects_basic_snapshot_and_missing_is_unknown(self):
        with tempfile.TemporaryDirectory() as tmp:
            store = admin_store.Telemetry(Path(tmp)/"db")
            for at, bad in enumerate((None, [], {"schema":99}, {"schema":1,"status":"ready","operations":"bad"}), 1000):
                store.record(snapshot(), dict(operations=[], analytics=bad), now=at)
                view = store.view(since=0)
                self.assertIsNotNone(view["snapshot"])
                self.assertEqual(view["analytics"]["status"], "unavailable")
                self.assertIsNone(view["analytics"]["totals"]["hp"])
            op = operation()
            del op["damage"]["hp"]
            store.record(snapshot(), queue(op), now=1010)
            self.assertIsNone(store.view(since=0)["analytics"]["operations"][0]["damage"]["hp"])
            self.assertIsNone(store.view(since=0)["analytics"]["totals"]["hp"])

    def test_whitelist_rejects_nonfinite_out_of_range_and_private_fields(self):
        with tempfile.TemporaryDirectory() as tmp:
            store = admin_store.Telemetry(Path(tmp)/"db")
            op = operation(hp=float("nan"))
            op.update(playerUuid="private-player", x=123, participants=True,
                      timeline=[dict(tick=120, kind="damage", hp=float("inf"), count=-1,
                                     player="private-player", x=123)] * 140)
            op["damage"].update(deaths=-1, points=10**30)
            op["proxy"]["deception"]="invented"
            store.record(snapshot(), queue(op), now=1000)
            view=store.view(since=0)
            clean=view["analytics"]["operations"][0]
            self.assertIsNone(clean["participants"])
            self.assertIsNone(clean["damage"]["hp"])
            self.assertIsNone(clean["damage"]["deaths"])
            self.assertIsNone(clean["damage"]["points"])
            self.assertEqual(clean["proxy"]["deception"], "insufficient_data")
            self.assertEqual(len(clean["timeline"]),128)
            self.assertEqual(clean["droppedTimeline"],12)
            self.assertNotIn("private-player",json.dumps(view))
            self.assertNotIn('"x":',json.dumps(view["queue"]["analytics"]))
            json.dumps(view, allow_nan=False)

    def test_archive_bound_retention_and_epoch_separation(self):
        with tempfile.TemporaryDirectory() as tmp:
            store=admin_store.Telemetry(Path(tmp)/"db")
            store.record(snapshot(), queue(*(operation("op-"+str(i)) for i in range(512))), now=1000)
            store.record(snapshot(), queue(operation("op-new")), now=1010)
            self.assertEqual(len(store.view(since=0)["archive"]["analytics"]),512)
            epoch="00000000-0000-4000-8000-000000000003"
            store.record(snapshot(epoch), queue(operation("other")), now=1020)
            self.assertEqual(store.view(since=0)["analytics"]["totals"]["operations"],1)
            store.record(snapshot(epoch), queue(), now=1000+admin_store.RETENTION+100)
            self.assertEqual(store.view(CAMPAIGN+":"+EPOCH,since=0)["archive"]["analytics"],[])

    def test_nonready_status_keeps_archive_explicit_and_zero_is_only_for_ready_empty(self):
        with tempfile.TemporaryDirectory() as tmp:
            store=admin_store.Telemetry(Path(tmp)/"db")
            store.record(snapshot(), queue(), now=1000)
            self.assertEqual(store.view(since=0)["analytics"]["totals"]["hp"],0)
            for status in ("disabled","unsupported_save","unavailable"):
                store.record(snapshot(), queue(status=status), now=1001)
                view=store.view(since=0)
                self.assertEqual(view["analytics"]["status"],status)
                self.assertIsNone(view["analytics"]["totals"]["hp"])

    def test_council_metrics_projection_is_bounded_and_omits_model_outputs(self):
        with tempfile.TemporaryDirectory() as tmp:
            path=Path(tmp)/"journal"
            with contextlib.closing(sqlite3.connect(path)) as db:
                db.execute("CREATE TABLE decisions(goal_id TEXT,request_id TEXT,choice TEXT,report TEXT)")
                db.execute("PRAGMA user_version=1")
                stage=dict(stage="critic",model="pinned-model",digest="sha256:"+"a"*64,
                           status="validated",option=1,reason_codes=["safer"],elapsed_ms=45,
                           prompt_tokens=20,output_tokens=4,error_code=None,raw_output="private-reasoning")
                choice=dict(metrics=dict(ai_mode="dual",elapsed_ms=120,remaining_ms=450,proposal_option=0,final_option=1,outcome="revised",revision_count=99,
                           fallback_reason="critic_timeout",stages=[stage]*8,raw_output="private-reasoning"))
                db.execute("INSERT INTO decisions VALUES(?,?,?,NULL)",("goal","request",json.dumps(choice)))
                db.commit()
            metrics=admin_store.read_decisions(path)["items"][0]["choice"]["metrics"]
            self.assertEqual(metrics["ai_mode"],"dual")
            self.assertEqual(metrics["fallback_reason"],"critic_timeout")
            self.assertEqual(metrics["outcome"],"revised")
            self.assertEqual(metrics["remaining_ms"],450)
            self.assertEqual(metrics["proposal_option"],0)
            self.assertEqual(metrics["final_option"],1)
            self.assertIsNone(metrics["revision_count"])
            self.assertEqual(len(metrics["stages"]),3)
            self.assertEqual(metrics["stages"][0]["option"],1)
            self.assertNotIn("private-reasoning",json.dumps(metrics))


class AnalyticsHttpTests(unittest.TestCase):
    def test_dual_status_outage_preserves_model_identities_without_generation(self):
        from test_director import model_endpoint, DIGEST
        seen=[]
        def response(path,body):seen.append(path);return 500,{}
        with model_endpoint(response) as (url,requests),tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp);config=root/"config"
            config.write_text('ai_mode="dual"\npoll_seconds=30\n[model]\nurl="'+url+'"\nmodel="qwen3.5:4b"\ndigest="'+DIGEST+'"\n[critic]\nurl="'+url+'"\nmodel="second-model"\ndigest="'+'c'*64+'"\n',encoding="utf-8")
            app=admin.Application(root/"bridge",root/"journal",root/"db",root/"catalog",config)
            app.check_model()
            self.assertEqual(app.model["state"],"unavailable")
            self.assertEqual(app.model["critic"],dict(name="second-model",state="unavailable"))
            self.assertEqual(seen,["/api/tags"])

    def test_read_route_validates_scope_uuid_archives_full_and_survives_outage(self):
        full=operation(CAMPAIGN);full.update(wireTruncated=False,omittedTimeline=0)
        full["timeline"][0].update(seq=1,detail="<img src=x onerror=attack()>")
        with endpoint(lambda r:success(dict(schema=1,status="ready",operation=full))) as (bridge,requests), tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp);app=admin.Application(bridge,root/"journal",root/"db",root/"catalog")
            summary=operation(CAMPAIGN);summary.update(wireTruncated=True,omittedTimeline=1,timeline=[])
            app.store.record(snapshot(),queue(summary))
            server=admin.make_server(app,0);thread=threading.Thread(target=server.serve_forever,daemon=True);thread.start()
            def get(query):
                c=http.client.HTTPConnection("127.0.0.1",server.server_address[1],timeout=3)
                c.request("GET","/api/event-analytics?"+query)
                response=c.getresponse();body=response.read();status=response.status;c.close()
                return status,json.loads(body)
            scope=CAMPAIGN+":"+EPOCH
            try:
                for query in ("operation_id=bad&scope="+scope,"operation_id="+CAMPAIGN,"operation_id="+CAMPAIGN+"&scope="+scope+"&extra=1","operation_id="+CAMPAIGN+"&scope=bad"):
                    self.assertEqual(get(query)[0],400)
                self.assertEqual(requests,[])
                result=get("operation_id="+CAMPAIGN+"&scope="+scope)[1]
                self.assertEqual(result["status"],"ready")
                self.assertEqual(result["operation"]["timeline"][0]["detail"],"<img src=x onerror=attack()>")
                self.assertEqual([r["operation"] for r in requests],["get_event_analytics"])
                app.observer.bridge=root/"missing"
                self.assertEqual(get("operation_id="+CAMPAIGN+"&scope="+scope)[1]["status"],"ready")
                self.assertEqual(get("operation_id="+EPOCH+"&scope="+scope)[1]["status"],"unavailable")
            finally:
                server.shutdown();server.server_close();thread.join()

    def test_dual_model_status_only_lists_tags_and_reports_critic_digest(self):
        from test_director import model_endpoint, DIGEST
        seen=[]
        def response(path,body):
            seen.append(path)
            return 200,dict(models=[dict(name="qwen3.5:4b",digest=DIGEST),dict(name="second-model",digest="b"*64)])
        with model_endpoint(response) as (url,requests), tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp);config=root/"model.toml"
            config.write_text('ai_mode = "dual"\npoll_seconds = 30\n[model]\nurl = "'+url+'"\nmodel = "qwen3.5:4b"\ndigest = "'+DIGEST+'"\n[critic]\nurl = "'+url+'"\nmodel = "second-model"\ndigest = "'+'c'*64+'"\n',encoding="utf-8")
            app=admin.Application(root/"bridge",root/"journal",root/"db",root/"catalog",config)
            app.check_model()
            self.assertEqual(app.model["ai_mode"],"dual")
            self.assertEqual(app.model["primary"]["state"],"ready")
            self.assertEqual(app.model["critic"]["state"],"revision_mismatch")
            self.assertEqual(app.model["state"],"revision_mismatch")
            self.assertEqual(seen,["/api/tags"])


if __name__ == "__main__":
    unittest.main()
