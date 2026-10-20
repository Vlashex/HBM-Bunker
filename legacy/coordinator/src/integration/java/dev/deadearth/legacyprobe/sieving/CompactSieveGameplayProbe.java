package dev.deadearth.legacyprobe.sieving;

import dev.deadearth.legacy.*;
import dev.deadearth.legacy.sieving.*;
import java.util.*;
import net.minecraftforge.items.CapabilityItemHandler;

/**
 * Actual PlayerInteractionManager activations and ordinary END ticks; no manual lifecycle/progress.
 */
public final class CompactSieveGameplayProbe {
  private CompactSieveGameplayProbe() {}

  private static void require(boolean ok, String detail) {
    if (!ok) throw new AssertionError(detail);
  }

  private static Object stack(String id, int n) {
    return SievingProbe.stack(id, n);
  }

  private static int count(Object s) {
    return SievingProbe.count(s);
  }

  private static long number(Object tile, String key) {
    return SievingProbe.num(tile, key);
  }

  private static Object handler(Object tile) {
    return Reflect.call(tile, "getCapability", CapabilityItemHandler.ITEM_HANDLER_CAPABILITY, null);
  }

  public static Session begin(LegacyRuntime runtime, Object world, int x, int y, int z) {
    return new Session(runtime, world, x, y, z);
  }

  public static final class Session {
    private final LegacyRuntime runtime;
    private final Object world, main, off, up;
    private final Map<UUID, Object> playerMap;
    private final List<Object> positions = new ArrayList<Object>(),
        tiles = new ArrayList<Object>(),
        actors = new ArrayList<Object>(),
        interactions = new ArrayList<Object>(),
        networks = new ArrayList<Object>();
    private final List<Map<String, Object>> cases = new ArrayList<Map<String, Object>>();
    private final List<Map<String, Long>> extracted = new ArrayList<Map<String, Long>>();
    private final long[] seeds = new long[7];
    private final int[] strokes = new int[7];
    private final Map<String, Object> report = new LinkedHashMap<String, Object>();
    private Object hopperInput, hopperOutput;
    private long globalPrevious, worldPrevious, observedTicks, maxDrawsPerTick, hopperOutputItems;
    private int completeGrace;
    private boolean done;

    @SuppressWarnings("unchecked")
    private Session(LegacyRuntime runtime, Object world, int x, int y, int z) {
      this.runtime = runtime;
      this.world = world;
      runtime.requireThread();
      require(
          Boolean.getBoolean("deadearth.testControls")
              && java.nio.file.Files.isRegularFile(
                  java.nio.file.Paths.get(".dead-earth-isolated-native-test")),
          "isolated native test only");
      require(!(Boolean) Reflect.get(world, "isRemote/field_72995_K"), "logical server fixture");
      main = Reflect.get(Reflect.type("net.minecraft.util.EnumHand"), "MAIN_HAND");
      off = Reflect.get(Reflect.type("net.minecraft.util.EnumHand"), "OFF_HAND");
      up = Reflect.get(Reflect.type("net.minecraft.util.EnumFacing"), "UP");
      Object list = Reflect.call(runtime.server, "getPlayerList/func_184103_al");
      playerMap = (Map<UUID, Object>) Reflect.get(list, "uuidToPlayerMap/field_177454_f");
      for (int i = 0; i < 7; i++) {
        Object p = Reflect.pos(x + i * 3, y, z);
        require(
            Reflect.loaded(world, p)
                && Reflect.blockId(world, p).equals("minecraft:air")
                && Reflect.call(world, "getTileEntity/func_175625_s", p) == null,
            "fixture uses loaded empty air");
        positions.add(p);
      }
      try {
        for (int i = 0; i < 7; i++) prepare(i);
        prepareHoppers();
        globalPrevious = LegacySieving.nativeDrawCount();
        worldPrevious = time();
        report.put("cases", cases);
        report.put("normalPlacementOnly", true);
        report.put("manualLifecycleOrProgressCalls", false);
      } catch (Throwable failure) {
        cleanup();
        throw failure;
      }
    }

    private long time() {
      return ((Number) Reflect.call(world, "getTotalWorldTime/func_82737_E")).longValue();
    }

