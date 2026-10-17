package dev.deadearth.climate;
import static dev.deadearth.config.DeadEarthConfig.*;

/** Read-only hook state; published by the single logical server or a client packet. */
public final class SeasonState {
    private static volatile ClimateCycle.Phase phase=ClimateCycle.Phase.CALM;
    private static volatile double pressure=1;
    private static volatile boolean active;
    private SeasonState() {}
    public static ClimateCycle.Phase phase() { return phase; }
    public static double pressure() {return active?pressure:1;}
    public static double parasites() { return active?SeasonPolicy.forPhase(phase).parasites()*pressureEffect(pressure,PARASITE_INFLUENCE):1; }
    public static double parasiteEvolution() { return active?parasites()*d(PARASITE_EVOLUTION_BASE):1; }
    public static double forest() { return active?SeasonPolicy.forPhase(phase).forest()*pressureEffect(pressure,FOREST_INFLUENCE):1; }
    public static double health(){return active?pressureEffect(pressure,HEALTH_INFLUENCE):1;}
    public static double damage(){return active?pressureEffect(pressure,DAMAGE_INFLUENCE):1;}
    public static double parasiteHealth(){return active?SeasonPolicy.forPhase(phase).parasites()*health():1;}
    public static double parasiteDamage(){return active?SeasonPolicy.forPhase(phase).parasites()*damage():1;}
    public static void update(ClimateCycle.Phase value,double difficulty) {
        if (value==null || !Double.isFinite(difficulty) || difficulty<.05 || difficulty>4)
            throw new IllegalArgumentException("Invalid seasonal state");
        phase=value;pressure=difficulty;active=true;
    }
    public static void reset() { active=false;phase=ClimateCycle.Phase.CALM;pressure=1; }
}
