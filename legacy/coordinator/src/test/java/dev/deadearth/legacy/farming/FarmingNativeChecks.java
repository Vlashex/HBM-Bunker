package dev.deadearth.legacy.farming;

import dev.deadearth.legacy.Reflect;
import java.util.*;

/**
 * Call only from the final, isolated full-pack probe with a real loaded world, test player and tray
 * position.
 */
public final class FarmingNativeChecks {
  private FarmingNativeChecks() {}

  private static void require(boolean b, String why) {
    if (!b) throw new AssertionError("Native farming: " + why);
  }

  public static void nativeCrop(Object world, Object pos, String itemId) {
    Object seed =
        Reflect.make("net.minecraft.item.ItemStack", Reflect.registry("ITEMS", itemId), 1, 0);
    Object initial = CropTrayTile.crop(seed, world, pos);
    require(initial != null, "native IPlantable crop " + itemId);
    Object block = Reflect.call(initial, "getBlock/func_177230_c");
    Object mature =
        Reflect.call(
            block, "withAge/func_185528_e", Reflect.call(block, "getMaxAge/func_185526_g"));
    Object drops =
        Reflect.call(Reflect.type("net.minecraft.util.NonNullList"), "create/func_191196_a");
    Reflect.call(block, "getDrops", drops, world, pos, mature, 0);
    require(!((List<?>) drops).isEmpty(), "native mature drops " + itemId);
    List<Object> all = new ArrayList<Object>();
    for (Object stack : (List<?>) drops) all.add(stack);
    List<Object> after = InventoryPacking.reserveSeed(all, seed, FarmingStacks.OPS);
    int before = 0, left = 0;
    for (Object stack : all) before += FarmingStacks.count(stack);
    for (Object stack : after) left += FarmingStacks.count(stack);
    require(before - left >= 0 && before - left <= 1, "reserve at most one native planting seed");
  }

