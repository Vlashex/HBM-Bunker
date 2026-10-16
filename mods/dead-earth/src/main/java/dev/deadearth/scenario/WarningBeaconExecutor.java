package dev.deadearth.scenario;

import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.network.chat.Component;
import dev.deadearth.scenario.ScenarioTypes.*;

public final class WarningBeaconExecutor implements StepExecutor {
    private final MinecraftServer server;
    public WarningBeaconExecutor(MinecraftServer server) { this.server = server; }
    @Override public StepResult execute(UUID id) {
        if (!server.isSameThread()) throw new IllegalStateException("Signal outside server thread");
        if (server.getPlayerCount() == 0) return StepResult.UNCERTAIN;
        server.getPlayerList().broadcastSystemMessage(Component.literal("[DEAD EARTH] Тестовый сигнал " + id), false);
        return StepResult.COMPLETED;
    }
}
