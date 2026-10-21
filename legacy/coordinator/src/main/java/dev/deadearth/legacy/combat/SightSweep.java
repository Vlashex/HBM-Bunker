package dev.deadearth.legacy.combat;

import java.util.*;

/** Stable references prepared on the owner thread; removals in live lists cannot skip survivors. */
public final class SightSweep<T> {
  private final List<T> pending;
  private int cursor;

  public SightSweep(Collection<? extends T> loaded) {
    pending = new ArrayList<T>(loaded);
  }

  public boolean hasNext() {
    return cursor < pending.size();
  }

  public T next() {
    if (!hasNext()) throw new NoSuchElementException();
    T next = pending.get(cursor);
    pending.set(cursor++, null);
    return next;
  }
}
