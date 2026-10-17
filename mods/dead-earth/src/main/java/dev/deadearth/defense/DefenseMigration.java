package dev.deadearth.defense;

import dev.deadearth.DeadEarth;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.common.IOUtilities;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** Exact opt-in material mapping. Visits loaded chunks with a fixed budget, never force-loads. */
public final class DefenseMigration {
    private static final ResourceLocation ORIGINAL_STEEL=ResourceLocation.parse("outpostzero:steel_block"),V1_WALL=ResourceLocation.parse("dead_earth:defense_wall_v1");
    private static volatile Map<ResourceLocation,ResourceLocation> mappings=Map.of(ORIGINAL_STEEL,V1_WALL);
    private static final Map<ServerLevel,LinkedHashMap<Long,Cursor>> QUEUES=new WeakHashMap<>();
    private static final Map<ServerLevel,java.util.Iterator<net.minecraft.server.level.ChunkHolder>> RETRIES=new WeakHashMap<>();
    private static final java.util.Set<ServerLevel> OVERFLOW=java.util.Collections.newSetFromMap(new WeakHashMap<>());
    private static final java.util.Set<ServerLevel> FAILED=java.util.Collections.newSetFromMap(new WeakHashMap<>());
    private static List<String> cachedConfig;
    private static final class Cursor {final int x,z;int cell;Cursor(LevelChunk chunk){x=chunk.getPos().x;z=chunk.getPos().z;}}
    private record Candidate(BlockPos pos,BlockState before,BlockState after,CompoundTag be){}
    private DefenseMigration(){}
    private static boolean refreshMappings() {
        var config=DefenseConfig.SPEC.isLoaded()?DefenseConfig.LEGACY_MAPPINGS.get():DefenseConfig.LEGACY_MAPPINGS.getDefault();
        if(config.equals(cachedConfig))return false;
        // Remember rejected settings too: one diagnostic per change, never twenty identical errors/second.
        cachedConfig=List.copyOf(config);
        var next=new LinkedHashMap<ResourceLocation,ResourceLocation>();
        for(var text:config) {
            var pair=text.split("=",2);var old=ResourceLocation.parse(pair[0]);var target=ResourceLocation.parse(pair[1]);
            var block=BuiltInRegistries.BLOCK.get(target);
            if(!BuiltInRegistries.BLOCK.containsKey(old)||!BuiltInRegistries.BLOCK.containsKey(target)
                    ||!(block instanceof DefenseMaterial material)||material.defenseTier()!=DefenseTier.V1||old.equals(target))
                throw new IllegalArgumentException("Legacy defense mapping must name a registered source and V1 target: "+text);
            if(next.putIfAbsent(old,target)!=null)throw new IllegalArgumentException("Duplicate legacy defense mapping");
        }
        boolean changed=!mappings.equals(next);mappings=Map.copyOf(next);return changed;
    }
    public static boolean isLegacy(BlockState state){return mappings.containsKey(BuiltInRegistries.BLOCK.getKey(state.getBlock()));}
    public static boolean equivalent(ResourceLocation original,ResourceLocation current){return original.equals(current)
        ||original.equals(ORIGINAL_STEEL)&&current.equals(V1_WALL)||current.equals(mappings.get(original));}
    public static boolean migrationScope(net.minecraft.world.level.Level level,BlockPos pos){return DefensePolicy.currentScope()==DefensePolicy.Scope.MIGRATION&&DefensePolicy.allowed(level,pos);}
    public static void chunkLoaded(ChunkEvent.Load event) {
        if(!(event.getLevel() instanceof ServerLevel level)||!(event.getChunk() instanceof LevelChunk chunk))return;
        if(!level.getServer().isSameThread()) {
            level.getServer().execute(()->{if(level.getChunkSource().getChunkNow(chunk.getPos().x,chunk.getPos().z)==chunk)enqueue(level,chunk);});return;
        }
        enqueue(level,chunk);
    }
    private static boolean containsLegacy(LevelChunk chunk) {
        for(var section:chunk.getSections())if(!section.hasOnlyAir()&&section.maybeHas(DefenseMigration::isLegacy))return true;
        return false;
    }
    private static void enqueue(ServerLevel level,LevelChunk chunk) {
        if(mappings.isEmpty())return;
        var queue=QUEUES.computeIfAbsent(level,key->new LinkedHashMap<>());
        if(queue.containsKey(chunk.getPos().toLong()))return;
        if(!containsLegacy(chunk))return;
        if(queue.size()<DefenseConfig.integer(DefenseConfig.MIGRATION_QUEUE))queue.put(chunk.getPos().toLong(),new Cursor(chunk));
        else if(OVERFLOW.add(level))DeadEarth.LOGGER.warn("Defense migration queue full in {}; loaded chunk palettes will be retried with a bounded budget",level.dimension().location());
    }
    private static void retry(ServerLevel level) {
        if(!OVERFLOW.contains(level))return;
        var queue=QUEUES.computeIfAbsent(level,key->new LinkedHashMap<>());
        if(queue.size()>=DefenseConfig.integer(DefenseConfig.MIGRATION_QUEUE))return;
        var iterator=RETRIES.computeIfAbsent(level,key->((dev.deadearth.mixin.ChunkMapAccess)level.getChunkSource().chunkMap).deadEarthLoadedChunks().iterator());
        int budget=DefenseConfig.integer(DefenseConfig.MIGRATION_RETRY_CHUNKS);
        try {
            while(budget-->0&&iterator.hasNext()&&queue.size()<DefenseConfig.integer(DefenseConfig.MIGRATION_QUEUE)) {
                var pos=iterator.next().getPos();var chunk=level.getChunkSource().getChunkNow(pos.x,pos.z);if(chunk!=null)enqueue(level,chunk);
            }
            if(!iterator.hasNext()){RETRIES.remove(level);OVERFLOW.remove(level);DeadEarth.LOGGER.info("Defense migration palette retry completed in {}; queued {} candidate chunks",level.dimension().location(),queue.size());}
        }catch(java.util.ConcurrentModificationException changed){RETRIES.remove(level);}
    }
    public static void tick(ServerTickEvent.Post event) {
        boolean changed;
        try{changed=refreshMappings();}catch(RuntimeException invalid){DeadEarth.LOGGER.error("Invalid defense migration mapping; previous mapping preserved",invalid);return;}
        if(mappings.isEmpty())return;
        for(var level:event.getServer().getAllLevels()) {
            if(changed)OVERFLOW.add(level);
            if(FAILED.contains(level))continue;retry(level);var queue=QUEUES.get(level);if(queue==null||queue.isEmpty())continue;
            int budget=DefenseConfig.integer(DefenseConfig.MIGRATION_BUDGET);var candidates=new ArrayList<Candidate>();
            var iterator=queue.entrySet().iterator();
            while(iterator.hasNext()&&budget>0&&candidates.size()<64) {
                var cursor=iterator.next().getValue();var chunk=level.getChunkSource().getChunkNow(cursor.x,cursor.z);
                if(chunk==null){iterator.remove();continue;}
                int cells=level.getSectionsCount()*4096,checkedSection=-1;
                while(cursor.cell<cells&&budget-->0&&candidates.size()<64) {
                    int cell=cursor.cell++,section=cell>>>12,local=cell&4095;var part=chunk.getSection(section);
                    if(section!=checkedSection) {
                        checkedSection=section;
                        if(part.hasOnlyAir()||!part.maybeHas(DefenseMigration::isLegacy)){cursor.cell=(section+1)*4096;continue;}
                    }
                    int x=local&15,z=local>>>4&15,y=local>>>8;
                    var state=part.getBlockState(x,y,z);if(!isLegacy(state))continue;
                    var pos=new BlockPos((cursor.x<<4)+x,level.getMinBuildHeight()+(section<<4)+y,(cursor.z<<4)+z);
                    var candidate=candidate(level,pos,state);if(candidate!=null)candidates.add(candidate);
                }
                if(cursor.cell>=cells)iterator.remove();
            }
            if(!candidates.isEmpty())migrate(level,candidates);
        }
    }
    private static Candidate candidate(ServerLevel level,BlockPos pos,BlockState state) {
        var target=mappings.get(BuiltInRegistries.BLOCK.getKey(state.getBlock()));if(target==null)return null;
        var be=level.getBlockEntity(pos);var nbt=be==null?new CompoundTag():be.saveWithFullMetadata(level.registryAccess());
        // Never discard inventories or unknown block entity semantics. Known SC owner data is backed up whole.
        if(be!=null&&(be instanceof Container||!be.getClass().getName().startsWith("net.geforcemods.securitycraft.")||hasInventory(nbt)))return null;
        var next=BuiltInRegistries.BLOCK.get(target).defaultBlockState();
        // Wall migration only: changing the shape of a door/glass/container requires its own explicit adapter.
        if(!state.getProperties().isEmpty()||!next.getProperties().isEmpty())return null;
        return new Candidate(pos.immutable(),state,next,nbt);
    }
    private static boolean hasInventory(Tag tag) {
        if(tag instanceof CompoundTag compound)for(var key:compound.getAllKeys()) {
            var lower=key.toLowerCase(java.util.Locale.ROOT);
            if(lower.contains("inventory")||lower.equals("items")||hasInventory(compound.get(key)))return true;
        }
        if(tag instanceof net.minecraft.nbt.ListTag list)for(var child:list)if(hasInventory(child))return true;
        return false;
    }
    /** Gameplay request only: never reads/writes a journal or migrates inside a mob tick. */
    public static boolean request(ServerLevel level,BlockPos pos) {
        if(!level.getServer().isSameThread()||FAILED.contains(level))return false;
        var chunk=level.getChunkSource().getChunkNow(pos.getX()>>4,pos.getZ()>>4);
        if(chunk==null||!isLegacy(chunk.getBlockState(pos)))return false;
        enqueue(level,chunk);return true;
    }
    /** Synchronous diagnostic fixture entry point. Gameplay attacks must use request(), then wait. */
    public static boolean migrateAt(ServerLevel level,BlockPos pos) {
        if(!level.getServer().isSameThread()||!level.hasChunkAt(pos)||FAILED.contains(level))return false;
        var candidate=candidate(level,pos,level.getBlockState(pos));if(candidate==null)return false;
        migrate(level,List.of(candidate));return level.getBlockState(pos).getBlock()==candidate.after.getBlock();
    }
    private static void migrate(ServerLevel level,List<Candidate> candidates) {
        try {
            var factory=new SavedData.Factory<>(DefenseMigrationStore::create,DefenseMigrationStore::load);
            var store=level.getDataStorage().get(factory,DefenseMigrationStore.NAME);
            var folder=DimensionType.getStorageFolder(level.dimension(),level.getServer().getWorldPath(LevelResource.ROOT)).resolve("data");
            var file=folder.resolve(DefenseMigrationStore.NAME+".dat");
            if(store==null){if(Files.exists(file))throw new IllegalStateException("Unreadable migration journal");store=DefenseMigrationStore.create();level.getDataStorage().set(DefenseMigrationStore.NAME,store);}
            for(var candidate:candidates)store.record(candidate.pos.asLong(),NbtUtils.writeBlockState(candidate.before),BuiltInRegistries.BLOCK.getKey(candidate.after.getBlock()),candidate.be);
            if(store.isDirty()) {
                // Native SavedData may be saving the same journal asynchronously. Drain its IO worker
                // before this acknowledged, atomic checkpoint; at most 64 replacements share one write.
                IOUtilities.waitUntilIOWorkerComplete();var root=new CompoundTag();root.put("data",store.save(new CompoundTag(),level.registryAccess()));NbtUtils.addCurrentDataVersion(root);
                Files.createDirectories(folder);IOUtilities.writeNbtCompressed(root,file);store.setDirty(false);
            }
            for(var candidate:candidates)if(level.getBlockState(candidate.pos).equals(candidate.before))
                DefensePolicy.scoped(DefensePolicy.Scope.MIGRATION,level,candidate.pos,candidate.before,()->level.setBlock(candidate.pos,candidate.after,3));
        }catch(Exception invalid){FAILED.add(level);DeadEarth.LOGGER.error("Legacy defense migration stopped; original journal/blocks preserved",invalid);}
    }
    public static void close(MinecraftServer server){QUEUES.keySet().removeIf(level->level.getServer()==server);FAILED.removeIf(level->level.getServer()==server);RETRIES.keySet().removeIf(level->level.getServer()==server);OVERFLOW.removeIf(level->level.getServer()==server);}
}