  public static void persistedTray(Object world, Object pos, Object player, String seedId) {
    require(Reflect.loaded(world, pos), "caller supplies an already loaded test position");
    Object block = Reflect.registry("BLOCKS", "dead_earth:crop_tray");
    Reflect.call(
        world,
        "setBlockState/func_180501_a",
        pos,
        Reflect.call(block, "getDefaultState/func_176223_P"),
        3);
    CropTrayTile tile = (CropTrayTile) Reflect.call(world, "getTileEntity/func_175625_s", pos);
    Object cap =
        Reflect.get(
            Reflect.type("net.minecraftforge.items.CapabilityItemHandler"),
            "ITEM_HANDLER_CAPABILITY");
    Object up = Reflect.call(tile, "getCapability", cap, face("UP"));
    Object side = Reflect.call(tile, "getCapability", cap, face("NORTH"));
    Object handler = Reflect.call(tile, "getCapability", cap, face("DOWN"));
    Object combined = Reflect.call(tile, "getCapability", cap, null);
    int outputs = ((Number) Reflect.call(handler, "getSlots")).intValue();
    require(outputs == LegacyFarming.settings.outputSlots, "downward output slot count");
    require(
        ((Number) Reflect.call(up, "getSlots")).intValue() == 2
            && ((Number) Reflect.call(side, "getSlots")).intValue() == 2,
        "top and side insertion views");
    require(
        ((Number) Reflect.call(combined, "getSlots")).intValue() == outputs + 2,
        "combined view preserves output indices");
    Object dirt =
        Reflect.make(
            "net.minecraft.item.ItemStack", Reflect.registry("ITEMS", "minecraft:dirt"), 64, 0);
    Object seed =
        Reflect.make("net.minecraft.item.ItemStack", Reflect.registry("ITEMS", seedId), 64, 0);
    Reflect.call(seed, "setStackDisplayName/func_151001_c", "Native farming NBT test");
    require(
        FarmingStacks.count(Reflect.call(up, "insertItem", 1, seed, false)) == 64,
        "seed requires soil");
    Object beforeSimulation = save(tile);
    require(
        FarmingStacks.count(Reflect.call(up, "insertItem", 0, dirt, true)) == 63,
        "simulated soil accepts exactly one");
    require(
        save(tile).equals(beforeSimulation) && FarmingStacks.count(dirt) == 64,
        "soil simulation has no mutation");
    Object soilRemainder = Reflect.call(side, "insertItem", 0, dirt, false);
    require(
        FarmingStacks.count(soilRemainder) == 63 && FarmingStacks.count(dirt) == 64,
        "one soil consumed by native handler contract");
    require(
        FarmingStacks.count(Reflect.call(combined, "getStackInSlot", outputs)) == 1,
        "combined soil slot");
    require(
        FarmingStacks.count(Reflect.call(up, "insertItem", 0, dirt, false)) == 64,
        "occupied soil rejects a second block");
    beforeSimulation = save(tile);
    require(
        FarmingStacks.count(Reflect.call(side, "insertItem", 1, seed, true)) == 63,
        "simulated seed accepts exactly one");
    require(
        save(tile).equals(beforeSimulation) && FarmingStacks.count(seed) == 64,
        "seed simulation has no mutation");
    Object seedRemainder = Reflect.call(combined, "insertItem", outputs + 1, seed, false);
    require(
        FarmingStacks.count(seedRemainder) == 63 && FarmingStacks.count(seed) == 64,
        "one seed consumed without mutating caller");
    Object retained = Reflect.call(combined, "getStackInSlot", outputs + 1);
    require(
        FarmingStacks.count(retained) == 1
            && FarmingStacks.tag(retained).equals(FarmingStacks.tag(FarmingStacks.one(seed))),
        "retained seed preserves NBT");
    Reflect.call(retained, "shrink/func_190918_g", 1);
    require(
        FarmingStacks.count(Reflect.call(combined, "getStackInSlot", outputs + 1)) == 1,
        "read-only input view does not leak mutable stack");
    require(
        FarmingStacks.count(Reflect.call(side, "extractItem", 0, 64, false)) == 0
            && FarmingStacks.count(Reflect.call(combined, "extractItem", outputs + 1, 64, false))
                == 0,
        "automation cannot remove planted inputs");
    Object nbt =
        tile.func_189515_b(
            (net.minecraft.nbt.NBTTagCompound) Reflect.make("net.minecraft.nbt.NBTTagCompound"));
    Reflect.call(nbt, "setInteger/func_74768_a", "Growth", LegacyFarming.settings.cycleTicks);
    tile.func_145839_a((net.minecraft.nbt.NBTTagCompound) nbt);
    boolean requireLight = LegacyFarming.settings.requireLight;
    try {
      LegacyFarming.settings.requireLight = false;
      for (int i = 0; i <= LegacyFarming.settings.pollTicks; i++) tile.func_73660_a();
      tile.harvest(world);
    } finally {
      LegacyFarming.settings.requireLight = requireLight;
    }
    int total = 0;
    for (Object stack : FarmingStacks.slots(handler)) total += FarmingStacks.count(stack);
    require(total > 0, "mature native harvest entered real inventory");
    Object rejected =
        Reflect.make(
            "net.minecraft.item.ItemStack", Reflect.registry("ITEMS", "minecraft:dirt"), 1, 0);
    require(
        FarmingStacks.count(Reflect.call(handler, "insertItem", 0, rejected, false)) == 1,
        "automation cannot insert into outputs");
    int occupied = -1;
    for (int slot = 0; slot < outputs; slot++) {
      if (FarmingStacks.count(Reflect.call(handler, "getStackInSlot", slot)) > 0) {
        occupied = slot;
        break;
      }
    }
    require(occupied >= 0, "harvest has extractable output");
    int before = FarmingStacks.count(Reflect.call(handler, "getStackInSlot", occupied));
    Object simulated = Reflect.call(handler, "extractItem", occupied, 1, true);
    require(
        FarmingStacks.count(simulated) == 1
            && FarmingStacks.count(Reflect.call(handler, "getStackInSlot", occupied)) == before,
        "simulated output does not mutate");
    Object extracted = Reflect.call(handler, "extractItem", occupied, 1, false);
    require(
        FarmingStacks.count(extracted) == 1
            && FarmingStacks.count(Reflect.call(handler, "getStackInSlot", occupied)) == before - 1,
        "native output extraction conserves items");
    acceleration(world, pos, tile);
    Object saved =
        tile.func_189515_b(
            (net.minecraft.nbt.NBTTagCompound) Reflect.make("net.minecraft.nbt.NBTTagCompound"));
    CropTrayTile restored = new CropTrayTile();
    restored.func_145839_a((net.minecraft.nbt.NBTTagCompound) saved);
    Object savedAgain =
        restored.func_189515_b(
            (net.minecraft.nbt.NBTTagCompound) Reflect.make("net.minecraft.nbt.NBTTagCompound"));
    for (String key : new String[] {"Seed", "Soil", "Output"})
      require(
          Reflect.call(saved, "getCompoundTag/func_74775_l", key)
              .equals(Reflect.call(savedAgain, "getCompoundTag/func_74775_l", key)),
          "NBT round trip " + key);
  }

