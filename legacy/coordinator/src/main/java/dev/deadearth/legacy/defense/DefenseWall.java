package dev.deadearth.legacy.defense;

import net.minecraft.block.Block;
import net.minecraft.block.material.EnumPushReaction;
import net.minecraft.block.state.IBlockState;

public final class DefenseWall extends Block implements DefenseMaterial {
  private final int tier;

  public DefenseWall(int tier) {
    super(LegacyDefense.material(false));
    this.tier = tier;
    LegacyDefense.configure(this, "wall", tier);
  }

  public int tier() {
    return tier;
  }

  public EnumPushReaction func_149656_h(IBlockState state) {
    return EnumPushReaction.BLOCK;
  }
}
