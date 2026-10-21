package dev.deadearth.legacy;

import dev.deadearth.legacy.climate.LegacyClimate;
import dev.deadearth.legacy.defense.LegacyDefense;
import dev.deadearth.legacy.farming.LegacyFarming;
import dev.deadearth.legacy.gameplay.*;
import dev.deadearth.legacy.machinery.LegacyMachinery;
import dev.deadearth.legacy.progression.LegacyProgression;
import dev.deadearth.legacy.sieving.LegacySieving;
import dev.deadearth.legacy.worldgen.LegacyWasteland;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.*;
import net.minecraftforge.fml.common.eventhandler.*;
import net.minecraftforge.fml.common.gameevent.TickEvent;

@Mod(
    modid = "dead_earth",
    name = "DEAD EARTH SRP",
    version = "1.1.0",
    dependencies =
        "required-after:srparasites@[1.9.21];required-after:epicsiegemod@[13.169];required-after:hbm;after:appliedenergistics2;after:mysticalagriculture;after:exnihilocreatio;after:projecte;after:xlfoodmod;after:rftools;after:inventorysorter",
    acceptedMinecraftVersions = "[1.12.2]")
public final class DeadEarthLegacy {
  @Mod.Instance("dead_earth")
  public static DeadEarthLegacy instance;

  public static volatile LegacyRuntime runtime;
  public static Path config;
  public static Object relay;

  @Mod.EventHandler
  public void pre(FMLPreInitializationEvent event) {
    config = event.getModConfigurationDirectory().toPath();
    try {
      dev.deadearth.legacy.config.LiveConfiguration.recover(
          config, config.toAbsolutePath().getParent());
    } catch (Exception failure) {
      throw new IllegalStateException(
          "Configuration transaction recovery required; originals preserved", failure);
    }
    MinecraftForge.EVENT_BUS.register(this);
    ZoneRelay.Tickets.install();
    LegacyWasteland.install(config);
    LegacyClimate.install(config);
    LegacyDefense.install(config);
    LegacyFarming.install(config);
    LegacyMachinery.install(config);
    LegacySieving.install(config);
    NativeTools.install(config);
    LegacyProgression.install(config);
    dev.deadearth.legacy.materials.LegacyMaterials.install(config);
    dev.deadearth.legacy.combat.RelaySiege.install(config);
    LegacyClient.install();
  }

  @Mod.EventHandler
  public void initialized(FMLPostInitializationEvent event) {
    dev.deadearth.legacy.combat.LegacySiege.configure(config);
    Reflect.call(
        Reflect.type("net.minecraftforge.fml.common.registry.GameRegistry"),
        "registerTileEntity",
        ZoneRelay.RelayTile.class,
        Reflect.make("net.minecraft.util.ResourceLocation", "dead_earth:zone_relay"));
    LegacyCompat.postInit(config);
    LegacyProgression.postInit();
    dev.deadearth.legacy.materials.LegacyMaterials.postInit();
  }

  @Mod.EventHandler
  public void starting(FMLServerStartingEvent event) {
    Object server = Reflect.call(event, "getServer");
    Class<?> command = Reflect.type("net.minecraft.command.ICommand");
    Object proxy =
        Proxy.newProxyInstance(
            command.getClassLoader(),
            new Class<?>[] {command},
            (o, m, a) -> {
              String n = m.getName();
              if (n.equals("getName") || n.equals("func_71517_b")) return "deadearth";
              if (n.equals("getUsage") || n.equals("func_71518_a")) return "/deadearth status";
              if (n.equals("getAliases")
                  || n.equals("func_71514_a")
                  || n.equals("getTabCompletions")
                  || n.equals("func_184883_a")) return Collections.emptyList();
              if (n.equals("checkPermission") || n.equals("func_184882_a"))
                return Reflect.call(a[1], "canUseCommand/func_70003_b", 2, "deadearth");
              if (n.equals("isUsernameIndex") || n.equals("func_82358_a")) return false;
              if (n.equals("compareTo"))
                return "deadearth"
                    .compareTo(String.valueOf(Reflect.call(a[0], "getName/func_71517_b")));
              if (n.equals("execute") || n.equals("func_184881_a")) {
                Object result;
                try {
                  result =
                      ServerCommandDispatch.call(
                          runtime == null || runtime.onOwnerThread(),
                          task -> Reflect.call(server, "addScheduledTask/func_152344_a", task),
                          () ->
                              runtime == null
                                  ? JsonFiles.map("error", "unavailable")
                                  : runtime.command((String[]) a[2]));
                } catch (RuntimeException invalid) {
                  result = JsonFiles.map("error", "invalid_arguments");
                }
                Object text =
                    Reflect.make(
                        "net.minecraft.util.text.TextComponentString",
                        JsonFiles.JSON.toJson(result));
                Reflect.call(a[1], "sendMessage/func_145747_a", text);
                return null;
              }
              if (n.equals("toString")) return "DeadEarthLegacyCommand";
              if (n.equals("hashCode")) return System.identityHashCode(o);
              if (n.equals("equals")) return o == a[0];
              return null;
            });
    Reflect.call(
        Reflect.call(server, "getCommandManager/func_71187_D"),
        "registerCommand/func_71560_a",
        proxy);
  }

