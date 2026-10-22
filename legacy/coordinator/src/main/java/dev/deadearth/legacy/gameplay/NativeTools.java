package dev.deadearth.legacy.gameplay;

import dev.deadearth.legacy.*;
import java.nio.file.*;
import java.util.*;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.*;

/** Focused legacy tools built on HBM's native harvesting, battery and ability engine. */
public final class NativeTools {
  private static final NativeTools LISTENER = new NativeTools();
  private static final Map<String, Integer> TIERS = new LinkedHashMap<String, Integer>();
  private static final Map<String, Object> ITEMS = new LinkedHashMap<String, Object>();
  private static Config config;

  public static final class Config {
    public int[] durability = {900, 2400, 6000};
    public float[] miningSpeed = {6, 10, 15};
    public long[] electricCapacity = {250000, 1000000, 4000000};
    public long[] electricChargeRate = {2000, 4000, 8000};
    public long[] electricCostPerBlock = {80, 120, 180};
    public int efficiencyMaximum = 10,
        fortuneMaximum = 5,
        unbreakingMaximum = 5,
        maximumAnvilCost = 39;

    public void validate() {
      if (durability.length != 3
          || miningSpeed.length != 3
          || electricCapacity.length != 3
          || electricChargeRate.length != 3
          || electricCostPerBlock.length != 3)
        throw new IllegalArgumentException("Three tool tiers required");
      for (int i = 0; i < 3; i++)
        if (durability[i] < 32
            || durability[i] > 1000000
            || !Float.isFinite(miningSpeed[i])
            || miningSpeed[i] < 1
            || miningSpeed[i] > 100
            || electricCapacity[i] < 1000
            || electricCapacity[i] > 1000000000
            || electricChargeRate[i] < 1
            || electricChargeRate[i] > electricCapacity[i]
            || electricCostPerBlock[i] < 1
            || electricCostPerBlock[i] > electricCapacity[i])
          throw new IllegalArgumentException("Invalid tool tier");
      if (efficiencyMaximum < 5
          || efficiencyMaximum > 20
          || fortuneMaximum < 3
          || fortuneMaximum > 10
          || unbreakingMaximum < 3
          || unbreakingMaximum > 10
          || maximumAnvilCost < 1
          || maximumAnvilCost > 39)
        throw new IllegalArgumentException("Invalid enchantment limits");
    }
  }

  public static void install(Path directory) {
    try {
      config =
          JsonFiles.JSON.fromJson(
              JsonFiles.read(directory.resolve("dead-earth/tools.json"), 32768), Config.class);
      config.validate();
    } catch (Exception e) {
      throw new IllegalStateException("Invalid tool configuration", e);
    }
    MinecraftForge.EVENT_BUS.register(LISTENER);
  }

  public static void registerItems(Object registry) {
    Object flat =
        Reflect.get(Reflect.type("com.hbm.handler.ability.IToolAreaAbility"), "HAMMER_FLAT");
    Object miner =
        Reflect.get(Reflect.type("com.hbm.items.tool.ItemToolAbility$EnumToolType"), "MINER");
    for (int tier = 1; tier <= 3; tier++) {
      int n = tier - 1;
      Object material =
          Reflect.call(
              Reflect.type("net.minecraftforge.common.util.EnumHelper"),
              "addToolMaterial",
              "DEAD_EARTH_TOOL_" + tier,
              1 + tier,
              config.durability[n],
              config.miningSpeed[n],
              2f,
              18);
      Object repair =
          Reflect.make(
              "net.minecraft.item.ItemStack",
              Reflect.registry(
                  "ITEMS",
                  tier == 1
                      ? "hbm:ingot_steel"
                      : tier == 2 ? "hbm:ingot_titanium" : "hbm:ingot_tungsten"));
      Reflect.call(material, "setRepairItem", repair);
      for (boolean electric : new boolean[] {false, true}) {
        String name = (electric ? "mining_gadget_v" : "hammer_v") + tier, id = "dead_earth:" + name;
        Object item =
            electric
                ? Reflect.make(
                    "com.hbm.items.tool.ItemToolAbilityPower",
                    3f,
                    -2.8f,
                    0d,
                    material,
                    miner,
                    config.electricCapacity[n],
                    config.electricChargeRate[n],
                    config.electricCostPerBlock[n],
                    id)
                : Reflect.make(
                    "com.hbm.items.tool.ItemToolAbility", 3f, -2.8f, 0d, material, miner, id);
        // HBM's model builder has a fixed hbm:items domain. These are our own unique sprites.
        Reflect.set(item, "texturePath", "dead_earth_" + name);
        HbmToolApi.configure(
            item,
            flat,
            n,
            "dead_earth." + name,
            Reflect.get(
                Reflect.type("net.minecraft.creativetab.CreativeTabs"), "TOOLS/field_78040_i"));
        Reflect.call(registry, "register", item);
        TIERS.put(id, tier);
        ITEMS.put(id, item);
      }
    }
  }

  /** Native HBM registers and bakes its ability-tool sprites; no duplicate model handler. */
  public static void registerModels() {}

  public static Config configuration() {
    return config;
  }

  /** Called from the native HBM plane loop; only our larger manual hammers opt in. */
  public static int wallBottom(int y, int radius, Object player, Object tool) {
    boolean walking = radius == 2 && tool == ITEMS.get("dead_earth:hammer_v2")
        || radius == 3 && tool == ITEMS.get("dead_earth:hammer_v3");
    if (!walking) return y - radius;
    double feet = ((Number) Reflect.get(player, "posY/field_70163_u")).doubleValue();
    return HammerPlane.bottom(y, radius, feet, true);
  }

