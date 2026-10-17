package dev.deadearth.worldgen;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WastelandPolicyTest {
    @Test void suppressesVanillaGreeneryAndFrozenTerrainButKeepsOreAndUndergroundFeatures() {
        for(String id:List.of("minecraft:trees_plains","minecraft:patch_grass_plain","minecraft:bamboo","minecraft:seagrass_normal"))
            assertTrue(WastelandPolicy.removesFeature(id,true),id);
        for(String id:List.of("minecraft:freeze_top_layer","minecraft:ice_spike","minecraft:iceberg_blue","minecraft:desert_well","minecraft:ore_dirt","minecraft:disk_grass"))
            assertTrue(WastelandPolicy.removesFeature(id,false),id);
        for(String id:List.of("minecraft:ore_iron_upper","minecraft:ore_diamond","minecraft:fossil_upper","minecraft:spring_water"))
            assertFalse(WastelandPolicy.removesFeature(id,false),id);
    }
    @Test void nativeArchitectureAndEnemyFeaturesSurviveEvenInVegetationStage() {
        for(String id:List.of("hbm:barrel","hbm:arctic_vault","hbm:library_dungeon","hbm:forest_chem","hbm:meteorite","hbm:ore_uranium","overgrown:forest","mutationcraft:infection","ae2:meteorite"))
            for(boolean vegetation:List.of(true,false))assertFalse(WastelandPolicy.removesFeature(id,vegetation),id);
        for(String id:List.of("hbm:foxglove","hbm:nightshade","hbm:reeds_beach","hbm:reeds_river","hbm:tobacco","hbm:weed","hbm:pink_log"))
            assertTrue(WastelandPolicy.removesFeature(id,false),id);
    }
    @Test void nativeInvadersAndVanillaHostilesAreNotClearedWithPassiveFauna() {
        assertTrue(WastelandPolicy.removesSpawn("minecraft:cow",false));
        assertTrue(WastelandPolicy.removesSpawn("minecraft:bat",false));
        assertTrue(WastelandPolicy.removesSpawn("minecraft:salmon",false));
        assertTrue(WastelandPolicy.removesSpawn("hbm:pigeon",false));
        assertFalse(WastelandPolicy.removesSpawn("minecraft:zombie",true));
        assertFalse(WastelandPolicy.removesSpawn("hbm:gold_creeper",true));
        assertFalse(WastelandPolicy.removesSpawn("mutationcraft:assimilated_cow",false));
        assertFalse(WastelandPolicy.removesSpawn("overgrown:spore",false));
    }
}
