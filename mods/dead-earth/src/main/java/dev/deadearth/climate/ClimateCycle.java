package dev.deadearth.climate;

import java.util.ArrayList;
import java.util.List;

/** Pure climate timeline; its only input is active campaign time. */
public final class ClimateCycle {
    public static final long DEFAULT_PHASE_TICKS = 48000;
    public enum Phase { HEAT, CALM, ASH_WINTER }
    public enum Trend { NONE, WARMING, COOLING }
    public enum NoticeKind { PHASE, FORECAST }
    public record Snapshot(long originTick, Phase phase, Trend trend, long cycle, long phaseOrdinal,
            long enteredTick, long nextTransitionTick, long ticksRemaining,
            Phase nextPhase, Trend nextTrend, long phaseTicks, long observedActiveTick) {}
    public record Window(Phase phase, Trend trend, long startTick, long endTick) {}
    public record Notice(NoticeKind kind, Snapshot snapshot) {}

    private final long originTick;
    private final long phaseTicks;
    private long lastAnnouncedPhase = -1;
    private long lastWarnedPhase = -1;

    public ClimateCycle(long originTick, long phaseTicks) {
        if (originTick < 0 || phaseTicks < 200 || phaseTicks > 1920000 || phaseTicks % 20 != 0)
            throw new IllegalArgumentException("Invalid climate timeline");
        this.originTick = originTick;
        this.phaseTicks = phaseTicks;
    }
    public long originTick() { return originTick; }
    public long phaseTicks() { return phaseTicks; }
    public long lastAnnouncedPhase() { return lastAnnouncedPhase; }
    public long lastWarnedPhase() { return lastWarnedPhase; }

    public Snapshot view(long activeTick) {
        if (activeTick < originTick) throw new IllegalArgumentException("Climate origin is after campaign clock");
        long elapsed = activeTick - originTick;
        long ordinal = elapsed / phaseTicks;
        int slot = (int) (ordinal % 4);
        long entered = Math.addExact(originTick, Math.multiplyExact(ordinal, phaseTicks));
        long next = Math.addExact(entered, phaseTicks);
        return new Snapshot(originTick, phase(slot), trend(slot), ordinal / 4, ordinal, entered, next,
                next - activeTick, phase((slot + 1) % 4), trend((slot + 1) % 4), phaseTicks, activeTick);
    }
    public List<Window> forecast(long activeTick, int count) {
        if (count < 1 || count > 4) throw new IllegalArgumentException("Forecast limit is 1..4");
        long start = view(activeTick).nextTransitionTick();
        var result = new ArrayList<Window>();
        for (int i = 0; i < count; i++) {
            var future = view(start);
            result.add(new Window(future.phase(), future.trend(), start, future.nextTransitionTick()));
            start = future.nextTransitionTick();
        }
        return List.copyOf(result);
    }
    public List<Notice> advance(long activeTick, boolean hasOnlinePlayers) {
        var status = view(activeTick);
        if (status.phaseOrdinal() < lastAnnouncedPhase || status.phaseOrdinal() < lastWarnedPhase)
            throw new IllegalArgumentException("Climate notice clock moved backwards");
        if (!hasOnlinePlayers) return List.of();
        var notices = new ArrayList<Notice>();
        if (status.phaseOrdinal() > lastAnnouncedPhase) {
            lastAnnouncedPhase = status.phaseOrdinal();
            notices.add(new Notice(NoticeKind.PHASE, status));
        }
        if (status.ticksRemaining() <= warningWindow() && status.phaseOrdinal() > lastWarnedPhase) {
            lastWarnedPhase = status.phaseOrdinal();
            notices.add(new Notice(NoticeKind.FORECAST, status));
        }
        return List.copyOf(notices);
    }
    public static ClimateCycle restore(long origin, long duration, long announced, long warned, long now) {
        var result = new ClimateCycle(origin, duration);
        var status = result.view(now);
        long current = status.phaseOrdinal();
        if (announced < -1 || warned < -1 || announced > current || warned > announced
                || (warned == current && status.ticksRemaining() > result.warningWindow()))
            throw new IllegalArgumentException("Invalid saved climate notice markers");
        result.lastAnnouncedPhase = announced;
        result.lastWarnedPhase = warned;
        return result;
    }
    private long warningWindow() { return Math.min(6000, phaseTicks / 4); }
    private static Phase phase(int slot) {
        return switch (slot) { case 0, 2 -> Phase.CALM; case 1 -> Phase.HEAT; case 3 -> Phase.ASH_WINTER;
            default -> throw new IllegalArgumentException("Invalid climate slot"); };
    }
    private static Trend trend(int slot) {
        return switch (slot) { case 0 -> Trend.WARMING; case 2 -> Trend.COOLING; case 1, 3 -> Trend.NONE;
            default -> throw new IllegalArgumentException("Invalid climate slot"); };
    }
}
