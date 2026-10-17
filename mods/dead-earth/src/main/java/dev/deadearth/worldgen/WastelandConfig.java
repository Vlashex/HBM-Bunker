package dev.deadearth.worldgen;

import java.util.*;
import net.neoforged.neoforge.common.ModConfigSpec;

/** Server-install common settings, frozen once before world generation; restart after editing. */
public final class WastelandConfig {
    private static final ModConfigSpec.Builder B=new ModConfigSpec.Builder();
    private static final ModConfigSpec.IntValue SMALL_SPACING=B.defineInRange("craters.small.spacing",24,16,256);
    private static final ModConfigSpec.DoubleValue SMALL_CHANCE=B.defineInRange("craters.small.chance",.85,0,1);
    private static final ModConfigSpec.IntValue SMALL_MIN_RADIUS=B.defineInRange("craters.small.minRadius",3,2,32);
    private static final ModConfigSpec.IntValue SMALL_MAX_RADIUS=B.defineInRange("craters.small.maxRadius",9,2,32);
    private static final ModConfigSpec.IntValue SMALL_MIN_DEPTH=B.defineInRange("craters.small.minDepth",2,1,32);
    private static final ModConfigSpec.IntValue SMALL_MAX_DEPTH=B.defineInRange("craters.small.maxDepth",5,1,32);
    private static final ModConfigSpec.IntValue SMALL_RIM=B.defineInRange("craters.small.rimHeight",1,0,4);
    private static final ModConfigSpec.IntValue LARGE_SPACING=B.defineInRange("craters.large.spacing",384,64,1024);
    private static final ModConfigSpec.DoubleValue LARGE_CHANCE=B.defineInRange("craters.large.chance",.35,0,1);
    private static final ModConfigSpec.IntValue LARGE_MIN_RADIUS=B.defineInRange("craters.large.minRadius",32,2,128);
    private static final ModConfigSpec.IntValue LARGE_MAX_RADIUS=B.defineInRange("craters.large.maxRadius",64,2,128);
    private static final ModConfigSpec.IntValue LARGE_MIN_DEPTH=B.defineInRange("craters.large.minDepth",10,1,32);
    private static final ModConfigSpec.IntValue LARGE_MAX_DEPTH=B.defineInRange("craters.large.maxDepth",22,1,32);
    private static final ModConfigSpec.IntValue LARGE_RIM=B.defineInRange("craters.large.rimHeight",3,0,4);
    private static final ModConfigSpec.DoubleValue ASH_PATCHES=B.defineInRange("surface.ashPatchChance",.28,0,1);
    private static final ModConfigSpec.IntValue COAT=B.defineInRange("surface.coatingDepth",3,1,5);
    private static final ModConfigSpec.IntValue WRITES=B.defineInRange("surface.maximumWritesPerChunk",16384,1024,32768);
    private static final ModConfigSpec.ConfigValue<List<? extends String>> PALETTE=B.defineList("surface.palette",
        List.of("STONE=38","ASH=22","COBBLESTONE=13","ANDESITE=8","TUFF=7","GRAVEL=7","BLACKSTONE=3","CRACKED_BRICKS=2"),WastelandConfig::weight);
    private static final ModConfigSpec.DoubleValue TRUNK_CHANCE=B.defineInRange("trunks.chancePerChunk",.35,0,1);
    private static final ModConfigSpec.IntValue TRUNK_MIN=B.defineInRange("trunks.minHeight",3,2,14);
    private static final ModConfigSpec.IntValue TRUNK_MAX=B.defineInRange("trunks.maxHeight",9,2,14);
    private static final ModConfigSpec.IntValue WATER=B.defineInRange("water.color",0x62613f,0,0xffffff);
    private static final ModConfigSpec.IntValue WATER_FOG=B.defineInRange("water.fogColor",0x343a29,0,0xffffff);
    public static final ModConfigSpec SPEC=B.build();
    private static volatile WastelandSettings frozen;
    private WastelandConfig(){}
    private static boolean weight(Object value) {
        if(!(value instanceof String raw))return false;var pair=raw.split("=",-1);if(pair.length!=2)return false;
        try{WastelandSettings.Surface.valueOf(pair[0]);int n=Integer.parseInt(pair[1]);return n>=0&&n<=1000;}catch(IllegalArgumentException e){return false;}
    }
    private static int i(ModConfigSpec.IntValue v){return SPEC.isLoaded()?v.get():v.getDefault();}
    private static double d(ModConfigSpec.DoubleValue v){return SPEC.isLoaded()?v.get():v.getDefault();}
    public static WastelandSettings settings() {
        if(frozen!=null)return frozen;
        synchronized(WastelandConfig.class) {
            if(frozen==null) {
                var palette=new EnumMap<WastelandSettings.Surface,Integer>(WastelandSettings.Surface.class);
                for(String entry:SPEC.isLoaded()?PALETTE.get():PALETTE.getDefault()) {
                    var pair=entry.split("=",-1);if(palette.put(WastelandSettings.Surface.valueOf(pair[0]),Integer.parseInt(pair[1]))!=null)
                        throw new IllegalArgumentException("Duplicate wasteland palette entry");
                }
                frozen=new WastelandSettings(new WastelandSettings.Band(i(SMALL_SPACING),d(SMALL_CHANCE),i(SMALL_MIN_RADIUS),i(SMALL_MAX_RADIUS),i(SMALL_MIN_DEPTH),i(SMALL_MAX_DEPTH),i(SMALL_RIM)),
                    new WastelandSettings.Band(i(LARGE_SPACING),d(LARGE_CHANCE),i(LARGE_MIN_RADIUS),i(LARGE_MAX_RADIUS),i(LARGE_MIN_DEPTH),i(LARGE_MAX_DEPTH),i(LARGE_RIM)),
                    d(ASH_PATCHES),d(TRUNK_CHANCE),i(TRUNK_MIN),i(TRUNK_MAX),i(COAT),i(WRITES),i(WATER),i(WATER_FOG),palette);
            }
            return frozen;
        }
    }
}
