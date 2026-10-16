package dev.deadearth.content;

import dev.deadearth.climate.ClimateCycle;
import dev.deadearth.climate.SeasonState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/** Air-origin rime/snow: grows from an anchor face and always thaws to air. */
public final class SeasonalCoatingBlock extends Block {
    public static final DirectionProperty FACING=BlockStateProperties.FACING;
    public static final IntegerProperty LAYERS=BlockStateProperties.LAYERS;
    private static final VoxelShape[][] SHAPES=new VoxelShape[6][9];
    static {
        for(var d:Direction.values()) for(int n=1;n<=8;n++) {
            int h=n*2;
            SHAPES[d.ordinal()][n]=switch(d) {
                case UP -> Block.box(0,0,0,16,h,16);
                case DOWN -> Block.box(0,16-h,0,16,16,16);
                case EAST -> Block.box(0,0,0,h,16,16);
                case WEST -> Block.box(16-h,0,0,16,16,16);
                case SOUTH -> Block.box(0,0,0,16,16,h);
                case NORTH -> Block.box(0,0,16-h,16,16,16);
            };
        }
    }
    public SeasonalCoatingBlock(Properties p) {
        super(p);registerDefaultState(stateDefinition.any().setValue(FACING,Direction.UP).setValue(LAYERS,1));
    }
    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block,BlockState> b) {b.add(FACING,LAYERS);}
    @Override protected VoxelShape getShape(BlockState s,BlockGetter l,BlockPos p,CollisionContext c) {return SHAPES[s.getValue(FACING).ordinal()][s.getValue(LAYERS)];}
    @Override protected boolean useShapeForLightOcclusion(BlockState s) {return true;}
    @Override protected boolean skipRendering(BlockState s,BlockState adjacent,Direction face) {
        if(isCoating(adjacent)&&adjacent.getValue(LAYERS)==8
                &&(adjacent.is(s.getBlock())||adjacent.is(SeasonBlocks.SNOW.get())))return true;
        return super.skipRendering(s,adjacent,face);
    }
    // The weather scheduler owns ordinary thaw. Random ticks only schedule a bounded
    // cleanup request; they never write outside the shared per-tick weather budget.
    @Override protected void randomTick(BlockState s,ServerLevel l,BlockPos p,RandomSource r) {
        if(SeasonState.phase()!=ClimateCycle.Phase.ASH_WINTER)dev.deadearth.climate.WinterCoating.requestThaw(l,p);
        else if(s.getValue(LAYERS)<8)dev.deadearth.climate.WinterCoating.requestColumn(l,p.getX(),p.getZ());
    }
    public static boolean isCoating(BlockState s) {return s.getBlock() instanceof SeasonalCoatingBlock;}
    public static BlockState thawStep(ServerLevel level,BlockPos pos,BlockState s) {
        var outside=level.getBlockState(pos.relative(s.getValue(FACING)));
        if(isCoating(outside)&&outside.getValue(FACING)==s.getValue(FACING)) return s;
        int layers=s.getValue(LAYERS);
        int step=dev.deadearth.config.DeadEarthConfig.i(dev.deadearth.config.DeadEarthConfig.COATING_STEP);
        return layers<=step?Blocks.AIR.defaultBlockState():s.setValue(LAYERS,layers-step);
    }
}
