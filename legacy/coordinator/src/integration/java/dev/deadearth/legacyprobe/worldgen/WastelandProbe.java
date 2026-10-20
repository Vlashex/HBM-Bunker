package dev.deadearth.legacyprobe.worldgen;

import dev.deadearth.legacy.JsonFiles;
import dev.deadearth.legacy.Reflect;
import dev.deadearth.legacy.worldgen.LegacyWasteland;
import dev.deadearth.legacy.worldgen.WastelandField;
import dev.deadearth.legacy.worldgen.WastelandSettings;
import dev.deadearth.legacy.worldgen.WastelandSurface;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;

/**
 * Final-stage native generation assertions. The observer reads before the product pass; it never
 * paints a fixture.
 */
public final class WastelandProbe {
  private WastelandProbe() {}

  private static final class Sample {
    final int cx, cz;
    final int[] ground = new int[256];
    final String[] surface = new String[256];
    final Map<Long, String> ores = new LinkedHashMap<Long, String>(),
        architecture = new LinkedHashMap<Long, String>(),
        hbmCells = new LinkedHashMap<Long, String>();
    boolean reserved;
    int site;
    String reservation, planReason;
    int protectedColumns, unsafeColumns;
    final Map<Long, String> planned = new HashMap<Long, String>();

    Sample(int x, int z) {
      cx = x;
      cz = z;
    }
  }

