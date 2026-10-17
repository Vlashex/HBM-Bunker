package dev.deadearth.mixin;
import dev.deadearth.defense.DefenseConfig;
import dev.deadearth.defense.DefenseMigration;
import dev.deadearth.defense.DefensePolicy;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo @Mixin(targets="io.github.flemmli97.improvedmobs.common.entities.ai.BlockBreakGoal",remap=false)
public abstract class DefenseImprovedMobsMixin {
    @Shadow @Final protected Mob living;
    @Shadow private BlockPos diggingPosition;
    @Shadow private int digTimer;
    @Inject(method="tick",at=@At("HEAD"),cancellable=true)
    private void defenseBreaker(CallbackInfo ci) {
        if(diggingPosition==null||!(living.level() instanceof ServerLevel level))return;
        var state=level.getBlockState(diggingPosition);
        if(DefenseMigration.isLegacy(state)) {
            DefenseMigration.request(level,diggingPosition);digTimer=0;ci.cancel();return;
        }
        if(DefensePolicy.tier(state)==null)return;
        // Before BlockRestorationData.restore and native drops, including restoreDelay > 0.
        if(++digTimer>=DefenseConfig.integer(DefenseConfig.BREAKER_TICKS)) {
            digTimer=0;DefensePolicy.attack(level,diggingPosition,DefensePolicy.Attack.IMPROVED_MOBS);
            if(DefensePolicy.tier(level.getBlockState(diggingPosition))==null){level.destroyBlockProgress(living.getId(),diggingPosition,-1);diggingPosition=null;}
            else living.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
        }
        ci.cancel();
    }
}
