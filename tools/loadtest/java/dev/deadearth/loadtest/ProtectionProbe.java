package dev.deadearth.loadtest;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/** Explicit load-test fixture only; invokes real native methods and leaves its small air arena restored. */
public final class ProtectionProbe {
    private ProtectionProbe() {}
    public static Map<String,Object> run(MinecraftServer server)throws Exception {
        if(!server.isSameThread())throw new IllegalStateException("Protection probe must run on server thread");
        var level=server.overworld();var base=findAirArena(level);
        var source=base;var target=base.above();var vine=base.above(2);
        var saved=new LinkedHashMap<BlockPos,BlockState>();
        for(var pos:BlockPos.betweenClosed(base.offset(-1,0,-1),base.offset(1,3,1)))saved.put(pos.immutable(),level.getBlockState(pos));
        var report=new LinkedHashMap<String,Object>();var rows=new ArrayList<Map<String,Object>>();
        report.put("fixture","bounded_native_protection_probe_v1");
        report.put("arena",List.of(base.getX(),base.getY(),base.getZ()));
        report.put("activeTicks",dev.deadearth.DeadEarth.runtime(server).campaign().campaign().activeTicks());
        report.put("scope","Real native predicate/randomTick/grow calls; no arbitrary destroyBlock is used to infer a mob ability");
        report.put("randomTickLimit","One deterministic native randomTick per block; current config and seasonal probability gates remain applied");
        report.put("directGrowLimit","Private native grow is invoked separately after native canSpreadInto; this deliberately bypasses randomTick config/probability gates");
        report.put("rows",rows);
        var putrid=BuiltInRegistries.BLOCK.getOptional(ResourceLocation.parse("mutationcraft:putrid_block")).orElseThrow();
        var nativeType=Class.forName("com.asestefan.mutationcraft.block.PutridBlock");
        Method canSpread=nativeType.getDeclaredMethod("canSpreadInto",ServerLevel.class,BlockPos.class,BlockPos.class);canSpread.setAccessible(true);
        Method grow=nativeType.getDeclaredMethod("grow",ServerLevel.class,BlockPos.class,BlockPos.class,RandomSource.class);grow.setAccessible(true);
        Method randomTick=nativeType.getMethod("randomTick",BlockState.class,ServerLevel.class,BlockPos.class,RandomSource.class);
        Method canCorrupt=Class.forName("com.yayuy.overgrownvegetation.corruption.CorruptionRegistry").getMethod("canCorrupt",LevelReader.class,BlockPos.class,BlockState.class);
        long seed=smallRollSeed();double roll=RandomSource.create(seed).nextDouble();
        report.put("randomSeed",seed);report.put("firstRandomRoll",roll);
        var spreading=Class.forName("com.asestefan.mutationcraft.config.MutationcraftConfig").getField("PUTRID_BLOCK_SPREADING").get(null);
        report.put("naturalPutridSpreadingEnabled",spreading.getClass().getMethod("get").invoke(spreading));
        try {
            for(String id:List.of("minecraft:stone","securitycraft:reinforced_stone","securitycraft:reinforced_stone_bricks","securitycraft:reinforced_glass",
                    "hbm:reinforced_stone","hbm:reinforced_brick","hbm:concrete_rebar","hbm:reinforced_ducrete","hbm:concrete_super","hbm:reinforced_glass")) {
                var row=new LinkedHashMap<String,Object>();row.put("block",id);rows.add(row);
                var optional=BuiltInRegistries.BLOCK.getOptional(ResourceLocation.parse(id));row.put("registered",optional.isPresent());if(optional.isEmpty())continue;
                var block=optional.get();var state=block.defaultBlockState();
                level.setBlock(source,putrid.defaultBlockState(),2);level.setBlock(target,state,2);level.setBlock(vine,Blocks.AIR.defaultBlockState(),2);
                row.put("hardness",(double)state.getDestroySpeed(level,target));row.put("explosionResistance",(double)block.getExplosionResistance());
                row.put("hasBlockEntity",state.hasBlockEntity());row.put("securityCraftOwnerSet",setProbeOwner(level,target));
                row.put("starterBunkerProtectsRemoval",dev.deadearth.starter.SafeBunker.blocksReplacement(level,target,state,Blocks.AIR.defaultBlockState()));
                row.put("nativeCanSpreadInto",canSpread.invoke(null,level,target,target));
                row.put("nativeOvergrownCanCorrupt",canCorrupt.invoke(null,level,target,state));
                randomTick.invoke(putrid,putrid.defaultBlockState(),level,source,RandomSource.create(seed));
                String afterTick=key(level.getBlockState(target));row.put("afterNativeRandomTick",afterTick);row.put("randomTickConverted",afterTick.equals("mutationcraft:putrid_block"));
                // Reset the same owned native block, then demonstrate the exact write primitive separately.
                level.setBlock(target,state,2);level.setBlock(vine,Blocks.AIR.defaultBlockState(),2);setProbeOwner(level,target);
                boolean eligible=(boolean)canSpread.invoke(null,level,target,target);
                if(eligible)grow.invoke(null,level,target,vine,RandomSource.create(seed));
                row.put("directGrowInvokedAfterPredicate",eligible);row.put("afterDirectNativeGrow",key(level.getBlockState(target)));
                level.setBlock(target,Blocks.AIR.defaultBlockState(),2);level.setBlock(vine,Blocks.AIR.defaultBlockState(),2);
            }
        } finally {
            for(var entry:saved.entrySet())level.setBlock(entry.getKey(),entry.getValue(),2);
            boolean restored=saved.entrySet().stream().allMatch(e->level.getBlockState(e.getKey()).equals(e.getValue()));
            report.put("arenaRestored",restored);if(!restored)throw new IllegalStateException("Protection probe arena was not restored");
        }
        return report;
    }
    private static String key(BlockState state){return BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();}
    private static long smallRollSeed() {
        for(long seed=0;seed<262144;seed++)if(RandomSource.create(seed).nextDouble()<0.0001)return seed;
        throw new IllegalStateException("Cannot find bounded deterministic native random seed");
    }
    private static BlockPos findAirArena(ServerLevel level) {
        int y=level.getMaxBuildHeight()-8;
        for(int attempt=0;attempt<4;attempt++) {
            var base=new BlockPos(2720008+attempt*32,y,2720008);level.getChunkAt(base);
            boolean air=true;
            for(var pos:BlockPos.betweenClosed(base.offset(-1,0,-1),base.offset(1,3,1)))if(!level.getBlockState(pos).isAir()||level.getBlockEntity(pos)!=null){air=false;break;}
            if(air)return base;
        }
        throw new IllegalStateException("No empty probe arena found; existing blocks were not changed");
    }
    private static boolean setProbeOwner(ServerLevel level,BlockPos pos)throws Exception {
        var block=level.getBlockEntity(pos);if(block==null||!Class.forName("net.geforcemods.securitycraft.api.IOwnable").isInstance(block))return false;
        block.getClass().getMethod("setOwner",String.class,String.class).invoke(block,"00000000-0000-0000-0000-000000005005","ProtectionProbe");
        return true;
    }
}
