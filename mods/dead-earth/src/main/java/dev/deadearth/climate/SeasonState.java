package dev.deadearth.climate;

/** Read-only hook state; published by the single logical server or a client packet. */
public final class SeasonState {
    private static volatile ClimateCycle.Phase phase=ClimateCycle.Phase.CALM;
    private static volatile double pressure=1;
    private SeasonState() {}
    public static ClimateCycle.Phase phase() { return phase; }
    public static double parasites() { return SeasonPolicy.forPhase(phase).parasites()*pressure; }
    public static double forest() { return SeasonPolicy.forPhase(phase).forest()*pressure; }
    public static void update(ClimateCycle.Phase value,double difficulty) {
        if (value==null || !Double.isFinite(difficulty) || difficulty<.5 || difficulty>2)
            throw new IllegalArgumentException("Invalid seasonal state");
        phase=value;pressure=difficulty;
    }
    public static void reset() { phase=ClimateCycle.Phase.CALM;pressure=1; }
}
