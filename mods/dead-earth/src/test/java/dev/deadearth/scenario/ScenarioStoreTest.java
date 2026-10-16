package dev.deadearth.scenario;

import dev.deadearth.scenario.ScenarioTypes.*;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ScenarioStoreTest {
    @Test void preservesPendingReceiptAndCampaignIdentity() {
        UUID campaign = UUID.randomUUID(), epoch = UUID.randomUUID(), request = UUID.randomUUID();
        var store = ScenarioStore.create(campaign);
        var receipt = store.engine().submit(new Proposal(request, epoch, "test_anchor", 200, 1200),
                new WorldView(epoch, 0, 1, true));
        var restored = ScenarioStore.load(campaign, store.save(new CompoundTag(), null), null);
        assertTrue(restored.enabled());
        assertEquals(receipt, restored.engine().findRequest(request).orElseThrow());
    }
    @Test void futureSchemaIsNotOverwritten() {
        UUID campaign = UUID.randomUUID();
        var tag = new CompoundTag();
        tag.putInt("schemaVersion", 99);
        tag.putString("opaque", "keep me");
        var store = ScenarioStore.load(campaign, tag, null);
        assertFalse(store.enabled());
        assertFalse(store.isDirty());
        assertEquals(tag, store.save(new CompoundTag(), null));
    }
    @Test void foreignCampaignIsNotAdopted() {
        var original = ScenarioStore.create(UUID.randomUUID()).save(new CompoundTag(), null);
        var store = ScenarioStore.load(UUID.randomUUID(), original, null);
        assertFalse(store.enabled());
        assertEquals(original, store.save(new CompoundTag(), null));
    }
    @Test void interruptedReceiptIsQuarantinedAndDirty() {
        UUID campaign = UUID.randomUUID(), epoch = UUID.randomUUID(), request = UUID.randomUUID();
        var store = ScenarioStore.create(campaign);
        store.engine().submit(new Proposal(request, epoch, "test_anchor", 200, 1200), new WorldView(epoch,0,1,true));
        var tag = store.save(new CompoundTag(), null);
        tag.getList("receipts", 10).getCompound(0).putString("state", "RUNNING");
        var restored = ScenarioStore.load(campaign, tag, null);
        assertTrue(restored.enabled());
        assertTrue(restored.isDirty());
        assertEquals(State.UNCERTAIN, restored.engine().findRequest(request).orElseThrow().state());
    }
    @Test void malformedReceiptDisablesStoreInsteadOfDiscardingHistory() {
        UUID campaign = UUID.randomUUID(), epoch = UUID.randomUUID();
        var store = ScenarioStore.create(campaign);
        store.engine().submit(new Proposal(UUID.randomUUID(), epoch, "test_anchor", 200, 1200), new WorldView(epoch,0,1,true));
        var tag = store.save(new CompoundTag(), null);
        tag.getList("receipts", 10).getCompound(0).remove("requestId");
        var restored = ScenarioStore.load(campaign, tag, null);
        assertFalse(restored.enabled());
        assertEquals(tag, restored.save(new CompoundTag(), null));
    }
}
