package dev.deadearth.legacy.machinery;

import dev.deadearth.legacy.Reflect;
import java.util.*;

/** Native stack operations keep metadata/NBT and never turn HBM ore into invented dust. */
final class MachineStacks {
  private MachineStacks() {}

  static Object empty() {
    return Reflect.get(Reflect.type("net.minecraft.item.ItemStack"), "EMPTY/field_190927_a");
  }

  static boolean empty(Object s) {
    return s == null || (Boolean) Reflect.call(s, "isEmpty/func_190926_b");
  }

  static int count(Object s) {
    return empty(s) ? 0 : ((Number) Reflect.call(s, "getCount/func_190916_E")).intValue();
  }

  static Object copy(Object s) {
    return empty(s) ? empty() : Reflect.call(s, "copy/func_77946_l");
  }

  static Object count(Object s, int n) {
    if (n <= 0) return empty();
    Object c = copy(s);
    Reflect.call(c, "setCount/func_190920_e", n);
    return c;
  }

  static int max(Object s) {
    return Math.min(64, ((Number) Reflect.call(s, "getMaxStackSize/func_77976_d")).intValue());
  }

  static boolean same(Object a, Object b) {
    return !empty(a)
        && !empty(b)
        && (Boolean)
            Reflect.call(
                Reflect.type("net.minecraft.item.ItemStack"), "areItemsEqual/func_179545_c", a, b)
        && (Boolean)
            Reflect.call(
                Reflect.type("net.minecraft.item.ItemStack"),
                "areItemStackTagsEqual/func_77970_a",
                a,
                b);
  }

  static Object item(Object s) {
    return Reflect.call(s, "getItem/func_77973_b");
  }

  static Object block(Object s) {
    return Reflect.call(
        Reflect.type("net.minecraft.block.Block"), "getBlockFromItem/func_149634_a", item(s));
  }

  static boolean ore(Object s) {
    if (empty(s)) return false;
    Object b = block(s);
    if (b == null || "minecraft:air".equals(String.valueOf(Reflect.call(b, "getRegistryName"))))
      return false;
    int[] ids =
        (int[])
            Reflect.call(Reflect.type("net.minecraftforge.oredict.OreDictionary"), "getOreIDs", s);
    for (int id : ids) {
      String name =
          String.valueOf(
              Reflect.call(
                  Reflect.type("net.minecraftforge.oredict.OreDictionary"), "getOreName", id));
      if (name.startsWith("ore") && name.length() > 3) return true;
    }
    return false;
  }

  static Object write(Object s) {
    Object tag = Reflect.make("net.minecraft.nbt.NBTTagCompound");
    if (!empty(s)) Reflect.call(s, "writeToNBT/func_77955_b", tag);
    return tag;
  }

  static Object read(Object tag) {
    return Reflect.make("net.minecraft.item.ItemStack", tag);
  }

  /** Complete-capacity simulation; caller swaps the result only when every native output fits. */
  static List<Object> merge(List<Object> old, List<Object> additions) {
    List<Object> result = new ArrayList<Object>();
    for (Object s : old) result.add(copy(s));
    for (Object add : additions) {
      if (empty(add)) continue;
      int left = count(add);
      if (left < 1 || left > 4096) return null;
      for (int i = 0; i < result.size() && left > 0; i++) {
        Object have = result.get(i);
        if (empty(have) || !same(have, add)) continue;
        int n = Math.min(left, Math.max(0, max(have) - count(have)));
        if (n > 0) {
          result.set(i, count(have, count(have) + n));
          left -= n;
        }
      }
      for (int i = 0; i < result.size() && left > 0; i++)
        if (empty(result.get(i))) {
          int n = Math.min(left, max(add));
          if (n < 1) return null;
          result.set(i, count(add, n));
          left -= n;
        }
      if (left > 0) return null;
    }
    return result;
  }
}
