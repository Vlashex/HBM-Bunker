package dev.deadearth.legacy.farming;

import dev.deadearth.legacy.Reflect;
import java.lang.reflect.Proxy;

/** Sided native item-handler views. Old output indices remain stable in the combined view. */
final class CropTrayInventory {
  static final int OUTPUT = 0, INPUT = 1, COMBINED = 2;

  private CropTrayInventory() {}

  static Object create(final CropTrayTile tile, final int mode) {
    Class<?> api = Reflect.type("net.minecraftforge.items.IItemHandler");
    return Proxy.newProxyInstance(
        api.getClassLoader(),
        new Class<?>[] {api},
        (proxy, method, args) -> {
          String name = method.getName();
          if (name.equals("toString")) return "DeadEarthCropTrayInventory[" + mode + "]";
          if (name.equals("hashCode")) return System.identityHashCode(proxy);
          if (name.equals("equals")) return proxy == args[0];
          int outputs = ((Number) Reflect.call(tile.outputs(), "getSlots")).intValue();
          int size = mode == INPUT ? 2 : outputs + (mode == COMBINED ? 2 : 0);
          if (name.equals("getSlots")) return size;
          int slot = ((Number) args[0]).intValue();
          if (slot < 0 || slot >= size) throw new IllegalArgumentException("Crop tray slot");
          int input =
              mode == INPUT ? slot : mode == COMBINED && slot >= outputs ? slot - outputs : -1;
          if (name.equals("getStackInSlot"))
            return input >= 0
                ? tile.input(input)
                : FarmingStacks.copy(Reflect.call(tile.outputs(), name, slot));
          if (name.equals("getSlotLimit"))
            return input >= 0 ? 1 : Reflect.call(tile.outputs(), name, slot);
          if (name.equals("isItemValid")) return input >= 0 && tile.validInput(input, args[1]);
          if (name.equals("insertItem"))
            return input < 0 ? args[1] : tile.insertInput(input, args[1], (Boolean) args[2]);
          if (name.equals("extractItem"))
            return input >= 0
                ? FarmingStacks.empty()
                : tile.extractOutput(slot, ((Number) args[1]).intValue(), (Boolean) args[2]);
          throw new IllegalStateException("Unsupported native crop item-handler operation " + name);
        });
  }
}