    private int tier(int i) {
      return i == 6 ? 3 : 3 + i / 2;
    }

    private int target(int i) {
      int tier = tier(i);
      return tier == 3 ? 81 : tier == 4 ? 729 : 6561;
    }

    private int fill(int i) {
      Object block = Reflect.registry("BLOCKS", "dead_earth:compact_sieve_v" + tier(i));
      Object state =
          Reflect.call(
              block,
              "getActualState/func_176221_a",
              Reflect.state(world, positions.get(i)),
              world,
              positions.get(i));
      return ((Number)
              Reflect.call(state, "getValue/func_177229_b", Reflect.get(block.getClass(), "FILL")))
          .intValue();
    }

    private void prepare(int i) {
      int tier = tier(i);
      boolean creative = i % 2 == 1 && i != 6;
      Object p = positions.get(i),
          block = Reflect.registry("BLOCKS", "dead_earth:compact_sieve_v" + tier);
      require(
          (Boolean)
              Reflect.call(
                  world,
                  "setBlockState/func_180501_a",
                  p,
                  Reflect.call(block, "getDefaultState/func_176223_P"),
                  3),
          "frame placed by native World");
      Object tile = Reflect.call(world, "getTileEntity/func_175625_s", p);
      require(
          tile instanceof SieveTile
              && ((Set<?>) Reflect.get(LegacySieving.class, "TILES")).contains(tile),
          "native onLoad naturally tracks frame");
      tiles.add(tile);
      extracted.add(new TreeMap<String, Long>());
      Object interaction =
          Reflect.make("net.minecraft.server.management.PlayerInteractionManager", world);
      interactions.add(interaction);
      UUID uuid = new UUID(0x731a520e44554c00L, i + 1);
      require(!playerMap.containsKey(uuid), "isolated actor UUID unused");
      Object actor =
          Reflect.make(
              "net.minecraft.entity.player.EntityPlayerMP",
              runtime.server,
              world,
              Reflect.make("com.mojang.authlib.GameProfile", uuid, "CompactProbe" + i),
              interaction);
      actors.add(actor);
      playerMap.put(uuid, actor);
      require(
          Reflect.call(
                  Reflect.call(runtime.server, "getPlayerList/func_184103_al"),
                  "getPlayerByUUID/func_177451_a",
                  uuid)
              == actor,
          "native UUID lookup finds deferred payout recipient");
      Object network =
          Reflect.make(
              "net.minecraft.network.NetworkManager",
              Reflect.get(
                  Reflect.type("net.minecraft.network.EnumPacketDirection"), "SERVERBOUND"));
      networks.add(network);
      Reflect.set(
          actor,
          "connection/field_71135_a",
          Reflect.make(
              "net.minecraft.network.NetHandlerPlayServer", runtime.server, network, actor));
      Reflect.call(
          interaction,
          "setGameType/func_73076_a",
          Reflect.get(
              Reflect.type("net.minecraft.world.GameType"), creative ? "CREATIVE" : "SURVIVAL"));
      Object h = handler(tile);
      require(((Number) Reflect.call(h, "getSlotLimit", 0)).intValue() == 1, "input capacity one");
      for (boolean simulate : new boolean[] {true, false})
        require(
            count(
                    Reflect.call(
                        h,
                        "insertItem",
                        0,
                        stack("dead_earth:compressed_gravel_" + (tier - 1), 64),
                        simulate))
                == 64,
            "mesh prerequisite also applies to simulated hopper insertion");
      click(i, main, stack("dead_earth:compressed_gravel_" + (tier - 1), 64));
      require(
          count(Reflect.call(h, "getStackInSlot", 0)) == 0
              && count(Reflect.call(actor, "getHeldItem/func_184586_b", main)) == 64,
          "player cannot load compressed64 before mesh");
      click(
          i,
          main,
          Reflect.make(
              "net.minecraft.item.ItemStack",
              Reflect.registry("ITEMS", "exnihilocreatio:item_mesh"),
              1,
              4));
      require(count(Reflect.call(h, "getStackInSlot", 1)) == 0, "plain native mesh rejected");
      click(i, main, stack("dead_earth:compact_mesh_diamond_v" + (tier == 5 ? 3 : 5), 1));
      require(count(Reflect.call(h, "getStackInSlot", 1)) == 0, "wrong tier mesh rejected");
      click(i, main, stack("dead_earth:compact_mesh_diamond_v" + tier, 1));
      require(count(Reflect.call(h, "getStackInSlot", 1)) == 1, "matching mesh installed");
      Object tag = Reflect.call(tile, "getUpdateTag/func_189517_E_");
      require(
          ((Number) Reflect.call(tag, "getInteger/func_74762_e", "VisibleMesh")).intValue() == 4
              && !(Boolean) Reflect.call(tag, "hasKey/func_74764_b", "SieveItems")
              && !(Boolean) Reflect.call(tag, "hasKey/func_74764_b", "RewardBank"),
          "only visible state is synchronized");
      require(
          Reflect.is(
              Reflect.call(tile, "getUpdatePacket/func_189518_D_"),
              "net.minecraft.network.play.server.SPacketUpdateTileEntity"),
          "native update packet exists");
      if (i < 6) {
        click(i, main, stack("minecraft:gravel", 1));
        require(count(Reflect.call(h, "getStackInSlot", 0)) == 0, "plain gravel rejected");
        click(i, main, stack("dead_earth:compressed_gravel_" + (tier - 1), 64));
        require(
            count(Reflect.call(h, "getStackInSlot", 0)) == 1
                && count(Reflect.call(actor, "getHeldItem/func_184586_b", main)) == 63,
            "normal stack64 loads exactly one and retains63");
        require(
            fill(i) == 4 && number(tile, "manualStrokes") == 0 && number(tile, "draws") == 0,
            "loading is not a work stroke");
        Reflect.call(actor, "setHeldItem/func_184611_a", main, SievingProbe.empty());
      } else SievingProbe.fillInventory(actor);
      cases.add(
          JsonFiles.map(
              "tier",
              tier,
              "mode",
              i == 6 ? "native_hoppers" : creative ? "creative" : "survival",
              "naturallyTracked",
              true,
              "matchingMeshRequired",
              true,
              "slotLimit",
              1));
    }

