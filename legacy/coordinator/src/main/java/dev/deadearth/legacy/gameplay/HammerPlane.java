package dev.deadearth.legacy.gameplay;

/** Geometry only: a walking tunnel begins at the player's feet, without losing the hit block. */
public final class HammerPlane {
  private HammerPlane() {}

  public static int bottom(int targetY, int radius, double playerY, boolean walkingTunnel) {
    if (!walkingTunnel || radius == 0 || !Double.isFinite(playerY)) return targetY - radius;
    int feet = (int) Math.floor(playerY);
    // Deliberate digging below the feet or far above the tunnel keeps native targeting.
    if (targetY < feet || targetY > feet + 2 * radius) return targetY - radius;
    return feet;
  }
}
