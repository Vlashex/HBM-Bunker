package dev.deadearth.mixin;

import dev.deadearth.compat.EvolutionHooks;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LivingEntity.class)
abstract class EvolutionSicknessMixin {
    @Inject(method="addEffect(Lnet/minecraft/world/effect/MobEffectInstance;Lnet/minecraft/world/entity/Entity;)Z",at=@At("HEAD"),cancellable=true)
    private void sickness(MobEffectInstance effect,Entity cause,CallbackInfoReturnable<Boolean> ci){
        if(!EvolutionHooks.effect(((LivingEntity)(Object)this).level(),effect))ci.setReturnValue(false);
    }
}

@Pseudo @Mixin(targets="com.asestefan.mutationcraft.behavior.AberrationSpawns",remap=false)
abstract class EvolutionAberrationMixin {
    @Inject(method={"spawnAt","spawnNear"},at=@At("HEAD"),cancellable=true)
    private static void spawn(ServerLevel level,EntityType<? extends Mob> type,double x,double y,double z,CallbackInfoReturnable<Mob> ci){
        if(!EvolutionHooks.entity(level,type))ci.setReturnValue(null);
    }
    @Inject(method="deathSpawns",at=@At("HEAD"),cancellable=true)
    private static void death(LivingEntity mob,CallbackInfo ci){if(!EvolutionHooks.ability(mob.level(),"death_parasites"))ci.cancel();}
}

@Pseudo @Mixin(targets={"com.asestefan.mutationcraft.behavior.AnimalDeath","com.asestefan.mutationcraft.behavior.HumanoidDeath"},remap=false)
abstract class EvolutionDeathParasitesMixin {
    @Inject(method="spawn",at=@At("HEAD"),cancellable=true)
    private static void spawn(ServerLevel level,EntityType<? extends Mob> type,double x,double y,double z,CallbackInfo ci){
        if(!EvolutionHooks.ability(level,"death_parasites")||!EvolutionHooks.entity(level,type))ci.cancel();
    }
}
@Pseudo @Mixin(targets="com.asestefan.mutationcraft.event.IllagerEvents",remap=false)
abstract class EvolutionIllagerDeathMixin {
    @Inject(method="spawn",at=@At("HEAD"),cancellable=true)
    private static void spawn(ServerLevel level,EntityType<? extends Mob> type,LivingEntity from,CallbackInfo ci){
        if(!EvolutionHooks.ability(level,"death_parasites")||!EvolutionHooks.entity(level,type))ci.cancel();
    }
}
@Pseudo @Mixin(targets="com.asestefan.mutationcraft.event.BossEvents",remap=false)
abstract class EvolutionBossDeathMixin {
    @Inject(method="summon",at=@At("HEAD"),cancellable=true)
    private static void spawn(ServerLevel level,EntityType<? extends Mob> type,LivingEntity from,CallbackInfo ci){
        if(!EvolutionHooks.ability(level,"death_parasites")||!EvolutionHooks.entity(level,type))ci.cancel();
    }
}

@Pseudo @Mixin(targets="com.asestefan.mutationcraft.behavior.HookBehavior",remap=false)
abstract class EvolutionHookAbilityMixin {
    @Inject(method="summon",at=@At("HEAD"),cancellable=true)
    private static void summon(ServerLevel level,@Coerce Object hook,CallbackInfo ci){if(!EvolutionHooks.ability(level,"hooks"))ci.cancel();}
    @Inject(method="summonOne",at=@At("HEAD"),cancellable=true)
    private static void summonOne(ServerLevel level,@Coerce Object hook,EntityType<? extends Mob> type,CallbackInfo ci){
        if(!EvolutionHooks.ability(level,"hooks")||!EvolutionHooks.entity(level,type))ci.cancel();
    }
    @Redirect(method="onSpawn",at=@At(value="INVOKE",target="Lnet/minecraft/world/level/ServerLevelAccessor;setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)Z"))
    private static boolean initialPutrid(ServerLevelAccessor level,BlockPos pos,BlockState state,int flags){
        return EvolutionHooks.ability(level,"putrid")&&level.setBlock(pos,state,flags);
    }
}
@Pseudo @Mixin(targets="com.asestefan.mutationcraft.block.PutridBlock",remap=false)
abstract class EvolutionPutridMixin {
    @Inject(method="spread",at=@At("HEAD"),cancellable=true)
    private static void spread(ServerLevel level,BlockPos pos,RandomSource random,CallbackInfo ci){if(!EvolutionHooks.ability(level,"putrid"))ci.cancel();}
    @Inject(method="grow",at=@At("HEAD"),cancellable=true)
    private static void grow(ServerLevel level,BlockPos target,BlockPos vine,RandomSource random,CallbackInfo ci){if(!EvolutionHooks.ability(level,"putrid"))ci.cancel();}
}
@Pseudo @Mixin(targets="com.asestefan.mutationcraft.block.PutridVineBlock",remap=false)
abstract class EvolutionPutridVineMixin {
    @Inject(method="randomTick",at=@At("HEAD"),cancellable=true)
    private void grow(BlockState state,ServerLevel level,BlockPos pos,RandomSource random,CallbackInfo ci){if(!EvolutionHooks.ability(level,"putrid"))ci.cancel();}
}

