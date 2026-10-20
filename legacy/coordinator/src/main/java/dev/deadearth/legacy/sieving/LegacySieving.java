package dev.deadearth.legacy.sieving;
import dev.deadearth.legacy.*;
import com.google.gson.*;
import java.nio.file.Path;
import java.util.*;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.*;
/** Three single-block compact sieves; one global native-draw quota on the logical server owner thread. */
public final class LegacySieving {
 static final String[] GRADES={"string","flint","iron","diamond"};
 static final Map<String,String> RAW=new LinkedHashMap<String,String>();private static final Set<String> OWN_FIRST=new HashSet<String>();
 private static final Map<String,Object> BLOCKS=new LinkedHashMap<String,Object>();
 private static final Set<SieveTile> TILES=Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<SieveTile,Boolean>()));
 private static boolean installed;private static int drawsPerTick=8,cursor;private static long lastTick=Long.MIN_VALUE,totalDraws;private static LegacyRuntime owner;
 private LegacySieving(){}
 public static void install(Path config){if(installed)return;try{JsonObject j=JsonFiles.read(config.resolve("dead-earth/sieving.json"),32768);if(!j.get("schema").getAsString().equals("1"))throw new IllegalArgumentException("Sieving schema");drawsPerTick=j.get("nativeDrawsPerServerTick").getAsInt();if(drawsPerTick<1||drawsPerTick>8)throw new IllegalArgumentException("Sieving global quota");for(Map.Entry<String,JsonElement> e:j.getAsJsonObject("materials").entrySet()){if(!e.getKey().matches("[a-z_]+")||!e.getValue().getAsString().matches("[a-z0-9_]+:[a-z0-9_./]+(?:#[0-9]+)?"))throw new IllegalArgumentException("Sieving raw input");RAW.put(e.getKey(),e.getValue().getAsString());}for(JsonElement e:j.getAsJsonArray("ownFirstCompression")){String name=e.getAsString();if(!RAW.containsKey(name))throw new IllegalArgumentException("Unknown first compression");OWN_FIRST.add(name);}if(RAW.size()<4||RAW.size()>32)throw new IllegalArgumentException("Sieving material bound");}catch(Exception e){throw new IllegalStateException("Invalid compact sieve configuration; original preserved",e);}
  for(int level=3;level<=5;level++)Reflect.call(Reflect.type("net.minecraftforge.fml.common.registry.GameRegistry"),"registerTileEntity",level==3?SieveTile.III.class:level==4?SieveTile.IV.class:SieveTile.V.class,Reflect.make("net.minecraft.util.ResourceLocation","dead_earth:compact_sieve_v"+level));installed=true;
 }
 static void configure(Object block,String id,float hardness){Reflect.call(block,"setRegistryName","dead_earth",id);Reflect.call(block,"setUnlocalizedName/func_149663_c","dead_earth."+id);Reflect.call(block,"setHardness/func_149711_c",hardness);Reflect.call(block,"setResistance/func_149752_b",5f);Reflect.call(block,"setCreativeTab/func_149647_a",Reflect.get(Reflect.type("net.minecraft.creativetab.CreativeTabs"),"BUILDING_BLOCKS/field_78030_b"));}
 public static void registerBlocks(Object registry){for(int level=3;level<=5;level++)add(registry,"compact_sieve_v"+level,new SieveBlock(level));for(String material:RAW.keySet())for(int depth=OWN_FIRST.contains(material)?1:2;depth<=4;depth++){String id=compressed(material,depth);add(registry,id,new CompressedBlock(id,depth));}}
 private static void add(Object registry,String id,Object block){BLOCKS.put(id,block);Reflect.call(registry,"register",block);}
 public static String compressed(String material,int depth){return "compressed_"+material+"_"+depth;}
 public static int equivalents(int level){if(level<3||level>5)throw new IllegalArgumentException("Sieve level");return level==3?81:level==4?729:6561;}
 public static void registerItems(Object registry){for(Map.Entry<String,Object> e:BLOCKS.entrySet()){Object item=Reflect.make("net.minecraft.item.ItemBlock",e.getValue());Reflect.call(item,"setRegistryName","dead_earth",e.getKey());if(e.getKey().startsWith("compact_sieve_"))Reflect.call(item,"setMaxStackSize/func_77625_d",1);Reflect.call(registry,"register",item);}for(int level=3;level<=5;level++)for(String grade:GRADES)Reflect.call(registry,"register",new SieveMesh("compact_mesh_"+grade+"_v"+level));}
 public static void registerModels(){List<String> ids=new ArrayList<String>(BLOCKS.keySet());for(int level=3;level<=5;level++)for(String grade:GRADES)ids.add("compact_mesh_"+grade+"_v"+level);for(String id:ids)Reflect.call(Reflect.type("net.minecraftforge.client.model.ModelLoader"),"setCustomModelResourceLocation",Reflect.registry("ITEMS","dead_earth:"+id),0,Reflect.make("net.minecraft.client.renderer.block.model.ModelResourceLocation","dead_earth:"+id,"inventory"));}
 static void track(SieveTile tile){Object world=Reflect.call(tile,"getWorld/func_145831_w");if(world!=null&&!(Boolean)Reflect.get(world,"isRemote/field_72995_K"))TILES.add(tile);}static void untrack(SieveTile tile){TILES.remove(tile);}
 public static long nativeDrawCount(){return totalDraws;}
 public static void tick(LegacyRuntime runtime){runtime.requireThread();Object world=runtime.world.world(0);if(world==null)return;if(owner!=runtime){owner=runtime;lastTick=Long.MIN_VALUE;}long tick=((Number)Reflect.call(world,"getTotalWorldTime/func_82737_E")).longValue();if(tick==lastTick)return;lastTick=tick;if(TILES.isEmpty())return;List<SieveTile> tiles;synchronized(TILES){tiles=new ArrayList<SieveTile>(TILES);}int budget=drawsPerTick,n=tiles.size(),noProgress=0,visits=0;while(budget>0&&noProgress<n&&visits<256){SieveTile tile=tiles.get(Math.floorMod(cursor++,n));visits++;if(tile.step()){budget--;totalDraws++;noProgress=0;}else noProgress++;}}
 public static void close(){TILES.clear();owner=null;lastTick=Long.MIN_VALUE;cursor=0;totalDraws=0;}
}
