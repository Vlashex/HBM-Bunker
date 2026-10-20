package dev.deadearth.legacyprobe;

import com.google.gson.*;
import dev.deadearth.legacy.*;
import dev.deadearth.legacy.climate.LegacyClimate;
import java.lang.invoke.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLServerStartedEvent;
import net.minecraftforge.fml.common.eventhandler.*;

/** Opt-in server companion for the actual GL client. Never packaged in the coordinator. */
@Mod(
    modid = "deadearth_visual_showcase",
    name = "Dead Earth Isolated Visual Showcase",
    version = "1.0.0",
    dependencies =
        "required-after:dead_earth;required-after:exnihilocreatio;required-after:excompressum",
    acceptableRemoteVersions = "*")
public final class VisualShowcase {
  private static final String USER = "DeadEarthProbe";
  private LegacyRuntime runtime;
  private Object world, actor;
  private Path directory;
  private String runId, epoch;
  private int baseX, baseY, baseZ, ticks, readyAt = -1;
  private boolean enabled, readySent, nativeSieveFilled, nativeBonsaiFilled;
  private final List<Map<String, Object>> views = new ArrayList<Map<String, Object>>(),
      frames = new ArrayList<Map<String, Object>>();
  private final Map<String, Object> report = new LinkedHashMap<String, Object>();
  private final List<String> requiredBlocks = new ArrayList<String>(),
      requiredItems = new ArrayList<String>();

