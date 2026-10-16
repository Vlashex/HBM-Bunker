package dev.deadearth.mixin;
import dev.deadearth.climate.SeasonState;
import net.neoforged.neoforge.common.ModConfigSpec;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Pseudo @Mixin(targets={"com.yayuy.overgrownvegetation.phase.OvergrownBiomeSpawner","com.yayuy.overgrownvegetation.phase.OutsideInvaderSpawner"},remap=false)
public abstract class ForestSpawnMixin {
    @Redirect(method="onLevelTick",at=@At(value="INVOKE",target="Lnet/neoforged/neoforge/common/ModConfigSpec$IntValue;get()Ljava/lang/Object;"))
    private static Object deadEarthInterval(ModConfigSpec.IntValue value) {
        int original=value.get();
        return value.getPath().contains("overgrownSpawnIntervalTicks")?Math.max(1,(int)Math.round(original/SeasonState.forest())):original;
    }
}
