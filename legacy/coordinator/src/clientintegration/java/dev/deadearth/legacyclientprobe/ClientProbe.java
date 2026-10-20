package dev.deadearth.legacyclientprobe;

import com.google.gson.*;
import dev.deadearth.legacy.JsonFiles;
import dev.deadearth.legacy.Reflect;
import java.net.InetSocketAddress;
import java.nio.file.*;
import java.util.*;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.eventhandler.*;

/**
 * Final-stage real GL client probe. Test-only plaintext protocol, confined to a UUID-marked
 * loopback fixture.
 */
@Mod(
    modid = "deadearth_client_probe",
    name = "DEAD EARTH Isolated Client Probe",
    version = "1.0.0",
    clientSideOnly = true,
    dependencies = "required-after:dead_earth",
    acceptableRemoteVersions = "*")
public final class ClientProbe {
  private JsonObject control;
  private Path directory, reportPath;
  private String runId, epoch;
  private Object client;
  private int ticks, viewIndex = -1, settle;
  private boolean ready, ack, capture, models, done;
  private long started;
  private int holdSeconds;
  private long holdUntil, holdStarted;
  private boolean holding;
  private final List<Object> screenshots = new ArrayList<Object>(),
      checks = new ArrayList<Object>();
  private final Set<Integer> seasons = new LinkedHashSet<Integer>();
  private final Map<String, Object> report = new LinkedHashMap<String, Object>();

  @Mod.EventHandler
  public void pre(FMLPreInitializationEvent event) {
    if (!Boolean.getBoolean("deadearth.clientProbe"))
      throw new IllegalStateException("Client probe requires explicit final-stage test controls");
    try {
      directory = Paths.get("").toRealPath();
      Path file = Paths.get(System.getProperty("deadearth.clientProbeControl")).toRealPath();
      if (!file.getParent().equals(directory))
        throw new IllegalStateException("Client control is outside its isolated game directory");
      control = JsonFiles.read(file, 1048576);
      if (control.get("schema").getAsInt() != 1
          || !control.get("kind").getAsString().equals("dead-earth-client-probe"))
        throw new IllegalStateException("Client marker is invalid");
      runId = UUID.fromString(control.get("runId").getAsString()).toString();
      epoch = UUID.fromString(control.get("worldEpoch").getAsString()).toString();
      for (JsonElement entry : control.getAsJsonArray("views"))
        if (!entry.getAsJsonObject().get("name").getAsString().matches("[a-z0-9_-]{1,48}"))
          throw new IllegalStateException("Unsafe screenshot name");
      Path server = Paths.get(control.get("serverDirectory").getAsString()).toRealPath();
      if (!Files.isRegularFile(server.resolve(".dead-earth-isolated-native-test")))
        throw new IllegalStateException("Server fixture marker is missing");
      JsonObject marker = JsonFiles.read(server.resolve("client-showcase.json"), 1048576);
      if (!runId.equals(marker.get("runId").getAsString())
          || !epoch.equals(marker.get("worldEpoch").getAsString()))
        throw new IllegalStateException("Server/client fixture identity changed");
      reportPath = directory.resolve("client-report.json");
      started = System.nanoTime();
      holdSeconds = Integer.parseInt(System.getProperty("deadearth.clientProbeHoldSeconds", "0"));
      if (holdSeconds < 0 || holdSeconds > 300)
        throw new IllegalStateException("Client hold must be bounded to 0..300 seconds");
      report.put("schema", 1);
      report.put("runId", runId);
      report.put("worldEpoch", epoch);
      report.put("probe", "actual_forge_1_12_2_client");
      report.put("screenshots", screenshots);
      report.put("checks", checks);
      MinecraftForge.EVENT_BUS.register(this);
    } catch (Exception error) {
      throw new IllegalStateException("Unsafe isolated client probe environment", error);
    }
  }

