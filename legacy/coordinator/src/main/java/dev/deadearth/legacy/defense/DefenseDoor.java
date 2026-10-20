package dev.deadearth.legacy.defense;

import dev.deadearth.legacy.Reflect;
import java.util.Random;
import net.minecraft.block.BlockDoor;
import net.minecraft.block.material.EnumPushReaction;
import net.minecraft.block.state.IBlockState;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/** Native vanilla paired door geometry/placement/redstone, hand operable steel skin. */
public final class DefenseDoor extends BlockDoor implements DefenseMaterial {
  private final int tier;

  public DefenseDoor(int tier) {
    super(LegacyDefense.material(true));
    this.tier = tier;
    LegacyDefense.configure(this, "door", tier);
  }

  public int tier() {
    return tier;
  }

  public Item func_180660_a(IBlockState state, Random random, int fortune) {
    return (Item)
        Reflect.registry(
            "ITEMS", LegacyDefense.upper(state) ? "minecraft:air" : LegacyDefense.id("door", tier));
  }

  public ItemStack func_185473_a(World world, BlockPos pos, IBlockState state) {
    return (ItemStack)
        Reflect.make(
            "net.minecraft.item.ItemStack",
            Reflect.registry("ITEMS", LegacyDefense.id("door", tier)));
  }

  public EnumPushReaction func_149656_h(IBlockState state) {
    return EnumPushReaction.BLOCK;
  }
}
