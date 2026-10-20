package dev.deadearth.legacy.climate;

import io.netty.buffer.ByteBuf;
import net.minecraftforge.fml.common.network.simpleimpl.*;

/**
 * Server-authored presentation only; no client request, terrain authority, runtime or coordinates.
 */
public final class ClimatePacket implements IMessage {
  int season, particles, radius, height;
  boolean enabled;
  float fog, red, green, blue;

  public ClimatePacket() {}

  ClimatePacket(int season, ClimateConfig c) {
    this.season = season;
    enabled = c.clientPresentation;
    particles = c.particlesPerTick;
    radius = c.particleRadius;
    height = c.particleHeight;
    fog = (float) (season == 2 ? c.winterFog : season == 1 ? c.heatFog : 0);
    red = (float) (season == 2 ? c.winterFogRed : c.heatFogRed);
    green = (float) (season == 2 ? c.winterFogGreen : c.heatFogGreen);
    blue = (float) (season == 2 ? c.winterFogBlue : c.heatFogBlue);
  }

  public void toBytes(ByteBuf out) {
    out.writeByte(season);
    out.writeBoolean(enabled);
    out.writeByte(particles);
    out.writeByte(radius);
    out.writeByte(height);
    out.writeFloat(fog);
    out.writeFloat(red);
    out.writeFloat(green);
    out.writeFloat(blue);
  }

  public void fromBytes(ByteBuf in) {
    season = in.readUnsignedByte();
    enabled = in.readBoolean();
    particles = in.readUnsignedByte();
    radius = in.readUnsignedByte();
    height = in.readUnsignedByte();
    fog = in.readFloat();
    red = in.readFloat();
    green = in.readFloat();
    blue = in.readFloat();
    if (season > 2
        || particles > 32
        || radius < 4
        || radius > 64
        || height < 2
        || height > 32
        || !valid(fog, 0, .1f)
        || !valid(red, 0, 1)
        || !valid(green, 0, 1)
        || !valid(blue, 0, 1)) enabled = false;
  }

  private static boolean valid(float n, float min, float max) {
    return Float.isFinite(n) && n >= min && n <= max;
  }

  public static final class Handler implements IMessageHandler<ClimatePacket, IMessage> {
    public IMessage onMessage(ClimatePacket message, MessageContext context) {
      // Immutable after decoding. Event callbacks read the volatile snapshot on the client thread.
      ClimateClient.accept(message);
      return null;
    }
  }
}
