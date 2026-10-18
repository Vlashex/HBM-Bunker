"""Data-only, bounded projections for event analytics and council audit metrics."""
import math
import json

MAX_OPERATIONS = 512
MAX_TIMELINE = 128
MAX_INTEGER = 2**53 - 1
MAX_VALUE = 10**12
STATUSES = {"ready", "disabled", "unsupported_save", "unavailable"}


def number(value, maximum=MAX_VALUE, integer=False):
    if type(value) not in (int, float) or (integer and type(value) is not int):
        return None
    return value if 0 <= value <= maximum and math.isfinite(value) else None


def text(value, limit=128):
    return value[:limit].encode("utf-8","replace").decode("utf-8") if type(value) is str else None


def _object(value):
    return value if type(value) is dict else {}


def _enum(value, choices, default=None):
    return value if type(value) is str and value in choices else default


def operation(value):
    value = _object(value)
    identity = value.get("operationId")
    if type(identity) is not str or not 1 <= len(identity) <= 128 or any(ord(c) < 32 or 0xd800 <= ord(c) <= 0xdfff for c in identity):
        return None
    result = {key: text(value.get(key)) for key in ("operationId", "eventId", "zoneId", "state")}
    result.update({key: number(value.get(key), MAX_INTEGER, integer=True)
                   for key in ("createdTick", "expiresTick", "endedTick", "participants")})
    result["participantsTruncated"] = value.get("participantsTruncated") if type(value.get("participantsTruncated")) is bool else None
    result["coverage"] = _enum(value.get("coverage"), {"complete", "partial", "unknown"}, "unknown")
    damage = _object(value.get("damage"))
    result["damage"] = {key: number(damage.get(key), integer=key == "deaths")
                        for key in ("hp", "absorbedHp", "deaths", "ownedAttributedHp", "spatialAttributedHp", "points")}
    result["damage"]["pointsWeights"] = weights(damage.get("pointsWeights"))
    observations = _object(value.get("observations"))
    result["observations"] = {key: number(observations.get(key), integer=True)
                              for key in ("signalEmissions", "tracePlacements", "traceInteractions", "nearEntries", "approaches", "trapEntries", "retreats", "samplePairs")}
    proxy = _object(value.get("proxy"))
    result["proxy"] = {key: _enum(proxy.get(key), {"observed", "not_observed", "insufficient_data"}, "insufficient_data")
                       for key in ("distraction", "deception")}
    result["proxy"].update(score=number(proxy.get("score")), reason=text(proxy.get("reason"), 512))
    timeline = value.get("timeline")
    timeline = timeline if type(timeline) is list else []
    result["timeline"] = []
    for entry in timeline[:MAX_TIMELINE]:
        if type(entry) is not dict:
            continue
        item = {key: text(entry.get(key), 256) for key in ("kind", "role", "detail", "attribution")}
        item.update(seq=number(entry.get("seq"), MAX_INTEGER, integer=True),
                    tick=number(entry.get("tick"), MAX_INTEGER, integer=True),
                    count=number(entry.get("count"), integer=True), hp=number(entry.get("hp")))
        result["timeline"].append(item)
    dropped = number(value.get("droppedTimeline"), MAX_INTEGER, integer=True)
    extra = len(timeline) - len(result["timeline"])
    result["droppedTimeline"] = min(MAX_INTEGER, dropped + extra) if dropped is not None else (extra or None)
    result["wireTruncated"] = value.get("wireTruncated") if type(value.get("wireTruncated")) is bool else None
    result["omittedTimeline"] = number(value.get("omittedTimeline"), MAX_INTEGER, integer=True)
    bounded_record(result)
    return result


def project(value):
    value = _object(value)
    status = _enum(value.get("status"), STATUSES, "unavailable") if type(value.get("schema")) is int and value["schema"] == 1 else "unavailable"
    items = value.get("operations")
    if status == "ready" and type(items) is not list:
        status = "unavailable"
    items = items if type(items) is list and status == "ready" else []
    # A changing operation is one record, even if a server repeats it in one response.
    unique = {}
    invalid = 0
    for item in items[:MAX_OPERATIONS]:
        clean = operation(item)
        if clean is None:
            invalid += 1
        else:
            unique[clean["operationId"]] = clean
    omitted=number(value.get("omittedOperations"),MAX_INTEGER,integer=True)
    return dict(schema=1, status=status, sampleTicks=number(value.get("sampleTicks"), MAX_INTEGER, integer=True),
                maxOperations=number(value.get("maxOperations"), MAX_OPERATIONS, integer=True),
                operations=list(unique.values()), invalidOperations=invalid,
                operationsTruncated=len(items) > MAX_OPERATIONS or value.get("operationsTruncated") is True or (omitted is not None and omitted>0),
                omittedOperations=omitted,
                scoring=weights(value.get("scoring")))


