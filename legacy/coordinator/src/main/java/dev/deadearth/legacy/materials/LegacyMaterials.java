package dev.deadearth.legacy.materials;

import dev.deadearth.legacy.Reflect;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.*;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.*;

/**
 * Source normalization plus a small same-form migration of old player-held raw stacks. World
 * containers/ME cells are never opened, scanned or rewritten. NBT/capabilities are never discarded.
 */
public final class LegacyMaterials {
  private static MaterialCatalog catalog;
  private static Map<String, String> aliases = Collections.emptyMap();
  private static final Map<Object, Integer> cursors = new WeakHashMap<Object, Integer>();
  private static boolean installed, ready;
  private static long migratedStacks, migratedItems, furnaceOutputs, sieveOutputs, protectedStacks;

  private LegacyMaterials() {}

  public static void install(Path directory) {
    if (installed) return;
    try {
      catalog = MaterialCatalog.load(directory.resolve("dead-earth/materials.json"));
    } catch (Exception invalid) {
      throw new IllegalStateException("Invalid material catalog; original preserved", invalid);
    }
    MinecraftForge.EVENT_BUS.register(new LegacyMaterials());
    installed = true;
  }

  /** Call after the native item/ore registrations. No worlds or saved inventories are touched. */
  public static void postInit() {
    if (!installed) throw new IllegalStateException("Material catalog was not installed");
    if (ready) return;
    Map<String, String> mapping = new LinkedHashMap<String, String>();
    for (MaterialCatalog.Material material : catalog.materials)
      for (MaterialCatalog.Form form : material.forms) {
        stack(form.canonical, 1, true);
        for (String alias : form.legacyAliases)
          if (itemPresent(alias)) put(mapping, alias, form.canonical);
        for (String oreName : form.oreNames) {
          List<?> members =
              (List<?>)
                  Reflect.call(
                      Reflect.type("net.minecraftforge.oredict.OreDictionary"), "getOres", oreName);
          if (members.size() > 1024)
            throw new IllegalStateException("Material OreDictionary bound exceeded");
          for (Object member : members) {
            if (empty(member)) continue;
            String token = token(member);
            // Preserve HBM's own purities, isotopes and alloys even if a broad ore group overlaps.
            if (!token.startsWith("hbm:")
                && !token.startsWith("minecraft:")
                && MaterialCatalog.token(token)) put(mapping, token, form.canonical);
          }
        }
      }
    aliases = Collections.unmodifiableMap(mapping);
    ready = true;
    remapFurnaceOutputs();
    Object registry = sieveRegistry();
    normalizeSieveRegistry(registry);
    // Append after all mods registered their defaults. Every later /exnihiloreload
    // therefore normalizes the newly built native reward table again.
    Class<?> provider =
        Reflect.type("exnihilocreatio.registries.manager.ISieveDefaultRegistryProvider");
    Object adapter =
        Proxy.newProxyInstance(
            provider.getClassLoader(),
            new Class<?>[] {provider},
            (o, m, a) -> {
              if (m.getName().equals("registerRecipeDefaults")) {
                normalizeSieveRegistry(a[0]);
                return null;
              }
              if (m.getName().equals("toString")) return "DeadEarthCanonicalMaterialProvider";
              if (m.getName().equals("hashCode")) return System.identityHashCode(o);
              if (m.getName().equals("equals")) return o == a[0];
              return null;
            });
    Reflect.call(
        Reflect.type("exnihilocreatio.registries.manager.ExNihiloRegistryManager"),
        "registerSieveDefaultRecipeHandler",
        adapter);
  }

  private static void put(Map<String, String> map, String alias, String target) {
    if (alias.equals(target)) return;
    String previous = map.put(alias, target);
    if (previous != null && !previous.equals(target))
      throw new IllegalArgumentException("Ambiguous material alias " + alias);
  }

  /** Input/output use namespace:item:meta, matching the coordinator's price table. */
  public static String canonicalToken(String value) {
    String result = aliases.get(value);
    return result == null ? value : result;
  }

  public static boolean ready() {
    return ready;
  }

  public static int aliasCount() {
    return aliases.size();
  }

