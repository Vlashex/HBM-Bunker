package dev.deadearth.loadtest;

import com.google.gson.Gson;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import dev.deadearth.DeadEarth;
import dev.deadearth.zone.ZoneLoaders;
import java.io.BufferedWriter;
import java.lang.management.ManagementFactory;
import java.nio.file.*;
import java.util.*;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.bus.api.*;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.*;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** Optional development source set. Never compiled into an ordinary release. */
@EventBusSubscriber(modid=DeadEarth.MOD_ID)
public final class LoadTestServerProbe {
    private static final Gson JSON=new Gson();
    private static Path output;
    private static BufferedWriter writer;
    private static final ArrayDeque<Runnable> jobs=new ArrayDeque<>();
    private static final ArrayList<Long> tickNanos=new ArrayList<>(), vanillaNanos=new ArrayList<>();
    private static long pre,windowStart,lastWrite;
    private static String phase="startup";
    private static int forcedTarget;
    private static boolean enabled(MinecraftServer server){return output!=null&&server.getPort()==25568;}

    @SubscribeEvent public static void started(ServerStartedEvent event)throws Exception {
        String value=System.getProperty("deadearth.loadTestOutput");
        if(value==null||event.getServer().getPort()!=25568)return;
        var path=Path.of(value).toAbsolutePath().normalize();
        if(!Files.isDirectory(path)||!Files.isRegularFile(path.resolve(".dead-earth-loadtest")))return;
        output=path;writer=Files.newBufferedWriter(output.resolve("server-metrics.jsonl"),StandardOpenOption.CREATE_NEW);
        windowStart=lastWrite=System.nanoTime();
        Files.writeString(output.resolve("server-ready.json"),JSON.toJson(Map.of("pid",ProcessHandle.current().pid(),"port",25568,"seed",event.getServer().overworld().getSeed())));
    }
    @SubscribeEvent(priority=EventPriority.HIGHEST) public static void before(ServerTickEvent.Pre event){if(enabled(event.getServer()))pre=System.nanoTime();}
    @SubscribeEvent(priority=EventPriority.LOWEST) public static void after(ServerTickEvent.Post event) {
        var server=event.getServer();if(!enabled(server))return;
        // Job costs are intentionally included; warm phases contain no pending jobs.
        for(int i=0;i<2&&!jobs.isEmpty();i++)jobs.removeFirst().run();
        long now=System.nanoTime();tickNanos.add(now-pre);
        long[] times=server.getTickTimesNanos();vanillaNanos.add(times[Math.floorMod(server.getTickCount(),times.length)]);
        if(now-lastWrite<1_000_000_000L)return;
        try {
            String current=Files.readString(output.resolve("phase.txt")).trim();
            var row=new LinkedHashMap<String,Object>();row.put("type","window");row.put("phase",phase);
            row.put("wallTimeMs",System.currentTimeMillis());row.put("windowNanos",now-windowStart);
            row.put("ticks",tickNanos.size());row.put("tickNanos",List.copyOf(tickNanos));row.put("vanillaTickNanos",List.copyOf(vanillaNanos));
            row.putAll(snapshot(server));writer.write(JSON.toJson(row));writer.newLine();writer.flush();
            phase=current;lastWrite=windowStart=now;tickNanos.clear();vanillaNanos.clear();
        }catch(Exception failure){DeadEarth.LOGGER.error("Load test telemetry failed",failure);}
    }
    @SubscribeEvent public static void stopped(ServerStoppedEvent event)throws Exception {if(writer!=null){writer.close();writer=null;output=null;}}

