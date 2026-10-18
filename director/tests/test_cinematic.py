import contextlib
import json
from pathlib import Path
import tempfile
import unittest
from uuid import UUID, uuid5
from dead_earth_director import host
from dead_earth_director.host import Journal, DirectorError
from dead_earth_director.model import OllamaModel, ModelSettings, ModelError
from dead_earth_director.bridge import BridgeClient, BridgeError, Descriptor
from test_director import model_endpoint, DIGEST
from test_bridge import endpoint, success, CAMPAIGN, EPOCH

GOAL="00000000-0000-4000-8000-000000000003"
ZONE="00000000-0000-4000-8000-000000000004"
OP="00000000-0000-4000-8000-000000000005"

def brief():
    options=[dict(eventId="spore_lure",zoneId=ZONE,title="Споровая приманка",tactic="lure",faction="forest",weight=1.25,budget=5),
             dict(eventId="false_siege",zoneId=ZONE,title="Ложная осада",tactic="feint",faction="parasites",weight=1.1,budget=4)]
    return dict(status="ready",catalogRevision="c"*64,activeTicks=20,season="CALM",pressure=1.0,
                goal=dict(id=GOAL,epoch=EPOCH,catalogRevision="c"*64,created=0,expires=2400,options=options),recent=[],active=0,queued=0)

class ChoiceModel:
    def __init__(self,error=None):self.calls=0;self.error=error;self.context=None
    def select(self,context):
        self.calls+=1;self.context=context
        if self.error:raise ModelError(self.error)
        return dict(option=1),dict(elapsed_ms=12,prompt_tokens=300,output_tokens=5)

def reply(request):
    if request["operation"]=="get_cinematic_brief":return success(brief())
    a=request["arguments"]
    return success(dict(requestId=a["request_id"],goalId=a["goal_id"],accepted=True,code="queued",operationId=OP))

