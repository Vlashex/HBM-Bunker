package dev.deadearth.legacy.combat;

/** Preserves the native baseline and adopts later changes made by another native adapter. */
public final class SightRange {
  private SightRange() {}

  public static double[] update(double[] previous, double current, boolean enabled, int radius) {
    double baseline =
        previous == null || Double.compare(current, previous[1]) != 0 ? current : previous[0];
    return new double[] {baseline, enabled ? Math.max(baseline, radius) : baseline};
  }
}
