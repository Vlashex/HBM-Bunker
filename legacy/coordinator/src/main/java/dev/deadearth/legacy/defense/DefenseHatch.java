package dev.deadearth.legacy.defense;

import net.minecraft.block.BlockTrapDoor;
import net.minecraft.block.material.EnumPushReaction;
import net.minecraft.block.state.IBlockState;

/** Native trapdoor collision, half placement, hand opening and redstone. */
public final class DefenseHatch extends BlockTrapDoor implements DefenseMaterial {
  private final int tier;

  public DefenseHatch(int tier) {
    super(LegacyDefense.material(true));
    this.tier = tier;
    LegacyDefense.configure(this, "hatch", tier);
  }

  public int tier() {
    return tier;
  }

  public EnumPushReaction func_149656_h(IBlockState state) {
    return EnumPushReaction.BLOCK;
  }
}