class CinematicTests(unittest.TestCase):
    def test_phase_context_is_read_only_and_rejects_invalid_phase(self):
        from types import SimpleNamespace
        view=brief();view["parasitePhase"]=3
        _,context=host.cinematic_context(view,SimpleNamespace(world_epoch=EPOCH))
        self.assertEqual(context["parasite_phase"],3)
        for bad in (True,-1,6,"3"):
            view["parasitePhase"]=bad
            with self.subTest(phase=bad),self.assertRaises(DirectorError):
                host.cinematic_context(view,SimpleNamespace(world_epoch=EPOCH))

    def test_public_event_id_connects_choices_to_recent_history(self):
        from types import SimpleNamespace
        view=brief();view['recent']=[dict(eventId='false_siege',outcome='repelled')]
        _,context=host.cinematic_context(view,SimpleNamespace(world_epoch=EPOCH))
        self.assertEqual(context['options'][1]['event'],'false_siege')
        self.assertEqual(context['recent'],[dict(event='false_siege',outcome='repelled')])
        self.assertNotIn(ZONE,json.dumps(context))

    def director(self,client,model,journal):
        factory=getattr(host,"CinematicDirector",None)
        self.assertTrue(callable(factory),"live cinematic host is missing")
        return factory(client,model,journal)

    def test_live_choice_is_bounded_and_does_not_expose_coordinates_or_token(self):
        with endpoint(reply) as (path,requests),tempfile.TemporaryDirectory() as tmp,contextlib.ExitStack() as stack:
            journal=stack.enter_context(contextlib.closing(Journal(Path(tmp)/"live.db")));model=ChoiceModel()
            report=self.director(BridgeClient(Descriptor.load(path)),model,journal).once()
            self.assertEqual(report["state"],"queued");self.assertEqual(model.calls,1)
            self.assertEqual(requests[-1]["operation"],"choose_cinematic")
            self.assertEqual(requests[-1]["arguments"]["event_id"],"false_siege")
            context=json.dumps(model.context,ensure_ascii=False)
            self.assertNotIn(ZONE,context);self.assertNotIn("a"*64,context);self.assertNotIn("budget",context)
            audit=journal.read(GOAL)["choice"]["audit"]
            self.assertEqual(audit["campaign_id"],CAMPAIGN)
            self.assertEqual(audit["world_epoch"],EPOCH)
            self.assertEqual(audit["selected_index"],1)
            self.assertEqual(audit["options"][0]["weight"],1.25)
            self.assertGreater(audit["recorded_at"],0)
            self.assertEqual(journal.read(GOAL)["report"]["operation_id"],OP)
            journal.close()

    def test_model_unavailable_selects_server_shortlist_fallback(self):
        with endpoint(reply) as (path,requests),tempfile.TemporaryDirectory() as tmp,contextlib.ExitStack() as stack:
            journal=stack.enter_context(contextlib.closing(Journal(Path(tmp)/"live.db")))
            result=self.director(BridgeClient(Descriptor.load(path)),ChoiceModel("model_timeout"),journal).once()
            self.assertEqual(result["source"],"fallback");self.assertEqual(requests[-1]["arguments"]["event_id"],"spore_lure")
            journal.close()

    def test_unknown_submission_outcome_retries_durable_choice_before_new_goal(self):
        attempts=0
        def respond(request):
            nonlocal attempts
            if request["operation"]=="choose_cinematic":
                attempts+=1
                if attempts==1:return dict(ok=False,error=dict(code="request_expired"))
            if attempts and request["operation"]=="get_cinematic_brief":return success(dict(status="waiting"))
            return reply(request)
        with endpoint(respond) as (path,requests),tempfile.TemporaryDirectory() as tmp,contextlib.ExitStack() as stack:
            path_db=Path(tmp)/"live.db";journal=stack.enter_context(contextlib.closing(Journal(path_db)));model=ChoiceModel();client=BridgeClient(Descriptor.load(path))
            with self.assertRaises(BridgeError):self.director(client,model,journal).once()
            journal.close();journal=stack.enter_context(contextlib.closing(Journal(path_db)))
            self.assertEqual(self.director(client,model,journal).once()["state"],"queued")
            self.assertEqual(model.calls,1)
            choices=[r["arguments"] for r in requests if r["operation"]=="choose_cinematic"]
            self.assertEqual(choices[0],choices[1]);journal.close()

    def test_choice_adapter_uses_cpu_schema_and_rejects_extra_authority(self):
        for content,valid in [('{{"option":1}}',True),('{{"option":true}}',False),('{{"option":2}}',False),
                              ('{{"option":1,"count":20}}',False),('{{"option":0,"option":1}}',False)]:
            content=content.replace('{{','{').replace('}}','}')
            def respond(path,body):
                if path=="/api/tags":return 200,dict(models=[dict(name="qwen3.5:4b",digest=DIGEST)])
                return 200,dict(done=True,done_reason="stop",message=dict(content=content),prompt_eval_count=300,eval_count=5)
            with self.subTest(content=content),model_endpoint(respond) as (url,requests):
                model=OllamaModel(ModelSettings(url,"qwen3.5:4b",DIGEST))
                select=getattr(model,"select",None);self.assertTrue(callable(select),"bounded event selection adapter is missing")
                if valid:
                    value,_=select(dict(options=[dict(option=0),dict(option=1)]));self.assertEqual(value,dict(option=1))
                    self.assertEqual(requests[-1][1]["options"]["num_gpu"],0)
                    self.assertEqual(requests[-1][1]["format"]["properties"]["option"]["enum"],[0,1])
                else:
                    with self.assertRaises(ModelError):select(dict(options=[dict(option=0),dict(option=1)]))

    def test_no_players_or_invalid_context_never_admits(self):
        for status in ("disabled","no_players","waiting","catalog_unavailable","runtime_fault","unsupported_save"):
            with endpoint(lambda r:success(dict(status=status))) as (path,requests),tempfile.TemporaryDirectory() as tmp,contextlib.ExitStack() as stack:
                journal=stack.enter_context(contextlib.closing(Journal(Path(tmp)/"live.db")));model=ChoiceModel()
                self.assertEqual(self.director(BridgeClient(Descriptor.load(path)),model,journal).once(),dict(state=status))
                self.assertEqual(model.calls,0);self.assertEqual(len(requests),1);journal.close()
