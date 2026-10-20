package dev.deadearth.legacy.climate;

/**
 * Loaded-only exterior shell. Only air is replaced; natural blocks are never seasonal ownership.
 */
public final class CoatingEngine {
  public static final int UP = 0, NORTH = 1, SOUTH = 2, WEST = 3, EAST = 4;
  private static final int[] DX = {0, 0, 0, -1, 1}, DZ = {0, -1, 1, 0, 0};

  public static final class Cell {
    public static final Cell AIR = new Cell(false, false, false, false, 0, 0);
    public static final Cell NATURAL = new Cell(false, true, false, false, 0, 0);
    public static final Cell BLOCKED = new Cell(false, false, false, false, 0, 0);
    public static final Cell OPERATION = new Cell(false, false, true, false, 0, 0);
    public static final Cell WATER = new Cell(false, false, false, false, 0, 0);
    public static final Cell FROZEN_WATER = new Cell(true, true, false, false, UP, 8);
    public final boolean own, anchor, operation, snow;
    public final int face, layers;

    private Cell(
        boolean own, boolean anchor, boolean operation, boolean snow, int face, int layers) {
      this.own = own;
      this.anchor = anchor;
      this.operation = operation;
      this.snow = snow;
      this.face = face;
      this.layers = layers;
    }

    public static Cell coating(boolean snow, int face, int layers) {
      return new Cell(true, false, false, snow, face, layers);
    }

    public boolean replaceable() {
      return this == AIR || own;
    }
  }

  public interface World {
    int dimension();

    boolean loaded(int x, int z);

    int height(int x, int z);

    Cell read(int x, int y, int z);

    boolean write(int x, int y, int z, Cell next);

    boolean unobstructed(int x, int y, int z, Cell next);
  }

  public static final class Column {
    public final int x, z;
    private int y = -1, end;

    public Column(int x, int z) {
      this.x = x;
      this.z = z;
    }

    public boolean inProgress() {
      return y >= 0;
    }
  }

  private static final class Stop extends RuntimeException {
    static final Stop INSTANCE = new Stop();

    private Stop() {
      super(null, null, false, false);
    }
  }

  private final ClimateConfig c;

  public CoatingEngine(ClimateConfig c) {
    this.c = c;
  }

  /**
   * Returns true only after the whole column was considered, resumes exactly at a read-budget stop.
   */
  public boolean column(
      World world, Column column, boolean winter, long seed, ClimateBudget budget) {
    if (!world.loaded(column.x, column.z)) return true;
    try {
      WinterField.Profile profile = WinterField.sample(seed, column.x, column.z, c);
      if (column.y < 0) {
        int floor = winter ? surface(world, column.x, column.z, budget) : 0;
        int ceiling = winter ? floor : height(world, column.x, column.z, budget);
        if (winter)
          for (int face = 1; face < 5; face++)
            for (int d = 1; d <= c.maximumSide(); d++)
              ceiling =
                  Math.max(
                      ceiling,
                      surface(world, column.x + DX[face] * d, column.z + DZ[face] * d, budget));
        // First original roof/ground is the floor: indoor air/caves cannot become winter shells.
        column.y = winter ? Math.max(floor, ceiling - c.coatingDepth) : 0;
        column.end = Math.min(255, ceiling + (winter ? c.maximumTop() : 0));
      }
      for (; column.y <= column.end; column.y++) {
        if (budget.exhausted()) return false;
        int y = column.y;
        Cell old = read(world, column.x, y, column.z, budget);
        if (!winter) {
          if (old == Cell.FROZEN_WATER) write(world, column.x, y, column.z, Cell.WATER, budget);
          else if (old.own)
            write(
                world,
                column.x,
                y,
                column.z,
                old.layers <= c.thawLayers
                    ? Cell.AIR
                    : Cell.coating(old.snow, old.face, old.layers - c.thawLayers),
                budget);
          continue;
        }
        if (old == Cell.FROZEN_WATER) continue;
        if (!old.replaceable()) continue;
        Cell plan = plan(world, column.x, y, column.z, profile, budget);
        if (plan == null) {
          if (old.own)
            write(
                world,
                column.x,
                y,
                column.z,
                old.layers <= c.thawLayers
                    ? Cell.AIR
                    : Cell.coating(old.snow, old.face, old.layers - c.thawLayers),
                budget);
          continue;
        }
        if (!operationClear(world, column.x, y, column.z, budget)) {
          // Remove previous seasonal ice when a player adds a machine/door beside it.
          if (old.own) write(world, column.x, y, column.z, Cell.AIR, budget);
          continue;
        }
        int px = column.x - DX[plan.face],
            py = y - (plan.face == UP ? 1 : 0),
            pz = column.z - DZ[plan.face];
        Cell previous = read(world, px, py, pz, budget);
        if (!previous.anchor && !(previous.own && previous.layers == 8)) continue;
        int layers = old.own ? Math.min(8, old.layers + c.accretionLayers) : c.accretionLayers;
        Cell next = Cell.coating(plan.snow, plan.face, layers);
        if (!old.own || old.layers != layers || old.snow != next.snow || old.face != next.face) {
          if (world.unobstructed(column.x, y, column.z, next))
            write(world, column.x, y, column.z, next, budget);
        }
      }
      column.y = -1;
      return true;
    } catch (Stop exhaustedOrUnloaded) {
      if (budget.exhausted()) return false;
      // An unloaded border never stalls the rest of this chunk. It is revisited next sweep.
      column.y = -1;
      return true;
    }
  }

