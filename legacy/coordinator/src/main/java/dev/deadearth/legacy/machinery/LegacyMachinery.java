package dev.deadearth.legacy.machinery;

import dev.deadearth.legacy.Reflect;
import java.nio.file.Path;
import java.util.*;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.*;

/** Small native energy/ore utilities; HBM remains the source of metallurgy and native yields. */
public final class LegacyMachinery {
  private static MachinerySettings settings;
  private static boolean installed;
  private static final Map<String, Object> blocks = new LinkedHashMap<String, Object>();

  private LegacyMachinery() {}

  public static MachinerySettings configuration() {
    return settings;
  }

  public static void applyConfiguration(MachinerySettings prepared) {
    settings = prepared;
  }

  public static void install(Path config) {
    if (installed) return;
    settings = MachinerySettings.load(config);
    MinecraftForge.EVENT_BUS.register(new LegacyMachinery());
    Reflect.call(
        Reflect.type("net.minecraftforge.fml.common.registry.GameRegistry"),
        "registerTileEntity",
        MachineTile.Charger.class,
        Reflect.make("net.minecraft.util.ResourceLocation", "dead_earth:charger"));
    Reflect.call(
        Reflect.type("net.minecraftforge.fml.common.registry.GameRegistry"),
        "registerTileEntity",
        MachineTile.Processor.class,
        Reflect.make("net.minecraft.util.ResourceLocation", "dead_earth:fortune_processor"));
    installed = true;
  }

  static MachinerySettings settings() {
    if (settings == null) throw new IllegalStateException("Install machinery before tile creation");
    return settings;
  }

  public static void registerBlocks(Object registry) {
    for (boolean processor : new boolean[] {false, true}) {
      String id = processor ? "fortune_processor" : "charger";
      Object block = new MachineBlock(processor);
      blocks.put(id, block);
      Reflect.call(registry, "register", block);
    }
  }

  public static void registerItems(Object registry) {
    for (Map.Entry<String, Object> e : blocks.entrySet()) {
      Object item = Reflect.make("net.minecraft.item.ItemBlock", e.getValue());
      Reflect.call(item, "setRegistryName", "dead_earth", e.getKey());
      Reflect.call(registry, "register", item);
    }
  }

  public static void registerModels() {
    for (String id : blocks.keySet())
      Reflect.call(
          Reflect.type("net.minecraftforge.client.model.ModelLoader"),
          "setCustomModelResourceLocation",
          Reflect.registry("ITEMS", "dead_earth:" + id),
          0,
          Reflect.make(
              "net.minecraft.client.renderer.block.model.ModelResourceLocation",
              "dead_earth:" + id,
              "inventory"));
  }

  @SubscribeEvent(priority = EventPriority.HIGH)
  public void event(Event event) {
    if (!event
        .getClass()
        .getName()
        .equals("net.minecraftforge.event.entity.player.PlayerInteractEvent$RightClickBlock"))
      return;
    Object w = Reflect.call(event, "getWorld"), p = Reflect.call(event, "getPos");
    if (!Reflect.loaded(w, p)) return;
    Object b = Reflect.call(Reflect.state(w, p), "getBlock/func_177230_c");
    if (!(b instanceof MachineBlock)) return;
    event.setCanceled(true);
    Reflect.call(
        event,
        "setCancellationResult",
        Reflect.get(Reflect.type("net.minecraft.util.EnumActionResult"), "SUCCESS"));
    if (!(Boolean) Reflect.get(w, "isRemote/field_72995_K")) {
      Object tile = Reflect.call(w, "getTileEntity/func_175625_s", p);
      if (tile instanceof MachineTile)
        ((MachineTile) tile)
            .interact(Reflect.call(event, "getEntityPlayer"), Reflect.call(event, "getHand"));
    }
  }
}
