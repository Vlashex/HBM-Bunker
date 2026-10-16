package dev.deadearth.compat;

import dev.deadearth.DeadEarth;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModList;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;

public final class ProjectECompatibility {
    private ProjectECompatibility() {}
    public static void install(IEventBus modBus) {
        modBus.addListener((FMLClientSetupEvent event) -> event.enqueueWork(() -> {
            if (!ModList.get().isLoaded("projecte")) return;
            try {
                for (var name : new String[] {
                        "moze_intel.projecte.api.components.IComponentProcessorHelper",
                        "moze_intel.projecte.api.codec.IPECodecHelper",
                        "moze_intel.projecte.api.proxy.IEMCProxy",
                        "moze_intel.projecte.api.proxy.ITransmutationProxy"}) ServiceBootstrap.initialize(name);
                DeadEarth.LOGGER.info("ProjectE API initialized before background tooltip indexing");
            } catch (ClassNotFoundException error) {
                throw new IllegalStateException("Installed ProjectE API differs from the tested pack version", error);
            }
        }));
    }
}