  public static Map<String, Object> verify(Object world, int requestedX, int requestedZ)
      throws Exception {
    require(
        Boolean.getBoolean("deadearth.testControls")
            && Files.exists(Paths.get(".dead-earth-isolated-native-test")),
        "isolated test gate");
    require(
        Reflect.dimension(world) == 0 && !(Boolean) Reflect.get(world, "isRemote/field_72995_K"),
        "server overworld");
    Object ash = Reflect.registry("BLOCKS", "dead_earth:ash");
    require(ash != null, "own ash registered");
    require(
        ash.getClass().getName().equals("dev.deadearth.legacy.worldgen.AshBlock"),
        "inert own ash class");
    final int[][] sites = {
      landSite(world, requestedX, requestedZ),
      landSite(world, requestedX + 384, requestedZ),
      landSite(world, requestedX, requestedZ + 384)
    };
    final int cx = sites[0][0], cz = sites[0][1];
    WastelandSettings settings = (WastelandSettings) Reflect.get(LegacyWasteland.class, "settings");
    long seed = ((Number) Reflect.call(world, "getSeed/func_72905_C")).longValue();
    Map<Long, Sample> originals = new LinkedHashMap<Long, Sample>();
    boolean[] observing = {true};
    Class<?> generator = Reflect.type("net.minecraftforge.fml.common.IWorldGenerator");
    Method reservation =
        Reflect.type("dev.deadearth.legacy.worldgen.HbmFootprints")
            .getDeclaredMethod("reason", Object.class, int.class, int.class);
    reservation.setAccessible(true);
    Constructor<?> terrainFactory =
        Reflect.type("dev.deadearth.legacy.worldgen.LegacyWasteland$NativeTerrain")
            .getDeclaredConstructor(Object.class, int.class, int.class);
    terrainFactory.setAccessible(true);
    Object observer =
        Proxy.newProxyInstance(
            generator.getClassLoader(),
            new Class<?>[] {generator},
            (proxy, method, args) -> {
              if (method.getName().equals("generate")) {
                int x = ((Number) args[1]).intValue(), z = ((Number) args[2]).intValue();
                int site = siteIndex(sites, x, z);
                if (observing[0] && args[3] == world && site >= 0) {
                  Sample sample = snapshot(world, x, z);
                  sample.site = site;
                  sample.reservation = (String) reservation.invoke(null, world, x, z);
                  sample.reserved = sample.reservation != null;
                  // Read-only diagnostic projection uses the actual product adapter and never
                  // applies a write.
                  WastelandSurface.Plan planned =
                      WastelandSurface.plan(
                          (WastelandSurface.Terrain)
                              terrainFactory.newInstance(chunk(world, x, z), x, z),
                          seed,
                          settings,
                          x,
                          z,
                          sample.reserved);
                  sample.planReason = planned.reason;
                  sample.protectedColumns = planned.protectedColumns;
                  sample.unsafeColumns = planned.unsafeColumns;
                  for (WastelandSurface.Write w : planned.writes)
                    sample.planned.put(cellKey(w.x, w.y, w.z), w.state);
                  originals.put(chunkKey(x, z), sample);
                }
                return null;
              }
              if (method.getName().equals("toString")) return "DeadEarthReadOnlyPreWastelandProbe";
              if (method.getName().equals("hashCode")) return System.identityHashCode(proxy);
              if (method.getName().equals("equals")) return proxy == args[0];
              return null;
            });
    // HBM/native generators run first; the product wasteland generator has Integer.MAX_VALUE
    // priority.
    Reflect.call(
        Reflect.type("net.minecraftforge.fml.common.registry.GameRegistry"),
        "registerWorldGenerator",
        observer,
        Integer.MAX_VALUE - 1);
    Map<String, Long> before = LegacyWasteland.diagnostics();
    try {
      for (int[] site : sites)
        for (int dx = 0; dx < 6; dx++)
          for (int dz = 0; dz < 6; dz++)
            Reflect.call(world, "getChunkFromChunkCoords/func_72964_e", site[0] + dx, site[1] + dz);
    } finally {
      observing[0] = false;
    }
    Map<String, Long> after = LegacyWasteland.diagnostics();
    require(!originals.isEmpty(), "native pre-pass terrain was actually observed");
    require(value(after, "generated") > value(before, "generated"), "new chunks ran native pass");
    long writes = value(after, "writes") - value(before, "writes");
    require(
        writes > 0,
        "native wasteland actually changed terrain, not only protected/empty plans; before="
            + before
            + ", after="
            + after);
    int physicalAsh = 0,
        actualCuts = 0,
        vanillaOres = 0,
        hbmOres = 0,
        reservedChunks = 0,
        preservedArchitecture = 0,
        preservedHbmCells = 0;
    int soilColumns = 0,
        paletteSoilColumns = 0,
        nativeCoveredSoilColumns = 0,
        unconvertedSoilColumns = 0,
        reservedSoilColumns = 0,
        reservedPaintedSoilColumns = 0;
    int nativeGeologyColumns = 0, unexpectedSurfaceColumns = 0;
    List<Map<String, Object>> unconvertedDetails = new ArrayList<Map<String, Object>>(),
        nativeGeologyDetails = new ArrayList<Map<String, Object>>();
    int[] paintedBySite = new int[sites.length];
    Set<Long> paintedChunks = new HashSet<Long>(), paintedReservedChunks = new HashSet<Long>();
    for (Sample s : originals.values()) {
      Object chunk = chunk(world, s.cx, s.cz);
      if (s.reserved) reservedChunks++;
      List<WastelandField.Crater> craters = WastelandField.candidates(seed, settings, s.cx, s.cz);
      for (int x = 0; x < 16; x++)
        for (int z = 0; z < 16; z++) {
          int i = x * 16 + z, g = s.ground[i];
          if (g < 1 || !WastelandSurface.natural(s.surface[i])) continue;
          if (WastelandSurface.surfaceSoil(s.surface[i])) {
            soilColumns++;
            if (s.reserved) reservedSoilColumns++;
            int finalGround = ground(chunk, s.cx * 16 + x, s.cz * 16 + z, x, z);
            String finalSurface = state(chunk, s.cx * 16 + x, finalGround, s.cz * 16 + z);
            if (settings.palette.containsKey(finalSurface)) {
              paletteSoilColumns++;
              paintedBySite[s.site]++;
              paintedChunks.add(chunkKey(s.cx, s.cz));
              if (s.reserved) {
                require(
                    finalGround == g,
                    "reserved natural land is repainted without crater excavation or fill");
                reservedPaintedSoilColumns++;
                paintedReservedChunks.add(chunkKey(s.cx, s.cz));
              }
            } else if (finalSurface.equals("minecraft:stone#1")
                || finalSurface.equals("minecraft:stone#3")) {
              // Adjacent vanilla stone veins can replace newly exposed palette stone after this
              // pass.
              // Granite/diorite are inert native rock, not surviving grass/dirt/sand.
              nativeGeologyColumns++;
              if (nativeGeologyDetails.size() < 32)
                nativeGeologyDetails.add(detail(s, x, z, g, finalGround, finalSurface));
            } else if (WastelandSurface.architecture(finalSurface)
                || ore(finalSurface)
                || WastelandSurface.water(finalSurface)) nativeCoveredSoilColumns++;
            else {
              if (WastelandSurface.surfaceSoil(finalSurface)) unconvertedSoilColumns++;
              else unexpectedSurfaceColumns++;
              if (unconvertedDetails.size() < 32)
                unconvertedDetails.add(detail(s, x, z, g, finalGround, finalSurface));
            }
          }
          int delta = WastelandField.displacement(craters, s.cx * 16 + x, s.cz * 16 + z),
              target = Math.max(settings.coatingDepth + 2, Math.min(253, g + delta));
          String actual = state(chunk, s.cx * 16 + x, target, s.cz * 16 + z);
          if (actual.equals("dead_earth:ash#0")) physicalAsh++;
          // Positive evidence: native solid ground became air at the deterministic bowl height,
          // and the newly exposed floor is the exact configured inert material at that coordinate.
          if (!s.reserved
              && delta < 0
              && target < g
              && state(chunk, s.cx * 16 + x, g, s.cz * 16 + z).equals("minecraft:air#0")
              && actual.equals(
                  WastelandField.surface(seed, settings, s.cx * 16 + x, s.cz * 16 + z)))
            actualCuts++;
        }
      for (Map.Entry<Long, String> ore : s.ores.entrySet()) {
        require(
            at(chunk, s, ore.getKey()).equals(ore.getValue()),
            "naturally generated deep ore survives the surface pass: " + ore.getValue());
        if (ore.getValue().startsWith("hbm:")) hbmOres++;
        else vanillaOres++;
      }
      for (Map.Entry<Long, String> cell : s.hbmCells.entrySet()) {
        require(
            at(chunk, s, cell.getKey()).equals(cell.getValue()),
            "every observed HBM cell remains exact: " + cell.getValue());
        preservedHbmCells++;
      }
      for (Map.Entry<Long, String> block : s.architecture.entrySet()) {
        require(
            at(chunk, s, block.getKey()).equals(block.getValue()),
            "actual native HBM architecture survives the surface pass: " + block.getValue());
        preservedArchitecture++;
      }
    }
    // Preserve actionable evidence even when this group throws before PortProbe can attach its
    // result.
    JsonFiles.write(
        Paths.get("worldgen-probe-diagnostics.json"),
        JsonFiles.map(
            "schema",
            1,
            "sites",
            sites,
            "observedPrePassChunks",
            originals.size(),
            "observedNaturalSoilColumns",
            soilColumns,
            "paletteNaturalSoilColumns",
            paletteSoilColumns,
            "unconvertedNaturalSoilColumns",
            unconvertedSoilColumns,
            "unexpectedNaturalSurfaceColumns",
            unexpectedSurfaceColumns,
            "nativeGeologyColumns",
            nativeGeologyColumns,
            "nativeGeologyDetails",
            nativeGeologyDetails,
            "unconvertedDetails",
            unconvertedDetails,
            "observedReservedSoilColumns",
            reservedSoilColumns,
            "reservedPaintedSoilColumns",
            reservedPaintedSoilColumns,
            "counts",
            after));
    require(physicalAsh > 0, "physical own ash exists on naturally generated terrain");
    require(
        actualCuts > 0,
        "physical crater excavation matches the absolute field and pre-pass native surface");
    int paintedSites = 0;
    for (int n : paintedBySite) if (n >= 256) paintedSites++;
    require(
        soilColumns >= 1024 && paintedChunks.size() >= 8 && paintedSites >= 2,
        "positive natural-soil coverage spans multiple independent fresh regions");
    require(
        unconvertedSoilColumns == 0 && unexpectedSurfaceColumns == 0,
        "all observed natural land becomes inert palette/native granite-diorite or actual native"
            + " feature, with zero grass/dirt/sand islands; unconvertedSoil="
            + unconvertedSoilColumns
            + ", unexpectedSurface="
            + unexpectedSurfaceColumns
            + ", observed="
            + soilColumns
            + ", details="
            + unconvertedDetails);
    require(
        reservedSoilColumns > 0 && reservedPaintedSoilColumns > 0,
        "actual native reserved chunks still repaint exposed natural soil");
    require(
        vanillaOres > 0,
        "genuine pre-pass vanilla deep ore coordinates were observed and retained");
    // CE schedules ordinary HBM veins after population. This synchronous observer cannot
    // require callbacks that have not run; every HBM ore actually seen above was still compared.

    Sample tested = originals.values().iterator().next();
    Object chunk = chunk(world, tested.cx, tested.cz);
    long saved = fingerprint(chunk, tested.cx, tested.cz);
    Method generate =
        LegacyWasteland.class.getDeclaredMethod("generate", Object.class, int.class, int.class);
    generate.setAccessible(true);
    generate.invoke(null, world, tested.cx, tested.cz);
    require(
        fingerprint(chunk, tested.cx, tested.cz) == saved,
        "second generation callback cannot repaint a chunk");
    require(
        value(LegacyWasteland.diagnostics(), "existing_skipped") > value(after, "existing_skipped"),
        "one-shot existing guard observed");
    Map<String, Object> report = new LinkedHashMap<String, Object>();
    report.put("nativePass", true);
    report.put("inertAsh", true);
    report.put("physicalAshColumns", physicalAsh);
    report.put("actualDeterministicCuts", actualCuts);
    report.put("selectedChunkX", cx);
    report.put("selectedChunkZ", cz);
    report.put("landSearchBiomeOnly", true);
    report.put("nativeWrites", writes);
    report.put("observedPrePassChunks", originals.size());
    report.put("preservedVanillaOreSamples", vanillaOres);
    report.put("preservedHbmOreSamples", hbmOres);
    report.put("nativeVanillaOrePreservationTested", true);
    report.put("nativeHbmOrePreservationTested", hbmOres > 0);
    report.put("deferredCallbacksNotObserved", hbmOres == 0);
    report.put("deferredHbmOreGenerationTested", false);
    report.put("observedNativeReservationChunks", reservedChunks);
    report.put("preservedNativeHbmArchitectureSamples", preservedArchitecture);
    report.put("nativeHbmArchitecturePreservationTested", preservedArchitecture > 0);
    report.put("nativeReservationObserved", reservedChunks > 0);
    report.put("sampledIndependentRegions", sites.length);
    report.put("regionsWithAtLeast256PaletteSoilColumns", paintedSites);
    report.put("paintedSoilChunks", paintedChunks.size());
    report.put("observedNaturalSoilColumns", soilColumns);
    report.put("paletteNaturalSoilColumns", paletteSoilColumns);
    report.put("nativeFeatureCoveredSoilColumns", nativeCoveredSoilColumns);
    report.put("nativeGeologyColumns", nativeGeologyColumns);
    report.put("nativeGeologyDetails", nativeGeologyDetails);
    report.put("unexpectedNaturalSurfaceColumns", unexpectedSurfaceColumns);
    report.put("unconvertedNaturalSoilColumns", unconvertedSoilColumns);
    report.put("observedReservedSoilColumns", reservedSoilColumns);
    report.put("reservedPaintedSoilColumns", reservedPaintedSoilColumns);
    report.put("reservedPaintedChunks", paintedReservedChunks.size());
    report.put("protectedNativeHbmCells", preservedHbmCells);
    report.put("nativeChunkLoadLifecycle", loadLifecycle(world, sites, generate, tested));
    report.put("existingChunkPreserved", true);
    report.put("counts", LegacyWasteland.diagnostics());
    return report;
  }

