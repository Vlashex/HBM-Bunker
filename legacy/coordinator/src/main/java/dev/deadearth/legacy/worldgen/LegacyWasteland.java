package dev.deadearth.legacy.worldgen;

import dev.deadearth.legacy.Reflect;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.*;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.*;

/** Native Forge 1.12.2 wasteland, independent of the later server runtime startup. */
public final class LegacyWasteland {
    private static final LegacyWasteland LISTENER=new LegacyWasteland();
    private static WastelandSettings settings;
    private static Object ash;
    private static boolean installed,modelsRegistered;
    // Loaded chunks are NEVER retrogen candidates, even if their durable marker is missing.
    private static final Map<Object,Integer> completed=Collections.synchronizedMap(new WeakHashMap<Object,Integer>());
    private static final Map<String,Object> paletteStates=new HashMap<String,Object>();
    private static final Map<String,Long> counters=new LinkedHashMap<String,Long>();
    private LegacyWasteland(){}

    public static synchronized void install(Path config){
        if(installed)return;settings=WastelandSettings.load(config);
        MinecraftForge.EVENT_BUS.register(LISTENER);MinecraftForge.TERRAIN_GEN_BUS.register(LISTENER);
        Class<?> iface=Reflect.type("net.minecraftforge.fml.common.IWorldGenerator");
        Object generator=Proxy.newProxyInstance(iface.getClassLoader(),new Class<?>[]{iface},(proxy,method,args)->{
            String name=method.getName();
            if(name.equals("generate")){generate(args[3],((Number)args[1]).intValue(),((Number)args[2]).intValue());return null;}
            if(name.equals("toString"))return "DeadEarthNativeWasteland";
            if(name.equals("hashCode"))return System.identityHashCode(proxy);
            if(name.equals("equals"))return proxy==args[0];return null;
        });
        Reflect.call(Reflect.type("net.minecraftforge.fml.common.registry.GameRegistry"),"registerWorldGenerator",generator,Integer.MAX_VALUE);
        installed=true;
    }
    public static void registerBlocks(Object registry){if(ash==null)ash=new AshBlock();Reflect.call(registry,"register",ash);}
    public static void registerItems(Object registry){
        if(ash==null)throw new IllegalStateException("Register wasteland blocks before items");
        Object item=Reflect.make("net.minecraft.item.ItemBlock",ash);Reflect.call(item,"setRegistryName","dead_earth","ash");Reflect.call(registry,"register",item);
    }
    public static void registerModels(){
        Object item=Reflect.call(Reflect.type("net.minecraft.item.Item"),"getItemFromBlock/func_150898_a",ash);
        Reflect.call(Reflect.type("net.minecraftforge.client.model.ModelLoader"),"setCustomModelResourceLocation",item,0,
            Reflect.make("net.minecraft.client.renderer.block.model.ModelResourceLocation","dead_earth:ash","inventory"));modelsRegistered=true;
    }
    /** Read-only counts for the final native generation probe; contains no world coordinates. */
    public static synchronized Map<String,Long> diagnostics(){return Collections.unmodifiableMap(new LinkedHashMap<String,Long>(counters));}
    private static synchronized void count(String name,long amount){Long old=counters.get(name);counters.put(name,(old==null?0:old)+amount);}
    private static boolean serverWorld(Object world){
        if(world==null||(Boolean)Reflect.get(world,"isRemote/field_72995_K")||Reflect.dimension(world)!=0)return false;
        Object server=Reflect.call(world,"getMinecraftServer/func_73046_m");
        return server!=null&&(Boolean)Reflect.call(server,"isCallingFromMinecraftThread/func_152345_ab");
    }
    @SuppressWarnings("unchecked")
    private static Object loadedChunk(Object world,int x,int z){
        Object provider=Reflect.call(world,"getChunkProvider/func_72863_F");
        Map<Object,Object> loaded=(Map<Object,Object>)Reflect.get(provider,"loadedChunks/field_73244_f");
        return loaded.get(Long.valueOf(((long)x&0xffffffffL)|((long)z&0xffffffffL)<<32));
    }
    /** Shared by read-only footprint callers (starter/probe); never loads terrain. */
    static int minimumAffectedY(Object world,int cx,int cz){
        Object chunk=loadedChunk(world,cx,cz);
        return settings==null||chunk==null?0:WastelandSurface.minimumAffectedY(new NativeTerrain(chunk,cx,cz),settings);
    }
    private static void generate(Object world,int cx,int cz){
        if(settings==null||!settings.enabled||!serverWorld(world))return;
        Object chunk=loadedChunk(world,cx,cz);if(chunk==null){count("unloaded_skipped",1);return;}
        if(completed.containsKey(chunk)){count("existing_skipped",1);return;}
        NativeTerrain terrain=new NativeTerrain(chunk,cx,cz);
        String reservation=HbmFootprints.reason(world,cx,cz,WastelandSurface.minimumAffectedY(terrain,settings));boolean reserved=reservation!=null;
        if(reserved)count("reservation_"+reservation,1);
        long seed=((Number)Reflect.call(world,"getSeed/func_72905_C")).longValue();
        WastelandSurface.Plan plan=WastelandSurface.plan(terrain,seed,settings,cx,cz,reserved);
        if(plan.writes.size()>settings.maxWrites)throw new IllegalStateException("Wasteland plan exceeds whole-chunk budget");
        // Resolve every target before mutation. Invalid registry/config data cannot leave half a chunk painted.
        for(WastelandSurface.Write write:plan.writes)material(write.state);
        for(WastelandSurface.Write write:plan.writes){
            if(write.x<0||write.x>15||write.z<0||write.z>15||write.y<1||write.y>254)
                throw new IllegalStateException("Wasteland attempted a nonlocal write");
        }
        completed.put(chunk,Integer.valueOf(1));
        for(WastelandSurface.Write write:plan.writes)
            Reflect.call(chunk,"setBlockState/func_177436_a",Reflect.pos(cx*16+write.x,write.y,cz*16+write.z),material(write.state));
        if(!plan.writes.isEmpty()){
            Reflect.call(chunk,"generateSkylightMap/func_76603_b");Reflect.call(chunk,"markDirty/func_76630_e");
        }
        count("generated",1);count("writes",plan.writes.size());count(plan.reason,1);
    }
    private static Object material(String key){
        Object found=paletteStates.get(key);if(found!=null)return found;
        String[] bits=key.split("#",-1);Object block=Reflect.registry("BLOCKS",bits[0]);
        if(block==null)throw new IllegalStateException("Missing inert wasteland block "+bits[0]);
        Object state=Reflect.call(block,"getStateFromMeta/func_176203_a",bits.length==1?0:Integer.parseInt(bits[1]));paletteStates.put(key,state);return state;
    }
    @SubscribeEvent(priority=EventPriority.LOWEST)
    public void event(Event event){
        if(settings==null)return;String name=event.getClass().getName();
        if(name.equals("net.minecraftforge.event.terraingen.InitMapGenEvent")){
            if(settings.enabled){Object original=Reflect.call(event,"getOriginalGen"),current=Reflect.call(event,"getNewGen");
                // Respect another mod's replacement, including HBM. CUSTOM is never selected here.
                HbmFootprints.track(current);
                if(original==current){Object replacement=VanillaSurfaceStructures.replacement(original,String.valueOf(Reflect.call(event,"getType")));
                    if(replacement!=original)Reflect.call(event,"setNewGen",replacement);}}
            return;
        }
        if(name.equals("net.minecraftforge.event.terraingen.DecorateBiomeEvent$Decorate")){
            if(!settings.enabled)return;Object world=Reflect.call(event,"getWorld");if(!serverWorld(world))return;
            String type=String.valueOf(Reflect.call(event,"getType"));
            if((type.equals("TREE")||type.equals("GRASS"))&&vanillaDecoration())event.setResult(Event.Result.DENY);return;
        }
        if(name.equals("net.minecraftforge.event.world.ChunkDataEvent$Load")){
            Object world=Reflect.call(event,"getWorld");
            if(!(Boolean)Reflect.get(world,"isRemote/field_72995_K")&&Reflect.dimension(world)==0)
                completed.put(Reflect.call(event,"getChunk"),Integer.valueOf(((Number)Reflect.call(Reflect.call(event,"getData"),
                    "getInteger/func_74762_e","DeadEarthWasteland")).intValue()==1?1:0));return;
        }
        if(name.equals("net.minecraftforge.event.world.ChunkDataEvent$Save")){
            Object chunk=Reflect.call(event,"getChunk");Integer marker=completed.get(chunk);
            if(marker!=null&&marker==1)Reflect.call(Reflect.call(event,"getData"),"setInteger/func_74768_a","DeadEarthWasteland",1);return;
        }
        if(name.equals("net.minecraftforge.event.world.WorldEvent$Unload")){
            Object world=Reflect.call(event,"getWorld");if(!(Boolean)Reflect.get(world,"isRemote/field_72995_K")){
                synchronized(completed){Iterator<Object> it=completed.keySet().iterator();while(it.hasNext()){
                    Object chunk=it.next();if(Reflect.call(chunk,"getWorld/func_177412_p")==world)it.remove();}}
                paletteStates.clear();}return;
        }
        if(modelsRegistered&&settings.enabled)clientEvent(event,name);
    }
    /** Only the direct vanilla TerrainGen caller is suppressed; mod-specific TREE/GRASS hooks remain native. */
    private static boolean vanillaDecoration(){
        StackTraceElement[] stack=Thread.currentThread().getStackTrace();boolean terrain=false;
        for(StackTraceElement frame:stack){String c=frame.getClassName();
            if(c.equals("net.minecraftforge.event.terraingen.TerrainGen")){terrain=true;continue;}
            if(terrain)return c.startsWith("net.minecraft.world.biome.");
        }return false;
    }
    private static void clientEvent(Event event,String name){
        if(name.equals("net.minecraftforge.event.terraingen.BiomeEvent$GetWaterColor")){
            Object client=Reflect.call(Reflect.type("net.minecraft.client.Minecraft"),"getMinecraft/func_71410_x");
            Object world=Reflect.get(client,"world/field_71441_e");
            if(world!=null&&Reflect.dimension(world)==0)Reflect.call(event,"setNewColor",settings.waterColor);
        }else if(name.equals("net.minecraftforge.client.event.EntityViewRenderEvent$FogColors")){
            Object entity=Reflect.call(event,"getEntity");Object world=Reflect.world(entity);
            if(!(Boolean)Reflect.get(world,"isRemote/field_72995_K")||Reflect.dimension(world)!=0)return;
            Object state=Reflect.call(event,"getState");Object block=Reflect.call(state,"getBlock/func_177230_c");
            String id=String.valueOf(Reflect.call(block,"getRegistryName"));if(!id.equals("minecraft:water")&&!id.equals("minecraft:flowing_water"))return;
            int color=settings.waterFogColor;Reflect.call(event,"setRed",((color>>16)&255)/255f);
            Reflect.call(event,"setGreen",((color>>8)&255)/255f);Reflect.call(event,"setBlue",(color&255)/255f);
        }
    }
    private static final class NativeTerrain implements WastelandSurface.Terrain {
        private final Object chunk;private final int cx,cz;private final Map<Object,String> states=new IdentityHashMap<Object,String>();
        private final Map<Long,Object> samples=new HashMap<Long,Object>();private final Set<String> tileStates=new HashSet<String>(),oreStates=new HashSet<String>();
        NativeTerrain(Object chunk,int cx,int cz){this.chunk=chunk;this.cx=cx;this.cz=cz;}
        public int height(int x,int z){return ((Number)Reflect.call(chunk,"getHeightValue/func_76611_b",x,z)).intValue();}
        private Object sample(int x,int y,int z){
            if(x<0||x>15||z<0||z>15||y<0||y>255)throw new IllegalStateException("Nonlocal wasteland read");
            Long key=Long.valueOf((long)x<<12|(long)z<<8|y);Object s=samples.get(key);
            if(s==null){s=Reflect.call(chunk,"getBlockState/func_177435_g",Reflect.pos(cx*16+x,y,cz*16+z));samples.put(key,s);}return s;
        }
        public String state(int x,int y,int z){
            Object nativeState=sample(x,y,z);String value=states.get(nativeState);if(value!=null)return value;
            Object block=Reflect.call(nativeState,"getBlock/func_177230_c");String id=String.valueOf(Reflect.call(block,"getRegistryName"));
            int meta=((Number)Reflect.call(block,"getMetaFromState/func_176201_c",nativeState)).intValue();value=id+"#"+meta;states.put(nativeState,value);
            boolean tile=(Boolean)Reflect.call(block,"hasTileEntity",nativeState);if(tile)tileStates.add(value);
            if(!tile&&!WastelandSurface.dedicatedBedrock(value)){
                boolean ore=WastelandSurface.ore(value)||Reflect.is(block,"net.minecraft.block.BlockOre");
                if(!ore){
                    Object item=Reflect.call(Reflect.type("net.minecraft.item.Item"),"getItemFromBlock/func_150898_a",block);
                    if(item!=null&&!"minecraft:air".equals(String.valueOf(Reflect.call(item,"getRegistryName")))){
                        Object sample=Reflect.make("net.minecraft.item.ItemStack",item,1,meta);
                        int[] ids=(int[])Reflect.call(Reflect.type("net.minecraftforge.oredict.OreDictionary"),"getOreIDs",sample);
                        for(int oreId:ids){String name=String.valueOf(Reflect.call(Reflect.type("net.minecraftforge.oredict.OreDictionary"),"getOreName",oreId));if(name.startsWith("ore")&&name.length()>3){ore=true;break;}}
                    }
                }
                if(ore)oreStates.add(value);
            }
            return value;
        }
        public boolean tile(int x,int y,int z){return tileStates.contains(state(x,y,z));}
        public boolean ordinaryOre(String state){return oreStates.contains(state);}
    }
}
