package dev.deadearth.mixin;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.deadearth.defense.DefensePolicy;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;

/** Nuclear destruction is deliberately allowed. Raw parallel section snapshots need no defense filter. */
@Pseudo @Mixin(targets="com.hbm.explosion.ExplosionNukeRayBatched",remap=false)
public abstract class DefenseHbmBatchedMixin {
    @WrapMethod(method="destructionTick")
    private void defenseNuclear(long timeMs,Operation<Void> nativeCall) {
        DefensePolicy.scoped(DefensePolicy.Scope.NUCLEAR,()->nativeCall.call(timeMs));
    }
}
