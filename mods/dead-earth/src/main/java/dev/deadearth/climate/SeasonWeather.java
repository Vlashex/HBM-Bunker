package dev.deadearth.climate;

import dev.deadearth.content.SeasonBlocks;
import dev.deadearth.mixin.ChunkMapAccess;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.Fluids;

/** Bounded loaded-surface work: 32 columns/tick, at most two edits per column. */
public final class SeasonWeather {
    private static final TagKey<Item> SUITS=TagKey.create(Registries.ITEM,ResourceLocation.parse("dead_earth:climate_protection"));
    private List<LevelChunk> loaded=List.of();
    private int chunkCursor,column;
    private long lastWeather=-1;
    private ClimateCycle.Phase lastPhase;

    public void tick(ServerLevel level,ClimateCycle.Phase phase,boolean online) {
        if (!online) return;
        long tick=level.getGameTime();
        if (lastPhase!=phase || tick-lastWeather>=200) {
            if (phase==ClimateCycle.Phase.HEAT) level.setWeatherParameters(6000,0,false,false);
            else if (phase==ClimateCycle.Phase.ASH_WINTER) level.setWeatherParameters(0,6000,true,false);
            else if (lastPhase!=null && lastPhase!=phase) level.setWeatherParameters(6000,0,false,false);
            lastWeather=tick;lastPhase=phase;
        }
        if (tick%200==0 || loaded.isEmpty()) {
            var fresh=new ArrayList<LevelChunk>();
            for(var holder:((ChunkMapAccess)level.getChunkSource().chunkMap).deadEarthLoadedChunks()) {
                var chunk=holder.getTickingChunk();
                if (chunk!=null && fresh.size()<8192) fresh.add(chunk);
            }
            loaded=List.copyOf(fresh);
        }
        if (!loaded.isEmpty()) for(int i=0;i<32;i++) {
            if (chunkCursor>=loaded.size()) {chunkCursor=0;column=(column+1)&255;}
            var chunk=loaded.get(chunkCursor++);
            var holder=level.getChunkSource().chunkMap.getVisibleChunkIfPresent(chunk.getPos().toLong());
            if (holder==null || holder.getTickingChunk()!=chunk) continue;
            int x=chunk.getPos().getMinBlockX()+(column&15),z=chunk.getPos().getMinBlockZ()+(column>>4);
            surfaceColumn(level,x,z,phase);
        }
        if(tick%20==0) for(var player:level.getServer().getPlayerList().getPlayers()) expose(player,phase);
    }
    public static boolean surfaceColumn(ServerLevel level,int x,int z,ClimateCycle.Phase phase) {
        // Check the entire neighbour footprint before any heightmap, shape or block queries.
        if(!level.hasChunksAt(new BlockPos(x-1,level.getMinBuildHeight(),z-1),
                new BlockPos(x+1,level.getMinBuildHeight(),z+1))) return false;
        var top=level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING,new BlockPos(x,0,z));
        if (!level.hasChunkAt(top) || !level.canSeeSky(top)) return false;
        var below=top.below();var state=level.getBlockState(below);
        if(level.getBlockState(top).is(SeasonBlocks.CRUST.get())) {
            if(phase!=ClimateCycle.Phase.ASH_WINTER) {level.setBlock(top,Blocks.AIR.defaultBlockState(),2|16);return true;}
            return false;
        }
        if (phase==ClimateCycle.Phase.ASH_WINTER) {
            if (state.getFluidState().getType()==Fluids.WATER && state.getFluidState().isSource() && state.getBlock()==Blocks.WATER) {
                level.setBlock(below,SeasonBlocks.ICE.get().defaultBlockState(),2|16);return true;
            }
            if (level.isEmptyBlock(top) && !state.hasBlockEntity() && !state.is(SeasonBlocks.CRUST.get())
                    && state.getFluidState().isEmpty() && SeasonBlocks.CRUST.get().defaultBlockState().canSurvive(level,top)) {
                level.setBlock(top,SeasonBlocks.CRUST.get().defaultBlockState(),2|16);return true;
            }
        } else {
            if (state.is(SeasonBlocks.CRUST.get())) {level.setBlock(below,Blocks.AIR.defaultBlockState(),2|16);return true;}
            if (state.is(SeasonBlocks.ICE.get())) {level.setBlock(below,Blocks.WATER.defaultBlockState(),2|16);return true;}
            if (phase==ClimateCycle.Phase.HEAT && level.isDay() && level.getGameRules().getBoolean(GameRules.RULE_DOFIRETICK)
                    && level.isEmptyBlock(top) && !state.hasBlockEntity() && state.isFlammable(level,below,net.minecraft.core.Direction.UP)) {
                var fire=BaseFireBlock.getState(level,top);
                if (fire.canSurvive(level,top)) {level.setBlock(top,fire,2|16);return true;}
            }
        }
        return false;
    }
    public static void expose(ServerPlayer player,ClimateCycle.Phase phase) {
        var level=player.serverLevel();
        var data=player.getPersistentData();
        if (player.isCreative() || player.isSpectator() || !player.isAlive() || !level.dimension().equals(Level.OVERWORLD)) {
            data.remove("deadEarthExposure");return;
        }
        boolean suit=true;int pieces=0;
        for(var armor:player.getArmorSlots()) {pieces++;if(!armor.is(SUITS)) suit=false;}
        suit&=pieces==4;
        // Heightmap reacts immediately to a new roof; skylight propagation can lag behind.
        boolean outside=level.getHeight(Heightmap.Types.MOTION_BLOCKING,player.getBlockX(),player.getBlockZ())<=player.getBlockY()+1;
        int exposure=Exposure.step(data.getInt("deadEarthExposure"),phase,outside,level.isDay()&&!level.isRaining(),suit);
        data.putInt("deadEarthExposure",exposure);
        if (exposure<=-20) {
            player.setTicksFrozen(Math.max(player.getTicksFrozen(),140));
            player.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN,45,0,false,true));
            if (exposure<=-40 && level.getGameTime()%100==0) player.hurt(level.damageSources().freeze(),1);
        }
        if (exposure>=20) {
            player.addEffect(new MobEffectInstance(MobEffects.HUNGER,45,0,false,true));
            player.addEffect(new MobEffectInstance(MobEffects.DIG_SLOWDOWN,45,0,false,true));
            if(exposure>=40) player.igniteForSeconds(3);
        }
    }
}