def view(current, operations):
    current = _object(current)
    status = _enum(current.get("status"), STATUSES, "unavailable")
    totals = dict(operations=len(operations))
    for key in ("hp", "absorbedHp", "deaths", "points"):
        values = [o["damage"].get(key) for o in operations]
        totals[key] = sum(values) if (operations or status == "ready") and all(v is not None for v in values) else None
    return dict(schema=1, status=status, sampleTicks=current.get("sampleTicks"),
                maxOperations=current.get("maxOperations"), operations=operations, totals=totals,
                invalidOperations=current.get("invalidOperations", 0),
                omittedOperations=current.get("omittedOperations"),
                operationsTruncated=current.get("operationsTruncated", False),
                scoring=current.get("scoring"),retentionDays=7, archiveLimit=MAX_OPERATIONS)


def weights(value):
    value = _object(value)
    return {key:number(value.get(key),10000) for key in ("hpWeight", "deathWeight", "absorbedWeight")}


def bounded_record(value):
    """A single dossier remains below the transport budget after projection/merging."""
    timeline = value.get("timeline", [])
    while timeline and len(json.dumps(value,ensure_ascii=False,allow_nan=False).encode("utf-8")) > 60000:
        timeline.pop(0)
        value["wireTruncated"] = True
        value["omittedTimeline"] = (value.get("omittedTimeline") or 0) + 1
    return value


def merge(previous, incoming):
    """Summary tails never erase an archived dossier; seq distinguishes equal-tick hits."""
    if previous is None:
        return incoming
    result = dict(incoming)
    old, new = previous.get("timeline", []), incoming.get("timeline", [])
    old_seq=[t["seq"] for t in old if type(t.get("seq")) is int]
    new_seq=[t["seq"] for t in new if type(t.get("seq")) is int]
    if old_seq and new_seq and max(new_seq)<max(old_seq):
        return previous
    if incoming.get("wireTruncated") is True and old:
        if all(type(t.get("seq")) is int for t in old+new):
            combined = {t["seq"]:t for t in old+new}
            timeline = [combined[k] for k in sorted(combined)]
            if previous.get("wireTruncated") is False and new:
                last_old = old[-1]["seq"]
                tail = [t["seq"] for t in new if t["seq"] > last_old]
                if tail == list(range(last_old+1,last_old+1+len(tail))):
                    result.update(wireTruncated=False,omittedTimeline=0)
            if len(timeline)>MAX_TIMELINE:
                removed=len(timeline)-MAX_TIMELINE
                timeline=timeline[-MAX_TIMELINE:]
                result.update(wireTruncated=True,omittedTimeline=(result.get("omittedTimeline") or 0)+removed)
            result["timeline"] = timeline
        elif len(old)>len(new):
            # Pre-sequence rows cannot be safely deduplicated. Keep their known prefix.
            result["timeline"] = old
            result["wireTruncated"] = True
    result["observed_at"] = previous.get("observed_at", incoming.get("observed_at"))
    return bounded_record(result)


def council_metrics(value):
    value = _object(value)
    result = {key: number(value.get(key), integer=key.endswith("tokens"))
              for key in ("elapsed_ms", "remaining_ms", "prompt_tokens", "output_tokens")}
    result.update(ai_mode=_enum(value.get("ai_mode"), {"single", "dual"}),
                  fallback_reason=text(value.get("fallback_reason")),
                  outcome=text(value.get("outcome")),
                  proposal_option=number(value.get("proposal_option"),7,integer=True),
                  final_option=number(value.get("final_option"),7,integer=True),
                  revision_count=number(value.get("revision_count"), 1, integer=True), stages=[])
    stages = value.get("stages")
    for stage in (stages[:3] if type(stages) is list else []):
        if type(stage) is not dict:
            continue
        clean = {key: text(stage.get(key), 128) for key in ("stage", "model", "digest", "status", "error_code")}
        clean.update({key: number(stage.get(key), integer=key in {"option", "prompt_tokens", "output_tokens"})
                      for key in ("option", "elapsed_ms", "remaining_ms", "prompt_tokens", "output_tokens")})
        reasons = stage.get("reason_codes")
        clean["reason_codes"] = [text(r, 80) for r in reasons[:8] if type(r) is str] if type(reasons) is list else []
        result["stages"].append(clean)
    return result


def dashboard_summary(analytics):
    """Keep expensive timelines in SQLite; the browser requests one dossier on demand."""
    items=analytics["operations"]
    result=dict(analytics,operations=[],displayLimit=128,displayedOperations=min(128,len(items)),
                operationsTruncated=analytics.get("operationsTruncated") is True or len(items)>128)
    for item in items[-128:]:
        timeline=item.get("timeline",[])
        summary=dict(item,timeline=[],detailsAvailable=True,
                     lastSeq=max((t.get("seq") for t in timeline if type(t.get("seq")) is int),default=None))
        if timeline:
            summary.update(wireTruncated=True,omittedTimeline=(item.get("omittedTimeline") or 0)+len(timeline))
        result["operations"].append(summary)
    return result
