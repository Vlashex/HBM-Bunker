package dev.deadearth.legacy.worldgen;

import dev.deadearth.legacy.Reflect;
import java.util.*;

/** Exact CE2.6.1.0 read-only phased reservation adapter; never advances HBM or loads chunks. */
final class HbmFootprints {
  private HbmFootprints() {}

  private static final int MAX_JOBS = 4096, MAX_PENDING_FEATURES = 131072;
  private static final Set<Object> generators =
      Collections.newSetFromMap(new WeakHashMap<Object, Boolean>());

  static void track(Object gen) {
    if (gen.getClass().getName().startsWith("com.hbm.")
        && Reflect.is(gen, "net.minecraft.world.gen.structure.MapGenStructure"))
      generators.add(gen);
  }

  private static String nativeBox(Object world, int cx, int cz, int minimumAffectedY) {
    int visited = 0;
    for (Object gen : generators) {
      if (Reflect.get(gen, "world/field_75039_c") != world) continue;
      Map<?, ?> map = (Map<?, ?>) Reflect.get(gen, "structureMap/field_75053_d");
      for (Object start : map.values()) {
        if (++visited > MAX_JOBS) return "native_scan_limit";
        Object box = Reflect.call(start, "getBoundingBox/func_75071_a");
        if (box == null) continue;
        int maxY = ((Number) Reflect.get(box, "maxY/field_78894_e")).intValue();
        if (maxY < minimumAffectedY) continue;
        int minX = ((Number) Reflect.get(box, "minX/field_78897_a")).intValue(),
            maxX = ((Number) Reflect.get(box, "maxX/field_78893_d")).intValue();
        int minZ = ((Number) Reflect.get(box, "minZ/field_78896_c")).intValue(),
            maxZ = ((Number) Reflect.get(box, "maxZ/field_78892_f")).intValue();
        if (cx * 16 <= maxX && cx * 16 + 15 >= minX && cz * 16 <= maxZ && cz * 16 + 15 >= minZ)
          return "native_structure_box";
      }
    }
    return null;
  }

  private static long key(int x, int z) {
    return ((long) x & 0xffffffffL) | ((long) z & 0xffffffffL) << 32;
  }

  /**
   * These exact CE classes decorate natural terrain; they do not describe building foundations. Ore
   * generation remains scheduled natively. Dedicated deposits and all unknown classes stay
   * protected.
   */
  static boolean reservesStructure(String generatorClass) {
    return !generatorClass.equals("com.hbm.world.feature.WorldGenMinableNonCascade")
        && !generatorClass.equals("com.hbm.world.feature.NTMFlowers")
        && !generatorClass.equals("com.hbm.world.feature.PlantReeds");
  }

  /** Exact write ceilings from CE 2.6.1.0; unknown generators cannot gain a vertical exemption. */
  static int maximumDeepWriteY(String type, int originY, int size) {
    if (type.equals("com.hbm.world.feature.BedrockOre")) return 6; // Absolute Y0 ore; filler Y1..6.
    if (type.equals("com.hbm.world.feature.DepthDeposit") && size >= 0)
      return (int)
          Math.min(126L, (long) originY + size); // generateSphere clips every write to Y1..126.
    return Integer.MAX_VALUE;
  }

  private static Object taskStructure(Object job) {
    String name = job.getClass().getName();
    if (name.endsWith("$PendingValidationJob")) {
      Object pending = Reflect.get(job, "pending");
      return pending == null ? null : Reflect.get(pending, "structure");
    }
    return name.endsWith("$PendingDynamicStructure") ? Reflect.get(job, "structure") : null;
  }

  private static String taskType(Object job) {
    Object structure = taskStructure(job);
    return structure == null ? job.getClass().getName() : structure.getClass().getName();
  }

  private static boolean nonSurfaceTask(Object job, int minimumAffectedY) {
    Object structure = taskStructure(job);
    if (structure == null) return false;
    String type = structure.getClass().getName();
    if (!reservesStructure(type)) return true;
    if (type.equals("com.hbm.world.feature.BedrockOre"))
      return maximumDeepWriteY(type, 0, 0) < minimumAffectedY;
    if (!type.equals("com.hbm.world.feature.DepthDeposit")) return false;
    Object originOwner =
        job.getClass().getName().endsWith("$PendingValidationJob")
            ? Reflect.get(job, "pending")
            : job;
    long origin = ((Number) Reflect.get(originOwner, "origin")).longValue();
    int originY =
        ((Number) Reflect.call(Reflect.type("com.hbm.lib.Library"), "getBlockPosY", origin))
            .intValue();
    int size = ((Number) Reflect.get(structure, "size")).intValue();
    // This exact class inherits the empty height-point list: validation retains its supplied Y.
    return maximumDeepWriteY(type, originY, size) < minimumAffectedY;
  }

  static boolean reserved(Object world, int cx, int cz) {
    return reason(world, cx, cz) != null;
  }

