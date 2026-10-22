package dev.deadearth.legacy.defense;

import com.google.gson.JsonObject;
import dev.deadearth.legacy.JsonFiles;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Arrays;

/** Real temporary chunk files and JsonFiles, without loading a Minecraft world. */
public final class MaterialDamageChecks {
  private static int checks;
  private static Path root;

  private static void check(boolean condition, String message) {
    checks++;
    if (!condition) throw new AssertionError(message);
  }

  private static void equal(int expected, int actual, String message) {
    check(expected == actual, message + ": expected " + expected + ", got " + actual);
  }

  private static void rejected(Runnable operation, Class<? extends RuntimeException> type,
      String message) {
    checks++;
    try {
      operation.run();
    } catch (RuntimeException ex) {
      if (type.isInstance(ex)) return;
      throw new AssertionError(message + ": wrong exception", ex);
    }
    throw new AssertionError(message + ": accepted");
  }

  /* Reflect only to let the RED run execute and explain the absent implementation. */
  private static final class Store {
    private final Object value;
    private final Class<?> type;

    Store(Path directory, int cacheChunks) {
      try {
        type = Class.forName("dev.deadearth.legacy.defense.MaterialDamageStore");
        value = type.getConstructor(Path.class, int.class).newInstance(directory, cacheChunks);
      } catch (InvocationTargetException ex) {
        throw failure(ex.getCause());
      } catch (ReflectiveOperationException ex) {
        throw new AssertionError("Persistent ordinary-material HP store API is missing", ex);
      }
    }

    private Object call(String name, Class<?>[] parameters, Object... arguments) {
      try {
        return type.getMethod(name, parameters).invoke(value, arguments);
      } catch (InvocationTargetException ex) {
        throw failure(ex.getCause());
      } catch (ReflectiveOperationException ex) {
        throw new AssertionError("Material HP method is missing: " + name, ex);
      }
    }

    int remaining(int dim, int x, int y, int z, String block, int maximum) {
      return (Integer) call("remaining", new Class<?>[] {int.class, int.class, int.class,
          int.class, String.class, int.class}, dim, x, y, z, block, maximum);
    }

    int damage(int dim, int x, int y, int z, String block, int maximum, int amount,
        long tick, int cooldown) {
      return (Integer) call("damage", new Class<?>[] {int.class, int.class, int.class,
          int.class, String.class, int.class, int.class, long.class, int.class},
          dim, x, y, z, block, maximum, amount, tick, cooldown);
    }

    void removed(int dim, int x, int y, int z) {
      call("removed", new Class<?>[] {int.class, int.class, int.class, int.class}, dim, x, y, z);
    }

    void clearCache() { call("clearCache", new Class<?>[0]); }

    private static RuntimeException failure(Throwable error) {
      if (error instanceof Error) throw (Error) error;
      if (error instanceof RuntimeException) return (RuntimeException) error;
      return new IllegalStateException(error);
    }
  }

  private static void persistence() throws Exception {
    Path directory = root.resolve("persistence");
    Store store = new Store(directory, 2);
    equal(100, store.remaining(0, 1, 64, 2, "minecraft:stone", 100), "new block is full");
    equal(75, store.damage(0, 1, 64, 2, "minecraft:stone", 100, 25, 10L, 0), "first damage");
    Path file = directory.resolve("0/0_0.json");
    check(Files.isRegularFile(file), "damage is checkpointed before returning");
    JsonObject row = JsonFiles.read(file, 1048576).getAsJsonObject("blocks")
        .getAsJsonObject("1:64:2");
    check(row.get("block").getAsString().equals("minecraft:stone"), "stored block identity");
    equal(100, row.get("max").getAsInt(), "stored maximum");
    equal(75, row.get("remaining").getAsInt(), "stored remaining HP");
    check(row.get("lastTick").getAsLong() == 10L, "stored last accepted tick");
    equal(75, new Store(directory, 2).remaining(0, 1, 64, 2, "minecraft:stone", 100),
        "new process view keeps ordinary damage");
    store.clearCache();
    equal(75, store.remaining(0, 1, 64, 2, "minecraft:stone", 100), "clear cache keeps damage");
  }

