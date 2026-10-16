package dev.deadearth.climate;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class ExposureScheduleTest {
 @Test void irregularSamplingDoesNotMultiplyDamageInterval() {
  long last=-1;int hits=0;
  for(long now=21;now<=4200;now+=21)if(Exposure.damageDue(last,now,100)) {
   if(last>=0)assertTrue(now-last>=100&&now-last<121);
   last=now;hits++;
  }
  assertTrue(hits>30,"Expected roughly one hit/100 ticks, not one/2100");
 }
 @Test void clockRewindDoesNotGivePermanentImmunity() {
  assertTrue(Exposure.damageDue(1000,10,100));
  assertThrows(IllegalArgumentException.class,()->Exposure.damageDue(-1,10,0));
 }
}
