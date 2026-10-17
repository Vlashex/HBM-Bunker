package dev.deadearth.mixin;
import dev.deadearth.defense.DefensePolicy;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LevelChunk.class)
public abstract class DefenseChunkMixin {
    @Shadow @Final private Level level;
    @Inject(method="setBlockState",at=@At("HEAD"),cancellable=true)
    private void defenseRawWrite(BlockPos pos,BlockState next,boolean moving,CallbackInfoReturnable<BlockState> result) {
        if(!level.isClientSide()&&DefensePolicy.blocksReplacement(level,pos,((LevelChunk)(Object)this).getBlockState(pos),next))result.setReturnValue(null);
    }
    @Inject(method="setBlockState",at=@At("RETURN"))
    private void defenseSuccessfulWrite(BlockPos pos,BlockState next,boolean moving,CallbackInfoReturnable<BlockState> result) {
        DefensePolicy.replaced(level,pos,result.getReturnValue(),next);
    }
}
