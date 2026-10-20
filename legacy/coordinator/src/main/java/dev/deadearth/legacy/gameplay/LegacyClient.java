package dev.deadearth.legacy.gameplay;

import dev.deadearth.legacy.Reflect;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.*;

/** Reflective client-only target outline; dedicated servers never link Minecraft client types. */
public final class LegacyClient {
  private static final LegacyClient LISTENER = new LegacyClient();

  public static void install() {
    Object side =
        Reflect.call(
            Reflect.call(
                Reflect.type("net.minecraftforge.fml.common.FMLCommonHandler"), "instance"),
            "getSide");
    if (side.toString().equals("CLIENT")) MinecraftForge.EVENT_BUS.register(LISTENER);
  }

  @SubscribeEvent
  public void draw(Event event) {
    if (!event.getClass().getName().endsWith("DrawBlockHighlightEvent")) return;
    Object target = Reflect.call(event, "getTarget");
    if (target == null
        || !Reflect.get(target, "typeOfHit/field_72313_a").toString().equals("BLOCK")) return;
    Object pos = Reflect.call(target, "getBlockPos/func_178782_a");
    if (pos == null) return;
    Object player = Reflect.call(event, "getPlayer"), world = Reflect.world(player);
    if (!Reflect.loaded(world, pos)
        || (Boolean) Reflect.call(world, "isAirBlock/func_175623_d", pos)) return;
    Object state = Reflect.state(world, pos),
        box = Reflect.call(state, "getSelectedBoundingBox/func_185918_c", world, pos);
    if (box == null) return;
    double partial = ((Number) Reflect.call(event, "getPartialTicks")).doubleValue();
    double x = axis(player, "lastTickPosX/field_70142_S", "posX/field_70165_t", partial),
        y = axis(player, "lastTickPosY/field_70137_T", "posY/field_70163_u", partial),
        z = axis(player, "lastTickPosZ/field_70136_U", "posZ/field_70161_v", partial);
    box =
        Reflect.call(
            Reflect.call(box, "grow/func_186662_g", .002d), "offset/func_72317_d", -x, -y, -z);
    Class<?> gl = Reflect.type("org.lwjgl.opengl.GL11");
    Reflect.call(gl, "glPushAttrib", 0x000fffff);
    try {
      Reflect.call(gl, "glEnable", 3042);
      Reflect.call(gl, "glBlendFunc", 770, 771);
      Reflect.call(gl, "glDisable", 3553);
      Reflect.call(gl, "glDepthMask", false);
      Reflect.call(gl, "glLineWidth", 2.5f);
      Reflect.call(
          Reflect.type("net.minecraft.client.renderer.RenderGlobal"),
          "drawSelectionBoundingBox/func_189697_a",
          box,
          .15f,
          .85f,
          1f,
          .85f);
      event.setCanceled(true);
    } finally {
      Reflect.call(gl, "glPopAttrib");
    }
  }

  private static double axis(Object p, String previous, String current, double t) {
    double a = ((Number) Reflect.get(p, previous)).doubleValue();
    return a + (((Number) Reflect.get(p, current)).doubleValue() - a) * t;
  }
}
