package net.minecraftforge.event;
/** Exact Forge 2860 server descriptors; compilation boundary only, never packaged. */
public final class ForgeEventFactory {
  private ForgeEventFactory() {}
  public static boolean getMobGriefingEvent(
      net.minecraft.world.World world, net.minecraft.entity.Entity entity) {
    throw new UnsupportedOperationException("compile-only Forge API");
  }
  public static boolean onEntityDestroyBlock(
      net.minecraft.entity.EntityLivingBase entity,
      net.minecraft.util.math.BlockPos pos,
      net.minecraft.block.state.IBlockState state) {
    throw new UnsupportedOperationException("compile-only Forge API");
  }
}
