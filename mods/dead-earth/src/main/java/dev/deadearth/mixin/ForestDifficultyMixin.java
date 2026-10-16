package dev.deadearth.mixin;
import dev.deadearth.climate.SeasonState;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo @Mixin(targets="com.yayuy.overgrownvegetation.difficulty.WorldDifficultyData",remap=false)
public abstract class ForestDifficultyMixin {
    @Inject(method="phasePointMultiplier",at=@At("RETURN"),cancellable=true)
    private static void deadEarthPoints(MinecraftServer server,CallbackInfoReturnable<Double> result) {
        result.setReturnValue(result.getReturnValue()*SeasonState.forest());
    }
}
