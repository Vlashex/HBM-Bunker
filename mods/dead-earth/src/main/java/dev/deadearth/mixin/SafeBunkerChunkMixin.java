package dev.deadearth.mixin;

import dev.deadearth.starter.SafeBunker;
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
public abstract class SafeBunkerChunkMixin {
    @Shadow @Final private Level level;
    @Inject(method="setBlockState",at=@At("HEAD"),cancellable=true)
    private void deadEarthChunkWrite(BlockPos pos,BlockState next,boolean moving,CallbackInfoReturnable<BlockState> result) {
        if(level.isClientSide())return;
        var chunk=(LevelChunk)(Object)this;
        if(SafeBunker.intersectsSection(level,pos.getX()>>4,pos.getZ()>>4,level.getSectionIndex(pos.getY()))
                &&SafeBunker.blocksReplacement(level,pos,chunk.getBlockState(pos),next)){result.setReturnValue(null);return;}
        SafeBunker.captureWrite(level,pos,next);
    }
}
