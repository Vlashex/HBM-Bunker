package dev.deadearth.legacy.climate;

import java.util.*;

/**
 * Final-stage JVM checks. Intentionally authored without execution during the implementation stage.
 */
public final class ClimateChecks {
  private static int checks;

  private static void check(boolean value) {
    checks++;
    if (!value) throw new AssertionError("Climate check " + checks);
  }

  public static void main(String[] args) {
    ClimateConfig c = new ClimateConfig();
    c.validate();
    long seed = WinterField.seed(1234, 2);
    check(seed != WinterField.seed(1234, 3));
    for (int x = -128; x <= 128; x++) {
      WinterField.Profile a = WinterField.sample(seed, x, -17, c),
          b = WinterField.sample(seed, x, -17, c);
      check(a.topIce == b.topIce && a.peak == b.peak && a.sideIce == b.sideIce);
      check(a.top() <= c.maximumTop() && a.side() <= c.maximumSide());
      check(a.topIce >= c.topIce && a.sideIce >= c.sideIce);
    }
    ClimateBudget b = new ClimateBudget(3, 2, 1);
    check(b.read() && b.read() && b.read() && !b.read() && b.reads == 3);
    check(b.canWrite(0, 0, 70, 0));
    b.written(0, 0, 70, 0);
    check(!b.canWrite(0, 16, 70, 0) && !b.canWrite(1, 0, 70, 0) && b.canWrite(0, 1, 70, 0));
    b.written(0, 1, 70, 0);
    check(!b.canWrite(0, 2, 70, 0) && b.changes == 2 && b.sections() == 1);
    check(ExposurePolicy.step(0, 2, true, false, false, c) == -c.coldGain);
    check(ExposurePolicy.step(0, 1, true, true, false, c) == c.heatGain);
    check(ExposurePolicy.step(-20, 2, true, false, true, c) == -17);
    check(!ExposurePolicy.damageDue(10, 109, 100) && ExposurePolicy.damageDue(10, 110, 100));
    Fake world = new Fake();
    world.set(0, 64, 0, CoatingEngine.Cell.NATURAL);
    c.topIce = 2;
    c.topSnow = 1;
    c.sideIce = 2;
    c.sideSnow = 1;
    c.iceRelief = 0;
    c.snowRelief = 0;
    c.sideRelief = 0;
    c.peakChance = 0;
    c.operationClearance = 1;
    CoatingEngine e = new CoatingEngine(c);
    CoatingEngine.Column top = new CoatingEngine.Column(0, 0);
    for (int pass = 0; pass < 30; pass++)
      e.column(world, top, true, seed, new ClimateBudget(100000, 512, 64));
    check(world.get(0, 65, 0).own && world.get(0, 66, 0).own && world.get(0, 67, 0).own);
    check(!world.get(0, 68, 0).own && world.get(0, 67, 0).snow);
    CoatingEngine.Column side = new CoatingEngine.Column(1, 0);
    for (int pass = 0; pass < 20; pass++)
      e.column(world, side, true, seed, new ClimateBudget(100000, 512, 64));
    check(world.get(1, 64, 0).own);
    world.set(0, 66, 0, CoatingEngine.Cell.NATURAL); // A player's replacement must survive thaw.
    for (int pass = 0; pass < 20; pass++)
      e.column(world, top, false, seed, new ClimateBudget(100000, 512, 64));
    check(
        world.get(0, 64, 0) == CoatingEngine.Cell.NATURAL
            && world.get(0, 66, 0) == CoatingEngine.Cell.NATURAL);
    check(
        world.get(0, 65, 0) == CoatingEngine.Cell.AIR
            && world.get(0, 67, 0) == CoatingEngine.Cell.AIR);
    world.set(2, 64, 0, CoatingEngine.Cell.OPERATION);
    world.set(1, 64, 0, CoatingEngine.Cell.AIR);
    for (int pass = 0; pass < 20; pass++)
      e.column(world, side, true, seed, new ClimateBudget(100000, 512, 64));
    check(world.get(1, 64, 0) == CoatingEngine.Cell.AIR);
    Fake edge = new Fake();
    edge.unloadedX = 1;
    edge.set(0, 64, 0, CoatingEngine.Cell.NATURAL);
    for (int pass = 0; pass < 5; pass++)
      e.column(
          edge, new CoatingEngine.Column(0, 0), true, seed, new ClimateBudget(100000, 512, 64));
    check(edge.unloadedReads == 0 && edge.unloadedWrites == 0);
    ClimateBudget small = new ClimateBudget(17, 1, 1);
    e.column(world, new CoatingEngine.Column(1, 0), true, seed, small);
    check(small.reads <= 17 && small.changes <= 1 && small.sections() <= 1);
    Fake lake = new Fake();
    lake.set(0, 64, 0, CoatingEngine.Cell.FROZEN_WATER);
    e.column(lake, new CoatingEngine.Column(0, 0), false, seed, new ClimateBudget(100000, 512, 64));
    check(lake.get(0, 64, 0) == CoatingEngine.Cell.WATER);
    System.out.println("ClimateChecks: " + checks + " checks passed");
  }

  static final class Fake implements CoatingEngine.World {
    final Map<String, CoatingEngine.Cell> cells = new HashMap<String, CoatingEngine.Cell>();
    int unloadedX = Integer.MAX_VALUE, unloadedReads, unloadedWrites;

    String key(int x, int y, int z) {
      return x + ":" + y + ":" + z;
    }

    void set(int x, int y, int z, CoatingEngine.Cell c) {
      cells.put(key(x, y, z), c);
    }

    CoatingEngine.Cell get(int x, int y, int z) {
      return cells.containsKey(key(x, y, z)) ? cells.get(key(x, y, z)) : CoatingEngine.Cell.AIR;
    }

    public boolean loaded(int x, int z) {
      return x < unloadedX;
    }

    public int dimension() {
      return 0;
    }

    public int height(int x, int z) {
      return 70;
    }

    public CoatingEngine.Cell read(int x, int y, int z) {
      if (!loaded(x, z)) unloadedReads++;
      return get(x, y, z);
    }

    public boolean write(int x, int y, int z, CoatingEngine.Cell c) {
      if (!loaded(x, z)) unloadedWrites++;
      set(x, y, z, c);
      return true;
    }

    public boolean unobstructed(int x, int y, int z, CoatingEngine.Cell c) {
      return true;
    }
  }
}
