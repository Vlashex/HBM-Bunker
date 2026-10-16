package dev.deadearth.climate;

import dev.deadearth.client.SeasonVisuals;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

public final class SeasonNetwork {
    public record State(int phase,double pressure) implements CustomPacketPayload {
        public static final Type<State> TYPE=new Type<>(ResourceLocation.parse("dead_earth:season"));
        public static final StreamCodec<RegistryFriendlyByteBuf,State> CODEC=StreamCodec.of(
                (buf,value)->{buf.writeVarInt(value.phase);buf.writeDouble(value.pressure);},
                buf->new State(buf.readVarInt(),buf.readDouble()));
        public State {
            if (phase< -1 || phase>=ClimateCycle.Phase.values().length || !Double.isFinite(pressure) || pressure<.05 || pressure>4)
                throw new IllegalArgumentException("Invalid season packet");
        }
        @Override public Type<State> type() { return TYPE; }
    }
    private SeasonNetwork() {}
    public static void register(RegisterPayloadHandlersEvent event) {
        event.registrar("2").playToClient(State.TYPE,State.CODEC,(value,context)->SeasonVisuals.receive(value));
    }
}
