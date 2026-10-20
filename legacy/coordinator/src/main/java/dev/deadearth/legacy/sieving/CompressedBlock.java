package dev.deadearth.legacy.sieving;

import dev.deadearth.legacy.Reflect;
import net.minecraft.block.Block;
import net.minecraft.block.material.Material;

/**
 * Stable placeable storage block; it never becomes falling sand or receives an invented sieve
 * multiplier.
 */
final class CompressedBlock extends Block {
  private final boolean leaves;

  CompressedBlock(String id, int depth) {
    super(
        (Material)
            Reflect.get(
                Reflect.type("net.minecraft.block.material.Material"), "ROCK/field_151576_e"));
    leaves = id.contains("_leaves_");
    LegacySieving.configure(this, id, 2f + depth);
    Reflect.call(this, "setHarvestLevel", "pickaxe", 0);
  }

  public boolean func_149662_c(net.minecraft.block.state.IBlockState state) {
    return !leaves;
  }

  @net.minecraftforge.fml.relauncher.SideOnly(net.minecraftforge.fml.relauncher.Side.CLIENT)
  public net.minecraft.util.BlockRenderLayer getBlockLayer() {
    return leaves
        ? net.minecraft.util.BlockRenderLayer.CUTOUT_MIPPED
        : net.minecraft.util.BlockRenderLayer.SOLID;
  }
}