  @SubscribeEvent
  public void events(Event event) {
    if (done) return;
    try {
      String name = event.getClass().getName();
      if (name.endsWith("ClientChatReceivedEvent")) {
        String text =
            (String)
                Reflect.call(Reflect.call(event, "getMessage"), "getUnformattedText/func_150260_c");
        if (text.equals("DEAD_EARTH_CLIENT_READY:" + runId + ":" + epoch)) {
          ready = true;
          event.setCanceled(true);
        }
        if (text.equals("DEAD_EARTH_CLIENT_VIEW_READY:" + runId + ":" + viewIndex)) {
          ack = true;
          settle = 0;
          event.setCanceled(true);
        }
      } else if (name.endsWith("ClientTickEvent")
          && Reflect.get(event, "phase").toString().equals("END")) tick();
      else if (name.endsWith("RenderTickEvent")
          && Reflect.get(event, "phase").toString().equals("END")
          && capture) capture();
    } catch (Throwable error) {
      error.printStackTrace();
      finish(false, error.getClass().getSimpleName() + ": " + String.valueOf(error.getMessage()));
    }
  }

  private void tick() throws Exception {
    client =
        Reflect.call(Reflect.type("net.minecraft.client.Minecraft"), "getMinecraft/func_71410_x");
    ticks++;
    if (Files.exists(directory.resolve("client-quit.request"))) {
      finish(false, "Supervisor requested graceful shutdown");
      return;
    }
    if ((System.nanoTime() - started) / 1000000000L
        > control.get("timeoutSeconds").getAsInt() + holdSeconds) {
      finish(false, "Native client deadline exceeded");
      return;
    }
    Object world = Reflect.get(client, "world/field_71441_e"),
        player = Reflect.get(client, "player/field_71439_g");
    if (world == null || player == null) return;
    endpoint();
    if (!ready) return;
    if (holding) {
      if (Files.exists(directory.resolve("client-release.request"))
          || System.nanoTime() >= holdUntil) finish(true, null);
      return;
    }
    if (!models) {
      validateModels();
      models = true;
    }
    Object snapshot =
        Reflect.get(Reflect.type("dev.deadearth.legacy.climate.ClimateClient"), "snapshot");
    int actual = -1;
    if (snapshot != null) {
      actual = ((Number) Reflect.get(snapshot, "season")).intValue();
      seasons.add(actual);
    }
    if (viewIndex < 0) {
      next(player);
      return;
    }
    if (!ack || capture) return;
    JsonObject view = control.getAsJsonArray("views").get(viewIndex).getAsJsonObject();
    int expected =
        view.get("season").getAsString().equals("ASH_WINTER")
            ? 2
            : view.get("season").getAsString().equals("HEAT") ? 1 : 0;
    if (actual != expected) {
      settle = 0;
      return;
    }
    Object position = Reflect.position(player);
    double tx = view.get("x").getAsDouble(),
        ty = view.get("y").getAsDouble(),
        tz = view.get("z").getAsDouble();
    double x = ((Number) Reflect.get(player, "posX/field_70165_t")).doubleValue(),
        y = ((Number) Reflect.get(player, "posY/field_70163_u")).doubleValue(),
        z = ((Number) Reflect.get(player, "posZ/field_70161_v")).doubleValue();
    double dx = tx - x,
        dy = ty - y,
        dz = tz - z,
        distance = Math.sqrt(dx * dx + dy * dy + dz * dz),
        step = distance > 1 ? 1 / distance : 1;
    Object target = Reflect.pos((int) Math.floor(tx), (int) Math.floor(ty), (int) Math.floor(tz));
    if (!(Boolean) Reflect.call(world, "isBlockLoaded/func_175667_e", target)) {
      settle = 0;
      return;
    }
    float yaw = ((Number) Reflect.get(player, "rotationYaw/field_70177_z")).floatValue(),
        pitch = ((Number) Reflect.get(player, "rotationPitch/field_70125_A")).floatValue();
    float turn = (view.get("yaw").getAsFloat() - yaw) % 360;
    if (turn > 180) turn -= 360;
    if (turn < -180) turn += 360;
    float tilt = view.get("pitch").getAsFloat() - pitch;
    Reflect.call(
        player,
        "setPositionAndRotation/func_70080_a",
        x + dx * step,
        y + dy * step,
        z + dz * step,
        yaw + Math.max(-4, Math.min(4, turn)),
        pitch + Math.max(-3, Math.min(3, tilt)));
    if (distance > .15 || Math.abs(turn) > .25 || Math.abs(tilt) > .25) {
      settle = 0;
      return;
    }
    if (++settle >= 40) {
      visibleBlocks(world, view);
      capture = true;
    }
  }

