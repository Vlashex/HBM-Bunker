package dev.deadearth.mixin;
import dev.deadearth.climate.SeasonState;
import dev.deadearth.compat.SeasonHooks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo @Mixin(targets="com.yayuy.overgrownvegetation.data.SpreadTable",remap=false)
public abstract class ForestLedgerMixin {
    @Inject(method="chanceByContacts",at=@At("RETURN"),cancellable=true)
    private void deadEarthLedger(CallbackInfoReturnable<double[]> result) {
        result.setReturnValue(SeasonHooks.chances(result.getReturnValue(),SeasonState.forest()));
    }
}
