package dev.deadearth.legacy.combat;

/** Bounded voxel traversal. Native adapter supplies exact collision tests for opaque shapes. */
public final class TransparentSight {
  public static final int CLEAR = 0, TRANSPARENT = 1, OCCLUDED = 2, UNKNOWN = 3;

  public interface WorldRay {
    int cell(int x, int y, int z);
  }

  private TransparentSight() {}

  public static boolean visible(
      double[] from,
      double[] to,
      int radius,
      int maximumCells,
      int maximumTransparent,
      WorldRay world) {
    if (from.length != 3
        || to.length != 3
        || radius < 1
        || maximumCells < 1
        || maximumTransparent < 1) return false;
    for (int n = 0; n < 3; n++)
      if (!Double.isFinite(from[n])
          || !Double.isFinite(to[n])
          || Math.abs(from[n]) > 30000000
          || Math.abs(to[n]) > 30000000) return false;
    double dx = to[0] - from[0], dy = to[1] - from[1], dz = to[2] - from[2];
    if (dx * dx + dy * dy + dz * dz > (double) radius * radius) return false;
    int[] cell = {(int) Math.floor(from[0]), (int) Math.floor(from[1]), (int) Math.floor(from[2])};
    int[] end = {(int) Math.floor(to[0]), (int) Math.floor(to[1]), (int) Math.floor(to[2])};
    double[] delta = {dx, dy, dz}, stepT = new double[3], nextT = new double[3];
    int[] step = new int[3];
    for (int n = 0; n < 3; n++) {
      step[n] = delta[n] > 0 ? 1 : delta[n] < 0 ? -1 : 0;
      stepT[n] = step[n] == 0 ? Double.POSITIVE_INFINITY : Math.abs(1 / delta[n]);
      nextT[n] =
          step[n] == 0
              ? Double.POSITIVE_INFINITY
              : ((step[n] > 0 ? cell[n] + 1 : cell[n]) - from[n]) / delta[n];
    }
    int transparent = 0, visited = 0;
    while (visited++ < maximumCells) {
      int state = world.cell(cell[0], cell[1], cell[2]);
      if (state == UNKNOWN || state == OCCLUDED) return false;
      if (state == TRANSPARENT && ++transparent > maximumTransparent) return false;
      if (cell[0] == end[0] && cell[1] == end[1] && cell[2] == end[2]) return true;
      double next = Math.min(nextT[0], Math.min(nextT[1], nextT[2]));
      if (!Double.isFinite(next) || next > 1) return false;
      int crossed = 0;
      for (int n = 0; n < 3; n++) if (nextT[n] <= next + 1e-12) crossed |= 1 << n;
      // Supercover corner/edge neighbors too: a ray cannot skip an opaque voxel
      // merely because it crosses two boundaries at exactly the same instant.
      for (int subset = (crossed - 1) & crossed; subset > 0; subset = (subset - 1) & crossed) {
        if (visited++ >= maximumCells) return false;
        int edge =
            world.cell(
                cell[0] + ((subset & 1) != 0 ? step[0] : 0),
                cell[1] + ((subset & 2) != 0 ? step[1] : 0),
                cell[2] + ((subset & 4) != 0 ? step[2] : 0));
        if (edge == UNKNOWN || edge == OCCLUDED) return false;
        if (edge == TRANSPARENT && ++transparent > maximumTransparent) return false;
      }
      for (int n = 0; n < 3; n++)
        if ((crossed & (1 << n)) != 0) {
          cell[n] += step[n];
          nextT[n] += stepT[n];
        }
    }
    return false;
  }
}
