"""D1 preview loop. This module has no propose_scenario or world-writing call."""
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

def load_config(path):
    try:
        raw=tomllib.loads(Path(path).read_text(encoding="utf-8"))
        if set(raw)!={"model","poll_seconds"} or type(raw["model"]) is not dict:
            raise DirectorError("invalid_config")
        poll=raw["poll_seconds"]
        if type(poll) is not int or not 5 <= poll <= 300:
            raise DirectorError("invalid_config")
        return ModelSettings(**raw["model"]),poll
    except (OSError,ValueError,TypeError):
        raise DirectorError("invalid_config") from None

def main():
    parser=argparse.ArgumentParser(description="DEAD EARTH director D1: read-only scenario previews")
    parser.add_argument("--bridge",type=Path,required=True)
    parser.add_argument("--config",type=Path,default=Path(__file__).resolve().parents[1]/"director.example.toml")
    parser.add_argument("--journal",type=Path,default=Path(__file__).resolve().parents[1]/"runtime"/"decisions.sqlite3")
    parser.add_argument("--once",action="store_true")
    args=parser.parse_args()
    journal=None
    try:
        settings,poll=load_config(args.config)
        client=BridgeClient(Descriptor.load(args.bridge))
        journal=Journal(args.journal)
        director=Director(client,OllamaModel(settings),journal)
        previous=None
        while True:
            report=director.once()
            if report != previous:
                print(json.dumps(report,ensure_ascii=False,allow_nan=False),flush=True)
            previous=report
            if args.once:
                return 0
            time.sleep(poll)
    except KeyboardInterrupt:
        return 0
    except (BridgeError,DirectorError,ModelError) as error:
        print("Dead Earth: "+str(error),file=sys.stderr)
        return 2
    except (sqlite3.Error,OSError,ValueError,KeyError,TypeError):
        print("Dead Earth: director_unavailable",file=sys.stderr)
        return 2
    finally:
        if journal:
            journal.close()

if __name__=="__main__":
    raise SystemExit(main())
