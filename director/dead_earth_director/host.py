"""Durable preview and bounded live director loops; the server owns physical execution."""
import argparse
import json
import math
from pathlib import Path
import sqlite3
import sys
import time
import tomllib
from uuid import UUID, uuid5
from .bridge import BridgeClient, BridgeError, Descriptor, _uuid
from .model import ModelSettings, ModelError, OllamaModel, VARIANTS, valid_narrative
from .council import CouncilError, CouncilModel, CouncilSettings
from .ai_control import ModeStore,RuntimeModels,ControlError

class DirectorError(RuntimeError):
    pass

def context_for(view, descriptor):
    try:
        plan = view["planning"]
        goal = plan["goal"]
        if (type(goal) is not dict or set(goal) != {"id","campaignId","worldEpoch","policyRevision","targetId","tactic",
                                                     "createdActiveTicks","expiresActiveTicks","variants"}
                or not all(_uuid(goal[k]) for k in ("id","campaignId","worldEpoch","policyRevision"))
                or goal["campaignId"] != descriptor.campaign_id or goal["worldEpoch"] != descriptor.world_epoch
                or goal["variants"] != list(VARIANTS)):
            raise DirectorError("invalid_brief")
        target = goal["targetId"]
        if type(target) is not str or not (target == "campaign" or target.startswith("zone:") and _uuid(target[5:])):
            raise DirectorError("invalid_brief")
        if goal["tactic"] != ("observe_campaign" if target == "campaign" else "observe_zone"):
            raise DirectorError("invalid_brief")
        now, created, expires = plan["activeTicks"], goal["createdActiveTicks"], goal["expiresActiveTicks"]
        if any(type(v) is not int for v in (now,created,expires)) or not 0 <= created <= now < expires <= created+72000:
            raise DirectorError("invalid_brief")
        pressure = view["pressure"]
        if type(pressure) not in (float,int) or not math.isfinite(pressure) or not .01 <= pressure <= 100:
            raise DirectorError("invalid_brief")
        season = view["season"]
        if type(season) is not str or len(season) > 32 or not season.replace("_","").isalpha():
            raise DirectorError("invalid_brief")
        # Deliberate projection: no chat, player names, coordinates or bridge credentials.
        return goal, dict(goal=goal["tactic"], target_kind="campaign" if target=="campaign" else "zone",
                          season=season, pressure=pressure, permitted_variants=list(VARIANTS))
    except (KeyError,TypeError,ValueError):
        raise DirectorError("invalid_brief") from None

class Journal:
    def __init__(self, path):
        path = Path(path)
        if path.is_symlink():
            raise DirectorError("journal_unavailable")
        path.parent.mkdir(parents=True,exist_ok=True)
        try:
            self.db = sqlite3.connect(path)
            version = self.db.execute("PRAGMA user_version").fetchone()[0]
            tables = self.db.execute("SELECT name FROM sqlite_master WHERE type='table'").fetchall()
            if version not in (0,1) or version==0 and tables:
                self.db.close()
                raise DirectorError("journal_schema_unsupported")
            if version==0:
                self.db.execute("CREATE TABLE decisions (goal_id TEXT PRIMARY KEY, request_id TEXT NOT NULL, choice TEXT, report TEXT)")
                self.db.execute("PRAGMA user_version=1")
                self.db.commit()
        except sqlite3.Error:
            raise DirectorError("journal_unavailable") from None

    def read(self, goal_id):
        try:
            row = self.db.execute("SELECT request_id,choice,report FROM decisions WHERE goal_id=?",(goal_id,)).fetchone()
            return None if row is None else dict(request_id=row[0],choice=json.loads(row[1]) if row[1] else None,
                                                report=json.loads(row[2]) if row[2] else None)
        except (sqlite3.Error,ValueError):
            raise DirectorError("journal_unavailable") from None

    def reserve(self, goal_id, request_id):
        self.db.execute("INSERT INTO decisions(goal_id,request_id) VALUES (?,?)",(goal_id,request_id))
        self.db.commit()

    def choice(self, goal_id, choice):
        self.db.execute("UPDATE decisions SET choice=? WHERE goal_id=?",(json.dumps(choice,ensure_ascii=False,allow_nan=False),goal_id))
        self.db.commit()

    def finish(self, goal_id, report):
        self.db.execute("UPDATE decisions SET report=? WHERE goal_id=?",(json.dumps(report,ensure_ascii=False,allow_nan=False),goal_id))
        self.db.execute("DELETE FROM decisions WHERE rowid IN (SELECT rowid FROM decisions ORDER BY rowid DESC LIMIT -1 OFFSET 4096)")
        self.db.commit()

    def pending(self):
        try:
            rows=self.db.execute("SELECT goal_id,request_id,choice FROM decisions WHERE report IS NULL AND choice IS NOT NULL ORDER BY rowid LIMIT 16").fetchall()
            return [dict(goal_id=g,request_id=r,choice=json.loads(c)) for g,r,c in rows]
        except (sqlite3.Error,ValueError):
            raise DirectorError("journal_unavailable") from None

    def close(self):
        self.db.close()