    private void prepareHoppers() {
      Object p = positions.get(6),
          input = Reflect.pos(Reflect.x(p), Reflect.y(p) + 1, Reflect.z(p)),
          output = Reflect.pos(Reflect.x(p), Reflect.y(p) - 1, Reflect.z(p));
      Object state =
          Reflect.call(
              Reflect.registry("BLOCKS", "minecraft:hopper"), "getDefaultState/func_176223_P");
      for (Object hp : Arrays.asList(input, output)) {
        require(
            Reflect.loaded(world, hp) && Reflect.blockId(world, hp).equals("minecraft:air"),
            "hopper fixture loaded empty air");
        positions.add(hp);
        require(
            (Boolean) Reflect.call(world, "setBlockState/func_180501_a", hp, state, 3),
            "actual hopper placement");
      }
      hopperInput = Reflect.call(world, "getTileEntity/func_175625_s", input);
      hopperOutput = Reflect.call(world, "getTileEntity/func_175625_s", output);
      require(
          Reflect.is(hopperInput, "net.minecraft.tileentity.TileEntityHopper")
              && Reflect.is(hopperOutput, "net.minecraft.tileentity.TileEntityHopper"),
          "real vanilla hoppers");
      Reflect.call(
          hopperInput,
          "setInventorySlotContents/func_70299_a",
          0,
          stack("dead_earth:compressed_gravel_2", 1));
    }