  /**
   * Real Forge load event objects exercise the production listener; never posts to unrelated mods.
   */
  @SuppressWarnings("unchecked")
  private static Map<String, Object> loadLifecycle(
      Object world, int[][] sites, Method generate, Sample existing) throws Exception {
    Map<Object, Integer> marks =
        (Map<Object, Integer>) Reflect.get(LegacyWasteland.class, "completed");
    Object listener = Reflect.get(LegacyWasteland.class, "LISTENER");
    Object old = chunk(world, existing.cx, existing.cz);
    long preserved = fingerprint(old, existing.cx, existing.cz);
    loadEvent(listener, old, 0, Boolean.TRUE);
    generate.invoke(null, world, existing.cx, existing.cz);
    require(
        fingerprint(old, existing.cx, existing.cz) == preserved,
        "populated unmarked save never gets retrogen");
    saveReload(listener, old, marks, 2);
    generate.invoke(null, world, existing.cx, existing.cz);
    require(
        fingerprint(old, existing.cx, existing.cz) == preserved,
        "populated-save protection survives save/reload with false population flag");
    loadEvent(listener, old, 0, null);
    require(marks.containsKey(old), "unknown legacy population data stays protected");
    saveReload(listener, old, marks, 2);
    generate.invoke(null, world, existing.cx, existing.cz);
    require(
        fingerprint(old, existing.cx, existing.cz) == preserved,
        "unknown population protection survives save/reload");
    loadEvent(listener, old, 7, Boolean.FALSE);
    saveReload(listener, old, marks, 7);
    generate.invoke(null, world, existing.cx, existing.cz);
    require(
        fingerprint(old, existing.cx, existing.cz) == preserved,
        "unknown future marker remains exact and protected across save/reload");
    loadEvent(listener, old, 1, Boolean.FALSE);
    require(
        Integer.valueOf(1).equals(marks.get(old)),
        "already painted but unpopulated save is not painted twice");
    saveReload(listener, old, marks, 1);
    Object fresh = null;
    int fx = 0, fz = 0;
    outer:
    for (int[] site : sites)
      for (int dx = 0; dx < 6; dx++)
        for (int dz = 0; dz < 6; dz++) {
          if (dx < 5 && dz < 5) continue;
          Object candidate = chunk(world, site[0] + dx, site[1] + dz);
          if (!marks.containsKey(candidate)) {
            fresh = candidate;
            fx = site[0] + dx;
            fz = site[1] + dz;
            break outer;
          }
        }
    require(fresh != null, "real loaded native unpopulated edge exists");
    loadEvent(listener, fresh, 0, Boolean.FALSE);
    require(
        !marks.containsKey(fresh), "saved unpopulated edge remains eligible for FIRST population");
    long before = value(LegacyWasteland.diagnostics(), "generated");
    generate.invoke(null, world, fx, fz);
    require(
        value(LegacyWasteland.diagnostics(), "generated") == before + 1
            && Integer.valueOf(1).equals(marks.get(fresh)),
        "first generation callback after load is actually executed once");
    long now = fingerprint(fresh, fx, fz);
    generate.invoke(null, world, fx, fz);
    require(now == fingerprint(fresh, fx, fz), "repeated callback cannot run a second crater pass");
    return JsonFiles.map(
        "nativeForgeLoadEvents",
        true,
        "unpopulatedFirstPass",
        true,
        "populatedSaveProtected",
        true,
        "unknownSaveProtected",
        true,
        "paintedUnpopulatedProtected",
        true,
        "unknownMarkerSaveReload",
        true,
        "legacyProtectionSaveReload",
        true,
        "noDuplicatePass",
        true);
  }

