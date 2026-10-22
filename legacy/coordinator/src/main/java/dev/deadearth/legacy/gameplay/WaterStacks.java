package dev.deadearth.legacy.gameplay;

import dev.deadearth.legacy.Reflect;

/** Only plain drinking water stacks; actual potions and custom-effect bottles keep one. */
public final class WaterStacks {
  private WaterStacks() {}
  public static int potionLimit(Object stack) {
    if (stack == null) return 1;
    Object tag = Reflect.call(stack, "getTagCompound/func_77978_p");
    if (tag == null) return 1;
    String potion = String.valueOf(Reflect.call(tag, "getString/func_74779_i", "Potion"));
    boolean effects = Boolean.TRUE.equals(Reflect.call(tag, "hasKey/func_74764_b", "CustomPotionEffects"));
    return !effects && ("minecraft:water".equals(potion) || "water".equals(potion)) ? 64 : 1;
  }
}
