package dev.deadearth.mixin;
import dev.deadearth.defense.DefensePolicy;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo @Mixin(targets="com.yayuy.overgrownvegetation.corruption.CorruptionRegistry",remap=false)
public abstract class DefenseCorruptionMixin {
    @Inject(method="canCorrupt(Lnet/minecraft/world/level/LevelReader;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Z)Z",at=@At("HEAD"),cancellable=true)
    private static void defenseCorruptible(LevelReader level,BlockPos pos,BlockState state,boolean flag,CallbackInfoReturnable<Boolean> ci) {
        if(DefensePolicy.tier(state)==dev.deadearth.defense.DefenseTier.V3)ci.setReturnValue(false);
    }
    @Inject(method="placeCorrupted",at=@At("HEAD"),cancellable=true)
    private static void defenseCorruption(LevelAccessor level,BlockPos pos,BlockState before,BlockState after,int flags,CallbackInfo ci) {
        if(DefensePolicy.tier(before)==dev.deadearth.defense.DefenseTier.V3&&(!(level instanceof net.minecraft.world.level.Level world)||!DefensePolicy.allowed(world,pos)))ci.cancel();
    }
}
