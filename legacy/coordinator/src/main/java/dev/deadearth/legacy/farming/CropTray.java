package dev.deadearth.legacy.farming;

import dev.deadearth.legacy.Reflect;
import net.minecraft.block.BlockContainer;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.*;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.World;

public final class CropTray extends BlockContainer {
  public CropTray() {
    super(
        (Material)
            Reflect.get(
                Reflect.type("net.minecraft.block.material.Material"), "ROCK/field_151576_e"));
    Reflect.call(this, "setRegistryName", "dead_earth", "crop_tray");
    Reflect.call(this, "setUnlocalizedName/func_149663_c", "dead_earth.crop_tray");
    Reflect.call(this, "setHardness/func_149711_c", 2f);
    Reflect.call(this, "setResistance/func_149752_b", 5f);
    Reflect.call(
        this,
        "setCreativeTab/func_149647_a",
        Reflect.get(
            Reflect.type("net.minecraft.creativetab.CreativeTabs"), "DECORATIONS/field_78031_c"));
  }

  public TileEntity func_149915_a(World world, int meta) {
    return new CropTrayTile();
  }

  public net.minecraft.util.EnumBlockRenderType func_149645_b(IBlockState state) {
    return net.minecraft.util.EnumBlockRenderType.MODEL;
  }

  public boolean func_149662_c(IBlockState state) {
    return false;
  }

  public boolean func_149686_d(IBlockState state) {
    return false;
  }

  public AxisAlignedBB func_185496_a(IBlockState state, IBlockAccess world, BlockPos pos) {
    return new AxisAlignedBB(0, 0, 0, 1, .5, 1);
  }

  @Override
  public AxisAlignedBB func_180640_a(IBlockState state, World world, BlockPos pos) {
    return new AxisAlignedBB(0, 0, 0, 1, .5, 1);
  }

  @Override
  public void func_180663_b(World world, BlockPos pos, IBlockState state) {
    if (!(Boolean) Reflect.get(world, "isRemote/field_72995_K")) {
      Object tile = Reflect.call(world, "getTileEntity/func_175625_s", pos);
      if (tile instanceof CropTrayTile) ((CropTrayTile) tile).dropContents(world, pos);
    }
    super.func_180663_b(world, pos, state);
  }
}
