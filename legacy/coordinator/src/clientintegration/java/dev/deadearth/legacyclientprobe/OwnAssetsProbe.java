package dev.deadearth.legacyclientprobe;

import com.google.gson.*;
import dev.deadearth.legacy.JsonFiles;
import dev.deadearth.legacy.Reflect;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Test-only native GL audit of every registered own state, item, quad sprite and name. */
public final class OwnAssetsProbe {
  private OwnAssetsProbe() {}

  private static final List<String> diagnostics = new ArrayList<String>();

  private static void note(boolean ok, String message) {
    if (!ok && diagnostics.size() < 256 && !diagnostics.contains(message)) diagnostics.add(message);
  }

  public static Map<String, Object> audit(Object client) throws Exception {
    diagnostics.clear();
    JsonObject contract;
    try (InputStream input =
        OwnAssetsProbe.class
            .getClassLoader()
            .getResourceAsStream("assets/dead_earth/asset_manifest.json")) {
      if (input == null) throw new IllegalStateException("Packaged own asset manifest is missing");
      try (Reader reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
        contract = new JsonParser().parse(reader).getAsJsonObject();
      }
    }
    require(contract.get("schema").getAsInt() == 1, "Own asset manifest schema");
    Set<String> blocks = strings(contract.getAsJsonArray("blocks"));
    Set<String> items = strings(contract.getAsJsonArray("items"));
    Set<String> environment = strings(contract.getAsJsonArray("environmentalBlocks"));
    Set<String> specialized = strings(contract.getAsJsonArray("specializedBlockModels"));
    require(
        blocks.equals(registered("net.minecraft.block.Block", "REGISTRY/field_149771_c")),
        "Manifest differs from actual registered own blocks");
    require(
        items.equals(registered("net.minecraft.item.Item", "REGISTRY/field_150901_e")),
        "Manifest differs from actual registered own items");
    Set<String> missingItems = new TreeSet<String>(blocks);
    missingItems.removeAll(items);
    require(missingItems.equals(environment), "Only environmental blocks may lack inventory items");
    Object renderer = Reflect.call(client, "getRenderItem/func_175599_af");
    Object mesher = Reflect.call(renderer, "getItemModelMesher/func_175037_a");
    Object manager = Reflect.call(mesher, "getModelManager/func_178083_a");
    Object missing = Reflect.call(manager, "getMissingModel/func_174951_a");
    Object dispatcher = Reflect.call(client, "getBlockRendererDispatcher/func_175602_ab");
    List<Object> blockRows = new ArrayList<Object>(), itemRows = new ArrayList<Object>();
    Set<String> sprites = new TreeSet<String>();
    Set<String> lazyBaseItems = new TreeSet<String>();
    int stateCount = 0, itemCases = 0, quadCount = 0;
    for (String id : blocks) {
      Object block = Reflect.registry("BLOCKS", id);
      Object container = Reflect.call(block, "getBlockState/func_176194_O");
      Collection<?> states =
          (Collection<?>) Reflect.call(container, "getValidStates/func_177619_a");
      require(!states.isEmpty(), "No valid block states: " + id);
      String label = String.valueOf(Reflect.call(block, "getLocalizedName/func_149732_F"));
      readable(label, id);
      int quads = 0;
      for (Object state : states) {
        note(
            "MODEL"
                .equals(String.valueOf(Reflect.call(block, "getRenderType/func_149645_b", state))),
            "Own block state is not rendered through its model: " + id);
        Object model = Reflect.call(dispatcher, "getModelForState/func_184389_a", state);
        quads += model(model, missing, state, id + " " + state, sprites);
        stateCount++;
      }
      if (specialized.contains(id))
        note(
            quads > states.size() * 12,
            "Specialized machine has reverted to simple cube geometry: " + id);
      quadCount += quads;
      blockRows.add(
          JsonFiles.map(
              "id",
              id,
              "name",
              label,
              "validStates",
              states.size(),
              "quadsChecked",
              quads,
              "inventoryItem",
              items.contains(id)));
    }
    Object world = Reflect.get(client, "world/field_71441_e"),
        player = Reflect.get(client, "player/field_71439_g");
    for (String id : items) {
      Object item = Reflect.registry("ITEMS", id);
      @SuppressWarnings("unchecked")
      List<Object> subitems =
          (List<Object>)
              Reflect.call(Reflect.type("net.minecraft.util.NonNullList"), "create/func_191196_a");
      Object search =
          Reflect.get(
              Reflect.type("net.minecraft.creativetab.CreativeTabs"), "SEARCH/field_78027_g");
      Reflect.call(item, "getSubItems/func_150895_a", search, subitems);
      List<Object> cases = new ArrayList<Object>();
      cases.add(Reflect.make("net.minecraft.item.ItemStack", item, 1, 0));
      cases.addAll(subitems);
      Object baseline = cases.get(0);
      int maximum = ((Number) Reflect.call(baseline, "getMaxDamage/func_77958_k")).intValue();
      if (maximum > 1) {
        cases.add(Reflect.make("net.minecraft.item.ItemStack", item, 1, maximum / 2));
        cases.add(Reflect.make("net.minecraft.item.ItemStack", item, 1, maximum - 1));
      }
      Set<String> seen = new HashSet<String>();
      int count = 0, quads = 0;
      for (Object stack : cases) {
        if ((Boolean) Reflect.call(stack, "isEmpty/func_190926_b")) continue;
        String signature =
            String.valueOf(Reflect.call(stack, "getMetadata/func_77960_j"))
                + ":"
                + String.valueOf(Reflect.call(stack, "getTagCompound/func_77978_p"));
        if (!seen.add(signature)) continue;
        String label = String.valueOf(Reflect.call(stack, "getDisplayName/func_82833_r"));
        readable(label, id);
        Object base = Reflect.call(mesher, "getItemModel/func_178089_a", stack);
        int baseQuads = model(base, missing, null, id + " base " + signature, sprites, false);
        quads += baseQuads;
        if (baseQuads == 0) lazyBaseItems.add(id);
        Object resolved =
            Reflect.call(renderer, "getItemModelWithOverrides/func_184393_a", stack, world, player);
        quads += model(resolved, missing, null, id + " overrides " + signature, sprites);
        count++;
      }
      require(count > 0, "No inventory stack cases: " + id);
      quadCount += quads;
      itemCases += count;
      itemRows.add(
          JsonFiles.map(
              "id",
              id,
              "stackCases",
              count,
              "quadsChecked",
              quads,
              "nativeDynamic",
              contract.getAsJsonObject("nativeDynamicItems").has(id)));
    }
    return JsonFiles.map(
        "passed",
        diagnostics.isEmpty(),
        "errors",
        new ArrayList<String>(diagnostics),
        "scope",
        "all_actual_registered_own_blockstates_and_inventory_stacks",
        "registeredBlocks",
        blocks.size(),
        "registeredItems",
        items.size(),
        "environmentalBlocksWithoutItems",
        environment.size(),
        "blockStatesChecked",
        stateCount,
        "specializedBlockModels",
        specialized,
        "inventoryStackCases",
        itemCases,
        "quadSpritesChecked",
        quadCount,
        "allBlockStates",
        true,
        "defaultAndCreativeSubitems",
        true,
        "damagedToolVariants",
        true,
        "baseAndOverrideItemModels",
        true,
        "lazyBaseItemsWithRenderableResolvedModel",
        lazyBaseItems,
        "quadSprites",
        sprites,
        "blocks",
        blockRows,
        "items",
        itemRows);
  }

