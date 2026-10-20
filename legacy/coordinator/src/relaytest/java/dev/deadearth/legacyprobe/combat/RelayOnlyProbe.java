package dev.deadearth.legacyprobe.combat;

import dev.deadearth.legacy.DeadEarthLegacy;
import dev.deadearth.legacy.JsonFiles;
import dev.deadearth.legacy.LegacyRuntime;
import dev.deadearth.legacy.Reflect;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Map;
import java.util.UUID;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLServerStartedEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

/** Separate relay fixture; never compiled into the coordinator or ordinary full PortProbe. */
@Mod(
    modid = "deadearth_relay_probe",
    name = "Dead Earth Relay Only Probe",
    version = "1.0",
    dependencies =
        "required-after:dead_earth;required-after:srparasites;required-after:epicsiegemod")
public final class RelayOnlyProbe {
  private RelaySiegeProbe.Session session;
  private LegacyRuntime runtime;
  private int warmup = 2;
  private boolean done;

  @Mod.EventHandler
  public void started(FMLServerStartedEvent event) {
    if (!Boolean.getBoolean("deadearth.testControls")
        || !Files.isRegularFile(Paths.get(".dead-earth-isolated-native-test"))
        || !Files.isRegularFile(Paths.get(".dead-earth-relay-only")))
      throw new IllegalStateException("Separate isolated relay-only controls required");
    runtime = DeadEarthLegacy.runtime;
    runtime.requireThread();
    MinecraftForge.EVENT_BUS.register(this);
  }

  @SubscribeEvent
  public void tick(TickEvent.ServerTickEvent event) {
    if (done || event.phase != TickEvent.Phase.END) return;
    try {
      if (session == null) {
        if (warmup-- > 0) return;
        Object world = runtime.world.world(0);
        Object spawn = Reflect.call(world, "getSpawnPoint/func_175694_M");
        int x = Reflect.x(spawn) + 320, z = Reflect.z(spawn) + 272;
        // Matches full PortProbe.startRelayGameplay; only fixture preparation may load chunks.
        for (int dx = -7; dx <= 7; dx++)
          for (int dz = -7; dz <= 7; dz++)
            Reflect.call(
                world, "getChunkFromChunkCoords/func_72964_e", (x >> 4) + dx, (z >> 4) + dz);
        Object profile =
            Reflect.make(
                "com.mojang.authlib.GameProfile",
                UUID.fromString("b7701f9d-518c-46d6-b0f4-42e5648fa1b9"),
                "DeadEarthRelay");
        Object player =
            Reflect.call(
                Reflect.type("net.minecraftforge.common.util.FakePlayerFactory"),
                "get",
                world,
                profile);
        Reflect.call(
            Reflect.get(player, "interactionManager/field_71134_c"),
            "setGameType/func_73076_a",
            Reflect.get(Reflect.type("net.minecraft.world.GameType"), "SURVIVAL"));
        // The session deliberately grants invulnerability: this fixture measures block HP only.
        session = RelaySiegeProbe.begin(runtime, world, player, x, 180, z);
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
          Paths.get("relay-probe.json"),
          JsonFiles.JSON.toJson(result).getBytes(StandardCharsets.UTF_8));
      System.out.println("DEAD EARTH isolated relay probe complete: " + result.get("passed"));
    } catch (Exception failed) {
      throw new IllegalStateException(failed);
    } finally {
      Reflect.call(runtime.server, "initiateShutdown/func_71263_m");
    }
  }
}
