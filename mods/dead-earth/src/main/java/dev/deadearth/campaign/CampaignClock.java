package dev.deadearth.campaign;

/** Active server time; deliberately independent of level dayTime and sleeping. */
public final class CampaignClock {
    private CampaignClock() {}

    public static long advance(long activeTicks, boolean hasOnlinePlayers) {
        if (activeTicks < 0) throw new IllegalArgumentException("Negative campaign time");
        return hasOnlinePlayers && activeTicks < Long.MAX_VALUE ? activeTicks + 1 : activeTicks;
    }
}
