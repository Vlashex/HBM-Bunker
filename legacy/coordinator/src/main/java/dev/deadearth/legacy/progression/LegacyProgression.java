package dev.deadearth.legacy.progression;
import com.google.gson.*;
import dev.deadearth.legacy.*;
import java.nio.file.*;
import java.util.*;
import java.lang.reflect.Proxy;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.*;
/** Native sieve default-provider and strict raw-only ProjectE remap boundary. No world access. */
public final class LegacyProgression {
 private static boolean installed;private static JsonObject config;private static final Map<String,Long> RAW=new LinkedHashMap<String,Long>();private static int providerRuns,restricted;
 private LegacyProgression(){}
 /** Call once in preInit so the native ExNihilo default lifecycle includes our additive drops. */
 public static void install(Path directory){if(installed)return;try{config=JsonFiles.read(directory.resolve("dead-earth/progression.json"),524288);if(!config.get("schema").getAsString().equals("1")||config.getAsJsonArray("sieve").size()>512||config.getAsJsonArray("requiredItems").size()>1024||config.getAsJsonObject("rawEmc").entrySet().size()>512)throw new IllegalArgumentException("Progression schema/bounds");for(Map.Entry<String,JsonElement> row:config.getAsJsonObject("rawEmc").entrySet()){long value=row.getValue().getAsLong();if(!row.getKey().matches("[a-z0-9_]+:[a-z0-9_./]+\\|[0-9]+")||value<=0)throw new IllegalArgumentException("Raw EMC entry");RAW.put(row.getKey(),value);}
  Class<?> provider=Reflect.type("exnihilocreatio.registries.manager.ISieveDefaultRegistryProvider");Object adapter=Proxy.newProxyInstance(provider.getClassLoader(),new Class<?>[]{provider},(o,m,a)->{if(m.getName().equals("registerRecipeDefaults")){registerDrops(a[0]);return null;}if(m.getName().equals("toString"))return "DeadEarthRawSieveProvider";if(m.getName().equals("hashCode"))return System.identityHashCode(o);if(m.getName().equals("equals"))return o==a[0];return null;});Reflect.call(Reflect.type("exnihilocreatio.registries.manager.ExNihiloRegistryManager"),"registerSieveDefaultRecipeHandler",adapter);MinecraftForge.EVENT_BUS.register(new LegacyProgression());installed=true;
 }catch(Exception invalid){throw new IllegalStateException("Invalid full progression configuration; original file preserved",invalid);}}
 private static void registerDrops(Object registry){for(JsonElement row:config.getAsJsonArray("sieve")){JsonObject j=row.getAsJsonObject();float chance=j.get("chance").getAsFloat();int mesh=j.get("mesh").getAsInt();if(!(chance>0&&chance<=1)||mesh<1||mesh>4)throw new IllegalArgumentException("Native sieve chance/mesh");Object input=stack(j.get("input").getAsString()),output=stack(j.get("output").getAsString());Object info=Reflect.make("exnihilocreatio.util.ItemInfo",output);Reflect.call(registry,"register",input,info,chance,mesh);}providerRuns++;}
 private static Object stack(String token){String[] parts=token.split(":");if(parts.length<2||parts.length>3)throw new IllegalArgumentException("Native progression item");String id=parts[0]+":"+parts[1];Object item=Reflect.registry("ITEMS",id);if(item==null||!id.equals(String.valueOf(Reflect.call(item,"getRegistryName"))))throw new IllegalStateException("Missing native progression item "+id);int meta=parts.length==3?Integer.parseInt(parts[2]):0;if(meta<0||meta>32767)throw new IllegalArgumentException("Native item metadata");return Reflect.make("net.minecraft.item.ItemStack",item,1,meta);}
 /** Call in postInit after item registration. No recipes or worlds are mutated here. */
 public static void postInit(){if(!installed)throw new IllegalStateException("Progression install is missing");for(JsonElement token:config.getAsJsonArray("requiredItems"))stack(token.getAsString());}
 @SubscribeEvent(priority=EventPriority.LOWEST) public void event(Event event){if(!event.getClass().getName().equals("moze_intel.projecte.api.event.EMCRemapEvent"))return;filterRaw();}
 @SuppressWarnings("unchecked") private static Map<Object,Object> emc(){return (Map<Object,Object>)Reflect.get(Reflect.type("moze_intel.projecte.emc.EMCMapper"),"emc");}
 private static String emcKey(Object simple){return String.valueOf(Reflect.get(simple,"id"))+"|"+((Number)Reflect.get(simple,"damage")).intValue();}
 /** Final defense against third-party direct/API additions; applied before ProjectE caches knowledge. */
 private static void filterRaw(){for(Iterator<Map.Entry<Object,Object>> it=emc().entrySet().iterator();it.hasNext();){Map.Entry<Object,Object> row=it.next();Long value=RAW.get(emcKey(row.getKey()));if(value==null){it.remove();restricted++;}else row.setValue(value);}}
 public static boolean rawOnly(){if(!installed)return false;for(Map.Entry<Object,Object> row:emc().entrySet()){Long expected=RAW.get(emcKey(row.getKey()));if(expected==null||((Number)row.getValue()).longValue()!=expected.longValue())return false;}return !emc().isEmpty();}
 public static int sieveProviderRuns(){return providerRuns;}
 public static int restrictedEmcEntries(){return restricted;}
}