  public static Map<String, Long> statistics() {
    Map<String, Long> out = new LinkedHashMap<String, Long>();
    out.put("aliases", (long) aliases.size());
    out.put("migratedStacks", migratedStacks);
    out.put("migratedItems", migratedItems);
    out.put("furnaceOutputs", furnaceOutputs);
    out.put("sieveOutputs", sieveOutputs);
    out.put("protectedStackChecks", protectedStacks);
    return out;
  }

  private static boolean empty(Object value) {
    return value == null || (Boolean) Reflect.call(value, "isEmpty/func_190926_b");
  }

  private static String token(Object value) {
    Object item = Reflect.call(value, "getItem/func_77973_b");
    return Reflect.call(item, "getRegistryName")
        + ":"
        + ((Number) Reflect.call(value, "getMetadata/func_77960_j")).intValue();
  }

  private static boolean itemPresent(String token) {
    String id = token.substring(0, token.lastIndexOf(':'));
    Object item = Reflect.registry("ITEMS", id);
    return item != null && id.equals(String.valueOf(Reflect.call(item, "getRegistryName")));
  }

  private static Object stack(String token, int count, boolean required) {
    if (!itemPresent(token)) {
      if (required) throw new IllegalStateException("Missing canonical material " + token);
      return null;
    }
    int split = token.lastIndexOf(':');
    return Reflect.make(
        "net.minecraft.item.ItemStack",
        Reflect.registry("ITEMS", token.substring(0, split)),
        count,
        Integer.parseInt(token.substring(split + 1)));
  }

  /**
   * Returns the original object unless an exact, safe substitution is possible. Never mutates
   * input.
   */
  public static Object canonicalCopy(Object original) {
    if (!ready || empty(original)) return original;
    String target = aliases.get(token(original));
    if (target == null) return original;
    if ((Boolean) Reflect.call(original, "hasTagCompound/func_77942_o")) {
      protectedStacks++;
      return original;
    }
    Object serialized = Reflect.call(original, "serializeNBT");
    if ((Boolean) Reflect.call(serialized, "hasKey/func_74764_b", "ForgeCaps")) {
      Object capabilities = Reflect.call(serialized, "getCompoundTag/func_74775_l", "ForgeCaps");
      if (!(Boolean) Reflect.call(capabilities, "hasNoTags/func_82582_d")) {
        protectedStacks++;
        return original;
      }
    }
    int count = ((Number) Reflect.call(original, "getCount/func_190916_E")).intValue();
    Object result = stack(target, count, true);
    if (count < 1
        || count > ((Number) Reflect.call(original, "getMaxStackSize/func_77976_d")).intValue()
        || count > ((Number) Reflect.call(result, "getMaxStackSize/func_77976_d")).intValue())
      return original;
    return result;
  }

  @SuppressWarnings("unchecked")
  private static void remapFurnaceOutputs() {
    Object recipes =
        Reflect.call(
            Reflect.type("net.minecraft.item.crafting.FurnaceRecipes"), "instance/func_77602_a");
    Map<Object, Object> outputs =
        (Map<Object, Object>) Reflect.call(recipes, "getSmeltingList/func_77599_b");
    for (Map.Entry<Object, Object> row : outputs.entrySet()) {
      Object original = row.getValue(), normalized = canonicalCopy(original);
      if (normalized != original) {
        row.setValue(normalized);
        furnaceOutputs++;
      }
    }
  }

  private static Object sieveRegistry() {
    return Reflect.get(
        Reflect.type("exnihilocreatio.registries.manager.ExNihiloRegistryManager"),
        "SIEVE_REGISTRY");
  }

  @SuppressWarnings("unchecked")
  public static void normalizeSieveRegistry(Object registry) {
    if (!ready) return;
    Map<Object, Object> inputs = (Map<Object, Object>) Reflect.call(registry, "getRegistry");
    for (Object value : inputs.values()) {
      List<Object> rewards = (List<Object>) value;
      for (int index = 0; index < rewards.size(); index++) {
        Object reward = rewards.get(index), info = Reflect.call(reward, "getDrop");
        if (info == null) continue;
        Object original = Reflect.call(info, "getItemStack"), normalized = canonicalCopy(original);
        if (normalized == original) continue;
        rewards.set(
            index,
            Reflect.make(
                "exnihilocreatio.registries.types.Siftable",
                Reflect.make("exnihilocreatio.util.ItemInfo", normalized),
                ((Number) Reflect.call(reward, "getChance")).floatValue(),
                ((Number) Reflect.call(reward, "getMeshLevel")).intValue()));
        sieveOutputs++;
      }
    }
  }