  @Mod.EventHandler
  public void started(FMLServerStartedEvent event) {
    // Full native probe compilation includes integration sources; this mod stays inert there.
    if (!Boolean.getBoolean("deadearth.visualShowcase")) return;
    try {
      require(Boolean.getBoolean("deadearth.testControls"), "explicit test controls");
      directory = Paths.get("").toRealPath();
      require(
          Files.isRegularFile(directory.resolve(".dead-earth-isolated-native-test")),
          "isolated native marker");
      JsonObject request =
          JsonFiles.read(directory.resolve("visual-showcase-request.json"), 1048576);
      require(
          request.get("schema").getAsInt() == 1
              && request.get("kind").getAsString().equals("dead-earth-visual-showcase-request"),
          "visual request kind");
      require(
          directory.equals(Paths.get(request.get("serverDirectory").getAsString()).toRealPath()),
          "exact owned server directory");
      require(
          directory
              .getParent()
              .equals(Paths.get(request.get("repositoryWork").getAsString()).toRealPath()),
          "direct child of this fork work directory");
      require(
          !Files.exists(directory.resolve("client-showcase.json")),
          "fresh showcase marker; preserve previous evidence");
      runId = UUID.fromString(request.get("runId").getAsString()).toString();
      epoch = UUID.fromString(request.get("worldEpoch").getAsString()).toString();
      Properties properties = new Properties();
      try (java.io.Reader reader =
          Files.newBufferedReader(directory.resolve("server.properties"), StandardCharsets.UTF_8)) {
        properties.load(reader);
      }
      require(
          "127.0.0.1".equals(properties.getProperty("server-ip"))
              && "25585".equals(properties.getProperty("server-port"))
              && "false".equals(properties.getProperty("online-mode")),
          "explicit loopback offline transport");
      require(
          ("DEAD EARTH CLIENT SHOWCASE " + runId + " " + epoch)
              .equals(properties.getProperty("motd")),
          "exact UUID transport identity");
      runtime = DeadEarthLegacy.runtime;
      require(runtime != null, "coordinator started");
      runtime.requireThread();
      world = runtime.world.world(0);
      require(epoch.equals(runtime.campaign.worldEpoch), "same saved campaign epoch");
      JsonObject nativeReport =
          JsonFiles.read(directory.resolve(request.get("nativeReport").getAsString()), 8388608);
      require(nativeReport.get("passed").getAsBoolean(), "successful full native fixture");
      JsonObject land = nativeReport.getAsJsonObject("fullGroups").getAsJsonObject("worldgen");
      require(
          land.get("nativePass").getAsBoolean()
              && land.get("actualDeterministicCuts").getAsInt() > 0,
          "genuine generated wasteland evidence");
      baseX = Math.addExact(Math.multiplyExact(land.get("selectedChunkX").getAsInt(), 16), 8);
      baseZ = Math.addExact(Math.multiplyExact(land.get("selectedChunkZ").getAsInt(), 16), 8);
      require(
          Math.abs(baseX) < 8000 && Math.abs(baseZ) < 8000, "bounded local showcase coordinates");
      loadCluster();
      int highest = 1;
      for (int x = 0; x < 28; x++)
        for (int z = 0; z < 14; z++) highest = Math.max(highest, height(baseX + x, baseZ + z));
      baseY = highest + 3;
      require(baseY >= 8 && baseY <= 230, "limited elevated platform above native terrain");
      // Entire platform is preflighted air. No generated terrain, building or basement is replaced.
      for (int x = 0; x < 28; x++)
        for (int z = 0; z < 14; z++)
          for (int y = baseY; y <= baseY + 4; y++)
            require(
                Reflect.blockId(world, Reflect.pos(baseX + x, y, baseZ + z))
                    .equals("minecraft:air"),
                "platform volume is existing air");
      for (int x = 0; x < 28; x++)
        for (int z = 0; z < 14; z++) place(baseX + x, baseY, baseZ + z, "minecraft:stone", 0);
      buildFrames();
      buildCompressed();
      buildMachinery();
      buildBonsai();
      buildVista();
      requiredItems.add("dead_earth:compact_mesh_string_v3");
      requiredItems.add("dead_earth:compact_mesh_iron_v4");
      requiredItems.add("dead_earth:compact_mesh_diamond_v5");
      requiredItems.add("dead_earth:compressed_gravel_2");
      requiredItems.add("dead_earth:compressed_gravel_3");
      requiredItems.add("dead_earth:compressed_gravel_4");
      long originalTicks = runtime.campaign.activeTicks;
      if (!runtime.settings.season(originalTicks).equals("CALM"))
        runtime.campaign.activeTicks =
            Math.floorDiv(originalTicks, runtime.settings.seasonTicks * 3)
                    * runtime.settings.seasonTicks
                    * 3
                + 1;
      Object rules = Reflect.call(world, "getGameRules/func_82736_K");
      for (String rule : new String[] {"doDaylightCycle", "doWeatherCycle", "doMobSpawning"})
        Reflect.call(rules, "setOrCreateGameRule/func_82764_b", rule, "false");
      Reflect.call(rules, "setOrCreateGameRule/func_82764_b", "keepInventory", "true");
      calm();
      runtime.checkpoint();
      JsonFiles.write(
          directory.resolve("client-showcase.json"),
          JsonFiles.map(
              "schema",
              1,
              "kind",
              "dead-earth-client-showcase",
              "purpose",
              "complete-assets-and-worldgen-refactor",
              "runId",
              runId,
              "worldEpoch",
              epoch,
              "views",
              views,
              "requiredBlocks",
              requiredBlocks,
              "requiredItems",
              requiredItems,
              "inventoryItems",
              requiredItems));
      report.put("schema", 1);
      report.put("runId", runId);
      report.put("worldEpoch", epoch);
      report.put("passed", true);
      report.put("scenePrepared", true);
      report.put("originalActiveTicks", originalTicks);
      report.put("platformBlocks", 28 * 14);
      report.put("platformReplacedOnlyAir", true);
      report.put("nativeTerrainWasNotPaintedByFixture", true);
      report.put("views", views);
      report.put("frames", frames);
      writeReport();
      enabled = true;
      MinecraftForge.EVENT_BUS.register(this);
      System.out.println("DEAD EARTH visual showcase prepared " + runId);
    } catch (Throwable failure) {
      fail(failure);
      throw new IllegalStateException("Isolated visual showcase preparation failed", failure);
    }
  }

