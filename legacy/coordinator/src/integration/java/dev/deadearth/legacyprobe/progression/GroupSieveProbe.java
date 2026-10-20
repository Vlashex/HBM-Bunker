package dev.deadearth.legacyprobe.progression;

import dev.deadearth.legacy.Reflect;
import dev.deadearth.legacy.JsonFiles;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.*;

/** Real center-click group test, only in the isolated native test world. No clocks/configs are changed. */
public final class GroupSieveProbe {
 private GroupSieveProbe(){}
 private static void require(boolean value,String detail){if(!value)throw new AssertionError(detail);}
 private static Object stack(String id,int count,int meta){return Reflect.make("net.minecraft.item.ItemStack",Reflect.registry("ITEMS",id),count,meta);}
 private static Object exact(Object receiver,String owner,String name,Class<?> result,Class<?>[] params,Object...args){
  try{List<Object> values=new ArrayList<Object>();values.add(receiver);Collections.addAll(values,args);return MethodHandles.publicLookup().findVirtual(Reflect.type(owner),name,MethodType.methodType(result,params)).invokeWithArguments(values);}
  catch(Throwable failure){throw new IllegalStateException("Native sieve API "+owner+"."+name,failure);}
 }
 private static Object sieve(Object receiver,String method,Class<?> result,Class<?>[] params,Object...args){return exact(receiver,"exnihilocreatio.tiles.TileSieve",method,result,params,args);}
 private static boolean filled(Object tile){Object current=sieve(tile,"getCurrentStack",Reflect.type("exnihilocreatio.util.BlockInfo"),new Class<?>[0]);return (Boolean)exact(current,"exnihilocreatio.util.BlockInfo","isValid",boolean.class,new Class<?>[0]);}
 private static float progress(Object tile){return ((Number)sieve(tile,"getProgress",float.class,new Class<?>[0])).floatValue();}
 private static void click(Object block,Object world,Object position,Object actor,Object hand,Object up){require((Boolean)Reflect.call(block,"onBlockActivated/func_180639_a",world,position,Reflect.state(world,position),actor,hand,up,.5f,.5f,.5f),"native center click accepted");}
 public static Map<String,Object> run(Object world,Object ignoredFakePlayer,int x,int y,int z){
  require(Boolean.getBoolean("deadearth.testControls"),"test controls required");
  require(!(Boolean)Reflect.get(world,"isRemote/field_72995_K"),"server-only group sieve probe");
  require(y>0&&y<255,"bounded fixture Y");
  require(((Number)Reflect.call(world,"getTotalWorldTime/func_82737_E")).longValue()>=4,"ordinary warmup before manual sieve tick gate");
  Object settings=Reflect.get(Reflect.type("exnihilocreatio.config.ModConfig"),"sieve");
  int radius=((Number)Reflect.get(settings,"sieveSimilarRadius")).intValue();require(radius==2,"pinned ordinary sieve radius is2");
  List<Object> positions=new ArrayList<Object>();for(int dx=-2;dx<=2;dx++)for(int dz=-2;dz<=2;dz++)positions.add(Reflect.pos(x+dx,y,z+dz));
  Object outside=Reflect.pos(x+3,y,z);positions.add(outside);
  for(Object position:positions){require(Reflect.loaded(world,position),"fixture chunks already loaded");require(Reflect.blockId(world,position).equals("minecraft:air")&&Reflect.call(world,"getTileEntity/func_175625_s",position)==null,"fixture must be empty air; never replace existing blocks");}
  Object server=Reflect.call(world,"getMinecraftServer/func_73046_m");
  require((Boolean)Reflect.call(server,"isCallingFromMinecraftThread/func_152345_ab"),"server thread required");
  Object profile=Reflect.make("com.mojang.authlib.GameProfile",UUID.fromString("975f518f-53fa-4a4f-9525-ae57685f9992"),"GroupSieveProbe");
  Object interaction=Reflect.make("net.minecraft.server.management.PlayerInteractionManager",world);
  Object actor=Reflect.make("net.minecraft.entity.player.EntityPlayerMP",server,world,profile,interaction);
  require(!Reflect.is(actor,"net.minecraftforge.common.util.FakePlayer"),"manual actor is not FakePlayer");
  require(!(Boolean)Reflect.call(actor,"isCreative/func_184812_l_"),"raw consumption uses survival semantics");
  Object hand=Reflect.get(Reflect.type("net.minecraft.util.EnumHand"),"MAIN_HAND"),up=Reflect.get(Reflect.type("net.minecraft.util.EnumFacing"),"UP");
  Object block=Reflect.registry("BLOCKS","exnihilocreatio:block_sieve"),state=Reflect.call(block,"getDefaultState/func_176223_P");
  List<Object> placed=new ArrayList<Object>(),tiles=new ArrayList<Object>();
  try{
   for(Object position:positions){require((Boolean)Reflect.call(world,"setBlockState/func_180501_a",position,state,2),"native sieve block placed");placed.add(position);Object tile=Reflect.call(world,"getTileEntity/func_175625_s",position);require(Reflect.is(tile,"exnihilocreatio.tiles.TileSieve"),"real TileSieve created");require((Boolean)sieve(tile,"setMesh",boolean.class,new Class<?>[]{Reflect.type("net.minecraft.item.ItemStack")},stack("exnihilocreatio:item_mesh",1,4)),"native diamond mesh inserted");tiles.add(tile);}
   Object center=Reflect.pos(x,y,z);Reflect.call(actor,"setHeldItem/func_184611_a",hand,stack("minecraft:gravel",25,0));click(block,world,center,actor,hand,up);
   require((Boolean)Reflect.call(Reflect.call(actor,"getHeldItem/func_184586_b",hand),"isEmpty/func_190926_b"),"exactly25 raw gravel consumed");
   for(int i=0;i<25;i++){require(filled(tiles.get(i)),"all25 native tiles filled");require(progress(tiles.get(i))==0f,"filling does not sieve");}
   require(!filled(tiles.get(25)),"outside radius sentinel not filled");
   click(block,world,center,actor,hand,up);
   float amount=progress(tiles.get(12));require(amount>0f&&amount<1f,"one center click advances native progress without finishing");
   for(int i=0;i<25;i++){require(filled(tiles.get(i)),"all25 reserved raw inputs remain after one sift");require(progress(tiles.get(i))==amount,"one center click advances every tile equally");}
   require(!filled(tiles.get(25))&&progress(tiles.get(25))==0f,"outside radius sentinel untouched");
   Object registry=Reflect.get(Reflect.type("exnihilocreatio.registries.manager.ExNihiloRegistryManager"),"SIEVE_REGISTRY");
   Object gravelState=Reflect.call(Reflect.registry("BLOCKS","minecraft:gravel"),"getDefaultState/func_176223_P");
   Random positiveDraw=new Random(0L){@Override public double nextDouble(){return 0d;}@Override public int nextInt(int bound){if(bound<=0)throw new IllegalArgumentException();return 0;}};
   List<?> rewards=(List<?>)Reflect.call(registry,"getRewardDrops",positiveDraw,gravelState,4,0);boolean hbm=false;for(Object reward:rewards){if(String.valueOf(Reflect.call(Reflect.call(reward,"getItem/func_77973_b"),"getRegistryName")).equals("hbm:ore_tungsten"))hbm=true;}require(hbm,"actual raw registry positive RNG draw includes custom HBM tungsten");
   return JsonFiles.map("width",5,"nativeTiles",25,"rawConsumed",25,"centerClicks",2,"advancedTiles",25,"progressPerTile",amount,"outsideRadiusUnchanged",true,"nativeHbmRewardDraw",true,"rewardDrawUsesControlledRng",true,"manualPlayerClass",actor.getClass().getName(),"fullCycleDropsTested",false);
  }finally{Object air=Reflect.call(Reflect.registry("BLOCKS","minecraft:air"),"getDefaultState/func_176223_P");for(Object position:placed){Reflect.call(world,"removeTileEntity/func_175713_t",position);Reflect.call(world,"setBlockState/func_180501_a",position,air,2);}}
 }
}