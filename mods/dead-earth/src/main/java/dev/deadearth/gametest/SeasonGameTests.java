package dev.deadearth.gametest;

import dev.deadearth.climate.ClimateCycle.Phase;
import dev.deadearth.climate.SeasonState;
import dev.deadearth.climate.SeasonWeather;
import dev.deadearth.compat.SeasonMobHealth;
import dev.deadearth.content.SeasonBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.GameType;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("dead_earth") @PrefixGameTestTemplate(false)
public final class SeasonGameTests {
    @GameTest(template="empty",batch="seasons")
    public static void seasonalIceAndCrustThawWithoutChangingOrdinaryIce(GameTestHelper helper) {
        var level=helper.getLevel();var origin=helper.absolutePos(new BlockPos(1,0,1));
        var water=new BlockPos(origin.getX(),250,origin.getZ());var land=water.east(2);var ordinary=water.east(4);
        try {
            level.setBlockAndUpdate(water,Blocks.WATER.defaultBlockState());
            level.setBlockAndUpdate(land,Blocks.STONE.defaultBlockState());
            level.setBlockAndUpdate(ordinary,Blocks.ICE.defaultBlockState());
            helper.assertTrue(SeasonWeather.surfaceColumn(level,water.getX(),water.getZ(),Phase.ASH_WINTER),"Water did not freeze");
            helper.assertTrue(level.getBlockState(water).is(SeasonBlocks.ICE.get()),"Native water was not seasonal ice");
            SeasonWeather.surfaceColumn(level,land.getX(),land.getZ(),Phase.ASH_WINTER);
            helper.assertTrue(level.getBlockState(land.above()).is(SeasonBlocks.CRUST.get()),"No physical ice crust");
            helper.assertTrue(!level.getBlockState(land.above()).isViewBlocking(level,land.above()),"Crust unexpectedly blocks view");
            SeasonWeather.surfaceColumn(level,water.getX(),water.getZ(),Phase.CALM);
            SeasonWeather.surfaceColumn(level,land.getX(),land.getZ(),Phase.CALM);
            SeasonWeather.surfaceColumn(level,ordinary.getX(),ordinary.getZ(),Phase.HEAT);
            helper.assertTrue(level.getBlockState(water).is(Blocks.WATER),"Seasonal ice did not thaw");
            helper.assertTrue(level.isEmptyBlock(land.above()),"Crust did not thaw");
            helper.assertTrue(level.getBlockState(ordinary).is(Blocks.ICE),"Player ice was changed");
        } finally {for(var pos:new BlockPos[]{water,land,land.above(),ordinary}) level.removeBlock(pos,false);}
        helper.succeed();
    }
    @GameTest(template="empty",batch="seasons")
    public static void exposedWoodBurnsAndPlayerShelterRecovers(GameTestHelper helper) {
        var level=helper.getLevel();var origin=helper.absolutePos(new BlockPos(1,0,1));
        var wood=new BlockPos(origin.getX(),250,origin.getZ());long previousTime=level.getDayTime();
        var fireRule=level.getGameRules().getRule(net.minecraft.world.level.GameRules.RULE_DOFIRETICK);
        boolean fireBefore=fireRule.get();
        var player=FakePlayerFactory.getMinecraft(level);var previousPosition=player.position();
        try {
            level.setDayTime(6000);level.setBlockAndUpdate(wood,Blocks.OAK_PLANKS.defaultBlockState());
            fireRule.set(false,level.getServer());
            SeasonWeather.surfaceColumn(level,wood.getX(),wood.getZ(),Phase.HEAT);
            helper.assertTrue(level.isEmptyBlock(wood.above()),"doFireTick=false was ignored");
            fireRule.set(true,level.getServer());
            SeasonWeather.surfaceColumn(level,wood.getX(),wood.getZ(),Phase.HEAT);
            helper.assertTrue(level.getBlockState(wood.above()).is(Blocks.FIRE),"Exposed wood did not ignite");
            level.removeBlock(wood.above(),false);player.setGameMode(GameType.SURVIVAL);player.moveTo(wood.above().getCenter());
            player.getPersistentData().putInt("deadEarthExposure",-20);SeasonWeather.expose(player,Phase.ASH_WINTER);
            helper.assertTrue(player.getTicksFrozen()>=140,"No freezing player effect");
            level.setBlockAndUpdate(wood.above(4),Blocks.STONE.defaultBlockState());
            SeasonWeather.expose(player,Phase.ASH_WINTER);
            helper.assertTrue(player.getPersistentData().getInt("deadEarthExposure")==-19,"Shelter did not recover exposure");
            player.setGameMode(GameType.CREATIVE);SeasonWeather.expose(player,Phase.ASH_WINTER);
            helper.assertTrue(!player.getPersistentData().contains("deadEarthExposure"),"Creative player retains climate exposure");
        } finally {
            level.setDayTime(previousTime);player.moveTo(previousPosition);player.setGameMode(GameType.CREATIVE);
            fireRule.set(fireBefore,level.getServer());
            player.getPersistentData().remove("deadEarthExposure");player.setTicksFrozen(0);
            for(var pos:new BlockPos[]{wood,wood.above(),wood.above(4)}) level.removeBlock(pos,false);
        }
        helper.succeed();
    }
    @GameTest(template="empty",batch="seasons")
    public static void healthDoesNotCompoundAndSurvivesEntitySave(GameTestHelper helper) {
        var level=helper.getLevel();var mob=EntityType.ZOMBIE.create(level);float base=mob.getMaxHealth();mob.setHealth(base/2);
        SeasonMobHealth.apply(mob,1.8);SeasonMobHealth.apply(mob,1.8);
        helper.assertTrue(Math.abs(mob.getMaxHealth()-base*1.8)<.01,"Season health compounded");
        var tag=new CompoundTag();mob.saveWithoutId(tag);var restored=EntityType.ZOMBIE.create(level);restored.load(tag);
        SeasonMobHealth.apply(restored,1.8);
        helper.assertTrue(Math.abs(restored.getHealth()/restored.getMaxHealth()-.5)<.01,"HP fraction was lost on load");
        SeasonMobHealth.apply(restored,.5);SeasonMobHealth.apply(restored,1);
        helper.assertTrue(Math.abs(restored.getMaxHealth()-base)<.01,"Calm did not restore base health");
        helper.succeed();
    }
    @GameTest(template="empty",batch="seasons")
    public static void pinnedNativeHooksScaleAndRestoreWithoutChangingConfig(GameTestHelper helper) throws Exception {
        if(!ModList.get().isLoaded("mutationcraft") || !ModList.get().isLoaded("overgrown_vegetation")) {helper.succeed();return;}
        ClassLoader loader=SeasonGameTests.class.getClassLoader();
        for(String name:new String[]{"com.asestefan.mutationcraft.behavior.HumanStageEvolution","com.asestefan.mutationcraft.behavior.AssimilatedRoamerDevelop",
                "com.asestefan.mutationcraft.entity.MiterEntity","com.asestefan.mutationcraft.behavior.HookBehavior",
                "com.asestefan.mutationcraft.behavior.NecroptorBehavior","com.asestefan.mutationcraft.event.HumanoidEvents",
                "com.asestefan.mutationcraft.block.PutridBlock","com.yayuy.overgrownvegetation.corruption.CorruptionSpreader",
                "com.yayuy.overgrownvegetation.data.SpreadTable","com.yayuy.overgrownvegetation.phase.OvergrownBiomeSpawner",
                "com.yayuy.overgrownvegetation.phase.OutsideInvaderSpawner","com.yayuy.overgrownvegetation.corruption.OvergrownBiomeCreep",
                "com.yayuy.overgrownvegetation.worldgen.GiantTreeGrowthQueue","com.yayuy.overgrownvegetation.worldgen.SaplingOfflineGrowth",
                "com.yayuy.overgrownvegetation.block.CorruptedSaplingBlock","com.yayuy.overgrownvegetation.corruption.BlightFernSelfSpread"}) Class.forName(name,true,loader);
        var config=Class.forName("com.asestefan.mutationcraft.config.MutationcraftConfig",true,loader);
        var number=config.getField("OUTBREAK_TIMER_SPEED").get(null);var get=number.getClass().getMethod("get");
        try {
            SeasonState.update(Phase.CALM,1);double base=(double)get.invoke(number);
            SeasonState.update(Phase.ASH_WINTER,1);helper.assertTrue(Math.abs((double)get.invoke(number)-base*1.8)<1e-8,"Winter native factor missing");
            SeasonState.update(Phase.HEAT,1);helper.assertTrue(Math.abs((double)get.invoke(number)-base*.5)<1e-8,"Summer native factor missing");
            SeasonState.update(Phase.CALM,1);helper.assertTrue((double)get.invoke(number)==base,"Native config was permanently changed");
        } finally {SeasonState.reset();}
        helper.succeed();
    }
}
