package dev.deadearth.starter;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SafeBunkerStoreTest {
    private static final ResourceLocation WALL=ResourceLocation.parse("minecraft:stone_bricks");
    private static final ResourceLocation AIR=ResourceLocation.parse("minecraft:air");
    private static final ResourceLocation PLAYER=ResourceLocation.parse("minecraft:oak_planks");
    private static final BlockPos POS=new BlockPos(100,40,100);
    private static SafeBunkerStore recorded(UUID campaign) {
        var store=SafeBunkerStore.create(campaign,216000);
        store.record(List.of(new SafeBunkerStore.Box(100,40,100,110,45,110)),Map.of(POS.asLong(),WALL),"native_capture");
        return store;
    }
    @Test void deadlineIsCampaignTimeAndDoesNotRestartOnReloadOrRecapture() {
        var campaign=UUID.randomUUID();var store=recorded(campaign);
        assertTrue(store.active(campaign,0));assertTrue(store.active(campaign,215999));
        assertFalse(store.active(campaign,216000));assertFalse(store.active(UUID.randomUUID(),1));
        var loaded=SafeBunkerStore.load(store.save(new CompoundTag(),null),null);
        assertTrue(loaded.active(campaign,215999));assertFalse(loaded.active(campaign,216000));
        assertThrows(IllegalStateException.class,()->loaded.record(List.of(new SafeBunkerStore.Box(0,0,0,1,1,1)),Map.of(),"native_capture"));
    }
    @Test void originalBlockTypeIsProtectedButPropertiesAndPlayerConstructionAreAllowed() {
        var store=recorded(UUID.randomUUID());
        assertTrue(store.blocksReplacement(POS,WALL,AIR,false));
        assertFalse(store.blocksReplacement(POS,WALL,WALL,false));
        assertFalse(store.blocksReplacement(POS.east(),PLAYER,AIR,false));
        assertFalse(store.blocksReplacement(POS,PLAYER,AIR,false));
        assertTrue(store.blocksReplacement(POS.east(),PLAYER,AIR,true));
        assertFalse(store.blocksReplacement(POS.east(40),PLAYER,AIR,true));
    }
    @Test void snapshotRoundTripAndBoundsStayExact() {
        var store=recorded(UUID.randomUUID());var tag=store.save(new CompoundTag(),null);
        var loaded=SafeBunkerStore.load(tag,null);
        assertTrue(loaded.enabled());assertEquals(tag,loaded.save(new CompoundTag(),null));
        assertTrue(loaded.contains(100,40,100));assertTrue(loaded.contains(110.999,45.999,110.999));
        assertFalse(loaded.contains(111,40,100));assertFalse(loaded.contains(100,46,100));
    }
    @Test void invalidAndFutureSavesArePreservedWithoutCreatingAReplacement() {
        var valid=recorded(UUID.randomUUID()).save(new CompoundTag(),null);
        for(int version:new int[]{0,2}) {
            var tag=valid.copy();tag.putInt("schemaVersion",version);tag.putString("future","keep");
            var store=SafeBunkerStore.load(tag,null);
            assertFalse(store.enabled());assertFalse(store.isDirty());assertEquals(tag,store.save(new CompoundTag(),null));
        }
        var tag=valid.copy();tag.putLong("deadline",-1);
        assertFalse(SafeBunkerStore.load(tag,null).enabled());
    }
    @Test void sweptBoundsStopTunnelingButAllowAnExistingIntruderToLeave() {
        var store=recorded(UUID.randomUUID());
        assertTrue(store.blocksTravel(new AABB(90,41,104,91,43,105),new Vec3(30,0,0)));
        assertTrue(store.blocksTravel(new AABB(99.2,41,104,99.8,43,105),new Vec3(0.5,0,0)));
        assertFalse(store.blocksTravel(new AABB(90,50,104,91,52,105),new Vec3(30,0,0)));
        assertFalse(store.blocksTravel(new AABB(102,41,104,103,43,105),new Vec3(-20,0,0)));
    }
}
