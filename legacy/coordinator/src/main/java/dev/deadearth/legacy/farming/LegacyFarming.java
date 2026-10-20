package dev.deadearth.legacy.farming;

import dev.deadearth.legacy.Reflect;
import java.nio.file.Path;
import java.util.*;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.*;

/**
 * Focused Botany-Pots substitute: no recipes for fake crops, no new energy loop, native drops only.
 */
public final class LegacyFarming {
  static FarmingConfig settings = new FarmingConfig();
  private static Object tray;
  private static boolean installed;
  private static final Map<Object, LinkedHashSet<CropTrayTile>> QUEUES =
      new WeakHashMap<Object, LinkedHashSet<CropTrayTile>>();

  public static void install(Path config) {
    settings = FarmingConfig.load(config.resolve("dead-earth/farming.json"));
    if (!installed) {
      Reflect.call(
          Reflect.type("net.minecraftforge.fml.common.registry.GameRegistry"),
          "registerTileEntity",
          CropTrayTile.class,
          Reflect.make("net.minecraft.util.ResourceLocation", "dead_earth:crop_tray"));
      MinecraftForge.EVENT_BUS.register(new LegacyFarming());
      installed = true;
    }
  }

  public static void registerBlocks(Object registry) {
    tray = new CropTray();
    Reflect.call(registry, "register", tray);
  }

  public static void registerItems(Object registry) {
    Object item = Reflect.make("net.minecraft.item.ItemBlock", tray);
    Reflect.call(item, "setRegistryName", "dead_earth", "crop_tray");
    Reflect.call(registry, "register", item);
  }

  public static void registerModels() {
    Object item =
        Reflect.call(
            Reflect.type("net.minecraft.item.Item"), "getItemFromBlock/func_150898_a", tray);
    Reflect.call(
        Reflect.type("net.minecraftforge.client.model.ModelLoader"),
        "setCustomModelResourceLocation",
        item,
        0,
        Reflect.make(
            "net.minecraft.client.renderer.block.model.ModelResourceLocation",
            "dead_earth:crop_tray",
            "inventory"));
  }

  static void request(Object world, CropTrayTile tile) {
    LinkedHashSet<CropTrayTile> queue = QUEUES.get(world);
    if (queue == null) {
      queue = new LinkedHashSet<CropTrayTile>();
      QUEUES.put(world, queue);
    }
    if (queue.size() < settings.maxQueuedTrays)
      queue.add(tile); // Full queues retry from the tile; no crop state is lost.
  }

  private static void drain() {
    if (!settings.enabled) return;
    for (Map.Entry<Object, LinkedHashSet<CropTrayTile>> entry : QUEUES.entrySet()) {
      Iterator<CropTrayTile> iterator = entry.getValue().iterator();
      for (int n = 0; n < settings.harvestAttemptsPerTick && iterator.hasNext(); n++) {
        CropTrayTile tile = iterator.next();
        iterator.remove();
        if (tile.loadedIn(entry.getKey())) tile.harvest(entry.getKey());
      }
    }
  }

  static void message(Object player, String key, Object... args) {
    Object text = Reflect.make("net.minecraft.util.text.TextComponentTranslation", key, args);
    Reflect.call(player, "sendStatusMessage/func_146105_b", text, true);
  }

  @SubscribeEvent(priority = EventPriority.LOWEST)
  public void events(Event event) {
    String name = event.getClass().getName();
    if (name.endsWith("ServerTickEvent")) {
      if (Reflect.get(event, "phase").toString().equals("END")) drain();
    } else if (name.endsWith("WorldEvent$Unload")) {
      Object world = Reflect.call(event, "getWorld");
      if (!(Boolean) Reflect.get(world, "isRemote/field_72995_K")) QUEUES.remove(world);
    } else if (name.endsWith("PlayerInteractEvent$RightClickBlock")) {
      Object world = Reflect.call(event, "getWorld");
      if ((Boolean) Reflect.get(world, "isRemote/field_72995_K")) return;
      Object pos = Reflect.call(event, "getPos");
      if (!Reflect.loaded(world, pos)) return;
      Object tile = Reflect.call(world, "getTileEntity/func_175625_s", pos);
      if (!(tile instanceof CropTrayTile)) return;
      Object player = Reflect.call(event, "getEntityPlayer");
      if ((Boolean) Reflect.call(player, "isSpectator/func_175149_v")) return;
      Object hand = Reflect.call(event, "getHand");
      if (!hand.toString().equals("MAIN_HAND")) return;
      ((CropTrayTile) tile).interact(player, hand);
      Reflect.call(
          event,
          "setCancellationResult",
          Reflect.get(Reflect.type("net.minecraft.util.EnumActionResult"), "SUCCESS"));
      event.setCanceled(true);
    } else if (name.endsWith("ItemTooltipEvent")) {
      Object stack = Reflect.call(event, "getItemStack");
      if (FarmingStacks.count(stack) == 0) return;
      String id = FarmingStacks.id(stack);
      boolean cropTray = id.equals("dead_earth:crop_tray");
      if (!cropTray && !id.equals(settings.acceleratorBlock)) return;
      @SuppressWarnings("unchecked")
      List<String> tooltip = (List<String>) Reflect.call(event, "getToolTip");
      if (cropTray) {
        for (String key :
            new String[] {
              "dead_earth.farming.tooltip",
              "dead_earth.farming.instructions",
              "dead_earth.farming.automation"
            })
          tooltip.add(
              (String)
                  Reflect.call(
                      Reflect.type("net.minecraft.util.text.translation.I18n"),
                      "translateToLocal/func_74838_a",
                      key));
      }
      tooltip.add(
          (String)
              Reflect.call(
                  Reflect.type("net.minecraft.util.text.translation.I18n"),
                  "translateToLocalFormatted/func_74837_a",
                  "dead_earth.farming.acceleration",
                  new Object[] {
                    settings.maxAccelerators,
                    settings.acceleratorDepth,
                    settings.growthRate(settings.maxAccelerators)
                  }));
    }
  }
}
