package dev.deadearth.legacy.climate;

import dev.deadearth.legacy.*;
import java.nio.file.Path;
import java.util.*;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.*;
import net.minecraftforge.fml.relauncher.Side;

/** Server-thread climate integration; client code is isolated behind the packet/event boundary. */
public final class LegacyClimate {
    private static final LegacyClimate LISTENER=new LegacyClimate();
    private static final Map<Object,WorldWork> WORLDS=new WeakHashMap<Object,WorldWork>();
    static final CoatingBlock[][][] BLOCKS=new CoatingBlock[2][5][8];
    private static Object lakeIce;
    static ClimateConfig config;
    private static CoatingEngine engine;
    private static Object channel;
    private static boolean installed;
    private static long lastTick=-1;
    private static int previousSeason=-1;
    private LegacyClimate() {}

    public static void install(Path directory) {
        config=ClimateConfig.load(directory.resolve("dead-earth/climate.json"));engine=new CoatingEngine(config);
        if(!installed) {
            installed=true;MinecraftForge.EVENT_BUS.register(LISTENER);
            Object registry=Reflect.get(Reflect.type("net.minecraftforge.fml.common.network.NetworkRegistry"),"INSTANCE");
            channel=Reflect.call(registry,"newSimpleChannel","dead_earth_climate");
            Reflect.call(channel,"registerMessage",ClimatePacket.Handler.class,ClimatePacket.class,0,Side.CLIENT);
        }
    }
    public static void registerBlocks(Object registry) {
        for(int material=0;material<2;material++)for(int face=0;face<5;face++)for(int layer=1;layer<=8;layer++) {
            CoatingBlock block=new CoatingBlock(material==1,face,layer);BLOCKS[material][face][layer-1]=block;Reflect.call(registry,"register",block);
        }
        lakeIce=new LakeIceBlock();Reflect.call(registry,"register",lakeIce);
    }
    /** Environmental geometry has no inventory items or drops. */
    public static void registerItems(Object registry) {}
    public static void registerModels() {}
    public static void tick(LegacyRuntime runtime) {
        runtime.requireThread();if(config==null||!config.enabled)return;
        long now=runtime.campaign.activeTicks;
        if(now==lastTick)return;lastTick=now;
        int season=season(runtime.settings.season(now));boolean changed=season!=previousSeason;
        ClimateBudget budget=new ClimateBudget(config.cellsPerTick,config.editsPerTick,config.sectionsPerTick);
        for(Object world:runtime.world.worlds()) {
            if(world==null||Reflect.dimension(world)!=0)continue;
            WorldWork work=work(world);
            if(changed)work.resetColumns();
            if(config.weather&&(changed||now%config.weatherRefreshTicks==0))weather(world,season);
            discover(world,work);
            if(config.coating)process(world,work,season,WinterField.seed(((Number)Reflect.call(world,"getSeed/func_72905_C")).longValue(),Math.floorDiv(now,runtime.settings.seasonTicks*3)),budget);
            if(config.exposure&&now%config.exposureTicks==0)for(Object player:(List<?>)Reflect.get(world,"playerEntities/field_73010_i"))expose(runtime,player,season,now);
        }
        if(changed||now%config.weatherRefreshTicks==0)Reflect.call(channel,"sendToAll",new ClimatePacket(season,config));
        previousSeason=season;
    }
    public static void close() {WORLDS.clear();lastTick=-1;previousSeason=-1;}
    private static int season(String name) {return name.equals("ASH_WINTER")?2:name.equals("HEAT")?1:0;}
    private static WorldWork work(Object world) {WorldWork value=WORLDS.get(world);if(value==null){value=new WorldWork();WORLDS.put(world,value);}return value;}
    private static void weather(Object world,int season) {
        Object info=Reflect.call(world,"getWorldInfo/func_72912_H");
        int duration=Math.max(config.weatherLockTicks,config.weatherRefreshTicks*2);
        Reflect.call(info,"setRaining/func_76084_b",season==2);Reflect.call(info,"setThundering/func_76069_a",false);
        Reflect.call(info,"setRainTime/func_76080_g",duration);Reflect.call(info,"setThunderTime/func_76090_f",duration);
        Reflect.call(info,"setCleanWeatherTime/func_176142_i",season==2?0:duration);
    }
    private static void discover(Object world,WorldWork work) {
        Object provider=Reflect.call(world,"getChunkProvider/func_72863_F");
        if(work.discovery==null) {
            Collection<?> loaded=(Collection<?>)Reflect.call(provider,"getLoadedChunks/func_189548_a");
            work.discovery=loaded.iterator();work.sweep++;
        }
        try {
            for(int i=0;i<config.chunkDiscoveryPerTick&&work.discovery!=null;i++) {
                if(!work.discovery.hasNext()){work.discovery=null;break;}
                work.offer(work.discovery.next());
            }
        } catch(ConcurrentModificationException changed) {work.discovery=null;}
    }
    private static void process(Object world,WorldWork work,int season,long seed,ClimateBudget budget) {
        NativeWorld adapter=new NativeWorld(world);
        for(int count=0;count<config.columnsPerTick&&!work.chunks.isEmpty()&&!budget.exhausted();count++) {
            Iterator<Map.Entry<Long,ChunkWork>> it=work.chunks.entrySet().iterator();Map.Entry<Long,ChunkWork> first=it.next();
            long key=first.getKey();ChunkWork task=first.getValue();it.remove();
            if(!adapter.loaded(task.x<<4,task.z<<4))continue;
            if(task.column==null) {
                int local=(task.cursor*73+(int)WinterField.mix(key))&255;
                task.column=new CoatingEngine.Column((task.x<<4)+(local&15),(task.z<<4)+(local>>4));
            }
            if(engine.column(adapter,task.column,season==2,seed,budget)) {
                if(season==2&&config.freezeWater)surfaceWater(adapter,task.column.x,task.column.z,budget);
                if(season==1&&config.summerFire)summerFire(adapter,task.column.x,task.column.z,lastTick,budget);
                task.cursor=(task.cursor+1)&255;task.column=null;
            }
            work.chunks.put(key,task);
        }
    }
    static void surfaceWater(NativeWorld world,int x,int z,ClimateBudget budget) {
        if(!world.loaded(x,z)||!budget.read())return;
        int y=world.height(x,z)-1;if(y<0||y>255||!budget.read())return;
        if(world.read(x,y,z)!=CoatingEngine.Cell.WATER||!budget.canWrite(0,x,y,z)||!budget.read())return;
        if(engine.clearForOperation(world,x,y,z,budget)&&world.unobstructed(x,y,z,CoatingEngine.Cell.FROZEN_WATER)&&world.write(x,y,z,CoatingEngine.Cell.FROZEN_WATER))budget.written(0,x,y,z);
    }
    private static void summerFire(NativeWorld world,int x,int z,long now,ClimateBudget budget) {
        if(WinterField.unit(WinterField.mix(now^((long)x<<32)^z))>=config.summerFireChance||!budget.read())return;
        if(!(Boolean)Reflect.call(world.world,"isDaytime/func_72935_r"))return;
        Object rules=Reflect.call(world.world,"getGameRules/func_82736_K");
        if(!(Boolean)Reflect.call(rules,"getBoolean/func_82766_b","doFireTick")||!world.loaded(x,z))return;
        int y=world.height(x,z);if(y<1||y>255||!budget.read())return;
        if(world.read(x,y,z)!=CoatingEngine.Cell.AIR||!budget.canWrite(0,x,y,z))return;
        for(int dx=-config.operationClearance;dx<=config.operationClearance;dx++)for(int dz=-config.operationClearance;dz<=config.operationClearance;dz++)for(int dy=-1;dy<=1;dy++) {
            if(y+dy<0||y+dy>255)continue;
            if(!world.loaded(x+dx,z+dz)||!budget.read()||world.read(x+dx,y+dy,z+dz).operation)return;
        }
        if(!budget.read())return;
        Object under=Reflect.pos(x,y-1,z),state=Reflect.state(world.world,under),block=Reflect.call(state,"getBlock/func_177230_c");
        // Fire is opt-in native summer behavior and never directly replaces an occupied block.
        if((Boolean)Reflect.call(block,"hasTileEntity",state))return;
        Object up=Reflect.get(Reflect.type("net.minecraft.util.EnumFacing"),"UP");
        if(!(Boolean)Reflect.call(block,"isFlammable",world.world,under,up))return;
        Object fire=Reflect.call(Reflect.registry("BLOCKS","minecraft:fire"),"getDefaultState/func_176223_P");
        if((Boolean)Reflect.call(world.world,"setBlockState/func_180501_a",Reflect.pos(x,y,z),fire,3))budget.written(0,x,y,z);
    }
    private static void expose(LegacyRuntime runtime,Object player,int season,long now) {
        Object data=Reflect.nbt(player);String key="deadEarthClimateExposure";
        if(!runtime.world.survival(player)) {Reflect.call(data,"removeTag/func_82580_o",key);return;}
        Object world=Reflect.world(player),position=Reflect.position(player);
        boolean outside=(Boolean)Reflect.call(world,"canSeeSky/func_175678_i",position);
        boolean sunny=(Boolean)Reflect.call(world,"isDaytime/func_72935_r")&&!(Boolean)Reflect.call(world,"isRaining/func_72896_J");
        int value=ExposurePolicy.step(((Number)Reflect.call(data,"getInteger/func_74762_e",key)).intValue(),season,outside,sunny,suited(player),config);
        Reflect.call(data,"setInteger/func_74768_a",key,value);
        if(value<=-config.softThreshold) {
            potion(player,"minecraft:slowness");potion(player,"minecraft:weakness");
            long last=((Number)Reflect.call(data,"getLong/func_74763_f","deadEarthColdDamageAt")).longValue();
            if(value<=-config.harmThreshold&&config.coldDamage>0&&ExposurePolicy.damageDue(last==0?-1:last,now,config.damageTicks)) {
                Reflect.call(data,"setLong/func_74772_a","deadEarthColdDamageAt",now);
                Object damage=Reflect.get(Reflect.type("net.minecraft.util.DamageSource"),"GENERIC/field_76377_j");
                Reflect.call(player,"attackEntityFrom/func_70097_a",damage,(float)config.coldDamage);
            }
        }
        if(value>=config.softThreshold) {
            potion(player,"minecraft:hunger");potion(player,"minecraft:mining_fatigue");
            if(value>=config.harmThreshold&&config.igniteSeconds>0)Reflect.call(player,"setFire/func_70015_d",config.igniteSeconds);
        }
    }
    private static boolean suited(Object player) {
        Set<String> armor=new HashSet<String>(Arrays.asList(config.protectiveArmor));int count=0;
        for(Object stack:(Iterable<?>)Reflect.call(player,"getArmorInventoryList/func_184193_aE")) {
            Object item=Reflect.call(stack,"getItem/func_77973_b");
            if(!armor.contains(Reflect.call(item,"getRegistryName").toString()))return false;count++;
        }
        return count==4;
    }
    private static void potion(Object player,String id) {
        Object potion=Reflect.call(Reflect.type("net.minecraft.potion.Potion"),"getPotionFromResourceLocation/func_180142_c",id);
        Reflect.call(player,"addPotionEffect/func_70690_d",Reflect.make("net.minecraft.potion.PotionEffect",potion,config.potionTicks,config.potionAmplifier,false,true));
    }
    @SubscribeEvent public void events(Event event) {
        String name=event.getClass().getName();
        if(name.endsWith("ChunkEvent$Load")||name.endsWith("ChunkEvent$Unload")) {
            Object chunk=Reflect.call(event,"getChunk"),world=Reflect.call(event,"getWorld");
            if((Boolean)Reflect.get(world,"isRemote/field_72995_K")||Reflect.dimension(world)!=0||config==null)return;
            WorldWork work=work(world);work.discovery=null;
            if(name.endsWith("$Load"))work.offer(chunk);else work.chunks.remove(chunkKey(chunk));
        } else if(name.contains("ClientDisconnectionFromServerEvent"))ClimateClient.reset();
        else if(name.endsWith("ClientTickEvent"))ClimateClient.tick(event);
        else if(name.contains("EntityViewRenderEvent$"))ClimateClient.fog(event,name);
    }
    private static long chunkKey(Object chunk) {
        int x=((Number)Reflect.get(chunk,"x/field_76635_g")).intValue(),z=((Number)Reflect.get(chunk,"z/field_76647_h")).intValue();
        return (x&0xffffffffL)|((long)z<<32);
    }
    private static final class ChunkWork {
        final int x,z;int cursor;CoatingEngine.Column column;
        ChunkWork(long key,long sweep){x=(int)key;z=(int)(key>>32);cursor=(int)(sweep*73)&255;}
    }
    private static final class WorldWork {
        final LinkedHashMap<Long,ChunkWork> chunks=new LinkedHashMap<Long,ChunkWork>();Iterator<?> discovery;long sweep;
        void offer(Object chunk) {
            long key=chunkKey(chunk);if(chunks.containsKey(key))return;
            if(chunks.size()>=config.trackedChunks){Iterator<Long> i=chunks.keySet().iterator();i.next();i.remove();}
            chunks.put(key,new ChunkWork(key,sweep));
        }
        void resetColumns(){for(ChunkWork w:chunks.values())w.column=null;}
    }
    static final class NativeWorld implements CoatingEngine.World {
        final Object world,provider;NativeWorld(Object world){this.world=world;provider=Reflect.call(world,"getChunkProvider/func_72863_F");}
        public int dimension(){return 0;}
        public boolean loaded(int x,int z){return Reflect.call(provider,"getLoadedChunk/func_186028_c",x>>4,z>>4)!=null;}
        public int height(int x,int z){Object chunk=Reflect.call(provider,"getLoadedChunk/func_186028_c",x>>4,z>>4);return ((Number)Reflect.call(chunk,"getHeightValue/func_76611_b",x&15,z&15)).intValue();}
        public CoatingEngine.Cell read(int x,int y,int z) {
            Object pos=Reflect.pos(x,y,z),state=Reflect.state(world,pos),block=Reflect.call(state,"getBlock/func_177230_c");
            if(block==lakeIce)return CoatingEngine.Cell.FROZEN_WATER;
            if(block instanceof CoatingBlock)return ((CoatingBlock)block).cell;
            String id=Reflect.call(block,"getRegistryName").toString();
            if(id.equals("minecraft:air"))return CoatingEngine.Cell.AIR;
            if(id.equals("minecraft:water")&&((Number)Reflect.call(block,"getMetaFromState/func_176201_c",state)).intValue()==0)return CoatingEngine.Cell.WATER;
            if((Boolean)Reflect.call(block,"hasTileEntity",state))return CoatingEngine.Cell.OPERATION;
            for(String fragment:config.protectedFragments)if(id.contains(fragment))return CoatingEngine.Cell.OPERATION;
            Object material=Reflect.call(state,"getMaterial/func_185904_a");
            if((Boolean)Reflect.call(material,"isLiquid/func_76224_d"))return CoatingEngine.Cell.BLOCKED;
            return (Boolean)Reflect.call(state,"isFullCube/func_185913_b")?CoatingEngine.Cell.NATURAL:CoatingEngine.Cell.BLOCKED;
        }
        public boolean write(int x,int y,int z,CoatingEngine.Cell next) {
            Object pos=Reflect.pos(x,y,z);CoatingEngine.Cell actual=read(x,y,z);
            if(next==CoatingEngine.Cell.FROZEN_WATER&&actual!=CoatingEngine.Cell.WATER||next==CoatingEngine.Cell.WATER&&actual!=CoatingEngine.Cell.FROZEN_WATER)return false;
            // Re-read directly before writing: never erase a native block or a player's replacement.
            if(next==CoatingEngine.Cell.AIR&&!actual.own||next.own&&next!=CoatingEngine.Cell.FROZEN_WATER&&!actual.replaceable())return false;
            Object block=next==CoatingEngine.Cell.WATER?Reflect.registry("BLOCKS","minecraft:water"):next==CoatingEngine.Cell.FROZEN_WATER?lakeIce:next==CoatingEngine.Cell.AIR?Reflect.registry("BLOCKS","minecraft:air"):BLOCKS[next.snow?1:0][next.face][next.layers-1];
            return (Boolean)Reflect.call(world,"setBlockState/func_180501_a",pos,Reflect.call(block,"getDefaultState/func_176223_P"),2|16);
        }
        public boolean unobstructed(int x,int y,int z,CoatingEngine.Cell next) {
            double t=next.layers/8.0,a=0,b=0,d=0,e=1,f=1,g=1;
            if(next.face==CoatingEngine.UP)f=t;
            else if(next.face==CoatingEngine.NORTH)d=1-t;else if(next.face==CoatingEngine.SOUTH)g=t;
            else if(next.face==CoatingEngine.WEST)a=1-t;else e=t;
            Object box=Reflect.make("net.minecraft.util.math.AxisAlignedBB",x+a,y+b,z+d,x+e,y+f,z+g);
            return (Boolean)Reflect.call(world,"checkNoEntityCollision/func_72855_b",box);
        }
    }
}