  private static void saveReload(
      Object listener, Object chunk, Map<Object, Integer> marks, int expected) {
    Object data = Reflect.make("net.minecraft.nbt.NBTTagCompound"),
        level = Reflect.make("net.minecraft.nbt.NBTTagCompound");
    Reflect.call(level, "setBoolean/func_74757_a", "TerrainPopulated", false);
    Reflect.call(data, "setTag/func_74782_a", "Level", level);
    Reflect.call(
        listener,
        "event",
        Reflect.make("net.minecraftforge.event.world.ChunkDataEvent$Save", chunk, data));
    require(
        ((Number) Reflect.call(data, "getInteger/func_74762_e", "DeadEarthWasteland")).intValue()
            == expected,
        "save persists painted/protected/unknown marker exactly");
    marks.remove(chunk);
    Reflect.call(
        listener,
        "event",
        Reflect.make("net.minecraftforge.event.world.ChunkDataEvent$Load", chunk, data));
    require(
        Integer.valueOf(expected).equals(marks.get(chunk)),
        "reload reconstructs durable marker protection");
  }

  private static void loadEvent(Object listener, Object chunk, int marker, Boolean populated) {
    Object data = Reflect.make("net.minecraft.nbt.NBTTagCompound"),
        level = Reflect.make("net.minecraft.nbt.NBTTagCompound");
    Reflect.call(data, "setInteger/func_74768_a", "DeadEarthWasteland", marker);
    if (populated != null)
      Reflect.call(level, "setBoolean/func_74757_a", "TerrainPopulated", populated.booleanValue());
    Reflect.call(data, "setTag/func_74782_a", "Level", level);
    Object event = Reflect.make("net.minecraftforge.event.world.ChunkDataEvent$Load", chunk, data);
    Reflect.call(listener, "event", event);
  }

