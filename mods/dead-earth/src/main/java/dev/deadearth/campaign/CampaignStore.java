package dev.deadearth.campaign;

import java.util.Map;
import java.util.UUID;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.saveddata.SavedData;

public final class CampaignStore extends SavedData {
    public static final String NAME = "dead_earth_campaign";
    private CampaignState campaign;
    private CompoundTag unsupported;

    private CampaignStore() {}

    public static CampaignStore create() {
        var store = new CampaignStore();
        store.campaign = new CampaignState(UUID.randomUUID(), 0, 0);
        store.setDirty();
        return store;
    }

    public static CampaignStore load(CompoundTag tag, HolderLookup.Provider registries) {
        var store = new CampaignStore();
        try {
            if (!tag.contains("schemaVersion", Tag.TAG_INT) || !tag.contains("campaignId", Tag.TAG_STRING)
                    || !tag.contains("revision", Tag.TAG_LONG) || !tag.contains("activeTicks", Tag.TAG_LONG))
                throw new IllegalArgumentException("Missing campaign fields");
            store.campaign = CampaignState.fromValues(Map.of(
                    "schemaVersion", Integer.toString(tag.getInt("schemaVersion")),
                    "campaignId", tag.getString("campaignId"),
                    "revision", Long.toString(tag.getLong("revision")),
                    "activeTicks", Long.toString(tag.getLong("activeTicks"))));
        } catch (IllegalArgumentException invalid) {
            store.unsupported = tag.copy();
        }
        return store;
    }

    public boolean enabled() { return unsupported == null; }
    public CampaignState campaign() {
        if (!enabled()) throw new IllegalStateException("Campaign save is unsupported or invalid");
        return campaign;
    }

    public void advance(boolean hasOnlinePlayers) {
        if (!enabled()) return;
        long next = CampaignClock.advance(campaign.activeTicks(), hasOnlinePlayers);
        if (next == campaign.activeTicks()) return;
        campaign = new CampaignState(campaign.campaignId(),
                campaign.revision() == Long.MAX_VALUE ? Long.MAX_VALUE : campaign.revision() + 1, next);
        setDirty();
    }

    @Override public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        if (!enabled()) return unsupported.copy();
        tag.putInt("schemaVersion", CampaignState.SCHEMA_VERSION);
        tag.putString("campaignId", campaign.campaignId().toString());
        tag.putLong("revision", campaign.revision());
        tag.putLong("activeTicks", campaign.activeTicks());
        return tag;
    }
}
