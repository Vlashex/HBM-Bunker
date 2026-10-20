package dev.deadearth.legacy.worldgen;

import java.util.*;

/** Pure absolute-coordinate field, including negative chunk borders and generation order. */
public final class WastelandField {
  private WastelandField() {}

  public static final class Crater {
    public final int x, z, radius, depth, rim;
    public final boolean large;
    public final long shapeSeed;

    Crater(int x, int z, int r, int d, int rim, boolean large, long seed) {
      this.x = x;
      this.z = z;
      radius = r;
      depth = d;
      this.rim = rim;
      this.large = large;
      shapeSeed = seed;
    }
  }

  public static long hash(long seed, int x, int z, long salt) {
    long v =
        seed
            ^ ((long) x * 0x9e3779b97f4a7c15L)
            ^ Long.rotateLeft((long) z * 0xc2b2ae3d27d4eb4fL, 27)
            ^ salt;
    v = (v ^ (v >>> 30)) * 0xbf58476d1ce4e5b9L;
    v = (v ^ (v >>> 27)) * 0x94d049bb133111ebL;
    return v ^ (v >>> 31);
  }

  public static double unit(long n) {
    return (n >>> 11) * 0x1.0p-53;
  }

  static int between(long n, int min, int max) {
    return min + (int) Math.floor(unit(n) * (max - min + 1));
  }

  public static List<Crater> candidates(long seed, WastelandSettings settings, int cx, int cz) {
    List<Crater> out = new ArrayList<Crater>();
    append(out, seed, settings.small, cx, cz, false);
    append(out, seed, settings.large, cx, cz, true);
    return Collections.unmodifiableList(out);
  }

  private static void append(
      List<Crater> out, long seed, WastelandSettings.Band b, int cx, int cz, boolean large) {
    if (b.chance == 0) return;
    int minX = Math.multiplyExact(cx, 16),
        minZ = Math.multiplyExact(cz, 16),
        reach = (int) Math.ceil(b.radiusMax * 1.2) + 2;
    long salt = large ? 0x4c41524745435241L : 0x534d414c4c435241L;
    for (int gx = Math.floorDiv(minX - reach, b.spacing);
        gx <= Math.floorDiv(minX + 15 + reach, b.spacing);
        gx++)
      for (int gz = Math.floorDiv(minZ - reach, b.spacing);
          gz <= Math.floorDiv(minZ + 15 + reach, b.spacing);
          gz++) {
        long key = hash(seed, gx, gz, salt);
        if (unit(key) >= b.chance) continue;
        out.add(
            new Crater(
                gx * b.spacing + between(hash(key, gx, gz, 1), 0, b.spacing - 1),
                gz * b.spacing + between(hash(key, gx, gz, 2), 0, b.spacing - 1),
                between(hash(key, gx, gz, 3), b.radiusMin, b.radiusMax),
                between(hash(key, gx, gz, 4), b.depthMin, b.depthMax),
                b.rim,
                large,
                key));
      }
  }

  public static int displacement(List<Crater> craters, int x, int z) {
    int cut = 0, rim = 0;
    for (Crater c : craters) {
      double dx = (double) x - c.x,
          dz = (double) z - c.z,
          angle = Math.atan2(dz, dx),
          phase = unit(c.shapeSeed) * Math.PI * 2;
      double rough = 1 + .04 * Math.sin(angle * 3 + phase) + .02 * Math.sin(angle * 7 - phase);
      double distance = Math.hypot(dx, dz) / (c.radius * rough);
      if (distance > 1.2) continue;
      double bowl = distance < 1 ? -c.depth * Math.pow(1 - distance * distance, 1.5) : 0;
      int value = (int) Math.round(bowl + c.rim * Math.max(0, 1 - Math.abs(distance - 1) / .18));
      cut = Math.min(cut, value);
      rim = Math.max(rim, value);
    }
    return cut < 0 ? cut : rim;
  }

  public static int displacement(long seed, WastelandSettings settings, int x, int z) {
    return displacement(
        candidates(seed, settings, Math.floorDiv(x, 16), Math.floorDiv(z, 16)), x, z);
  }

  public static String surface(long seed, WastelandSettings s, int x, int z) {
    Integer ash = s.palette.get("dead_earth:ash#0");
    if (ash != null
        && ash > 0
        && unit(hash(seed, Math.floorDiv(x, 7), Math.floorDiv(z, 7), 0x415348)) < s.ashPatches)
      return "dead_earth:ash#0";
    // Sort keys so JSON object order cannot change the generated field.
    List<String> keys = new ArrayList<String>(s.palette.keySet());
    Collections.sort(keys);
    int total = 0;
    for (Integer v : s.palette.values()) total += v;
    int choice = (int) (unit(hash(seed, x, z, 0x525542424c45L)) * total);
    for (String key : keys) {
      choice -= s.palette.get(key);
      if (choice < 0) return key;
    }
    throw new IllegalStateException("Empty wasteland palette");
  }
}
