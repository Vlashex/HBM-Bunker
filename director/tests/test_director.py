import contextlib
import json
from pathlib import Path
import sqlite3
import tempfile
import threading
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from uuid import UUID

from dead_earth_director.bridge import BridgeClient, BridgeError, Descriptor
from dead_earth_director.host import Director, DirectorError, Journal, context_for, load_config
from dead_earth_director.model import ModelSettings, ModelError, OllamaModel, parse_narrative, VARIANTS
from test_bridge import descriptor, endpoint, success, CAMPAIGN, EPOCH

GOAL="00000000-0000-4000-8000-000000000003"
DIGEST="d"*64
NARRATIVE=dict(variant="radio_static",text="В эфире появился короткий шорох.")

def brief(**changes):
    goal=dict(id=GOAL,campaignId=CAMPAIGN,worldEpoch=EPOCH,policyRevision=CAMPAIGN,
              targetId="campaign",tactic="observe_campaign",createdActiveTicks=0,expiresActiveTicks=2400,
              variants=list(VARIANTS))
    return dict(planning=dict(status="ready",activeTicks=20,nextDecisionActiveTicks=18000,zonesSeen=0,
                              candidates=[],goal=goal),season="WINTER",pressure=1.0) | changes

class FixedModel:
    def __init__(self,error=None): self.calls=0; self.error=error
    def generate(self,context):
        self.calls+=1
        if self.error: raise ModelError(self.error)
        return NARRATIVE,dict(elapsed_ms=12,prompt_tokens=200,output_tokens=30)

def reply(request):
    if request["operation"]=="get_director_brief": return success(brief())
    args=request["arguments"]
    return success(dict(requestId=args["request_id"],goalId=args["goal_id"],accepted=True,
                        code="preview_accepted",previewOnly=True))

@contextlib.contextmanager
def model_endpoint(responder):
    requests=[]
    class Handler(BaseHTTPRequestHandler):
        def log_message(self,*args): pass
        def do_GET(self): self.respond(None)
        def do_POST(self): self.respond(json.loads(self.rfile.read(int(self.headers["Content-Length"]))))
        def respond(self,body):
            requests.append((self.path,body))
            status,payload=responder(self.path,body)
            self.send_response(status);self.end_headers()
            self.wfile.write(payload if isinstance(payload,bytes) else json.dumps(payload).encode())
    with ThreadingHTTPServer(("127.0.0.1",0),Handler) as server:
        thread=threading.Thread(target=lambda:server.serve_forever(poll_interval=.01),daemon=True);thread.start()
        try: yield "http://127.0.0.1:"+str(server.server_port),requests
        finally: server.shutdown();thread.join(1)

