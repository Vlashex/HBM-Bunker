package dev.deadearth.legacy.combat;

import dev.deadearth.legacy.data.GameDataFiles;
import java.nio.file.Path;

/**
 * Bounded block-goal policy. Requires a server restart; no generated targets or model coordinates.
 */
public final class RelaySiegeSettings {
  public int schema = 1;
  public boolean enabled = true;
  public int radius = 64, maximumTrackedGoals = 128, maximumActiveGoals = 16, maximumRelays = 64;
  public int entityVisitsPerTick = 32, tileVisitsPerTick = 32, workStepsPerTick = 8;
  public int repathTicks = 20, workIntervalTicks = 20, damagePerHit = 100, relayHitPoints = 1000;
  public double reach = 3, meleePriorityRadius = 4, navigationSpeed = 1.1;

  public static RelaySiegeSettings load(Path path) {
    try {
      RelaySiegeSettings out = GameDataFiles.load(path, RelaySiegeSettings.class, 16384);
      out.validate();
      return out;
    } catch (Exception invalid) {
      throw new IllegalStateException(
          "Invalid relay siege configuration; original preserved", invalid);
    }
  }

  public void validate() {
    if (schema != 1
        || radius < 8
        || radius > 96
        || maximumTrackedGoals < 1
        || maximumTrackedGoals > 256
        || maximumActiveGoals < 1
        || maximumActiveGoals > 64
        || maximumActiveGoals > maximumTrackedGoals
        || maximumRelays < 1
        || maximumRelays > 128
        || entityVisitsPerTick < 1
        || entityVisitsPerTick > 128
        || tileVisitsPerTick < 1
        || tileVisitsPerTick > 128
        || workStepsPerTick < 1
        || workStepsPerTick > 32
        || repathTicks < 10
        || repathTicks > 200
        || workIntervalTicks < 5
        || workIntervalTicks > 200
        || damagePerHit < 1
        || damagePerHit > 1000
        || relayHitPoints < 1000
        || relayHitPoints > 100000
        || !finite(reach, 1.5, 4)
        || !finite(meleePriorityRadius, 2, 8)
        || !finite(navigationSpeed, .2, 2))
      throw new IllegalArgumentException("Relay siege settings/bounds");
  }

  private static boolean finite(double n, double lo, double hi) {
    return Double.isFinite(n) && n >= lo && n <= hi;
  }
}