  private static Map<String, Object> detail(
      Sample s, int x, int z, int originalGround, int finalGround, String finalSurface) {
    return JsonFiles.map(
        "x",
        s.cx * 16 + x,
        "z",
        s.cz * 16 + z,
        "chunkX",
        s.cx,
        "chunkZ",
        s.cz,
        "site",
        s.site,
        "originalY",
        originalGround,
        "originalSurface",
        s.surface[x * 16 + z],
        "finalY",
        finalGround,
        "finalSurface",
        finalSurface,
        "reservation",
        s.reservation,
        "planReason",
        s.planReason,
        "protectedColumns",
        s.protectedColumns,
        "unsafeColumns",
        s.unsafeColumns,
        "plannedStateAtFinalGround",
        s.planned.get(cellKey(x, finalGround, z)));
  }

  private static int siteIndex(int[][] sites, int x, int z) {
    for (int i = 0; i < sites.length; i++)
      if (x >= sites[i][0] && x < sites[i][0] + 5 && z >= sites[i][1] && z < sites[i][1] + 5)
        return i;
    return -1;
  }

  private static int ground(Object chunk, int absoluteX, int absoluteZ, int localX, int localZ) {
    int y =
        Math.min(
            254,
            ((Number) Reflect.call(chunk, "getHeightValue/func_76611_b", localX, localZ)).intValue()
                + 1);
    while (y > 0 && groundCover(state(chunk, absoluteX, y, absoluteZ))) y--;
    return Math.max(0, y);
  }

