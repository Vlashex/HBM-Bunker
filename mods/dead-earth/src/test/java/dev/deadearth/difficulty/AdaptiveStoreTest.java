package dev.deadearth.difficulty;
import dev.deadearth.climate.ClimateCycle;
import net.minecraft.nbt.CompoundTag;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class AdaptiveStoreTest {
 @Test void strongerPlayersAccelerateBiologyAndPressurePersists() {
  var id=UUID.randomUUID();var s=AdaptiveStore.create(id,0);
  for(int i=0;i<30;i++)s.observe(new PlayerStatistics.Sample(1,0,0,1,true));
  s.advance(6000,true);assertEquals(1.05,s.pressure(),1e-9);
  var profile=s.view(ClimateCycle.Phase.ASH_WINTER);
  assertEquals(1.8*1.05*.25,profile.parasiteEvolutionRate(),1e-9);assertEquals(.25*1.05,profile.forestPhaseRate(),1e-9);
  var reload=AdaptiveStore.load(id,6000,s.save(new CompoundTag(),null),null);
  assertEquals(profile,reload.view(ClimateCycle.Phase.ASH_WINTER));reload.advance(6000,false);assertEquals(profile,reload.view(ClimateCycle.Phase.ASH_WINTER));
 }
 @Test void futureCorruptAndMismatchedCheckpointsArePreserved() {
  var id=UUID.randomUUID();var tag=AdaptiveStore.create(id,100).save(new CompoundTag(),null);
  var gap=AdaptiveStore.load(id,101,tag,null);assertFalse(gap.enabled());assertEquals(tag,gap.save(new CompoundTag(),null));
  tag.putInt("schemaVersion",99);tag.putString("unknown","retain");
  var future=AdaptiveStore.load(id,100,tag,null);assertFalse(future.enabled());assertEquals(tag,future.save(new CompoundTag(),null));
  tag.putInt("schemaVersion",1);tag.putDouble("pressure",Double.NaN);
  var bad=AdaptiveStore.load(id,100,tag,null);assertFalse(bad.enabled());assertEquals(tag,bad.save(new CompoundTag(),null));
 }
 @Test void campaignMarkerPersistsRequiredAdaptiveFile() {
  var campaign=dev.deadearth.campaign.CampaignStore.create();campaign.requireAdaptive();
  var reload=dev.deadearth.campaign.CampaignStore.load(campaign.save(new CompoundTag(),null),null);
  assertTrue(reload.enabled());assertTrue(reload.adaptiveRequired());
  assertThrows(IllegalStateException.class,()->AdaptiveStore.createForCampaign(reload));
 }
 @Test void inactivePlayersDoNotDiluteActivePerformanceByDefault() {
  var id=UUID.randomUUID();var tag=AdaptiveStore.create(id,0).save(new CompoundTag(),null);tag.putDouble("pressure",1.3);
  var alone=AdaptiveStore.load(id,0,tag,null);var mixed=AdaptiveStore.load(id,0,tag,null);
  for(int i=0;i<30;i++) {var active=new PlayerStatistics.Sample(2,0,0,1,true);alone.observe(active);mixed.observe(active);
   for(int afk=0;afk<9;afk++)mixed.observe(new PlayerStatistics.Sample(0,0,0,1,false));}
  alone.advance(6000,true);mixed.advance(6000,true);assertEquals(1.35,alone.pressure(),1e-9);assertEquals(alone.pressure(),mixed.pressure());
 }
}