  private static Set<String> registered(String type, String field) {
    Object registry = Reflect.get(Reflect.type(type), field);
    Set<String> ids = new TreeSet<String>();
    for (Object key : (Set<?>) Reflect.call(registry, "getKeys/func_148742_b")) {
      String id = key.toString();
      if (id.startsWith("dead_earth:")) ids.add(id);
    }
    return ids;
  }

  private static Set<String> strings(JsonArray rows) {
    Set<String> result = new TreeSet<String>();
    for (JsonElement row : rows)
      require(result.add(row.getAsString()), "Duplicate manifest ID " + row);
    return result;
  }

  private static int model(
      Object model, Object missing, Object state, String id, Set<String> sprites) {
    return model(model, missing, state, id, sprites, true);
  }

  private static int model(
      Object model,
      Object missing,
      Object state,
      String id,
      Set<String> sprites,
      boolean requireGeometry) {
    if (model == null || model == missing) {
      note(false, "Missing baked model " + id);
      return 0;
    }
    sprite(Reflect.call(model, "getParticleTexture/func_177554_e"), id + " particle", sprites);
    int count = 0;
    List<Object> sides = new ArrayList<Object>();
    sides.add(null);
    sides.addAll(Arrays.asList(Reflect.type("net.minecraft.util.EnumFacing").getEnumConstants()));
    for (long seed : new long[] {0L, 1L})
      for (Object side : sides) {
        Collection<?> quads =
            (Collection<?>) Reflect.call(model, "getQuads/func_188616_a", state, side, seed);
        for (Object quad : quads) {
          sprite(Reflect.call(quad, "getSprite/func_187508_a"), id + " quad", sprites);
          count++;
        }
      }
    note(!requireGeometry || count > 0, "Baked own model has no visible geometry " + id);
    return count;
  }

  private static void sprite(Object sprite, String id, Set<String> sprites) {
    if (sprite == null) {
      note(false, "No texture sprite " + id);
      return;
    }
    String key = String.valueOf(Reflect.call(sprite, "getIconName/func_94215_i"));
    note(
        !key.contains("missingno") && !key.contains("missing_texture"),
        "Missing quad texture " + id + ": " + key);
    sprites.add(key);
  }

  private static void readable(String label, String id) {
    note(
        label != null
            && !label.trim().isEmpty()
            && !label.endsWith(".name")
            && !label.startsWith("tile.")
            && !label.startsWith("item.")
            && !label.contains("dead_earth."),
        "Untranslated own registry entry " + id + ": " + label);
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new IllegalStateException(message);
  }
}
