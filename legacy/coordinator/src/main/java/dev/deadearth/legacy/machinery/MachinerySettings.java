package dev.deadearth.legacy.machinery;

import dev.deadearth.legacy.JsonFiles;
import java.nio.file.*;

public final class MachinerySettings {
  public int schema = 1, capacityFE = 200000, maxReceiveFE = 4000, chargerFEPerTick = 2000;
  public double hePerFE = 1;
  public int fortuneLevel = 5, processorFEPerCycle = 4000, processorCycleTicks = 40;

  public void validate() {
    if (schema != 1
        || capacityFE < 1000
        || capacityFE > 100000000
        || maxReceiveFE < 1
        || maxReceiveFE > 10000000
        || chargerFEPerTick < 1
        || chargerFEPerTick > 10000000
        || Double.isNaN(hePerFE)
        || Double.isInfinite(hePerFE)
        || hePerFE < .001
        || hePerFE > 1000000
        || fortuneLevel < 0
        || fortuneLevel > 10
        || processorFEPerCycle < 1
        || processorFEPerCycle > capacityFE
        || processorCycleTicks < 1
        || processorCycleTicks > 12000)
      throw new IllegalArgumentException("Invalid machinery budgets");
  }

  static MachinerySettings load(Path config) {
    Path p = config.resolve("dead-earth/machinery.json");
    try {
      if (!Files.exists(p)) {
        MachinerySettings s = new MachinerySettings();
        s.validate();
        JsonFiles.write(p, s);
        return s;
      }
      MachinerySettings s =
          dev.deadearth.legacy.data.GameDataFiles.load(p, MachinerySettings.class, 16384);
      if (s == null) throw new IllegalArgumentException("Missing machinery settings");
      s.validate();
      return s;
    } catch (Exception error) {
      throw new IllegalStateException(
          "Machinery settings unreadable; original file preserved", error);
    }
  }
}
