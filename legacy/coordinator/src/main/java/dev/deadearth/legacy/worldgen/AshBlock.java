package dev.deadearth.legacy.worldgen;

import dev.deadearth.legacy.Reflect;
import net.minecraft.block.Block;
import net.minecraft.block.material.Material;

/** Own inert solid ash. No HBM hazard interface, random ticks, spreading or radiation. */
public final class AshBlock extends Block {
  public AshBlock() {
    super(
        (Material)
            Reflect.get(
                Reflect.type("net.minecraft.block.material.Material"), "GROUND/field_151578_c"));
    Reflect.call(this, "setRegistryName", "dead_earth", "ash");
    Reflect.call(this, "setUnlocalizedName/func_149663_c", "dead_earth.ash");
    Reflect.call(this, "setHardness/func_149711_c", .6f);
    Reflect.call(this, "setResistance/func_149752_b", 2f);
    Reflect.call(
        this,
        "setCreativeTab/func_149647_a",
        Reflect.get(
            Reflect.type("net.minecraft.creativetab.CreativeTabs"),
            "BUILDING_BLOCKS/field_78030_b"));
    Reflect.call(this, "setHarvestLevel", "shovel", 0);
  }
}
