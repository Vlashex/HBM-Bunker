package dev.deadearth.worldgen;

import dev.deadearth.content.WastelandContent;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.feature.*;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;

/** One bounded pass before native architectural features. It never visits another chunk. */
public final class WastelandFeature extends Feature<NoneFeatureConfiguration> {
    private record Write(BlockPos pos,BlockState state){}
    private static final Set<Block> NATURAL=Set.of(Blocks.DIRT,Blocks.COARSE_DIRT,Blocks.ROOTED_DIRT,Blocks.GRASS_BLOCK,Blocks.PODZOL,
        Blocks.MYCELIUM,Blocks.MUD,Blocks.CLAY,Blocks.SAND,Blocks.RED_SAND,Blocks.SANDSTONE,Blocks.RED_SANDSTONE,
        Blocks.TERRACOTTA,Blocks.ORANGE_TERRACOTTA,Blocks.WHITE_TERRACOTTA,Blocks.CALCITE,Blocks.GRAVEL,Blocks.COBBLESTONE,Blocks.MOSSY_COBBLESTONE,
        Blocks.BLACKSTONE,Blocks.CRACKED_STONE_BRICKS,Blocks.SNOW_BLOCK,Blocks.ICE,Blocks.PACKED_ICE);
    public WastelandFeature(){super(NoneFeatureConfiguration.CODEC);}
    static boolean natural(BlockState state) {
        return state.is(BlockTags.BASE_STONE_OVERWORLD)||NATURAL.contains(state.getBlock())||state.is(WastelandContent.ASH.get());
    }
    static boolean protectedStructureChunk(WorldGenLevel level,int cx,int cz) {
        // Starts were planned before decoration. References are already in this chunk: no neighbor lookup or force-load.
        var structures=level.registryAccess().registryOrThrow(Registries.STRUCTURE);
        return level.getChunk(cx,cz).getAllReferences().entrySet().stream().anyMatch(entry->{
            var id=structures.getKey(entry.getKey());return !entry.getValue().isEmpty()&&id!=null&&id.getNamespace().equals("hbm");
        });
    }
    private static BlockState material(WastelandSettings.Surface surface) {
        return (switch(surface) {
            case STONE->Blocks.STONE;case ASH->WastelandContent.ASH.get();case COBBLESTONE->Blocks.COBBLESTONE;
            case ANDESITE->Blocks.ANDESITE;case TUFF->Blocks.TUFF;case GRAVEL->Blocks.GRAVEL;
            case BLACKSTONE->Blocks.BLACKSTONE;case CRACKED_BRICKS->Blocks.CRACKED_STONE_BRICKS;
        }).defaultBlockState();
    }
    @Override public boolean place(FeaturePlaceContext<NoneFeatureConfiguration> context) {
        var level=context.level();int cx=Math.floorDiv(context.origin().getX(),16),cz=Math.floorDiv(context.origin().getZ(),16);
        if(!level.hasChunk(cx,cz))return false;
        var settings=WastelandConfig.settings();var craters=WastelandField.candidates(level.getSeed(),settings,cx,cz);
        boolean protect=protectedStructureChunk(level,cx,cz);var writes=new ArrayList<Write>();
        var materials=new EnumMap<WastelandSettings.Surface,BlockState>(WastelandSettings.Surface.class);
        for(var type:WastelandSettings.Surface.values())materials.put(type,material(type));
        for(int x=cx*16;x<cx*16+16;x++)for(int z=cz*16;z<cz*16+16;z++) {
            int ground=level.getHeight(Heightmap.Types.OCEAN_FLOOR,x,z)-1;
            if(ground<level.getMinBuildHeight()+settings.coatingDepth()+2||ground>=level.getMaxBuildHeight()-1)continue;
            var groundPos=new BlockPos(x,ground,z);if(!natural(level.getBlockState(groundPos)))continue;
            int surface=level.getHeight(Heightmap.Types.WORLD_SURFACE,x,z)-1;
            boolean submerged=surface>ground&&level.getBlockState(new BlockPos(x,surface,z)).is(Blocks.WATER);
            if(surface>ground&&!submerged)continue;
            int delta=protect?0:WastelandField.displacement(craters,x,z);
            int target=Math.max(level.getMinBuildHeight()+settings.coatingDepth()+2,Math.min(level.getMaxBuildHeight()-2,ground+delta));
            var coating=materials.get(WastelandField.surface(level.getSeed(),settings,x,z));var column=new ArrayList<Write>();boolean safe=true;
            for(int y=target-settings.coatingDepth()+1;y<=Math.max(ground,target);y++) {
                var pos=new BlockPos(x,y,z);var old=level.getBlockState(pos);
                if(!level.ensureCanWrite(pos)||old.hasBlockEntity()||(!old.isAir()&&!old.is(Blocks.WATER)&&!natural(old))){safe=false;break;}
                BlockState next;
                if(y>target)next=submerged?Blocks.WATER.defaultBlockState():Blocks.AIR.defaultBlockState();
                else if(y>ground||natural(old))next=coating;
                else continue; // Exposed caves remain open: do not create an artificial floating floor.
                if(!old.equals(next))column.add(new Write(pos,next));
            }
            if(safe)writes.addAll(column);
        }
        // Reject before the first write. Validated settings normally guarantee this whole-chunk bound.
        if(writes.size()>settings.maxWrites())throw new IllegalStateException("Wasteland whole-chunk write bound exceeded");
        for(var write:writes)level.setBlock(write.pos(),write.state(),Block.UPDATE_CLIENTS|Block.UPDATE_KNOWN_SHAPE);
        return !writes.isEmpty();
    }
}
