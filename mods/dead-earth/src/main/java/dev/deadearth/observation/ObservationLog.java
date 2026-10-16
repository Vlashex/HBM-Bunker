package dev.deadearth.observation;

import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public final class ObservationLog {
    public static final int MAX_PLAYERS = 64, MAX_REGIONS = 1024, MAX_HISTORY = 512;
    public enum Kind { REGION_DISCOVERED, DEATH, SITE_DISCOVERED }
    public record Region(String dimension, int x, int z) {
        public Region { dimension = dimensionId(dimension); }
    }
    public record PlayerFact(UUID player, String dimension, int regionX, int regionZ, float health, long seenTick) {
        public PlayerFact {
            Objects.requireNonNull(player); dimension = dimensionId(dimension);
            if (!Float.isFinite(health) || health < 0 || seenTick < 0) throw new IllegalArgumentException("Invalid player fact");
        }
    }
    public record Event(long tick, Kind kind, UUID player, String subject) {
        public Event {
            Objects.requireNonNull(kind); Objects.requireNonNull(player); Objects.requireNonNull(subject);
            if (tick < 0 || subject.isBlank() || subject.length() > 128) throw new IllegalArgumentException("Invalid observation");
        }
    }
    public record Snapshot(List<PlayerFact> players, List<Region> regions, List<Event> history) {
        public Snapshot { players = List.copyOf(players); regions = List.copyOf(regions); history = List.copyOf(history); }
    }
    private final LinkedHashMap<UUID, PlayerFact> players = new LinkedHashMap<>();
    private final LinkedHashSet<Region> regions = new LinkedHashSet<>();
    private final ArrayDeque<Event> history = new ArrayDeque<>();

    public boolean sample(UUID player, String dimension, int x, int z, float health, long tick) {
        var fact = new PlayerFact(player, dimension, Math.floorDiv(x, 64), Math.floorDiv(z, 64), health, tick);
        if (!players.containsKey(player) && players.size() >= MAX_PLAYERS) return false;
        var previous = players.get(player);
        if (previous != null && previous.seenTick() > tick) throw new IllegalArgumentException("Observation is older than known fact");
        players.put(player, fact);
        var region = new Region(dimension, fact.regionX(), fact.regionZ());
        if (regions.size() < MAX_REGIONS && regions.add(region))
            record(tick, Kind.REGION_DISCOVERED, player, dimension + "/" + region.x() + "/" + region.z());
        return !fact.equals(previous);
    }
    public void record(long tick, Kind kind, UUID player, String subject) {
        var event = new Event(tick, kind, player, subject);
        if (!history.isEmpty() && history.getLast().tick() > tick) throw new IllegalArgumentException("History time went backwards");
        if (history.size() == MAX_HISTORY) history.removeFirst();
        history.addLast(event);
    }
    public Snapshot snapshot() { return new Snapshot(List.copyOf(players.values()), List.copyOf(regions), List.copyOf(history)); }
    public static ObservationLog restore(Snapshot state) {
        if (state.players().size() > MAX_PLAYERS || state.regions().size() > MAX_REGIONS || state.history().size() > MAX_HISTORY)
            throw new IllegalArgumentException("Observation limit exceeded");
        var log = new ObservationLog();
        for (var player : state.players()) if (log.players.put(player.player(), player) != null)
            throw new IllegalArgumentException("Duplicate player fact");
        for (var region : state.regions()) if (!log.regions.add(region)) throw new IllegalArgumentException("Duplicate region");
        for (var event : state.history()) log.record(event.tick(), event.kind(), event.player(), event.subject());
        return log;
    }
    private static String dimensionId(String value) {
        if (value == null || value.length() > 128 || !value.matches("[a-z0-9_.-]+:[a-z0-9/._-]+"))
            throw new IllegalArgumentException("Invalid dimension identifier");
        return value;
    }
}