  private static void coordinates() throws Exception {
    Path directory = root.resolve("coordinates");
    Store store = new Store(directory, 1);
    equal(72, store.damage(-1, -1, 0, -17, "mod:block", 80, 8, 1L, 0), "negative position");
    equal(50, store.damage(-1, -16, 0, -17, "mod:block", 80, 30, 1L, 0), "local x isolation");
    equal(60, store.damage(0, -1, 0, -17, "mod:block", 80, 20, 1L, 0), "dimension isolation");
    equal(70, store.damage(-1, 15, 0, -17, "mod:block", 80, 10, 1L, 0), "chunk isolation");
    equal(79, store.damage(2, Integer.MIN_VALUE, 255, Integer.MAX_VALUE,
        "mod:block/path#32767", 80, 1, 1L, 0), "integer coordinate boundaries");
    check(Files.isRegularFile(directory.resolve("-1/-1_-2.json")), "floor-divided negative chunk");
    check(Files.isRegularFile(directory.resolve("2/-134217728_134217727.json")),
        "extreme coordinate chunk file");
    Store reload = new Store(directory, 1);
    equal(72, reload.remaining(-1, -1, 0, -17, "mod:block", 80), "negative persisted HP");
    equal(50, reload.remaining(-1, -16, 0, -17, "mod:block", 80), "distinct local cell");
    equal(60, reload.remaining(0, -1, 0, -17, "mod:block", 80), "distinct persisted dimension");
    equal(70, reload.remaining(-1, 15, 0, -17, "mod:block", 80), "distinct persisted chunk");
  }

  private static void replacementAndMaximum() throws Exception {
    Path directory = root.resolve("replacement");
    Store store = new Store(directory, 2);
    equal(70, store.damage(0, 1, 64, 2, "mod:block#1", 100, 30, 100L, 20), "metadata one damaged");
    equal(100, store.remaining(0, 1, 64, 2, "mod:block#2", 100), "new metadata is a full block");
    Store reload = new Store(directory, 2);
    equal(100, reload.remaining(0, 1, 64, 2, "mod:block#1", 100), "replaced identity was cleared durably");
    equal(90, store.damage(0, 1, 64, 2, "mod:block#2", 100, 10, 100L, 20),
        "replacement does not inherit cooldown");
    equal(70, store.damage(0, 1, 64, 2, "other:block#2", 75, 5, 100L, 20), "new id resets maximum");
    equal(33, store.damage(0, 3, 64, 2, "mod:block", 100, 67, 100L, 20), "fraction fixture");
    equal(4, store.remaining(0, 3, 64, 2, "mod:block", 10), "maximum remap rounds fraction up");
    equal(4, new Store(directory, 2).remaining(0, 3, 64, 2, "mod:block", 10),
        "maximum remap persisted");
    equal(40, store.remaining(0, 3, 64, 2, "mod:block", 100), "larger maximum preserves fraction");
    equal(99, store.damage(0, 4, 64, 2, "mod:block", 100, 1, 100L, 20), "near-full fixture");
    equal(1, store.remaining(0, 4, 64, 2, "mod:block", 1), "remap can round to full");
    equal(1, store.damage(0, 4, 64, 2, "mod:block", 1, 1, 101L, 20),
        "rounding to full does not reset cooldown");
    equal(1000000, store.remaining(0, 5, 64, 2, "mod:block", 1000000), "maximum upper boundary");
  }

  private static void cooldown() throws Exception {
    Path directory = root.resolve("cooldown");
    Store store = new Store(directory, 2);
    equal(90, store.damage(0, 1, 64, 2, "mod:block", 100, 10, 100L, 20), "first hit due");
    equal(90, store.damage(0, 1, 64, 2, "mod:block", 100, 10, 100L, 20), "same-tick cooldown");
    equal(90, store.damage(0, 1, 64, 2, "mod:block", 100, 10, 119L, 20), "cooldown last blocked tick");
    equal(80, store.damage(0, 1, 64, 2, "mod:block", 100, 10, 120L, 20), "cooldown exact boundary");
    equal(90, store.damage(0, 2, 64, 2, "mod:block", 100, 10, 120L, 20), "cooldown is per block");
    Store reload = new Store(directory, 2);
    equal(80, reload.damage(0, 1, 64, 2, "mod:block", 100, 10, 121L, 20), "cooldown survives reload");
    equal(70, reload.damage(0, 1, 64, 2, "mod:block", 100, 10, 5L, 20), "backward clock permits hit");
    equal(70, reload.damage(0, 1, 64, 2, "mod:block", 100, 10, 6L, 20), "backward hit starts cooldown");
    equal(60, reload.damage(0, 1, 64, 2, "mod:block", 100, 10, 25L, 20), "cooldown after backward clock");
    equal(90, store.damage(0, 3, 64, 2, "mod:block", 100, 10, Long.MIN_VALUE, 1), "lowest tick first hit");
    equal(90, store.damage(0, 3, 64, 2, "mod:block", 100, 10, Long.MIN_VALUE, 1),
        "lowest tick is a real accepted tick, not a sentinel");
    equal(80, store.damage(0, 3, 64, 2, "mod:block", 100, 10, Long.MAX_VALUE, 1),
        "large positive elapsed ticks do not overflow cooldown");
    equal(80, store.damage(0, 3, 64, 2, "mod:block", 100, 10, Long.MAX_VALUE, 1), "highest same tick blocked");
  }

