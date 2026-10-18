package dev.deadearth.gametest;

import dev.deadearth.climate.ClimateCycle.Phase;
import dev.deadearth.climate.SeasonState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("dead_earth_native") @PrefixGameTestTemplate(false)
public final class NativeSeasonGameTests {
    @GameTest(template="empty",batch="native_seasons")
    public static void winterEvolutionUsesScaledValueInNativeThreshold(GameTestHelper helper) throws Exception {
        boundary(helper,Phase.ASH_WINTER,3,7);
    }
    @GameTest(template="empty",batch="native_seasons")
    public static void summerEvolutionDoesNotCrossThresholdOnZeroIncrement(GameTestHelper helper) throws Exception {
        boundary(helper,Phase.HEAT,1,8);
    }
    @GameTest(template="empty",batch="native_seasons",timeoutTicks=80)
    public static void leavingOverworldClearsSeasonalExposure(GameTestHelper helper) {
        var level=helper.getLevel();var client=FoundationGameTests.attach(helper);
        var player=client.player();player.getPersistentData().putInt("deadEarthExposure",-60);
        var nether=level.getServer().getLevel(net.minecraft.world.level.Level.NETHER);
        player.teleportTo(nether,.5,80,.5,java.util.Set.of(),0,0);
        helper.runAfterDelay(22,()->{
            try {helper.assertTrue(!player.getPersistentData().contains("deadEarthExposure"),"Exposure survived leaving Overworld");helper.succeed();}
            finally {client.close();}
        });
    }
    @GameTest(template="empty",batch="native_seasons")
    public static void weatherDoesNotLoadNeighbourAcrossChunkBoundary(GameTestHelper helper) {
        var level=helper.getLevel();var pos=new BlockPos(6000000,250,6000000);
        level.getChunkAt(pos);level.setBlock(pos,net.minecraft.world.level.block.Blocks.OAK_PLANKS.defaultBlockState(),2|16);
        var missing=pos.west();
        helper.assertTrue(!level.hasChunkAt(missing),"Neighbour fixture unexpectedly loaded");
        dev.deadearth.climate.SeasonWeather.surfaceColumn(level,pos.getX(),pos.getZ(),Phase.ASH_WINTER);
        helper.assertTrue(!level.hasChunkAt(missing),"Weather loaded neighbouring chunk");
        level.setBlock(pos,net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(),2|16);helper.succeed();
    }
    @GameTest(template="empty",batch="native_spawn",timeoutTicks=80)
    public static void extraSpawnsRespectPositionAndFinalizeVeto(GameTestHelper helper) {
        var level=helper.getLevel();var pos=new BlockPos(4000008,250,4000008);
        for(int x=-2;x<=2;x++) for(int z=-2;z<=2;z++) {
            var chunk=new net.minecraft.world.level.ChunkPos(pos.offset(x*16,0,z*16));
            level.setChunkForced(chunk.x,chunk.z,true);level.getChunkAt(pos.offset(x*16,0,z*16));
        }
        for(int x=-2;x<=2;x++) for(int z=-2;z<=2;z++) level.setBlock(pos.offset(x,-1,z),net.minecraft.world.level.block.Blocks.GRASS_BLOCK.defaultBlockState(),2|16);
        helper.runAfterDelay(10,()->{
            var cow=EntityType.COW.create(level);cow.moveTo(pos.getCenter());
            var positionSeen=new java.util.concurrent.atomic.AtomicBoolean();
            var vetoPosition=new java.util.concurrent.atomic.AtomicBoolean(true);
            java.util.function.Consumer<net.neoforged.neoforge.event.entity.living.MobSpawnEvent.SpawnPlacementCheck> placement=event->{
                if(event.getEntityType()==EntityType.COW && event.getPos().equals(pos))
                    event.setResult(net.neoforged.neoforge.event.entity.living.MobSpawnEvent.SpawnPlacementCheck.Result.SUCCEED);
            };
            java.util.function.Consumer<net.neoforged.neoforge.event.entity.living.MobSpawnEvent.PositionCheck> position=event->{
                if(event.getEntity()==cow) {positionSeen.set(true);event.setResult(vetoPosition.get()?net.neoforged.neoforge.event.entity.living.MobSpawnEvent.PositionCheck.Result.FAIL:net.neoforged.neoforge.event.entity.living.MobSpawnEvent.PositionCheck.Result.SUCCEED);}
            };
            var finalizeSeen=new java.util.concurrent.atomic.AtomicBoolean();
            java.util.function.Consumer<net.neoforged.neoforge.event.entity.living.FinalizeSpawnEvent> finalize=event->{
                if(event.getEntity()==cow) {finalizeSeen.set(true);event.setSpawnCancelled(true);}
            };
            try {
                helper.assertTrue(level.hasChunksAt(pos.offset(-24,-1,-24),pos.offset(24,2,24)),"Spawn fixture chunks unloaded");
                helper.assertTrue(net.minecraft.world.entity.SpawnPlacements.getPlacementType(cow.getType()).isSpawnPositionOk(level,pos,cow.getType()),"Invalid spawn ground fixture");
                net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(placement);
                net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(position);
                helper.assertTrue(!dev.deadearth.compat.BiosphereEvents.tryNaturalExtra(level,cow),"Position veto ignored");
                helper.assertTrue(positionSeen.get(),"Position check event not dispatched");
                vetoPosition.set(false);
                net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(finalize);
                helper.assertTrue(!dev.deadearth.compat.BiosphereEvents.tryNaturalExtra(level,cow),"Finalize veto ignored");
                helper.assertTrue(finalizeSeen.get(),"Finalize event not dispatched");
                helper.succeed();
            } finally {
                net.neoforged.neoforge.common.NeoForge.EVENT_BUS.unregister(position);
                net.neoforged.neoforge.common.NeoForge.EVENT_BUS.unregister(finalize);
                net.neoforged.neoforge.common.NeoForge.EVENT_BUS.unregister(placement);cow.discard();
                for(int x=-2;x<=2;x++) for(int z=-2;z<=2;z++) {
                    var chunk=new net.minecraft.world.level.ChunkPos(pos.offset(x*16,0,z*16));level.setChunkForced(chunk.x,chunk.z,false);
                }
            }
        });
    }
    private static void boundary(GameTestHelper helper,Phase phase,int remaining,int calls) throws Exception {
        if(!ModList.get().isLoaded("mutationcraft")) {helper.succeed();return;}
        var level=helper.getLevel();var position=helper.absolutePos(new BlockPos(2,2,2));
        var type=BuiltInRegistries.ENTITY_TYPE.get(ResourceLocation.parse("mutationcraft:human_stage_1"));
        var mob=(Mob)type.create(level);mob.moveTo(position.getCenter());mob.setNoAi(true);
        var config=Class.forName("com.asestefan.mutationcraft.config.MutationcraftConfig");
        var number=config.getField("HUMAN_STAGE_EVOLVE_SECONDS").get(null);
        int threshold=(int)number.getClass().getMethod("ticks").invoke(number);
        var util=Class.forName("com.asestefan.mutationcraft.ModUtil");
        var data=(CompoundTag)util.getMethod("data",Entity.class).invoke(null,mob);data.putInt("Evolve",threshold-remaining);
        var nativeTick=Class.forName("com.asestefan.mutationcraft.behavior.HumanStageEvolution").getMethod("tick",Mob.class,String.class,EntityType.class);
        try(var evolution=EvolutionNativeGameTests.phaseFiveWithPlayer(helper)) {
            SeasonState.update(phase,1);
            for(int i=0;i<calls-1;i++) {nativeTick.invoke(null,mob,"Evolve",EntityType.ZOMBIE);helper.assertTrue(!mob.isRemoved(),"Native evolution happened too early");}
            nativeTick.invoke(null,mob,"Evolve",EntityType.ZOMBIE);
            helper.assertTrue(mob.isRemoved(),"Native threshold ignored seasonal future progress");
        } finally {
            SeasonState.reset();mob.discard();
            for(var entity:level.getEntitiesOfClass(net.minecraft.world.entity.monster.Zombie.class,new net.minecraft.world.phys.AABB(position).inflate(2))) entity.discard();
        }
        helper.succeed();
    }
}
