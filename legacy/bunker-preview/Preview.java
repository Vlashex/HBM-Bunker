package dev.deadearth.bunkerpreview;

import com.google.gson.*;
import dev.deadearth.legacy.JsonFiles;
import dev.deadearth.legacy.Reflect;
import java.lang.reflect.Array;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.eventhandler.*;

/** Local single-player gallery only. Not included by any server or pack builder. */
@Mod(
    modid = "deadearth_bunker_preview",
    name = "DEAD EARTH Bunker Gallery",
    version = "0.1.0",
    clientSideOnly = true,
    dependencies = "required-after:dead_earth",
    acceptableRemoteVersions = "*")
public final class Preview {
  private Path game, save, reportPath;
  private JsonObject plan;
  private String runId;
  private int ticks, cursor;
  private boolean opening, configured, positioned;
  private volatile boolean built, failed;
  private final Map<String, Object> states = new HashMap<String, Object>();

  @Mod.EventHandler
  public void pre(FMLPreInitializationEvent event) {
    try {
      if (!Boolean.getBoolean("deadearth.bunkerPreview"))
        throw new IllegalStateException("Explicit preview launch flag required");
      game = Paths.get("").toRealPath();
      if (!game.getParent().getFileName().toString().startsWith("bunker-preview-"))
        throw new IllegalStateException("Preview requires its isolated directory");
      Path input = game.resolve("bunker-preview.json");
      if (Files.isSymbolicLink(input) || Files.size(input) > 8388608)
        throw new IllegalStateException("Unsafe or oversized gallery plan");
      plan =
          new JsonParser()
              .parse(new String(Files.readAllBytes(input), StandardCharsets.UTF_8))
              .getAsJsonObject();
      if (plan.get("schema").getAsInt() != 1
          || !plan.get("kind").getAsString().equals("dead-earth-bunker-preview"))
        throw new IllegalStateException("Unknown gallery plan");
      runId = UUID.fromString(plan.get("runId").getAsString()).toString();
      String folder = plan.get("saveFolder").getAsString();
      if (!folder.matches("bunker-gallery-[a-f0-9]{8}"))
        throw new IllegalStateException("Unsafe save name");
      save = game.resolve("saves").resolve(folder).toRealPath();
      if (!save.getParent().equals(game.resolve("saves").toRealPath())
          || !new String(
                  Files.readAllBytes(save.resolve(".dead-earth-bunker-preview")),
                  StandardCharsets.UTF_8)
              .equals(runId)) throw new IllegalStateException("World ownership marker mismatch");
      if (plan.getAsJsonArray("blocks").size() > 100000)
        throw new IllegalStateException("Gallery block budget exceeded");
      reportPath = game.resolve("bunker-preview-report.json");
      status("awaiting_menu", null);
      MinecraftForge.EVENT_BUS.register(this);
    } catch (Exception error) {
      throw new IllegalStateException("Invalid isolated bunker preview", error);
    }
  }

  @SubscribeEvent
  public void events(Event event) {
    if (failed) return;
    try {
      String name = event.getClass().getName();
      if (name.endsWith("ClientTickEvent") && Reflect.get(event, "phase").toString().equals("END"))
        clientTick();
      if (name.endsWith("ServerTickEvent") && Reflect.get(event, "phase").toString().equals("END"))
        serverTick();
    } catch (Throwable error) {
      failed = true;
      error.printStackTrace();
      status(
          "failed", error.getClass().getSimpleName() + ": " + String.valueOf(error.getMessage()));
    }
  }

  private Object enumValue(String type, String name) {
    for (Object item : Reflect.type(type).getEnumConstants())
      if (((Enum<?>) item).name().equals(name)) return item;
    throw new IllegalStateException("Missing enum " + type + " / " + name);
  }