  private static void eviction() throws Exception {
    Path directory = root.resolve("eviction");
    Store store = new Store(directory, 1);
    for (int chunk = 0; chunk < 40; chunk++) {
      equal(90, store.damage(0, chunk * 16, 64, 0, "mod:block", 100, 10, 1L, 20),
          "cache bound must not grant invulnerability at chunk " + chunk);
    }
    store.clearCache();
    for (int chunk = 0; chunk < 40; chunk++) {
      equal(90, store.remaining(0, chunk * 16, 64, 0, "mod:block", 100),
          "evicted chunk keeps damage " + chunk);
    }
    Path lruDirectory = root.resolve("lru");
    Store lru = new Store(lruDirectory, 2);
    lru.damage(0, 0, 64, 0, "mod:block", 100, 10, 1L, 0);
    lru.damage(0, 16, 64, 0, "mod:block", 100, 10, 1L, 0);
    lru.remaining(0, 0, 64, 0, "mod:block", 100);
    lru.damage(0, 32, 64, 0, "mod:block", 100, 10, 1L, 0);
    write(lruDirectory.resolve("0/1_0.json"), validRow("mod:block", "100", "37", "1"));
    equal(37, lru.remaining(0, 16, 64, 0, "mod:block", 100), "least recently used chunk reloads");
  }

  private static void terminalAndRemoval() throws Exception {
    Path directory = root.resolve("terminal");
    Store store = new Store(directory, 1);
    equal(0, store.damage(0, 0, 64, 0, "mod:block", 100, Integer.MAX_VALUE, 1L, 20),
        "large damage clamps at zero");
    Store reload = new Store(directory, 1);
    equal(0, reload.remaining(0, 0, 64, 0, "mod:block", 100), "terminal zero survives reload");
    equal(0, reload.remaining(0, 0, 64, 0, "mod:block", 1000000), "terminal zero survives maximum change");
    equal(0, reload.damage(0, 0, 64, 0, "mod:block", 1000000, 1, 2L, 20), "cooldown cannot revive zero");
    reload.removed(0, 0, 64, 0);
    equal(100, new Store(directory, 1).remaining(0, 0, 64, 0, "mod:block", 100), "removal checkpointed");
    reload.removed(0, 0, 64, 0);
  }

  private static String validRow(String block, String maximum, String remaining, String tick) {
    return "{\"schema\":1,\"blocks\":{\"0:64:0\":{\"block\":\"" + block
        + "\",\"max\":" + maximum + ",\"remaining\":" + remaining
        + ",\"lastTick\":" + tick + "}}}";
  }

  private static void write(Path file, String text) throws Exception {
    Files.createDirectories(file.getParent());
    Files.write(file, text.getBytes(StandardCharsets.UTF_8));
  }

  private static void corruptFiles() throws Exception {
    String valid = validRow("mod:block", "100", "80", "1");
    String[] malformed = {"{broken", valid + " true", valid.replace("\"schema\":1", "\"schema\":2"),
        valid.replace("0:64:0", "0:256:0"), valid.replace("0:64:0", "16:64:0"),
        valid.replace("0:64:0", "00:64:0"), validRow("bad", "100", "80", "1"),
        validRow("mod:block#32768", "100", "80", "1"), validRow("mod:block", "0", "0", "1"),
        validRow("mod:block", "1000001", "80", "1"), validRow("mod:block", "100", "101", "1"),
        validRow("mod:block", "100", "-1", "1"), validRow("mod:block", "100", "80.5", "1"),
        validRow("mod:block", "100", "80", "1.5"), validRow("mod:block", "100", "80", "\"1\""),
        valid.replace("\"schema\":1", "\"schema\":1,\"schema\":1"),
        valid.replace("\"lastTick\":1", "\"lastTick\":1,\"future\":1")};
    for (int i = 0; i < malformed.length; i++) {
      Path directory = root.resolve("corrupt-" + i);
      Path file = directory.resolve("0/0_0.json");
      write(file, malformed[i]);
      final Store store = new Store(directory, 1);
      final byte[] original = Files.readAllBytes(file);
      rejected(() -> store.remaining(0, 0, 64, 0, "mod:block", 100), IllegalStateException.class,
          "malformed existing chunk rejected " + i);
      rejected(() -> store.damage(0, 0, 64, 0, "mod:block", 100, 1, 1L, 0),
          IllegalStateException.class, "damage cannot replace malformed chunk " + i);
      rejected(() -> store.removed(0, 0, 64, 0), IllegalStateException.class,
          "removal cannot replace malformed chunk " + i);
      check(Arrays.equals(original, Files.readAllBytes(file)), "original malformed bytes preserved " + i);
    }
  }

