package dev.deadearth.content;

import dev.deadearth.DeadEarth;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.*;

/** Inert scenery: ordinary solid block, no HBM radiation, ticking, fluid or block entity. */
public final class WastelandContent {
    private static final DeferredRegister.Blocks BLOCKS=DeferredRegister.createBlocks(DeadEarth.MOD_ID);
    private static final DeferredRegister.Items ITEMS=DeferredRegister.createItems(DeadEarth.MOD_ID);
    public static final DeferredBlock<Block> ASH=BLOCKS.register("ash",()->new Block(BlockBehaviour.Properties.ofFullCopy(Blocks.TUFF).strength(.6f).sound(SoundType.SAND)));
    public static final DeferredItem<BlockItem> ASH_ITEM=ITEMS.registerSimpleBlockItem(ASH);
    private WastelandContent(){}
    public static void register(IEventBus bus){BLOCKS.register(bus);ITEMS.register(bus);}
}
