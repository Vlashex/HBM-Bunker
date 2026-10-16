package dev.deadearth.difficulty;
import dev.deadearth.climate.ClimateCycle.Phase;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class AdaptivePolicyTest {
 @Test void limitsAndGradualChangesFollowPerformance() {
  var strong=new AdaptivePolicy.Window(40,0,0,1,true);
  var weak=new AdaptivePolicy.Window(0,80,2,.3,true);
  assertEquals(1.05,AdaptivePolicy.next(1,strong),1e-9);
  assertEquals(.95,AdaptivePolicy.next(1,weak),1e-9);
  assertEquals(1.5,AdaptivePolicy.next(1.5,strong),1e-9);
  assertEquals(.75,AdaptivePolicy.next(.75,weak),1e-9);
  assertEquals(1.2,AdaptivePolicy.next(1.2,new AdaptivePolicy.Window(0,0,0,1,false)));
 }
 @Test void rosterBaselineDoesNotCountOfflineKillsAndResetCountersCannotGoNegative() {
  var stats=new PlayerStatistics();var id=UUID.randomUUID();
  assertTrue(stats.sample(id,new PlayerStatistics.Counters(50,0,0,1,0,0,"minecraft:overworld")).isEmpty());
  var delta=stats.sample(id,new PlayerStatistics.Counters(52,20,1,.5,3,0,"minecraft:overworld")).orElseThrow();
  assertEquals(2,delta.kills());assertEquals(2,delta.damage());assertEquals(1,delta.deaths());assertTrue(delta.active());
  stats.retain(java.util.Set.of());
  assertTrue(stats.sample(id,new PlayerStatistics.Counters(99,100,2,1,0,0,"minecraft:overworld")).isEmpty());
  delta=stats.sample(id,new PlayerStatistics.Counters(0,0,0,1,0,0,"minecraft:overworld")).orElseThrow();
  assertEquals(0,delta.kills());assertEquals(0,delta.damage());assertFalse(delta.active());
 }
}