  private void endpoint() {
    Object handler = Reflect.call(client, "getConnection/func_147114_u"),
        network = Reflect.call(handler, "getNetworkManager/func_147298_b");
    Object remote = Reflect.call(network, "getRemoteAddress/func_74430_c");
    if (!(remote instanceof InetSocketAddress))
      throw new IllegalStateException("Unexpected local/integrated server transport");
    InetSocketAddress address = (InetSocketAddress) remote;
    if (address.getAddress() == null
        || !address.getAddress().getHostAddress().equals("127.0.0.1")
        || address.getPort() != 25585)
      throw new IllegalStateException("Client joined a server outside the marked loopback fixture");
  }

  private void next(Object player) {
    viewIndex++;
    ack = false;
    settle = 0;
    if (viewIndex >= control.getAsJsonArray("views").size()) {
      report.put("viewsComplete", true);
      report.put("holding", holdSeconds > 0);
      report.put("holdSeconds", holdSeconds);
      report.put("seasonsObserved", seasons);
      JsonFiles.write(reportPath, report);
      JsonFiles.write(
          directory.resolve("client-views-complete.json"),
          JsonFiles.map(
              "runId",
              runId,
              "worldEpoch",
              epoch,
              "viewsComplete",
              true,
              "holding",
              holdSeconds > 0,
              "holdSeconds",
              holdSeconds,
              "screenshots",
              screenshots));
      if (holdSeconds == 0) finish(true, null);
      else {
        holding = true;
        holdStarted = System.nanoTime();
        holdUntil = holdStarted + holdSeconds * 1000000000L;
      }
      return;
    }
    endpoint();
    Reflect.call(
        player,
        "sendChatMessage/func_71165_d",
        "DEAD_EARTH_CLIENT_VIEW:" + runId + ":" + epoch + ":" + viewIndex);
  }

  private void validateModels() {
    Object dispatcher = Reflect.call(client, "getBlockRendererDispatcher/func_175602_ab");
    Object renderer = Reflect.call(client, "getRenderItem/func_175599_af"),
        mesher = Reflect.call(renderer, "getItemModelMesher/func_175037_a");
    Object manager = Reflect.call(mesher, "getModelManager/func_178083_a"),
        missing = Reflect.call(manager, "getMissingModel/func_174951_a");
    for (JsonElement entry : control.getAsJsonArray("requiredBlocks")) {
      String id = entry.getAsString();
      Object block = Reflect.registry("BLOCKS", id);
      if (block == null) throw new IllegalStateException("Missing registered block " + id);
      Object model =
          Reflect.call(
              dispatcher,
              "getModelForState/func_184389_a",
              Reflect.call(block, "getDefaultState/func_176223_P"));
      model(model, missing, id);
    }
    for (JsonElement entry : control.getAsJsonArray("requiredItems")) {
      String id = entry.getAsString();
      Object item = Reflect.registry("ITEMS", id);
      if (item == null) throw new IllegalStateException("Missing registered item " + id);
      Object stack = Reflect.make("net.minecraft.item.ItemStack", item, 1, 0);
      model(Reflect.call(mesher, "getItemModel/func_178089_a", stack), missing, id);
    }
    try {
      Map<String, Object> own = OwnAssetsProbe.audit(client);
      report.put("ownAssets", own);
      if (!Boolean.TRUE.equals(own.get("passed")))
        throw new IllegalStateException(String.valueOf(own.get("errors")));
    } catch (Exception failure) {
      throw new IllegalStateException(
          "Complete own asset bake audit failed: " + failure.toString(), failure);
    }
    validateNames();
    Object colors = Reflect.get(client, "itemColors/field_184128_aI");
    List<Integer> shades = new ArrayList<Integer>();
    int previous = 256;
    for (int power = 2; power <= 4; power++) {
      Object stack =
          Reflect.make(
              "net.minecraft.item.ItemStack",
              Reflect.registry("ITEMS", "dead_earth:compressed_gravel_" + power),
              1,
              0);
      int rgb =
          ((Number) Reflect.call(colors, "colorMultiplier/func_186728_a", stack, 0)).intValue();
      int brightness = rgb & 255;
      if (rgb == -1 || brightness >= previous)
        throw new IllegalStateException(
            "Compressed gravel tint is missing or does not darken: " + power + " = " + rgb);
      shades.add(rgb);
      previous = brightness;
    }
    report.put("compressedGravelTints", shades);
    List<Object> mods = new ArrayList<Object>();
    for (Object mod :
        (List<?>)
            Reflect.call(
                Reflect.call(Reflect.type("net.minecraftforge.fml.common.Loader"), "instance"),
                "getModList"))
      mods.add(
          JsonFiles.map(
              "id", Reflect.call(mod, "getModId"), "version", Reflect.call(mod, "getVersion")));
    report.put("mods", mods);
    Set<String> ids = new HashSet<String>();
    for (Object row : mods) ids.add((String) ((Map<?, ?>) row).get("id"));
    for (JsonElement id : control.getAsJsonArray("requiredMods"))
      if (!ids.contains(id.getAsString()))
        throw new IllegalStateException("Full client mod is not loaded: " + id.getAsString());
    if (control.has("inventoryItems")) {
      Object player = Reflect.get(client, "player/field_71439_g"),
          inventory = Reflect.get(player, "inventory/field_71071_by");
      Set<String> held = new HashSet<String>();
      for (Object stack : (List<?>) Reflect.get(inventory, "mainInventory/field_70462_a"))
        if (!(Boolean) Reflect.call(stack, "isEmpty/func_190926_b"))
          held.add(
              Reflect.call(Reflect.call(stack, "getItem/func_77973_b"), "getRegistryName")
                  .toString());
      for (JsonElement id : control.getAsJsonArray("inventoryItems"))
        if (!held.contains(id.getAsString()))
          throw new IllegalStateException(
              "Showcase inventory does not contain " + id.getAsString());
      checks.add(JsonFiles.map("showcaseInventory", held, "ok", true));
    }
    Object gl = Reflect.type("org.lwjgl.opengl.GL11");
    report.put(
        "openGL",
        JsonFiles.map(
            "vendor",
            Reflect.call(gl, "glGetString", 7936),
            "renderer",
            Reflect.call(gl, "glGetString", 7937),
            "version",
            Reflect.call(gl, "glGetString", 7938)));
  }

