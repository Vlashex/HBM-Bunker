package dev.deadearth.site;

import static dev.deadearth.campaign.NbtFields.*;
import java.util.ArrayList;
import java.util.UUID;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.level.saveddata.SavedData;

public final class SiteStore extends SavedData {
    public static final String NAME = "dead_earth_sites";
    private UUID campaign;
    private SiteRegistry registry;
    private CompoundTag unsupported;
    private SiteStore() {}
    public static SiteStore create(UUID campaign) {
        var store = new SiteStore(); store.campaign = campaign; store.registry = new SiteRegistry(); store.setDirty(); return store;
    }
    public static SiteStore load(UUID campaign, CompoundTag tag, HolderLookup.Provider registries) {
        var store = new SiteStore();
        try {
            if (integer(tag, "schemaVersion") != 1 || !UUID.fromString(text(tag, "campaignId")).equals(campaign))
                throw new IllegalArgumentException("Unsupported sites campaign/schema");
            var sites = new ArrayList<SiteRegistry.Site>();
            for (var raw : rows(tag, "sites", SiteRegistry.MAX_SITES)) {
                var row = (CompoundTag) raw;
                if (!text(row, "template").equals("relay_v1")) throw new IllegalArgumentException("Unknown site template");
                var discoverer = text(row, "discoverer");
                sites.add(new SiteRegistry.Site(UUID.fromString(text(row, "request")), UUID.fromString(text(row, "id")),
                        integer(row, "x"), integer(row, "y"), integer(row, "z"), number(row, "createdTick"),
                        SiteRegistry.State.valueOf(text(row, "state")), discoverer.isEmpty() ? null : UUID.fromString(discoverer),
                        number(row, "discoveredTick")));
            }
            store.campaign = campaign; store.registry = SiteRegistry.restore(sites);
            if (sites.stream().anyMatch(s -> s.state() == SiteRegistry.State.PLACING || s.state() == SiteRegistry.State.READY)) store.setDirty();
        } catch (IllegalArgumentException error) { store.unsupported = tag.copy(); store.setDirty(false); }
        return store;
    }
    public boolean enabled() { return unsupported == null; }
    public SiteRegistry registry() { if (!enabled()) throw new IllegalStateException("Unsupported site save"); return registry; }
    @Override public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        if (!enabled()) return unsupported.copy();
        tag.putInt("schemaVersion", 1); tag.putString("campaignId", campaign.toString()); var rows = new ListTag();
        for (var site : registry.sites()) {
            var row = new CompoundTag(); row.putString("template", "relay_v1"); row.putString("request", site.request().toString()); row.putString("id", site.id().toString());
            row.putInt("x", site.x()); row.putInt("y", site.y()); row.putInt("z", site.z()); row.putLong("createdTick", site.createdTick());
            row.putString("state", site.state().name()); row.putString("discoverer", site.discoverer() == null ? "" : site.discoverer().toString());
            row.putLong("discoveredTick", site.discoveredTick()); rows.add(row);
        }
        tag.put("sites", rows); return tag;
    }
}
