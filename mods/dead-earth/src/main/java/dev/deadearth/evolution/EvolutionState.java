package dev.deadearth.evolution;

import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.entity.EntityType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.core.registries.Registries;

/** Immutable published permissions; absence is fail-closed on the logical server. */
public final class EvolutionState {
    public record Published(EvolutionCatalog catalog,int phase) {}
    private static final ConcurrentHashMap<MinecraftServer,Published> STATES=new ConcurrentHashMap<>();
    private static final TagKey<EntityType<?>> MUTANTS=TagKey.create(Registries.ENTITY_TYPE,ResourceLocation.parse("mutationcraft:mutants"));
    private EvolutionState(){}
    public static Published published(MinecraftServer server){return STATES.get(server);}
    public static void publish(MinecraftServer server,EvolutionCatalog catalog,int phase){if(!server.isSameThread()||catalog==null||phase<0||phase>5)throw new IllegalArgumentException("Invalid phase publication");STATES.put(server,new Published(catalog,phase));}
    public static void restore(MinecraftServer server,Published previous){if(previous==null)clear(server);else publish(server,previous.catalog(),previous.phase());}
    public static void clear(MinecraftServer server){STATES.remove(server);}
    private static Published get(LevelAccessor level){return level instanceof ServerLevel sl?STATES.get(sl.getServer()):null;}
    public static boolean controlled(LevelAccessor level){var p=get(level);return level instanceof ServerLevel&&(p==null||p.catalog().enabled());}
    public static int phase(LevelAccessor level){var p=get(level);return p==null?0:p.phase();}
    public static boolean allowsEntity(LevelAccessor level,EntityType<?> type){if(!controlled(level)||!type.is(MUTANTS))return true;var p=get(level);return p!=null&&p.catalog().allowsEntity(p.phase(),BuiltInRegistries.ENTITY_TYPE.getKey(type).toString());}
    public static boolean allowsAbility(LevelAccessor level,String ability){if(!controlled(level))return true;var p=get(level);return p!=null&&p.catalog().abilities(p.phase()).contains(ability);}
}
