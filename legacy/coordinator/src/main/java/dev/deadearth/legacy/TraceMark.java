package dev.deadearth.legacy;

import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.math.*;
import net.minecraft.world.IBlockAccess;

/**
 * Inert ground decal; one block per pattern, not an entity per pixel. No spreading, loot, or
 * radiation.
 */
public final class TraceMark extends Block {
  public TraceMark(String key) {
    super(
        (Material)
            Reflect.get(
                Reflect.type("net.minecraft.block.material.Material"), "CIRCUITS/field_151594_q"));
    Reflect.call(this, "setRegistryName", "dead_earth", "trace_" + key);
    Reflect.call(this, "setUnlocalizedName/func_149663_c", "dead_earth.trace_" + key);
    Reflect.call(this, "setHardness/func_149711_c", .1f);
  }

  public net.minecraft.util.BlockRenderLayer func_180664_k() {
    return net.minecraft.util.BlockRenderLayer.CUTOUT;
  }

  public boolean func_149662_c(IBlockState s) {
    return false;
  }

  public boolean func_149686_d(IBlockState s) {
    return false;
  }

  public AxisAlignedBB func_180640_a(IBlockState s, IBlockAccess w, BlockPos p) {
    return null;
  }

  public AxisAlignedBB func_185496_a(IBlockState s, IBlockAccess w, BlockPos p) {
    return new AxisAlignedBB(0, 0, 0, 1, .035, 1);
  }

  public int func_149745_a(java.util.Random random) {
    return 0;
  }
}
