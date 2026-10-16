package dev.deadearth.climate;

import dev.deadearth.campaign.NbtFields;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.saveddata.SavedData;

public final class ClimateStore extends SavedData {
    public static final String NAME = "dead_earth_climate";
    private UUID campaignId;
    private ClimateCycle cycle;
    private CompoundTag unsupported;
    private ClimateStore() {}

    public static ClimateStore create(UUID campaignId, long now) {
        var store = new ClimateStore();
        store.campaignId = Objects.requireNonNull(campaignId);
        store.cycle = new ClimateCycle(now, ClimateCycle.DEFAULT_PHASE_TICKS);
        store.setDirty();
        return store;
    }
    public static ClimateStore load(UUID expectedCampaign, long now, CompoundTag tag, HolderLookup.Provider registries) {
        var store = new ClimateStore();
        try {
            if (NbtFields.integer(tag, "schemaVersion") != 1) throw new IllegalArgumentException("Unsupported climate schema");
            store.campaignId = UUID.fromString(NbtFields.text(tag, "campaignId"));
            if (!expectedCampaign.equals(store.campaignId)) throw new IllegalArgumentException("Foreign climate campaign");
            store.cycle = ClimateCycle.restore(NbtFields.number(tag, "originTick"), NbtFields.number(tag, "phaseTicks"),
                    NbtFields.number(tag, "lastAnnouncedPhase"), NbtFields.number(tag, "lastWarnedPhase"), now);
        } catch (IllegalArgumentException | ArithmeticException invalid) {
            store.unsupported = tag.copy();
            store.setDirty(false);
        }
        return store;
    }
    public boolean enabled() { return unsupported == null; }
    public ClimateCycle cycle() {
        if (!enabled()) throw new IllegalStateException("Climate save is unsupported or invalid");
        return cycle;
    }
    public List<ClimateCycle.Notice> advance(long now, boolean online) {
        var notices = cycle().advance(now, online);
        if (!notices.isEmpty()) setDirty();
        return notices;
    }
    @Override public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        if (!enabled()) return unsupported.copy();
        tag.putInt("schemaVersion", 1);
        tag.putString("campaignId", campaignId.toString());
        tag.putLong("originTick", cycle.originTick());
        tag.putLong("phaseTicks", cycle.phaseTicks());
        tag.putLong("lastAnnouncedPhase", cycle.lastAnnouncedPhase());
        tag.putLong("lastWarnedPhase", cycle.lastWarnedPhase());
        return tag;
    }
}
