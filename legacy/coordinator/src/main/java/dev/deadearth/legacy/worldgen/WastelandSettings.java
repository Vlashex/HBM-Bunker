package dev.deadearth.legacy.worldgen;

import dev.deadearth.legacy.JsonFiles;
import java.nio.file.*;
import java.util.*;

/** Restart-only immutable-by-ownership settings; never changes an existing chunk. */
public final class WastelandSettings {
  public int schema = 1;
  public boolean enabled = true;
  public Band small = new Band(24, .85, 3, 9, 2, 5, 1);
  public Band large = new Band(384, .35, 32, 64, 10, 22, 3);
  public double ashPatches = .28, trunkChance = .35;
  public int trunkMin = 3, trunkMax = 9, coatingDepth = 3, maxWrites = 16384;
  public int waterColor = 0x62613f, waterFogColor = 0x343a29;
  public LinkedHashMap<String, Integer> palette = new LinkedHashMap<String, Integer>();

  public WastelandSettings() {
    palette.put("minecraft:stone#0", 38);
    palette.put("dead_earth:ash#0", 22);
    palette.put("minecraft:cobblestone#0", 15);
    palette.put("minecraft:stone#5", 10);
    palette.put("minecraft:gravel#0", 10);
    palette.put("minecraft:stonebrick#2", 5);
  }

  public static final class Band {
    public int spacing, radiusMin, radiusMax, depthMin, depthMax, rim;
    public double chance;

    public Band() {}

    public Band(int spacing, double chance, int lo, int hi, int dLo, int dHi, int rim) {
      this.spacing = spacing;
      this.chance = chance;
      radiusMin = lo;
      radiusMax = hi;
      depthMin = dLo;
      depthMax = dHi;
      this.rim = rim;
    }

    void validate() {
      if (spacing < 16
          || spacing > 1024
          || !finite(chance)
          || chance < 0
          || chance > 1
          || radiusMin < 2
          || radiusMax < radiusMin
          || radiusMax > 128
          || spacing < 2 * radiusMax
          || depthMin < 1
          || depthMax < depthMin
          || depthMax > 32
          || rim < 0
          || rim > 4) throw new IllegalArgumentException("Invalid wasteland crater band");
    }
  }

  private static boolean finite(double d) {
    return !Double.isNaN(d) && !Double.isInfinite(d);
  }

  public void validate() {
    if (schema != 1 || small == null || large == null)
      throw new IllegalArgumentException("Unsupported wasteland settings");
    small.validate();
    large.validate();
    if (!finite(ashPatches)
        || ashPatches < 0
        || ashPatches > 1
        || !finite(trunkChance)
        || trunkChance < 0
        || trunkChance > 1
        || trunkMin < 2
        || trunkMax < trunkMin
        || trunkMax > 14
        || coatingDepth < 1
        || coatingDepth > 5
        || maxWrites < 1024
        || maxWrites > 32768
        || maxWrites
            < 256
                    * (Math.max(small.depthMax, large.depthMax)
                        + Math.max(small.rim, large.rim)
                        + coatingDepth
                        + 2)
                + 32
        || waterColor < 0
        || waterColor > 0xffffff
        || waterFogColor < 0
        || waterFogColor > 0xffffff
        || palette == null
        || palette.isEmpty()
        || palette.size() > 16)
      throw new IllegalArgumentException("Invalid wasteland settings/budget");
    Set<String> allowed =
        new HashSet<String>(
            Arrays.asList(
                "minecraft:stone#0",
                "minecraft:stone#1",
                "minecraft:stone#3",
                "minecraft:stone#5",
                "dead_earth:ash#0",
                "minecraft:cobblestone#0",
                "minecraft:gravel#0",
                "minecraft:stonebrick#2"));
    int total = 0;
    for (Map.Entry<String, Integer> e : palette.entrySet()) {
      if (!allowed.contains(e.getKey())
          || e.getValue() == null
          || e.getValue() < 0
          || e.getValue() > 1000)
        throw new IllegalArgumentException(
            "Palette must contain only inert legacy stone/ash/rubble");
      total += e.getValue();
    }
    if (total < 1) throw new IllegalArgumentException("Empty wasteland palette");
  }

  public static WastelandSettings load(Path config) {
    Path p = config.resolve("dead-earth/worldgen.json");
    try {
      if (!Files.exists(p)) {
        WastelandSettings defaults = new WastelandSettings();
        defaults.validate();
        JsonFiles.write(p, defaults);
        return defaults;
      }
      WastelandSettings value =
          dev.deadearth.legacy.data.GameDataFiles.load(p, WastelandSettings.class, 32768);
      if (value == null) throw new IllegalArgumentException("Missing wasteland settings");
      value.validate();
      return value;
    } catch (Exception ex) {
      throw new IllegalStateException("Cannot read wasteland settings; file was preserved", ex);
    }
  }
}
