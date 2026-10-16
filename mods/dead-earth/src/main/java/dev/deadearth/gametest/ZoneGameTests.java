package dev.deadearth.gametest;

import dev.deadearth.zone.*;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.gametest.*;

@GameTestHolder("dead_earth_review") @PrefixGameTestTemplate(false)
public final class ZoneGameTests {
    @GameTest(template="empty",batch="zones_stock")
    public static void scannerCountsSharedPhysicalStockOnlyOnceWithoutLoadingMissingChunks(GameTestHelper h) {
        var level=h.getLevel();var p=new BlockPos(1400008,230,1400008);level.getChunkAt(p);
        level.setBlock(p,Blocks.CHEST.defaultBlockState(),3);var chest=(ChestBlockEntity)level.getBlockEntity(p);chest.setItem(0,new ItemStack(Items.DIAMOND,4));
        var loader=new ZoneTopology.Loader(level.dimension().location().toString(),p.getX(),p.getY(),p.getZ());
        var zone=ZoneTopology.build(java.util.List.of(loader,loader)).getFirst();var scanner=new ZoneScanner();
        for(int tick=0;tick<100;tick++)scanner.tick(level.getServer(),java.util.List.of(zone));
        var report=scanner.reports(level.getServer().getTickCount()).getFirst();
        h.assertTrue(report.finished()&&report.containers()==1&&report.inventorySlots()==27,"Physical stock was not bounded or was duplicated");
        h.assertTrue(report.missingChunks()==8&&!report.complete(),"Unloaded footprint pretended complete");
        h.assertTrue(!level.hasChunkAt(p.east(16))&&chest.getItem(0).getCount()==4,"Scanner loaded chunk or consumed stock");
        level.setBlock(p,Blocks.AIR.defaultBlockState(),3);h.succeed();
    }
    @GameTest(template="empty",batch="zones",timeoutTicks=100)
    public static void realBlockLoaderTicksWithoutPlayersAndOverlapSurvivesRemoval(GameTestHelper h) {
        var level=h.getLevel();var a=new BlockPos(1200000,220,1200000);var b=a.east(16);
        level.getChunkAt(a);level.getChunkAt(b);
        level.setBlock(a,dev.deadearth.content.ZoneLoaderContent.LOADER.get().defaultBlockState(),3);
        level.setBlock(b,dev.deadearth.content.ZoneLoaderContent.LOADER.get().defaultBlockState(),3);
        h.runAfterDelay(12,()->{
            try {
                h.assertTrue(ZoneLoaders.active(level,a)&&ZoneLoaders.active(level,b),"Placed loaders failed to activate");
                var zones=ZoneLoaders.zones(level.getServer()).stream().filter(z->z.loaders().stream().anyMatch(l->l.x()==a.getX())).toList();
                h.assertTrue(zones.size()==1&&zones.getFirst().chunks().size()==12,"Overlap must count twelve distinct chunks");
                var holder=level.getChunkSource().chunkMap.getVisibleChunkIfPresent(net.minecraft.world.level.ChunkPos.asLong(a.getX()>>4,a.getZ()>>4));
                h.assertTrue(holder!=null&&holder.getTickingChunk()!=null,"Loader does not provide ticking chunks without a player");
                level.setBlock(a,Blocks.AIR.defaultBlockState(),3);
                h.assertTrue(!ZoneLoaders.active(level,a)&&ZoneLoaders.active(level,b),"Removing one owner affected overlapping owner");
                h.assertTrue(ZoneLoaders.zones(level.getServer()).stream().filter(z->z.loaders().stream().anyMatch(l->l.x()==b.getX())).findFirst().orElseThrow().chunks().size()==9,"Removed loader left ghost zone chunks");
                h.succeed();
            } finally {level.setBlock(a,Blocks.AIR.defaultBlockState(),3);level.setBlock(b,Blocks.AIR.defaultBlockState(),3);}
        });
    }
    @GameTest(template="empty",batch="zones")
    public static void physicalChestObservationPreservesContentsAndDeferredLoot(GameTestHelper h) {
        var level=h.getLevel();var p=h.absolutePos(new BlockPos(2,2,2));
        level.setBlock(p,Blocks.CHEST.defaultBlockState(),3);var chest=(ChestBlockEntity)level.getBlockEntity(p);
        chest.setItem(0,new ItemStack(Items.DIAMOND,4));var before=chest.saveWithoutMetadata(level.registryAccess()).copy();
        var scan=ZoneCompatibility.container(chest);
        h.assertTrue(scan.slots()==27&&!scan.unknown(),"Physical chest slots were not observed");
        h.assertTrue(before.equals(chest.saveWithoutMetadata(level.registryAccess())),"Read-only observation modified chest");
        chest.setLootTable(net.minecraft.world.level.storage.loot.BuiltInLootTables.SIMPLE_DUNGEON);
        var deferred=ZoneCompatibility.container(chest);
        h.assertTrue(deferred.unknown()&&chest.getLootTable()!=null,"Observation unpacked deferred loot");
        level.setBlock(p,Blocks.AIR.defaultBlockState(),3);h.succeed();
    }
}
