package dev.deadearth.config;
import net.neoforged.neoforge.common.ModConfigSpec;
/** Server config is stored in <world>/serverconfig and synced by NeoForge to clients. */
public final class DeadEarthConfig {
 private static final ModConfigSpec.Builder B=new ModConfigSpec.Builder();
 public static final ModConfigSpec.DoubleValue ADAPTIVE_BELOW=B.defineInRange("adaptive.maxBelowBase",0.25,0.0,0.95);
 public static final ModConfigSpec.DoubleValue ADAPTIVE_ABOVE=B.defineInRange("adaptive.maxAboveBase",0.5,0.0,3.0);
 public static final ModConfigSpec.DoubleValue ADAPTIVE_STEP=B.defineInRange("adaptive.stepPerWindow",0.05,0.001,1.0);
 public static final ModConfigSpec.IntValue WINDOW_TICKS=B.defineInRange("adaptive.windowTicks",6000,200,72000);
 public static final ModConfigSpec.IntValue SAMPLE_TICKS=B.defineInRange("adaptive.sampleTicks",200,20,1200);
 public static final ModConfigSpec.IntValue MIN_SAMPLES=B.defineInRange("adaptive.minimumSamples",6,1,2048);
 public static final ModConfigSpec.DoubleValue KILL_WEIGHT=B.defineInRange("adaptive.killWeight",0.025,0.0,1.0);
 public static final ModConfigSpec.DoubleValue DAMAGE_WEIGHT=B.defineInRange("adaptive.damageWeight",0.01,0.0,1.0);
 public static final ModConfigSpec.DoubleValue DEATH_WEIGHT=B.defineInRange("adaptive.deathWeight",0.3,0.0,2.0);
 public static final ModConfigSpec.DoubleValue LOW_HEALTH_WEIGHT=B.defineInRange("adaptive.lowHealthWeight",0.3,0.0,2.0);
 public static final ModConfigSpec.DoubleValue HEALTH_INFLUENCE=B.defineInRange("adaptive.healthInfluence",1.0,0.0,1.0);
 public static final ModConfigSpec.DoubleValue DAMAGE_INFLUENCE=B.defineInRange("adaptive.damageInfluence",1.0,0.0,1.0);
 public static final ModConfigSpec.DoubleValue PARASITE_INFLUENCE=B.defineInRange("adaptive.parasiteEvolutionInfluence",1.0,0.0,1.0);
 public static final ModConfigSpec.DoubleValue FOREST_INFLUENCE=B.defineInRange("adaptive.forestDevelopmentInfluence",1.0,0.0,1.0);
 public static final ModConfigSpec.DoubleValue WINTER_PARASITES=B.defineInRange("seasons.winterParasites",1.8,0.05,4.0);
 public static final ModConfigSpec.DoubleValue WINTER_FOREST=B.defineInRange("seasons.winterForest",0.25,0.05,4.0);
 public static final ModConfigSpec.DoubleValue SUMMER_PARASITES=B.defineInRange("seasons.summerParasites",0.5,0.05,4.0);
 public static final ModConfigSpec.DoubleValue SUMMER_FOREST=B.defineInRange("seasons.summerForest",1.5,0.05,4.0);
 public static final ModConfigSpec.DoubleValue CALM_PARASITES=B.defineInRange("seasons.calmParasites",1.0,0.05,4.0);
 public static final ModConfigSpec.DoubleValue CALM_FOREST=B.defineInRange("seasons.calmForest",1.0,0.05,4.0);
 public static final ModConfigSpec.IntValue PHASE_MINUTES=B.defineInRange("seasons.phaseMinutes",40,1,1600);
 public static final ModConfigSpec.IntValue WARNING_SECONDS=B.defineInRange("seasons.forecastSeconds",300,0,3600);
 public static final ModConfigSpec.IntValue SURFACE_COLUMNS=B.defineInRange("weather.columnsPerTick",16,0,256);
 public static final ModConfigSpec.IntValue MAX_WEATHER_CHUNKS=B.defineInRange("weather.maximumChunks",8192,9,16384);
 public static final ModConfigSpec.IntValue WEATHER_REFRESH=B.defineInRange("weather.refreshTicks",200,20,2000);
 public static final ModConfigSpec.IntValue WEATHER_LOCK=B.defineInRange("weather.lockTicks",6000,200,100000);
 public static final ModConfigSpec.BooleanValue SUMMER_FIRE=B.define("weather.summerFire",true);
 public static final ModConfigSpec.BooleanValue WINTER_ICE=B.define("weather.winterIce",true);
 public static final ModConfigSpec.BooleanValue WINTER_CRUST=B.define("weather.winterCrust",true);
 public static final ModConfigSpec.IntValue TOP_ICE=B.defineInRange("weather.coating.topIceBlocks",2,0,8);
 public static final ModConfigSpec.IntValue TOP_SNOW=B.defineInRange("weather.coating.topSnowBlocks",1,0,8);
 public static final ModConfigSpec.IntValue SIDE_ICE=B.defineInRange("weather.coating.sideIceBlocks",1,0,4);
 public static final ModConfigSpec.IntValue SIDE_SNOW=B.defineInRange("weather.coating.sideSnowBlocks",1,0,4);
 public static final ModConfigSpec.IntValue COATING_DEPTH=B.defineInRange("weather.coating.scanDepth",384,8,384);
 public static final ModConfigSpec.IntValue COATING_CELLS=B.defineInRange("weather.coating.maximumCellsPerTick",2048,384,32768);
 public static final ModConfigSpec.IntValue COATING_STEP=B.defineInRange("weather.coating.layersPerPass",2,1,8);
 public static final ModConfigSpec.IntValue COATING_QUEUE=B.defineInRange("weather.coating.maximumActiveColumns",2048,64,8192);
 public static final ModConfigSpec.IntValue COATING_REVISITS=B.defineInRange("weather.coating.revisitedColumnsPerTick",8,1,64);
 public static final ModConfigSpec.BooleanValue WINTER_RELIEF=B.define("weather.relief.enabled",true);
 public static final ModConfigSpec.IntValue RELIEF_SCALE=B.defineInRange("weather.relief.scaleBlocks",24,8,128);
 public static final ModConfigSpec.IntValue ICE_RELIEF=B.defineInRange("weather.relief.extraIceBlocks",2,0,4);
 public static final ModConfigSpec.IntValue SNOW_RELIEF=B.defineInRange("weather.relief.extraSnowBlocks",1,0,2);
 public static final ModConfigSpec.IntValue SIDE_RELIEF=B.defineInRange("weather.relief.extraSideIceBlocks",1,0,2);
 public static final ModConfigSpec.IntValue PEAK_SPACING=B.defineInRange("weather.spikes.cellSizeBlocks",48,16,256);
 public static final ModConfigSpec.DoubleValue PEAK_CHANCE=B.defineInRange("weather.spikes.chancePerCell",0.25,0,1);
 public static final ModConfigSpec.IntValue PEAK_MIN_HEIGHT=B.defineInRange("weather.spikes.minimumExtraHeight",4,1,16);
 public static final ModConfigSpec.IntValue PEAK_MAX_HEIGHT=B.defineInRange("weather.spikes.maximumExtraHeight",8,1,16);
 public static final ModConfigSpec.IntValue PEAK_MIN_RADIUS=B.defineInRange("weather.spikes.minimumRadius",3,1,8);
 public static final ModConfigSpec.IntValue PEAK_MAX_RADIUS=B.defineInRange("weather.spikes.maximumRadius",6,1,8);
 public static final ModConfigSpec.BooleanValue PEAK_ICY_TIPS=B.define("weather.spikes.bareIceTips",true);
 public static final ModConfigSpec.IntValue WEATHER_EDITS=B.defineInRange("weather.maximumBlockChangesPerTick",24,1,256);
 public static final ModConfigSpec.IntValue WEATHER_SECTIONS=B.defineInRange("weather.maximumChangedSectionsPerTick",2,1,16);
 public static final ModConfigSpec.BooleanValue EXPOSURE_ENABLED=B.define("exposure.enabled",true);
 public static final ModConfigSpec.IntValue EXPOSURE_TICKS=B.defineInRange("exposure.sampleTicks",20,1,1200);
 public static final ModConfigSpec.IntValue EXPOSURE_LIMIT=B.defineInRange("exposure.maximum",60,20,1000);
 public static final ModConfigSpec.IntValue HEAT_GAIN=B.defineInRange("exposure.heatGain",1,0,100);
 public static final ModConfigSpec.IntValue COLD_GAIN=B.defineInRange("exposure.coldGain",2,0,100);
 public static final ModConfigSpec.IntValue RECOVERY=B.defineInRange("exposure.recovery",3,0,100);
 public static final ModConfigSpec.IntValue SOFT_THRESHOLD=B.defineInRange("exposure.effectThreshold",20,1,1000);
 public static final ModConfigSpec.IntValue HARM_THRESHOLD=B.defineInRange("exposure.harmThreshold",40,1,1000);
 public static final ModConfigSpec.DoubleValue EXPOSURE_DAMAGE=B.defineInRange("exposure.coldDamage",1.0,0.0,20.0);
 public static final ModConfigSpec.IntValue DAMAGE_TICKS=B.defineInRange("exposure.damageIntervalTicks",100,20,2000);
 public static final ModConfigSpec.IntValue FREEZE_TICKS=B.defineInRange("exposure.freezeTicks",140,0,1000);
 public static final ModConfigSpec.IntValue POTION_TICKS=B.defineInRange("exposure.effectDurationTicks",45,1,10000);
 public static final ModConfigSpec.IntValue EXPOSURE_AMPLIFIER=B.defineInRange("exposure.effectAmplifier",0,0,4);
 public static final ModConfigSpec.IntValue IGNITE_SECONDS=B.defineInRange("exposure.igniteSeconds",3,0,60);
 public static final ModConfigSpec.IntValue EXTRAS_PER_TICK=B.defineInRange("spawning.extrasPerTick",2,0,32);
 public static final ModConfigSpec.IntValue EXTRA_QUEUE=B.defineInRange("spawning.maximumQueuedExtras",64,1,1024);
 public static final ModConfigSpec.IntValue LOCAL_MOB_CAP=B.defineInRange("spawning.localMutantCap",32,1,256);
 public static final ModConfigSpec.IntValue LOCAL_MOB_RADIUS=B.defineInRange("spawning.localRadius",24,8,64);
 public static final ModConfigSpec.IntValue PLAYER_SPAWN_DISTANCE=B.defineInRange("spawning.minimumPlayerDistance",24,24,128);
 public static final ModConfigSpec.IntValue HEALTH_REFRESH=B.defineInRange("spawning.healthRefreshTicks",20,1,1200);
 public static final ModConfigSpec.IntValue NETWORK_REFRESH=B.defineInRange("performance.seasonSyncTicks",40,1,200);
 public static final ModConfigSpec.IntValue WINTER_FOG=B.defineInRange("visual.winterFogDistance",72,16,512);
 public static final ModConfigSpec.IntValue HEAT_FOG=B.defineInRange("visual.summerFogDistance",160,16,512);
 public static final ModConfigSpec.DoubleValue VISUAL_BLEND=B.defineInRange("visual.transitionSpeed",0.015,0.001,1.0);
 public static final ModConfigSpec.IntValue PARTICLE_COUNT=B.defineInRange("visual.particleCount",3,0,32);
 public static final ModConfigSpec.IntValue PARTICLE_TICKS=B.defineInRange("visual.particleIntervalTicks",3,1,200);
 public static final ModConfigSpec.DoubleValue PARTICLE_RADIUS=B.defineInRange("visual.particleRadius",12.0,1.0,64.0);
 public static final ModConfigSpec.DoubleValue PARTICLE_HEIGHT=B.defineInRange("visual.particleHeight",10.0,1.0,32.0);
 public static final ModConfigSpec.IntValue SCENARIO_COOLDOWN=B.defineInRange("scenarios.targetCooldownTicks",2400,0,72000);
 public static final ModConfigSpec.BooleanValue IGNORE_INACTIVE=B.define("adaptive.ignoreInactivePlayers",true);
 public static final ModConfigSpec.BooleanValue VISUAL_ENABLED=B.define("visual.enabled",true);
 public static final ModConfigSpec.DoubleValue WINTER_TINT=B.defineInRange("visual.winterTintStrength",.7,0,1);
 public static final ModConfigSpec.DoubleValue HEAT_TINT=B.defineInRange("visual.summerTintStrength",.25,0,1);
 public static final ModConfigSpec.IntValue WINTER_COLOR=B.defineInRange("visual.winterTintColor",0x6D7C92,0,0xFFFFFF);
 public static final ModConfigSpec.IntValue HEAT_COLOR=B.defineInRange("visual.summerTintColor",0xF5993D,0,0xFFFFFF);
 public static final ModConfigSpec.BooleanValue WEATHER_CONTROL=B.define("weather.controlWeather",true);
 public static final ModConfigSpec.BooleanValue WINTER_SNOW=B.define("weather.forceWinterSnow",true);
 public static final ModConfigSpec.IntValue OBSERVATION_REFRESH=B.defineInRange("performance.observationSampleTicks",200,20,1200);
 public static final ModConfigSpec.IntValue SITE_DISCOVERY_RADIUS=B.defineInRange("scenarios.siteDiscoveryRadius",12,1,64);
 public static final ModConfigSpec.IntValue ZONE_LOADERS=B.defineInRange("zones.maximumLoaders",16,1,64);
 public static final ModConfigSpec.DoubleValue ZONE_EMC=B.defineInRange("zones.resourceCoefficient",0.0001,0,100);
 public static final ModConfigSpec.IntValue ZONE_INTERVAL=B.defineInRange("zones.scanIntervalTicks",200,20,72000);
 public static final ModConfigSpec.IntValue ZONE_CHUNKS=B.defineInRange("zones.chunksPerTick",1,1,8);
 public static final ModConfigSpec.IntValue ZONE_BES=B.defineInRange("zones.blockEntitiesPerTick",4,1,64);
 public static final ModConfigSpec.IntValue ZONE_SLOTS=B.defineInRange("zones.inventorySlotsPerTick",64,1,1024);
 public static final ModConfigSpec.IntValue ZONE_CELLS=B.defineInRange("zones.meCellsPerTick",1,1,10);
 public static final ModConfigSpec.IntValue ZONE_MAX_BES=B.defineInRange("zones.maximumBlockEntitiesPerChunk",512,16,8192);
 public static final ModConfigSpec.IntValue ZONE_MAX_ENTITIES=B.defineInRange("zones.maximumSentriesPerChunk",128,8,1024);
 public static final ModConfigSpec.ConfigValue<java.util.List<String>> ZONE_RANKS=B.define("zones.turretRanks",java.util.List.of(
  "hbm:turret_sentry=1","hbm:turret_friendly=1","hbm:turret_sentry_damaged=1",
  "hbm:turret_chekhov=2","hbm:turret_fritz=2","hbm:turret_jeremy=2","hbm:turret_howard=2","hbm:turret_howard_damaged=2",
  "hbm:turret_richard=3","hbm:turret_tauon=3","hbm:turret_maxwell=4","hbm:turret_arty=4","hbm:turret_himars=5",
  "securitycraft:sentry=2"),v->v instanceof java.util.List<?> list&&list.size()<=128&&list.stream().allMatch(x->x instanceof String s&&s.matches("[a-z0-9_.-]+:[a-z0-9_./-]+=[0-9]{1,3}")));
 public static final ModConfigSpec.BooleanValue DIRECTOR_ENABLED=B.define("director.enabled",false);
 public static final ModConfigSpec.IntValue DIRECTOR_INTERVAL=B.defineInRange("director.decisionIntervalTicks",18000,200,144000);
 public static final ModConfigSpec.IntValue DIRECTOR_LIFETIME=B.defineInRange("director.goalLifetimeTicks",2400,200,72000);
 public static final ModConfigSpec.IntValue DIRECTOR_ZONE_AGE=B.defineInRange("director.maximumZoneAgeTicks",1200,0,72000);
 public static final ModConfigSpec.BooleanValue STARTER_ENABLED=B.define("starter.enabled",true);
 public static final ModConfigSpec.IntValue STARTER_LOADERS=B.defineInRange("starter.loaders",6,0,16);
 public static final ModConfigSpec.IntValue STARTER_DIRT=B.defineInRange("starter.dirt",256,0,512);
 public static final ModConfigSpec.IntValue STARTER_SAPLINGS=B.defineInRange("starter.saplingsPerType",4,0,64);
 public static final ModConfigSpec SPEC=B.build();
 private DeadEarthConfig(){}
 public static double d(ModConfigSpec.DoubleValue v){return SPEC.isLoaded()?v.get():v.getDefault();}
 public static int i(ModConfigSpec.IntValue v){return SPEC.isLoaded()?v.get():v.getDefault();}
 public static boolean b(ModConfigSpec.BooleanValue v){return SPEC.isLoaded()?v.get():v.getDefault();}
 public static double minPressure(){return 1-d(ADAPTIVE_BELOW);}
 public static double maxPressure(){return 1+d(ADAPTIVE_ABOVE);}
 public static long phaseTicks(){return i(PHASE_MINUTES)*1200L;}
 public static double pressureEffect(double pressure,ModConfigSpec.DoubleValue influence){return 1+(pressure-1)*d(influence);}
}
