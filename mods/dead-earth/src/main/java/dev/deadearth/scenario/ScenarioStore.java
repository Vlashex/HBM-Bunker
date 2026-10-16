package dev.deadearth.scenario;

import dev.deadearth.scenario.ScenarioTypes.*;
import java.util.ArrayList;
import java.util.UUID;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.saveddata.SavedData;

public final class ScenarioStore extends SavedData {
    public static final String NAME = "dead_earth_scenarios";
    private UUID campaignId;
    private ScenarioEngine engine;
    private CompoundTag unsupported;

    private ScenarioStore() {}

    public static ScenarioStore create(UUID campaignId) {
        var store = new ScenarioStore();
        store.campaignId = campaignId;
        store.engine = new ScenarioEngine();
        store.setDirty();
        return store;
    }

    public static ScenarioStore load(UUID expectedCampaign, CompoundTag tag, HolderLookup.Provider registries) {
        var store = new ScenarioStore();
        try {
            require(tag, "schemaVersion", Tag.TAG_INT);
            int schema = tag.getInt("schemaVersion");
            if (schema != 1 && schema != 2) throw new IllegalArgumentException("Unsupported scenario schema");
            store.campaignId = UUID.fromString(string(tag, "campaignId"));
            if (!expectedCampaign.equals(store.campaignId)) throw new IllegalArgumentException("Foreign campaign");
            require(tag, "receipts", Tag.TAG_LIST);
            var raw = (ListTag) tag.get("receipts");
            if (raw.size() > ScenarioEngine.MAX_RECEIPTS || (!raw.isEmpty() && raw.getElementType() != Tag.TAG_COMPOUND))
                throw new IllegalArgumentException("Invalid receipt list");
            var receipts = new ArrayList<Receipt>();
            for (int i = 0; i < raw.size(); i++) {
                var row = raw.getCompound(i);
                if (schema == 1 && !string(row, "catalogId").equals(ScenarioChoice.WARNING))
                    throw new IllegalArgumentException("Unknown saved catalog entry");
                var proposal = new Proposal(UUID.fromString(string(row, "requestId")),
                        UUID.fromString(string(row, "worldEpoch")), string(row, "targetId"),
                        number(row, "earliestTick"), number(row, "expiresTick"), string(row,"catalogId"),
                        schema == 1 ? "standard" : string(row,"variant"), schema == 1 ? 200 : integer(row,"delayTicks"));
                receipts.add(new Receipt(proposal, UUID.fromString(string(row, "scenarioId")),
                        State.valueOf(string(row, "state")), number(row, "revision"), string(row, "reason")));
            }
            store.engine = ScenarioEngine.restore(receipts);
            if (schema == 1 || receipts.stream().anyMatch(r -> r.state() == State.RUNNING)) store.setDirty();
        } catch (IllegalArgumentException invalid) {
            store.unsupported = tag.copy();
            store.setDirty(false);
        }
        return store;
    }

    public boolean enabled() { return unsupported == null; }
    public ScenarioEngine engine() {
        if (!enabled()) throw new IllegalStateException("Scenario save is unsupported or invalid");
        return engine;
    }

    @Override public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        if (!enabled()) return unsupported.copy();
        tag.putInt("schemaVersion", 2);
        tag.putString("campaignId", campaignId.toString());
        var rows = new ListTag();
        for (var receipt : engine.receipts()) {
            var row = new CompoundTag();
            var proposal = receipt.proposal();
            row.putString("catalogId", proposal.catalogId());
            row.putString("variant", proposal.variant());
            row.putInt("delayTicks", proposal.delayTicks());
            row.putString("requestId", proposal.requestId().toString());
            row.putString("worldEpoch", proposal.worldEpoch().toString());
            row.putString("targetId", proposal.targetId());
            row.putLong("earliestTick", proposal.earliestTick());
            row.putLong("expiresTick", proposal.expiresTick());
            row.putString("scenarioId", receipt.scenarioId().toString());
            row.putString("state", receipt.state().name());
            row.putLong("revision", receipt.revision());
            row.putString("reason", receipt.reason());
            rows.add(row);
        }
        tag.put("receipts", rows);
        return tag;
    }

    private static void require(CompoundTag tag, String key, int type) {
        if (!tag.contains(key, type)) throw new IllegalArgumentException("Missing or invalid scenario field: " + key);
    }
    private static String string(CompoundTag tag, String key) {
        require(tag, key, Tag.TAG_STRING);
        return tag.getString(key);
    }
    private static int integer(CompoundTag tag, String key) {
        require(tag,key,Tag.TAG_INT); return tag.getInt(key);
    }
    private static long number(CompoundTag tag, String key) {
        require(tag, key, Tag.TAG_LONG);
        return tag.getLong(key);
    }
}
