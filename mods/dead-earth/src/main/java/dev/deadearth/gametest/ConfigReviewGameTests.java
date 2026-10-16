package dev.deadearth.gametest;
import static dev.deadearth.config.DeadEarthConfig.*;
import dev.deadearth.climate.*;
import net.minecraft.gametest.framework.*;
import net.minecraft.core.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.*;
@GameTestHolder("dead_earth_review") @PrefixGameTestTemplate(false)
public final class ConfigReviewGameTests {
 @GameTest(template="empty",batch="config_review")
 public static void disabledCoordinatorIsNeutralWithCustomCalmConfig(GameTestHelper h) {
  double p=d(CALM_PARASITES),f=d(CALM_FOREST);
  try {CALM_PARASITES.set(2d);CALM_FOREST.set(2d);SeasonState.reset();
   h.assertTrue(SeasonState.parasites()==1&&SeasonState.forest()==1&&SeasonState.parasiteHealth()==1&&SeasonState.parasiteDamage()==1,"Disabled coordinator still changes native biology/combat");
  } finally {CALM_PARASITES.set(p);CALM_FOREST.set(f);SeasonState.reset();}
  h.succeed();
 }
 @GameTest(template="empty",batch="config_review")
 public static void nativePutridSpreadDoesNotUseDamageInfluence(GameTestHelper h) throws Exception {
  if(!net.neoforged.fml.ModList.get().isLoaded("mutationcraft")){h.succeed();return;}
  double damage=d(DAMAGE_INFLUENCE);var level=h.getLevel();var pos=h.absolutePos(new BlockPos(2,2,2));
  var block=BuiltInRegistries.BLOCK.get(ResourceLocation.parse("mutationcraft:putrid_block"));
  var random=(RandomSource)java.lang.reflect.Proxy.newProxyInstance(RandomSource.class.getClassLoader(),new Class[]{RandomSource.class},(proxy,method,args)->{
   if(method.getName().equals("nextDouble"))return .4d;return method.invoke(RandomSource.create(1),args);
  });
  try {
   DAMAGE_INFLUENCE.set(0d);SeasonState.update(ClimateCycle.Phase.CALM,1.5);
   level.setBlock(pos,block.defaultBlockState(),2|16);level.setBlock(pos.above(),Blocks.STONE.defaultBlockState(),2|16);
   block.getClass().getMethod("randomTick",net.minecraft.world.level.block.state.BlockState.class,net.minecraft.server.level.ServerLevel.class,BlockPos.class,RandomSource.class).invoke(block,block.defaultBlockState(),level,pos,random);
   h.assertTrue(level.getBlockState(pos.above()).is(block),"Putrid spread incorrectly follows combat-damage coefficient");
  } finally {DAMAGE_INFLUENCE.set(damage);SeasonState.reset();level.setBlock(pos,Blocks.AIR.defaultBlockState(),2|16);level.setBlock(pos.above(),Blocks.AIR.defaultBlockState(),2|16);}
  h.succeed();
 }
}
