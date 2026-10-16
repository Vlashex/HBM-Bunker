package dev.deadearth.mixin;
import dev.deadearth.compat.SeasonHooks;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Redirect;

@Pseudo @Mixin(targets="com.asestefan.mutationcraft.behavior.HookBehavior",remap=false)
public abstract class HookSummonMixin {
    @Redirect(method="tick",at=@At(value="INVOKE",target="Lcom/asestefan/mutationcraft/behavior/HookBehavior;summon(Lnet/minecraft/server/level/ServerLevel;Lcom/asestefan/mutationcraft/entity/HookEntity;)V"))
    private static void attempts(ServerLevel level,@Coerce Object hook) {SeasonHooks.hookSummon(level,hook);}
}
