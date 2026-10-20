package dev.deadearth.legacy.defense;

import com.google.gson.*;
import dev.deadearth.legacy.JsonFiles;
import java.nio.file.*;
import java.util.*;

/** Sparse bounded HP, checkpointed before the corresponding physical attack is accepted. */
public final class DefenseDamageStore {
  public static final class Entry {
    public String block;
    public int remaining;
    public long lastHostileTick;

    Entry(String b, int hp, long tick) {
      block = b;
      remaining = hp;
      lastHostileTick = tick;
    }
  }

  public static final class Hit {
    public final String key, block;
    public final int tier, amount;
    public final long tick;
    public final boolean hostile;

    public Hit(String k, String b, int t, int damage, long time, boolean mob) {
      key = k;
      block = b;
      tier = t;
      amount = damage;
      tick = time;
      hostile = mob;
    }
  }

  private long checkpoints;

  public long checkpoints() {
    return checkpoints;
  }

  private final Path path;
  private final int maximum;
  private final LinkedHashMap<String, Entry> entries = new LinkedHashMap<String, Entry>();

  public DefenseDamageStore(Path path, int maximum) {
    this.path = path;
    this.maximum = maximum;
    read();
  }

  public static int hp(int tier) {
    if (tier < 1 || tier > 3) throw new IllegalArgumentException("tier");
    return tier == 1 ? 1000 : tier == 2 ? 2000 : 8000;
  }

  public int size() {
    return entries.size();
  }

  public int remaining(String key, String block, int tier) {
    Entry e = entries.get(key);
    return e == null || !e.block.equals(block) ? hp(tier) : e.remaining;
  }

  public boolean hostileDue(String key, String block, long tick, int cooldown) {
    Entry e = entries.get(key);
    return e == null
        || !e.block.equals(block)
        || e.lastHostileTick == Long.MIN_VALUE
        || tick < e.lastHostileTick
        || tick - e.lastHostileTick >= cooldown;
  }

  /** One durable snapshot covers the entire batch BEFORE callers remove any terminal material. */
  public Map<String, Integer> damageBatch(Collection<Hit> hits, int cooldown) {
    Map<String, Integer> result = new LinkedHashMap<String, Integer>();
    boolean changed = false;
    for (Hit hit : hits) {
      if (hit.amount < 1) throw new IllegalArgumentException("damage");
      Entry e = entries.get(hit.key);
      if (e != null && !e.block.equals(hit.block)) {
        entries.remove(hit.key);
        e = null;
        changed = true;
      }
      if (e == null) {
        if (entries.size() >= maximum) {
          result.put(hit.key, hp(hit.tier));
          continue;
        }
        e = new Entry(hit.block, hp(hit.tier), Long.MIN_VALUE);
        entries.put(hit.key, e);
      }
      if (hit.hostile && !hostileDue(hit.key, hit.block, hit.tick, cooldown)) {
        result.put(hit.key, e.remaining);
        continue;
      }
      e.remaining = Math.max(0, e.remaining - hit.amount);
      if (hit.hostile) e.lastHostileTick = hit.tick;
      changed = true;
      result.put(hit.key, e.remaining);
    }
    if (changed) save();
    return result;
  }

  public int damage(
      String key, String block, int tier, int amount, long tick, int cooldown, boolean hostile) {
    return damageBatch(
            Collections.singletonList(new Hit(key, block, tier, amount, tick, hostile)), cooldown)
        .get(key);
  }

  public void removed(String key) {
    removedBatch(Collections.singletonList(key));
  }

  /** Terminal cleanup is one checkpoint, never one complete snapshot per exploded block. */
  public void removedBatch(Collection<String> keys) {
    boolean changed = false;
    for (String key : keys) changed |= entries.remove(key) != null;
    if (changed) save();
  }

  private void read() {
    if (!Files.exists(path)) return;
    try {
      JsonObject data = JsonFiles.read(path, 16 * 1024 * 1024);
      if (data.get("schema").getAsInt() != 1)
        throw new IllegalArgumentException("Unknown defense HP schema");
      JsonObject rows = data.getAsJsonObject("blocks");
      if (rows.entrySet().size() > maximum)
        throw new IllegalArgumentException("Defense damage limit exceeded");
      for (Map.Entry<String, JsonElement> row : rows.entrySet()) {
        if (!row.getKey().matches("-?[0-9]+:-?[0-9]+:[0-9]{1,3}:-?[0-9]+"))
          throw new IllegalArgumentException("Defense coordinate");
        JsonObject j = row.getValue().getAsJsonObject();
        String b = j.get("block").getAsString();
        if (!b.matches("dead_earth:defense_(wall|door|hatch)_v[123]"))
          throw new IllegalArgumentException("Defense block");
        int tier = Integer.parseInt(b.substring(b.length() - 1));
        int remaining = j.get("remaining").getAsInt();
        if (remaining < 0 || remaining > hp(tier)) throw new IllegalArgumentException("Defense HP");
        entries.put(row.getKey(), new Entry(b, remaining, j.get("lastHostileTick").getAsLong()));
      }
    } catch (Exception ex) {
      throw new IllegalStateException("Unreadable defense HP; original file preserved", ex);
    }
  }

  private void save() {
    JsonFiles.write(path, JsonFiles.map("schema", 1, "blocks", entries));
    checkpoints++;
  }
}
