package dev.deadearth.climate;

/** Signed exposure in seconds; one step per second, independent of rendering. */
public final class Exposure {
    private Exposure() {}
    public static int step(int previous,ClimateCycle.Phase phase,boolean outside,boolean sunny,boolean protectedSuit) {
        int value=Math.clamp(previous,-60,60);
        if (outside && !protectedSuit) {
            if (phase==ClimateCycle.Phase.HEAT && sunny) return Math.min(60,value+1);
            if (phase==ClimateCycle.Phase.ASH_WINTER) return Math.max(-60,value-2);
        }
        return value>0?Math.max(0,value-3):Math.min(0,value+3);
    }
}
