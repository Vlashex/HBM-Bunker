package dev.deadearth.legacyprobe.combat;

import dev.deadearth.legacy.DeadEarthLegacy;
import dev.deadearth.legacy.JsonFiles;
import dev.deadearth.legacy.LegacyRuntime;
import dev.deadearth.legacy.Reflect;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Map;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLServerStartedEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

/** Separate narrow fixture; never compiled into the coordinator or ordinary full PortProbe. */
@Mod(
    modid = "deadearth_combat_probe",
    name = "Dead Earth Combat Only Probe",
    version = "1.0",
    dependencies =
        "required-after:dead_earth;required-after:srparasites;required-after:epicsiegemod")
public final class CombatOnlyProbe {
  private SiegeProbe.Session session;
  private LegacyRuntime runtime;
  private int warmup = 2;
  private boolean done;

  @Mod.EventHandler
  public void started(FMLServerStartedEvent event) {
    if (!Boolean.getBoolean("deadearth.testControls")
        || !Files.isRegularFile(Paths.get(".dead-earth-isolated-native-test"))
        || !Files.isRegularFile(Paths.get(".dead-earth-combat-only")))
      throw new IllegalStateException("Separate isolated combat-only controls required");
    runtime = DeadEarthLegacy.runtime;
    runtime.requireThread();
    MinecraftForge.EVENT_BUS.register(this);
  }

  @SubscribeEvent
  public void tick(TickEvent.ServerTickEvent event) {
    if (done || event.phase != TickEvent.Phase.END) return;
    try {
      if (session == null) {
        if (--warmup > 0) return;
        Object world = runtime.world.world(0);
        Object spawn = Reflect.call(world, "getSpawnPoint/func_175694_M");
        int x = Reflect.x(spawn) + 256, z = Reflect.z(spawn) + 272;
        // Explicit fixture preparation only; ordinary AI checks afterwards may not force chunks.
        for (int dx = -3; dx <= 3; dx++)
          for (int dz = -3; dz <= 3; dz++)
            Reflect.call(
                world, "getChunkFromChunkCoords/func_72964_e", (x >> 4) + dx, (z >> 4) + dz);
        session = SiegeProbe.begin(runtime, world, null, x, 180, z);
      }
      if (session.poll()) finish(session.result());
    } catch (Throwable failure) {
      failure.printStackTrace();
      finish(JsonFiles.map("passed", false, "error", failure.toString()));
    }
  }

  private void finish(Map<String, Object> result) {
    if (done) return;
    done = true;
    try {
      Files.write(
          Paths.get("combat-probe.json"),
          JsonFiles.JSON.toJson(result).getBytes(StandardCharsets.UTF_8));
      System.out.println("DEAD EARTH isolated combat probe complete: " + result.get("passed"));
    } catch (Exception failed) {
      throw new IllegalStateException(failed);
    } finally {
      Reflect.call(runtime.server, "initiateShutdown/func_71263_m");
    }
  }
}
