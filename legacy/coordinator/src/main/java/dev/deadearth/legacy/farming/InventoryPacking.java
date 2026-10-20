package dev.deadearth.legacy.farming;

import java.util.*;

/**
 * Copy-on-plan inventory packing: a capacity failure never mutates live slots or discards a
 * remainder.
 */
public final class InventoryPacking {
  private InventoryPacking() {}

  public interface Ops<T> {
    T copy(T stack);

    int count(T stack);

    void count(T stack, int count);

    int limit(T stack);

    boolean same(T a, T b);

    default boolean sameSeed(T a, T b) {
      return same(a, b);
    }

    T empty();
  }

  public static final class Result<T> {
    public final List<T> slots;
    public final T remainder;

    Result(List<T> s, T r) {
      slots = s;
      remainder = r;
    }
  }

  public static <T> Result<T> insert(List<T> source, T addition, Ops<T> ops) {
    List<T> slots = copy(source, ops);
    T left = ops.copy(addition);
    for (int pass = 0; pass < 2; pass++)
      for (int i = 0; i < slots.size() && ops.count(left) > 0; i++) {
        T old = slots.get(i);
        int n = ops.count(old);
        if (pass == 0 ? (n == 0 || !ops.same(old, left)) : n != 0) continue;
        int room = Math.max(0, Math.min(64, ops.limit(left)) - n),
            amount = Math.min(room, ops.count(left));
        if (amount == 0) continue;
        T merged = n == 0 ? ops.copy(left) : ops.copy(old);
        ops.count(merged, n + amount);
        slots.set(i, merged);
        ops.count(left, ops.count(left) - amount);
      }
    return new Result<T>(slots, left);
  }

  public static <T> List<T> all(List<T> slots, List<T> additions, Ops<T> ops) {
    List<T> planned = copy(slots, ops);
    for (T stack : additions) {
      Result<T> result = insert(planned, stack, ops);
      if (ops.count(result.remainder) > 0) return null;
      planned = result.slots;
    }
    return planned;
  }

  public static <T> List<T> copy(List<T> source, Ops<T> ops) {
    List<T> result = new ArrayList<T>();
    for (T stack : source) result.add(ops.copy(stack));
    return result;
  }

  public static <T> List<T> reserveSeed(List<T> drops, T seed, Ops<T> ops) {
    List<T> copy = copy(drops, ops);
    for (T stack : copy)
      if (ops.count(stack) > 0 && ops.sameSeed(stack, seed)) {
        ops.count(stack, ops.count(stack) - 1);
        break;
      }
    List<T> result = new ArrayList<T>();
    for (T stack : copy) if (ops.count(stack) > 0) result.add(stack);
    return result;
  }
}
