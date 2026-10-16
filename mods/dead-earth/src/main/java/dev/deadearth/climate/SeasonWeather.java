package dev.deadearth.climate;
import static dev.deadearth.config.DeadEarthConfig.*;

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

/** Bounded loaded-surface work, including a shared edit and changed-section budget. */
public final class SeasonWeather {
    private static final TagKey<Item> SUITS=TagKey.create(Registries.ITEM,ResourceLocation.parse("dead_earth:climate_protection"));
    private List<LevelChunk> loaded=List.of();
    private int chunkCursor,column;
    private long lastWeather=-1;
    private ClimateCycle.Phase lastPhase;

    public void tick(ServerLevel level,ClimateCycle.Phase phase,boolean online,long winterNumber) {
        if (!online) return;
        if(phase==ClimateCycle.Phase.ASH_WINTER)WinterCoating.selectWinter(level,winterNumber);
        long tick=level.getGameTime();
        if (b(WEATHER_CONTROL) && (lastPhase!=phase || tick-lastWeather>=i(WEATHER_REFRESH))) {
            if (phase==ClimateCycle.Phase.HEAT) level.setWeatherParameters(Math.max(i(WEATHER_LOCK),i(WEATHER_REFRESH)*2),0,false,false);
            else if (phase==ClimateCycle.Phase.ASH_WINTER) level.setWeatherParameters(0,Math.max(i(WEATHER_LOCK),i(WEATHER_REFRESH)*2),true,false);
            else if (lastPhase!=null && lastPhase!=phase) level.setWeatherParameters(Math.max(i(WEATHER_LOCK),i(WEATHER_REFRESH)*2),0,false,false);
            lastWeather=tick;lastPhase=phase;
        }
        if (tick%i(WEATHER_REFRESH)==0 || loaded.isEmpty()) {
            var fresh=new ArrayList<LevelChunk>();
            for(var holder:((ChunkMapAccess)level.getChunkSource().chunkMap).deadEarthLoadedChunks()) {
                var chunk=holder.getTickingChunk();
                if (chunk!=null && fresh.size()<i(MAX_WEATHER_CHUNKS)) fresh.add(chunk);
            }
            loaded=List.copyOf(fresh);
        }
        var budget=new WinterCoating.Budget(i(WEATHER_EDITS),i(WEATHER_SECTIONS));
        WinterCoating.drainActive(level,phase==ClimateCycle.Phase.ASH_WINTER&&b(WINTER_CRUST),budget);
        if (!loaded.isEmpty()) for(int i=0;i<i(SURFACE_COLUMNS)&&!budget.exhausted();i++) {
            if (chunkCursor>=loaded.size()) {chunkCursor=0;column=(column+1)&255;}
            var chunk=loaded.get(chunkCursor++);
            var holder=level.getChunkSource().chunkMap.getVisibleChunkIfPresent(chunk.getPos().toLong());
            if (holder==null || holder.getTickingChunk()!=chunk) continue;
            long key=chunk.getPos().toLong();int offset=(int)(key^(key>>>32));offset^=offset>>>16;offset*=0x45d9f3b;offset^=offset>>>16;
            int local=(column*73+offset)&255;
            int x=chunk.getPos().getMinBlockX()+(local&15),z=chunk.getPos().getMinBlockZ()+(local>>4);
            surfaceColumn(level,x,z,phase,budget);
        }
        if(tick%i(EXPOSURE_TICKS)==0) for(var player:level.getServer().getPlayerList().getPlayers()) expose(player,phase);
    }
    public static boolean surfaceColumn(ServerLevel level,int x,int z,ClimateCycle.Phase phase) {
        var budget=new WinterCoating.Budget(i(WEATHER_EDITS),i(WEATHER_SECTIONS));
        surfaceColumn(level,x,z,phase,budget);return budget.changes()>0;
    }
    private static void surfaceColumn(ServerLevel level,int x,int z,ClimateCycle.Phase phase,WinterCoating.Budget budget) {
        if(!WinterCoating.loaded(level,x,z,WinterCoating.maximumSideReach()+1))return;
        boolean winter=phase==ClimateCycle.Phase.ASH_WINTER;
        // Full geometry is anchored on original blocks. It also clears old thin crust.
        if(!winter||b(WINTER_CRUST))WinterCoating.column(level,x,z,winter,budget);
        if(budget.exhausted())return;
        var top=level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING,new BlockPos(x,0,z));
        var below=top.below();var state=level.getBlockState(below);
        if(winter) {
            if(b(WINTER_ICE)&&state.is(Blocks.WATER)&&state.getFluidState().getType()==Fluids.WATER&&state.getFluidState().isSource())
                budget.write(level,below,SeasonBlocks.ICE.get().defaultBlockState());
        } else {
            if(state.is(SeasonBlocks.ICE.get()))budget.write(level,below,Blocks.WATER.defaultBlockState());
            else if(phase==ClimateCycle.Phase.HEAT&&b(SUMMER_FIRE)&&level.isDay()&&level.getGameRules().getBoolean(GameRules.RULE_DOFIRETICK)
                    &&level.isEmptyBlock(top)&&!state.hasBlockEntity()&&state.isFlammable(level,below,net.minecraft.core.Direction.UP)) {
                var fire=BaseFireBlock.getState(level,top);
                if(fire.canSurvive(level,top))budget.write(level,top,fire);
            }
        }
    }
    public static void expose(ServerPlayer player,ClimateCycle.Phase phase) {
        var level=player.serverLevel();
        var data=player.getPersistentData();
        if (!b(EXPOSURE_ENABLED) || player.isCreative() || player.isSpectator() || !player.isAlive() || !level.dimension().equals(Level.OVERWORLD)) {
            data.remove("deadEarthExposure");data.remove("deadEarthColdDamageAt");return;
        }
        boolean suit=true;int pieces=0;
        for(var armor:player.getArmorSlots()) {pieces++;if(!armor.is(SUITS)) suit=false;}
        suit&=pieces==4;
        // Heightmap reacts immediately to a new roof; skylight propagation can lag behind.
        boolean outside=level.getHeight(Heightmap.Types.MOTION_BLOCKING,player.getBlockX(),player.getBlockZ())<=player.getBlockY()+1;
        int exposure=Exposure.step(data.getInt("deadEarthExposure"),phase,outside,level.isDay()&&!level.isRaining(),suit);
        data.putInt("deadEarthExposure",exposure);
        if (exposure<=-Math.min(i(SOFT_THRESHOLD),i(EXPOSURE_LIMIT))) {
            player.setTicksFrozen(Math.max(player.getTicksFrozen(),i(FREEZE_TICKS)));
            player.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN,i(POTION_TICKS),i(EXPOSURE_AMPLIFIER),false,true));
            if(exposure<=-Math.min(Math.max(i(SOFT_THRESHOLD),i(HARM_THRESHOLD)),i(EXPOSURE_LIMIT)) && d(EXPOSURE_DAMAGE)>0
                    && Exposure.damageDue(data.contains("deadEarthColdDamageAt")?data.getLong("deadEarthColdDamageAt"):-1,level.getGameTime(),i(DAMAGE_TICKS))) {
                data.putLong("deadEarthColdDamageAt",level.getGameTime());
                player.hurt(level.damageSources().freeze(),(float)d(EXPOSURE_DAMAGE));
            }
        }
        if (exposure>=Math.min(i(SOFT_THRESHOLD),i(EXPOSURE_LIMIT))) {
            player.addEffect(new MobEffectInstance(MobEffects.HUNGER,i(POTION_TICKS),i(EXPOSURE_AMPLIFIER),false,true));
            player.addEffect(new MobEffectInstance(MobEffects.DIG_SLOWDOWN,i(POTION_TICKS),i(EXPOSURE_AMPLIFIER),false,true));
            if(exposure>=Math.min(Math.max(i(SOFT_THRESHOLD),i(HARM_THRESHOLD)),i(EXPOSURE_LIMIT))) player.igniteForSeconds(i(IGNITE_SECONDS));
        }
    }
}
