"""Bounded observer data; the director journal is always opened read-only."""
from contextlib import closing, contextmanager
import json
import hashlib
import math
from pathlib import Path
import sqlite3
import threading
import time
from .bridge import _uuid
from . import admin_analytics

RETENTION = 7 * 86400
MAX_SAMPLES = 20000


def safe_tree(value, depth=0):
    if depth > 10: return None
    if value is None or type(value) is bool: return value
    if type(value) in (int, float): return value if math.isfinite(value) else None
    if type(value) is str: return value[:2000]
    if type(value) is list: return [safe_tree(v, depth+1) for v in value[:2048]]
    if type(value) is dict:
        return {str(k)[:100]: safe_tree(v, depth+1) for k,v in list(value.items())[:512]
                if str(k).lower() not in {"token", "authorization", "password", "secret", "descriptor"}}
    return None


def _pick(value, keys):
    return {k: safe_tree(value[k]) for k in keys if k in value} if type(value) is dict else {}


def _decision(choice, report):
    c = _pick(choice, ("kind", "event_id", "zone_id", "source", "reason", "narrative"))
    c["metrics"] = admin_analytics.council_metrics(choice.get("metrics"))
    audit = choice.get("audit")
    if type(audit) is dict:
        a = _pick(audit, ("campaign_id", "world_epoch", "recorded_at", "selected_index", "season", "pressure", "active_ticks", "catalog_revision"))
        a["options"] = [_pick(o, ("eventId", "zoneId", "title", "tactic", "faction", "weight", "budget"))
                        for o in audit.get("options", [])[:8]] if type(audit.get("options")) is list else []
        c["audit"] = a
    return c, _pick(report, ("state", "code", "preview_only", "operation_id", "submitted_at")) if report else None


def read_decisions(path, scope=None, limit=500):
    result = dict(status="ok", items=[], unscoped=0, scopes=[])
    scopes = {}
    path = Path(path)
    if not path.is_file(): return dict(status="missing", items=[], unscoped=0)
    try:
        # No Journal instance: it creates/migrates tables and commits.
        with closing(sqlite3.connect(path.resolve().as_uri()+"?mode=ro", uri=True, timeout=.8)) as db:
            db.execute("PRAGMA query_only=ON")
            if db.execute("PRAGMA user_version").fetchone()[0] != 1: raise ValueError()
            rows = db.execute("SELECT goal_id,request_id,choice,report FROM decisions ORDER BY rowid DESC LIMIT ?", (4096,)).fetchall()
        for goal,request,raw_choice,raw_report in rows:
            if any(v is not None and (type(v) is not str or len(v)>65536) for v in (raw_choice,raw_report)):
                continue
            try:
                choice=json.loads(raw_choice) if raw_choice else {}
                report=json.loads(raw_report) if raw_report else None
                if type(choice) is not dict or (report is not None and type(report) is not dict): continue
                c,r=_decision(choice,report)
            except (ValueError,TypeError,RecursionError): continue
            a=c.get("audit", {})
            decision_scope=(a.get("campaign_id", "")+":"+a.get("world_epoch", "")) if _uuid(a.get("campaign_id")) and _uuid(a.get("world_epoch")) else None
            if decision_scope is None: result["unscoped"]+=1
            else:
                recorded = a.get("recorded_at")
                recorded = recorded if type(recorded) in (int,float) and math.isfinite(recorded) else None
                previous = scopes.get(decision_scope)
                if previous is None or (recorded is not None and recorded > (previous["at"] or 0)):
                    scopes[decision_scope] = dict(id=decision_scope,at=recorded)
            if scope == "unscoped" and decision_scope is not None: continue
            if scope and scope != "unscoped" and decision_scope != scope: continue
            if len(result["items"]) < max(0,min(500,limit)):
                result["items"].append(dict(goal_id=str(goal)[:100],request_id=str(request)[:100],scope=decision_scope,choice=c,report=r))
        result["scopes"] = sorted(scopes.values(),key=lambda s:s["at"] or 0,reverse=True)
        return result
    except (OSError, sqlite3.Error, ValueError):
        return dict(status="unavailable", items=[], unscoped=0)