  public static int wallTop(int y, int radius, Object player, Object tool) {
    return wallBottom(y, radius, player, tool) + 2 * radius;
  }

  public static boolean isTool(Object stack) {
    return stack != null && TIERS.containsKey(itemId(stack));
  }

  private static String itemId(Object stack) {
    Object item = Reflect.call(stack, "getItem/func_77973_b");
    Object id = Reflect.call(item, "getRegistryName");
    return id == null ? "" : id.toString();
  }

  private static boolean empty(Object stack) {
    return stack == null || (Boolean) Reflect.call(stack, "isEmpty/func_190926_b");
  }

  public static void initialize(Object stack) {
    if (empty(stack) || !isTool(stack)) return;
    Object tag = Reflect.call(stack, "getTagCompound/func_77978_p");
    if (tag != null && (Boolean) Reflect.call(tag, "hasKey/func_74764_b", "deadEarthTool")) return;
    Object item = Reflect.call(stack, "getItem/func_77973_b"),
        configuration = HbmToolApi.configuration(item, stack);
    @SuppressWarnings("unchecked")
    List<Object> presets = (List<Object>) Reflect.get(configuration, "presets");
    Object flat =
        Reflect.get(Reflect.type("com.hbm.handler.ability.IToolAreaAbility"), "HAMMER_FLAT");
    for (int i = 0; i < presets.size(); i++)
      if (Reflect.get(presets.get(i), "areaAbility") == flat) {
        Reflect.set(configuration, "currentPreset", i);
        break;
      }
    HbmToolApi.configuration(item, stack, configuration);
    tag = Reflect.call(stack, "getTagCompound/func_77978_p");
    Reflect.call(tag, "setBoolean/func_74757_a", "deadEarthTool", true);
  }

  @SubscribeEvent
  public void event(Event event) {
    String name = event.getClass().getName();
    if (name.endsWith("TickEvent$PlayerTickEvent")) {
      Object player = Reflect.get(event, "player"), world = Reflect.world(player);
      if (!(Boolean) Reflect.get(world, "isRemote/field_72995_K"))
        initialize(Reflect.call(player, "getHeldItemMainhand/func_184614_ca"));
    } else if (name.endsWith("ItemTooltipEvent")) {
      Object stack = Reflect.call(event, "getItemStack");
      if (!isTool(stack)) return;
      @SuppressWarnings("unchecked")
      List<String> lines = (List<String>) Reflect.call(event, "getToolTip");
      int tier = TIERS.get(itemId(stack));
      lines.add(
          "\u00a77"
              + (2 * tier + 1)
              + " x "
              + (2 * tier + 1)
              + " | HBM ability menu / sneak: precise mining");
      if (itemId(stack).contains("gadget"))
        lines.add("\u00a7bHBM HE battery: native charger or DEAD EARTH charger");
    } else if (name.endsWith("AnvilUpdateEvent")) anvil(event);
  }

  private static int cap(String id) {
    return id.equals("minecraft:efficiency")
        ? config.efficiencyMaximum
        : id.equals("minecraft:fortune")
            ? config.fortuneMaximum
            : id.equals("minecraft:unbreaking")
                ? config.unbreakingMaximum
                : id.equals("minecraft:silk_touch") ? 1 : 0;
  }

  @SuppressWarnings("unchecked")
  private static void anvil(Event event) {
    Object left = Reflect.call(event, "getLeft"), right = Reflect.call(event, "getRight");
    if (empty(left) || empty(right) || !itemId(right).equals("minecraft:enchanted_book")) return;
    Class<?> helper = Reflect.type("net.minecraft.enchantment.EnchantmentHelper");
    Map<Object, Integer> a =
        new LinkedHashMap<Object, Integer>(
            (Map<Object, Integer>) Reflect.call(helper, "getEnchantments/func_82781_a", left));
    Map<Object, Integer> b =
        (Map<Object, Integer>) Reflect.call(helper, "getEnchantments/func_82781_a", right);
    if (b.isEmpty()) return;
    boolean high = false;
    int levels = 0;
    boolean book = itemId(left).equals("minecraft:enchanted_book");
    for (Map.Entry<Object, Integer> row : b.entrySet()) {
      String id = Reflect.call(row.getKey(), "getRegistryName").toString();
      int maximum = cap(id);
      if (maximum == 0) return;
      int current = a.containsKey(row.getKey()) ? a.get(row.getKey()) : 0;
      int target =
          Math.min(
              maximum, current == row.getValue() ? current + 1 : Math.max(current, row.getValue()));
      if (!book && !(Boolean) Reflect.call(row.getKey(), "canApply/func_92089_a", left)) return;
      for (Object old : a.keySet())
        if (old != row.getKey()
            && !(Boolean) Reflect.call(row.getKey(), "isCompatibleWith/func_191560_c", old)) return;
      int vanilla = ((Number) Reflect.call(row.getKey(), "getMaxLevel/func_77325_b")).intValue();
      high |= target > vanilla || current > vanilla;
      levels += target;
      a.put(row.getKey(), target);
    }
    if (!high) return;
    Object out = Reflect.call(left, "copy/func_77946_l");
    Reflect.call(helper, "setEnchantments/func_82782_a", a, out);
    String rename = (String) Reflect.call(event, "getName");
    if (rename != null && !rename.trim().isEmpty())
      Reflect.call(out, "setStackDisplayName/func_151001_c", rename);
    Reflect.call(event, "setOutput", out);
    Reflect.call(event, "setCost", Math.min(config.maximumAnvilCost, Math.max(1, levels * 2)));
    Reflect.call(event, "setMaterialCost", 1);
  }
}
