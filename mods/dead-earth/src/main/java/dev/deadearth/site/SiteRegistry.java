package dev.deadearth.site;
import static dev.deadearth.config.DeadEarthConfig.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class SiteRegistry {
    public static final int MAX_SITES = 64;
    public enum State { PLACING, VERIFYING, READY, UNCERTAIN }
    public record Site(UUID request, UUID id, int x, int y, int z, long createdTick,
            State state, UUID discoverer, long discoveredTick) {
        public Site {
            Objects.requireNonNull(request); Objects.requireNonNull(id); Objects.requireNonNull(state);
            if (x < -29999984 || x > 29999975 || z < -29999984 || z > 29999975 || y < -63 || y > 314 || createdTick < 0)
                throw new IllegalArgumentException("Invalid relay origin/time");
            if (discoverer == null ? discoveredTick != -1 : state == State.PLACING || discoveredTick < createdTick)
                throw new IllegalArgumentException("Invalid site discovery");
        }
        Site state(State next) { return new Site(request, id, x, y, z, createdTick, next, discoverer, discoveredTick); }
    }
    private final LinkedHashMap<UUID, Site> sites = new LinkedHashMap<>();
    public List<Site> sites() { return List.copyOf(sites.values()); }
    public Optional<Site> byRequest(UUID request) { return Optional.ofNullable(sites.get(request)); }
    public Site reserve(UUID request, int x, int y, int z, long tick) {
        var previous = sites.get(request);
        if (previous != null) {
            if (previous.x() != x || previous.y() != y || previous.z() != z) throw new IllegalArgumentException("Placement request conflict");
            return previous;
        }
        if (sites.size() == MAX_SITES) throw new IllegalStateException("Site capacity reached");
        var site = new Site(request, UUID.randomUUID(), x, y, z, tick, State.PLACING, null, -1);
        for (var other : sites.values()) if (x <= other.x() + 8 && x + 8 >= other.x()
                && z <= other.z() + 8 && z + 8 >= other.z() && y <= other.y() + 5 && y + 5 >= other.y())
            throw new IllegalArgumentException("Registered site footprint overlaps");
        sites.put(request, site); return site;
    }
    public Site complete(UUID id) {
        var site = find(id);
        if (site.state() != State.PLACING) throw new IllegalStateException("Placement cannot be completed from " + site.state());
        return replace(site.state(State.READY));
    }
    public Site uncertain(UUID id) {
        var site = find(id);
        if (site.state() == State.UNCERTAIN) return site;
        return replace(site.state(State.UNCERTAIN));
    }
    public Site verify(UUID id, boolean intact) {
        var site = find(id);
        if (site.state() != State.VERIFYING && site.state() != State.READY) throw new IllegalStateException("Site cannot be validated");
        return replace(site.state(intact ? State.READY : State.UNCERTAIN));
    }
    public List<Site> discover(UUID player, int x, int y, int z, long tick) {
        Objects.requireNonNull(player);
        if (tick < 0) throw new IllegalArgumentException("Invalid discovery time");
        var found = new ArrayList<Site>();
        for (var site : sites()) {
            long dx = (long) x - site.x() - 4, dy = (long) y - site.y() - 1, dz = (long) z - site.z() - 4;
            if (site.state() == State.READY && site.discoverer() == null && tick >= site.createdTick()
                    && Math.abs(dx) <= i(SITE_DISCOVERY_RADIUS) && Math.abs(dy) <= i(SITE_DISCOVERY_RADIUS) && Math.abs(dz) <= i(SITE_DISCOVERY_RADIUS) && dx * dx + dy * dy + dz * dz <= i(SITE_DISCOVERY_RADIUS)*i(SITE_DISCOVERY_RADIUS))
                found.add(replace(new Site(site.request(), site.id(), site.x(), site.y(), site.z(), site.createdTick(),
                        site.state(), player, tick)));
        }
        return List.copyOf(found);
    }
    public static SiteRegistry restore(List<Site> saved) {
        if (saved.size() > MAX_SITES) throw new IllegalArgumentException("Too many sites");
        var registry = new SiteRegistry(); var ids = new java.util.HashSet<UUID>();
        for (var site : saved) {
            if (!ids.add(site.id()) || registry.sites.containsKey(site.request())) throw new IllegalArgumentException("Duplicate site identity");
            registry.sites.put(site.request(), switch (site.state()) {
                case PLACING -> site.state(State.UNCERTAIN);
                case READY -> site.state(State.VERIFYING);
                default -> site;
            });
        }
        return registry;
    }
    private Site find(UUID id) { return sites.values().stream().filter(s -> s.id().equals(id)).findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Unknown site")); }
    private Site replace(Site site) { sites.put(site.request(), site); return site; }
}
