package dev.deadearth.climate;

public record SeasonPolicy(double parasites, double forest) {
    public static SeasonPolicy forPhase(ClimateCycle.Phase phase) {
        return switch(phase) {
            case HEAT -> new SeasonPolicy(.5,1.5);
            case ASH_WINTER -> new SeasonPolicy(1.8,.25);
            case CALM -> new SeasonPolicy(1,1);
        };
    }
    public static double scale(double base,double multiplier) {
        if (!Double.isFinite(base) || !Double.isFinite(multiplier) || multiplier<0)
            throw new IllegalArgumentException("Non-finite seasonal value");
        return base*multiplier;
    }
}
