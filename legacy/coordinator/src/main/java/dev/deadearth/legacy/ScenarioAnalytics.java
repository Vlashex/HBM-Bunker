package dev.deadearth.legacy;

import static dev.deadearth.legacy.JsonFiles.map;

import dev.deadearth.legacy.kernel.*;
import java.util.*;

/** Persisted scenario analytics, numeric wire normalization and bounded history pages. */
final class ScenarioAnalytics {
  private final LegacyRuntime runtime;

  ScenarioAnalytics(LegacyRuntime runtime) {
    this.runtime = runtime;
  }

  Map<String, Object> view(String id) {
    return wire(runtime.campaign.analytics.get(id));
  }

  Object page() {
    runtime.requireThread();
    for (CinematicEngine.History h : runtime.engine.history()) {
      Map<String, Object> a = runtime.campaign.analytics.get(h.operationId().toString());
      if (a != null) {
        a.put("state", h.outcome());
        a.put("endedTick", h.tick());
      }
    }
    List<Object> records = new ArrayList<Object>();
    List<Map<String, Object>> allRecords =
        new ArrayList<Map<String, Object>>(runtime.campaign.analytics.values());
    for (int i = Math.max(0, allRecords.size() - 16); i < allRecords.size(); i++) {
      Map<String, Object> record = wire(allRecords.get(i));
      @SuppressWarnings("unchecked")
      List<Object> timeline = (List<Object>) record.get("timeline");
      record.put("omittedTimeline", Math.max(0, timeline.size() - 12));
      record.put("wireTruncated", timeline.size() > 12);
      record.put(
          "timeline",
          new ArrayList<Object>(
              timeline.subList(Math.max(0, timeline.size() - 12), timeline.size())));
      records.add(record);
    }
    return map(
        "operations",
        operationViews(),
        "history",
        runtime.engine.history(),
        "analytics",
        map(
            "schema",
            1,
            "status",
            "ready",
            "sampleTicks",
            runtime.campaign.activeTicks,
            "maxOperations",
            512,
            "operations",
            records,
            "operationsTruncated",
            allRecords.size() > 16,
            "omittedOperations",
            Math.max(0, allRecords.size() - 16),
            "scoring",
            map("hpWeight", 1, "deathWeight", 20, "absorbedWeight", 0)));
  }

  private List<Object> operationViews() {
    List<Object> out = new ArrayList<Object>();
    for (CinematicEngine.Operation op : runtime.engine.operations())
      out.add(
          map(
              "id",
              op.id(),
              "requestId",
              op.requestId(),
              "eventId",
              op.eventId(),
              "zoneId",
              op.zoneId(),
              "state",
              op.state(),
              "node",
              op.node(),
              "cursor",
              op.cursor(),
              "created",
              op.created(),
              "expires",
              op.expires(),
              "budget",
              op.budget(),
              "spent",
              op.spent()));
    return out;
  }

  @SuppressWarnings("unchecked")
  static Map<String, Object> wire(Map<String, Object> input) {
    if (input == null) return null;
    Map<String, Object> out = copyMap(input);
    for (String key :
        Arrays.asList(
            "createdTick",
            "expiresTick",
            "endedTick",
            "participants",
            "droppedTimeline",
            "omittedTimeline"))
      if (out.get(key) != null) out.put(key, ((Number) out.get(key)).longValue());
    Map<String, Object> damage =
        new LinkedHashMap<String, Object>((Map<String, Object>) out.get("damage"));
    damage.put("deaths", ((Number) damage.get("deaths")).longValue());
    out.put("damage", damage);
    Map<String, Object> observations =
        new LinkedHashMap<String, Object>((Map<String, Object>) out.get("observations"));
    for (String key : new ArrayList<String>(observations.keySet()))
      observations.put(key, ((Number) observations.get(key)).longValue());
    out.put("observations", observations);
    List<Object> timeline = new ArrayList<Object>();
    for (Object value : (List<?>) out.get("timeline")) {
      Map<String, Object> row = new LinkedHashMap<String, Object>((Map<String, Object>) value);
      for (String key : Arrays.asList("seq", "tick", "count"))
        row.put(key, ((Number) row.get(key)).longValue());
      timeline.add(row);
    }
    out.put("timeline", timeline);
    return out;
  }