class Director:
    def __init__(self, client, model, journal):
        self.client,self.model,self.journal=client,model,journal

    def once(self):
        view = self.client.call("get_director_brief",{})
        if type(view) is not dict or type(view.get("planning")) is not dict:
            raise DirectorError("invalid_brief")
        status = view["planning"].get("status")
        if status in {"disabled","no_players","waiting"}:
            return dict(state=status)
        if status != "ready":
            raise DirectorError("invalid_brief")
        goal, context = context_for(view,self.client.descriptor)
        saved = self.journal.read(goal["id"])
        if saved and saved["report"] is not None:
            return dict(state="already_recorded",goal_id=goal["id"])
        request_id = str(uuid5(UUID(goal["id"]),"dead-earth-preview-d1"))
        if saved is None:
            self.journal.reserve(goal["id"],request_id)
        elif saved["request_id"] != request_id:
            raise DirectorError("journal_context_mismatch")
        choice = saved["choice"] if saved else None
        if choice is None:
            metrics={}
            try:
                narrative,metrics = self.model.generate(context)
                source,reason="model","model_validated"
            except ModelError as error:
                narrative=dict(variant="distant_sound",text="План наблюдения готов: проверить внешние подступы.")
                source,reason="fallback",str(error)
            if type(narrative) is not dict or set(narrative)!={"variant","text"} or not valid_narrative(narrative["variant"],narrative["text"]):
                raise DirectorError("invalid_model_adapter")
            choice=dict(narrative=narrative,source=source,reason=reason,metrics=metrics)
            self.journal.choice(goal["id"],choice)
        narrative=choice["narrative"]
        if type(narrative) is not dict or set(narrative)!={"variant","text"} or not valid_narrative(narrative["variant"],narrative["text"]):
            raise DirectorError("journal_unavailable")
        verdict=self.client.call("preview_director",dict(request_id=request_id,goal_id=goal["id"],**narrative))
        if (type(verdict) is not dict or verdict.get("requestId") != request_id or verdict.get("goalId") != goal["id"]
                or type(verdict.get("accepted")) is not bool or verdict.get("previewOnly") is not True
                or verdict.get("code") not in {"preview_accepted","stale_goal","unknown_goal","invalid_narrative"}
                or verdict["accepted"] != (verdict["code"]=="preview_accepted")):
            raise DirectorError("invalid_preview")
        report=dict(state="preview_accepted" if verdict["accepted"] else "preview_rejected",goal_id=goal["id"],
                    target_id=goal["targetId"],source=choice["source"],reason=choice["reason"],
                    code=verdict["code"],metrics=choice["metrics"],preview_only=True)
        self.journal.finish(goal["id"],report)
        return report

def cinematic_context(view, descriptor):
    try:
        goal=view["goal"]
        if (type(goal) is not dict or set(goal)!={"id","epoch","catalogRevision","created","expires","options"}
                or not _uuid(goal["id"]) or goal["epoch"]!=descriptor.world_epoch
                or type(goal["catalogRevision"]) is not str or len(goal["catalogRevision"])!=64
                or any(c not in "0123456789abcdef" for c in goal["catalogRevision"])
                or goal["catalogRevision"]!=view["catalogRevision"]):
            raise DirectorError("invalid_brief")
        now,created,expires=view["activeTicks"],goal["created"],goal["expires"]
        if any(type(v) is not int for v in (now,created,expires)) or not 0 <= created <= now < expires <= created+72000:
            raise DirectorError("invalid_brief")
        if view["season"] not in {"CALM","HEAT","ASH_WINTER"} or type(view["pressure"]) not in (int,float) or not math.isfinite(view["pressure"]) or not .05 <= view["pressure"] <= 4:
            raise DirectorError("invalid_brief")
        options=goal["options"]
        if type(options) is not list or not 1 <= len(options) <= 8:
            raise DirectorError("invalid_brief")
        projected=[]
        import re
        for index,o in enumerate(options):
            if (type(o) is not dict or set(o)!={"eventId","zoneId","title","tactic","faction","weight","budget"}
                    or not _uuid(o["zoneId"]) or type(o["eventId"]) is not str or not re.fullmatch(r"[a-z][a-z0-9_]{0,63}",o["eventId"])
                    or type(o["tactic"]) is not str or not re.fullmatch(r"[a-z][a-z0-9_]{0,63}",o["tactic"])
                    or not valid_narrative("distant_sound",o["title"]) or o["faction"] not in {"forest","parasites","mixed"}
                    or type(o["weight"]) not in (int,float) or not math.isfinite(o["weight"]) or not 0 < o["weight"] <= 10000
                    or type(o["budget"]) is not int or not 1 <= o["budget"] <= 32):
                raise DirectorError("invalid_brief")
            projected.append(dict(option=index,event=o["eventId"],title=o["title"],tactic=o["tactic"],faction=o["faction"],weight=round(o["weight"],4)))
        recent=view["recent"]
        if type(recent) is not list or len(recent)>6:
            raise DirectorError("invalid_brief")
        history=[]
        for h in recent:
            if (type(h) is not dict or type(h.get("eventId")) is not str or not re.fullmatch(r"[a-z][a-z0-9_]{0,63}",h["eventId"])
                    or h.get("outcome") not in {"repelled","contained","avoided","engaged","inconclusive","uncertain","expired","cancelled"}):
                raise DirectorError("invalid_brief")
            history.append(dict(event=h["eventId"],outcome=h["outcome"]))
        phase=view.get("parasitePhase")
        if phase is not None and (type(phase) is not int or not 0 <= phase <= 5):
            raise DirectorError("invalid_brief")
        context=dict(season=view["season"],pressure=view["pressure"],options=projected,recent=history)
        if phase is not None:context["parasite_phase"]=phase
        return goal,context
    except (KeyError,TypeError,ValueError):
        raise DirectorError("invalid_brief") from None

