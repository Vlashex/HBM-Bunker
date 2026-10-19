package dev.deadearth.gametest;

import dev.deadearth.DeadEarth;
import dev.deadearth.campaign.SavedDataAccess;
import dev.deadearth.starter.SafeBunker;
import dev.deadearth.starter.SafeBunkerStore;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("dead_earth_starter") @PrefixGameTestTemplate(false)
public final class SafeBunkerGameTests {
    @GameTest(template="empty",batch="safe_bunker",timeoutTicks=200)
    public static void existingIntrudersAreDiscardedAfterNativeEntityIteration(GameTestHelper h) {
        var level=h.getLevel();var server=level.getServer();var runtime=DeadEarth.runtime(server);
        long deadline=runtime.campaign().campaign().activeTicks()+1000;
        var factory=new SavedData.Factory<>(()->SafeBunkerStore.create(runtime.campaign().campaign().campaignId(),deadline),SafeBunkerStore::load);
        var previous=SavedDataAccess.open(server.overworld(),SafeBunkerStore.NAME,factory);
        // The native entity getter visits accessible sections; the test framework forces this chunk.
        var base=h.absolutePos(new BlockPos(0,2,0));
        var first=EntityType.ZOMBIE.create(level);var second=EntityType.ZOMBIE.create(level);var cow=EntityType.COW.create(level);
        try {
            first.moveTo(base.getX()+0.25,base.getY()+1,base.getZ()+0.5,0,0);second.moveTo(base.getX()+0.5,base.getY()+1,base.getZ()+0.5,0,0);cow.moveTo(base.getX()+0.75,base.getY()+1,base.getZ()+0.5,0,0);
            h.assertTrue(level.addFreshEntity(first)&&level.addFreshEntity(second)&&level.addFreshEntity(cow),"Intruder fixture could not join before protection");
            var store=SafeBunkerStore.create(runtime.campaign().campaign().campaignId(),deadline);
            store.record(List.of(new SafeBunkerStore.Box(base.getX(),base.getY(),base.getZ(),base.getX(),base.getY()+3,base.getZ())),Map.of(),"native_capture");
            level.getDataStorage().set(SafeBunkerStore.NAME,store);SafeBunker.close(server);SafeBunker.tick(server,runtime);
            h.assertTrue(first.isRemoved()&&second.isRemoved(),"Bounded sweep did not remove both existing hostiles");
            h.assertFalse(cow.isRemoved(),"Bounded sweep removed a passive animal");
        }finally {
            first.discard();second.discard();cow.discard();level.getDataStorage().set(SafeBunkerStore.NAME,previous);SafeBunker.close(server);SafeBunker.tick(server,runtime);
        }
        h.succeed();
    }
    @GameTest(template="empty",batch="safe_bunker",timeoutTicks=400)
    @SuppressWarnings({"unchecked","rawtypes"})
    public static void nativeOutpostTemplatesCaptureEquipmentAndSurviveDiskReload(GameTestHelper h)throws Exception {
        var level=h.getLevel();var server=level.getServer();var runtime=DeadEarth.runtime(server);
        var factory=new SavedData.Factory<>(()->SafeBunkerStore.create(runtime.campaign().campaign().campaignId(),216000),SafeBunkerStore::load);
        var previous=SavedDataAccess.open(server.overworld(),SafeBunkerStore.NAME,factory);
        try {
            var empty=SafeBunkerStore.create(runtime.campaign().campaign().campaignId(),216000);
            server.overworld().getDataStorage().set(SafeBunkerStore.NAME,empty);SafeBunker.close(server);
            Class<?> kindType=Class.forName("dev.mostlyharmless.outpostzero.world.bunker.BunkerKind");
            Object kind=Enum.valueOf((Class)kindType,"STARTER");
            var nativePlacer=Class.forName("dev.mostlyharmless.outpostzero.world.bunker.BunkerPlacer")
                .getMethod("placeBunker",net.minecraft.server.level.ServerLevel.class,BlockPos.class,kindType);
            // Deliberately enter the same scope as StarterBunkerLogic; all placement/marker processing is native.
            SafeBunker.beginCapture(level);boolean success=false;
            try{nativePlacer.invoke(null,level,new BlockPos(2480008,40,2480008),kind);success=true;}
            finally{SafeBunker.endCapture(level,success);}
            var store=SavedDataAccess.open(server.overworld(),SafeBunkerStore.NAME,factory);
            h.assertTrue(store.recorded()&&store.boxes().size()>=7,"Hub, actual modules and hatch were not captured");
            h.assertTrue(store.originals().size()>1000,"Native construction snapshot is missing");
            h.assertTrue(store.originals().values().stream().anyMatch(id->id.toString().equals("minecraft:chest")||id.toString().equals("outpostzero:crate")),"Original equipment not recorded");
            var disk=new net.minecraft.world.level.storage.DimensionDataStorage(
                server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).resolve("data").toFile(),server.getFixerUpper(),level.registryAccess());
            var restored=disk.get(factory,SafeBunkerStore.NAME);
            h.assertTrue(restored!=null&&restored.recorded()&&restored.originals().equals(store.originals()),"Native bunker snapshot did not survive disk reload");
            h.assertTrue(restored.deadline()==216000,"Reload extended campaign deadline");
        } finally {
            server.overworld().getDataStorage().set(SafeBunkerStore.NAME,previous);previous.setDirty();
            SavedDataAccess.checkpoint(server.overworld(),SafeBunkerStore.NAME,previous);
            SafeBunker.close(server);SafeBunker.tick(server,runtime);
        }
        h.succeed();
    }

    @GameTest(template="empty",batch="safe_bunker",timeoutTicks=200)
    public static void originalWallsDoorsDropsAndHostileMovementUseTheRealWorldPath(GameTestHelper h) {
        var level=h.getLevel();var server=level.getServer();var runtime=DeadEarth.runtime(server);
        var factory=new SavedData.Factory<>(()->SafeBunkerStore.create(runtime.campaign().campaign().campaignId(),216000),SafeBunkerStore::load);
        var previous=SavedDataAccess.open(server.overworld(),SafeBunkerStore.NAME,factory);
        var base=new BlockPos(2400008,220,2400008);level.getChunkAt(base);
        var wall=base;var door=base.east();var playerBlock=base.east(2);var chest=base.east(3);
        var zombie=EntityType.ZOMBIE.create(level);var cow=EntityType.COW.create(level);
        try {
            level.setBlock(wall,Blocks.STONE_BRICKS.defaultBlockState(),3);
            level.setBlock(door,Blocks.OAK_TRAPDOOR.defaultBlockState(),3);
            level.setBlock(playerBlock,Blocks.OAK_PLANKS.defaultBlockState(),3);
            level.setBlock(chest,Blocks.CHEST.defaultBlockState(),3);
            var store=SafeBunkerStore.create(runtime.campaign().campaign().campaignId(),216000);
            store.record(List.of(new SafeBunkerStore.Box(base.getX(),220,base.getZ(),base.getX()+6,225,base.getZ()+6)),
                Map.of(wall.asLong(),BuiltInRegistries.BLOCK.getKey(Blocks.STONE_BRICKS),door.asLong(),BuiltInRegistries.BLOCK.getKey(Blocks.OAK_TRAPDOOR),chest.asLong(),BuiltInRegistries.BLOCK.getKey(Blocks.CHEST)),"native_capture");
            server.overworld().getDataStorage().set(SafeBunkerStore.NAME,store);SafeBunker.close(server);SafeBunker.tick(server,runtime);
            h.assertTrue(java.util.concurrent.CompletableFuture.supplyAsync(()->SafeBunker.intersectsSection(level,wall.getX()>>4,wall.getZ()>>4,level.getSectionIndex(wall.getY()))).join(),"Published worker section view was unavailable");
            // Native player demolition is covered by BunkerRemodelGameTests.
            h.assertFalse(level.destroyBlock(wall,true),"destroyBlock removed original wall");
            h.assertFalse(level.setBlock(wall,Blocks.AIR.defaultBlockState(),3),"setBlock removed original wall");
            h.assertTrue(level.getChunkAt(wall).setBlockState(wall,Blocks.AIR.defaultBlockState(),false)==null,"Direct chunk write bypassed protection");
            Block.dropResources(level.getBlockState(wall),level,wall);
            h.assertTrue(level.getEntitiesOfClass(ItemEntity.class,new AABB(wall).inflate(2)).isEmpty(),"Protected block generated duplicate drops");
            h.assertTrue(level.setBlock(door,level.getBlockState(door).setValue(BlockStateProperties.OPEN,true),3),"Door property change was blocked");
            var container=(net.minecraft.world.Container)level.getBlockEntity(chest);container.setItem(0,new ItemStack(Items.DIRT,3));
            h.assertTrue(container.removeItem(0,1).getCount()==1,"Protected container could not be used");
            h.assertTrue(level.destroyBlock(playerBlock,false),"Player construction became indestructible");
            level.explode(null,wall.getX()+0.5,wall.getY()+0.5,wall.getZ()+0.5,4f,net.minecraft.world.level.Level.ExplosionInteraction.BLOCK);
            h.assertTrue(level.getBlockState(wall).is(Blocks.STONE_BRICKS)&&level.getBlockState(chest).is(Blocks.CHEST),"Native explosion destroyed original construction");
            zombie.moveTo(base.getX()+3,221,base.getZ()+3,0,0);
            h.assertFalse(level.addFreshEntity(zombie),"Hostile join was allowed inside");
            zombie=EntityType.ZOMBIE.create(level);zombie.moveTo(base.getX()-3,221,base.getZ()+3,0,0);
            h.assertTrue(level.addFreshEntity(zombie),"Hostile outside join was blocked");
            double before=zombie.getX();zombie.move(MoverType.SELF,new Vec3(15,0,0));
            h.assertTrue(zombie.getX()<base.getX(),"Swept movement tunneled through bunker");
            zombie.setPosRaw(base.getX()+3,221,base.getZ()+3);h.assertTrue(zombie.getX()==before,"Teleport bypassed protection");
            cow.moveTo(base.getX()+3,221,base.getZ()+3,0,0);h.assertTrue(level.addFreshEntity(cow),"Passive animal join blocked");
        } finally {
            if(zombie!=null)zombie.discard();if(cow!=null)cow.discard();
            server.overworld().getDataStorage().set(SafeBunkerStore.NAME,previous);SafeBunker.close(server);SafeBunker.tick(server,runtime);
            for(int n=0;n<4;n++)level.removeBlock(base.east(n),false);
        }
        h.succeed();
    }
}
