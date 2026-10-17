package dev.deadearth.worldgen;

import java.util.Set;

/** Namespace boundaries preserve native architecture and the pack's invading creatures. */
public final class WastelandPolicy {
    private static final Set<String> VANILLA_SURFACE=Set.of("freeze_top_layer","ice_spike","ice_patch","iceberg_blue","iceberg_packed","blue_ice","desert_well","ore_dirt","disk_grass","disk_sand","disk_clay");
    private static final Set<String> HBM_PLANTS=Set.of("foxglove","nightshade","reeds_beach","reeds_river","tobacco","weed","pink_log");
    private WastelandPolicy(){}
    public static boolean removesFeature(String id,boolean vegetation) {
        if(id.startsWith("minecraft:"))return vegetation||VANILLA_SURFACE.contains(id.substring(10));
        return id.startsWith("hbm:")&&HBM_PLANTS.contains(id.substring(4));
    }
    public static boolean removesSpawn(String id,boolean hostile) {
        return id.equals("hbm:pigeon")||(id.startsWith("minecraft:")&&!hostile);
    }
}
