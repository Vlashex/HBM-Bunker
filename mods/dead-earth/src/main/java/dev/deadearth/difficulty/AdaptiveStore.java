package dev.deadearth.difficulty;
import static dev.deadearth.config.DeadEarthConfig.*;
import dev.deadearth.campaign.NbtFields;
import dev.deadearth.climate.SeasonPolicy;
import dev.deadearth.climate.ClimateCycle.Phase;
import java.util.UUID;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.saveddata.SavedData;
public final class AdaptiveStore extends SavedData {
 public static final String NAME="dead_earth_adaptive";

 private UUID campaignId;private long lastTick;private double pressure=1;
 private int samplePeriod,windowPeriod;
 private long evaluatedAt;private int samples,activeSamples;private double kills,damage,deaths,health;
 private CompoundTag unsupported;
 private AdaptiveStore(){}
 public static AdaptiveStore create(UUID campaign,long now) {
  var s=new AdaptiveStore();s.campaignId=campaign;s.lastTick=now;s.evaluatedAt=now;s.samplePeriod=i(SAMPLE_TICKS);s.windowPeriod=i(WINDOW_TICKS);s.setDirty();return s;
 }
 public static AdaptiveStore load(UUID expected,long now,CompoundTag tag,HolderLookup.Provider registries) {
  var s=new AdaptiveStore();
  try {
   if(NbtFields.integer(tag,"schemaVersion")!=1) throw new IllegalArgumentException("Unsupported adaptive schema");
   s.campaignId=UUID.fromString(NbtFields.text(tag,"campaignId"));if(!expected.equals(s.campaignId)) throw new IllegalArgumentException("Foreign adaptive campaign");
   s.pressure=number(tag,"pressure");if(s.pressure<.05||s.pressure>4)throw new IllegalArgumentException("Invalid saved pressure");
   double clamped=Math.clamp(s.pressure,minPressure(),maxPressure());if(clamped!=s.pressure)s.setDirty();s.pressure=clamped;
   s.lastTick=NbtFields.number(tag,"campaignTick");
   s.samplePeriod=NbtFields.integer(tag,"samplePeriod");s.windowPeriod=NbtFields.integer(tag,"windowPeriod");
   if(s.samplePeriod<20||s.samplePeriod>1200||s.windowPeriod<200||s.windowPeriod>72000)throw new IllegalArgumentException("Invalid saved sampling period");
   s.evaluatedAt=NbtFields.number(tag,"evaluatedAt");
   if(s.lastTick!=now||s.evaluatedAt<0||s.evaluatedAt>s.lastTick) throw new IllegalArgumentException("Adaptive checkpoint timestamps differ");
   s.samples=NbtFields.integer(tag,"samples");s.activeSamples=NbtFields.integer(tag,"activeSamples");
   s.kills=number(tag,"kills");s.damage=number(tag,"damage");s.deaths=number(tag,"deaths");s.health=number(tag,"health");
   if(s.samples<0||s.samples>1000000||s.activeSamples<0||s.activeSamples>s.samples||s.health>s.samples
     ||(s.samples==0&&(s.kills!=0||s.damage!=0||s.deaths!=0||s.health!=0))) throw new IllegalArgumentException("Invalid saved window");
   if(s.samplePeriod!=i(SAMPLE_TICKS)||s.windowPeriod!=i(WINDOW_TICKS)) {
    s.samples=s.activeSamples=0;s.kills=s.damage=s.deaths=s.health=0;s.evaluatedAt=now;
    s.samplePeriod=i(SAMPLE_TICKS);s.windowPeriod=i(WINDOW_TICKS);s.setDirty();
   }
  } catch(IllegalArgumentException|ArithmeticException error){s.unsupported=tag.copy();s.setDirty(false);}
  return s;
 }
 public static AdaptiveStore createForCampaign(dev.deadearth.campaign.CampaignStore campaign) {
  if(campaign.adaptiveRequired())throw new IllegalStateException("Required adaptive save missing");
  return create(campaign.campaign().campaignId(),campaign.campaign().activeTicks());
 }
 private static double number(CompoundTag tag,String key) {
  NbtFields.require(tag,key,Tag.TAG_DOUBLE);double n=tag.getDouble(key);
  if(!Double.isFinite(n)||n<0||n>1e12) throw new IllegalArgumentException("Invalid adaptive number");return n;
 }
 public boolean enabled(){return unsupported==null;}
 private void require(){if(!enabled()) throw new IllegalStateException("Unsupported adaptive save");}
 public double pressure(){require();return Math.clamp(pressure,minPressure(),maxPressure());}
 public void observe(PlayerStatistics.Sample sample) {
  require();refreshPeriods();if((!sample.active()&&b(IGNORE_INACTIVE))||samples>=1000000) return;
  if(sample.kills()<0||sample.damage()<0||!Double.isFinite(sample.damage())||sample.deaths()<0||!Double.isFinite(sample.health())||sample.health()<0||sample.health()>1)
   throw new IllegalArgumentException("Invalid adaptive sample");
  samples++;if(sample.active()) activeSamples++;
  kills+=sample.kills();damage+=sample.damage();deaths+=sample.deaths();health+=sample.health();setDirty();
 }
 private void refreshPeriods() {
  if(samplePeriod!=i(SAMPLE_TICKS)||windowPeriod!=i(WINDOW_TICKS)) {
   samples=activeSamples=0;kills=damage=deaths=health=0;evaluatedAt=lastTick;
   samplePeriod=i(SAMPLE_TICKS);windowPeriod=i(WINDOW_TICKS);setDirty();
  }
 }
 public void advance(long now,boolean online) {
  require();refreshPeriods();if(now<lastTick)throw new IllegalArgumentException("Adaptive clock backwards");if(!online)return;long before=lastTick;lastTick=now;double oldPressure=pressure;pressure=pressure();
  if(now-evaluatedAt>=i(WINDOW_TICKS)) {
   if(samples>=i(MIN_SAMPLES)) {double players=samples/(i(WINDOW_TICKS)/(double)i(SAMPLE_TICKS));pressure=AdaptivePolicy.next(pressure,new AdaptivePolicy.Window(kills/players,damage/players,deaths/players,health/samples,activeSamples>0));}
   evaluatedAt=now;samples=activeSamples=0;kills=damage=deaths=health=0;
  }
  if(now!=before||oldPressure!=pressure) setDirty();
 }
 public void testPressure(double value) {
  if(!Boolean.getBoolean("deadearth.testControls"))throw new IllegalArgumentException("Test controls disabled");
  require();AdaptivePolicy.validate(value);pressure=value;setDirty();
 }
 public record Snapshot(double pressure,double parasiteEvolutionRate,double forestPhaseRate,double hostileHealth,double hostileDamage,double parasiteHealth,double parasiteDamage,long windowTicks,int samples,double adaptivePressure,long introTicksRemaining){}
 public Snapshot view(Phase phase){return snapshot(phase,pressure(),0);}
 public Snapshot view(Phase phase,long activeTicks){return snapshot(phase,IntroDifficulty.pressure(activeTicks,pressure()),IntroDifficulty.remaining(activeTicks));}
 private Snapshot snapshot(Phase phase,double effective,long remaining){require();var base=SeasonPolicy.forPhase(phase);return new Snapshot(effective,base.parasites()*pressureEffect(effective,PARASITE_INFLUENCE)*d(PARASITE_EVOLUTION_BASE),base.forest()*pressureEffect(effective,FOREST_INFLUENCE),pressureEffect(effective,HEALTH_INFLUENCE),pressureEffect(effective,DAMAGE_INFLUENCE),base.parasites()*pressureEffect(effective,HEALTH_INFLUENCE),base.parasites()*pressureEffect(effective,DAMAGE_INFLUENCE),lastTick-evaluatedAt,samples,pressure(),remaining);}
 @Override public CompoundTag save(CompoundTag tag,HolderLookup.Provider registries) {
  if(!enabled()) return unsupported.copy();
  tag.putInt("schemaVersion",1);tag.putString("campaignId",campaignId.toString());tag.putDouble("pressure",pressure);
  tag.putLong("campaignTick",lastTick);tag.putInt("samplePeriod",samplePeriod);tag.putInt("windowPeriod",windowPeriod);
  tag.putLong("evaluatedAt",evaluatedAt);tag.putInt("samples",samples);tag.putInt("activeSamples",activeSamples);
  tag.putDouble("kills",kills);tag.putDouble("damage",damage);tag.putDouble("deaths",deaths);tag.putDouble("health",health);return tag;
 }
}
