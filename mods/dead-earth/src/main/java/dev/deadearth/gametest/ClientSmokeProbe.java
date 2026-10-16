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
            if(Boolean.getBoolean("deadearth.seasonScreenshot")&&finishTicks%200==199) {
                DeadEarth.LOGGER.info("DEAD_EARTH_VISUAL_SMOKE fps={}",client.getFps());
                Screenshot.grab(client.gameDirectory,client.getMainRenderTarget(),message->DeadEarth.LOGGER.info("Smoke screenshot: {}",message.getString()));
            }
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
                for (var item : new ItemStack[] {new ItemStack(Items.IRON_INGOT), new ItemStack(Items.DIAMOND), new ItemStack(Items.STONE),new ItemStack(dev.deadearth.content.ZoneLoaderContent.ITEM.get())})
                    if (item.getTooltipLines(context, player, TooltipFlag.Default.NORMAL).isEmpty()) throw new IllegalStateException("Empty tooltip");
            } finally { thread.setContextClassLoader(previous); }
        }).whenComplete((unused, error) -> client.execute(() -> {
            if (error != null) {
                DeadEarth.LOGGER.error("DEAD_EARTH_CLIENT_SMOKE failed", error);
                client.stop();
            } else {
                var loaderStack=new ItemStack(dev.deadearth.content.ZoneLoaderContent.ITEM.get());
                if(client.getItemRenderer().getModel(loaderStack,client.level,player,0)==client.getModelManager().getMissingModel()) {
                    DeadEarth.LOGGER.error("DEAD_EARTH_ZONE_CLIENT missing loader model");client.stop();return;
                }
                DeadEarth.LOGGER.info("DEAD_EARTH_CLIENT_SMOKE worker tooltips passed");
                DeadEarth.LOGGER.info("DEAD_EARTH_ZONE_CLIENT loaderName={} coefficient={}",new ItemStack(dev.deadearth.content.ZoneLoaderContent.ITEM.get()).getHoverName().getString(),dev.deadearth.config.DeadEarthConfig.d(dev.deadearth.config.DeadEarthConfig.ZONE_EMC));
                DeadEarth.LOGGER.info("DEAD_EARTH_CONFIG_SMOKE phaseMinutes={} maxAbove={} healthInfluence={}",
                    dev.deadearth.config.DeadEarthConfig.i(dev.deadearth.config.DeadEarthConfig.PHASE_MINUTES),
                    dev.deadearth.config.DeadEarthConfig.d(dev.deadearth.config.DeadEarthConfig.ADAPTIVE_ABOVE),
                    dev.deadearth.config.DeadEarthConfig.d(dev.deadearth.config.DeadEarthConfig.HEALTH_INFLUENCE));
                Screenshot.grab(client.gameDirectory, client.getMainRenderTarget(), message -> DeadEarth.LOGGER.info("Smoke screenshot: {}", message.getString()));
                finished = true;
            }
        }));
    }
}
