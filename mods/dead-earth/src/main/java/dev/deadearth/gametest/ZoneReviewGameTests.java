package dev.deadearth.gametest;

import dev.deadearth.zone.*;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.*;
import net.neoforged.neoforge.gametest.*;

@GameTestHolder("dead_earth_zones_review") @PrefixGameTestTemplate(false)
public final class ZoneReviewGameTests {
    @GameTest(template="empty",batch="zones_emc_readiness")
    public static void pendingProjecteMappingIsUnknownInsteadOfUnpriced(GameTestHelper h) throws Exception {
        var field=Class.forName("moze_intel.projecte.emc.EMCMappingHandler").getDeclaredField("loadIndex");field.setAccessible(true);int before=field.getInt(null);
        try {field.setInt(null,-1);h.assertTrue(ZoneCompatibility.price(new ItemStack(Items.DIAMOND,4)).unknown(),"Pending EMC map was treated as a known zero price");}
        finally {field.setInt(null,before);}
        h.succeed();
    }
    @SuppressWarnings("unchecked") @GameTest(template="empty",batch="zones_review")
    public static void incompatibleDuplicateAndNegativeMeContentsAreUnknown(GameTestHelper h) throws Exception {
        var level=h.getLevel();var p=h.absolutePos(new BlockPos(2,2,2));
        level.setBlock(p,BuiltInRegistries.BLOCK.get(ResourceLocation.parse("ae2:chest")).defaultBlockState(),3);var chest=level.getBlockEntity(p);
        try {
            var keyType=Class.forName("appeng.api.stacks.AEKey");Object diamond=Class.forName("appeng.api.stacks.AEItemKey").getMethod("of",ItemStack.class).invoke(null,new ItemStack(Items.DIAMOND));
            var generic=Class.forName("appeng.api.stacks.GenericStack").getConstructor(keyType,long.class);
            var component=(DataComponentType<List<Object>>)Class.forName("appeng.api.ids.AEComponents").getField("STORAGE_CELL_INV").get(null);
            Object inventory=chest.getClass().getMethod("getInternalInventory").invoke(chest);
            for(var entries:List.of(List.of(generic.newInstance(diamond,4L),generic.newInstance(diamond,8L)),List.of(generic.newInstance(diamond,-1L)),List.of(generic.newInstance(diamond,0L)))) {
                var cell=new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.parse("ae2:item_storage_cell_1k")));cell.set(component,entries);
                inventory.getClass().getMethod("setItemDirect",int.class,ItemStack.class).invoke(inventory,1,cell);
                // Native slot changes eagerly build the handler; model the pre-cache load state explicitly.
                var handler=chest.getClass().getDeclaredField("cellHandler");handler.setAccessible(true);handler.set(chest,null);
                h.assertTrue(chest.getClass().getMethod("getOriginalCellInventory",int.class).invoke(chest,0)==null,"Fallback fixture already cached");
                h.assertTrue(ZoneCompatibility.cell(chest,0).unknown(),"Malformed ME cell was claimed to be known stock");
            }
        } finally {level.setBlock(p,net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(),3);}
        h.succeed();
    }
    @GameTest(template="empty",batch="zones_index",timeoutTicks=100)
    public static void sentryIndexTracksJoinMoveRemovalAndCapsCandidateEnumeration(GameTestHelper h) {
        var level=h.getLevel();var p=h.absolutePos(new BlockPos(3,2,3));var made=new ArrayList<Entity>();
        var target=p.east(32);var chunk=new net.minecraft.world.level.ChunkPos(target);boolean forced=level.getForcedChunks().contains(chunk.toLong());
        level.getChunkAt(target);level.setChunkForced(chunk.x,chunk.z,true);
        // A forced chunk can be FULL/block-ticking without an entity-ticking player ticket.
        var client=FoundationGameTests.attach(h);client.player().setPos(target.getX()+.5,target.getY(),target.getZ()+.5);
        var closed=new java.util.concurrent.atomic.AtomicBoolean();
        Runnable cleanup=()->{if(closed.compareAndSet(false,true)){for(var entity:made)entity.discard();if(!forced)level.setChunkForced(chunk.x,chunk.z,false);client.close();}};
        h.runAfterDelay(99,cleanup);
        // Forced FULL chunks do not become entity-ticking after a fixed number of ticks.
        // Wait for the actual destination readiness; the existing 100-tick timeout bounds this.
        h.startSequence().thenWaitUntil(()->h.assertTrue(level.isPositionEntityTicking(target),"Destination sentry chunk is not entity-ticking")).thenExecute(()->{
        try {
            var type=BuiltInRegistries.ENTITY_TYPE.get(ResourceLocation.parse("securitycraft:sentry"));
            for(int n=0;n<3;n++){var entity=type.create(level);h.assertTrue(entity!=null&&ZoneCompatibility.sentry(entity),"Native Sentry fixture missing");entity.moveTo(p.getX()+.5,p.getY(),p.getZ()+.5,0,0);level.addFreshEntity(entity);made.add(entity);}
            var sample=ZoneSentryIndex.sample(level,new net.minecraft.world.level.ChunkPos(p).toLong(),2);
            h.assertTrue(sample.ids().size()==2&&sample.truncated(),"Index failed to cap observed candidates");
            var moved=made.getFirst();moved.moveTo(target.getCenter());ZoneSentryIndex.observe(moved);
            h.assertTrue(ZoneSentryIndex.sample(level,new net.minecraft.world.level.ChunkPos(p).toLong(),8).ids().size()==2,"Moving sentry left stale source entry");
            h.assertTrue(ZoneSentryIndex.sample(level,new net.minecraft.world.level.ChunkPos(target).toLong(),8).ids().contains(moved.getUUID()),"Moving sentry missing in destination index");
            moved.discard();h.assertTrue(ZoneSentryIndex.sample(level,new net.minecraft.world.level.ChunkPos(target).toLong(),8).ids().isEmpty(),"Removed sentry remained indexed");
        } finally {cleanup.run();}
        }).thenSucceed();
    }
}
