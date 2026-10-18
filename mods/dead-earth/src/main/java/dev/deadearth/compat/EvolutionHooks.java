package dev.deadearth.compat;

import dev.deadearth.evolution.EvolutionState;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.ServerLevelAccessor;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.MobSpawnEvent;

/** Read-only permissions for pinned native entry points. Never rewrites Mutationcraft data. */
@EventBusSubscriber(modid="dead_earth")
public final class EvolutionHooks {
    private static final TagKey<EntityType<?>> MUTANTS=TagKey.create(Registries.ENTITY_TYPE,ResourceLocation.parse("mutationcraft:mutants"));
    private static final ResourceLocation SICKNESS=ResourceLocation.parse("mutationcraft:mutagen_sickness");
    // Only freshly constructed entities rejected by ModUtil.finalizeSpawn are marked. Loaded
    // entities and operator/egg spawns never enter this set. Weak keys also cover aborted adds.
    private static final Set<Entity> DENIED=Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));
    private static volatile Method nativeTeleport;
    private EvolutionHooks() {}

    private static LevelAccessor server(LevelAccessor level){return level instanceof ServerLevelAccessor accessor?accessor.getLevel():level;}
    public static boolean controlled(LevelAccessor level){return EvolutionState.controlled(server(level));}
    public static boolean ability(LevelAccessor level,String ability){return EvolutionState.allowsAbility(server(level),ability);}
    public static boolean entity(LevelAccessor level,EntityType<?> type){return EvolutionState.allowsEntity(server(level),type);}
    public static boolean entity(LevelAccessor level,String path){
        var type=BuiltInRegistries.ENTITY_TYPE.getOptional(ResourceLocation.fromNamespaceAndPath("mutationcraft",path));
        return type.isPresent()&&entity(level,type.get());
    }
    public static boolean conversion(Entity from,EntityType<?> to,String ability){
        return entity(from.level(),to)&&(!to.is(MUTANTS)||(ability(from.level(),ability)
                &&(!ability.equals("timed_evolution")||!controlled(from.level())||online(from.level()))));
    }
    public static boolean effect(LevelAccessor level,MobEffectInstance effect){
        return !SICKNESS.equals(BuiltInRegistries.MOB_EFFECT.getKey(effect.getEffect().value()))||ability(level,"infection");
    }
    /** Only progress counters pause without players; combat, drops and AI continue. */
    public static boolean progress(Entity from,EntityType<?> next){
        if(!controlled(from.level()))return true;
        return online(from.level())&&ability(from.level(),"timed_evolution")&&entity(from.level(),next);
    }
    private static boolean online(LevelAccessor level){return server(level) instanceof ServerLevel sl&&sl.getServer().getPlayerCount()>0;}
    public static boolean progress(Entity from,String next){
        var type=BuiltInRegistries.ENTITY_TYPE.getOptional(ResourceLocation.fromNamespaceAndPath("mutationcraft",next));
        return type.isPresent()&&progress(from,type.get());
    }
    public static void rejectNativeSpawn(Entity entity){DENIED.add(entity);}
    public static boolean consumeRejectedNativeSpawn(Entity entity){return DENIED.remove(entity);}

    @SubscribeEvent(priority=EventPriority.LOWEST)
    public static void spawnPlacement(MobSpawnEvent.SpawnPlacementCheck event){
        var reason=event.getSpawnType();
        if(reason==MobSpawnType.COMMAND||reason==MobSpawnType.SPAWN_EGG||reason==MobSpawnType.DISPENSER)return;
        if(!entity(event.getLevel(),event.getEntityType()))event.setResult(MobSpawnEvent.SpawnPlacementCheck.Result.FAIL);
    }

    /** Preserve native terrain/collision behavior for the two selected teleport abilities. */
    public static boolean teleport(Entity entity,double x,double y,double z){
        if(!ability(entity.level(),"teleport"))return false;
        try{
            var method=nativeTeleport;
            if(method==null){method=Class.forName("com.asestefan.mutationcraft.ModUtil").getMethod("teleportNear",Entity.class,double.class,double.class,double.class);nativeTeleport=method;}
            return (Boolean)method.invoke(null,entity,x,y,z);
        }catch(InvocationTargetException failed){throw new IllegalStateException("Native Mutationcraft teleport failed",failed.getCause());}
        catch(ReflectiveOperationException failed){throw new IllegalStateException("Unsupported native Mutationcraft teleport",failed);}
    }
}
