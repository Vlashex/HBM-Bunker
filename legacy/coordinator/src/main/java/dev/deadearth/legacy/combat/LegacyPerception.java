package dev.deadearth.legacy.combat;

import dev.deadearth.legacy.DeadEarthLegacy;
import dev.deadearth.legacy.LegacyRuntime;
import dev.deadearth.legacy.Reflect;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.Event;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

/** Global mob-to-player sight, independent of Epic Siege's selective AI replacement scope. */
public final class LegacyPerception {
  private static SightSettings settings;
  private static boolean installed;

  private LegacyPerception() {}

  public static void configure(SightSettings value) {
    settings = value;
    if (!installed) {
      MinecraftForge.EVENT_BUS.register(new LegacyPerception());
      installed = true;
    }
  }

  /** Hook applies only on the logical server and only for living mobs looking at players. */
  public static boolean applies(Object observer, Object target) {
    return settings != null
        && settings.mode.equals("xray")
        && observer != null
        && target != null
        && Reflect.is(observer, "net.minecraft.entity.EntityLiving")
        && Reflect.is(target, "net.minecraft.entity.player.EntityPlayer")
        && !(Boolean) Reflect.get(Reflect.world(observer), "isRemote/field_72995_K");
  }

  /** No raytrace, terrain mutation, target assignment, or chunk loading. */
  public static boolean playerVisible(Object observer, Object target) {
    LegacyRuntime runtime = DeadEarthLegacy.runtime;
    if (settings == null || runtime == null || !runtime.onOwnerThread()) return false;
    Object world = Reflect.world(observer), targetWorld = Reflect.world(target);
    boolean sameWorld = world == targetWorld;
    if (!sameWorld) return false;
    Object caps = Reflect.get(target, "capabilities/field_71075_bZ");
    return PlayerVisibility.allowed(
        settings.radius,
        true,
        Reflect.loaded(world, Reflect.position(observer)),
        Reflect.loaded(world, Reflect.position(target)),
        (Boolean) Reflect.call(observer, "isEntityAlive/func_70089_S"),
        (Boolean) Reflect.call(target, "isEntityAlive/func_70089_S"),
        (Boolean) Reflect.call(target, "isSpectator/func_175149_v"),
        (Boolean) Reflect.get(caps, "isCreativeMode/field_75098_d"),
        settings.targetCreativePlayers,
        ((Number) Reflect.call(observer, "getDistanceSq/func_70068_e", target)).doubleValue());
  }

  /**
   * Keep all original target predicates: increasing FOLLOW_RANGE never creates a new attack goal.
   */
  @SubscribeEvent(priority = EventPriority.LOWEST)
  public void join(Event event) {
    if (settings == null
        || settings.mode.equals("native")
        || !event.getClass().getName().endsWith("EntityJoinWorldEvent")) return;
    Object world = Reflect.call(event, "getWorld"), entity = Reflect.call(event, "getEntity");
    if ((Boolean) Reflect.get(world, "isRemote/field_72995_K")
        || !Reflect.is(entity, "net.minecraft.entity.EntityLiving")) return;
    if (settings.mode.equals("glass")
        && !LegacySiege.configuredEntities().contains(Reflect.entityId(entity))) return;
    Object attribute =
        Reflect.call(
            entity,
            "getEntityAttribute/func_110148_a",
            Reflect.get(
                Reflect.type("net.minecraft.entity.SharedMonsterAttributes"),
                "FOLLOW_RANGE/field_111265_b"));
    if (attribute == null) return;
    double base = ((Number) Reflect.call(attribute, "getBaseValue/func_111125_b")).doubleValue();
    if (base < settings.radius)
      Reflect.call(attribute, "setBaseValue/func_111128_a", (double) settings.radius);
  }
}
