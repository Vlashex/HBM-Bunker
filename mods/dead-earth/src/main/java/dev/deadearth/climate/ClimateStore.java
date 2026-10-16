package dev.deadearth.climate;
import static dev.deadearth.config.DeadEarthConfig.*;

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
        store.cycle = new ClimateCycle(now, phaseTicks());
        store.setDirty();
        return store;
    }
    public static ClimateStore load(UUID expectedCampaign, long now, CompoundTag tag, HolderLookup.Provider registries) {
        var store = new ClimateStore();
        try {
            int schema=NbtFields.integer(tag,"schemaVersion");
            if (schema != 1 && schema != 2) throw new IllegalArgumentException("Unsupported climate schema");
            store.campaignId = UUID.fromString(NbtFields.text(tag, "campaignId"));
            if (!expectedCampaign.equals(store.campaignId)) throw new IllegalArgumentException("Foreign climate campaign");
            store.cycle = ClimateCycle.restore(NbtFields.number(tag, "originTick"), NbtFields.number(tag, "phaseTicks"),
                    NbtFields.number(tag, "lastAnnouncedPhase"), NbtFields.number(tag, "lastWarnedPhase"), now, schema==1?0:NbtFields.number(tag,"ordinalOffset"),schema==1?Math.min(6000,NbtFields.number(tag,"phaseTicks")/4):NbtFields.number(tag,"warningTicks"));
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
        if(online && (cycle().phaseTicks()!=phaseTicks()||cycle().warningTicks()!=Math.min(i(WARNING_SECONDS)*20L,phaseTicks()/4)) && cycle().view(now).phaseOrdinal()>cycle().lastAnnouncedPhase()) {
            cycle=cycle().nextDuration(now,phaseTicks());setDirty();
        }
        var notices = cycle().advance(now, online);
        if (!notices.isEmpty()) setDirty();
        return notices;
    }
    public List<ClimateCycle.Window> forecast(long now,int count) {
        if(count<1||count>4)throw new IllegalArgumentException("Forecast limit is 1..4");
        long start=cycle().view(now).nextTransitionTick();var future=cycle().nextDuration(start,phaseTicks());
        var result=new java.util.ArrayList<ClimateCycle.Window>();
        for(int i=0;i<count;i++) {var phase=future.view(start);result.add(new ClimateCycle.Window(phase.phase(),phase.trend(),start,phase.nextTransitionTick()));start=phase.nextTransitionTick();}
        return List.copyOf(result);
    }
    @Override public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        if (!enabled()) return unsupported.copy();
        tag.putInt("schemaVersion", 2);
        tag.putLong("ordinalOffset",cycle.ordinalOffset());
        tag.putLong("warningTicks",cycle.warningTicks());
        tag.putString("campaignId", campaignId.toString());
        tag.putLong("originTick", cycle.originTick());
        tag.putLong("phaseTicks", cycle.phaseTicks());
        tag.putLong("lastAnnouncedPhase", cycle.lastAnnouncedPhase());
        tag.putLong("lastWarnedPhase", cycle.lastWarnedPhase());
        return tag;
    }
}