class DirectorTests(unittest.TestCase):
    def test_full_loop_only_previews_and_duplicate_survives_restart(self):
        with endpoint(reply) as (path,requests),tempfile.TemporaryDirectory() as tmp:
            model=FixedModel();db=Path(tmp)/"decisions.db"
            journal=Journal(db)
            director=Director(BridgeClient(Descriptor.load(path)),model,journal)
            self.assertEqual(director.once()["state"],"preview_accepted")
            journal.close()
            journal=Journal(db)
            self.assertEqual(Director(director.client,model,journal).once()["state"],"already_recorded")
            self.assertEqual(model.calls,1)
            self.assertEqual([r["operation"] for r in requests],["get_director_brief","preview_director","get_director_brief"])
            journal.close()
            self.assertNotIn("a"*64,db.read_bytes().decode("utf-8",errors="ignore"))

    def test_model_failure_uses_validated_fallback(self):
        for error in ("model_timeout","model_invalid_response","model_revision_mismatch"):
            with self.subTest(error=error),endpoint(reply) as (path,requests),tempfile.TemporaryDirectory() as tmp:
                journal=Journal(Path(tmp)/"decisions.db")
                report=Director(BridgeClient(Descriptor.load(path)),FixedModel(error),journal).once()
                self.assertEqual(report["source"],"fallback");self.assertEqual(report["reason"],error)
                self.assertTrue(report["preview_only"]);journal.close()

    def test_no_players_or_disabled_does_not_call_model_or_preview(self):
        for status in ("no_players","disabled","waiting"):
            with endpoint(lambda r:success(brief(planning=dict(status=status)))) as (path,requests),tempfile.TemporaryDirectory() as tmp:
                journal=Journal(Path(tmp)/"decisions.db");model=FixedModel()
                self.assertEqual(Director(BridgeClient(Descriptor.load(path)),model,journal).once(),dict(state=status))
                self.assertEqual(model.calls,0);self.assertEqual(len(requests),1);journal.close()

    def test_stale_result_is_rejected_and_recorded(self):
        def respond(request):
            result=reply(request)
            if request["operation"]=="preview_director":
                result["result"].update(accepted=False,code="stale_goal")
            return result
        with endpoint(respond) as (path,requests),tempfile.TemporaryDirectory() as tmp:
            journal=Journal(Path(tmp)/"decisions.db")
            self.assertEqual(Director(BridgeClient(Descriptor.load(path)),FixedModel(),journal).once()["state"],"preview_rejected")
            journal.close()

    def test_failed_preview_retries_same_request_and_saved_choice(self):
        count=0
        def respond(request):
            nonlocal count
            if request["operation"]=="preview_director":
                count+=1
                if count==1: return dict(ok=False,error=dict(code="request_expired"))
            return reply(request)
        with endpoint(respond) as (path,requests),tempfile.TemporaryDirectory() as tmp:
            journal=Journal(Path(tmp)/"decisions.db");model=FixedModel()
            director=Director(BridgeClient(Descriptor.load(path)),model,journal)
            with self.assertRaises(BridgeError): director.once()
            self.assertEqual(director.once()["state"],"preview_accepted")
            self.assertEqual(model.calls,1)
            previews=[r["arguments"] for r in requests if r["operation"]=="preview_director"]
            self.assertEqual(previews[0],previews[1]);journal.close()

    def test_untrusted_context_and_extended_arguments_are_rejected(self):
        view=brief()
        for key,value in [("campaignId",EPOCH),("targetId","0 0 0"),("variants",["command"]),("expiresActiveTicks",True)]:
            changed=brief();changed["planning"]["goal"][key]=value
            with self.subTest(key=key),self.assertRaises(DirectorError):
                context_for(changed,Descriptor(**{k:v for k,v in descriptor().items() if k!="protocol"}))
        with endpoint(reply) as (path,requests):
            args=dict(request_id=CAMPAIGN,goal_id=GOAL,**NARRATIVE)
            BridgeClient(Descriptor.load(path)).call("preview_director",args)
            for change in (dict(count=10),dict(text="x\nstop"),dict(variant="execute"),dict(goal_id="1")):
                with self.assertRaises(BridgeError):
                    BridgeClient(Descriptor.load(path)).call("preview_director",args | change)
            self.assertEqual(len(requests),1)

    def test_unknown_journal_schema_is_preserved(self):
        with tempfile.TemporaryDirectory() as tmp:
            path=Path(tmp)/"journal.db"
            db=sqlite3.connect(path)
            db.execute("PRAGMA user_version=99");db.commit();db.close()
            before=path.read_bytes()
            with self.assertRaisesRegex(DirectorError,"schema_unsupported"): Journal(path)
            self.assertEqual(before,path.read_bytes())

    def test_model_json_rejects_duplicates_extras_controls_and_nonfinite(self):
        for raw in ['{"variant":"radio_static","text":"x","text":"y"}',
                    '{"variant":"radio_static","text":"x","count":10}',
                    '{"variant":"radio_static","text":NaN}',
                    '{"variant":"radio_static","text":"x\\nstop"}','[]']:
            with self.subTest(raw=raw),self.assertRaises(ModelError): parse_narrative(raw)
        self.assertEqual(parse_narrative(json.dumps(NARRATIVE)),NARRATIVE)

    def test_cpu_schema_pin_and_unload_are_sent_to_real_http_endpoint(self):
        def respond(path,body):
            if path=="/api/tags": return 200,dict(models=[dict(name="qwen3.5:4b",digest=DIGEST)])
            return 200,dict(done=True,done_reason="stop",message=dict(content=json.dumps(NARRATIVE)),
                            prompt_eval_count=200,eval_count=25)
        with model_endpoint(respond) as (url,requests):
            model=OllamaModel(ModelSettings(url,"qwen3.5:4b",DIGEST))
            value,metrics=model.generate(dict(goal="observe_campaign"))
            self.assertEqual(value,NARRATIVE);self.assertEqual(metrics["output_tokens"],25)
            sent=requests[-1][1]
            self.assertEqual(sent["options"]["num_gpu"],0);self.assertEqual(sent["options"]["num_thread"],4)
            self.assertFalse(sent["think"]);self.assertEqual(sent["keep_alive"],0)
            self.assertFalse(sent["format"]["additionalProperties"])

    def test_model_mismatch_truncation_and_remote_errors_fail_closed(self):
        for mode in ("mismatch","truncated","http"):
            def respond(path,body):
                if path=="/api/tags": return 200,dict(models=[dict(name="qwen3.5:4b",digest="x" if mode=="mismatch" else DIGEST)])
                if mode=="http": return 500,b"private backend diagnostics"
                return 200,dict(done=True,done_reason="length",message=dict(content=json.dumps(NARRATIVE)))
            with self.subTest(mode=mode),model_endpoint(respond) as (url,requests):
                with self.assertRaises(ModelError) as error: OllamaModel(ModelSettings(url,"qwen3.5:4b",DIGEST)).generate({})
                self.assertNotIn("private",str(error.exception))

    def test_model_settings_and_toml_fail_closed(self):
        for url in ("http://example.com:11434","http://localhost:11434","http://127.0.0.1:0","http://127.0.0.1:11434/path"):
            with self.assertRaises(ModelError): ModelSettings(url,"qwen3.5:4b",DIGEST)
        with tempfile.TemporaryDirectory() as tmp:
            path=Path(tmp)/"config.toml";path.write_text('poll_seconds = 1',encoding="utf-8")
            with self.assertRaises(DirectorError): load_config(path)
