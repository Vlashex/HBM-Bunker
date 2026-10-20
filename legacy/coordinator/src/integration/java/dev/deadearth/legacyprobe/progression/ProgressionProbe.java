package dev.deadearth.legacyprobe.progression;

import dev.deadearth.legacy.*;
import dev.deadearth.legacy.progression.LegacyProgression;
import java.util.*;

/** Deferred native full-pack assertions; invoke only after server EMC mapping and recipe load. */
public final class ProgressionProbe {
  private static void require(boolean b, String detail) {
    if (!b) throw new AssertionError(detail);
  }

  private static Object stack(String id, int meta) {
    return Reflect.make("net.minecraft.item.ItemStack", Reflect.registry("ITEMS", id), 1, meta);
  }

  private static Object recipe(String suffix) {
    Object registry =
        Reflect.get(
            Reflect.type("net.minecraftforge.fml.common.registry.ForgeRegistries"), "RECIPES");
    for (Object recipe : (Collection<?>) Reflect.call(registry, "getValuesCollection"))
      if (String.valueOf(Reflect.call(recipe, "getRegistryName")).endsWith(":" + suffix))
        return recipe;
    throw new AssertionError("Missing native recipe " + suffix);
  }

  private static boolean reward(Object registry, String input, String output, int meta, int mesh) {
    List<?> rewards = (List<?>) Reflect.call(registry, "getDrops", stack(input, 0));
    for (Object reward : rewards) {
      if (((Number) Reflect.call(reward, "getMeshLevel")).intValue() != mesh) continue;
      Object item = Reflect.call(Reflect.call(reward, "getDrop"), "getItemStack");
      if (Reflect.call(Reflect.call(item, "getItem/func_77973_b"), "getRegistryName")
              .toString()
              .equals(output)
          && ((Number) Reflect.call(item, "getMetadata/func_77960_j")).intValue() == meta)
        return true;
    }
    return false;
  }

  // A recipe grid needs a notification owner, not a live workbench session. The
  // native workbench callback sends SPacketSetSlot via EntityPlayerMP.connection;
  // this isolated fake player intentionally has no network connection. A native
  // hopper owner only detects slot changes against its empty listener list.
  private static Object craftingGrid(Object player) {
    Object inventory = Reflect.get(player, "inventory/field_71071_by");
    Object backing =
        Reflect.make(
            "net.minecraft.inventory.InventoryBasic", "DEAD EARTH progression probe", false, 5);
    Object owner =
        Reflect.make("net.minecraft.inventory.ContainerHopper", inventory, backing, player);
    return Reflect.make("net.minecraft.inventory.InventoryCrafting", owner, 3, 3);
  }

  private static Object book(int level) {
    Object item = stack("minecraft:enchanted_book", 0),
        enchantment = Reflect.registry("ENCHANTMENTS", "minecraft:efficiency"),
        data = Reflect.make("net.minecraft.enchantment.EnchantmentData", enchantment, level);
    Reflect.call(
        Reflect.type("net.minecraft.item.ItemEnchantedBook"),
        "addEnchantment/func_92115_a",
        item,
        data);
    return item;
  }

