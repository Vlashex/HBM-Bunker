package dev.deadearth.defense;

import dev.deadearth.DeadEarth;
import dev.deadearth.starter.SafeBunker;
import java.nio.file.Files;
import java.util.HashSet;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.ExplosionEvent;
import net.neoforged.neoforge.event.level.PistonEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

/** All HP mutation is on the server thread. The unscoped V3 write guard reads no SavedData. */
public final class DefensePolicy {
    public enum Scope { PLAYER,ADMIN,MIGRATION,HP_BREAK,NUCLEAR }
    public enum Attack { IMPROVED_MOBS,ARBORA_TUNNEL,SIEGE_CRATER,LIVING_DESTROY }
    private record Context(Scope reason,Level level,long anchor,long upper,boolean paired) {
        boolean permits(Level world,BlockPos pos){return reason==Scope.ADMIN||reason==Scope.NUCLEAR||world==level&&(pos.asLong()==anchor||paired&&pos.asLong()==upper);}
    }
    private static final ThreadLocal<Context> SCOPE=new ThreadLocal<>();
    private static final Map<ServerLevel,DefenseDamageStore> STORES=new WeakHashMap<>();
    private static final java.util.Set<ServerLevel> FAILED=java.util.Collections.newSetFromMap(new WeakHashMap<>());
    private static boolean installed;
    private DefensePolicy(){}
    public static void install() {
        if(installed)return;installed=true;
        NeoForge.EVENT_BUS.addListener(EventPriority.LOW,DefensePolicy::explosion);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOW,DefensePolicy::piston);
        NeoForge.EVENT_BUS.addListener(DefenseMigration::chunkLoaded);
        NeoForge.EVENT_BUS.addListener(DefenseMigration::tick);
        NeoForge.EVENT_BUS.addListener(DefensePolicy::stopped);
    }
    public static <T>T scoped(Scope scope,Supplier<T> action) {
        if(scope!=Scope.ADMIN&&scope!=Scope.NUCLEAR)throw new IllegalArgumentException("Local defense scope requires world and anchor");
        return contextual(new Context(scope,null,0,0,false),action);
    }
    public static void scoped(Scope scope,Runnable action){scoped(scope,()->{action.run();return null;});}
    public static <T>T scoped(Scope scope,Level level,BlockPos pos,BlockState state,Supplier<T> action) {
        var anchor=anchor(pos,state);return contextual(new Context(scope,level,anchor.asLong(),anchor.above().asLong(),state.getBlock() instanceof DoorBlock),action);
    }
    public static void scoped(Scope scope,Level level,BlockPos pos,BlockState state,Runnable action){scoped(scope,level,pos,state,()->{action.run();return null;});}
    private static <T>T contextual(Context context,Supplier<T> action) {
        var previous=SCOPE.get();SCOPE.set(context);
        try{return action.get();}finally{if(previous==null)SCOPE.remove();else SCOPE.set(previous);}
    }
    public static boolean allowed(Level level,BlockPos pos){var context=SCOPE.get();return context!=null&&context.permits(level,pos);}
    public static Scope currentScope(){var context=SCOPE.get();return context==null?null:context.reason;}
    public static DefenseTier tier(BlockState state){return state.getBlock() instanceof DefenseMaterial material?material.defenseTier():null;}
    public static BlockPos anchor(BlockPos pos,BlockState state) {
        return state.getBlock() instanceof DoorBlock&&state.getValue(DoorBlock.HALF)==DoubleBlockHalf.UPPER?pos.below():pos.immutable();
    }
    public static boolean immune(LevelReader level,BlockPos pos) {
        return level.hasChunkAt(pos)&&tier(level.getBlockState(pos))==DefenseTier.V3;
    }
    public static boolean blocksReplacement(Level level,BlockPos pos,BlockState before,BlockState after) {
        // This is the common hot path: no stack inspection, save access, allocation or chunk loading.
        if(!(before.getBlock() instanceof DefenseMaterial material)||material.defenseTier()!=DefenseTier.V3
                ||before.getBlock()==after.getBlock()||level.isClientSide())return false;
        return !allowed(level,pos);
    }
    public static boolean suppressDrops(BlockState before,net.minecraft.world.level.LevelAccessor world,BlockPos pos) {
        return before.getBlock() instanceof DefenseMaterial&&(!(world instanceof Level level)||!allowed(level,pos));
    }
    public static DefenseDamageStore store(ServerLevel level) {
        if(!level.getServer().isSameThread())throw new IllegalStateException("Defense save outside server thread");
        if(FAILED.contains(level))throw new IllegalStateException("Defense data unavailable; original file preserved");
        var existing=STORES.get(level);if(existing!=null)return existing;
        try {
            var factory=new SavedData.Factory<>(DefenseDamageStore::create,DefenseDamageStore::load);
            var data=level.getDataStorage().get(factory,DefenseDamageStore.NAME);
            if(data==null) {
                var folder=DimensionType.getStorageFolder(level.dimension(),level.getServer().getWorldPath(LevelResource.ROOT)).resolve("data");
                if(Files.exists(folder.resolve(DefenseDamageStore.NAME+".dat")))throw new IllegalStateException("Unreadable defense file");
                data=DefenseDamageStore.create();level.getDataStorage().set(DefenseDamageStore.NAME,data);
            }
            if(!data.enabled())throw new IllegalStateException("Unsupported defense schema");
            STORES.put(level,data);return data;
        }catch(RuntimeException error){FAILED.add(level);DeadEarth.LOGGER.error("Defense HP unavailable; existing data preserved",error);throw error;}
    }
    /** Returns true if the attempted native attack belongs to this system, including an absorbed hit. */
    public static boolean attack(ServerLevel level,BlockPos pos,Attack attack) {
        int amount=DefenseConfig.integer(switch(attack){case IMPROVED_MOBS->DefenseConfig.BREAKER_DAMAGE;case ARBORA_TUNNEL->DefenseConfig.TUNNEL_DAMAGE;case SIEGE_CRATER->DefenseConfig.SIEGE_DAMAGE;case LIVING_DESTROY->DefenseConfig.MOB_DAMAGE;});
        return damage(level,pos,amount);
    }
    public static boolean damage(ServerLevel level,BlockPos pos,int amount) {
        if(!level.hasChunkAt(pos))return false;
        var state=level.getBlockState(pos);var tier=tier(state);if(tier==null)return false;
        if(!level.getServer().isSameThread())return true;
        var anchor=anchor(pos,state);
        // The three-hour campaign protection takes precedence; no invisible HP loss under it.
        if(SafeBunker.protectsRemoval(level,pos)||SafeBunker.protectsRemoval(level,anchor))return true;
        try {
            var data=store(level);var id=BuiltInRegistries.BLOCK.getKey(state.getBlock());
            if(data.damage(anchor.asLong(),id,tier,DefenseConfig.hp(tier),amount)>0)return true;
            scoped(Scope.HP_BREAK,level,pos,state,()->level.destroyBlock(anchor,false));
            if(level.getBlockState(anchor).getBlock()!=state.getBlock())data.removed(anchor.asLong(),id);
        }catch(RuntimeException invalid){if(FAILED.add(level))DeadEarth.LOGGER.error("Defense attack stopped; material and saved HP preserved",invalid);}
        return true;
    }
    public static int remaining(ServerLevel level,BlockPos pos) {
        var state=level.getBlockState(pos);var tier=tier(state);if(tier==null)return 0;
        return store(level).remaining(anchor(pos,state).asLong(),BuiltInRegistries.BLOCK.getKey(state.getBlock()),tier,DefenseConfig.hp(tier));
    }
    public static boolean interceptDestroy(Level level,BlockPos pos,Entity actor) {
        if(level.isClientSide()||allowed(level,pos)||!level.hasChunkAt(pos))return false;
        var state=level.getBlockState(pos);if(!(state.getBlock() instanceof DefenseMaterial))return false;
        if(actor instanceof Player)return false;
        if(actor instanceof LivingEntity&&level instanceof ServerLevel server)return attack(server,pos,Attack.LIVING_DESTROY);
        return tier(state)==DefenseTier.V3;
    }
    /** Called only after a successful native chunk write; opening a door does not erase HP. */
    public static void replaced(Level level,BlockPos pos,BlockState before,BlockState next) {
        if(before==null||before.getBlock()==next.getBlock()||!(level instanceof ServerLevel server)||!server.getServer().isSameThread())return;
        boolean oldMaterial=before.getBlock() instanceof DefenseMaterial,newMaterial=next.getBlock() instanceof DefenseMaterial;
        if(!oldMaterial&&!newMaterial)return;
        try {
            var data=store(server);
            if(oldMaterial)data.removed(anchor(pos,before).asLong(),BuiltInRegistries.BLOCK.getKey(before.getBlock()));
            if(newMaterial)data.placed(anchor(pos,next).asLong());
        }
        catch(RuntimeException invalid){/* The guard already logged and preserved the file. */}
    }
    private static void explosion(ExplosionEvent.Detonate event) {
        if(!(event.getLevel() instanceof ServerLevel level))return;
        // HBM nuclear writers have their own native scopes; ordinary explosions consume HP.
        if(currentScope()==Scope.NUCLEAR)return;
        filterExplosion(level,event.getAffectedBlocks(),event.getExplosion().radius(),DefenseConfig.number(DefenseConfig.EXPLOSION_DAMAGE));
    }
    public static void filterExplosion(ServerLevel level,java.util.Collection<BlockPos> affected,float power,double perPower) {
        if(currentScope()==Scope.NUCLEAR)return;
        var seen=new HashSet<Long>();
        int amount=(int)Math.min(100000000,Math.ceil(power*perPower));
        affected.removeIf(pos->{var state=level.getBlockState(pos);if(tier(state)==null)return false;
            if(seen.add(anchor(pos,state).asLong()))damage(level,pos,amount);return true;});
    }
    private static void piston(PistonEvent.Pre event) {
        if(!(event.getLevel() instanceof Level level))return;var helper=event.getStructureHelper();if(helper==null||!helper.resolve())return;
        for(var pos:helper.getToPush())if(tier(level.getBlockState(pos))!=null){event.setCanceled(true);return;}
        for(var pos:helper.getToDestroy())if(tier(level.getBlockState(pos))!=null){event.setCanceled(true);return;}
    }
    private static void stopped(ServerStoppedEvent event) {
        STORES.keySet().removeIf(level->level.getServer()==event.getServer());FAILED.removeIf(level->level.getServer()==event.getServer());DefenseMigration.close(event.getServer());
    }
}
