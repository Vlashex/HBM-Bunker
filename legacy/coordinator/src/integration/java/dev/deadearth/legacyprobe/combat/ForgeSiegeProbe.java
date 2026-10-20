package dev.deadearth.legacyprobe.combat;

import dev.deadearth.legacy.JsonFiles;
import dev.deadearth.legacy.Reflect;
import dev.deadearth.legacy.combat.NativeSiegePermissions;
import java.lang.reflect.Method;
import java.util.Map;
import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockAccess;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.Event;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

/** Dedicated-server regression: real Forge permissions, cancellable events and block dispatch. */
public final class ForgeSiegeProbe {
  private ForgeSiegeProbe() {}

  public static Map<String, Object> verify(
      Object world, Object entity, Object pos, Object nativeAi, Method nativeCanHarvest)
      throws Exception {
    boolean clientAbsent = false;
    try {
      Class.forName(
          "net.minecraft.client.util.ITooltipFlag", false, ForgeSiegeProbe.class.getClassLoader());
    } catch (ClassNotFoundException expected) {
      clientAbsent = true;
    }
    require(clientAbsent, "regression runs without client-only ITooltipFlag");
    require(Reflect.loaded(world, pos), "native permission fixture is loaded");
    Object state = Reflect.state(world, pos);
    require(Reflect.blockId(world, pos).equals("minecraft:dirt"), "ordinary native dirt fixture");
    Object rules = Reflect.call(world, "getGameRules/func_82736_K");
    String previousRule = Reflect.call(rules, "getString/func_82767_a", "mobGriefing").toString();
    PermissionEvents listener = new PermissionEvents(entity);
    MinecraftForge.EVENT_BUS.register(listener);
    try {
      rule(rules, "true");
      require(NativeSiegePermissions.mobGriefing(world, entity), "native rule true preserved");
      int before = listener.griefingEvents;
      listener.griefing = Event.Result.DENY;
      require(
          !(Boolean) nativeCanHarvest.invoke(nativeAi, entity, pos),
          "actual transformed ESM task obeys Forge DENY");
      require(listener.griefingEvents == before + 1, "real ESM admission posted Forge event");
      listener.griefing = Event.Result.DEFAULT;
      rule(rules, "false");
      require(!NativeSiegePermissions.mobGriefing(world, entity), "native rule false preserved");
      listener.griefing = Event.Result.ALLOW;
      require(NativeSiegePermissions.mobGriefing(world, entity), "explicit Forge ALLOW preserved");
      listener.griefing = Event.Result.DEFAULT;
      rule(rules, "true");
      require(
          NativeSiegePermissions.destroyEvent(entity, pos, state),
          "actual uncancelled LivingDestroyBlockEvent allowed");
      listener.cancelDestroy = true;
      require(
          !NativeSiegePermissions.destroyEvent(entity, pos, state),
          "actual cancelled LivingDestroyBlockEvent refused");
      require(listener.destroyEvents == 2, "both native destruction calls posted real events");
      Object ordinary = Reflect.call(state, "getBlock/func_177230_c");
      require(
          NativeSiegePermissions.blockCanDestroy(ordinary, state, world, pos, entity),
          "native vanilla Block permission remains callable");
      RefusingBlock refusing = new RefusingBlock(Reflect.call(state, "getMaterial/func_185904_a"));
      require(
          !NativeSiegePermissions.blockCanDestroy(refusing, state, world, pos, entity)
              && refusing.calls == 1,
          "direct safe Block API still dispatches modded protection override");
      return JsonFiles.map(
          "case",
          "dedicated_server_forge_permissions",
          "passed",
          true,
          "clientTooltipClassAbsent",
          true,
          "nativeEsmCancellationObserved",
          true,
          "forgeGriefingEvents",
          listener.griefingEvents,
          "forgeDestroyEvents",
          listener.destroyEvents,
          "blockOverridePreserved",
          true);
    } finally {
      MinecraftForge.EVENT_BUS.unregister(listener);
      rule(rules, previousRule);
    }
  }

  private static void rule(Object rules, String value) {
    Reflect.call(rules, "setOrCreateGameRule/func_82764_b", "mobGriefing", value);
  }

  private static void require(boolean result, String message) {
    if (!result) throw new AssertionError(message);
  }

  public static final class PermissionEvents {
    final Object actor;
    Event.Result griefing = Event.Result.DEFAULT;
    boolean cancelDestroy;
    int griefingEvents, destroyEvents;

    PermissionEvents(Object actor) {
      this.actor = actor;
    }

    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
    public void event(Event event) {
      String name = event.getClass().getName();
      if (name.equals("net.minecraftforge.event.entity.EntityMobGriefingEvent")
          && Reflect.call(event, "getEntity") == actor) {
        griefingEvents++;
        event.setResult(griefing);
      } else if (name.equals("net.minecraftforge.event.entity.living.LivingDestroyBlockEvent")
          && Reflect.call(event, "getEntityLiving") == actor) {
        destroyEvents++;
        event.setCanceled(cancelDestroy);
      }
    }
  }

  private static final class RefusingBlock extends Block {
    int calls;

    RefusingBlock(Object material) {
      super((Material) material);
    }

    @Override
    public boolean canEntityDestroy(
        IBlockState state, IBlockAccess world, BlockPos pos, Entity entity) {
      calls++;
      return false;
    }
  }
}