    private void click(int i, Object hand, Object held) {
      Object p = positions.get(i), actor = actors.get(i), interaction = interactions.get(i);
      Reflect.call(
          actor,
          "setPosition/func_70107_b",
          Reflect.x(p) + .5,
          Reflect.y(p) + 1.0,
          Reflect.z(p) + .5);
      Reflect.call(actor, "setSneaking/func_70095_a", false);
      Reflect.call(actor, "setHeldItem/func_184611_a", hand, held);
      Map<String, Object> context =
          JsonFiles.map(
              "case",
              i,
              "loaded",
              Reflect.loaded(world, p),
              "block",
              Reflect.blockId(world, p),
              "heldBefore",
              count(held),
              "sneaking",
              false);
      Object result =
          Reflect.call(
              interaction,
              "processRightClickBlock/func_187251_a",
              actor,
              world,
              held,
              hand,
              p,
              up,
              .5f,
              .5f,
              .5f);
      context.put("result", String.valueOf(result));
      context.put("heldAfter", count(Reflect.call(actor, "getHeldItem/func_184586_b", hand)));
      report.put("lastPlayerInteraction", context);
      require(
          result.toString().equals("SUCCESS")
              || count(held) == 0 && result.toString().equals("PASS"),
          "native PlayerInteractionManager activation: " + context);
    }

    private void noLoot(int i) {
      Object h = handler(tiles.get(i));
      for (int slot = 2; slot < 29; slot++)
        require(
            count(Reflect.call(h, "getStackInSlot", slot)) == 0
                && count(Reflect.call(h, "extractItem", slot, 64, true)) == 0,
            "no visible/extractable loot before fourth stroke");
      require(
          SievingProbe.inventory(actors.get(i)).isEmpty(),
          "no inventory payout before fourth stroke");
    }

    /**
     * Invoked at LOWEST END after production tick; never calls step/tick/onLoad or changes a clock.
     */
    public boolean poll() {
      runtime.requireThread();
      if (done) return true;
      long now = time();
      if (now == worldPrevious) return false;
      try {
        require(now == worldPrevious + 1, "observe every ordinary server tick");
        worldPrevious = now;
        observedTicks++;
        long current = LegacySieving.nativeDrawCount(), used = current - globalPrevious;
        globalPrevious = current;
        maxDrawsPerTick = Math.max(maxDrawsPerTick, used);
        require(used >= 0 && used <= 256, "global native draw quota256 per ordinary tick");
        for (int i = 0; i < 7; i++) {
          Object tile = tiles.get(i), h = handler(tile);
          require(!(Boolean) Reflect.get(tile, "fault"), "normal gameplay frame remains healthy");
          require(
              count(Reflect.call(h, "getStackInSlot", 1)) == 1, "hopper/work retain matching mesh");
          int first = i == 6 ? 16 : 8;
          if (observedTicks < first)
            require(
                number(tile, "draws") == 0 && number(tile, "manualStrokes") == 0,
                "input including hopper feed cannot authorize free draws");
          if (i == 6 && observedTicks == 12)
            require(
                count(Reflect.call(h, "getStackInSlot", 0)) == 1 && fill(i) == 4,
                "real feeder inserts one without starting work");
          if (observedTicks >= first
              && observedTicks <= first + 24
              && (observedTicks - first) % 8 == 0) {
            noLoot(i);
            click(i, i == 6 ? off : main, SievingProbe.empty());
            strokes[i]++;
            if (strokes[i] == 1) seeds[i] = number(tile, "seed");
            require(fill(i) == 4 - strokes[i], "four real activations show fill4,3,2,1,0");
            if (strokes[i] < 4) {
              require(
                  number(tile, "manualStrokes") == strokes[i], "one activation equals one stroke");
              noLoot(i);
            }
          }
          if (strokes[i] < 4) noLoot(i);
          require(
              number(tile, "draws") <= target(i),
              "one reserved input produces only one native batch");
          if (strokes[i] >= 4 && (i != 6 || observedTicks >= 56))
            for (int slot = 2; slot < 29; slot++)
              SievingProbe.add(extracted.get(i), Reflect.call(h, "extractItem", slot, 64, false));
        }
        for (int slot = 0; slot < 5; slot++) {
          Object s = Reflect.call(hopperOutput, "getStackInSlot/func_70301_a", slot);
          if (count(s) > 0) {
            String id =
                String.valueOf(
                    Reflect.call(Reflect.call(s, "getItem/func_77973_b"), "getRegistryName"));
            require(
                !id.contains("compact_mesh") && !id.contains("compressed_gravel"),
                "bottom hopper extracts only released rewards");
            require(strokes[6] == 4, "hopper cannot extract pre-fourth rewards");
            hopperOutputItems += count(s);
            SievingProbe.add(extracted.get(6), s);
            Reflect.call(
                hopperOutput, "setInventorySlotContents/func_70299_a", slot, SievingProbe.empty());
          }
        }
        boolean complete = true;
        for (int i = 0; i < 7; i++)
          complete &=
              number(tiles.get(i), "draws") == target(i)
                  && number(tiles.get(i), "completed") == 1
                  && number(tiles.get(i), "remaining") == 0
                  && ((List<?>) Reflect.get(tiles.get(i), "bank")).isEmpty();
        if (complete && observedTicks >= 60) completeGrace++;
        else completeGrace = 0;
        if (completeGrace >= 4) {
          finish();
          return true;
        }
        require(
            observedTicks < 240,
            "four work clicks and bounded ordinary ticks finish all batches without extra"
                + " collection click");
        return false;
      } catch (Throwable failure) {
        capture();
        report.put("passed", false);
        report.put("error", failure.toString());
        done = true;
        cleanup();
        return true;
      }
    }