    @SuppressWarnings("unchecked")
    private static Map<String,Object> snapshot(MinecraftServer server)throws Exception {
        var result=new LinkedHashMap<String,Object>();int holders=0,full=0,ticking=0,entityTicking=0,blockEntities=0,entities=0,mobs=0,ai=0,targets=0,items=0,benchEntities=0,benchAi=0,benchTargets=0,hoppers=0,chests=0;
        var types=new TreeMap<String,Integer>();var benchTypes=new TreeMap<String,Integer>();
        var method=ChunkMap.class.getDeclaredMethod("getChunks");method.setAccessible(true);
        for(var level:server.getAllLevels()) {
            holders+=level.getChunkSource().getLoadedChunksCount();
            for(var holder:(Iterable<ChunkHolder>)method.invoke(level.getChunkSource().chunkMap)) {
                var f=holder.getFullChunkFuture().getNow(null);
                if(f!=null&&f.isSuccess())full++;
                var chunk=holder.getTickingChunk();if(chunk!=null){ticking++;blockEntities+=chunk.getBlockEntities().size();for(var blockEntity:chunk.getBlockEntities().values()){if(blockEntity instanceof HopperBlockEntity)hoppers++;if(blockEntity instanceof ChestBlockEntity)chests++;}}
                var e=holder.getEntityTickingChunkFuture().getNow(null);if(e!=null&&e.isSuccess())entityTicking++;
            }
            for(var entity:level.getAllEntities()) {
                if(entity==null||!entity.isAlive())continue;entities++;
                types.merge(BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString(),1,Integer::sum);
                if(entity instanceof Mob mob){mobs++;if(!mob.isNoAi())ai++;if(mob.getTarget()!=null)targets++;}
                if(entity.getTags().contains("dead_earth_bench")){benchEntities++;benchTypes.merge(BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString(),1,Integer::sum);if(entity instanceof Mob mob){if(!mob.isNoAi())benchAi++;if(mob.getTarget()!=null)benchTargets++;}}
                if(entity instanceof net.minecraft.world.entity.item.ItemEntity)items++;
            }
        }
        var runtime=Runtime.getRuntime();var os=(com.sun.management.OperatingSystemMXBean)ManagementFactory.getOperatingSystemMXBean();
        result.put("chunkHolders",holders);result.put("fullChunks",full);result.put("tickingChunks",ticking);result.put("entityTickingChunks",entityTicking);
        result.put("tickingBlockEntities",blockEntities);result.put("entities",entities);result.put("mobs",mobs);result.put("mobsWithAi",ai);result.put("mobsWithTarget",targets);result.put("items",items);result.put("entityTypes",types);
        result.put("benchEntities",benchEntities);result.put("benchWithAi",benchAi);result.put("benchWithTarget",benchTargets);result.put("benchEntityTypes",benchTypes);result.put("tickingHoppers",hoppers);result.put("tickingChests",chests);
        result.put("heapUsed",runtime.totalMemory()-runtime.freeMemory());result.put("heapCommitted",runtime.totalMemory());result.put("heapMax",runtime.maxMemory());
        result.put("processCpuNanos",os.getProcessCpuTime());result.put("freePhysicalBytes",os.getFreeMemorySize());
        result.put("gcCount",ManagementFactory.getGarbageCollectorMXBeans().stream().mapToLong(b->Math.max(0,b.getCollectionCount())).sum());
        result.put("gcMillis",ManagementFactory.getGarbageCollectorMXBeans().stream().mapToLong(b->Math.max(0,b.getCollectionTime())).sum());
        result.put("players",server.getPlayerCount());result.put("jobsRemaining",jobs.size());result.put("forcedTarget",forcedTarget);
        result.put("loaders",ZoneLoaders.count(server));result.put("zones",ZoneLoaders.zones(server).size());
        return result;
    }
    @SubscribeEvent public static void register(RegisterCommandsEvent event) {
        if(System.getProperty("deadearth.loadTestOutput")==null)return;
        event.getDispatcher().register(Commands.literal("de_bench").requires(s->s.hasPermission(4)&&enabled(s.getServer()))
            .then(Commands.literal("snapshot").executes(c->reply(c.getSource(),()->snapshot(c.getSource().getServer()))))
            .then(Commands.literal("season").then(Commands.argument("phase",StringArgumentType.word()).executes(c->reply(c.getSource(),()->{
                var season=dev.deadearth.climate.ClimateCycle.Phase.valueOf(StringArgumentType.getString(c,"phase"));
                DeadEarth.runtime(c.getSource().getServer()).testSeason(season);return Map.of("season",season.name());}))))
            .then(Commands.literal("protection").executes(c->reply(c.getSource(),()->{
                jobs.add(()->{try{var data=ProtectionProbe.run(c.getSource().getServer());Files.writeString(output.resolve("protection.json"),JSON.toJson(data));}
                    catch(Exception failure){try{Files.writeString(output.resolve("protection-error.json"),JSON.toJson(Map.of("error",failure.toString())));}catch(Exception ignored){}}});
                return Map.of("queued",true);})))
            .then(Commands.literal("arena").executes(c->reply(c.getSource(),()->{arena(c.getSource().getServer());return Map.of("arenaQueued",true);})))
            .then(Commands.literal("chunks").then(Commands.argument("count",IntegerArgumentType.integer(0,1024)).executes(c->reply(c.getSource(),()->{
                chunks(c.getSource().getServer(),IntegerArgumentType.getInteger(c,"count"));return Map.of("target",forcedTarget,"queued",jobs.size());}))))
            .then(Commands.literal("entities").then(Commands.argument("count",IntegerArgumentType.integer(0,1500))
                .then(Commands.argument("type",ResourceLocationArgument.id()).then(Commands.argument("ai",BoolArgumentType.bool()).executes(c->reply(c.getSource(),()->{
                    entities(c.getSource().getServer(),IntegerArgumentType.getInteger(c,"count"),ResourceLocationArgument.getId(c,"type").toString(),BoolArgumentType.getBool(c,"ai"));return Map.of("queued",jobs.size());}))))))
            .then(Commands.literal("warehouses").then(Commands.argument("count",IntegerArgumentType.integer(0,6)).executes(c->reply(c.getSource(),()->{
                warehouses(c.getSource().getServer(),IntegerArgumentType.getInteger(c,"count"));return Map.of("queued",jobs.size());}))))
            .then(Commands.literal("registry").executes(c->reply(c.getSource(),()->Map.of(
                "entities",BuiltInRegistries.ENTITY_TYPE.keySet().stream().filter(k->Set.of("mutationcraft","overgrown_vegetation").contains(k.getNamespace())).map(Object::toString).sorted().toList(),
                "blocks",BuiltInRegistries.BLOCK.keySet().stream().filter(k->Set.of("mutationcraft","overgrown_vegetation").contains(k.getNamespace())).map(Object::toString).sorted().toList())))));
    }
    @FunctionalInterface private interface Action{Object run()throws Exception;}
    private static int reply(net.minecraft.commands.CommandSourceStack source,Action action){try{var message=Component.literal(JSON.toJson(action.run()));source.sendSuccess(()->message,false);return 1;}catch(Exception failure){source.sendFailure(Component.literal(failure.toString()));return 0;}}
    private static void arena(MinecraftServer server) {
        var level=server.overworld();
        for(int x=-48;x<48;x++){final int xx=x;jobs.add(()->{for(int z=-48;z<48;z++)level.setBlock(new BlockPos(xx,100,z),Blocks.OBSIDIAN.defaultBlockState(),2);});}
    }
    private static void chunks(MinecraftServer server,int count) {
        var level=server.overworld();int old=forcedTarget;forcedTarget=count;
        if(count>old)for(int i=old;i<count;i++){int index=i;jobs.add(()->level.setChunkForced(256+index%32,256+index/32,true));}
        else for(int i=count;i<old;i++){int index=i;jobs.add(()->level.setChunkForced(256+index%32,256+index/32,false));}
    }
    private static void entities(MinecraftServer server,int count,String name,boolean ai) {
        var level=server.overworld();var oldEntities=new ArrayList<Entity>();
        for(var entity:level.getAllEntities())if(entity!=null&&entity.getTags().contains("dead_earth_bench"))oldEntities.add(entity);
        // Discard mutates the backing entity lookup; never mutate its live iterable.
        for(var entity:oldEntities)entity.discard();
        var key=ResourceLocation.parse(name);if(!BuiltInRegistries.ENTITY_TYPE.containsKey(key))throw new IllegalArgumentException("Unknown native entity: "+name);
        var type=BuiltInRegistries.ENTITY_TYPE.get(key);
        for(int i=0;i<count;i++){int index=i;jobs.add(()->{
            var entity=type.create(level);if(entity==null)throw new IllegalStateException("Entity creation failed: "+name);
            entity.addTag("dead_earth_bench");entity.moveTo(-17.5+(index%40)*.85,101, -16.5+(index/40)*.85,180,0);
            entity.setInvulnerable(true);if(entity instanceof Mob mob){mob.setNoAi(!ai);mob.setPersistenceRequired();
                var range=mob.getAttribute(Attributes.FOLLOW_RANGE);if(range!=null)range.setBaseValue(64);
                if(ai&&!server.getPlayerList().getPlayers().isEmpty())mob.setTarget(server.getPlayerList().getPlayers().getFirst());}
            level.addFreshEntity(entity);
        });}
    }
    private static void warehouses(MinecraftServer server,int count) {
        var level=server.overworld();
        for(int n=0;n<count;n++){int zone=n;jobs.add(()->{
            int x=400+zone*80,z=0;var loader=new BlockPos(x,140,z);
            level.setBlock(loader,dev.deadearth.content.ZoneLoaderContent.LOADER.get().defaultBlockState(),3);
            ZoneLoaders.activate(level,loader);
            for(int dx=0;dx<8;dx++)for(int dz=0;dz<8;dz++) {
                var pos=loader.offset(dx*2,0,dz*2+2);level.setBlock(pos.below(),Blocks.CHEST.defaultBlockState(),2);
                level.setBlock(pos,Blocks.HOPPER.defaultBlockState(),2);level.setBlock(pos.above(),Blocks.CHEST.defaultBlockState(),2);
                var chest=(ChestBlockEntity)level.getBlockEntity(pos.above());for(int slot=0;slot<chest.getContainerSize();slot++)chest.setItem(slot,new ItemStack(Items.IRON_INGOT,64));
            }
        });}
        jobs.add(()->ZoneLoaders.requestScan(server));
    }
}
