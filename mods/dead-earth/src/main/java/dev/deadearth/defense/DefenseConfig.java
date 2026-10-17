package dev.deadearth.defense;

import net.neoforged.neoforge.common.ModConfigSpec;

/** Server-side coefficients. Defense state/blocks read strength at use time, after server config load. */
public final class DefenseConfig {
    private static final ModConfigSpec.Builder B=new ModConfigSpec.Builder();
    public static final ModConfigSpec.IntValue V1_HP=B.defineInRange("hp.v1",1000,1,100000000);
    public static final ModConfigSpec.IntValue V2_HP=B.defineInRange("hp.v2",2000,1,100000000);
    public static final ModConfigSpec.IntValue V3_HP=B.defineInRange("hp.v3",8000,1,100000000);
    public static final ModConfigSpec.DoubleValue V1_HARDNESS=B.defineInRange("strength.v1Hardness",5.0,0.1,10000.0);
    public static final ModConfigSpec.DoubleValue V2_HARDNESS=B.defineInRange("strength.v2Hardness",5.0,0.1,10000.0);
    public static final ModConfigSpec.DoubleValue V3_HARDNESS=B.defineInRange("strength.v3Hardness",5.0,0.1,10000.0);
    public static final ModConfigSpec.DoubleValue V1_RESISTANCE=B.defineInRange("strength.v1BlastResistance",6.0,0.0,100000.0);
    public static final ModConfigSpec.DoubleValue V2_RESISTANCE=B.defineInRange("strength.v2BlastResistance",6.0,0.0,100000.0);
    public static final ModConfigSpec.DoubleValue V3_RESISTANCE=B.defineInRange("strength.v3BlastResistance",6.0,0.0,100000.0);
    public static final ModConfigSpec.IntValue BREAKER_DAMAGE=B.defineInRange("damage.improvedMobsPerHit",50,0,100000000);
    public static final ModConfigSpec.IntValue BREAKER_TICKS=B.defineInRange("damage.improvedMobsHitIntervalTicks",20,1,1200);
    public static final ModConfigSpec.IntValue TUNNEL_DAMAGE=B.defineInRange("damage.arboraTunnelPerAttempt",100,0,100000000);
    public static final ModConfigSpec.IntValue SIEGE_DAMAGE=B.defineInRange("damage.siegeCraterPerAttempt",250,0,100000000);
    public static final ModConfigSpec.IntValue MOB_DAMAGE=B.defineInRange("damage.otherLivingDestroyPerAttempt",50,0,100000000);
    public static final ModConfigSpec.DoubleValue EXPLOSION_DAMAGE=B.defineInRange("damage.explosionPerPower",100.0,0.0,100000000.0);
    public static final ModConfigSpec.DoubleValue HBM_VNT_DAMAGE=B.defineInRange("damage.hbmVntExplosionPerPower",100.0,0.0,100000000.0);
    public static final ModConfigSpec.IntValue MIGRATION_BUDGET=B.defineInRange("migration.blocksPerTick",4096,16,65536);
    public static final ModConfigSpec.IntValue MIGRATION_QUEUE=B.defineInRange("migration.maximumQueuedChunks",2048,16,16384);
    public static final ModConfigSpec.IntValue MIGRATION_RETRY_CHUNKS=B.defineInRange("migration.retryChunkPalettesPerTick",16,1,128);
    public static final ModConfigSpec.ConfigValue<java.util.List<String>> LEGACY_MAPPINGS=B.comment("Explicit old_id=new_v1_id pairs only. Only the confirmed OutpostZero steel wall is migrated by default; no SecurityCraft family conversion.")
        .define("migration.legacyMappings",java.util.List.of("outpostzero:steel_block=dead_earth:defense_wall_v1"),value->value instanceof java.util.List<?> rows&&rows.size()<=16&&rows.stream().allMatch(row->row instanceof String text&&text.matches("[a-z0-9_.-]+:[a-z0-9_./-]+=[a-z0-9_.-]+:[a-z0-9_./-]+")));
    public static final ModConfigSpec SPEC=B.build();
    private DefenseConfig(){}
    public static int hp(DefenseTier tier){return integer(switch(tier){case V1->V1_HP;case V2->V2_HP;case V3->V3_HP;});}
    public static float hardness(DefenseTier tier){return (float)number(switch(tier){case V1->V1_HARDNESS;case V2->V2_HARDNESS;case V3->V3_HARDNESS;});}
    public static float blastResistance(DefenseTier tier){return (float)number(switch(tier){case V1->V1_RESISTANCE;case V2->V2_RESISTANCE;case V3->V3_RESISTANCE;});}
    public static int integer(ModConfigSpec.IntValue value){return SPEC.isLoaded()?value.get():value.getDefault();}
    public static double number(ModConfigSpec.DoubleValue value){return SPEC.isLoaded()?value.get():value.getDefault();}
}