  Map<String, Object> record(String id) {
    Map<String, Object> a = runtime.campaign.analytics.get(id);
    if (a != null) return a;
    CinematicEngine.Operation op = null;
    for (CinematicEngine.Operation value : runtime.engine.operations())
      if (value.id().toString().equals(id)) op = value;
    a =
        map(
            "operationId",
            id,
            "eventId",
            op == null ? "unknown" : op.eventId(),
            "zoneId",
            op == null ? "unknown" : op.zoneId(),
            "state",
            "active",
            "createdTick",
            op == null ? runtime.campaign.activeTicks : op.created(),
            "expiresTick",
            op == null ? runtime.campaign.activeTicks : op.expires(),
            "endedTick",
            null,
            "participants",
            runtime.onlinePlayers(),
            "participantsTruncated",
            false,
            "coverage",
            "partial",
            "damage",
            map(
                "hp",
                0d,
                "absorbedHp",
                0d,
                "deaths",
                0,
                "ownedAttributedHp",
                0d,
                "spatialAttributedHp",
                0d,
                "points",
                0d,
                "pointsWeights",
                map("hpWeight", 1, "deathWeight", 20, "absorbedWeight", 0)),
            "observations",
            map(
                "signalEmissions",
                0,
                "tracePlacements",
                0,
                "traceInteractions",
                0,
                "nearEntries",
                0,
                "approaches",
                0,
                "trapEntries",
                0,
                "retreats",
                0,
                "samplePairs",
                0),
            "proxy",
            map(
                "distraction",
                "insufficient_data",
                "deception",
                "insufficient_data",
                "score",
                0,
                "reason",
                "Behaviour not inferred from damage alone"),
            "timeline",
            new ArrayList<Object>(),
            "droppedTimeline",
            0,
            "wireTruncated",
            false,
            "omittedTimeline",
            0);
    runtime.campaign.analytics.put(id, a);
    while (runtime.campaign.analytics.size() > 512)
      runtime.campaign.analytics.remove(runtime.campaign.analytics.keySet().iterator().next());
    return a;
  }

  @SuppressWarnings("unchecked")
  public void metric(String id, String key, int count) {
    Map<String, Object> obs = (Map<String, Object>) record(id).get("observations");
    obs.put(key, ((Number) obs.get(key)).intValue() + count);
  }

  @SuppressWarnings("unchecked")
  public void timeline(String id, String kind, String role, String detail, int count) {
    Map<String, Object> a = record(id);
    List<Object> rows = (List<Object>) a.get("timeline");
    if (rows.size() >= 128) {
      a.put("droppedTimeline", ((Number) a.get("droppedTimeline")).intValue() + 1);
      return;
    }
    rows.add(
        map(
            "seq",
            rows.size(),
            "tick",
            runtime.campaign.activeTicks,
            "kind",
            kind,
            "role",
            role,
            "detail",
            detail,
            "attribution",
            "spatial",
            "count",
            count,
            "hp",
            0));
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> copyMap(Map<String, Object> source) {
    Map<String, Object> out = new LinkedHashMap<String, Object>();
    for (Map.Entry<String, Object> e : source.entrySet()) out.put(e.getKey(), copy(e.getValue()));
    return out;
  }

  @SuppressWarnings("unchecked")
  private static Object copy(Object source) {
    if (source instanceof Map) return copyMap((Map<String, Object>) source);
    if (source instanceof List) {
      List<Object> out = new ArrayList<Object>();
      for (Object item : (List<?>) source) out.add(copy(item));
      return out;
    }
    return source;
  }
}