  private void validateNames() {
    List<Object> names = new ArrayList<Object>();
    Object registry =
        Reflect.get(Reflect.type("net.minecraft.item.Item"), "REGISTRY/field_150901_e");
    for (Object key : (Set<?>) Reflect.call(registry, "getKeys/func_148742_b")) {
      String id = key.toString();
      if (!id.startsWith("dead_earth:")) continue;
      Object stack =
          Reflect.make("net.minecraft.item.ItemStack", Reflect.registry("ITEMS", id), 1, 0);
      String label = String.valueOf(Reflect.call(stack, "getDisplayName/func_82833_r"));
      if (label.trim().isEmpty()
          || label.endsWith(".name")
          || label.startsWith("tile.")
          || label.startsWith("item.")
          || label.contains("dead_earth."))
        throw new IllegalStateException("Untranslated own item " + id + ": " + label);
      boolean russian = false;
      for (int i = 0; i < label.length(); i++)
        if (label.charAt(i) >= 'А' && label.charAt(i) <= 'я'
            || label.charAt(i) == 'ё'
            || label.charAt(i) == 'Ё') russian = true;
      if (!russian)
        throw new IllegalStateException(
            "Own item has no readable Russian name " + id + ": " + label);
      names.add(JsonFiles.map("id", id, "name", label));
    }
    if (names.isEmpty())
      throw new IllegalStateException("No registered own items for localization proof");
    report.put("localizedItems", names);
    checks.add(
        JsonFiles.map("localizedOwnItemCount", names.size(), "language", "ru_ru", "ok", true));
  }

  private void model(Object model, Object missing, String id) {
    if (model == missing) throw new IllegalStateException("Missing baked model " + id);
    String texture =
        (String)
            Reflect.call(
                Reflect.call(model, "getParticleTexture/func_177554_e"),
                "getIconName/func_94215_i");
    if (texture.contains("missingno")) throw new IllegalStateException("Missing texture " + id);
    checks.add(JsonFiles.map("model", id, "texture", texture, "ok", true));
  }