  private void buildFrames() {
    List<Map<String, Object>> blocks = new ArrayList<Map<String, Object>>();
    int y = baseY + 1, z = baseZ + 6;
    Object nativeTile = place(baseX + 2, y, z, "exnihilocreatio:block_sieve", 0);
    require(nativeTile != null, "native ordinary TileSieve");
    require(
        (Boolean)
            nativeSieve(
                nativeTile,
                "setMesh",
                boolean.class,
                new Class<?>[] {Reflect.type("net.minecraft.item.ItemStack")},
                stack("exnihilocreatio:item_mesh", 1, 4)),
        "real native diamond mesh");
    blocks.add(block(baseX + 2, y, z, "exnihilocreatio:block_sieve", 0));
    requiredBlocks.add("exnihilocreatio:block_sieve");
    int[] grades = {1, 3, 4};
    String[] names = {"string", "iron", "diamond"}, materials = {"gravel", "sand", "dirt"};
    for (int i = 0; i < 3; i++) {
      int level = i + 3, x = baseX + 5 + i * 3;
      String id = "dead_earth:compact_sieve_v" + level;
      Object tile = place(x, y, z, id, 0);
      Object
          capability =
              Reflect.get(
                  Reflect.type("net.minecraftforge.items.CapabilityItemHandler"),
                  "ITEM_HANDLER_CAPABILITY"),
          handler = Reflect.call(tile, "getCapability", capability, null);
      // Physical full output inventory holds the normal automatic batch under real backpressure.
      // No visual fields, receipt cursors, progress counters or native RNG are forced.
      @SuppressWarnings("unchecked")
      List<Object> outputs = (List<Object>) Reflect.get(tile, "slots");
      for (int slot = 2; slot < 29; slot++) outputs.set(slot, stack("minecraft:stone", 64, 0));
      require(
          empty(
              Reflect.call(
                  handler,
                  "insertItem",
                  1,
                  stack("dead_earth:compact_mesh_" + names[i] + "_v" + level, 1, 0),
                  false)),
          "real installed compact mesh");
      require(
          empty(
              Reflect.call(
                  handler,
                  "insertItem",
                  0,
                  stack("dead_earth:compressed_" + materials[i] + "_" + (level - 1), 1, 0),
                  false)),
          "real loaded compressed material");
      Map<String, Object> descriptor = block(x, y, z, id, 0);
      descriptor.put("visualMesh", grades[i]);
      descriptor.put("visualFill", 4);
      descriptor.put("visualMaterial", materialIndex(materials[i]));
      blocks.add(descriptor);
      frames.add(
          JsonFiles.map(
              "x",
              x,
              "y",
              y,
              "z",
              z,
              "id",
              id,
              "mesh",
              grades[i],
              "fill",
              4,
              "material",
              materialIndex(materials[i]),
              "outputBackpressure",
              true,
              "fullOutputSlots",
              27));
      // A mesh-only row makes the open frame/lattice visible beside its filled version.
      Object emptyTile = place(x, y, baseZ + 9, id, 0),
          emptyHandler = Reflect.call(emptyTile, "getCapability", capability, null);
      require(
          empty(
              Reflect.call(
                  emptyHandler,
                  "insertItem",
                  1,
                  stack("dead_earth:compact_mesh_" + names[i] + "_v" + level, 1, 0),
                  false)),
          "mesh-only open frame");
      Map<String, Object> open = block(x, y, baseZ + 9, id, 0);
      open.put("visualMesh", grades[i]);
      open.put("visualFill", 0);
      open.put("visualMaterial", 0);
      blocks.add(open);
      requiredBlocks.add(id);
    }
    views.add(
        view(
            "open-sieves-native-and-loaded-compact",
            baseX + 6.5,
            baseY + 3.0,
            baseZ - 2.0,
            0,
            20,
            blocks));
  }

