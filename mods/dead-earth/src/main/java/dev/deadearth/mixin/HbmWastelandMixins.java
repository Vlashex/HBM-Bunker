package dev.deadearth.mixin;

import dev.deadearth.worldgen.WastelandGround;
import net.minecraft.tags.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo @Mixin(targets="com.hbm.world.LocationIsValidSpawn",remap=false)
abstract class HbmGroundMixin {
    @Inject(method="matches",at=@At("HEAD"),cancellable=true)
    private static void deadEarthGround(BlockState state,boolean terracotta,CallbackInfoReturnable<Boolean> result) {
        if(WastelandGround.accepts(state))result.setReturnValue(true);
    }
}
@Pseudo @Mixin(targets="com.hbm.world.feature.GeyserFeature",remap=false)
abstract class HbmGeyserGroundMixin {
    @Redirect(method="place",at=@At(value="INVOKE",target="Lnet/minecraft/world/level/block/state/BlockState;is(Lnet/minecraft/world/level/block/Block;)Z"))
    private boolean deadEarthGround(BlockState state,Block expected) {
        return state.is(expected)||(expected==Blocks.GRASS_BLOCK&&WastelandGround.accepts(state));
    }
}
@Pseudo @Mixin(targets="com.hbm.world.feature.ArcticVaultFeature",remap=false)
abstract class HbmVaultGroundMixin {
    @Redirect(method="place",at=@At(value="INVOKE",target="Lnet/minecraft/world/level/block/state/BlockState;is(Lnet/minecraft/tags/TagKey;)Z",ordinal=0))
    private boolean deadEarthGround(BlockState state,TagKey<Block> expected) {
        return state.is(expected)||(expected.equals(BlockTags.BASE_STONE_OVERWORLD)&&WastelandGround.accepts(state));
    }
}
