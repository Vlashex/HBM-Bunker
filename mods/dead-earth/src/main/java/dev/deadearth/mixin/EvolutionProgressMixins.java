package dev.deadearth.mixin;

import dev.deadearth.compat.EvolutionHooks;
import java.util.function.Supplier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo @Mixin(targets="com.asestefan.mutationcraft.behavior.HumanStageEvolution",remap=false)
abstract class EvolutionHumanTimerMixin {
    @Inject(method="tick",at=@At("HEAD"),cancellable=true)
    private static void tick(Mob mob,String key,EntityType<? extends Mob> next,CallbackInfo ci){if(!EvolutionHooks.progress(mob,next))ci.cancel();}
}
@Pseudo @Mixin(targets="com.asestefan.mutationcraft.behavior.AssimilatedRoamerDevelop",remap=false)
abstract class EvolutionRoamerTimerMixin {
    @Inject(method="tick",at=@At("HEAD"),cancellable=true)
    private static void tick(Mob mob,CallbackInfo ci){if(!EvolutionHooks.progress(mob,"developed_roamer"))ci.cancel();}
    @Inject(method="fromKill",at=@At("HEAD"),cancellable=true)
    private static void kill(Mob mob,Entity victim,CallbackInfo ci){if(!EvolutionHooks.progress(mob,"developed_roamer"))ci.cancel();}
}
@Pseudo @Mixin(targets="com.asestefan.mutationcraft.entity.MiterEntity",remap=false)
abstract class EvolutionMiterTimerMixin {
    @Inject(method="miterEvolution",at=@At("HEAD"),cancellable=true)
    private static void tick(@Coerce Object mob,CallbackInfo ci){if(!EvolutionHooks.progress((Entity)mob,"necroptor"))ci.cancel();}
}
@Pseudo @Mixin(targets="com.asestefan.mutationcraft.behavior.HookBehavior",remap=false)
abstract class EvolutionHookGrowthMixin {
    @Inject(method="grow",at=@At("HEAD"),cancellable=true)
    private static void tick(ServerLevel level,@Coerce Object hook,Supplier<? extends EntityType<? extends Mob>> next,CallbackInfoReturnable<Boolean> ci){
        if(!EvolutionHooks.ability(level,"hooks")||!EvolutionHooks.progress((Entity)hook,next.get()))ci.setReturnValue(false);
    }
}
@Pseudo @Mixin(targets="com.asestefan.mutationcraft.behavior.NecroptorBehavior",remap=false)
abstract class EvolutionNecroptorKillsMixin {
    @Inject(method="countZombieKill",at=@At("HEAD"),cancellable=true)
    private static void kill(ServerLevel level,@Coerce Object mob,CallbackInfo ci){if(!EvolutionHooks.progress((Entity)mob,"reductor"))ci.cancel();}
    @Inject(method="spawnHook",at=@At("HEAD"),cancellable=true)
    private static void hook(ServerLevel level,LivingEntity mob,CallbackInfo ci){if(!EvolutionHooks.ability(level,"death_parasites")||!EvolutionHooks.ability(level,"hooks"))ci.cancel();}
}
@Pseudo @Mixin(targets="com.asestefan.mutationcraft.event.HumanoidEvents",remap=false)
abstract class EvolutionHumanKillsMixin {
    @Inject(method="assimilatedHumanEvolution",at=@At("HEAD"),cancellable=true)
    private static void kill(LivingEntity victim,DamageSource damage,CallbackInfo ci){
        var killer=damage.getEntity();if(killer!=null&&!EvolutionHooks.progress(killer,"flayer"))ci.cancel();
    }
}
