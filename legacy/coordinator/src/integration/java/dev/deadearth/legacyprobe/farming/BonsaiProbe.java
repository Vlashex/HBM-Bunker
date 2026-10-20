package dev.deadearth.legacyprobe.farming;

import dev.deadearth.legacy.JsonFiles;
import dev.deadearth.legacy.Reflect;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;

/** Actual native clicks, growth, cutting and hopping export in the isolated test world. */
public final class BonsaiProbe {
  private BonsaiProbe() {}

  private static void require(boolean value, String detail) {
    if (!value) throw new AssertionError(detail);
  }

  private static Object stack(String id, int count, int meta) {
    Object item = Reflect.registry("ITEMS", id);
    require(item != null, "native item " + id);
    return Reflect.make("net.minecraft.item.ItemStack", item, count, meta);
  }

  private static Object empty() {
    return Reflect.get(Reflect.type("net.minecraft.item.ItemStack"), "EMPTY/field_190927_a");
  }

  private static String itemId(Object stack) {
    if ((Boolean) Reflect.call(stack, "isEmpty/func_190926_b")) return "minecraft:air";
    return String.valueOf(
        Reflect.call(Reflect.call(stack, "getItem/func_77973_b"), "getRegistryName"));
  }

  private static int count(Object stack) {
    return ((Number) Reflect.call(stack, "getCount/func_190916_E")).intValue();
  }

  private static int meta(Object stack) {
    return ((Number) Reflect.call(stack, "getMetadata/func_77960_j")).intValue();
  }

  private static void put(Object world, Object pos, String id, int meta) {
    Object block = Reflect.registry("BLOCKS", id);
    require(block != null, "native block " + id);
    Object state = Reflect.call(block, "getStateFromMeta/func_176203_a", meta);
    require(
        (Boolean) Reflect.call(world, "setBlockState/func_180501_a", pos, state, 3),
        "native placement " + id);
  }

  private static Object recipeGrid(Object player) {
    Object backing =
        Reflect.make("net.minecraft.inventory.InventoryBasic", "Bonsai probe", false, 5);
    Object owner =
        Reflect.make(
            "net.minecraft.inventory.ContainerHopper",
            Reflect.get(player, "inventory/field_71071_by"),
            backing,
            player);
    return Reflect.make("net.minecraft.inventory.InventoryCrafting", owner, 3, 3);
  }

  private static void slot(Object grid, int slot, Object item) {
    Reflect.call(grid, "setInventorySlotContents/func_70299_a", slot, item);
  }

  private static Object craft(Object grid, Object world) {
    return Reflect.call(
        Reflect.type("net.minecraft.item.crafting.CraftingManager"),
        "findMatchingResult/func_82787_a",
        grid,
        world);
  }

  private static void recipes(Object world, Object player) {
    Object grid = recipeGrid(player);
    slot(grid, 0, stack("hbm:plate_iron", 1, 0));
    slot(grid, 2, stack("hbm:plate_iron", 1, 0));
    for (int index : new int[] {3, 5, 6, 7, 8})
      slot(grid, index, stack("minecraft:clay_ball", 1, 0));
    Object result = craft(grid, world);
    require(
        itemId(result).equals("bonsaitrees:bonsaipot") && meta(result) == 0 && count(result) == 1,
        "native crafting manager makes pot from five clay and two HBM iron plates");
    slot(grid, 2, empty());
    require(
        !itemId(craft(grid, world)).equals("bonsaitrees:bonsaipot"),
        "missing HBM plate cannot craft pot");
    grid = recipeGrid(player);
    slot(grid, 0, stack("bonsaitrees:bonsaipot", 1, 0));
    slot(grid, 1, stack("minecraft:hopper", 1, 0));
    result = craft(grid, world);
    require(
        itemId(result).equals("bonsaitrees:bonsaipot") && meta(result) == 1 && count(result) == 1,
        "native crafting manager upgrades pot with hopper");
    grid = recipeGrid(player);
    for (int index : new int[] {0, 2, 3, 4, 5}) slot(grid, index, stack("minecraft:brick", 1, 0));
    require(
        !itemId(craft(grid, world)).equals("bonsaitrees:bonsaipot"),
        "original plate-free brick recipe removed");
  }

  private static void click(Object world, Object pos, Object actor, Object hand, Object up) {
    Object state = Reflect.state(world, pos);
    Object block = Reflect.call(state, "getBlock/func_177230_c");
    require(
        (Boolean)
            Reflect.call(
                block,
                "onBlockActivated/func_180639_a",
                world,
                pos,
                state,
                actor,
                hand,
                up,
                .5f,
                .5f,
                .5f),
        "native Bonsai click accepted");
  }

  private static void plant(
      Object world, Object pos, Object actor, Object hand, Object up, Object tile) {
    Object soil = stack("minecraft:dirt", 2, 0);
    Reflect.call(actor, "setHeldItem/func_184611_a", hand, soil);
    click(world, pos, actor, hand, up);
    require(
        (Boolean) Reflect.call(tile, "hasSoil") && count(soil) == 1,
        "survival dirt click consumes exactly one soil");
    Object sapling = stack("minecraft:sapling", 2, 0);
    Reflect.call(actor, "setHeldItem/func_184611_a", hand, sapling);
    click(world, pos, actor, hand, up);
    require(
        (Boolean) Reflect.call(tile, "hasSapling") && count(sapling) == 1,
        "survival sapling click consumes exactly one oak sapling");
    require(Reflect.call(tile, "getTreeType") != null, "native oak integration resolves");
  }

