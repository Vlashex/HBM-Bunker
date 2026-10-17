package dev.deadearth.mixin;
import dev.deadearth.defense.DefensePolicy;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Pseudo @Mixin(targets="com.yayuy.overgrownvegetation.entity.SiegeBombEntity",remap=false)
public abstract class DefenseSiegeMixin {
    @Redirect(method="carveMinimalCrater",at=@At(value="INVOKE",target="Lnet/minecraft/server/level/ServerLevel;destroyBlock(Lnet/minecraft/core/BlockPos;Z)Z"))
    private static boolean defenseCrater(ServerLevel level,BlockPos pos,boolean drops) {
        if(DefensePolicy.attack(level,pos,DefensePolicy.Attack.SIEGE_CRATER))return false;
        return level.destroyBlock(pos,drops);
    }
}