  /** Read-only explanation for isolated probes; null means no native reservation. */
  static String reason(Object world, int cx, int cz) {
    return reason(world, cx, cz, LegacyWasteland.minimumAffectedY(world, cx, cz));
  }

  @SuppressWarnings("unchecked")
  static String reason(Object world, int cx, int cz, int minimumAffectedY) {
    // The pinned jar is a required dependency. An unknown layout is an error, not permission to cut
    // structures.
    String nativeReason = nativeBox(world, cx, cz, minimumAffectedY);
    if (nativeReason != null) return nativeReason;
    Map<Object, Object> states =
        (Map<Object, Object>)
            Reflect.get(Reflect.type("com.hbm.world.phased.PhasedEventHandler"), "STATES");
    Object state = states.get(Integer.valueOf(Reflect.dimension(world)));
    if (state == null) return null;
    long chunk = key(cx, cz);
    Map<?, ?> components = (Map<?, ?>) Reflect.get(state, "componentsByChunk");
    Object tasks = components.get(Long.valueOf(chunk));
    if (tasks instanceof Collection && !((Collection<?>) tasks).isEmpty())
      return "native_index_componentsByChunk";
    // Both indexes also contain EVERY ordinary ore vein and plant decoration in CE.
    // An index entry by itself is therefore not evidence of a building footprint.
    for (String field : Arrays.asList("jobsByOriginChunk", "waitingJobs")) {
      Map<?, ?> map = (Map<?, ?>) Reflect.get(state, field);
      Object list = map.get(Long.valueOf(chunk));
      if (list instanceof Collection)
        for (Object job : (Collection<?>) list)
          if (!nonSurfaceTask(job, minimumAffectedY))
            return "native_index_" + field + ":" + taskType(job);
    }
    Map<?, ?> starts = (Map<?, ?>) Reflect.get(state, "structureMap");
    int visited = 0;
    for (Object bucket : starts.values())
      for (Object start : (Collection<?>) bucket) {
        if (++visited > MAX_JOBS) return "phased_scan_limit";
        int minX = ((Number) Reflect.get(start, "minX")).intValue(),
            maxX = ((Number) Reflect.get(start, "maxX")).intValue();
        int minZ = ((Number) Reflect.get(start, "minZ")).intValue(),
            maxZ = ((Number) Reflect.get(start, "maxZ")).intValue();
        if (cx * 16 <= maxX && cx * 16 + 15 >= minX && cz * 16 <= maxZ && cz * 16 + 15 >= minZ)
          return "phased_structure_box";
      }
    Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<Object, Boolean>());
    Map<?, ?> waits = (Map<?, ?>) Reflect.get(state, "waitingJobs");
    for (Object bucket : waits.values())
      for (Object job : (Collection<?>) bucket) {
        if (!seen.add(job)) continue;
        if (seen.size() > MAX_PENDING_FEATURES) return "native_pending_feature_limit";
        if (nonSurfaceTask(job, minimumAffectedY))
          continue; // Natural/deep backlogs cannot consume the surface-building budget.
        if (++visited > MAX_JOBS) return "phased_scan_limit";
        String name = job.getClass().getName();
        if (name.endsWith("$PendingValidationJob")) {
          Object pending = Reflect.get(job, "pending");
          if (pending == null) continue;
          long origin = ((Number) Reflect.get(pending, "origin")).longValue();
          int ox =
              ((Number) Reflect.call(Reflect.type("com.hbm.lib.Library"), "getBlockPosX", origin))
                      .intValue()
                  >> 4;
          int oz =
              ((Number) Reflect.call(Reflect.type("com.hbm.lib.Library"), "getBlockPosZ", origin))
                      .intValue()
                  >> 4;
          Map<?, ?> layout = (Map<?, ?>) Reflect.get(pending, "layout");
          if (layout.containsKey(Long.valueOf(key(cx - ox, cz - oz))))
            return "pending_validation_layout:" + taskType(job);
          if (cx == ox && cz == oz) return "pending_origin:" + taskType(job);
        } else if (name.endsWith("$PendingDynamicStructure")) {
          long origin = ((Number) Reflect.get(job, "origin")).longValue();
          int ox =
              ((Number) Reflect.call(Reflect.type("com.hbm.lib.Library"), "getBlockPosX", origin))
                      .intValue()
                  >> 4;
          int oz =
              ((Number) Reflect.call(Reflect.type("com.hbm.lib.Library"), "getBlockPosZ", origin))
                      .intValue()
                  >> 4;
          if (cx == ox && cz == oz) return "pending_origin:" + taskType(job);
          Object watched = Reflect.get(job, "watchedOffsets");
          // CE evaluate() decodes each relative offset, then adds its origin chunk.
          if (watched instanceof Collection
              && ((Collection<?>) watched).contains(Long.valueOf(key(cx - ox, cz - oz))))
            return "pending_dynamic_layout:" + taskType(job);
        } else
          return "unknown_wait_job:"
              + name; // Keep unknown native layouts fail-closed and diagnosable.
      }
    return null;
  }
}
