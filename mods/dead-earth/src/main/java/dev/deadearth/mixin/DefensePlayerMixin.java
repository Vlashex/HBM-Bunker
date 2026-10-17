package dev.deadearth.mixin;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.deadearth.defense.DefensePolicy;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayerGameMode;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(ServerPlayerGameMode.class)
public abstract class DefensePlayerMixin {
    @Shadow protected ServerLevel level;
    @WrapMethod(method="destroyBlock")
    private boolean defenseDemolition(BlockPos pos,Operation<Boolean> nativeCall) {
        return DefensePolicy.scoped(DefensePolicy.Scope.PLAYER,level,pos,level.getBlockState(pos),()->nativeCall.call(pos));
    }
}
