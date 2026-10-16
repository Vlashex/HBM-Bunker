package dev.deadearth.difficulty;
import dev.deadearth.config.DeadEarthConfig;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class ServerConfigTest {
 @Test void defaultsKeepApprovedBalanceAndFixedSeasonClock() {
  assertEquals(.75,DeadEarthConfig.minPressure());assertEquals(1.5,DeadEarthConfig.maxPressure());
  assertEquals(48000,DeadEarthConfig.phaseTicks());
  assertEquals(1.8,DeadEarthConfig.d(DeadEarthConfig.WINTER_PARASITES));
  assertEquals(.25,DeadEarthConfig.d(DeadEarthConfig.WINTER_FOREST));
  assertEquals(.5,DeadEarthConfig.d(DeadEarthConfig.SUMMER_PARASITES));
  assertEquals(1.5,DeadEarthConfig.d(DeadEarthConfig.SUMMER_FOREST));
 }
}