class CinematicDirector:
    def __init__(self,client,model,journal):
        self.client,self.model,self.journal=client,model,journal

    def _submit(self,goal_id,request_id,choice):
        import re
        if (type(choice) is not dict or choice.get("kind")!="cinematic_v1" or not _uuid(goal_id) or not _uuid(request_id)
                or not _uuid(choice.get("zone_id")) or type(choice.get("event_id")) is not str
                or not re.fullmatch(r"[a-z][a-z0-9_]{0,63}",choice["event_id"])
                or request_id!=str(uuid5(UUID(goal_id),"dead-earth-cinematic-v1"))):
            raise DirectorError("journal_unavailable")
        verdict=self.client.call("choose_cinematic",dict(request_id=request_id,goal_id=goal_id,event_id=choice["event_id"],zone_id=choice["zone_id"]))
        accepted={"queued","already_queued","already_recorded"}
        rejected={"stale_goal","ineligible","queue_full","disabled","conflicting_request"}
        if (type(verdict) is not dict or verdict.get("requestId")!=request_id or verdict.get("goalId")!=goal_id
                or type(verdict.get("accepted")) is not bool or verdict.get("code") not in accepted|rejected
                or verdict["accepted"]!=(verdict["code"] in accepted)
                or verdict["accepted"] and not _uuid(verdict.get("operationId"))):
            raise DirectorError("invalid_admission")
        report=dict(state=verdict["code"],goal_id=goal_id,source=choice["source"],reason=choice["reason"],
                    event_id=choice["event_id"],metrics=choice["metrics"],
                    operation_id=verdict.get("operationId"),submitted_at=time.time())
        self.journal.finish(goal_id,report)
        return report

    def once(self):
        # Resolve unknown outcomes first, even if that server goal is no longer advertised.
        for pending in self.journal.pending():
            if pending["choice"].get("kind")=="cinematic_v1":
                return self._submit(pending["goal_id"],pending["request_id"],pending["choice"])
        view=self.client.call("get_cinematic_brief",{})
        if type(view) is not dict:
            raise DirectorError("invalid_brief")
        status=view.get("status")
        if status in {"disabled","waiting","no_players","catalog_unavailable","runtime_fault","unsupported_save"}:
            return dict(state=status)
        if status!="ready":
            raise DirectorError("invalid_brief")
        goal,context=cinematic_context(view,self.client.descriptor)
        saved=self.journal.read(goal["id"])
        if saved and saved["report"] is not None:
            return dict(state="already_recorded",goal_id=goal["id"])
        request_id=str(uuid5(UUID(goal["id"]),"dead-earth-cinematic-v1"))
        if saved is None:
            self.journal.reserve(goal["id"],request_id)
        elif saved["request_id"]!=request_id:
            raise DirectorError("journal_context_mismatch")
        try:
            if isinstance(self.model,CouncilModel):
                decision,metrics=self.model.select(context,deadline=council_deadline(view,self.model.settings))
            else:
                decision,metrics=self.model.select(context)
            if type(decision) is not dict or set(decision)!={"option"} or type(decision["option"]) is not int or not 0 <= decision["option"] < len(goal["options"]):
                raise ModelError("model_invalid_response")
            source,reason="model","model_validated"
        except ModelError as error:
            decision,metrics=dict(option=0),error.metrics if isinstance(error,CouncilError) else {}
            source,reason="fallback",str(error)
        selected=goal["options"][decision["option"]]
        choice=dict(kind="cinematic_v1",event_id=selected["eventId"],zone_id=selected["zoneId"],source=source,reason=reason,metrics=metrics,
                    audit=dict(campaign_id=self.client.descriptor.campaign_id,world_epoch=self.client.descriptor.world_epoch,
                               recorded_at=time.time(),active_ticks=view["activeTicks"],season=view["season"],pressure=view["pressure"],
                               catalog_revision=view["catalogRevision"],selected_index=decision["option"],options=goal["options"]))
        self.journal.choice(goal["id"],choice)
        return self._submit(goal["id"],request_id,choice)


