package dev.deadearth.legacy.sieving;

import dev.deadearth.legacy.Reflect;
import java.lang.reflect.Array;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.Event;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

/** Client rendering boundary; no client class appears in a field or method signature. */
public final class SieveVisuals {
 private static final int[] BRIGHTNESS={255,219,179,140,107};
 private static boolean registered;
 private SieveVisuals(){}

 /** Called only from the client ModelRegistryEvent after compact blocks/items exist. */
 public static void registerColors(){
  if(registered)return;
  // Model registration is earlier than vanilla color-map construction in 1.12.
  // Subscribe now; ColorHandlerEvent supplies each fully constructed native map.
  MinecraftForge.EVENT_BUS.register(new Colors());MinecraftForge.EVENT_BUS.register(new Tooltips());registered=true;
 }

 private static void installColors(final Object colors,boolean blockColors){
  Class<?> color=Reflect.type("net.minecraft.client.renderer.color."+(blockColors?"IBlockColor":"IItemColor"));
  for(Map.Entry<String,String> material:LegacySieving.RAW.entrySet()){
   String[] raw=material.getValue().split("#");int meta=raw.length==1?0:Integer.parseInt(raw[1]);
   Object rawBlock=Reflect.registry("BLOCKS",raw[0]),rawItem=Reflect.registry("ITEMS",raw[0]);
   final Object rawState=Reflect.call(rawBlock,"getStateFromMeta/func_176203_a",meta),rawStack=Reflect.make("net.minecraft.item.ItemStack",rawItem,1,meta);
   for(int depth=1;depth<=4;depth++){
    String id="dead_earth:"+LegacySieving.compressed(material.getKey(),depth);Object block=Reflect.registry("BLOCKS",id),item=Reflect.registry("ITEMS",id);
    // Eight native first compressions belong to Ex Compressum and keep its renderer.
    if(block==null||item==null||!id.equals(String.valueOf(Reflect.call(block,"getRegistryName"))))continue;
    final int power=depth;
    Object handler=Proxy.newProxyInstance(color.getClassLoader(),new Class<?>[]{color},(proxy,method,args)->{
     Object object=objectMethod(proxy,method.getName(),args);if(object!=null)return object;
     int tint=((Number)args[blockColors?3:1]).intValue();if(tint!=0)return -1;
     int nativeTint=((Number)(blockColors?Reflect.call(colors,"colorMultiplier/func_186724_a",rawState,args[1],args[2],0):Reflect.call(colors,"colorMultiplier/func_186728_a",rawStack,0))).intValue();
     return shade(nativeTint,power);
    });
    Reflect.call(colors,blockColors?"registerBlockColorHandler/func_186722_a":"registerItemColorHandler/func_186731_a",handler,array("net.minecraft.block.Block",block));
   }
  }
  if(blockColors)installContentColors(colors,color);
 }

 /** The shrinking contents use native material tint, independently of the wooden frame. */
 private static void installContentColors(final Object colors,Class<?> color){
  final List<Object> rawStates=new ArrayList<Object>();
  for(String descriptor:LegacySieving.RAW.values()){
   String[] raw=descriptor.split("#");int meta=raw.length==1?0:Integer.parseInt(raw[1]);
   rawStates.add(Reflect.call(Reflect.registry("BLOCKS",raw[0]),"getStateFromMeta/func_176203_a",meta));
  }
  Object handler=Proxy.newProxyInstance(color.getClassLoader(),new Class<?>[]{color},(proxy,method,args)->{
   Object object=objectMethod(proxy,method.getName(),args);if(object!=null)return object;
   if(((Number)args[3]).intValue()!=1)return -1;
   int index=0;
   // Read the listed actual-state property so model fixtures and world renders agree.
   Map<?,?> properties=(Map<?,?>)Reflect.call(args[0],"getProperties/func_177228_b");
   for(Map.Entry<?,?> entry:properties.entrySet())if("material".equals(Reflect.call(entry.getKey(),"getName/func_177701_a")))index=((Number)entry.getValue()).intValue();
   if(index<0||index>=rawStates.size())index=0;
   return Reflect.call(colors,"colorMultiplier/func_186724_a",rawStates.get(index),args[1],args[2],0);
  });
  for(int level=3;level<=5;level++)Reflect.call(colors,"registerBlockColorHandler/func_186722_a",handler,array("net.minecraft.block.Block",Reflect.registry("BLOCKS","dead_earth:compact_sieve_v"+level)));
 }

