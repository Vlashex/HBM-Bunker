package dev.deadearth.difficulty;
import static dev.deadearth.config.DeadEarthConfig.*;

/** Campaign-wide introduction; derived from saved active ticks, never wall time. */
public final class IntroDifficulty {
    private IntroDifficulty() {}
    public static double pressure(long activeTicks, double adaptive) {
        if (activeTicks < 0 || !Double.isFinite(adaptive) || adaptive < .05 || adaptive > 4)
            throw new IllegalArgumentException("Invalid introductory difficulty input");
        if (!b(INTRO_ENABLED) || i(INTRO_MINUTES) == 0) return adaptive;
        long duration = i(INTRO_MINUTES) * 1200L;
        if (activeTicks <= duration) {
            double remaining = 1 - activeTicks / (double) duration;
            return 1 - (1 - d(INTRO_INITIAL)) * Math.pow(remaining, d(INTRO_EASE_POWER));
        }
        long handoff = i(INTRO_HANDOFF_MINUTES) * 1200L;
        double blend = handoff == 0 ? 1 : Math.min(1, (activeTicks - duration) / (double) handoff);
        return 1 + (adaptive - 1) * blend;
    }
    public static long remaining(long activeTicks) {
        return b(INTRO_ENABLED) ? Math.max(0, i(INTRO_MINUTES) * 1200L - activeTicks) : 0;
    }
}
