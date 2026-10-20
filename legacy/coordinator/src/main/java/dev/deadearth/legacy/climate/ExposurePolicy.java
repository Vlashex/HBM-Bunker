package dev.deadearth.legacy.climate;

/** Campaign time, signed exposure; called once per configured sample, not per rendered frame. */
public final class ExposurePolicy {
  private ExposurePolicy() {}

  public static int step(
      int previous, int season, boolean outside, boolean sunny, boolean suit, ClimateConfig c) {
    int n = Math.max(-c.exposureLimit, Math.min(c.exposureLimit, previous));
    if (outside && !suit) {
      if (season == 2) return Math.max(-c.exposureLimit, n - c.coldGain);
      if (season == 1 && sunny) return Math.min(c.exposureLimit, n + c.heatGain);
    }
    return n > 0 ? Math.max(0, n - c.recovery) : Math.min(0, n + c.recovery);
  }

  public static boolean damageDue(long last, long now, int interval) {
    return last < 0 || now < last || now - last >= interval;
  }
}
