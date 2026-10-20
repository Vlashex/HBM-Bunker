package dev.deadearth.legacy.combat;

import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.World;
import net.minecraftforge.event.ForgeEventFactory;

/**
 * Exact logical-server APIs. Never enumerate ForgeEventFactory: unrelated tooltip methods reference
 * client-only classes which are absent on a dedicated server. Direct calls preserve Forge events
 * and virtual Block overrides without resolving unrelated signatures.
 */
public final class NativeSiegePermissions {
  private NativeSiegePermissions() {}

  public static boolean mobGriefing(Object world, Object entity) {
    return ForgeEventFactory.getMobGriefingEvent((World) world, (Entity) entity);
  }

  public static boolean blockCanDestroy(
      Object block, Object state, Object world, Object pos, Object entity) {
    return ((Block) block)
        .canEntityDestroy(
            (IBlockState) state, (IBlockAccess) world, (BlockPos) pos, (Entity) entity);
  }

  public static boolean destroyEvent(Object entity, Object pos, Object state) {
    return ForgeEventFactory.onEntityDestroyBlock(
        (EntityLivingBase) entity, (BlockPos) pos, (IBlockState) state);
  }
}
