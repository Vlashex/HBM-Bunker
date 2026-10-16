package dev.deadearth.mixin;

import dev.deadearth.compat.ServiceBootstrap;
import java.util.ServiceLoader;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Ponder 1.0.82 uses an arbitrary worker context for platform SPI discovery. */
@Pseudo @Mixin(targets="net.createmod.catnip.platform.CatnipServices",remap=false)
public abstract class CatnipServicesMixin {
    @Redirect(method="load",at=@At(value="INVOKE",target="Ljava/util/ServiceLoader;load(Ljava/lang/Class;)Ljava/util/ServiceLoader;"))
    private static <T> ServiceLoader<T> deadEarthOwnServiceLoader(Class<T> service) {return ServiceBootstrap.services(service);}
}
