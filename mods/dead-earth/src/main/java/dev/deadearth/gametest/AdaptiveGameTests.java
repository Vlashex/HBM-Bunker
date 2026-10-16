package dev.deadearth.gametest;
import dev.deadearth.difficulty.*;
import dev.deadearth.climate.*;
import dev.deadearth.campaign.SavedDataAccess;
import java.util.UUID;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.*;
import net.minecraft.stats.Stats;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.gametest.*;
@GameTestHolder("dead_earth") @PrefixGameTestTemplate(false)
public final class AdaptiveGameTests {
 @GameTest(template="empty",batch="adaptive")
 public static void realStatisticsIgnoreCreativeAndCountOnlyDeltas(GameTestHelper helper) {
  try(var client=FoundationGameTests.attach(helper)) {
   var p=client.player();p.setGameMode(GameType.SURVIVAL);var stats=new PlayerStatistics();
   helper.assertTrue(stats.collect(java.util.List.of(p)).isEmpty(),"Initial stats counted as new progress");
   p.awardStat(Stats.MOB_KILLS,4);p.awardStat(Stats.DAMAGE_TAKEN,20);p.awardStat(Stats.DEATHS,1);p.setHealth(10);
   var sample=stats.collect(java.util.List.of(p)).getFirst();
   helper.assertTrue(sample.kills()==4&&sample.damage()==2&&sample.deaths()==1&&sample.active(),"Native stats delta is incorrect");
   p.setGameMode(GameType.CREATIVE);helper.assertTrue(stats.collect(java.util.List.of(p)).isEmpty(),"Creative player counted");
   p.awardStat(Stats.MOB_KILLS,100);p.setGameMode(GameType.SURVIVAL);
   helper.assertTrue(stats.collect(java.util.List.of(p)).isEmpty(),"Creative kills leaked into survival window");
  }
  helper.succeed();
 }
 @GameTest(template="empty",batch="adaptive")
 public static void adaptiveCheckpointPreservesPressureAndSeason(GameTestHelper helper) throws Exception {
  var level=helper.getLevel();var id=UUID.randomUUID();var store=AdaptiveStore.create(id,0);
  for(int i=0;i<30;i++)store.observe(new PlayerStatistics.Sample(1,0,0,1,true));
  store.advance(6000,true);store.advance(6001,true);
  SavedDataAccess.checkpoint(level,"dead_earth_gametest_adaptive",store);
  var path=level.getServer().getWorldPath(LevelResource.ROOT).resolve("data/dead_earth_gametest_adaptive.dat");
  var root=NbtIo.readCompressed(path,NbtAccounter.create(1024*1024));
  var reload=AdaptiveStore.load(id,6001,root.getCompound("data"),level.registryAccess());
  helper.assertTrue(reload.enabled()&&reload.view(ClimateCycle.Phase.CALM).equals(store.view(ClimateCycle.Phase.CALM)),"Adaptive checkpoint changed clock/pressure");
  reload.advance(6001,false);
  helper.assertTrue(Math.abs(reload.pressure()-1.05)<1e-9,"Offline clock advanced");helper.succeed();
 }
 @GameTest(template="empty",batch="adaptive")
 public static void adaptivePressureScalesVanillaHostileDamageOnce(GameTestHelper helper) {
  var level=helper.getLevel();var zombie=EntityType.ZOMBIE.create(level);var pig=EntityType.PIG.create(level);pig.setHealth(10);
  try {
   SeasonState.update(ClimateCycle.Phase.CALM,1.5);pig.hurt(level.damageSources().mobAttack(zombie),4);
   helper.assertTrue(Math.abs(pig.getHealth()-4)<.001,"Vanilla hostile pressure missing or applied twice");
  } finally {SeasonState.reset();zombie.discard();pig.discard();}
  helper.succeed();
 }
}
