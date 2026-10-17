package dev.deadearth.mixin;
import dev.deadearth.climate.SeasonState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo @Mixin(targets="com.asestefan.mutationcraft.config.MutationcraftConfig$Number",remap=false)
public abstract class MutationNumberMixin {
    @Unique private String deadEarthName;
    @Inject(method="get",at=@At("RETURN"),cancellable=true)
    private void deadEarthScale(CallbackInfoReturnable<Double> result) {
        if(deadEarthName==null) try {deadEarthName=(String)((Object)this).getClass().getField("name").get(this);}
        catch(ReflectiveOperationException error) {throw new IllegalStateException("Unsupported Mutationcraft Number",error);}
        if(deadEarthName.equals("outbreakTimerSpeed")) result.setReturnValue(result.getReturnValue()*SeasonState.parasiteEvolution());
        else if(deadEarthName.equals("hookFromNecroptorChance")) result.setReturnValue(Math.clamp(result.getReturnValue()*SeasonState.parasites(),0,1));
    }
}
