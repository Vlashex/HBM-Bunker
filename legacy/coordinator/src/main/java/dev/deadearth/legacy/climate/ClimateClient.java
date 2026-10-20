package dev.deadearth.legacy.climate;

import dev.deadearth.legacy.Reflect;
import java.util.Random;
import net.minecraftforge.fml.common.eventhandler.Event;
import net.minecraftforge.fml.common.gameevent.TickEvent;

/**
 * No Minecraft client type is linked by common/server class loading. Only client events call
 * reflection here.
 */
final class ClimateClient {
  private static volatile ClimatePacket snapshot;
  private static final Random RANDOM = new Random();

  private ClimateClient() {}

  static void accept(ClimatePacket value) {
    snapshot = value;
  }

  static void reset() {
    snapshot = null;
  }

  private static Object world() {
    Object client =
        Reflect.call(Reflect.type("net.minecraft.client.Minecraft"), "getMinecraft/func_71410_x");
    return Reflect.get(client, "world/field_71441_e");
  }

  static void fog(Event event, String name) {
    ClimatePacket p = snapshot;
    if (p == null || !p.enabled || p.season == 0) return;
    Object w = world();
    if (w == null || Reflect.dimension(w) != 0) return;
    if (name.endsWith("$FogColors")) {
      Reflect.call(event, "setRed", p.red);
      Reflect.call(event, "setGreen", p.green);
      Reflect.call(event, "setBlue", p.blue);
    } else if (name.endsWith("$FogDensity") && p.fog > 0) {
      Reflect.call(event, "setDensity", p.fog);
      event.setCanceled(true);
    }
  }

  static void tick(Event event) {
    ClimatePacket p = snapshot;
    if (p == null || !p.enabled || p.season == 0) return;
    if (Reflect.get(event, "phase") != TickEvent.Phase.END) return;
    Object client =
        Reflect.call(Reflect.type("net.minecraft.client.Minecraft"), "getMinecraft/func_71410_x");
    Object w = Reflect.get(client, "world/field_71441_e"),
        player = Reflect.get(client, "player/field_71439_g");
    if (w == null || player == null || Reflect.dimension(w) != 0) return;
    Object origin = Reflect.position(player);
    Object particle =
        Reflect.get(
            Reflect.type("net.minecraft.util.EnumParticleTypes"),
            p.season == 2 ? "SNOW_SHOVEL" : "SMOKE_NORMAL");
    for (int i = 0; i < p.particles; i++) {
      double x = Reflect.x(origin) + (RANDOM.nextDouble() * 2 - 1) * p.radius;
      double z = Reflect.z(origin) + (RANDOM.nextDouble() * 2 - 1) * p.radius;
      double y = Reflect.y(origin) + 2 + RANDOM.nextDouble() * p.height;
      Object at = Reflect.pos((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z));
      if (!(Boolean) Reflect.call(w, "isBlockLoaded/func_175667_e", at)
          || !(Boolean) Reflect.call(w, "canSeeSky/func_175678_i", at)) continue;
      Reflect.call(
          w,
          "spawnParticle/func_175688_a",
          particle,
          x,
          y,
          z,
          .02,
          p.season == 2 ? -.08 : .015,
          .01,
          new int[0]);
    }
  }
}
