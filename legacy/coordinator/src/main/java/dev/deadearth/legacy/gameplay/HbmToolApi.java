package dev.deadearth.legacy.gameplay;

import dev.deadearth.legacy.Reflect;
import java.lang.invoke.*;

/** Exact server-safe HBM calls: never enumerate an ability tool's client-only signatures. */
public final class HbmToolApi {
  private HbmToolApi() {}

  private static final Class<?> TOOL = Reflect.type("com.hbm.items.tool.ItemToolAbility");
  private static final Class<?> STACK = Reflect.type("net.minecraft.item.ItemStack");
  private static final Class<?> CONFIG =
      Reflect.type("com.hbm.items.tool.ItemToolAbility$Configuration");
  private static final MethodHandle ADD =
      virtual(
          TOOL,
          "addAbility",
          TOOL,
          Reflect.type("com.hbm.handler.ability.IBaseAbility"),
          int.class);
  private static final MethodHandle GET = virtual(TOOL, "getConfiguration", CONFIG, STACK);
  private static final MethodHandle SET =
      virtual(TOOL, "setConfiguration", void.class, STACK, CONFIG);
  private static final Class<?> ITEM = Reflect.type("net.minecraft.item.Item");
  private static final MethodHandle NAME =
      named(ITEM, "func_77655_b", "setUnlocalizedName", ITEM, String.class);
  private static final MethodHandle TAB =
      named(
          ITEM,
          "func_77637_a",
          "setCreativeTab",
          ITEM,
          Reflect.type("net.minecraft.creativetab.CreativeTabs"));
  private static final Class<?> BATTERY = Reflect.type("com.hbm.api.energymk2.IBatteryItem");
  private static final MethodHandle CHARGE = virtual(BATTERY, "getCharge", long.class, STACK);
  private static final MethodHandle MAX = virtual(BATTERY, "getMaxCharge", long.class, STACK);
  private static final MethodHandle RATE = virtual(BATTERY, "getChargeRate", long.class, STACK);
  private static final MethodHandle ACCEPT =
      virtual(BATTERY, "chargeBattery", void.class, STACK, long.class);
  private static final MethodHandle SET_CHARGE =
      virtual(BATTERY, "setCharge", void.class, STACK, long.class);

  private static MethodHandle virtual(
      Class<?> owner, String name, Class<?> result, Class<?>... parameters) {
    try {
      return MethodHandles.publicLookup()
          .findVirtual(owner, name, MethodType.methodType(result, parameters));
    } catch (ReflectiveOperationException failure) {
      throw Reflect.fail(owner.getName() + "." + name, failure);
    }
  }

  private static MethodHandle named(
      Class<?> owner, String srg, String mcp, Class<?> result, Class<?>... parameters) {
    try {
      return MethodHandles.publicLookup()
          .findVirtual(owner, srg, MethodType.methodType(result, parameters));
    } catch (NoSuchMethodException missing) {
      return virtual(owner, mcp, result, parameters);
    } catch (IllegalAccessException failure) {
      throw Reflect.fail(owner.getName() + "." + srg, failure);
    }
  }

  private static Object invoke(MethodHandle handle, Object... args) {
    try {
      return handle.invokeWithArguments(args);
    } catch (Throwable failure) {
      throw Reflect.fail("Exact HBM tool API", failure);
    }
  }

  public static void configure(Object item, Object flat, int level, String name, Object tab) {
    invoke(ADD, item, flat, level);
    invoke(NAME, item, name);
    invoke(TAB, item, tab);
  }

  public static Object configuration(Object item, Object stack) {
    return invoke(GET, item, stack);
  }

  public static void configuration(Object item, Object stack, Object value) {
    invoke(SET, item, stack, value);
  }

  public static long charge(Object item, Object stack) {
    return ((Number) invoke(CHARGE, item, stack)).longValue();
  }

  public static long maximum(Object item, Object stack) {
    return ((Number) invoke(MAX, item, stack)).longValue();
  }

  public static long rate(Object item, Object stack) {
    return ((Number) invoke(RATE, item, stack)).longValue();
  }

  public static void accept(Object item, Object stack, long amount) {
    invoke(ACCEPT, item, stack, amount);
  }

  public static void setCharge(Object item, Object stack, long amount) {
    invoke(SET_CHARGE, item, stack, amount);
  }
}
