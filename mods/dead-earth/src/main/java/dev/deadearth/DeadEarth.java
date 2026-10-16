package dev.deadearth;

import java.util.IdentityHashMap;
import java.util.Map;
import net.minecraft.server.MinecraftServer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mod(DeadEarth.MOD_ID)
public final class DeadEarth {
    public static final String MOD_ID = "dead_earth";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    private static final Map<MinecraftServer, CoordinatorRuntime> RUNTIMES = new IdentityHashMap<>();

    public DeadEarth() {
        NeoForge.EVENT_BUS.addListener(this::started);
        NeoForge.EVENT_BUS.addListener(this::stopped);
        NeoForge.EVENT_BUS.addListener(this::tick);
    }

    public static CoordinatorRuntime runtime(MinecraftServer server) {
        if (!server.isSameThread()) throw new IllegalStateException("Dead Earth access outside server thread");
        var runtime = RUNTIMES.get(server);
        if (runtime == null) throw new IllegalStateException("Dead Earth coordinator unavailable; inspect server log");
        return runtime;
    }

    private void started(ServerStartedEvent event) {
        try {
            var runtime = new CoordinatorRuntime(event.getServer());
            RUNTIMES.put(event.getServer(), runtime);
            LOGGER.info("Dead Earth coordinator {}", runtime.enabled() ? "ready" : "disabled: unsupported campaign save");
        } catch (RuntimeException invalid) {
            LOGGER.error("Dead Earth disabled; existing saves will not be replaced", invalid);
        }
    }
    private void stopped(ServerStoppedEvent event) { RUNTIMES.remove(event.getServer()); }
    private void tick(ServerTickEvent.Post event) {
        var runtime = RUNTIMES.get(event.getServer());
        if (runtime != null) runtime.tick();
    }
}
