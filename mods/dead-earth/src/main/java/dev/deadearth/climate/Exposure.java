package dev.deadearth.climate;
import static dev.deadearth.config.DeadEarthConfig.*;

/** Signed exposure in seconds; one step per second, independent of rendering. */
public final class Exposure {
    private Exposure() {}
    public static int step(int previous,ClimateCycle.Phase phase,boolean outside,boolean sunny,boolean protectedSuit) {
        int limit=i(EXPOSURE_LIMIT);int value=Math.clamp(previous,-limit,limit);
        if (outside && !protectedSuit) {
            if (phase==ClimateCycle.Phase.HEAT && sunny) return Math.min(limit,value+i(HEAT_GAIN));
            if (phase==ClimateCycle.Phase.ASH_WINTER) return Math.max(-limit,value-i(COLD_GAIN));
        }
        return value>0?Math.max(0,value-i(RECOVERY)):Math.min(0,value+i(RECOVERY));
    }
    /** Damage may be sampled at arbitrary intervals; elapsed time avoids modulo aliasing. */
    public static boolean damageDue(long last,long now,long interval) {
        if(now<0||interval<=0)throw new IllegalArgumentException("Invalid exposure damage clock");
        return last<0||now<last||now-last>=interval;
    }
}
