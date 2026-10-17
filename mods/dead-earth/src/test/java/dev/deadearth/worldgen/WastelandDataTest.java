package dev.deadearth.worldgen;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WastelandDataTest {
    private JsonObject json(String path)throws Exception {
        var file=java.nio.file.Path.of(System.getProperty("deadearth.resourceSources")).resolve(path);
        assertTrue(java.nio.file.Files.isRegularFile(file),"Missing own data: "+path);
        try(var reader=java.nio.file.Files.newBufferedReader(file,StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        }
    }
    private Set<String> stateNames(JsonElement element) {
        var names=new HashSet<String>();
        if(element.isJsonArray())element.getAsJsonArray().forEach(child->names.addAll(stateNames(child)));
        else if(element.isJsonObject()) {
            var object=element.getAsJsonObject();if(object.has("Name"))names.add(object.get("Name").getAsString());
            object.entrySet().forEach(entry->names.addAll(stateNames(entry.getValue())));
        }
        return names;
    }
    @Test void surfaceOverridesPreserveAllPinnedNoiseOresAndAquifers()throws Exception {
        try(var original=new ZipFile(System.getProperty("deadearth.pinnedVanillaResources"))) {
            for(String name:List.of("overworld","large_biomes","amplified")) {
                String path="data/minecraft/worldgen/noise_settings/"+name+".json";
                var actual=json(path);JsonObject expected;
                try(var stream=original.getInputStream(original.getEntry(path))) {
                    expected=JsonParser.parseReader(new InputStreamReader(stream,StandardCharsets.UTF_8)).getAsJsonObject();
                }
                assertNotEquals(expected.get("surface_rule"),actual.get("surface_rule"));
                var states=stateNames(actual.get("surface_rule"));
                for(String state:List.of("grass_block","dirt","snow_block","powder_snow","packed_ice","mycelium","podzol","red_sand"))
                    assertFalse(states.contains("minecraft:"+state),state);
                actual.remove("surface_rule");expected.remove("surface_rule");assertEquals(expected,actual,name);
            }
        }
    }
    @Test void onlyExplicitVanillaTerrestrialStructuresLoseBiomeEligibility()throws Exception {
        var disabled=List.of("village_plains","village_desert","village_savanna","village_snowy","village_taiga",
            "desert_pyramid","jungle_pyramid","swamp_hut","igloo","mansion","pillager_outpost","trail_ruins",
            "ruined_portal","ruined_portal_desert","ruined_portal_jungle","ruined_portal_mountain","ruined_portal_swamp","shipwreck_beached");
        for(String id:disabled)assertEquals(new JsonArray(),json("data/minecraft/worldgen/structure/"+id+".json").get("biomes"),id);
        for(String id:List.of("stronghold","ancient_city","trial_chambers","mineshaft","ruined_portal_nether","shipwreck","monument","ruined_portal_ocean"))
            assertFalse(java.nio.file.Files.exists(java.nio.file.Path.of(System.getProperty("deadearth.resourceSources")).resolve("data/minecraft/worldgen/structure/"+id+".json")),id);
        var modifier=json("data/dead_earth/neoforge/biome_modifier/wasteland.json");
        assertEquals("dead_earth:wasteland",modifier.get("type").getAsString());
        assertEquals("#minecraft:is_overworld",modifier.get("biomes").getAsString());
    }
}
