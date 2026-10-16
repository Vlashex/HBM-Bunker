package dev.deadearth.mixin;
import dev.deadearth.compat.SeasonHooks;
import net.minecraft.nbt.CompoundTag;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Pseudo @Mixin(targets="com.asestefan.mutationcraft.behavior.HumanStageEvolution",remap=false)
abstract class HumanEvolutionMixin {
    @Redirect(method="tick",at=@At(value="INVOKE",target="Lnet/minecraft/nbt/CompoundTag;getInt(Ljava/lang/String;)I"))
    private static int counter(CompoundTag tag,String key) {return SeasonHooks.counterRead(tag,key);}
}
@Pseudo @Mixin(targets="com.asestefan.mutationcraft.behavior.AssimilatedRoamerDevelop",remap=false)
abstract class RoamerEvolutionMixin {
    @Redirect(method="tick",at=@At(value="INVOKE",target="Lnet/minecraft/nbt/CompoundTag;getInt(Ljava/lang/String;)I"))
    private static int counter(CompoundTag tag,String key) {return SeasonHooks.counterRead(tag,key);}
}
@Pseudo @Mixin(targets="com.asestefan.mutationcraft.entity.MiterEntity",remap=false)
abstract class MiterEvolutionMixin {
    @Redirect(method="miterEvolution",at=@At(value="INVOKE",target="Lnet/minecraft/nbt/CompoundTag;getInt(Ljava/lang/String;)I"))
    private static int counter(CompoundTag tag,String key) {return SeasonHooks.counterRead(tag,key);}
}
@Pseudo @Mixin(targets="com.asestefan.mutationcraft.behavior.HookBehavior",remap=false)
abstract class HookEvolutionMixin {
    @Redirect(method="grow",at=@At(value="INVOKE",target="Lnet/minecraft/nbt/CompoundTag;getInt(Ljava/lang/String;)I"))
    private static int counter(CompoundTag tag,String key) {return SeasonHooks.counterRead(tag,key);}
}
@Pseudo @Mixin(targets="com.asestefan.mutationcraft.behavior.NecroptorBehavior",remap=false)
abstract class NecroptorEvolutionMixin {
    @Redirect(method="countZombieKill",at=@At(value="INVOKE",target="Lnet/minecraft/nbt/CompoundTag;getInt(Ljava/lang/String;)I"))
    private static int counter(CompoundTag tag,String key) {return SeasonHooks.counterRead(tag,key);}
}
@Pseudo @Mixin(targets="com.asestefan.mutationcraft.event.HumanoidEvents",remap=false)
abstract class HumanKillEvolutionMixin {
    @Redirect(method="assimilatedHumanEvolution",at=@At(value="INVOKE",target="Lnet/minecraft/nbt/CompoundTag;getInt(Ljava/lang/String;)I"))
    private static int counter(CompoundTag tag,String key) {return SeasonHooks.counterRead(tag,key);}
}