  private void visibleBlocks(Object world, JsonObject view) {
    if (!view.has("blocks")) return;
    for (JsonElement entry : view.getAsJsonArray("blocks")) {
      JsonObject b = entry.getAsJsonObject();
      Object at = Reflect.pos(b.get("x").getAsInt(), b.get("y").getAsInt(), b.get("z").getAsInt());
      if (!(Boolean) Reflect.call(world, "isBlockLoaded/func_175667_e", at))
        throw new IllegalStateException("Showcase assertion chunk is not loaded");
      String actual = Reflect.blockId(world, at);
      if (!actual.equals(b.get("id").getAsString()))
        throw new IllegalStateException("Showcase block differs: " + actual);
      Object state = Reflect.state(world, at),
          block = Reflect.call(state, "getBlock/func_177230_c");
      if (b.has("meta")
          && ((Number) Reflect.call(block, "getMetaFromState/func_176201_c", state)).intValue()
              != b.get("meta").getAsInt())
        throw new IllegalStateException("Showcase metadata differs: " + actual);
      if (b.has("visualMesh") || b.has("visualFill") || b.has("visualMaterial")) {
        Object actualState = Reflect.call(block, "getActualState/func_176221_a", state, world, at);
        for (String property : new String[] {"mesh", "fill", "material"}) {
          String field =
              "visual" + Character.toUpperCase(property.charAt(0)) + property.substring(1);
          if (!b.has(field)) continue;
          boolean found = false;
          for (Object key :
              (Collection<?>) Reflect.call(actualState, "getPropertyKeys/func_177227_a"))
            if (property.equals(Reflect.call(key, "getName/func_177701_a"))) {
              int value =
                  ((Number) Reflect.call(actualState, "getValue/func_177229_b", key)).intValue();
              if (value != b.get(field).getAsInt())
                throw new IllegalStateException(
                    "Unsynchronized sieve " + property + ": " + value + " != " + b.get(field));
              found = true;
            }
          if (!found) throw new IllegalStateException("Missing visible sieve property " + property);
        }
      }
      checks.add(
          JsonFiles.map("scene", view.get("name").getAsString(), "block", actual, "ok", true));
    }
  }

  private void capture() throws Exception {
    endpoint();
    capture = false;
    JsonObject view = control.getAsJsonArray("views").get(viewIndex).getAsJsonObject();
    String name =
        String.format(Locale.ROOT, "%02d-%s.png", viewIndex, view.get("name").getAsString());
    int width = ((Number) Reflect.get(client, "displayWidth/field_71443_c")).intValue(),
        height = ((Number) Reflect.get(client, "displayHeight/field_71440_d")).intValue();
    Object framebuffer = Reflect.call(client, "getFramebuffer/func_147110_a");
    Reflect.call(
        Reflect.type("net.minecraft.util.ScreenShotHelper"),
        "saveScreenshot/func_148259_a",
        directory.toFile(),
        name,
        width,
        height,
        framebuffer);
    Path saved = directory.resolve("screenshots").resolve(name);
    if (!Files.isRegularFile(saved) || Files.size(saved) < 1024)
      throw new IllegalStateException("Minecraft did not save the actual framebuffer screenshot");
    screenshots.add(
        JsonFiles.map(
            "view",
            view.get("name").getAsString(),
            "file",
            "screenshots/" + name,
            "width",
            width,
            "height",
            height,
            "season",
            view.get("season").getAsString(),
            "renderedAfterStableTicks",
            settle));
    JsonFiles.write(reportPath, report);
    next(Reflect.get(client, "player/field_71439_g"));
  }

  private void finish(boolean passed, String error) {
    if (done) return;
    done = true;
    report.put("passed", passed);
    report.put("error", error);
    report.put("readyHandshake", ready);
    report.put("seasonsObserved", seasons);
    report.put("clientTicks", ticks);
    report.put("holding", false);
    report.put(
        "heldSeconds", holdStarted == 0 ? 0 : (System.nanoTime() - holdStarted) / 1000000000L);
    JsonFiles.write(reportPath, report);
    if (client != null) Reflect.call(client, "shutdown/func_71400_g");
  }
}