  private static Object face(String name) {
    for (Object face : Reflect.type("net.minecraft.util.EnumFacing").getEnumConstants())
      if (((Enum<?>) face).name().equals(name)) return face;
    throw new AssertionError("Native facing " + name);
  }

  private static net.minecraft.nbt.NBTTagCompound save(CropTrayTile tile) {
    return tile.func_189515_b(
        (net.minecraft.nbt.NBTTagCompound) Reflect.make("net.minecraft.nbt.NBTTagCompound"));
  }

  private static int growth(CropTrayTile tile) {
    return ((Number) Reflect.call(save(tile), "getInteger/func_74762_e", "Growth")).intValue();
  }

  /** Actual native blocks and ordinary tile ticks, without waiting a complete crop cycle. */
  private static void acceleration(Object world, Object pos, CropTrayTile tile) {
    FarmingConfig production = LegacyFarming.settings;
    FarmingConfig check = new FarmingConfig();
    check.requireLight = false;
    check.pollTicks = 1;
    List<Object> positions = new ArrayList<Object>(), previous = new ArrayList<Object>();
    Object stone =
        Reflect.call(
            Reflect.registry("BLOCKS", "minecraft:stone"), "getDefaultState/func_176223_P");
    Object accelerator =
        Reflect.call(
            Reflect.registry("BLOCKS", check.acceleratorBlock), "getDefaultState/func_176223_P");
    Object hopper =
        Reflect.call(
            Reflect.registry("BLOCKS", "minecraft:hopper"), "getDefaultState/func_176223_P");
    for (int depth = 1; depth <= check.acceleratorDepth; depth++) {
      Object below = Reflect.pos(Reflect.x(pos), Reflect.y(pos) - depth, Reflect.z(pos));
      require(
          Reflect.y(below) >= 0 && Reflect.loaded(world, below),
          "loaded accelerator fixture column");
      require(
          Reflect.call(world, "getTileEntity/func_175625_s", below) == null,
          "fixture never overwrites an inventory");
      positions.add(below);
      previous.add(Reflect.state(world, below));
    }
    try {
      LegacyFarming.settings = check;
      for (Object below : positions)
        Reflect.call(world, "setBlockState/func_180501_a", below, stone, 3);
      tile.func_73660_a();
      int start = growth(tile);
      tile.func_73660_a();
      require(growth(tile) - start == 1, "unboosted native growth step");
      Reflect.call(world, "setBlockState/func_180501_a", positions.get(0), hopper, 3);
      for (int depth = 1; depth <= 5; depth++)
        Reflect.call(world, "setBlockState/func_180501_a", positions.get(depth), accelerator, 3);
      tile.func_73660_a();
      start = growth(tile);
      tile.func_73660_a();
      require(
          growth(tile) - start == 5, "real accelerators below hopper boost growth, capped at four");
      check.acceleratorsEnabled = false;
      tile.func_73660_a();
      start = growth(tile);
      tile.func_73660_a();
      require(growth(tile) - start == 1, "disabled accelerator returns to baseline");
    } finally {
      LegacyFarming.settings = production;
      for (int i = 0; i < positions.size(); i++)
        Reflect.call(world, "setBlockState/func_180501_a", positions.get(i), previous.get(i), 3);
    }
  }
}