  /** Query only the biome provider before loading any terrain; an ocean is not a crater fixture. */
  private static int[] landSite(Object world, int cx, int cz) {
    Object biomes = Reflect.call(world, "getBiomeProvider/func_72959_q"),
        provider = Reflect.call(world, "getChunkProvider/func_72863_F");
    int[][] directions = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {-1, 1}, {1, -1}, {-1, -1}};
    for (int attempt = 0; attempt < 33; attempt++) {
      int ring = attempt == 0 ? 0 : 1 + (attempt - 1) / 8,
          dx = attempt == 0 ? 0 : directions[(attempt - 1) % 8][0] * ring * 32,
          dz = attempt == 0 ? 0 : directions[(attempt - 1) % 8][1] * ring * 32;
      int x = cx + dx, z = cz + dz;
      boolean land = true;
      for (int xx = 0; xx < 5 && land; xx++)
        for (int zz = 0; zz < 5; zz++) {
          Object biome =
              Reflect.call(
                  biomes,
                  "getBiome/func_180631_a",
                  Reflect.pos((x + xx) * 16 + 8, 64, (z + zz) * 16 + 8));
          float elevation =
              ((Number) Reflect.call(biome, "getBaseHeight/func_185355_j")).floatValue();
          if (elevation < .1f) {
            land = false;
            break;
          }
        }
      if (!land) continue;
      for (int xx = 0; xx < 6 && land; xx++)
        for (int zz = 0; zz < 6; zz++)
          if (Reflect.call(provider, "getLoadedChunk/func_186028_c", x + xx, z + zz) != null) {
            land = false;
            break;
          }
      if (land) return new int[] {x, z};
    }
    throw new AssertionError(
        "No fresh land-biome square within bounded 33-site search; terrain was not generated during"
            + " search");
  }

  private static Sample snapshot(Object world, int cx, int cz) {
    Sample s = new Sample(cx, cz);
    Object chunk = chunk(world, cx, cz);
    for (int x = 0; x < 16; x++)
      for (int z = 0; z < 16; z++) {
        int
            top =
                Math.min(
                    254,
                    ((Number) Reflect.call(chunk, "getHeightValue/func_76611_b", x, z)).intValue()
                        + 1),
            g = top;
        while (g > 0 && groundCover(state(chunk, cx * 16 + x, g, cz * 16 + z))) g--;
        int i = x * 16 + z;
        s.ground[i] = g;
        s.surface[i] = state(chunk, cx * 16 + x, Math.max(0, g), cz * 16 + z);
        // Sparse deep samples are below the largest allowed affected band of this shipped config.
        if ((x & 1) == 0 && (z & 1) == 0)
          for (int y = 4; y <= Math.min(48, g - 32); y += 2) {
            String old = state(chunk, cx * 16 + x, y, cz * 16 + z);
            if (ore(old)) s.ores.put(cellKey(x, y, z), old);
          }
        for (int y = Math.max(1, g - 28); y <= top; y++) {
          String old = state(chunk, cx * 16 + x, y, cz * 16 + z);
          if (old.startsWith("hbm:")) {
            s.hbmCells.put(cellKey(x, y, z), old);
            if (WastelandSurface.architecture(old)) s.architecture.put(cellKey(x, y, z), old);
          }
        }
      }
    return s;
  }

  private static boolean groundCover(String state) {
    String id = WastelandSurface.id(state);
    return id.equals("minecraft:air")
        || id.equals("minecraft:tallgrass")
        || id.equals("minecraft:double_plant")
        || id.equals("minecraft:deadbush")
        || id.equals("minecraft:snow_layer")
        || id.equals("minecraft:yellow_flower")
        || id.equals("minecraft:red_flower")
        || id.equals("minecraft:reeds")
        || id.equals("minecraft:waterlily")
        || id.equals("minecraft:vine")
        || id.equals("minecraft:cactus")
        || id.equals("minecraft:sapling")
        || id.equals("minecraft:pumpkin")
        || id.equals("minecraft:melon_block")
        || id.equals("minecraft:leaves")
        || id.equals("minecraft:leaves2")
        || id.equals("minecraft:log")
        || id.equals("minecraft:log2")
        || id.equals("minecraft:red_mushroom_block")
        || id.equals("minecraft:brown_mushroom_block")
        || id.equals("minecraft:brown_mushroom")
        || id.equals("minecraft:red_mushroom")
        || id.equals("hbm:plant_flower")
        || id.equals("hbm:plant_tall")
        || id.equals("hbm:plant_dead")
        || id.equals("hbm:reeds");
  }

  private static boolean ore(String state) {
    String id = WastelandSurface.id(state);
    return id.startsWith("hbm:ore_")
        || id.equals("minecraft:coal_ore")
        || id.equals("minecraft:iron_ore")
        || id.equals("minecraft:gold_ore")
        || id.equals("minecraft:redstone_ore")
        || id.equals("minecraft:lapis_ore")
        || id.equals("minecraft:diamond_ore")
        || id.equals("minecraft:emerald_ore");
  }

  private static long chunkKey(int x, int z) {
    return (x & 0xffffffffL) | ((long) z << 32);
  }

  private static long cellKey(int x, int y, int z) {
    return ((long) x << 12) | ((long) z << 8) | y;
  }

  private static Object chunk(Object world, int x, int z) {
    Object provider = Reflect.call(world, "getChunkProvider/func_72863_F"),
        chunk = Reflect.call(provider, "getLoadedChunk/func_186028_c", x, z);
    if (chunk == null) throw new AssertionError("Probe must read only an already loaded chunk");
    return chunk;
  }

  private static String at(Object chunk, Sample s, long key) {
    return state(
        chunk,
        s.cx * 16 + (int) (key >> 12),
        (int) (key & 255),
        s.cz * 16 + (int) ((key >> 8) & 15));
  }

  private static String state(Object chunk, int x, int y, int z) {
    Object state = Reflect.call(chunk, "getBlockState/func_177435_g", Reflect.pos(x, y, z)),
        block = Reflect.call(state, "getBlock/func_177230_c");
    return Reflect.call(block, "getRegistryName").toString()
        + "#"
        + Reflect.call(block, "getMetaFromState/func_176201_c", state);
  }

  private static long value(Map<String, Long> m, String k) {
    Long v = m.get(k);
    return v == null ? 0 : v;
  }

  private static long fingerprint(Object chunk, int cx, int cz) {
    long h = 0x415348;
    for (int x = 0; x < 16; x++)
      for (int z = 0; z < 16; z++)
        for (int y = 1; y < 255; y++)
          h = 31 * h + state(chunk, cx * 16 + x, y, cz * 16 + z).hashCode();
    return h;
  }

  private static void require(boolean value, String why) {
    if (!value) throw new AssertionError(why);
  }
}
