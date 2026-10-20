package dev.deadearth.legacy.combat;

/**
 * Constant-time xray admission. Acquiring a target and deciding to attack remain native AI rules.
 */
public final class PlayerVisibility {
  private PlayerVisibility() {}

  public static boolean allowed(
      int radius,
      boolean sameWorld,
      boolean observerLoaded,
      boolean targetLoaded,
      boolean observerAlive,
      boolean targetAlive,
      boolean spectator,
      boolean creative,
      boolean allowCreative,
      double distanceSquared) {
    return radius >= 1
        && radius <= 128
        && sameWorld
        && observerLoaded
        && targetLoaded
        && observerAlive
        && targetAlive
        && !spectator
        && (!creative || allowCreative)
        && Double.isFinite(distanceSquared)
        && distanceSquared >= 0
        && distanceSquared <= (double) radius * radius;
  }
}
