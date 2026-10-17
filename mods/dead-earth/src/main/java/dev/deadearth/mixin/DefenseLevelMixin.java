package dev.deadearth.mixin;
import dev.deadearth.defense.DefensePolicy;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Level.class)
public abstract class DefenseLevelMixin {
    @Inject(method="setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z",at=@At("HEAD"),cancellable=true)
    private void defenseWrite(BlockPos pos,BlockState next,int flags,int recursion,CallbackInfoReturnable<Boolean> result) {
        var level=(Level)(Object)this;
        if(!level.isClientSide()&&level.hasChunkAt(pos)&&DefensePolicy.blocksReplacement(level,pos,level.getBlockState(pos),next))result.setReturnValue(false);
    }
    @Inject(method="destroyBlock(Lnet/minecraft/core/BlockPos;ZLnet/minecraft/world/entity/Entity;I)Z",at=@At("HEAD"),cancellable=true)
    private void defenseDestroy(BlockPos pos,boolean drops,Entity actor,int recursion,CallbackInfoReturnable<Boolean> result) {
        if(DefensePolicy.interceptDestroy((Level)(Object)this,pos,actor))result.setReturnValue(false);
    }
}