  private void buildCompressed() {
    List<Map<String, Object>> blocks = new ArrayList<Map<String, Object>>();
    String[] materials = {"gravel", "dirt"};
    int[] nativeMeta = {2, 4};
    for (int row = 0; row < materials.length; row++)
      for (int depth = 1; depth <= 4; depth++) {
        int x = baseX + 17 + (depth - 1) * 3, z = baseZ + 6 + row * 3;
        String id =
            depth == 1
                ? "excompressum:compressed_block"
                : "dead_earth:compressed_" + materials[row] + "_" + depth;
        int meta = depth == 1 ? nativeMeta[row] : 0;
        place(x, baseY + 1, z, id, meta);
        blocks.add(block(x, baseY + 1, z, id, meta));
        if (!requiredBlocks.contains(id)) requiredBlocks.add(id);
      }
    views.add(
        view(
            "compressed-native-l1-and-own-l2-l3-l4",
            baseX + 21.5,
            baseY + 3.0,
            baseZ - 2.0,
            0,
            20,
            blocks));
  }

  private void buildMachinery() {
    List<Map<String, Object>> blocks = new ArrayList<Map<String, Object>>();
    String[] ids = {"dead_earth:zone_relay", "dead_earth:fortune_processor", "dead_earth:charger"};
    for (int i = 0; i < ids.length; i++) {
      int x = baseX + 19 + i * 2, y = baseY + 1, z = baseZ + 2;
      place(x, y, z, ids[i], 0);
      blocks.add(block(x, y, z, ids[i], 0));
      requiredBlocks.add(ids[i]);
      requiredItems.add(ids[i]);
    }
    // CALM/noon and an unobstructed north-facing view show cabinets, mouth/cradle and aerial.
    views.add(
        view(
            "industrial-relay-ore-processor-and-charger",
            baseX + 21.5,
            baseY + 3.0,
            baseZ - 2.5,
            0,
            16,
            blocks));
  }

  private void buildBonsai() {
    List<Map<String, Object>> blocks = new ArrayList<Map<String, Object>>();
    for (int meta = 0; meta < 2; meta++) {
      int x = baseX + 20 + meta * 4, y = baseY + 2, z = baseZ + 9;
      place(x, y - 1, z, meta == 0 ? "minecraft:stone" : "minecraft:chest", 0);
      place(x, y, z, "bonsaitrees:bonsaipot", meta);
      blocks.add(block(x, y, z, "bonsaitrees:bonsaipot", meta));
    }
    requiredBlocks.add("bonsaitrees:bonsaipot");
    requiredItems.add("bonsaitrees:bonsaipot");
    views.add(
        view("bonsai-ordinary-and-hopping", baseX + 22.5, baseY + 3.5, baseZ + 4.0, 0, 12, blocks));
  }

  private void buildVista() {
    List<Map<String, Object>> blocks = new ArrayList<Map<String, Object>>();
    int palette = 0, ash = 0;
    for (int dx : new int[] {36, 44, 52, 60})
      for (int dz : new int[] {28, 36, 44, 52}) {
        int x = baseX + dx, z = baseZ + dz, y = height(x, z) - 1;
        String id = Reflect.blockId(world, Reflect.pos(x, y, z));
        if (id.equals("dead_earth:ash")) ash++;
        if (id.equals("dead_earth:ash")
            || id.equals("minecraft:stone")
            || id.equals("minecraft:cobblestone")
            || id.equals("minecraft:gravel")
            || id.equals("minecraft:stonebrick")) {
          blocks.add(block(x, y, z, id, meta(x, y, z)));
          palette++;
        }
      }
    require(palette >= 8, "actual generated palette vista across independent surface columns");
    report.put("vistaPaletteSamples", palette);
    report.put("vistaAshSamples", ash);
    views.add(
        view(
            "actual-generated-wasteland-vista",
            baseX + 30.5,
            baseY + 8.0,
            baseZ + 18.5,
            -30,
            20,
            blocks));
  }