  private void clientTick() {
    if (opening || ++ticks < 40) return;
    Object client =
        Reflect.call(Reflect.type("net.minecraft.client.Minecraft"), "getMinecraft/func_71410_x");
    if (Reflect.get(client, "world/field_71441_e") != null) return;
    Object screen = Reflect.get(client, "currentScreen/field_71462_r");
    if (screen == null || !Reflect.type("net.minecraft.client.gui.GuiMainMenu").isInstance(screen))
      return;
    opening = true;
    status("opening_world", null);
    Object flat =
        Reflect.call(
            Reflect.type("net.minecraft.world.WorldType"), "parseWorldType/func_77130_a", "flat");
    Object settings =
        Reflect.make(
            "net.minecraft.world.WorldSettings",
            plan.get("seed").getAsLong(),
            enumValue("net.minecraft.world.GameType", "CREATIVE"),
            false,
            false,
            flat);
    Reflect.call(
        settings,
        "setGeneratorOptions/func_82750_a",
        "3;minecraft:bedrock,62*minecraft:stone,minecraft:stone;1;");
    Reflect.call(settings, "enableCommands/func_77159_a");
    Reflect.call(
        client,
        "launchIntegratedServer/func_71371_a",
        plan.get("saveFolder").getAsString(),
        plan.get("title").getAsString(),
        settings);
  }

  private void serverTick() throws Exception {
    Object handler =
        Reflect.call(Reflect.type("net.minecraftforge.fml.common.FMLCommonHandler"), "instance");
    Object server = Reflect.call(handler, "getMinecraftServerInstance");
    if (server == null
        || Boolean.TRUE.equals(Reflect.call(server, "isDedicatedServer/func_71262_S"))) return;
    Object world = Reflect.call(server, "getWorld/func_71218_a", 0);
    if (world == null) return;
    Path actual =
        ((java.io.File)
                Reflect.call(
                    Reflect.call(world, "getSaveHandler/func_72860_G"),
                    "getWorldDirectory/func_75765_b"))
            .toPath()
            .toRealPath();
    if (!actual.equals(save)) return;
    if (!configured) {
      for (JsonElement element : plan.getAsJsonArray("blocks")) {
        JsonObject b = element.getAsJsonObject();
        int x = integer(b, "x"), y = integer(b, "y"), z = integer(b, "z");
        if (Math.abs(x) > 100 || y < 0 || y > 80 || Math.abs(z) > 50)
          throw new IllegalStateException("Block outside gallery bounds");
        String id = b.get("id").getAsString();
        int meta = integer(b, "meta");
        if (meta < 0 || meta > 15) throw new IllegalStateException("Invalid block metadata");
        String key = id + "#" + meta;
        if (!states.containsKey(key)) {
          Object block = Reflect.registry("BLOCKS", id);
          if (block == null || !id.equals(String.valueOf(Reflect.call(block, "getRegistryName"))))
            throw new IllegalStateException("Missing gallery block " + id);
          states.put(key, Reflect.call(block, "getStateFromMeta/func_176203_a", meta));
        }
      }
      Object rules = Reflect.call(world, "getGameRules/func_82736_K");
      for (String key :
          new String[] {
            "doMobSpawning",
            "doDaylightCycle",
            "doWeatherCycle",
            "mobGriefing",
            "commandBlockOutput"
          }) Reflect.call(rules, "setOrCreateGameRule/func_82764_b", key, "false");
      Reflect.call(rules, "setOrCreateGameRule/func_82764_b", "keepInventory", "true");
      Reflect.call(rules, "setOrCreateGameRule/func_82764_b", "randomTickSpeed", "0");
      Reflect.call(
          server,
          "setDifficultyForAllWorlds/func_147139_a",
          enumValue("net.minecraft.world.EnumDifficulty", "PEACEFUL"));
      Reflect.call(world, "setWorldTime/func_72877_b", 6000L);
      Object info = Reflect.call(world, "getWorldInfo/func_72912_H");
      Reflect.call(info, "setRaining/func_76084_b", false);
      Reflect.call(info, "setThundering/func_76069_a", false);
      Reflect.call(world, "setSpawnPoint/func_175652_B", Reflect.pos(-56, 64, -18));
      built = Files.isRegularFile(save.resolve(".bunker-preview-ready.json"));
      positioned = Files.isRegularFile(save.resolve(".bunker-preview-player-ready"));
      configured = true;
      status(built ? "waiting_for_player" : "building", null);
    }
    if (!built) {
      JsonArray blocks = plan.getAsJsonArray("blocks");
      int end = Math.min(blocks.size(), cursor + 512);
      while (cursor < end) {
        JsonObject b = blocks.get(cursor++).getAsJsonObject();
        Reflect.call(
            world,
            "setBlockState/func_180501_a",
            position(b),
            states.get(b.get("id").getAsString() + "#" + integer(b, "meta")),
            2);
      }
      if (cursor == blocks.size()) {
        decorate(world);
        int verified = 0;
        for (JsonElement row : plan.getAsJsonArray("checks")) {
          JsonObject c = row.getAsJsonObject();
          if (!Reflect.blockId(world, position(c)).equals(c.get("id").getAsString()))
            throw new IllegalStateException("Gallery placement verification failed");
          verified++;
        }
        JsonFiles.write(
            save.resolve(".bunker-preview-ready.json"),
            JsonFiles.map("runId", runId, "blocks", cursor, "verified", verified, "variants", 3));
        built = true;
        status("waiting_for_player", null);
      }
    }
    if (built && !positioned) {
      List<?> players = (List<?>) Reflect.get(world, "playerEntities/field_73010_i");
      if (!players.isEmpty()) {
        Object player = players.get(0);
        Reflect.call(
            player,
            "setGameType/func_71033_a",
            enumValue("net.minecraft.world.GameType", "CREATIVE"));
        Object connection = Reflect.get(player, "connection/field_71135_a");
        Reflect.call(connection, "setPlayerLocation/func_147364_a", -55.5d, 64d, -18.5d, 0f, 0f);
        Reflect.call(
            player,
            "sendMessage/func_145747_a",
            Reflect.make(
                "net.minecraft.util.text.TextComponentString",
                "Бункеры готовы: A — x=-56; B — x=0; C — x=56. Входы обращены к дорожке. Творческий"
                    + " режим, без мобов. Это отдельный одиночный мир."));
        Files.write(
            save.resolve(".bunker-preview-player-ready"), runId.getBytes(StandardCharsets.UTF_8));
        positioned = true;
        status("ready", null);
      }
    }
  }

