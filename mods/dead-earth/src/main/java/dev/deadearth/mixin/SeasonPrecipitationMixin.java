package dev.deadearth.mixin;

import dev.deadearth.climate.ClimateCycle;
import dev.deadearth.climate.SeasonState;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.biome.Biome;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Biome.class)
public abstract class SeasonPrecipitationMixin {
    @Inject(method="getPrecipitationAt",at=@At("RETURN"),cancellable=true)
    private void deadEarthSnow(BlockPos pos,CallbackInfoReturnable<Biome.Precipitation> result) {
        if (dev.deadearth.config.DeadEarthConfig.b(dev.deadearth.config.DeadEarthConfig.WINTER_SNOW) && SeasonState.phase()==ClimateCycle.Phase.ASH_WINTER) result.setReturnValue(Biome.Precipitation.SNOW);
    }
}