  @SubscribeEvent(priority = EventPriority.LOWEST)
  public void events(Event event) {
    if (!enabled) return;
    String name = event.getClass().getName();
    try {
      if (name.endsWith("PlayerLoggedInEvent")) {
        Object player = Reflect.get(event, "player");
        if (!fixturePlayer(player)) return;
        actor = player;
        preparePlayer(player);
        readyAt = ticks + 30;
      } else if (name.endsWith("ServerChatEvent")) {
        Object player = Reflect.call(event, "getPlayer");
        if (!fixturePlayer(player)) return;
        String text = String.valueOf(Reflect.call(event, "getMessage")),
            prefix = "DEAD_EARTH_CLIENT_VIEW:" + runId + ":" + epoch + ":";
        if (!text.startsWith(prefix)) return;
        String index = text.substring(prefix.length());
        if (!index.matches("[0-7]")) return;
        int selected = Integer.parseInt(index);
        if (selected >= views.size()) return;
        event.setCanceled(true);
        calm();
        sendClimate(player);
        teleport(player, views.get(selected));
        send(player, "DEAD_EARTH_CLIENT_VIEW_READY:" + runId + ":" + selected);
        report.put("lastAcknowledgedView", selected);
        writeReport();
      } else if (name.endsWith("ServerTickEvent")
          && Reflect.get(event, "phase").toString().equals("END")) {
        ticks++;
        if (ticks % 20 == 0) calm();
        if (actor != null && !readySent && ticks >= readyAt) {
          sendClimate(actor);
          send(actor, "DEAD_EARTH_CLIENT_READY:" + runId + ":" + epoch);
          readySent = true;
          report.put("actualPlayerJoined", true);
          report.put("readyHandshakeSent", true);
          writeReport();
        }
      }
    } catch (Throwable failure) {
      fail(failure);
      enabled = false;
    }
  }

  private boolean fixturePlayer(Object player) {
    if (player == null
        || !USER.equals(String.valueOf(Reflect.call(player, "getName/func_70005_c_")))
        || Reflect.world(player) != world) return false;
    UUID expected =
        UUID.nameUUIDFromBytes(("OfflinePlayer:" + USER).getBytes(StandardCharsets.UTF_8));
    if (!expected.equals(Reflect.call(player, "getUniqueID/func_110124_au"))) return false;
    Object connection = Reflect.get(player, "connection/field_71135_a"),
        network = Reflect.get(connection, "netManager/field_147371_a"),
        address = Reflect.call(network, "getRemoteAddress/func_74430_c");
    return address instanceof InetSocketAddress
        && ((InetSocketAddress) address).getAddress().isLoopbackAddress();
  }

