package dev.deadearth.legacy;

import java.util.*;

/** Incremental read-only inventory view; AE2 cells belong to their physical drive's zone. */
public final class InventoryValuation {
  private InventoryValuation() {}

  public static final class Entry {
    public final Object stack;
    public final long count;

    Entry(Object stack, long count) {
      this.stack = stack;
      this.count = count;
    }
  }

  public static final class Cursor {
    private final Object tile, world, pos, inventory;
    private final boolean vanilla, ae;
    private final int slots, maximumCellTypes;
    private int slot = 0;
    private Iterator<?> cellRows = Collections.emptyIterator();

    Cursor(Object tile, int maximumSlots, int maximumCellTypes) {
      this.tile = tile;
      world = Reflect.call(tile, "getWorld/func_145831_w");
      pos = Reflect.call(tile, "getPos/func_174877_v");
      String name = tile.getClass().getName();
      ae =
          name.equals("appeng.tile.storage.TileDrive")
              || name.equals("appeng.tile.storage.TileChest");
      this.maximumCellTypes = maximumCellTypes;
      if (ae) {
        inventory = Reflect.call(tile, "getInternalInventory");
        vanilla = false;
      } else if (Reflect.is(tile, "net.minecraft.inventory.IInventory")) {
        inventory = tile;
        vanilla = true;
      } else {
        Object capability =
            Reflect.get(
                Reflect.type("net.minecraftforge.items.CapabilityItemHandler"),
                "ITEM_HANDLER_CAPABILITY");
        vanilla = false;
        if (!(Boolean) Reflect.call(tile, "hasCapability", capability, null)) {
          inventory = null;
          slots = 0;
          return;
        }
        inventory = Reflect.call(tile, "getCapability", capability, null);
        if (inventory == null) throw new IllegalStateException("Advertised inventory unavailable");
      }
      slots =
          ((Number)
                  Reflect.call(inventory, vanilla ? "getSizeInventory/func_70302_i_" : "getSlots"))
              .intValue();
      if (slots < 0 || slots > maximumSlots)
        throw new IllegalStateException("Inventory slot bound");
    }

    public boolean done() {
      return slot >= slots && !cellRows.hasNext();
    }

    public void verify() {
      if (world == null
          || !Reflect.loaded(world, pos)
          || Reflect.call(world, "getTileEntity/func_175625_s", pos) != tile)
        throw new IllegalStateException("Stale inventory scan");
    }

    public Entry next() {
      if (cellRows.hasNext()) {
        Object row = cellRows.next();
        long count = ((Number) Reflect.call(row, "getStackSize")).longValue();
        if (count < 0) throw new IllegalStateException("Invalid ME quantity");
        return new Entry(Reflect.call(row, "createItemStack"), count);
      }
      if (slot >= slots) return null;
      Object stack =
          Reflect.call(
              inventory, vanilla ? "getStackInSlot/func_70301_a" : "getStackInSlot", slot++);
      if (stack == null || (Boolean) Reflect.call(stack, "isEmpty/func_190926_b"))
        return new Entry(stack, 0);
      if (ae) {
        Object api = Reflect.call(Reflect.type("appeng.api.AEApi"), "instance");
        Object channels = Reflect.call(api, "storage");
        Object channel =
            Reflect.call(
                channels,
                "getStorageChannel",
                Reflect.type("appeng.api.storage.channels.IItemStorageChannel"));
        Object registry = Reflect.call(Reflect.call(api, "registries"), "cell");
        Object cell = Reflect.call(registry, "getCellInventory", stack, null, channel);
        if (cell != null) {
          String itemId =
              Reflect.call(Reflect.call(stack, "getItem/func_77973_b"), "getRegistryName")
                  .toString();
          if (!itemId.matches("appliedenergistics2:storage_cell_(1|4|16|64)k"))
            throw new IllegalStateException("Unsupported ME cell adapter: " + itemId);
          Object list = Reflect.call(channel, "createList");
          Reflect.call(cell, "getAvailableItems", list);
          List<Object> rows = new ArrayList<Object>();
          for (Object row : (Iterable<?>) list) {
            if (rows.size() >= maximumCellTypes)
              throw new IllegalStateException("ME cell type bound");
            rows.add(row);
          }
          cellRows = rows.iterator();
        }
      }
      return new Entry(stack, ((Number) Reflect.call(stack, "getCount/func_190916_E")).longValue());
    }
  }

  public static Cursor open(Object tile, Settings settings) {
    return new Cursor(tile, settings.maximumInventorySlots, settings.maximumMECellTypes);
  }
}