def council_deadline(view,settings,*,now=None):
    now=time.monotonic() if now is None else now
    remaining=(view['goal']['expires']-view['activeTicks'])/20
    return now+max(0,min(settings.budget_seconds,remaining-settings.margin_seconds))

def build_model(settings,mode,*,continue_dual=None):
    primary=OllamaModel(settings)
    if mode=='live' and getattr(settings,'ai_mode','single')=='dual':
        return CouncilModel(primary,OllamaModel(settings.critic),settings,continue_dual=continue_dual)
    return primary

def load_config(path):
    try:
        raw=tomllib.loads(Path(path).read_text(encoding="utf-8"))
        if (not {'model','poll_seconds'}<=set(raw) or set(raw)-{'model','poll_seconds','ai_mode','critic','council'}
                or type(raw['model']) is not dict or set(raw['model'])-set(ModelSettings.__dataclass_fields__)):
            raise DirectorError("invalid_config")
        poll=raw["poll_seconds"]
        if type(poll) is not int or not 5 <= poll <= 300:
            raise DirectorError("invalid_config")
        council=raw.get('council',{})
        if type(council) is not dict or set(council)-{'budget_seconds','margin_seconds'}:
            raise DirectorError('invalid_config')
        critic=raw.get('critic')
        if critic is not None:
            if type(critic) is not dict or set(critic)-set(ModelSettings.__dataclass_fields__):raise DirectorError('invalid_config')
            critic=ModelSettings(**critic)
        return CouncilSettings(**raw['model'],ai_mode=raw.get('ai_mode','single'),critic=critic,**council),poll
    except (OSError,ValueError,TypeError):
        raise DirectorError("invalid_config") from None

def main():
    parser=argparse.ArgumentParser(description="DEAD EARTH: bounded cinematic director or D1 previews")
    parser.add_argument("--bridge",type=Path,required=True)
    parser.add_argument("--config",type=Path,default=Path(__file__).resolve().parents[1]/"director.example.toml")
    parser.add_argument("--journal",type=Path,default=Path(__file__).resolve().parents[1]/"runtime"/"decisions.sqlite3")
    parser.add_argument("--once",action="store_true")
    parser.add_argument("--mode",choices=("preview","live"),default="preview")
    parser.add_argument("--stop-file",type=Path)
    args=parser.parse_args()
    journal=None;runtime_models=None
    try:
        settings,poll=load_config(args.config)
        client=BridgeClient(Descriptor.load(args.bridge))
        journal=Journal(args.journal)
        runtime_models=RuntimeModels(settings,ModeStore(args.journal.parent/'ai-control.sqlite3'),args.mode)
        director=(CinematicDirector if args.mode=="live" else Director)(client,runtime_models.refresh(),journal)
        previous=None
        while True:
            if args.stop_file and args.stop_file.exists():return 0
            director.model=runtime_models.refresh()
            report=director.once()
            director.model=runtime_models.refresh()
            if report != previous:
                print(json.dumps(report,ensure_ascii=False,allow_nan=False),flush=True)
            previous=report
            if args.once:
                return 0
            for _ in range(poll):
                if args.stop_file and args.stop_file.exists():return 0
                director.model=runtime_models.refresh()
                time.sleep(1)
    except KeyboardInterrupt:
        return 0
    except (BridgeError,DirectorError,ModelError,ControlError) as error:
        print("Dead Earth: "+str(error),file=sys.stderr)
        return 2
    except (sqlite3.Error,OSError,ValueError,KeyError,TypeError):
        print("Dead Earth: director_unavailable",file=sys.stderr)
        return 2
    finally:
        if runtime_models:
            try:runtime_models.close()
            except ControlError:pass
        if journal:
            journal.close()

if __name__=="__main__":
    raise SystemExit(main())
