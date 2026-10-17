package dev.deadearth.mixin;
import dev.deadearth.defense.DefenseMigration;
import dev.deadearth.defense.DefensePolicy;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo @Mixin(targets="io.github.flemmli97.improvedmobs.common.utils.Utils",remap=false)
public abstract class DefenseImprovedUtilsMixin {
    @Inject(method="canBreakState",at=@At("HEAD"),cancellable=true)
    private static void defenseTarget(LivingEntity entity,BlockState state,CallbackInfoReturnable<Boolean> ci) {
        // Only dedicated defense materials/explicit legacy IDs bypass the old whitelist and hardness.
        // Native canBreakBlocks still enforces breaker flag, target/idle config and mobGriefing.
        if(entity instanceof Mob&&(DefensePolicy.tier(state)!=null||DefenseMigration.isLegacy(state)))ci.setReturnValue(true);
    }
}
