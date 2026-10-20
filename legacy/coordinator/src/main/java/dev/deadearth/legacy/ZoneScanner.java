package dev.deadearth.legacy;

import dev.deadearth.legacy.materials.LegacyMaterials;
import java.util.*;

/** Incremental loaded-only valuation. A scan job owns all its cursors and partial totals. */
final class ZoneScanner {
  private final LegacyRuntime runtime;
  private Job job;

  private static final class Job {
    final Campaign.Zone zone;
    final List<Object> tiles = new ArrayList<Object>();
    InventoryValuation.Cursor cursor;
    int index, ignored, unknown;
    long stock, armed, empty;
    boolean complete = true;
    String error = "";

    Job(Campaign.Zone zone) {
      this.zone = zone;
    }

    void fail(String reason) {
      complete = false;
      if (error.isEmpty()) error = reason;
    }
  }

  ZoneScanner(LegacyRuntime runtime) {
    this.runtime = runtime;
  }

  void tick() {
    runtime.requireThread();
    if (job == null) select();
    if (job == null) return;
    if (!runtime.campaign.zones.contains(job.zone)) {
      job = null;
      return;
    }
    Object world = runtime.world.world(job.zone.dimension);
    Object anchor = Reflect.pos(job.zone.x, job.zone.y, job.zone.z);
    if (world == null
        || !Reflect.loaded(world, anchor)
        || !Reflect.blockId(world, anchor).equals("dead_earth:zone_relay")) {
      job.fail("relay_missing_or_unloaded");
      finish();
      return;
    }
    int tiles = 0, slots = 0;
    while ((job.cursor != null || job.index < job.tiles.size())
        && slots < runtime.settings.inventorySlotsPerTick) {
      if (job.cursor != null) {
        try {
          job.cursor.verify();
          InventoryValuation.Entry entry = job.cursor.next();
          if (entry != null) {
            slots++;
            if (entry.stack != null && entry.count > 0) add(entry.stack, entry.count);
          }
          if (job.cursor.done()) job.cursor = null;
        } catch (RuntimeException invalid) {
          job.fail(message(invalid));
          job.cursor = null;
        }
      } else {
        if (tiles++ >= runtime.settings.tilesPerTick) break;
        Object tile = job.tiles.get(job.index++);
        try {
          stock(tile);
        } catch (RuntimeException invalid) {
          job.fail(message(invalid));
        }
      }
    }
    if (job.cursor == null && job.index >= job.tiles.size()) finish();
  }

  private void select() {
    for (Campaign.Zone zone : runtime.campaign.zones) {
      if (runtime.campaign.activeTicks - zone.scannedAt < runtime.settings.scanTicks) continue;
      Object world = runtime.world.world(zone.dimension);
      Object anchor = Reflect.pos(zone.x, zone.y, zone.z);
      if (world == null
          || !Reflect.loaded(world, anchor)
          || !Reflect.blockId(world, anchor).equals("dead_earth:zone_relay")) {
        zone.complete = false;
        zone.scanError = "relay_missing_or_unloaded";
        continue;
      }
      job = new Job(zone);
      int radius = runtime.settings.zoneRadiusChunks;
      Object provider = Reflect.call(world, "getChunkProvider/func_72863_F");
      collect:
      for (int x = (zone.x >> 4) - radius; x <= (zone.x >> 4) + radius; x++) {
        for (int z = (zone.z >> 4) - radius; z <= (zone.z >> 4) + radius; z++) {
          Object chunk = Reflect.call(provider, "getLoadedChunk/func_186028_c", x, z);
          if (chunk == null) {
            job.fail("unloaded_chunk:" + x + ":" + z);
            continue;
          }
          Map<?, ?> nativeTiles = (Map<?, ?>) Reflect.call(chunk, "getTileEntityMap/func_177434_r");
          for (Object tile : nativeTiles.values()) {
            if (job.tiles.size() >= runtime.settings.maximumZoneTiles) {
              job.fail("maximum_zone_tiles");
              break collect;
            }
            job.tiles.add(tile);
          }
        }
      }
      return;
    }
  }

  private void stock(Object tile) {
    Object world = Reflect.call(tile, "getWorld/func_145831_w");
    Object position = Reflect.call(tile, "getPos/func_174877_v");
    if (world == null
        || !Reflect.loaded(world, position)
        || Reflect.call(world, "getTileEntity/func_175625_s", position) != tile) {
      throw new IllegalStateException("Stale scan tile");
    }
    String id = Reflect.blockId(world, position);
    if (tile.getClass().getName().startsWith("com.hbm.tileentity.turret.")) {
      Integer rank = runtime.settings.turretRanks.get(id);
      if (rank == null) {
        job.unknown++;
        return;
      }
      boolean power = (Boolean) Reflect.call(tile, "hasPower");
      boolean on = (Boolean) Reflect.call(tile, "isOn");
      if (on && power && Reflect.call(tile, "getFirstConfigLoaded") != null)
        job.armed = Math.addExact(job.armed, rank);
      else job.empty = Math.addExact(job.empty, rank);
      return;
    }
    if (Reflect.is(tile, "net.minecraft.tileentity.TileEntityLockableLoot")
        && Reflect.get(tile, "lootTable/field_184284_m") != null) {
      job.ignored++;
      return;
    }
    job.cursor = InventoryValuation.open(tile, runtime.settings);
    if (job.cursor.done()) job.cursor = null;
  }

  private void add(Object stack, long count) {
    if ((Boolean) Reflect.call(stack, "isEmpty/func_190926_b")) return;
    String id =
        Reflect.call(Reflect.call(stack, "getItem/func_77973_b"), "getRegistryName").toString();
    int metadata = ((Number) Reflect.call(stack, "getMetadata/func_77960_j")).intValue();
    String canonical = LegacyMaterials.canonicalToken(id + ":" + metadata);
    Long price = runtime.settings.resourcePrices.get(canonical);
    if (price == null) price = runtime.settings.resourcePrices.get(id + ":" + metadata);
    if (price == null) price = runtime.settings.resourcePrices.get(id);
    if (price == null && canonical.endsWith(":0"))
      price = runtime.settings.resourcePrices.get(canonical.substring(0, canonical.length() - 2));
    if (price != null) job.stock = Math.addExact(job.stock, Math.multiplyExact(price, count));
  }

  private void finish() {
    Campaign.Zone zone = job.zone;
    zone.resourceEmc = job.stock;
    zone.armedRank = job.armed;
    zone.emptyRank = job.empty;
    zone.ignoredLootContainers = job.ignored;
    zone.unknownTurrets = job.unknown;
    zone.value = job.stock * runtime.settings.resourceCoefficient - job.armed + job.empty;
    zone.scannedAt = runtime.campaign.activeTicks;
    zone.complete = job.complete && job.unknown == 0;
    zone.scanError = job.error;
    job = null;
  }

  private static String message(RuntimeException invalid) {
    String value = invalid.getMessage();
    return value == null ? invalid.getClass().getSimpleName() : value;
  }
}
