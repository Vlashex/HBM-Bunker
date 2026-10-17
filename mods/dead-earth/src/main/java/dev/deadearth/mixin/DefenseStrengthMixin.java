package dev.deadearth.mixin;
import dev.deadearth.defense.DefenseConfig;
import dev.deadearth.defense.DefenseMaterial;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(BlockBehaviour.BlockStateBase.class)
public abstract class DefenseStrengthMixin {
    @Shadow public abstract Block getBlock();
    @Inject(method="getDestroySpeed",at=@At("HEAD"),cancellable=true)
    private void defenseConfiguredHardness(BlockGetter world,BlockPos pos,CallbackInfoReturnable<Float> ci) {
        if(getBlock() instanceof DefenseMaterial material)ci.setReturnValue(DefenseConfig.hardness(material.defenseTier()));
    }
}
