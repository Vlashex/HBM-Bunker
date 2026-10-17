package dev.deadearth.starter;

import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class StarterStockTest {
    @Test void sixPlayersAndReloadStillHaveOneWorldReceipt() {
        var store=StarterStock.create();var first=UUID.randomUUID();var chest=new BlockPos(10,40,-20);
        assertTrue(store.claim(chest,first,100));
        for(int n=0;n<6;n++)assertFalse(store.claim(chest.east(n+1),UUID.randomUUID(),101+n));
        var restored=StarterStock.load(store.save(new CompoundTag(),null),null);
        assertTrue(restored.enabled());assertTrue(restored.claimed());
        assertFalse(restored.claim(chest,first,200));
        assertEquals(store.save(new CompoundTag(),null),restored.save(new CompoundTag(),null));
    }
    @Test void availableStockSurvivesReload() {
        var restored=StarterStock.load(StarterStock.create().save(new CompoundTag(),null),null);
        assertTrue(restored.enabled());assertFalse(restored.claimed());
        assertTrue(restored.claim(BlockPos.ZERO,UUID.randomUUID(),0));
    }
    @Test void unknownOrIncompleteReceiptIsPreservedWithoutRefilling() {
        for(int version:new int[]{1,99}) {
            var tag=new CompoundTag();tag.putInt("schemaVersion",version);tag.putBoolean("claimed",true);tag.putString("future","keep");
            var store=StarterStock.load(tag,null);
            assertFalse(store.enabled());assertFalse(store.claim(BlockPos.ZERO,UUID.randomUUID(),0));
            assertEquals(tag,store.save(new CompoundTag(),null));assertFalse(store.isDirty());
        }
    }
}