  private static void add(Map<String, Integer> result, Object stack) {
    if ((Boolean) Reflect.call(stack, "isEmpty/func_190926_b")) return;
    String key = itemId(stack) + ":" + meta(stack);
    result.put(key, result.getOrDefault(key, 0) + count(stack));
  }

  private static Map<String, Integer> contents(Object handler) {
    Map<String, Integer> result = new TreeMap<String, Integer>();
    int slots = ((Number) Reflect.call(handler, "getSlots")).intValue();
    for (int i = 0; i < slots; i++) add(result, Reflect.call(handler, "getStackInSlot", i));
    return result;
  }

  private static Map<String, Integer> chestContents(Object chest) {
    Map<String, Integer> result = new TreeMap<String, Integer>();
    int slots = ((Number) Reflect.call(chest, "getSizeInventory/func_70302_i_")).intValue();
    for (int i = 0; i < slots; i++)
      add(result, Reflect.call(chest, "getStackInSlot/func_70301_a", i));
    return result;
  }

  private static void tick(Object tile, int[] ticks) {
    // Invoke the unmodified ITickable implementation; never set progress,
    // manufacture drops or insert a reward into either output inventory.
    Reflect.call(tile, "update/func_73660_a");
    ticks[0]++;
  }

  public static Map<String, Object> run(Object world, Object player, int x, int y, int z) {
    require(
        Boolean.getBoolean("deadearth.testControls")
            && Files.isRegularFile(Paths.get(".dead-earth-isolated-native-test")),
        "explicit isolated native test controls");
    require(!(Boolean) Reflect.get(world, "isRemote/field_72995_K"), "server world only");
    require(y > 0 && y < 250, "bounded fixture height");
    Object server = Reflect.call(world, "getMinecraftServer/func_73046_m");
    require(
        (Boolean) Reflect.call(server, "isCallingFromMinecraftThread/func_152345_ab"),
        "server thread only");
    require(
        !(Boolean)
            Reflect.get(
                Reflect.get(player, "capabilities/field_71075_bZ"), "isCreativeMode/field_75098_d"),
        "survival test actor");
    recipes(world, player);
    Class<?> settings =
        Reflect.type("org.dave.bonsaitrees.misc.ConfigurationHandler$GeneralSettings");
    require(
        (Boolean) Reflect.get(settings, "waitForEmptyItemBuffers"),
        "pack enables native non-voiding buffer policy");
    require(!(Boolean) Reflect.get(settings, "disableHoppingBonsaiPot"), "native hopping enabled");
    Object oldGrow = Reflect.get(settings, "baseGrowTicks");
    Object oldDefault = Reflect.get(settings, "defaultHoppingCooldown");
    Object oldPunish = Reflect.get(settings, "punishHoppingCooldown");
    Object main = Reflect.get(Reflect.type("net.minecraft.util.EnumHand"), "MAIN_HAND");
    Object off = Reflect.get(Reflect.type("net.minecraft.util.EnumHand"), "OFF_HAND");
    Object up = Reflect.get(Reflect.type("net.minecraft.util.EnumFacing"), "UP");
    Object oldMain =
        Reflect.call(Reflect.call(player, "getHeldItem/func_184586_b", main), "copy/func_77946_l");
    Object oldOff =
        Reflect.call(Reflect.call(player, "getHeldItem/func_184586_b", off), "copy/func_77946_l");
    boolean wasSneaking = (Boolean) Reflect.call(player, "isSneaking/func_70093_af");
    List<Object> positions = new ArrayList<Object>();
    Object ordinaryPos = Reflect.pos(x, y, z), hoppingPos = Reflect.pos(x + 2, y, z);
    Object ordinaryFloor = Reflect.pos(x, y - 1, z), hoppingFloor = Reflect.pos(x + 2, y - 1, z);
    for (int dx : new int[] {0, 2})
      for (int dy = -1; dy <= 1; dy++) {
        Object pos = Reflect.pos(x + dx, y + dy, z);
        require(
            Reflect.loaded(world, pos)
                && Reflect.blockId(world, pos).equals("minecraft:air")
                && Reflect.call(world, "getTileEntity/func_175625_s", pos) == null,
            "Bonsai fixture only uses loaded empty air");
        positions.add(pos);
      }
    int[] ticks = {0};
    Map<String, Object> result = new LinkedHashMap<String, Object>();
    try {
      // Official native timing settings only; never saved to the production config.
      Reflect.set(settings, "baseGrowTicks", 10);
      Reflect.set(settings, "defaultHoppingCooldown", 1);
      Reflect.set(settings, "punishHoppingCooldown", 1);
      Reflect.call(player, "setSneaking/func_70095_a", false);
      Reflect.call(player, "setHeldItem/func_184611_a", off, empty());
      put(world, ordinaryFloor, "minecraft:stone", 0);
      put(world, hoppingFloor, "minecraft:stone", 0);
      put(world, ordinaryPos, "bonsaitrees:bonsaipot", 0);
      put(world, hoppingPos, "bonsaitrees:bonsaipot", 1);
      Object ordinary = Reflect.call(world, "getTileEntity/func_175625_s", ordinaryPos);
      Object hopping = Reflect.call(world, "getTileEntity/func_175625_s", hoppingPos);
      require(ordinary != null && hopping != null, "native Bonsai tiles created");
      require(
          !(Boolean) Reflect.call(ordinary, "isHoppingPot")
              && (Boolean) Reflect.call(hopping, "isHoppingPot"),
          "native metadata selects both variants");
      Collection<?> ticking =
          (Collection<?>) Reflect.get(world, "tickableTileEntities/field_175730_i");
      require(
          ticking.contains(ordinary) && ticking.contains(hopping),
          "placed pots registered for ordinary world tile ticking");
      plant(world, ordinaryPos, player, main, up, ordinary);
      plant(world, hoppingPos, player, main, up, hopping);
      tick(ordinary, ticks);
      require(
          ((Number) Reflect.call(ordinary, "getProgress")).doubleValue() > 0,
          "native oak progress increases from zero without forced progress");
      for (int i = 0; i < 160 && !(Boolean) Reflect.call(ordinary, "isHarvestable"); i++)
        tick(ordinary, ticks);
      require(
          (Boolean) Reflect.call(ordinary, "isHarvestable"),
          "ordinary native pot grows to maturity");
      Object axe = stack("minecraft:stone_axe", 1, 0);
      Reflect.call(player, "setHeldItem/func_184611_a", main, axe);
      click(world, ordinaryPos, player, main, up);
      require(
          ((Number) Reflect.call(ordinary, "getProgress")).doubleValue() == 0
              && ((Number) Reflect.call(axe, "getItemDamage/func_77952_i")).intValue() == 1,
          "native axe interaction harvests grown ordinary pot and resets growth");

      Object buffer = Reflect.call(hopping, "getHoppingItemBuffer");
      for (int i = 0; i < 320 && contents(buffer).isEmpty(); i++) tick(hopping, ticks);
      Map<String, Integer> pending = contents(buffer);
      require(!pending.isEmpty(), "native hopping harvest creates actual randomized loot");
      for (int i = 0; i < 80; i++) tick(hopping, ticks);
      require(
          contents(buffer).equals(pending) && (Boolean) Reflect.call(hopping, "isHarvestable"),
          "missing destination pauses harvest and preserves all prior native drops");

      put(world, hoppingFloor, "minecraft:chest", 0);
      Object chest = Reflect.call(world, "getTileEntity/func_175625_s", hoppingFloor);
      require(chest != null && chestContents(chest).isEmpty(), "fresh native chest starts empty");
      tick(hopping, ticks);
      require(
          chestContents(chest).equals(pending),
          "native hopping export transfers the exact pending loot into real chest capability");
      for (int i = 0; i < 320 && !chestContents(chest).containsKey("minecraft:log:0"); i++)
        tick(hopping, ticks);
      Map<String, Integer> produced = chestContents(chest);
      require(
          produced.getOrDefault("minecraft:log:0", 0) > 0,
          "native automatic tree farm supplies actual oak logs");
      require(
          (Boolean) Reflect.call(hopping, "hasSapling")
              && (Boolean) Reflect.call(hopping, "hasSoil"),
          "automatic harvesting retains planted tree and soil");
      result.putAll(
          JsonFiles.map(
              "passed",
              true,
              "nativePlantingClicks",
              true,
              "nativeGrowth",
              true,
              "nativeAxeHarvest",
              true,
              "nativeHoppingExport",
              true,
              "bufferPreservedWithoutDestination",
              true,
              "craftedThroughHbm",
              true,
              "forcedDropsOrProgress",
              false,
              "manualNativeTileTicks",
              ticks[0],
              "ordinaryServerTileRegistration",
              true,
              "output",
              produced));
    } finally {
      Reflect.set(settings, "baseGrowTicks", oldGrow);
      Reflect.set(settings, "defaultHoppingCooldown", oldDefault);
      Reflect.set(settings, "punishHoppingCooldown", oldPunish);
      Reflect.call(player, "setHeldItem/func_184611_a", main, oldMain);
      Reflect.call(player, "setHeldItem/func_184611_a", off, oldOff);
      Reflect.call(player, "setSneaking/func_70095_a", wasSneaking);
      for (Object pos : positions)
        if (!Reflect.blockId(world, pos).equals("minecraft:air"))
          put(world, pos, "minecraft:air", 0);
    }
    require(
        Reflect.get(settings, "baseGrowTicks").equals(oldGrow)
            && Reflect.get(settings, "defaultHoppingCooldown").equals(oldDefault)
            && Reflect.get(settings, "punishHoppingCooldown").equals(oldPunish),
        "official native test timings restored");
    result.put("timingRestored", true);
    return result;
  }
}
