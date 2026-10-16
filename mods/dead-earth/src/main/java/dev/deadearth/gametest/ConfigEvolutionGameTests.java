package dev.deadearth.gametest;
import static dev.deadearth.config.DeadEarthConfig.*;
import dev.deadearth.DeadEarth;
import dev.deadearth.climate.*;
import dev.deadearth.difficulty.*;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.neoforged.neoforge.gametest.*;
import net.neoforged.fml.ModList;
@GameTestHolder("dead_earth") @PrefixGameTestTemplate(false)
public final class ConfigEvolutionGameTests {
 @GameTest(template="empty",batch="config")
 public static void serverConfigChangesBoundsAndCombatInfluence(GameTestHelper h) {
  h.assertTrue(SPEC.isLoaded(),"Server world config was not loaded");
  double upper=d(ADAPTIVE_ABOVE),hp=d(HEALTH_INFLUENCE),damage=d(DAMAGE_INFLUENCE);
  try {
   ADAPTIVE_ABOVE.set(.25);h.assertTrue(Math.abs(maxPressure()-1.25)<1e-9,"Configured upper bound ignored");
   HEALTH_INFLUENCE.set(0d);DAMAGE_INFLUENCE.set(0d);SeasonState.update(ClimateCycle.Phase.CALM,1.25);
   h.assertTrue(SeasonState.health()==1&&SeasonState.damage()==1,"Combat influence config ignored");
   h.assertTrue(Math.abs(SeasonState.parasites()-1.25)<1e-9,"Evolution should still respond to pressure");
  } finally {ADAPTIVE_ABOVE.set(upper);HEALTH_INFLUENCE.set(hp);DAMAGE_INFLUENCE.set(damage);SeasonState.reset();}
  h.succeed();
 }
 @GameTest(template="empty",batch="config")
 public static void seasonConfigWaitsForNextPhaseAndRoundTrips(GameTestHelper h) {
  int minutes=i(PHASE_MINUTES),warning=i(WARNING_SECONDS);var id=java.util.UUID.randomUUID();
  try {
   var store=ClimateStore.create(id,0);store.advance(0,true);long oldDuration=store.cycle().phaseTicks();
   PHASE_MINUTES.set(1);WARNING_SECONDS.set(0);var before=store.cycle().view(100);
   store.advance(100,true);h.assertTrue(store.cycle().view(100).equals(before),"Config skipped current season");
   var forecast=store.forecast(100,1).getFirst();h.assertTrue(forecast.endTick()-forecast.startTick()==1200,"Pending forecast used old duration");
   store.advance(oldDuration,true);var after=store.cycle().view(oldDuration);
   h.assertTrue(after.phaseOrdinal()==1&&after.phase()==ClimateCycle.Phase.HEAT&&after.ticksRemaining()==1200,"Next phase config lost phase identity");
   var restored=ClimateStore.load(id,oldDuration,store.save(new CompoundTag(),h.getLevel().registryAccess()),h.getLevel().registryAccess());
   h.assertTrue(restored.enabled()&&restored.cycle().view(oldDuration).equals(after),"Configured schedule failed reload");
  } finally {PHASE_MINUTES.set(minutes);WARNING_SECONDS.set(warning);}
  h.succeed();
 }
 @GameTest(template="empty",batch="config")
 public static void logoutRemovesRealCoordinatorStatsBaseline(GameTestHelper h) throws Exception {
  var runtime=DeadEarth.runtime(h.getLevel().getServer());var field=runtime.getClass().getDeclaredField("statistics");field.setAccessible(true);
  var stats=(PlayerStatistics)field.get(runtime);var client=FoundationGameTests.attach(h);boolean closed=false;
  var p=client.player();var id=p.getUUID();
  try {
   stats.sample(id,new PlayerStatistics.Counters(10,0,0,1,0,0,"minecraft:overworld"));
   client.close();closed=true;
   h.assertTrue(stats.sample(id,new PlayerStatistics.Counters(100,0,0,1,0,0,"minecraft:overworld")).isEmpty(),"Logout retained old coordinator baseline");
  } finally {stats.forget(id);if(!closed)client.close();}
  h.succeed();
 }
 @GameTest(template="empty",batch="config")
 public static void nativeEvolutionAndForestPointsScaleWithoutChangingSeason(GameTestHelper h) throws Exception {
  if(!ModList.get().isLoaded("mutationcraft")||!ModList.get().isLoaded("overgrown_vegetation")){h.succeed();return;}
  var level=h.getLevel();var runtime=DeadEarth.runtime(level.getServer());var season=runtime.climateStatus();
  var variables=Class.forName("com.asestefan.mutationcraft.network.ModVariables");
  var mapType=Class.forName("com.asestefan.mutationcraft.network.ModVariables$MapVariables");
  var map=mapType.getMethod("get",LevelAccessor.class).invoke(null,level);var time=mapType.getField("time");var paused=mapType.getField("paused");
  double original=time.getDouble(map);boolean wasPaused=paused.getBoolean(map);
  try {
   paused.setBoolean(map,false);SeasonState.update(ClimateCycle.Phase.CALM,1);variables.getMethod("tickTime",Level.class).invoke(null,level);
   double first=time.getDouble(map)-original;SeasonState.update(ClimateCycle.Phase.CALM,1.5);variables.getMethod("tickTime",Level.class).invoke(null,level);
   double second=time.getDouble(map)-original-first;
   h.assertTrue(first>0&&Math.abs(second/first-1.5)<1e-6,"Native outbreak progression ignored adaptive pressure");
   var phaseType=Class.forName("com.yayuy.overgrownvegetation.phase.PhaseData");
   var factory=(net.minecraft.world.level.saveddata.SavedData.Factory<?>)phaseType.getField("FACTORY").get(null);
   var normal=factory.constructor().get();var strong=factory.constructor().get();var phase=phaseType.getDeclaredField("phase");phase.setAccessible(true);phase.setInt(normal,1);phase.setInt(strong,1);
   var add=phaseType.getMethod("addPoints",ServerLevel.class,long.class);var points=phaseType.getMethod("getPoints");
   SeasonState.update(ClimateCycle.Phase.CALM,1);add.invoke(normal,level,20L);SeasonState.update(ClimateCycle.Phase.CALM,1.5);add.invoke(strong,level,20L);
   long a=(long)points.invoke(normal),b=(long)points.invoke(strong);h.assertTrue(a>0&&Math.abs(b/(double)a-1.5)<.001,"Native forest points ignored adaptive pressure");
   h.assertTrue(runtime.climateStatus().equals(season),"Adaptive biology changed seasonal calendar");
  } finally {time.setDouble(map,original);paused.setBoolean(map,wasPaused);SeasonState.reset();}
  h.succeed();
 }
}