  private void decorate(Object world) {
    for (JsonElement row : plan.getAsJsonArray("signs")) {
      JsonObject s = row.getAsJsonObject();
      Object tile = Reflect.call(world, "getTileEntity/func_175625_s", position(s));
      if (tile == null) throw new IllegalStateException("Gallery sign was not placed");
      Object text = Array.newInstance(Reflect.type("net.minecraft.util.text.ITextComponent"), 4);
      for (int i = 0; i < 4; i++)
        Array.set(
            text,
            i,
            Reflect.make(
                "net.minecraft.util.text.TextComponentString",
                s.getAsJsonArray("lines").get(i).getAsString()));
      Reflect.set(tile, "signText/field_145915_a", text);
      Reflect.call(tile, "markDirty/func_70296_d");
      Reflect.call(
          world,
          "notifyBlockUpdate/func_184138_a",
          position(s),
          Reflect.state(world, position(s)),
          Reflect.state(world, position(s)),
          3);
    }
    for (JsonElement row : plan.getAsJsonArray("chests")) {
      JsonObject c = row.getAsJsonObject();
      Object tile = Reflect.call(world, "getTileEntity/func_175625_s", position(c));
      if (tile == null) throw new IllegalStateException("Gallery starter chest was not placed");
      int slot = 0;
      for (JsonElement entry : c.getAsJsonArray("items")) {
        JsonObject item = entry.getAsJsonObject();
        Object nativeItem = Reflect.registry("ITEMS", item.get("id").getAsString());
        if (nativeItem == null) throw new IllegalStateException("Missing starter item");
        Reflect.call(
            tile,
            "setInventorySlotContents/func_70299_a",
            slot++,
            Reflect.make(
                "net.minecraft.item.ItemStack", nativeItem, item.get("count").getAsInt(), 0));
      }
      Reflect.call(tile, "markDirty/func_70296_d");
    }
  }

  private static int integer(JsonObject object, String key) {
    return object.get(key).getAsInt();
  }

  private static Object position(JsonObject object) {
    return Reflect.pos(integer(object, "x"), integer(object, "y"), integer(object, "z"));
  }

  private void status(String state, String error) {
    if (reportPath != null)
      JsonFiles.write(
          reportPath,
          JsonFiles.map(
              "schema",
              1,
              "runId",
              runId,
              "state",
              state,
              "error",
              error,
              "placed",
              cursor,
              "total",
              plan.getAsJsonArray("blocks").size(),
              "singleplayer",
              true,
              "dedicatedServerChanged",
              false,
              "autoQuit",
              false));
  }
}
