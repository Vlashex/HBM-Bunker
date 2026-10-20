package dev.deadearth.legacyprobe.materials;

import dev.deadearth.legacy.*;
import dev.deadearth.legacy.materials.LegacyMaterials;
import java.util.*;

/**
 * Native functional checks: exact-form migration, retained custom NBT, actual furnace/crop outputs.
 */
public final class MaterialsProbe {
  private static void require(boolean condition, String detail) {
    if (!condition) throw new AssertionError(detail);
  }

  private static Object stack(String id, int count, int meta) {
    return Reflect.make("net.minecraft.item.ItemStack", Reflect.registry("ITEMS", id), count, meta);
  }

  private static boolean present(String id) {
    Object item = Reflect.registry("ITEMS", id);
    return item != null && id.equals(String.valueOf(Reflect.call(item, "getRegistryName")));
  }

  private static String token(Object stack) {
    return Reflect.call(Reflect.call(stack, "getItem/func_77973_b"), "getRegistryName")
        + ":"
        + Reflect.call(stack, "getMetadata/func_77960_j");
  }

  private static int count(Object stack) {
    return ((Number) Reflect.call(stack, "getCount/func_190916_E")).intValue();
  }

  private static List<Map<String, Object>> cropRegistrations(
      Object world, Object player, Map<String, Object> recipes) {
    dev.deadearth.legacy.materials.MaterialCatalog catalog;
    try {
      catalog =
          dev.deadearth.legacy.materials.MaterialCatalog.load(
              DeadEarthLegacy.config.resolve("dead-earth/materials.json"));
    } catch (Exception invalid) {
      throw new IllegalStateException(invalid);
    }
    Object inventory = Reflect.get(player, "inventory/field_71071_by");
    Object backing =
        Reflect.make(
            "net.minecraft.inventory.InventoryBasic", "DEAD EARTH material crop probe", false, 5);
    Object owner =
        Reflect.make("net.minecraft.inventory.ContainerHopper", inventory, backing, player);
    Object grid = Reflect.make("net.minecraft.inventory.InventoryCrafting", owner, 3, 3);
    Object empty =
        Reflect.get(Reflect.type("net.minecraft.item.ItemStack"), "EMPTY/field_190927_a");
    List<Map<String, Object>> facts = new ArrayList<Map<String, Object>>();
    for (dev.deadearth.legacy.materials.MaterialCatalog.Material material : catalog.materials) {
      if (material.crop == null) continue;
      dev.deadearth.legacy.materials.MaterialCatalog.Crop crop = material.crop;
      String name = crop.essence.substring(crop.essence.indexOf(':') + 1).replace("_essence", "");
      Object type =
          Reflect.get(
              Reflect.type("com.blakebr0.mysticalagriculture.lib.CropType$Type"),
              name.toUpperCase(Locale.ROOT));
      require((Boolean) Reflect.call(type, "isEnabled"), "native MA CropType enabled: " + name);
      Object cropItem = Reflect.call(type, "getCrop"),
          seed = Reflect.call(type, "getSeed"),
          plant = Reflect.call(type, "getPlant");
      require(
          crop.essence.equals(String.valueOf(Reflect.call(cropItem, "getRegistryName"))),
          "actual registered essence ID matches catalog " + name);
      require(
          ("mysticalagriculture:" + name + "_seeds")
              .equals(String.valueOf(Reflect.call(seed, "getRegistryName"))),
          "actual registered seed ID " + name);
      require(
          ("mysticalagriculture:" + name + "_crop")
              .equals(String.valueOf(Reflect.call(plant, "getRegistryName"))),
          "actual registered plant block " + name);
      Object recipe = recipes.get(material.material);
      require(recipe != null, "explicit canonical crop recipe exists " + name);
      for (int row = 0; row < 3; row++)
        for (int column = 0; column < 3; column++) {
          Object ingredient =
              crop.pattern.get(row).charAt(column) == 'E'
                  ? Reflect.make("net.minecraft.item.ItemStack", cropItem, 1, 0)
                  : empty;
          Reflect.call(grid, "setInventorySlotContents/func_70299_a", row * 3 + column, ingredient);
        }
      require(
          (Boolean) Reflect.call(recipe, "matches/func_77569_a", grid, world),
          "actual native essence grid crafts " + name);
      Object result = Reflect.call(recipe, "getCraftingResult/func_77572_b", grid);
      require(
          token(result).equals(crop.output) && count(result) == crop.count,
          "actual crafted HBM product and count preserved " + name);
      Object drops =
          Reflect.call(Reflect.type("net.minecraft.util.NonNullList"), "create/func_191196_a");
      Object mature = Reflect.call(plant, "getStateFromMeta/func_176203_a", 7);
      Reflect.call(plant, "getDrops", drops, world, Reflect.position(player), mature, 0);
      boolean harvest = false;
      for (Object drop : (List<?>) drops)
        if (token(drop).equals(crop.essence + ":0") && count(drop) > 0) harvest = true;
      require(harvest, "native mature crop yields its actual essence " + name);
      facts.add(
          JsonFiles.map(
              "material",
              material.material,
              "essence",
              crop.essence,
              "seed",
              "mysticalagriculture:" + name + "_seeds",
              "nativeTypeEnabled",
              true,
              "actualCraftMatches",
              true,
              "matureHarvestVerified",
              true,
              "output",
              crop.output,
              "count",
              crop.count));
    }
    return facts;
  }

