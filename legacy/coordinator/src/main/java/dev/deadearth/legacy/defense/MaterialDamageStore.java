package dev.deadearth.legacy.defense;

import com.google.gson.*;
import dev.deadearth.legacy.JsonFiles;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

/** Durable ordinary-material HP. The cache bounds memory, never the number of damaged blocks. */
public final class MaterialDamageStore {
  private static final int MAX_BYTES = 16 * 1024 * 1024;
  private static final Pattern BLOCK =
      Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+(?:#([0-9]+))?");
  private static final Pattern POSITION =
      Pattern.compile("(?:[0-9]|1[0-5]):(?:0|[1-9][0-9]{0,2}):(?:[0-9]|1[0-5])");

  private static final class Entry {
    final String block;
    final int max, remaining;
    final long lastTick;

    Entry(String block, int maximum, int remaining, long tick) {
      this.block = block;
      this.max = maximum;
      this.remaining = remaining;
      this.lastTick = tick;
    }

    Entry maximum(int maximum) {
      if (max == maximum) return this;
      int scaled = (int) (((long) remaining * maximum + max - 1L) / max);
      return new Entry(block, maximum, scaled, lastTick);
    }
  }

  private final Path directory;
  private final Map<String, Map<String, Entry>> cache;

  public MaterialDamageStore(Path directory, final int cacheChunks) {
    if (directory == null || cacheChunks < 1) throw new IllegalArgumentException("Material HP cache");
    this.directory = directory.toAbsolutePath().normalize();
    this.cache = new LinkedHashMap<String, Map<String, Entry>>(16, 0.75f, true) {
      @Override
      protected boolean removeEldestEntry(
          Map.Entry<String, Map<String, MaterialDamageStore.Entry>> eldest) {
        return size() > cacheChunks;
      }
    };
  }

  public synchronized int remaining(int dimension, int x, int y, int z, String block, int maximum) {
    validate(y, block, maximum);
    Map<String, Entry> rows = load(dimension, x, z);
    String position = position(x, y, z);
    Entry previous = rows.get(position);
    if (previous == null) return maximum;
    if (!previous.block.equals(block)) {
      // Absence represents a fresh full block, with no previous attack cooldown.
      Map<String, Entry> changed = new LinkedHashMap<String, Entry>(rows);
      changed.remove(position);
      save(dimension, x, z, changed);
      return maximum;
    }
    Entry mapped = previous.maximum(maximum);
    if (mapped != previous) replace(dimension, x, z, rows, position, mapped);
    return mapped.remaining;
  }

  public synchronized int damage(int dimension, int x, int y, int z, String block, int maximum,
      int amount, long tick, int cooldown) {
    validate(y, block, maximum);
    if (amount < 1 || cooldown < 0) throw new IllegalArgumentException("Material HP damage/cooldown");
    Map<String, Entry> rows = load(dimension, x, z);
    String position = position(x, y, z);
    Entry previous = rows.get(position);
    Entry current;
    if (previous == null || !previous.block.equals(block)) {
      current = new Entry(block, maximum, maximum, tick);
    } else {
      current = previous.maximum(maximum);
      long elapsed = tick - current.lastTick;
      boolean due = tick < current.lastTick || elapsed < 0L || elapsed >= cooldown;
      if (!due) {
        if (current != previous) replace(dimension, x, z, rows, position, current);
        return current.remaining;
      }
    }
    int remaining = (int) Math.max(0L, (long) current.remaining - amount);
    replace(dimension, x, z, rows, position, new Entry(block, maximum, remaining, tick));
    return remaining;
  }

  public synchronized void removed(int dimension, int x, int y, int z) {
    validateY(y);
    Map<String, Entry> rows = load(dimension, x, z);
    String position = position(x, y, z);
    if (!rows.containsKey(position)) return;
    Map<String, Entry> changed = new LinkedHashMap<String, Entry>(rows);
    changed.remove(position);
    save(dimension, x, z, changed);
  }

  public synchronized void clearCache() {
    cache.clear();
  }

  private void replace(int dimension, int x, int z, Map<String, Entry> rows, String position,
      Entry value) {
    Map<String, Entry> changed = new LinkedHashMap<String, Entry>(rows);
    changed.put(position, value);
    save(dimension, x, z, changed);
  }

  private void save(int dimension, int x, int z, Map<String, Entry> changed) {
    Map<String, Object> snapshot = JsonFiles.map("schema", 1, "blocks", changed);
    if (JsonFiles.JSON.toJson(snapshot).getBytes(StandardCharsets.UTF_8).length > MAX_BYTES)
      throw new IllegalStateException("Material HP chunk exceeds readable checkpoint size");
    JsonFiles.write(file(dimension, x, z), snapshot);
    // Publish only after the real atomic checkpoint succeeds; entries are immutable.
    cache.put(chunk(dimension, x, z), changed);
  }

  private Map<String, Entry> load(int dimension, int x, int z) {
    String chunk = chunk(dimension, x, z);
    Map<String, Entry> found = cache.get(chunk);
    if (found != null) return found;
    Map<String, Entry> rows = new LinkedHashMap<String, Entry>();
    Path file = file(dimension, x, z);
    if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
      try {
        JsonObject data = JsonFiles.read(file, MAX_BYTES);
        fields(data, "schema", "blocks");
        if (number(data, "schema") != 1L) throw new IllegalArgumentException("Material HP schema");
        JsonElement blocks = data.get("blocks");
        if (blocks == null || !blocks.isJsonObject()) throw new IllegalArgumentException("Material HP blocks");
        for (Map.Entry<String, JsonElement> row : blocks.getAsJsonObject().entrySet()) {
          String key = row.getKey();
          if (!POSITION.matcher(key).matches() || Integer.parseInt(key.split(":")[1]) > 255)
            throw new IllegalArgumentException("Material HP position");
          if (!row.getValue().isJsonObject()) throw new IllegalArgumentException("Material HP entry");
          JsonObject value = row.getValue().getAsJsonObject();
          fields(value, "block", "max", "remaining", "lastTick");
          JsonElement id = value.get("block");
          if (id == null || !id.isJsonPrimitive() || !id.getAsJsonPrimitive().isString())
            throw new IllegalArgumentException("Material HP block");
          String block = id.getAsString();
          long maximum = number(value, "max"), remaining = number(value, "remaining");
          if (maximum < 1L || maximum > 1000000L || remaining < 0L || remaining > maximum)
            throw new IllegalArgumentException("Material HP range");
          validateBlock(block);
          rows.put(key, new Entry(block, (int) maximum, (int) remaining, number(value, "lastTick")));
        }
      } catch (Exception ex) {
        throw new IllegalStateException("Unreadable material HP chunk; original file preserved", ex);
      }
    }
    cache.put(chunk, rows);
    return rows;
  }