class Telemetry:
    def __init__(self, path, max_samples=MAX_SAMPLES):
        self.path=Path(path)
        self.path.parent.mkdir(parents=True, exist_ok=True)
        self.max_samples=max_samples
        self.lock=threading.RLock()
        with self.lock, self._db() as db:
            version=db.execute("PRAGMA user_version").fetchone()[0]
            if version not in (0,1): raise ValueError("unsupported_admin_store")
            db.execute("CREATE TABLE IF NOT EXISTS samples(id INTEGER PRIMARY KEY, at REAL NOT NULL, scope TEXT NOT NULL, data TEXT NOT NULL)")
            db.execute("CREATE INDEX IF NOT EXISTS samples_scope ON samples(scope,at)")
            db.execute("CREATE TABLE IF NOT EXISTS states(scope TEXT PRIMARY KEY, at REAL NOT NULL, data TEXT NOT NULL)")
            db.execute("CREATE TABLE IF NOT EXISTS events(id INTEGER PRIMARY KEY,scope TEXT NOT NULL,kind TEXT NOT NULL,key TEXT NOT NULL,at REAL NOT NULL,data TEXT NOT NULL,UNIQUE(scope,kind,key))")
            db.execute("CREATE INDEX IF NOT EXISTS events_scope ON events(scope,at)")
            db.execute("CREATE TABLE IF NOT EXISTS analytics_seen(scope TEXT NOT NULL,key TEXT NOT NULL,first_at REAL NOT NULL,last_at REAL NOT NULL,PRIMARY KEY(scope,key))")
            db.execute("INSERT OR IGNORE INTO analytics_seen SELECT scope,key,at,at FROM events WHERE kind='analytics'")
            db.execute("PRAGMA user_version=1")

    @contextmanager
    def _db(self):
        with closing(sqlite3.connect(self.path, timeout=2)) as db:
            with db:
                yield db

    def record(self, snapshot, queue, now=None):
        now=time.time() if now is None else now
        if snapshot is not None:
            if (type(snapshot) is not dict or type(snapshot.get("zones",[])) is not list
                    or any(type(z) is not dict for z in snapshot.get("zones",[]))
                    or type(snapshot.get("difficulty",{})) is not dict
                    or type(snapshot.get("climate",{})) is not dict
                    or type(snapshot.get("observations",{})) is not dict
                    or type(queue) is not dict or type(queue.get("operations",[])) is not list
                    or any(type(o) is not dict for o in queue.get("operations",[]))):
                raise ValueError("invalid_snapshot")
        with self.lock, self._db() as db:
            if snapshot is not None:
                campaign,epoch=snapshot.get("campaignId"),snapshot.get("worldEpoch")
                if not _uuid(campaign) or not _uuid(epoch): raise ValueError("invalid_snapshot")
                scope=campaign+":"+epoch
                snap=_pick(snapshot,("campaignId","worldEpoch","activeTicks","onlinePlayers","observations","climate","climateEffectsEnabled","difficulty","zones"))
                analytics=admin_analytics.project(queue.get("analytics"))
                # Store the operation archive separately: a large optional analytics payload
                # must not make the basic observer snapshot exceed its byte budget.
                clean_queue=safe_tree({k:v for k,v in queue.items() if k != "analytics"})
                clean_queue["analytics"]={**analytics,"operations":[]}
                payload=json.dumps(dict(snapshot=snap,queue=clean_queue),ensure_ascii=False,allow_nan=False)
                if len(payload)>524288: raise ValueError("snapshot_too_large")
                db.execute("INSERT INTO states VALUES(?,?,?) ON CONFLICT(scope) DO UPDATE SET at=excluded.at,data=excluded.data",(scope,now,payload))
                difficulty=snapshot.get("difficulty") or {}
                operations=(queue or {}).get("operations",[])
                metrics=_pick(difficulty,("pressure","parasiteEvolutionRate","forestPhaseRate","hostileHealth","hostileDamage","parasiteHealth","parasiteDamage"))
                zones=snapshot.get("zones",[])
                metrics.update(players=snapshot.get("onlinePlayers"),zoneValue=sum(z.get("value",0) for z in zones if type(z.get("value")) in (int,float)),
                               completeZones=sum(z.get("complete") is True for z in zones),zones=len(zones),
                               queued=sum(o.get("state")=="QUEUED" for o in operations),
                               active=sum(o.get("state") in {"WAITING","APPLYING"} for o in operations))
                sample=dict(at=now,online=True,tick=snapshot.get("activeTicks"),metrics=safe_tree(metrics))
                for item in analytics["operations"]:
                    self._analytics_upsert(db,scope,item,now)
                for kind,history in (("scenarios",queue.get("history",[])),("observations",snapshot.get("observations",{}).get("history",[]))):
                    if type(history) is not list: continue
                    for item in history[:512]:
                        if type(item) is not dict: continue
                        clean=safe_tree(item)
                        raw=json.dumps(clean,ensure_ascii=False,sort_keys=True,allow_nan=False)
                        if len(raw)>8192: continue
                        identity=item.get("operationId") if kind=="scenarios" else None
                        key=identity if type(identity) is str and 0<len(identity)<=128 else hashlib.sha256(raw.encode("utf-8")).hexdigest()
                        clean["observed_at"]=now
                        if identity:
                            db.execute("INSERT INTO events(scope,kind,key,at,data) VALUES(?,?,?,?,?) ON CONFLICT(scope,kind,key) DO UPDATE SET at=excluded.at,data=excluded.data",(scope,kind,key,now,json.dumps(clean,ensure_ascii=False)))
                        else:
                            db.execute("INSERT OR IGNORE INTO events(scope,kind,key,at,data) VALUES(?,?,?,?,?)",(scope,kind,key,now,json.dumps(clean,ensure_ascii=False)))
            else:
                last=db.execute("SELECT scope FROM states ORDER BY at DESC LIMIT 1").fetchone()
                if not last: return
                scope=last[0];sample=dict(at=now,online=False,metrics={})
            db.execute("INSERT INTO samples(at,scope,data) VALUES(?,?,?)",(now,scope,json.dumps(sample,allow_nan=False)))
            db.execute("DELETE FROM samples WHERE at<?",(now-RETENTION,))
            db.execute("DELETE FROM samples WHERE id IN (SELECT id FROM samples ORDER BY id DESC LIMIT -1 OFFSET ?)",(self.max_samples,))
            db.execute("DELETE FROM states WHERE scope NOT IN (SELECT scope FROM states ORDER BY at DESC LIMIT 16)")
            db.execute("DELETE FROM samples WHERE scope NOT IN (SELECT scope FROM states)")
            db.execute("DELETE FROM events WHERE at<? OR scope NOT IN (SELECT scope FROM states)",(now-RETENTION,))
            db.execute("DELETE FROM events WHERE id IN (SELECT id FROM events WHERE kind='analytics' ORDER BY at DESC,id DESC LIMIT -1 OFFSET ?)",(admin_analytics.MAX_OPERATIONS,))
            db.execute("DELETE FROM events WHERE id IN (SELECT id FROM events WHERE kind!='analytics' ORDER BY id DESC LIMIT -1 OFFSET 10000)")
            self._prune_analytics_seen(db)

    def scopes(self):
        with self.lock, self._db() as db:
            return [dict(id=s,at=a) for s,a in db.execute("SELECT scope,at FROM states ORDER BY at DESC")]

    def _analytics_upsert(self,db,scope,item,now):
        db.execute("INSERT INTO analytics_seen VALUES(?,?,?,?) ON CONFLICT(scope,key) DO UPDATE SET last_at=excluded.last_at",(scope,item["operationId"],now,now))
        first_at=db.execute("SELECT first_at FROM analytics_seen WHERE scope=? AND key=?",(scope,item["operationId"])).fetchone()[0]
        if first_at<now-RETENTION:return
        previous=db.execute("SELECT data FROM events WHERE scope=? AND kind='analytics' AND key=?",(scope,item["operationId"])).fetchone()
        item["observed_at"]=first_at
        item=admin_analytics.merge(json.loads(previous[0]) if previous else None,item)
        db.execute("INSERT INTO events(scope,kind,key,at,data) VALUES(?,'analytics',?,?,?) "
                   "ON CONFLICT(scope,kind,key) DO UPDATE SET data=excluded.data",
                   (scope,item["operationId"],first_at,json.dumps(item,ensure_ascii=False,allow_nan=False)))

    def _prune_analytics_seen(self,db):
        # A bounded first-seen index prevents an unchanged server ring from
        # reintroducing a dossier immediately after its seven-day payload expiry.
        db.execute("DELETE FROM analytics_seen WHERE scope NOT IN (SELECT scope FROM states)")
        db.execute("DELETE FROM analytics_seen WHERE rowid IN (SELECT rowid FROM analytics_seen ORDER BY last_at DESC,rowid DESC LIMIT -1 OFFSET ?)",(admin_analytics.MAX_OPERATIONS,))

    def analytics_operation(self,scope,operation_id):
        with self.lock,self._db() as db:
            row=db.execute("SELECT data FROM events WHERE scope=? AND kind='analytics' AND key=?",(scope,operation_id)).fetchone()
            return json.loads(row[0]) if row else None

    def record_analytics(self,scope,value,now=None):
        item=admin_analytics.operation(value)
        if item is None:raise ValueError("invalid_analytics")
        now=time.time() if now is None else now
        with self.lock,self._db() as db:
            if not db.execute("SELECT 1 FROM states WHERE scope=?",(scope,)).fetchone():raise ValueError("unknown_scope")
            self._analytics_upsert(db,scope,item,now)
            db.execute("DELETE FROM events WHERE kind='analytics' AND at<?",(now-RETENTION,))
            db.execute("DELETE FROM events WHERE id IN (SELECT id FROM events WHERE kind='analytics' ORDER BY at DESC,id DESC LIMIT -1 OFFSET ?)",(admin_analytics.MAX_OPERATIONS,))
            self._prune_analytics_seen(db)
        return self.analytics_operation(scope,item["operationId"])

    def view(self, scope=None, since=0):
        with self.lock, self._db() as db:
            row=db.execute("SELECT scope,at,data FROM states WHERE scope=?",(scope,)).fetchone() if scope else db.execute("SELECT scope,at,data FROM states ORDER BY at DESC LIMIT 1").fetchone()
            if not row: return dict(scope=scope,snapshot=None,queue=None,sampled_at=None,samples=[],archive=dict(scenarios=[],observations=[],analytics=[]),analytics=admin_analytics.view(None,[]))
            s,at,raw=row
            rows=db.execute("SELECT data FROM samples WHERE scope=? AND at>=? ORDER BY at,id",(s,since)).fetchall()
            archive={kind:[json.loads(r[0]) for r in reversed(db.execute("SELECT data FROM events WHERE scope=? AND kind=? ORDER BY at DESC,id DESC LIMIT 1000",(s,kind)).fetchall())] for kind in ("scenarios","observations","analytics")}
            # Mark real discontinuities BEFORE reducing successful measurements.
            samples=[json.loads(r[0]) for r in rows]
            previous=None
            for sample in samples:
                sample["gap_before"] = previous is not None and (sample["at"]-previous["at"]>35 or not previous["online"])
                previous=sample
            if len(samples)>1200:
                stride=math.ceil(len(samples)/400)
                reduced=[]
                for i in range(0,len(samples),stride):
                    bucket=samples[i:i+stride]
                    valid=[x for x in bucket if x["online"] and type(x["metrics"].get("pressure")) in (int,float)]
                    chosen=[bucket[0],bucket[-1]]
                    if valid: chosen.extend([min(valid,key=lambda x:x["metrics"]["pressure"]),max(valid,key=lambda x:x["metrics"]["pressure"])])
                    offline=next((x for x in bucket if not x["online"]),None)
                    if offline: chosen.append(offline)
                    chosen=sorted({x["at"]:dict(x) for x in chosen}.values(),key=lambda x:x["at"])
                    # Never connect across a dropped outage inside a bucket.
                    if any(x["gap_before"] or not x["online"] for x in bucket):
                        for x in chosen: x["gap_before"]=True
                    reduced.extend(chosen)
                samples=reduced
            state=json.loads(raw)
            return dict(scope=s,sampled_at=at,samples=samples,archive=archive,
                        analytics=admin_analytics.view((state.get("queue") or {}).get("analytics"),archive["analytics"]),**state)