  private static void failedSaves() throws Exception {
    Path directory = root.resolve("failed-save");
    final Store store = new Store(directory, 1);
    equal(90, store.damage(0, 0, 64, 0, "mod:block", 100, 10, 100L, 20), "save-failure fixture");
    Path file = directory.resolve("0/0_0.json");
    Path backup = directory.resolve("original.json");
    Files.move(file, backup);
    Files.createDirectory(file);
    Path blocker = file.resolve("blocker");
    Files.write(blocker, new byte[] {1});
    rejected(() -> store.damage(0, 0, 64, 0, "mod:block", 100, 10, 120L, 20),
        IllegalStateException.class, "real JsonFiles write failure rejects damage");
    equal(90, store.remaining(0, 0, 64, 0, "mod:block", 100), "failed damage leaves cached HP unchanged");
    rejected(() -> store.removed(0, 0, 64, 0), IllegalStateException.class, "failed removal rejected");
    equal(90, store.remaining(0, 0, 64, 0, "mod:block", 100), "failed removal leaves cache unchanged");
    rejected(() -> store.remaining(0, 0, 64, 0, "mod:block", 200), IllegalStateException.class,
        "failed maximum remap rejected");
    equal(90, store.remaining(0, 0, 64, 0, "mod:block", 100), "failed remap keeps old maximum and HP");
    rejected(() -> store.remaining(0, 0, 64, 0, "other:block", 100), IllegalStateException.class,
        "failed identity replacement rejected");
    equal(90, store.remaining(0, 0, 64, 0, "mod:block", 100), "failed replacement keeps old block");
    Files.delete(blocker);
    Files.delete(file);
    Files.move(backup, file);
    equal(80, store.damage(0, 0, 64, 0, "mod:block", 100, 10, 120L, 20),
        "failed damage did not consume lastTick cooldown");
    equal(80, new Store(directory, 1).remaining(0, 0, 64, 0, "mod:block", 100), "retry durably saved");
  }

  private static void validation() throws Exception {
    Path directory = root.resolve("validation");
    rejected(() -> new Store(directory, 0), IllegalArgumentException.class, "empty cache invalid");
    final Store store = new Store(directory, 1);
    rejected(() -> store.remaining(0, 0, -1, 0, "mod:block", 100), IllegalArgumentException.class, "negative y");
    rejected(() -> store.removed(0, 0, 256, 0), IllegalArgumentException.class, "y above world");
    rejected(() -> store.remaining(0, 0, 0, 0, "mod:block", 0), IllegalArgumentException.class, "zero maximum");
    rejected(() -> store.remaining(0, 0, 0, 0, "mod:block", 1000001), IllegalArgumentException.class, "huge maximum");
    for (final String block : new String[] {"stone", "Mod:block", "mod:block#-1", "mod:block#32768", "mod:block#"}) {
      rejected(() -> store.remaining(0, 0, 0, 0, block, 100), IllegalArgumentException.class, "bad block token " + block);
    }
    rejected(() -> store.damage(0, 0, 0, 0, "mod:block", 100, 0, 1L, 0), IllegalArgumentException.class, "zero damage");
    rejected(() -> store.damage(0, 0, 0, 0, "mod:block", 100, 1, 1L, -1), IllegalArgumentException.class, "negative cooldown");
    check(!Files.exists(directory), "invalid input does not create files");
  }

  public static void main(String[] args) throws Exception {
    root = Paths.get(args[0]);
    Files.createDirectories(root);
    persistence();
    coordinates();
    replacementAndMaximum();
    cooldown();
    eviction();
    terminalAndRemoval();
    corruptFiles();
    failedSaves();
    validation();
    System.out.println("Material damage checks passed: " + checks);
  }
}