 /** Multiply native RGB without replacing the material texture or biome foliage tint. */
 static int shade(int nativeTint,int power){
  int rgb=nativeTint==-1?0xffffff:nativeTint,brightness=BRIGHTNESS[power];
  return ((rgb>>16&255)*brightness/255)<<16|((rgb>>8&255)*brightness/255)<<8|(rgb&255)*brightness/255;
 }
 private static Object array(String type,Object value){Object out=Array.newInstance(Reflect.type(type),1);Array.set(out,0,value);return out;}
 private static Object objectMethod(Object proxy,String name,Object[] args){if(name.equals("toString"))return "DeadEarthCompressedMaterialTint";if(name.equals("hashCode"))return System.identityHashCode(proxy);if(name.equals("equals"))return proxy==args[0];return null;}
 private static String roman(int power){return new String[]{"","I","II","III","IV","V"}[power];}
 private static String text(String key,Object...args){return String.valueOf(Reflect.call(Reflect.type("net.minecraft.client.resources.I18n"),"format/func_135052_a",key,args));}

 public static final class Colors {
  @SubscribeEvent public void event(Event event){
   String name=event.getClass().getName();
   if(name.equals("net.minecraftforge.client.event.ColorHandlerEvent$Block"))installColors(Reflect.call(event,"getBlockColors"),true);
   else if(name.equals("net.minecraftforge.client.event.ColorHandlerEvent$Item"))installColors(Reflect.call(event,"getItemColors"),false);
  }
 }

 /** Uses a generic Forge event signature to keep dedicated-server verification safe. */
 public static final class Tooltips {
  @SubscribeEvent @SuppressWarnings("unchecked") public void event(Event event){
   if(!event.getClass().getName().equals("net.minecraftforge.event.entity.player.ItemTooltipEvent"))return;
   Object stack=Reflect.call(event,"getItemStack");String id=SieveStacks.id(stack);List<String> lines=(List<String>)Reflect.call(event,"getToolTip");
   if(id.startsWith("dead_earth:compact_sieve_v")){
    int level=Character.digit(id.charAt(id.length()-1),10);if(level<3||level>5)return;
    lines.add(text("tooltip.dead_earth.sieving.frame_mesh",roman(level)));
    lines.add(text("tooltip.dead_earth.sieving.frame_input",roman(level-1),LegacySieving.equivalents(level)));
    lines.add(text("tooltip.dead_earth.sieving.frame_use"));lines.add(text("tooltip.dead_earth.sieving.frame_strokes"));lines.add(text("tooltip.dead_earth.sieving.frame_remove"));
   }else if(id.startsWith("dead_earth:compact_mesh_")){
    int level=Character.digit(id.charAt(id.length()-1),10);if(level>=3&&level<=5)lines.add(text("tooltip.dead_earth.sieving.mesh_frame",roman(level)));
   }else if(id.startsWith("dead_earth:compressed_")){
    int power=Character.digit(id.charAt(id.length()-1),10);if(power>=1&&power<=4)lines.add(text("tooltip.dead_earth.sieving.compression",roman(power),(int)Math.pow(9,power)));
   }else if(id.equals("exnihilocreatio:item_mesh")||id.equals("excompressum:iron_mesh"))lines.add(text("tooltip.dead_earth.sieving.native_mesh"));
  }
 }
}