  private void preparePlayer(Object player) {
    runtime.requireThread();
    loadCluster();
    Reflect.call(
        Reflect.get(player, "interactionManager/field_71134_c"),
        "setGameType/func_73076_a",
        Reflect.get(Reflect.type("net.minecraft.world.GameType"), "SURVIVAL"));
    Object caps = Reflect.get(player, "capabilities/field_71075_bZ");
    Reflect.set(caps, "allowFlying/field_75101_c", true);
    Reflect.set(caps, "isFlying/field_75100_b", true);
    Reflect.set(caps, "disableDamage/field_75102_a", true);
    Reflect.call(player, "sendPlayerAbilities/func_71016_p");
    Object hand = Reflect.get(Reflect.type("net.minecraft.util.EnumHand"), "MAIN_HAND"),
        up = Reflect.get(Reflect.type("net.minecraft.util.EnumFacing"), "UP");
    if (!nativeSieveFilled) {
      Object p = Reflect.pos(baseX + 2, baseY + 1, baseZ + 6),
          block = Reflect.registry("BLOCKS", "exnihilocreatio:block_sieve");
      Reflect.call(player, "setHeldItem/func_184611_a", hand, stack("minecraft:gravel", 1, 0));
      require(
          (Boolean)
              Reflect.call(
                  block,
                  "onBlockActivated/func_180639_a",
                  world,
                  p,
                  Reflect.state(world, p),
                  player,
                  hand,
                  up,
                  .5f,
                  .5f,
                  .5f),
          "native ordinary sieve really filled by player interaction");
      Reflect.call(
          player,
          "setHeldItem/func_184611_a",
          hand,
          Reflect.get(Reflect.type("net.minecraft.item.ItemStack"), "EMPTY/field_190927_a"));
      nativeSieveFilled = true;
      report.put("nativeOrdinarySieveActuallyFilled", true);
      report.put("compactFramesAutomaticWithPhysicalOutputBackpressure", true);
      report.put("compactOutputFullSlotsPerFrame", 27);
    }
    if (!nativeBonsaiFilled) {
      Object bonsai = Reflect.registry("BLOCKS", "bonsaitrees:bonsaipot");
      for (int meta = 0; meta < 2; meta++) {
        Object at = Reflect.pos(baseX + 20 + meta * 4, baseY + 2, baseZ + 9);
        for (String id : new String[] {"minecraft:dirt", "minecraft:sapling"}) {
          Reflect.call(player, "setHeldItem/func_184611_a", hand, stack(id, 1, 0));
          require(
              (Boolean)
                  Reflect.call(
                      bonsai,
                      "onBlockActivated/func_180639_a",
                      world,
                      at,
                      Reflect.state(world, at),
                      player,
                      hand,
                      up,
                      .5f,
                      .5f,
                      .5f),
              "native Bonsai visual planting");
        }
      }
      Reflect.call(
          player,
          "setHeldItem/func_184611_a",
          hand,
          Reflect.get(Reflect.type("net.minecraft.item.ItemStack"), "EMPTY/field_190927_a"));
      nativeBonsaiFilled = true;
      report.put("bonsaiActuallyPlanted", true);
    }
    Object inventory = Reflect.get(player, "inventory/field_71071_by");
    for (String id : requiredItems)
      Reflect.call(inventory, "addItemStackToInventory/func_70441_a", stack(id, 1, 0));
    Reflect.call(
        Reflect.get(player, "inventoryContainer/field_71069_bz"),
        "detectAndSendChanges/func_75142_b");
    teleport(player, views.get(0));
  }

  private void calm() {
    require(
        runtime.settings.season(runtime.campaign.activeTicks).equals("CALM"),
        "showcase remains in genuine CALM climate window");
    Reflect.call(world, "setWorldTime/func_72877_b", 6000L);
    Object info = Reflect.call(world, "getWorldInfo/func_72912_H");
    Reflect.call(info, "setRaining/func_76084_b", false);
    Reflect.call(info, "setThundering/func_76069_a", false);
    LegacyClimate.tick(runtime);
  }

  private void sendClimate(Object player) {
    Object config = Reflect.get(LegacyClimate.class, "config"),
        channel = Reflect.get(LegacyClimate.class, "channel");
    try {
      java.lang.reflect.Constructor<?> constructor =
          Reflect.type("dev.deadearth.legacy.climate.ClimatePacket")
              .getDeclaredConstructor(int.class, config.getClass());
      constructor.setAccessible(true);
      Reflect.call(channel, "sendTo", constructor.newInstance(0, config), player);
    } catch (Exception error) {
      throw new IllegalStateException("Isolated climate packet construction", error);
    }
  }

  private void teleport(Object player, Map<String, Object> v) {
    Reflect.call(
        Reflect.get(player, "connection/field_71135_a"),
        "setPlayerLocation/func_147364_a",
        ((Number) v.get("x")).doubleValue(),
        ((Number) v.get("y")).doubleValue(),
        ((Number) v.get("z")).doubleValue(),
        ((Number) v.get("yaw")).floatValue(),
        ((Number) v.get("pitch")).floatValue());
  }

  private void send(Object player, String text) {
    Reflect.call(
        player,
        "sendMessage/func_145747_a",
        Reflect.make("net.minecraft.util.text.TextComponentString", text));
  }