  private static boolean server(Object player) {
    return !(Boolean) Reflect.get(Reflect.world(player), "isRemote/field_72995_K");
  }

  /**
   * Bounded migration of one player's native inventory. Source contents survive any rejected
   * conversion.
   */
  public static int migrateInventory(Object player, int cursor, int budget) {
    if (!ready || !server(player)) return cursor;
    if (budget < 1 || budget > 16 || cursor < 0)
      throw new IllegalArgumentException("Material inventory budget");
    Object inventory = Reflect.get(player, "inventory/field_71071_by");
    int size = ((Number) Reflect.call(inventory, "getSizeInventory/func_70302_i_")).intValue();
    if (size < 1 || size > 128) throw new IllegalStateException("Material inventory size");
    boolean changed = false;
    for (int index = 0; index < Math.min(size, budget); index++) {
      int slot = cursor++ % size;
      Object original = Reflect.call(inventory, "getStackInSlot/func_70301_a", slot),
          normalized = canonicalCopy(original);
      if (normalized != original) {
        Reflect.call(inventory, "setInventorySlotContents/func_70299_a", slot, normalized);
        migratedStacks++;
        migratedItems += ((Number) Reflect.call(normalized, "getCount/func_190916_E")).intValue();
        changed = true;
      }
    }
    if (changed) {
      Reflect.call(inventory, "markDirty/func_70296_d");
      Object container = Reflect.get(player, "inventoryContainer/field_71069_bz"),
          open = Reflect.get(player, "openContainer/field_71070_bA");
      if (container != null) Reflect.call(container, "detectAndSendChanges/func_75142_b");
      if (open != null && open != container)
        Reflect.call(open, "detectAndSendChanges/func_75142_b");
    }
    return cursor % size;
  }

  private static void itemEntity(Object entity) {
    Object original = Reflect.call(entity, "getItem/func_92059_d"),
        normalized = canonicalCopy(original);
    if (normalized != original) Reflect.call(entity, "setItem/func_92058_a", normalized);
  }

  @SubscribeEvent(priority = EventPriority.LOWEST)
  @SuppressWarnings("unchecked")
  public void event(Event event) {
    if (!ready) return;
    String name = event.getClass().getName();
    if (name.endsWith("TickEvent$PlayerTickEvent")) {
      if (!"END".equals(String.valueOf(Reflect.get(event, "phase")))) return;
      Object player = Reflect.get(event, "player");
      if (!server(player)) return;
      int age = ((Number) Reflect.get(player, "ticksExisted/field_70173_aa")).intValue();
      if (age % catalog.policy.inventoryIntervalTicks != 0) return;
      if (cursors.size() > 128) cursors.clear();
      Integer cursor = cursors.get(player);
      cursors.put(
          player,
          migrateInventory(
              player, cursor == null ? 0 : cursor, catalog.policy.inventorySlotsPerPass));
    } else if (name.endsWith("EntityItemPickupEvent")) {
      Object player = Reflect.call(event, "getEntityPlayer");
      if (server(player)) itemEntity(Reflect.call(event, "getItem"));
    } else if (name.endsWith("BlockEvent$HarvestDropsEvent")) {
      Object world = Reflect.call(event, "getWorld");
      if ((Boolean) Reflect.get(world, "isRemote/field_72995_K")) return;
      List<Object> drops = (List<Object>) Reflect.call(event, "getDrops");
      for (int index = 0; index < Math.min(1024, drops.size()); index++) {
        Object original = drops.get(index), normalized = canonicalCopy(original);
        if (normalized != original) drops.set(index, normalized);
      }
    } else if (name.endsWith("LivingDropsEvent")) {
      Object entity = Reflect.call(event, "getEntityLiving");
      if (!server(entity)) return;
      List<?> drops = (List<?>) Reflect.call(event, "getDrops");
      for (int index = 0; index < Math.min(1024, drops.size()); index++)
        itemEntity(drops.get(index));
    }
  }
}
