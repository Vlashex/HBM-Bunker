package dev.deadearth.mixin;
import dev.deadearth.climate.SeasonState;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo @Mixin(targets="com.yayuy.overgrownvegetation.corruption.CorruptionSpreader",remap=false)
public abstract class ForestCorruptionMixin {
    @Unique private double deadEarthCarry;
    @Inject(method="effectiveBlocksPerCycle",at=@At("RETURN"),cancellable=true)
    private void deadEarthBudget(ServerLevel level,CallbackInfoReturnable<Integer> result) {
        double total=deadEarthCarry+result.getReturnValue()*SeasonState.forest();
        int budget=(int)Math.floor(total+1e-9);deadEarthCarry=Math.max(0,total-budget);result.setReturnValue(budget);
    }
}
