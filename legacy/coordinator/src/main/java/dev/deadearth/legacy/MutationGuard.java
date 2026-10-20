package dev.deadearth.legacy;

/**
 * Grace policy recognizes only native enemy call chains; player remodeling always remains
 * available.
 */
public final class MutationGuard {
  public static boolean allow(Object world, Object pos, Object proposedState) {
    if (!dev.deadearth.legacy.defense.LegacyDefense.allowChange(world, pos, proposedState))
      return false;
    LegacyRuntime r = DeadEarthLegacy.runtime;
    if (r == null || !r.onOwnerThread() || r.campaign.activeTicks >= r.settings.bunkerGraceTicks)
      return true;
    if (!r.protectedAt(world, pos)) return true;
    if (dev.deadearth.legacy.combat.RelaySiege.hostileWriteActive()) return false;
    for (StackTraceElement frame : Thread.currentThread().getStackTrace()) {
      String c = frame.getClassName();
      if (c.startsWith("com.dhanantry.scapeandrunparasites.")
          || c.startsWith("funwayguy.epicsiegemod.")) return false;
    }
    return true;
  }
}
