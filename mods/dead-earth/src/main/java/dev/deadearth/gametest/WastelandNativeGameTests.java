package dev.deadearth.gametest;

import dev.deadearth.content.WastelandContent;
import java.util.List;
import net.minecraft.core.*;
import net.minecraft.core.registries.*;
import net.minecraft.gametest.framework.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.gametest.*;

@GameTestHolder("dead_earth_worldgen") @PrefixGameTestTemplate(false)
public final class WastelandNativeGameTests {
    @GameTest(template="empty",batch="wasteland_hbm_ground")
    public static void nativeHbmCornerPredicateAcceptsEveryInertSurfaceAndStillRejectsAir(GameTestHelper h)throws Exception {
        var method=Class.forName("com.hbm.world.LocationIsValidSpawn").getDeclaredMethod("matches",BlockState.class,boolean.class);method.setAccessible(true);
        for(var block:List.of(WastelandContent.ASH.get(),Blocks.STONE,Blocks.COBBLESTONE,Blocks.ANDESITE,Blocks.TUFF,Blocks.GRAVEL,Blocks.BLACKSTONE,Blocks.CRACKED_STONE_BRICKS))
            h.assertTrue((Boolean)method.invoke(null,block.defaultBlockState(),false),"Native HBM rejected inert ground: "+BuiltInRegistries.BLOCK.getKey(block));
        h.assertFalse((Boolean)method.invoke(null,Blocks.AIR.defaultBlockState(),false),"Ground adapter accepts air");h.succeed();
    }
    private static BlockPos fixture(GameTestHelper h,int offset,boolean cold) {
        var level=h.getLevel();int cx=137510+offset,cz=137510;var biome=level.registryAccess().registryOrThrow(Registries.BIOME).getHolderOrThrow(cold?Biomes.SNOWY_PLAINS:Biomes.PLAINS);
        for(int dx=-1;dx<=1;dx++)for(int dz=-1;dz<=1;dz++)level.getChunk(cx+dx,cz+dz).fillBiomesFromNoise((x,y,z,sampler)->biome,level.getChunkSource().randomState().sampler());
        for(int x=cx*16;x<cx*16+16;x++)for(int z=cz*16;z<cz*16+16;z++) {
            for(int y=250;y<280;y++)level.setBlock(new BlockPos(x,y,z),Blocks.STONE.defaultBlockState(),2|16);
            level.setBlock(new BlockPos(x,280,z),WastelandContent.ASH.get().defaultBlockState(),2|16);
        }
        return new BlockPos(cx*16+4,281,cz*16+4);
    }
    @GameTest(template="empty",batch="wasteland_hbm_placement",timeoutTicks=200)
    public static void actualNativeGeyserAndArcticVaultCanBuildOnAsh(GameTestHelper h) {
        var level=h.getLevel();var features=level.registryAccess().registryOrThrow(Registries.CONFIGURED_FEATURE);var generator=level.getChunkSource().getGenerator();
        var warm=fixture(h,0,false);var geyser=features.get(ResourceLocation.parse("hbm:geyser"));h.assertTrue(geyser!=null,"Pinned geyser missing");
        h.assertTrue(geyser.place(level,generator,RandomSource.create(4096),warm),"Native HBM geyser cannot physically build on ash");
        var cold=fixture(h,3,true);h.assertTrue(level.getBiome(cold.below()).value().getBaseTemperature()<.2f,"Cold biome fixture invalid");
        var vault=features.get(ResourceLocation.parse("hbm:arctic_vault"));h.assertTrue(vault!=null,"Pinned vault missing");
        h.assertTrue(vault.place(level,generator,RandomSource.create(4096),cold),"Native HBM vault cannot physically build on ash");h.succeed();
    }
    @GameTest(template="empty",batch="wasteland_hbm_barrel",timeoutTicks=200)
    public static void actualNativeBarrelCornerCheckAcceptsAsh(GameTestHelper h)throws Exception {
        var pos=fixture(h,6,false);var type=Class.forName("com.hbm.world.feature.BarrelFeature");
        var method=type.getDeclaredMethod("isValidSpawn",WorldGenLevel.class,BlockPos.class);method.setAccessible(true);
        var feature=BuiltInRegistries.FEATURE.get(ResourceLocation.parse("hbm:barrel"));
        h.assertTrue((Boolean)method.invoke(java.lang.reflect.Modifier.isStatic(method.getModifiers())?null:feature,h.getLevel(),pos),"Native HBM barrel rejects ash corners");h.succeed();
    }
}
