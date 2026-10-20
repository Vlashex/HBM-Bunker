package dev.deadearth.legacy.combat;

/** Pure configuration limits; physical AI behavior is covered by the deferred native session. */
public final class RelaySiegeChecks {
  private static int checks;

  private static void reject(RelaySiegeSettings settings) {
    checks++;
    try {
      settings.validate();
      throw new AssertionError("Unsafe relay settings accepted");
    } catch (IllegalArgumentException expected) {
    }
  }

  public static void main(String[] args) {
    RelaySiegeSettings s = new RelaySiegeSettings();
    s.validate();
    checks++;
    s.radius = 97;
    reject(s);
    s = new RelaySiegeSettings();
    s.maximumActiveGoals = 129;
    reject(s);
    s = new RelaySiegeSettings();
    s.maximumTrackedGoals = 8;
    reject(s);
    s = new RelaySiegeSettings();
    s.workStepsPerTick = 100;
    reject(s);
    s = new RelaySiegeSettings();
    s.repathTicks = 0;
    reject(s);
    s = new RelaySiegeSettings();
    s.meleePriorityRadius = Double.NaN;
    reject(s);
    s = new RelaySiegeSettings();
    s.relayHitPoints = 1;
    reject(s);
    s = new RelaySiegeSettings();
    s.workIntervalTicks = 1;
    reject(s);
    System.out.println("RelaySiegeChecks: " + checks + " passed");
  }
}