  @Mod.EventHandler
  public void started(FMLServerStartedEvent event) {
    try {
      Object server =
          Reflect.call(
              Reflect.call(
                  Reflect.type("net.minecraftforge.fml.common.FMLCommonHandler"), "instance"),
              "getMinecraftServerInstance");
      runtime = new LegacyRuntime(server, config);
    } catch (Exception error) {
      throw new IllegalStateException(
          "DEAD EARTH legacy coordinator startup failed; save was not replaced", error);
    }
  }

  @Mod.EventHandler
  public void stopping(FMLServerStoppingEvent event) {
    LegacyRuntime r = runtime;
    if (r != null) {
      try {
        r.close();
      } finally {
        dev.deadearth.legacy.combat.LegacySiege.close();
        dev.deadearth.legacy.combat.RelaySiege.close();
        runtime = null;
      }
    }
  }

  @SubscribeEvent
  public void tick(TickEvent.ServerTickEvent event) {
    if (event.phase == TickEvent.Phase.END && runtime != null) runtime.tick();
  }

  @SubscribeEvent(priority = EventPriority.LOWEST)
  public void events(Event event) {
    String name = event.getClass().getName();
    if (name.endsWith("RegistryEvent$Register")) {
      Object registry = Reflect.call(event, "getRegistry");
      Object type = Reflect.call(registry, "getRegistrySuperType");
      if (type == Reflect.type("net.minecraft.block.Block")) {
        relay = new ZoneRelay();
        Reflect.call(registry, "register", relay);
        LegacyWasteland.registerBlocks(registry);
        LegacyClimate.registerBlocks(registry);
        LegacyDefense.registerBlocks(registry);
        LegacyFarming.registerBlocks(registry);
        LegacyMachinery.registerBlocks(registry);
        LegacySieving.registerBlocks(registry);
        try {
          com.google.gson.JsonObject traces =
              JsonFiles.read(config.resolve("dead-earth/director/catalog.json"), 1048576)
                  .getAsJsonObject("traces");
          for (java.util.Map.Entry<String, com.google.gson.JsonElement> trace : traces.entrySet())
            Reflect.call(registry, "register", new TraceMark(trace.getKey()));
        } catch (Exception ex) {
          throw new IllegalStateException("Cannot register trace patterns", ex);
        }
      } else if (type == Reflect.type("net.minecraft.item.Item")) {
        Object item = Reflect.make("net.minecraft.item.ItemBlock", relay);
        Reflect.call(item, "setRegistryName", "dead_earth", "zone_relay");
        Reflect.call(registry, "register", item);
        LegacyWasteland.registerItems(registry);
        LegacyClimate.registerItems(registry);
        LegacyDefense.registerItems(registry);
        LegacyFarming.registerItems(registry);
        LegacyMachinery.registerItems(registry);
        LegacySieving.registerItems(registry);
        NativeTools.registerItems(registry);
      }
      return;
    }
    if (name.endsWith("ModelRegistryEvent")) {
      Object item =
          Reflect.call(
              Reflect.type("net.minecraft.item.Item"), "getItemFromBlock/func_150898_a", relay);
      Reflect.call(
          Reflect.type("net.minecraftforge.client.model.ModelLoader"),
          "setCustomModelResourceLocation",
          item,
          0,
          Reflect.make(
              "net.minecraft.client.renderer.block.model.ModelResourceLocation",
              "dead_earth:zone_relay",
              "inventory"));
      LegacyWasteland.registerModels();
      LegacyClimate.registerModels();
      LegacyDefense.registerModels();
      LegacyFarming.registerModels();
      LegacyMachinery.registerModels();
      LegacySieving.registerModels();
      dev.deadearth.legacy.sieving.SieveVisuals.registerColors();
      NativeTools.registerModels();
      return;
    }
    LegacyRuntime r = runtime;
    if (r == null || !r.onOwnerThread()) return;
    try {
      if (name.endsWith("LivingSpawnEvent$CheckSpawn")) {
        Object e = Reflect.call(event, "getEntityLiving");
        if (!r.world.phaseAllowed(e) || r.protectedAt(Reflect.world(e), Reflect.position(e)))
          event.setResult(Event.Result.DENY);
      } else if (name.endsWith("LivingEvent$LivingUpdateEvent")) {
        Object e = Reflect.call(event, "getEntityLiving");
        if (Reflect.is(e, "net.minecraft.entity.monster.IMob")
            && r.protectedAt(Reflect.world(e), Reflect.position(e))) {
          Object spawn = Reflect.call(Reflect.world(e), "getSpawnPoint/func_175694_M");
          Reflect.call(
              e,
              "setPositionAndUpdate/func_70634_a",
              Reflect.x(spawn) + 20d,
              (double) Reflect.y(spawn),
              Reflect.z(spawn) + 20d);
        }
      } else if (name.endsWith("EntityMobGriefingEvent")) {
        Object e = Reflect.call(event, "getEntity");
        if (r.protectedAt(Reflect.world(e), Reflect.position(e)))
          event.setResult(Event.Result.DENY);
      } else if (!name.startsWith("net.minecraftforge.fml.")) r.event(event);
    } catch (RuntimeException error) {
      throw new IllegalStateException("DEAD EARTH event adapter " + name, error);
    }
  }
}
