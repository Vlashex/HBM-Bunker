package dev.deadearth.mixin;

import dev.deadearth.compat.EvolutionHooks;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ServerLevelAccessor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo @Mixin(targets="com.asestefan.mutationcraft.network.ModVariables",remap=false)
abstract class EvolutionClockMixin {
    @Inject(method="tickTime",at=@At("HEAD"),cancellable=true)
    private static void controlledClock(Level level,CallbackInfo ci){if(EvolutionHooks.controlled(level))ci.cancel();}
}

@Pseudo @Mixin(targets="com.asestefan.mutationcraft.behavior.Spawning",remap=false)
abstract class EvolutionSpawningMixin {
    @Shadow public static boolean always(ServerLevelAccessor level){throw new AssertionError();}
    @Shadow public static boolean enabled(double[] thresholds){throw new AssertionError();}
    @Inject(method="staged",at=@At("HEAD"),cancellable=true)
    private static void controlledStage(ServerLevelAccessor level,double[] thresholds,ResourceKey<Level> dimension,CallbackInfoReturnable<Boolean> ci){
        // Replace only native stage/time. Exact entity permission is applied to the real
        // SpawnPlacementCheck, after these original group and dimension restrictions.
        if(EvolutionHooks.controlled(level))ci.setReturnValue(always(level)&&enabled(thresholds)
                &&(dimension==null||level.getLevel().dimension()==dimension));
    }
}

@Pseudo @Mixin(targets="com.asestefan.mutationcraft.behavior.MutantConversion",remap=false)
abstract class EvolutionConversionMixin {
    @Inject(method="convert",at=@At("HEAD"),cancellable=true)
    private static void convert(Entity from,EntityType<? extends Mob> to,SoundEvent sound,CallbackInfoReturnable<Mob> ci){
        if(!EvolutionHooks.conversion(from,to,"infection"))ci.setReturnValue(null);
    }
    @Inject(method="evolve",at=@At("HEAD"),cancellable=true)
    private static void evolve(Mob from,EntityType<? extends Mob> to,SoundEvent sound,CallbackInfoReturnable<Mob> ci){
        // Mob.convertTo discards the source. Check before invoking it, not at join/finalize.
        if(!EvolutionHooks.conversion(from,to,"timed_evolution"))ci.setReturnValue(null);
    }
    @ModifyConstant(method="onKill",constant=@Constant(doubleValue=72000.0))
    private static double endermanTime(double nativeThreshold,LivingEntity killer,Entity victim,boolean weak){
        // This one additional native unlock used the retired global timer. Conversion
        // still checks the exact enderman phase and native toggles remain in onKill.
        return EvolutionHooks.controlled(killer.level())?-Double.MAX_VALUE:nativeThreshold;
    }
}

@Pseudo @Mixin(targets="com.asestefan.mutationcraft.ModUtil",remap=false)
abstract class EvolutionFinalizeMixin {
    @Inject(method="finalizeSpawn",at=@At("HEAD"),cancellable=true)
    private static void nativeSpawn(Mob mob,ServerLevelAccessor level,DifficultyInstance difficulty,MobSpawnType reason,CallbackInfo ci){
        if(!EvolutionHooks.entity(level,mob.getType())){EvolutionHooks.rejectNativeSpawn(mob);ci.cancel();}
    }
}

@Mixin(ServerLevel.class)
abstract class EvolutionNativeAddMixin {
    @Inject(method="addFreshEntity",at=@At("HEAD"),cancellable=true)
    private void add(Entity entity,CallbackInfoReturnable<Boolean> ci){if(EvolutionHooks.consumeRejectedNativeSpawn(entity))ci.setReturnValue(false);}
}

@Pseudo @Mixin(targets="com.asestefan.mutationcraft.entity.RottenSkeletonEntity",remap=false)
abstract class EvolutionAwakeningMixin {
    @Inject(method="rottenSkeletonAwaken",at=@At("HEAD"),cancellable=true)
    private static void awaken(LivingEntity source,CallbackInfo ci){
        // Native code kills the source even when create/add fails.
        if(!EvolutionHooks.progress(source,"the_intoxicator"))ci.cancel();
    }
}
