package dev.deadearth.observation;

import static dev.deadearth.campaign.NbtFields.*;
import dev.deadearth.observation.ObservationLog.*;
import java.util.ArrayList;
import java.util.UUID;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.saveddata.SavedData;

public final class ObservationStore extends SavedData {
    public static final String NAME = "dead_earth_observations";
    private UUID campaign;
    private ObservationLog log;
    private CompoundTag unsupported;
    private ObservationStore() {}
    public static ObservationStore create(UUID campaign) {
        var store = new ObservationStore(); store.campaign = campaign; store.log = new ObservationLog(); store.setDirty(); return store;
    }
    public static ObservationStore load(UUID campaign, CompoundTag tag, HolderLookup.Provider registries) {
        var store = new ObservationStore();
        try {
            if (integer(tag, "schemaVersion") != 1 || !UUID.fromString(text(tag, "campaignId")).equals(campaign))
                throw new IllegalArgumentException("Unsupported observation campaign/schema");
            var players = new ArrayList<PlayerFact>();
            for (var raw : rows(tag, "players", ObservationLog.MAX_PLAYERS)) {
                var row = (CompoundTag) raw; require(row, "health", Tag.TAG_FLOAT);
                players.add(new PlayerFact(UUID.fromString(text(row, "player")), text(row, "dimension"),
                        integer(row, "x"), integer(row, "z"), row.getFloat("health"), number(row, "tick")));
            }
            var regions = new ArrayList<Region>();
            for (var raw : rows(tag, "regions", ObservationLog.MAX_REGIONS)) {
                var row = (CompoundTag) raw; regions.add(new Region(text(row, "dimension"), integer(row, "x"), integer(row, "z")));
            }
            var history = new ArrayList<Event>();
            for (var raw : rows(tag, "history", ObservationLog.MAX_HISTORY)) {
                var row = (CompoundTag) raw;
                history.add(new Event(number(row, "tick"), Kind.valueOf(text(row, "kind")),
                        UUID.fromString(text(row, "player")), text(row, "subject")));
            }
            store.campaign = campaign; store.log = ObservationLog.restore(new Snapshot(players, regions, history));
        } catch (IllegalArgumentException error) { store.unsupported = tag.copy(); }
        return store;
    }
    public boolean enabled() { return unsupported == null; }
    public ObservationLog log() { if (!enabled()) throw new IllegalStateException("Unsupported observation save"); return log; }
    @Override public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        if (!enabled()) return unsupported.copy();
        tag.putInt("schemaVersion", 1); tag.putString("campaignId", campaign.toString());
        var state = log.snapshot(); var players = new ListTag(); var regions = new ListTag(); var history = new ListTag();
        for (var fact : state.players()) {
            var row = new CompoundTag(); row.putString("player", fact.player().toString()); row.putString("dimension", fact.dimension());
            row.putInt("x", fact.regionX()); row.putInt("z", fact.regionZ()); row.putFloat("health", fact.health()); row.putLong("tick", fact.seenTick()); players.add(row);
        }
        for (var fact : state.regions()) {
            var row = new CompoundTag(); row.putString("dimension", fact.dimension()); row.putInt("x", fact.x()); row.putInt("z", fact.z()); regions.add(row);
        }
        for (var event : state.history()) {
            var row = new CompoundTag(); row.putLong("tick", event.tick()); row.putString("kind", event.kind().name());
            row.putString("player", event.player().toString()); row.putString("subject", event.subject()); history.add(row);
        }
        tag.put("players", players); tag.put("regions", regions); tag.put("history", history); return tag;
    }
}
