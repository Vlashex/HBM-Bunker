package dev.deadearth.gametest;

import dev.deadearth.DeadEarth;
import dev.deadearth.campaign.SavedDataAccess;
import dev.deadearth.defense.DefensePolicy;
import dev.deadearth.starter.SafeBunker;
import dev.deadearth.starter.SafeBunkerStore;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("dead_earth_bunker_remodel") @PrefixGameTestTemplate(false)
public final class BunkerRemodelGameTests {
    @GameTest(template="empty",batch="bunker_remodel",timeoutTicks=200)
    public static void playerCanMineRebuildAndHarvestDoorWithoutOpeningOtherWritePaths(GameTestHelper h) {
        var level=h.getLevel();var server=level.getServer();var runtime=DeadEarth.runtime(server);
        long deadline=runtime.campaign().campaign().activeTicks()+216000;
        var factory=new SavedData.Factory<>(()->SafeBunkerStore.create(runtime.campaign().campaign().campaignId(),deadline),SafeBunkerStore::load);
        var previous=SavedDataAccess.open(server.overworld(),SafeBunkerStore.NAME,factory);
        // Item entities must be observed in the region forced by the framework.
        var base=h.absolutePos(new BlockPos(1,2,1));level.getChunkAt(base);
        var wall=BuiltInRegistries.BLOCK.get(ResourceLocation.parse("dead_earth:defense_wall_v1"));
        var door=BuiltInRegistries.BLOCK.get(ResourceLocation.parse("dead_earth:defense_door_v1"));
        var doorPos=base.east(2);var neighbor=base.south(2);
        var zombie=EntityType.ZOMBIE.create(level);
        try {
            level.setBlock(base,wall.defaultBlockState(),3);level.setBlock(neighbor,wall.defaultBlockState(),3);
            level.setBlock(doorPos.below(),Blocks.STONE.defaultBlockState(),3);
            level.setBlock(doorPos,door.defaultBlockState(),3);
            level.setBlock(doorPos.above(),door.defaultBlockState().setValue(DoorBlock.HALF,DoubleBlockHalf.UPPER),3);
            var store=SafeBunkerStore.create(runtime.campaign().campaign().campaignId(),deadline);
            store.record(List.of(new SafeBunkerStore.Box(base.getX(),base.getY()-1,base.getZ(),base.getX()+6,base.getY()+5,base.getZ()+6)),
                Map.of(base.asLong(),BuiltInRegistries.BLOCK.getKey(wall),neighbor.asLong(),BuiltInRegistries.BLOCK.getKey(wall),
                    doorPos.asLong(),BuiltInRegistries.BLOCK.getKey(door),doorPos.above().asLong(),BuiltInRegistries.BLOCK.getKey(door)),"native_capture");
            level.getDataStorage().set(SafeBunkerStore.NAME,store);SafeBunker.close(server);SafeBunker.tick(server,runtime);
            try(var attached=FoundationGameTests.attach(h)) {
                var player=attached.player();player.gameMode.changeGameModeForPlayer(GameType.SURVIVAL);
                player.setItemSlot(EquipmentSlot.MAINHAND,new ItemStack(Items.DIAMOND_PICKAXE));
                h.assertTrue(player.gameMode.destroyBlock(base),"Player could not mine original V1 during grace period");
                h.assertTrue(level.getBlockState(base).isAir(),"Mined wall remained in the world");
                h.assertTrue(level.getEntitiesOfClass(ItemEntity.class,new AABB(base).inflate(1)).stream()
                    .filter(e->e.getItem().is(wall.asItem())).mapToInt(e->e.getItem().getCount()).sum()==1,"Player mining did not yield exactly one wall: "+level.getEntitiesOfClass(ItemEntity.class,new AABB(base).inflate(1)).stream().map(e->e.getItem().toString()).toList());
                h.assertTrue(level.setBlock(base,wall.defaultBlockState(),3),"Player could not rebuild mined wall");
                h.assertTrue(player.gameMode.destroyBlock(doorPos.above()),"Player could not mine the upper door half");
                h.assertTrue(level.getBlockState(doorPos).isAir()&&level.getBlockState(doorPos.above()).isAir(),"Door left an orphan half");
                h.assertTrue(level.getEntitiesOfClass(ItemEntity.class,new AABB(doorPos).inflate(1)).stream()
                    .filter(e->e.getItem().is(door.asItem())).mapToInt(e->e.getItem().getCount()).sum()==1,"Door mining duplicated or lost drops");
            }
            h.assertTrue(SafeBunker.protectsRemoval(level,base),"Player edit leaked outside native action");
            h.assertFalse(level.destroyBlock(base,true),"Unattributed destruction bypassed grace protection");
            h.assertFalse(level.setBlock(neighbor,Blocks.AIR.defaultBlockState(),3),"Neighbor raw write bypassed grace protection");
            zombie.moveTo(base.getX()-2,base.getY()+1,base.getZ(),0,0);
            h.assertFalse(level.destroyBlock(base,true,zombie),"Mob destroyed rebuilt wall during grace period");
            DefensePolicy.damage(level,base,500);
            h.assertTrue(DefensePolicy.remaining(level,base)==1000,"Mob damage consumed HP during grace protection");
            level.explode(null,base.getX()+0.5,base.getY()+0.5,base.getZ()+0.5,4f,net.minecraft.world.level.Level.ExplosionInteraction.BLOCK);
            h.assertTrue(level.getBlockState(base).is(wall)&&level.getBlockState(neighbor).is(wall),"Explosion removed protected rebuilt construction");
            zombie.moveTo(base.getX()+3,base.getY()+1,base.getZ()+3,0,0);
            h.assertFalse(level.addFreshEntity(zombie),"Mob entered bunker after player remodeling");
            h.assertTrue(store.deadline()==deadline,"Player edits changed the protection deadline");
        } finally {
            zombie.discard();level.getDataStorage().set(SafeBunkerStore.NAME,previous);SafeBunker.close(server);SafeBunker.tick(server,runtime);
            DefensePolicy.scoped(DefensePolicy.Scope.ADMIN,()->{
                level.removeBlock(base,false);level.removeBlock(neighbor,false);level.removeBlock(doorPos,false);level.removeBlock(doorPos.above(),false);level.removeBlock(doorPos.below(),false);
            });
        }
        h.succeed();
    }
}
