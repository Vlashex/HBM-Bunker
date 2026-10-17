package dev.deadearth.worldgen;

import dev.deadearth.content.WastelandContent;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/** Compatibility for native HBM generation predicates, not a global soil/ore tag. */
public final class WastelandGround {
    private WastelandGround(){}
    public static boolean accepts(BlockState state) {
        return state.is(WastelandContent.ASH.get())||state.is(Blocks.STONE)||state.is(Blocks.COBBLESTONE)||state.is(Blocks.ANDESITE)
            ||state.is(Blocks.TUFF)||state.is(Blocks.GRAVEL)||state.is(Blocks.BLACKSTONE)||state.is(Blocks.CRACKED_STONE_BRICKS);
    }
}