  public static Map<String, Object> run(LegacyRuntime runtime, Object world, Object player) {
    runtime.requireThread();
    require(LegacyMaterials.ready(), "material runtime initialized");
    int checked = 0;
    List<String> absentOptionalAliases = new ArrayList<String>();
    for (String[] pair :
        new String[][] {
          {"exnihilocreatio:item_ore_copper", "3", "hbm:ingot_copper:0"},
          {"exnihilocreatio:item_ore_copper", "2", "hbm:powder_copper:0"},
          {"exnihilocreatio:item_ore_aluminium", "3", "hbm:ingot_aluminium:0"},
          {"exnihilocreatio:item_ore_lead", "2", "hbm:powder_lead:0"},
          {"exnihilocreatio:item_ore_iron", "3", "minecraft:iron_ingot:0"},
          {"exnihilocreatio:item_ore_gold", "3", "minecraft:gold_ingot:0"}
        }) {
      if (!present(pair[0])) {
        absentOptionalAliases.add(pair[0] + ":" + pair[1]);
        continue;
      }
      Object old = stack(pair[0], 37, Integer.parseInt(pair[1])),
          migrated = LegacyMaterials.canonicalCopy(old);
      require(
          token(migrated).equals(pair[2]) && count(migrated) == 37,
          "native same-form count preserved " + pair[0]);
      require(
          count(old) == 37 && token(old).equals(pair[0] + ":" + pair[1]),
          "conversion did not mutate source stack");
      checked++;
    }
    require(checked >= 2, "real vanilla-backed ExN iron and gold aliases tested");
    for (int meta : new int[] {0, 1}) {
      Object old = stack("exnihilocreatio:item_ore_iron", 12, meta);
      require(
          LegacyMaterials.canonicalCopy(old) == old,
          "pieces/chunks are not whole-ingot/ore aliases");
    }
    for (String[] item :
        new String[][] {
          {"appliedenergistics2:material", "5"},
          {"appliedenergistics2:material", "0"},
          {"minecraft:quartz", "0"},
          {"hbm:ingot_silicon", "0"},
          {"hbm:powder_steel_tiny", "0"},
          {"hbm:ingot_uranium_fuel", "0"}
        }) {
      Object old = stack(item[0], 1, Integer.parseInt(item[1]));
      require(
          LegacyMaterials.canonicalCopy(old) == old,
          "unique/specialized material preserved " + item[0]);
    }
    Object custom = stack("exnihilocreatio:item_ore_iron", 29, 3),
        nbt = Reflect.make("net.minecraft.nbt.NBTTagCompound");
    Reflect.call(nbt, "setString/func_74778_a", "displayName", "Keep this exact old item");
    Reflect.call(custom, "setTagCompound/func_77982_d", nbt);
    Object before = Reflect.call(custom, "serializeNBT");
    require(
        LegacyMaterials.canonicalCopy(custom) == custom
            && before.equals(Reflect.call(custom, "serializeNBT")),
        "tagged legacy stack retained byte-for-byte");
    Object furnace =
        Reflect.call(
            Reflect.type("net.minecraft.item.crafting.FurnaceRecipes"), "instance/func_77602_a");
    Object smelted =
        Reflect.call(
            furnace,
            "getSmeltingResult/func_151395_a",
            stack("exnihilocreatio:item_ore_iron", 1, 1));
    require(
        token(smelted).equals("minecraft:iron_ingot:0") && count(smelted) == 1,
        "native ExN iron chunks smelt to HBM's vanilla-backed iron ingot");
    Object recipes =
        Reflect.get(
            Reflect.type("net.minecraftforge.fml.common.registry.ForgeRegistries"), "RECIPES");
    Set<String> found = new HashSet<String>();
    Map<String, Object> cropRecipes = new LinkedHashMap<String, Object>();
    for (Object recipe : (Collection<?>) Reflect.call(recipes, "getValuesCollection")) {
      String id = String.valueOf(Reflect.call(recipe, "getRegistryName"));
      if (!id.contains(":dead_earth_material_")) continue;
      Object result = Reflect.call(recipe, "getRecipeOutput/func_77571_b");
      require(token(result).startsWith("hbm:"), "resource plants emit explicit HBM products");
      require(
          !token(result).contains("steel") && !token(result).contains("plate"),
          "plants cannot bypass HBM steel/plate metallurgy");
      found.add(
          id.substring(id.indexOf(":dead_earth_material_") + ":dead_earth_material_".length()));
      cropRecipes.put(
          id.substring(id.indexOf(":dead_earth_material_") + ":dead_earth_material_".length()),
          recipe);
    }
    require(
        found.equals(
            new HashSet<String>(
                Arrays.asList(
                    "copper", "aluminium", "lead", "titanium", "tungsten", "sulfur", "niter"))),
        "all seven canonical crop recipes registered");
    List<Map<String, Object>> cropFacts = cropRegistrations(world, player, cropRecipes);
    Object inventory = Reflect.get(player, "inventory/field_71071_by");
    Object old0 = Reflect.call(inventory, "getStackInSlot/func_70301_a", 0),
        old1 = Reflect.call(inventory, "getStackInSlot/func_70301_a", 1);
    try {
      Reflect.call(
          inventory,
          "setInventorySlotContents/func_70299_a",
          0,
          stack("exnihilocreatio:item_ore_iron", 53, 3));
      Reflect.call(inventory, "setInventorySlotContents/func_70299_a", 1, custom);
      LegacyMaterials.migrateInventory(player, 0, 2);
      Object migrated = Reflect.call(inventory, "getStackInSlot/func_70301_a", 0);
      require(
          token(migrated).equals("minecraft:iron_ingot:0") && count(migrated) == 53,
          "bounded native player inventory migration conserves count");
      require(
          Reflect.call(inventory, "getStackInSlot/func_70301_a", 1) == custom,
          "inventory migration skips custom NBT");
    } finally {
      Reflect.call(inventory, "setInventorySlotContents/func_70299_a", 0, old0);
      Reflect.call(inventory, "setInventorySlotContents/func_70299_a", 1, old1);
    }
    return JsonFiles.map(
        "ready",
        true,
        "aliases",
        LegacyMaterials.aliasCount(),
        "testedSameFormCases",
        checked,
        "absentOptionalAliases",
        absentOptionalAliases,
        "nativeFurnace",
        true,
        "canonicalCropRecipes",
        found.size(),
        "cropRegistrations",
        cropFacts,
        "boundedInventoryCountConserved",
        true,
        "taggedStacksPreserved",
        true,
        "pieceChunkAndUniqueResourcesPreserved",
        true,
        "statistics",
        LegacyMaterials.statistics());
  }
}
