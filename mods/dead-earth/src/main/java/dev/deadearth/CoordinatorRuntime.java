package dev.deadearth;

import dev.deadearth.campaign.CampaignStore;
import dev.deadearth.campaign.SavedDataAccess;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

public final class CoordinatorRuntime {
    private final MinecraftServer server;
    private final UUID epoch = UUID.randomUUID();
    private final CampaignStore campaign;

    public CoordinatorRuntime(MinecraftServer server) {
        this.server = server;
        requireServerThread();
        campaign = SavedDataAccess.open(server.overworld(), CampaignStore.NAME,
                new SavedData.Factory<>(CampaignStore::create, CampaignStore::load));
    }

    public boolean enabled() { return campaign.enabled(); }
    public UUID epoch() { return epoch; }
    public CampaignStore campaign() { requireServerThread(); return campaign; }

    public void tick() {
        requireServerThread();
        if (enabled()) campaign.advance(server.getPlayerCount() > 0);
    }

    public void requireServerThread() {
        if (!server.isSameThread()) throw new IllegalStateException("Dead Earth world access outside server thread");
    }
}
