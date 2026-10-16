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
import dev.deadearth.command.DeadEarthCommands;
import dev.deadearth.compat.ProjectECompatibility;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.minecraft.server.level.ServerPlayer;

@Mod(DeadEarth.MOD_ID)
public final class DeadEarth {
    public static final String MOD_ID = "dead_earth";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    private static final Map<MinecraftServer, CoordinatorRuntime> RUNTIMES = new IdentityHashMap<>();
    private static final Map<MinecraftServer, Map<java.util.UUID, LivingDeathEvent>> PENDING_DEATHS = new IdentityHashMap<>();

    public DeadEarth(IEventBus modBus,net.neoforged.fml.ModContainer container) {
        container.registerConfig(net.neoforged.fml.config.ModConfig.Type.SERVER,dev.deadearth.config.DeadEarthConfig.SPEC,"dead-earth-server.toml");
        ProjectECompatibility.install(modBus);
        dev.deadearth.content.SeasonBlocks.register(modBus);
        dev.deadearth.content.ZoneLoaderContent.register(modBus);
        dev.deadearth.zone.ZoneSentryIndex.install();
        modBus.addListener(dev.deadearth.climate.SeasonNetwork::register);
        NeoForge.EVENT_BUS.addListener(this::started);
        NeoForge.EVENT_BUS.addListener(this::stopped);
        NeoForge.EVENT_BUS.addListener(this::tick);
        NeoForge.EVENT_BUS.addListener(DeadEarthCommands::register);
        NeoForge.EVENT_BUS.addListener(this::death);
        NeoForge.EVENT_BUS.addListener(this::logout);
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
    private void stopped(ServerStoppedEvent event) {
        var runtime = RUNTIMES.remove(event.getServer()); if (runtime != null) runtime.close();
        PENDING_DEATHS.remove(event.getServer());
        dev.deadearth.climate.SeasonState.reset();
        dev.deadearth.compat.SeasonHooks.reset();
        dev.deadearth.zone.ZoneLoaders.close(event.getServer());
        dev.deadearth.zone.ZoneSentryIndex.close(event.getServer());
    }
    private void tick(ServerTickEvent.Post event) {
        dev.deadearth.zone.ZoneLoaders.tick(event.getServer());
        var runtime = RUNTIMES.get(event.getServer());
        var deaths = PENDING_DEATHS.remove(event.getServer());
        if (runtime != null) {
            if (deaths != null) for (var death : deaths.values())
                if (!death.isCanceled() && death.getEntity() instanceof ServerPlayer player && player.isDeadOrDying()) runtime.recordDeath(player);
            runtime.tick();
        }
    }
    private void logout(net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent event) {
        if(event.getEntity() instanceof ServerPlayer p) {var runtime=RUNTIMES.get(p.getServer());if(runtime!=null)runtime.playerLeft(p.getUUID());}
    }
    private void death(LivingDeathEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            var server = player.getServer();
            if (server.isSameThread() && RUNTIMES.containsKey(server)) {
                var pending = PENDING_DEATHS.computeIfAbsent(server, ignored -> new java.util.LinkedHashMap<>());
                if (pending.size() < 64 || pending.containsKey(player.getUUID())) pending.put(player.getUUID(), event);
            }
        }
    }
}
