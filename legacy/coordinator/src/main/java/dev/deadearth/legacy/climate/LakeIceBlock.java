package dev.deadearth.legacy.climate;

import dev.deadearth.legacy.Reflect;
import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;

/**
 * Exclusive ownership of one source-water cell. Its identity is the thaw receipt; native ice is
 * never claimed.
 */
public final class LakeIceBlock extends Block {
  public LakeIceBlock() {
    super(
        (Material)
            Reflect.get(
                Reflect.type("net.minecraft.block.material.Material"), "ICE/field_151588_w"));
    Reflect.call(this, "setRegistryName", "dead_earth", "season_lake_ice");
    Reflect.call(this, "setUnlocalizedName/func_149663_c", "dead_earth.season_lake_ice");
    Reflect.call(this, "setHardness/func_149711_c", .5f);
    Reflect.call(this, "setLightOpacity/func_149713_g", 3);
    Reflect.set(this, "slipperiness/field_149765_K", .98f);
  }

  public boolean func_149662_c(IBlockState state) {
    return false;
  }

  public net.minecraft.util.BlockRenderLayer func_180664_k() {
    return net.minecraft.util.BlockRenderLayer.TRANSLUCENT;
  }

  public int func_149745_a(java.util.Random random) {
    return 0;
  }
}