  private Path file(int dimension, int x, int z) {
    return directory.resolve(Integer.toString(dimension)).resolve((x >> 4) + "_" + (z >> 4) + ".json");
  }

  private static String chunk(int dimension, int x, int z) {
    return dimension + ":" + (x >> 4) + ":" + (z >> 4);
  }

  private static String position(int x, int y, int z) {
    return (x & 15) + ":" + y + ":" + (z & 15);
  }

  private static void validate(int y, String block, int maximum) {
    validateY(y);
    if (maximum < 1 || maximum > 1000000) throw new IllegalArgumentException("Material HP maximum");
    validateBlock(block);
  }

  private static void validateY(int y) {
    if (y < 0 || y > 255) throw new IllegalArgumentException("Material HP y");
  }

  private static void validateBlock(String block) {
    if (block == null) throw new IllegalArgumentException("Material HP block");
    Matcher match = BLOCK.matcher(block);
    if (!match.matches()) throw new IllegalArgumentException("Material HP block");
    if (match.group(1) != null) {
      try {
        if (Integer.parseInt(match.group(1)) > 32767) throw new IllegalArgumentException("Material HP metadata");
      } catch (NumberFormatException ex) {
        throw new IllegalArgumentException("Material HP metadata", ex);
      }
    }
  }

  private static long number(JsonObject object, String name) {
    JsonElement value = object.get(name);
    if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber())
      throw new IllegalArgumentException("Material HP integer: " + name);
    return value.getAsBigDecimal().longValueExact();
  }

  private static void fields(JsonObject object, String... names) {
    if (object.entrySet().size() != names.length) throw new IllegalArgumentException("Material HP fields");
    for (String name : names) {
      if (!object.has(name)) throw new IllegalArgumentException("Missing material HP field: " + name);
    }
  }
}
