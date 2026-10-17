package dev.deadearth.mixin;
import dev.deadearth.defense.DefenseConfig;
import dev.deadearth.defense.DefensePolicy;
import java.util.HashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** HBM VNT bypasses NeoForge Detonate; intercept after its real allocator and before any processor drops. */
@Pseudo @Mixin(targets="com.hbm.explosion.vanillant.ExplosionVNT",remap=false)
public abstract class DefenseHbmVntMixin {
    @Shadow public Level world;
    @Shadow public float size;
    @Shadow public HashSet<BlockPos> affectedBlocks;
    @Inject(method="explode",at=@At(value="INVOKE",target="Lcom/hbm/explosion/vanillant/interfaces/IBlockProcessor;process(Lcom/hbm/explosion/vanillant/ExplosionVNT;Lnet/minecraft/world/level/Level;DDDLjava/util/HashSet;)V"))
    private void defenseVntBlast(CallbackInfo ci) {
        if(world instanceof ServerLevel level)DefensePolicy.filterExplosion(level,affectedBlocks,size,DefenseConfig.number(DefenseConfig.HBM_VNT_DAMAGE));
    }
}
