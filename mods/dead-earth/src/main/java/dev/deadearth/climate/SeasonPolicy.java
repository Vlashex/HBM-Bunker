package dev.deadearth.climate;
import static dev.deadearth.config.DeadEarthConfig.*;

public record SeasonPolicy(double parasites, double forest) {
    public static SeasonPolicy forPhase(ClimateCycle.Phase phase) {
        return switch(phase) {
            case HEAT -> new SeasonPolicy(d(SUMMER_PARASITES),d(SUMMER_FOREST));
            case ASH_WINTER -> new SeasonPolicy(d(WINTER_PARASITES),d(WINTER_FOREST));
            case CALM -> new SeasonPolicy(d(CALM_PARASITES),d(CALM_FOREST));
        };
    }
    public static double scale(double base,double multiplier) {
        if (!Double.isFinite(base) || !Double.isFinite(multiplier) || multiplier<0)
            throw new IllegalArgumentException("Non-finite seasonal value");
        return base*multiplier;
    }
}
