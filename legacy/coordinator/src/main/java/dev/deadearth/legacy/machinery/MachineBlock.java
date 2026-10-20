package dev.deadearth.legacy.machinery;

import dev.deadearth.legacy.Reflect;
import net.minecraft.block.BlockContainer;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

final class MachineBlock extends BlockContainer {
  private final boolean processor;

  MachineBlock(boolean processor) {
    super(
        (Material)
            Reflect.get(
                Reflect.type("net.minecraft.block.material.Material"), "IRON/field_151573_f"));
    this.processor = processor;
    String id = processor ? "fortune_processor" : "charger";
    Reflect.call(this, "setRegistryName", "dead_earth", id);
    Reflect.call(this, "setUnlocalizedName/func_149663_c", "dead_earth." + id);
    Reflect.call(this, "setHardness/func_149711_c", 3f);
    Reflect.call(this, "setResistance/func_149752_b", 5f);
    Reflect.call(this, "setHarvestLevel", "pickaxe", 1);
    Reflect.call(
        this,
        "setCreativeTab/func_149647_a",
        Reflect.get(
            Reflect.type("net.minecraft.creativetab.CreativeTabs"), "REDSTONE/field_78028_d"));
  }

  public net.minecraft.util.EnumBlockRenderType func_149645_b(IBlockState state) {
    return net.minecraft.util.EnumBlockRenderType.MODEL;
  }

  public TileEntity func_149915_a(World world, int meta) {
    return processor ? new MachineTile.Processor() : new MachineTile.Charger();
  }

  public void func_180663_b(World world, BlockPos pos, IBlockState state) {
    if (!(Boolean) Reflect.get(world, "isRemote/field_72995_K")) {
      Object tile = Reflect.call(world, "getTileEntity/func_175625_s", pos);
      if (tile instanceof MachineTile) ((MachineTile) tile).spill();
    }
    // BlockContainer's native break contract: remove the tile after its items have been emitted
    // once.
    Reflect.call(world, "removeTileEntity/func_175713_t", pos);
  }
}
