package dev.deadearth.mixin;
import dev.deadearth.defense.DefensePolicy;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo @Mixin(targets="com.asestefan.mutationcraft.block.PutridBlock",remap=false)
public abstract class DefensePutridMixin {
    @Inject(method="canSpreadInto",at=@At("HEAD"),cancellable=true)
    private static void defensePredicate(ServerLevel level,BlockPos target,BlockPos vine,CallbackInfoReturnable<Boolean> ci) {
        if(DefensePolicy.immune(level,target)||DefensePolicy.immune(level,vine))ci.setReturnValue(false);
    }
    // The native fallback invokes grow without a successful predicate. Guard the actual write too.
    @Inject(method="grow",at=@At("HEAD"),cancellable=true)
    private static void defenseGrow(ServerLevel level,BlockPos target,BlockPos vine,RandomSource random,CallbackInfo ci) {
        if(DefensePolicy.immune(level,target))ci.cancel();
        // A protected vine position is separately blocked by the low-level replacement guard.
    }
}
