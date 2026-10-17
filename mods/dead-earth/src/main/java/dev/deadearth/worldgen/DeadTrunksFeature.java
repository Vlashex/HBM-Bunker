package dev.deadearth.worldgen;

import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.feature.*;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;

/** Sparse bare trunks: no leaves, foliage updates, ticking or adjacent chunk writes. */
public final class DeadTrunksFeature extends Feature<NoneFeatureConfiguration> {
    private record Log(BlockPos pos,BlockState state){}
    public DeadTrunksFeature(){super(NoneFeatureConfiguration.CODEC);}
    @Override public boolean place(FeaturePlaceContext<NoneFeatureConfiguration> context) {
        var level=context.level();var random=context.random();var settings=WastelandConfig.settings();
        if(random.nextFloat()>=settings.trunkChance())return false;
        int cx=Math.floorDiv(context.origin().getX(),16),cz=Math.floorDiv(context.origin().getZ(),16);
        if(!level.hasChunk(cx,cz)||WastelandFeature.protectedStructureChunk(level,cx,cz))return false;
        int x=cx*16+3+random.nextInt(10),z=cz*16+3+random.nextInt(10);
        int y=level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,x,z);
        int height=settings.trunkMin()+random.nextInt(settings.trunkMax()-settings.trunkMin()+1);
        if(y<=level.getMinBuildHeight()||y+height>=level.getMaxBuildHeight()||!WastelandFeature.natural(level.getBlockState(new BlockPos(x,y-1,z))))return false;
        var type=switch(random.nextInt(3)){case 0->Blocks.OAK_LOG;case 1->Blocks.SPRUCE_LOG;default->Blocks.DARK_OAK_LOG;};
        var logs=new ArrayList<Log>();for(int dy=0;dy<height;dy++)logs.add(new Log(new BlockPos(x,y+dy,z),type.defaultBlockState()));
        if(height>=5&&random.nextBoolean()) {
            var direction=Direction.from2DDataValue(random.nextInt(4));
            logs.add(new Log(new BlockPos(x,y+height-2,z).relative(direction),type.defaultBlockState().setValue(RotatedPillarBlock.AXIS,direction.getAxis())));
        }
        for(var log:logs)if(!level.ensureCanWrite(log.pos())||!level.isEmptyBlock(log.pos()))return false;
        for(var log:logs)level.setBlock(log.pos(),log.state(),Block.UPDATE_CLIENTS|Block.UPDATE_KNOWN_SHAPE);
        return true;
    }
}
