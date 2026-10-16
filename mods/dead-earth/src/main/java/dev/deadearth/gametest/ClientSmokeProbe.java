package dev.deadearth.gametest;

import dev.deadearth.DeadEarth;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.TooltipFlag;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;

/** Explicitly enabled development smoke run; never active in ordinary gameplay. */
public final class ClientSmokeProbe {
    private int joinedTicks;
    private boolean started, finished;
    private int finishTicks;
    private final int holdTicks = Math.clamp(Integer.getInteger("deadearth.clientSmokeHoldTicks", 160), 20, 2400);
    private ClientSmokeProbe() {}
    public static void install() {
        if (Boolean.getBoolean("deadearth.clientSmoke")) {
            var probe = new ClientSmokeProbe();
            NeoForge.EVENT_BUS.addListener(probe::tick);
        }
    }
    private void tick(ClientTickEvent.Post event) {
        var client = Minecraft.getInstance();
        if(Boolean.getBoolean("deadearth.seasonScreenshot")) client.options.hideGui=true;
        if (client.player == null || client.level == null) return;
        if (finished) {
            if (++finishTicks == holdTicks) {
                DeadEarth.LOGGER.info("DEAD_EARTH_CLIENT_SMOKE completed; shutting down owned test client");
                client.stop();
            }
            return;
        }
        if (started || ++joinedTicks < 300) return;
        started = true;
        var player = client.player;
        var context = Item.TooltipContext.of(client.level);
        CompletableFuture.runAsync(() -> {
            var thread = Thread.currentThread(); var previous = thread.getContextClassLoader();
            try {
                thread.setContextClassLoader(ClassLoader.getPlatformClassLoader());
                for (var item : new ItemStack[] {new ItemStack(Items.IRON_INGOT), new ItemStack(Items.DIAMOND), new ItemStack(Items.STONE)})
                    if (item.getTooltipLines(context, player, TooltipFlag.Default.NORMAL).isEmpty()) throw new IllegalStateException("Empty tooltip");
            } finally { thread.setContextClassLoader(previous); }
        }).whenComplete((unused, error) -> client.execute(() -> {
            if (error != null) {
                DeadEarth.LOGGER.error("DEAD_EARTH_CLIENT_SMOKE failed", error);
                client.stop();
            } else {
                DeadEarth.LOGGER.info("DEAD_EARTH_CLIENT_SMOKE worker tooltips passed");
                Screenshot.grab(client.gameDirectory, client.getMainRenderTarget(), message -> DeadEarth.LOGGER.info("Smoke screenshot: {}", message.getString()));
                finished = true;
            }
        }));
    }
}
