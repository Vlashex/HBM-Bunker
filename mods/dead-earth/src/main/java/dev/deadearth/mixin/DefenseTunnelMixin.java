package dev.deadearth.mixin;
import dev.deadearth.defense.DefensePolicy;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo @Mixin(targets="com.yayuy.overgrownvegetation.entity.ArboraTunnelGoal",remap=false)
public abstract class DefenseTunnelMixin {
    @Inject(method="breakAndCorrupt",at=@At("HEAD"),cancellable=true)
    private static void defenseTunnel(ServerLevel level,BlockPos pos,CallbackInfo ci) {
        if(DefensePolicy.attack(level,pos,DefensePolicy.Attack.ARBORA_TUNNEL))ci.cancel();
    }
}