    private void capture() {
      report.put("observedServerTicks", observedTicks);
      report.put("maximumGlobalNativeDrawsPerTick", maxDrawsPerTick);
      report.put("nativeHopperOutputItems", hopperOutputItems);
      for (int i = 0; i < tiles.size(); i++) {
        cases.get(i).put("workClicks", strokes[i]);
        cases.get(i).put("normalTickDraws", number(tiles.get(i), "draws"));
        cases.get(i).put("remainingRawDraws", number(tiles.get(i), "remaining"));
        cases.get(i).put("completedBatches", number(tiles.get(i), "completed"));
      }
    }

    private void finish() {
      capture();
      for (int i = 0; i < 7; i++) {
        Map<String, Long> all = SievingProbe.inventory(actors.get(i));
        for (Map.Entry<String, Long> entry : extracted.get(i).entrySet())
          all.put(
              entry.getKey(),
              (all.containsKey(entry.getKey()) ? all.get(entry.getKey()) : 0) + entry.getValue());
        require(
            strokes[i] == 4 && all.equals(SievingProbe.expected(seeds[i], target(i))),
            "case"
                + i
                + " inventory plus overflow exactly equals native81/729/6561 rewards; no fifth"
                + " click: "
                + all);
        cases.get(i).put("exactNativeRewards", true);
      }
      int chat = 0;
      for (Object network : networks)
        for (Object tuple :
            (Iterable<?>) Reflect.get(network, "outboundPacketsQueue/field_150745_j"))
          if (Reflect.is(
              Reflect.get(tuple, "packet/field_150774_a"),
              "net.minecraft.network.play.server.SPacketChat")) chat++;
      require(chat == 0, "all accepted and rejected interactions emit zero chat packets");
      require(
          hopperOutputItems > 0
              && count(Reflect.call(hopperInput, "getStackInSlot/func_70301_a", 0)) == 0,
          "real hoppers feed input and transport retained overflow");
      report.put("fourWorkClicksWholeBatch", true);
      report.put("noDrawBeforeFirstWork", true);
      report.put("noLootBeforeFourthWork", true);
      report.put("noExtraCollectionClick", true);
      report.put("chatPackets", chat);
      report.put("passed", true);
      done = true;
      cleanup();
    }

    public Map<String, Object> result() {
      require(done, "normal ticks must finish before reporting");
      return report;
    }

    private void cleanup() {
      for (Object actor : actors) {
        UUID uuid = (UUID) Reflect.call(actor, "getUniqueID/func_110124_au");
        if (playerMap.get(uuid) == actor) playerMap.remove(uuid);
      }
      Object air =
          Reflect.call(
              Reflect.registry("BLOCKS", "minecraft:air"), "getDefaultState/func_176223_P");
      for (Object p : positions)
        if (Reflect.loaded(world, p)
            && (Reflect.blockId(world, p).startsWith("dead_earth:compact_sieve_")
                || Reflect.blockId(world, p).equals("minecraft:hopper"))) {
          Reflect.call(world, "removeTileEntity/func_175713_t", p);
          Reflect.call(world, "setBlockState/func_180501_a", p, air, 2);
        }
    }
  }
}
