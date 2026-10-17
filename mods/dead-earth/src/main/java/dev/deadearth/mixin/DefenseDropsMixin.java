package dev.deadearth.mixin;
import dev.deadearth.defense.DefensePolicy;
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
public abstract class DefenseDropsMixin {
    @Inject(method="dropResources(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;)V",at=@At("HEAD"),cancellable=true)
    private static void defenseDrops(BlockState state,Level level,BlockPos pos,CallbackInfo ci){if(DefensePolicy.suppressDrops(state,level,pos))ci.cancel();}
    @Inject(method="dropResources(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/LevelAccessor;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/entity/BlockEntity;)V",at=@At("HEAD"),cancellable=true)
    private static void defenseContainerDrops(BlockState state,LevelAccessor level,BlockPos pos,BlockEntity be,CallbackInfo ci){if(DefensePolicy.suppressDrops(state,level,pos))ci.cancel();}
    @Inject(method="dropResources(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/entity/BlockEntity;Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/item/ItemStack;)V",at=@At("HEAD"),cancellable=true)
    private static void defenseToolDrops(BlockState state,Level level,BlockPos pos,BlockEntity be,Entity entity,ItemStack tool,CallbackInfo ci){if(DefensePolicy.suppressDrops(state,level,pos))ci.cancel();}
}