@Pseudo @Mixin(targets="com.asestefan.mutationcraft.behavior.AssimilatedEndermanBehavior",remap=false)
abstract class EvolutionEndermanWarpMixin {
    @Inject(method="warp",at=@At("HEAD"),cancellable=true)
    private static void warp(Entity entity,double x,double y,double z,CallbackInfoReturnable<Boolean> ci){if(!EvolutionHooks.ability(entity.level(),"teleport"))ci.setReturnValue(false);}
}
@Pseudo @Mixin(targets="com.asestefan.mutationcraft.behavior.AssimilatedEvokerBehavior",remap=false)
abstract class EvolutionEvokerWarpMixin {
    @Redirect(method="lambda$retaliate$3",at=@At(value="INVOKE",target="Lcom/asestefan/mutationcraft/ModUtil;teleportNear(Lnet/minecraft/world/entity/Entity;DDD)Z"))
    private static boolean warp(Entity entity,double x,double y,double z){return EvolutionHooks.teleport(entity,x,y,z);}
}
@Pseudo @Mixin(targets="com.asestefan.mutationcraft.entity.FlayerEntity",remap=false)
abstract class EvolutionFlayerWarpMixin {
    // Do not cancel resurface itself: the existing mutant must leave its burrow normally.
    @Redirect(method="resurface",at=@At(value="INVOKE",target="Lcom/asestefan/mutationcraft/ModUtil;teleportNear(Lnet/minecraft/world/entity/Entity;DDD)Z"))
    private boolean warp(Entity entity,double x,double y,double z){return EvolutionHooks.teleport(entity,x,y,z);}
}
@Pseudo @Mixin(targets="com.asestefan.mutationcraft.entity.AssimilatedCreeperEntity",remap=false)
abstract class EvolutionCreeperExplosionMixin {
    @Inject(method="assimilatedCreeperFuse",at=@At("HEAD"),cancellable=true)
    private static void fuse(@Coerce Object creeper,Player player,CallbackInfo ci){if(!EvolutionHooks.ability(((Entity)creeper).level(),"explosions"))ci.cancel();}
    // A phase may have changed after fuse scheduling. Check at the actual callback too.
    @Inject(method="lambda$assimilatedCreeperFuse$0",at=@At("HEAD"),cancellable=true)
    private static void explode(@Coerce Object creeper,Level level,CallbackInfo ci){if(!EvolutionHooks.ability(level,"explosions"))ci.cancel();}
}
@Pseudo @Mixin(targets="com.asestefan.mutationcraft.entity.BloodSpikeEntity",remap=false)
abstract class EvolutionBloodExplosionMixin {
    @Redirect(method="touch",at=@At(value="INVOKE",target="Lnet/minecraft/world/level/Level;explode(Lnet/minecraft/world/entity/Entity;DDDFLnet/minecraft/world/level/Level$ExplosionInteraction;)Lnet/minecraft/world/level/Explosion;"))
    private Explosion explode(Level level,Entity entity,double x,double y,double z,float power,Level.ExplosionInteraction interaction){
        return EvolutionHooks.ability(level,"explosions")?level.explode(entity,x,y,z,power,interaction):null;
    }
}
