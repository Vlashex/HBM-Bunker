package dev.deadearth.gametest;

import dev.deadearth.worldgen.*;
import dev.deadearth.content.WastelandContent;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.core.registries.*;
import net.minecraft.gametest.framework.*;
import net.minecraft.resources.*;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;
import net.neoforged.neoforge.gametest.*;

@GameTestHolder("dead_earth_worldgen") @PrefixGameTestTemplate(false)
public final class WastelandGameTests {
    private static ResourceLocation id(String value){return ResourceLocation.parse(value);}
    @GameTest(template="empty",batch="wasteland_data")
    public static void actualBiomeAndStructureRegistriesKeepHbmAndSuppressVanillaSurfaceBuildings(GameTestHelper h) {
        var access=h.getLevel().registryAccess();var biomes=access.registryOrThrow(Registries.BIOME);
        var plains=biomes.getOrThrow(Biomes.PLAINS);var desert=biomes.getOrThrow(Biomes.DESERT);
        h.assertTrue(plains.getWaterColor()==WastelandConfig.settings().waterColor(),"Water tint did not reach actual biome");
        for(var biome:List.of(plains,desert)) {
            var stages=biome.getGenerationSettings().features();
            for(int index=0;index<stages.size();index++)for(var f:stages.get(index)) {
                String key=f.unwrapKey().orElseThrow().location().toString();
                h.assertFalse(WastelandPolicy.removesFeature(key,index==GenerationStep.Decoration.VEGETAL_DECORATION.ordinal()),"Greenery survived: "+key);
            }
            for(var category:MobCategory.values())for(var mob:biome.getMobSettings().getMobs(category).unwrap())
                h.assertFalse(WastelandPolicy.removesSpawn(BuiltInRegistries.ENTITY_TYPE.getKey(mob.type).toString(),category==MobCategory.MONSTER),"Passive spawn survived");
        }
        var desertFeatures=new HashSet<String>();desert.getGenerationSettings().features().forEach(stage->stage.forEach(f->f.unwrapKey().ifPresent(k->desertFeatures.add(k.location().toString()))));
        h.assertTrue(desertFeatures.contains("hbm:barrel"),"HBM surface feature removed with plants");
        h.assertTrue(desertFeatures.contains("dead_earth:wasteland_terrain")&&desertFeatures.contains("dead_earth:dead_trunks"),"Own features missing");
        var structures=access.registryOrThrow(Registries.STRUCTURE);
        for(String name:List.of("hbm:antenna","hbm:bunker","hbm:forest_chem","hbm:sellafield_crater","minecraft:stronghold","minecraft:trial_chambers","minecraft:monument","minecraft:ruined_portal_nether"))
            h.assertTrue(structures.get(id(name))!=null&&structures.get(id(name)).biomes().size()>0,"Protected structure lost eligibility: "+name);
        for(String name:List.of("minecraft:village_plains","minecraft:desert_pyramid","minecraft:pillager_outpost","minecraft:trail_ruins","minecraft:shipwreck_beached"))
            h.assertTrue(structures.get(id(name)).biomes().size()==0,"Surface vanilla structure still eligible: "+name);
        h.assertFalse(WastelandContent.ASH.get().defaultBlockState().hasBlockEntity(),"Ash is not inert");h.succeed();
    }
    private static void flat(GameTestHelper h,int cx,int cz) {
        var level=h.getLevel();level.getChunk(cx,cz);
        for(int x=cx*16;x<cx*16+16;x++)for(int z=cz*16;z<cz*16+16;z++) {
            for(int y=240;y<=280;y++)level.setBlock(new BlockPos(x,y,z),Blocks.STONE.defaultBlockState(),2|16);
            for(int y=281;y<level.getMaxBuildHeight();y++)level.setBlock(new BlockPos(x,y,z),Blocks.AIR.defaultBlockState(),2|16);
        }
    }
    @GameTest(template="empty",batch="wasteland_terrain",timeoutTicks=200)
    public static void craterFeatureChangesOnlyItsChunkAndLeavesContainersUntouched(GameTestHelper h) {
        int cx=137500,cz=137500;var level=h.getLevel();
        for(int tries=0;tries<32;tries++,cx++) {
            boolean impact=false;var candidates=WastelandField.candidates(level.getSeed(),WastelandConfig.settings(),cx,cz);
            for(int x=cx*16;x<cx*16+16;x++)for(int z=cz*16;z<cz*16+16;z++)if(WastelandField.displacement(candidates,x,z)!=0)impact=true;
            if(impact&&level.getChunk(cx,cz).getAllReferences().values().stream().allMatch(set->set.isEmpty()))break;
        }
        flat(h,cx,cz);
        var neighbor=new BlockPos(cx*16-1,280,cz*16+8);level.setBlock(neighbor,Blocks.GOLD_BLOCK.defaultBlockState(),2|16);
        var chest=new BlockPos(cx*16+8,280,cz*16+8);level.setBlock(chest,Blocks.CHEST.defaultBlockState(),2|16);
        boolean result=WastelandWorldgen.TERRAIN.get().place(NoneFeatureConfiguration.INSTANCE,level,level.getChunkSource().getGenerator(),RandomSource.create(4096),new BlockPos(cx*16,0,cz*16));
        h.assertTrue(result,"Terrain feature did not change the new surface");
        int converted=0,changed=0;
        for(int x=cx*16;x<cx*16+16;x++)for(int z=cz*16;z<cz*16+16;z++) {
            var top=level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.OCEAN_FLOOR,x,z)-1;
            if(top!=280)changed++;
            var state=level.getBlockState(new BlockPos(x,top,z));
            if(state.is(WastelandContent.ASH.get())||state.is(Blocks.COBBLESTONE)||state.is(Blocks.ANDESITE)||state.is(Blocks.GRAVEL)||state.is(Blocks.TUFF))converted++;
            h.assertTrue(top>=258&&top<=283,"Crater exceeded depth/rim bounds");
        }
        h.assertTrue(converted>20,"Surface did not receive mixed palette");
        h.assertTrue(changed>0,"Crater displacement was not applied");
        h.assertTrue(level.getBlockState(chest).is(Blocks.CHEST),"Terrain removed protected chest");h.assertTrue(level.getBlockState(neighbor).is(Blocks.GOLD_BLOCK),"Terrain touched neighboring chunk");h.succeed();
    }
    @GameTest(template="empty",batch="wasteland_trunks",timeoutTicks=200)
    public static void bareTrunksContainOnlyLogsAndDoNotReachAdjacentChunk(GameTestHelper h) {
        int cx=137502,cz=137502;var level=h.getLevel();flat(h,cx,cz);
        boolean result=WastelandWorldgen.TRUNKS.get().place(NoneFeatureConfiguration.INSTANCE,level,level.getChunkSource().getGenerator(),RandomSource.create(4096),new BlockPos(cx*16,0,cz*16));
        h.assertTrue(result,"No dead trunk was placed on valid fixture");int logs=0;
        for(int x=cx*16;x<cx*16+16;x++)for(int z=cz*16;z<cz*16+16;z++)for(int y=281;y<300;y++) {
            var state=level.getBlockState(new BlockPos(x,y,z));if(state.isAir())continue;
            h.assertTrue(state.is(net.minecraft.tags.BlockTags.LOGS),"Bare tree grew something other than logs");logs++;
        }
        h.assertTrue(logs>=3&&logs<=12,"Trunk escaped configured bounds");h.succeed();
    }
}
