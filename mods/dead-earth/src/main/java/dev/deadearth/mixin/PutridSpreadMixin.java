package dev.deadearth.mixin;
import dev.deadearth.climate.SeasonState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.At;

@Pseudo @Mixin(targets="com.asestefan.mutationcraft.block.PutridBlock",remap=false)
public abstract class PutridSpreadMixin {
    @ModifyConstant(method="randomTick",constant=@Constant(doubleValue=.3))
    private double deadEarthSpread(double base) {return Math.clamp(base*SeasonState.parasites(),0,1);}
    @ModifyVariable(method="infect",at=@At("HEAD"),argsOnly=true,ordinal=0)
    private static float deadEarthContactDamage(float base) {return (float)(base*SeasonState.parasites());}
}