  private void loadCluster() {
    for (int x = (baseX >> 4) - 1; x <= ((baseX + 72) >> 4); x++)
      for (int z = (baseZ >> 4) - 1; z <= ((baseZ + 72) >> 4); z++)
        Reflect.call(world, "getChunkFromChunkCoords/func_72964_e", x, z);
  }

  private int height(int x, int z) {
    Object c =
        Reflect.call(
            Reflect.call(world, "getChunkProvider/func_72863_F"),
            "getLoadedChunk/func_186028_c",
            x >> 4,
            z >> 4);
    require(c != null, "showcase reads a loaded current cluster");
    return ((Number) Reflect.call(c, "getHeightValue/func_76611_b", x & 15, z & 15)).intValue();
  }

  private int meta(int x, int y, int z) {
    Object state = Reflect.state(world, Reflect.pos(x, y, z)),
        block = Reflect.call(state, "getBlock/func_177230_c");
    return ((Number) Reflect.call(block, "getMetaFromState/func_176201_c", state)).intValue();
  }

  private Object place(int x, int y, int z, String id, int meta) {
    Object at = Reflect.pos(x, y, z);
    require(
        Reflect.blockId(world, at).equals("minecraft:air"),
        "showcase places into existing air only");
    Object block = Reflect.registry("BLOCKS", id);
    require(block != null, "registered showcase block " + id);
    Reflect.call(
        world,
        "setBlockState/func_180501_a",
        at,
        Reflect.call(block, "getStateFromMeta/func_176203_a", meta),
        3);
    return Reflect.call(world, "getTileEntity/func_175625_s", at);
  }

  private static Map<String, Object> block(int x, int y, int z, String id, int meta) {
    return JsonFiles.map("x", x, "y", y, "z", z, "id", id, "meta", meta);
  }

  private static Map<String, Object> view(
      String name,
      double x,
      double y,
      double z,
      float yaw,
      float pitch,
      List<Map<String, Object>> blocks) {
    return JsonFiles.map(
        "name", name, "x", x, "y", y, "z", z, "yaw", yaw, "pitch", pitch, "season", "CALM",
        "blocks", blocks);
  }

  private static Object stack(String id, int count, int meta) {
    Object item = Reflect.registry("ITEMS", id);
    require(item != null, "registered showcase item " + id);
    return Reflect.make("net.minecraft.item.ItemStack", item, count, meta);
  }

  private static boolean empty(Object stack) {
    return (Boolean) Reflect.call(stack, "isEmpty/func_190926_b");
  }

  private static int materialIndex(String material) {
    @SuppressWarnings("unchecked")
    Map<String, String> raw =
        (Map<String, String>)
            Reflect.get(Reflect.type("dev.deadearth.legacy.sieving.LegacySieving"), "RAW");
    int i = 0;
    for (String key : raw.keySet()) {
      if (key.equals(material)) return i;
      i++;
    }
    throw new IllegalStateException("Unknown showcase material " + material);
  }

  private static Object nativeSieve(
      Object tile, String name, Class<?> result, Class<?>[] args, Object... values) {
    try {
      List<Object> all = new ArrayList<Object>();
      all.add(tile);
      Collections.addAll(all, values);
      return MethodHandles.publicLookup()
          .findVirtual(
              Reflect.type("exnihilocreatio.tiles.TileSieve"),
              name,
              MethodType.methodType(result, args))
          .invokeWithArguments(all);
    } catch (Throwable failure) {
      throw new IllegalStateException("Exact native sieve API", failure);
    }
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new IllegalStateException(message);
  }

  private void writeReport() {
    if (directory != null)
      JsonFiles.write(directory.resolve("visual-showcase-server.json"), report);
  }

  private void fail(Throwable failure) {
    report.put("passed", false);
    report.put("error", failure.toString());
    failure.printStackTrace();
    writeReport();
  }
}
