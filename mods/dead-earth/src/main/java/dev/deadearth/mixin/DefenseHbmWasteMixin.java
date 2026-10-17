package dev.deadearth.mixin;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.deadearth.defense.DefensePolicy;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;

@Pseudo @Mixin(targets="com.hbm.explosion.ExplosionNukeGeneric",remap=false)
public abstract class DefenseHbmWasteMixin {
    @WrapMethod(method={"wasteDest","wasteDestNoSchrab"})
    private static void defenseNuclearWaste(Level level,BlockPos pos,RandomSource random,Operation<Void> nativeCall) {
        DefensePolicy.scoped(DefensePolicy.Scope.NUCLEAR,()->nativeCall.call(level,pos,random));
    }
}