  public static void run(LegacyRuntime runtime, Object world, Object player) {
    runtime.requireThread();
    LegacyProgression.postInit();
    require(LegacyProgression.sieveProviderRuns() > 0, "native default-provider executed");
    require(LegacyProgression.rawOnly(), "ProjectE final map contains only explicit raw values");
    Object emc = Reflect.get(Reflect.type("moze_intel.projecte.emc.EMCMapper"), "emc");
    @SuppressWarnings("unchecked")
    Map<Object, Object> map = (Map<Object, Object>) emc;
    Set<String> valued = new HashSet<String>();
    for (Object key : map.keySet())
      valued.add(String.valueOf(Reflect.get(key, "id")) + "|" + Reflect.get(key, "damage"));
    require(
        valued.contains("minecraft:iron_ingot|0") && valued.contains("hbm:ingot_copper|0"),
        "native raw resources carry EMC");
    for (String forbidden :
        new String[] {
          "hbm:plate_iron|0",
          "hbm:plate_steel|0",
          "hbm:ingot_steel|0",
          "hbm:circuit|8",
          "hbm:ingot_uranium_fuel|0",
          "appliedenergistics2:inscriber|0",
          "appliedenergistics2:material|13",
          "dead_earth:defense_wall_v3|0"
        }) require(!valued.contains(forbidden), "unlearnable technology/fuel " + forbidden);
    Object registry =
        Reflect.get(
            Reflect.type("exnihilocreatio.registries.manager.ExNihiloRegistryManager"),
            "SIEVE_REGISTRY");
    require(
        reward(registry, "minecraft:gravel", "hbm:ore_tungsten", 0, 4),
        "actual HBM tungsten ore via diamond mesh");
    require(
        reward(registry, "minecraft:gravel", "hbm:ore_gneiss_lithium", 0, 3),
        "native lithium ore, no nonexistent modern raw ID");
    require(
        reward(registry, "minecraft:gravel", "mysticalagriculture:crafting", 5, 2)
            && reward(registry, "minecraft:dirt", "mysticalagriculture:crafting", 0, 2),
        "MA prosperity/inferium obtainable");
    for (String seed :
        new String[] {"corn", "cucumber", "lettuce", "pepper", "rice", "strawberry", "tomato"})
      require(
          reward(registry, "minecraft:dirt", "xlfoodmod:" + seed + "_seeds", 0, 1),
          "food seed obtainable in wasteland " + seed);
    Object grid = craftingGrid(player);
    Reflect.call(
        grid, "setInventorySlotContents/func_70299_a", 1, stack("minecraft:lapis_block", 0));
    Reflect.call(grid, "setInventorySlotContents/func_70299_a", 3, stack("hbm:plate_steel", 0));
    Reflect.call(grid, "setInventorySlotContents/func_70299_a", 5, stack("hbm:plate_steel", 0));
    Reflect.call(
        grid, "setInventorySlotContents/func_70299_a", 7, stack("minecraft:redstone_block", 0));
    Object upgrade = recipe("dead_earth_progression_efficiency_5");
    Reflect.call(grid, "setInventorySlotContents/func_70299_a", 4, stack("minecraft:book", 0));
    require(
        !(Boolean) Reflect.call(upgrade, "matches/func_77569_a", grid, world),
        "plain book cannot skip progression");
    Reflect.call(grid, "setInventorySlotContents/func_70299_a", 4, book(3));
    require(
        !(Boolean) Reflect.call(upgrade, "matches/func_77569_a", grid, world),
        "wrong enchantment level cannot skip progression");
    Reflect.call(grid, "setInventorySlotContents/func_70299_a", 4, book(4));
    require(
        (Boolean) Reflect.call(upgrade, "matches/func_77569_a", grid, world),
        "exact previous native book accepted");
    Object result = Reflect.call(upgrade, "getCraftingResult/func_77572_b", grid),
        enchants =
            Reflect.call(
                Reflect.type("net.minecraft.enchantment.EnchantmentHelper"),
                "getEnchantments/func_82781_a",
                result);
    Object efficiency = Reflect.registry("ENCHANTMENTS", "minecraft:efficiency");
    require(
        ((Number) ((Map<?, ?>) enchants).get(efficiency)).intValue() == 5,
        "native crafted book contains EfficiencyV");
    for (String id :
        new String[] {
          "manual_sieve",
          "auto_sieve",
          "auto_hammer",
          "simple_rf_crafter",
          "ae_inscriber",
          "ae_silicon_press",
          "ae_calculation_press",
          "ae_engineering_press",
          "ae_logic_press",
          "me_crafting_unit",
          "me_molecular_assembler",
          "me_controller"
        }) recipe("dead_earth_progression_" + id);
  }
}