  private Cell plan(World w, int x, int y, int z, WinterField.Profile p, ClimateBudget b) {
    for (int d = 1; d <= p.top() && y - d >= 0; d++) {
      Cell base = read(w, x, y - d, z, b);
      if (base.anchor) return Cell.coating(d > p.topIce, UP, 1);
      if (!base.replaceable()) break;
    }
    // Cardinal shells preserve a simple outward predecessor, including vertical faces.
    for (int d = 1; d <= p.side(); d++)
      for (int face = 1; face < 5; face++) {
        boolean clear = true;
        for (int k = 1; k < d; k++)
          if (!read(w, x - DX[face] * k, y, z - DZ[face] * k, b).replaceable()) {
            clear = false;
            break;
          }
        if (clear && read(w, x - DX[face] * d, y, z - DZ[face] * d, b).anchor)
          return Cell.coating(d > p.sideIce, face, 1);
      }
    return null;
  }

  private boolean operationClear(World w, int x, int y, int z, ClimateBudget b) {
    for (int dx = -c.operationClearance; dx <= c.operationClearance; dx++)
      for (int dz = -c.operationClearance; dz <= c.operationClearance; dz++)
        for (int dy = -1; dy <= 1; dy++)
          if (y + dy >= 0 && y + dy <= 255 && read(w, x + dx, y + dy, z + dz, b).operation)
            return false;
    return true;
  }

  boolean clearForOperation(World w, int x, int y, int z, ClimateBudget b) {
    try {
      return operationClear(w, x, y, z, b);
    } catch (Stop unavailable) {
      return false;
    }
  }

  private int height(World w, int x, int z, ClimateBudget b) {
    if (!w.loaded(x, z)) throw Stop.INSTANCE;
    if (!b.read()) throw Stop.INSTANCE;
    return w.height(x, z);
  }

  private int surface(World w, int x, int z, ClimateBudget b) {
    int y = Math.min(255, height(w, x, z, b) - 1);
    while (y >= 0) {
      Cell state = read(w, x, y, z, b);
      if (!state.own && state != Cell.AIR) return y + 1;
      y--;
    }
    return 0;
  }

  private Cell read(World w, int x, int y, int z, ClimateBudget b) {
    if (y < 0 || y > 255) return Cell.BLOCKED;
    if (!w.loaded(x, z) || !b.read()) throw Stop.INSTANCE;
    return w.read(x, y, z);
  }

  private void write(World w, int x, int y, int z, Cell next, ClimateBudget b) {
    if (!w.loaded(x, z)) throw Stop.INSTANCE;
    if (!b.read()) throw Stop.INSTANCE; // Adapter's final ownership recheck before mutation.
    if (!b.canWrite(w.dimension(), x, y, z)) throw Stop.INSTANCE;
    if (w.write(x, y, z, next)) b.written(w.dimension(), x, y, z);
  }
}
