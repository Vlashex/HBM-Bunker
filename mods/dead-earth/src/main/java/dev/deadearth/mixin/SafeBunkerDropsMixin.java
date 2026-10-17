package dev.deadearth.mixin;

import dev.deadearth.starter.SafeBunker;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Block.class)
public abstract class SafeBunkerDropsMixin {
    @Inject(method="dropResources(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;)V",at=@At("HEAD"),cancellable=true)
    private static void deadEarthSimpleDrops(BlockState state,Level level,BlockPos pos,CallbackInfo result) {
        if(SafeBunker.protectsRemoval(level,pos))result.cancel();
    }
    @Inject(method="dropResources(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/LevelAccessor;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/entity/BlockEntity;)V",at=@At("HEAD"),cancellable=true)
    private static void deadEarthContainerDrops(BlockState state,LevelAccessor accessor,BlockPos pos,BlockEntity block,CallbackInfo result) {
        if(accessor instanceof Level level&&SafeBunker.protectsRemoval(level,pos))result.cancel();
    }
    @Inject(method="dropResources(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/entity/BlockEntity;Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/item/ItemStack;)V",at=@At("HEAD"),cancellable=true)
    private static void deadEarthToolDrops(BlockState state,Level level,BlockPos pos,BlockEntity block,Entity actor,ItemStack tool,CallbackInfo result) {
        if(SafeBunker.protectsRemoval(level,pos))result.cancel();
    }
}
