package dev.deadearth.content;

import dev.deadearth.DeadEarth;
import dev.deadearth.climate.ClimateCycle;
import dev.deadearth.climate.SeasonState;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CarpetBlock;
import net.minecraft.world.level.block.IceBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.DeferredBlock;

public final class SeasonBlocks {
    public static final DeferredRegister.Blocks BLOCKS=DeferredRegister.createBlocks(DeadEarth.MOD_ID);
    public static final DeferredBlock<Block> RIME=BLOCKS.register("rime_ice",()->new SeasonalCoatingBlock(
            BlockBehaviour.Properties.ofFullCopy(Blocks.ICE).noLootTable().noOcclusion().dynamicShape().randomTicks()));
    public static final DeferredBlock<Block> SNOW=BLOCKS.register("seasonal_snow",()->new SeasonalCoatingBlock(
            BlockBehaviour.Properties.ofFullCopy(Blocks.SNOW_BLOCK).noLootTable().noOcclusion().dynamicShape().randomTicks()));
    public static final DeferredBlock<Block> CRUST=BLOCKS.register("ice_crust",()->new CarpetBlock(
            BlockBehaviour.Properties.ofFullCopy(Blocks.WHITE_CARPET).strength(.2f).friction(.98f)
                    .sound(net.minecraft.world.level.block.SoundType.GLASS).noOcclusion().randomTicks()) {
        @Override protected void randomTick(BlockState state,ServerLevel level,BlockPos pos,RandomSource random) {
            if (SeasonState.phase()!=ClimateCycle.Phase.ASH_WINTER)dev.deadearth.climate.WinterCoating.requestThaw(level,pos);
        }
    });
    public static final DeferredBlock<Block> ICE=BLOCKS.register("seasonal_ice",()->new IceBlock(
            BlockBehaviour.Properties.ofFullCopy(Blocks.ICE).randomTicks()) {
        @Override protected void randomTick(BlockState state,ServerLevel level,BlockPos pos,RandomSource random) {
            if (SeasonState.phase()!=ClimateCycle.Phase.ASH_WINTER)dev.deadearth.climate.WinterCoating.requestThaw(level,pos);
        }
    });
    private SeasonBlocks() {}
    public static void register(IEventBus bus) { BLOCKS.register(bus); }
}
