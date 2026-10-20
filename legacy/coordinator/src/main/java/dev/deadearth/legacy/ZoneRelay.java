package dev.deadearth.legacy;
import net.minecraft.block.BlockContainer;
import net.minecraft.block.material.Material;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ITickable;
import net.minecraft.world.World;
import java.util.*;
/** Six inexpensive physical anchors. Forge tickets, not player presence, define loaded zones. */
public final class ZoneRelay extends BlockContainer {
 public ZoneRelay(){super((Material)Reflect.get(Reflect.type("net.minecraft.block.material.Material"),"IRON/field_151573_f"));Reflect.call(this,"setRegistryName","dead_earth","zone_relay");Reflect.call(this,"setUnlocalizedName/func_149663_c","dead_earth.zone_relay");Reflect.call(this,"setHardness/func_149711_c",3f);Reflect.call(this,"setCreativeTab/func_149647_a",Reflect.get(Reflect.type("net.minecraft.creativetab.CreativeTabs"),"REDSTONE/field_78028_d"));}
 public net.minecraft.util.EnumBlockRenderType func_149645_b(net.minecraft.block.state.IBlockState state){return net.minecraft.util.EnumBlockRenderType.MODEL;}
 public TileEntity func_149915_a(World world,int meta){return new RelayTile();}
 public static final class RelayTile extends TileEntity implements ITickable {
  private Object ticket;private int ticks;
  public void func_73660_a(){if(++ticks%100!=1)return;Object w=Reflect.call(this,"getWorld/func_145831_w");if(w==null||(Boolean)Reflect.get(w,"isRemote/field_72995_K"))return;
   Object p=Reflect.call(this,"getPos/func_174877_v");LegacyRuntime r=DeadEarthLegacy.runtime;if(r==null)return;
   if(!r.anchor(w,p))return;if(ticket==null)ticket=Tickets.acquire(w,p);
  }
  public void func_145843_s(){Tickets.release(ticket);ticket=null;super.func_145843_s();}
  public void onChunkUnload(){Tickets.release(ticket);ticket=null;super.onChunkUnload();}
 }
 public static final class Tickets {
  private static final Map<String,Object> restored=new HashMap<String,Object>();
  private static String key(Object w,Object p){return Reflect.dimension(w)+":"+Reflect.x(p)+":"+Reflect.y(p)+":"+Reflect.z(p);}
  public static void install(){Class<?> iface=Reflect.type("net.minecraftforge.common.ForgeChunkManager$LoadingCallback");Object callback=java.lang.reflect.Proxy.newProxyInstance(iface.getClassLoader(),new Class<?>[]{iface},(o,m,a)->{if(m.getName().equals("ticketsLoaded")){for(Object t:(List<?>)a[0]){Object tag=Reflect.call(t,"getModData");int x=((Number)Reflect.call(tag,"getInteger/func_74762_e","x")).intValue(),y=((Number)Reflect.call(tag,"getInteger/func_74762_e","y")).intValue(),z=((Number)Reflect.call(tag,"getInteger/func_74762_e","z")).intValue();Object p=Reflect.pos(x,y,z);if(restored.size()<16){force(t,x,z);restored.put(key(a[1],p),t);}else release(t);}}return null;});Reflect.call(Reflect.type("net.minecraftforge.common.ForgeChunkManager"),"setForcedChunkLoadingCallback",DeadEarthLegacy.instance,callback);}
  public static Object acquire(Object w,Object p){Object prior=restored.remove(key(w,p));if(prior!=null){for(Object chunk:new ArrayList<Object>((java.util.Collection<?>)Reflect.call(prior,"getChunkList")))Reflect.call(Reflect.type("net.minecraftforge.common.ForgeChunkManager"),"unforceChunk",prior,chunk);force(prior,Reflect.x(p),Reflect.z(p));return prior;}Object t=Reflect.call(Reflect.type("net.minecraftforge.common.ForgeChunkManager"),"requestTicket",DeadEarthLegacy.instance,w,Reflect.get(Reflect.type("net.minecraftforge.common.ForgeChunkManager$Type"),"NORMAL"));if(t!=null){Object tag=Reflect.call(t,"getModData");Reflect.call(tag,"setInteger/func_74768_a","x",Reflect.x(p));Reflect.call(tag,"setInteger/func_74768_a","y",Reflect.y(p));Reflect.call(tag,"setInteger/func_74768_a","z",Reflect.z(p));force(t,Reflect.x(p),Reflect.z(p));}return t;}
  private static void force(Object t,int x,int z){Object tag=Reflect.call(t,"getModData");int radius=DeadEarthLegacy.runtime==null?((Boolean)Reflect.call(tag,"hasKey/func_74764_b","radius")?((Number)Reflect.call(tag,"getInteger/func_74762_e","radius")).intValue():1):DeadEarthLegacy.runtime.settings.zoneRadiusChunks;if(radius<0||radius>2){release(t);return;}Reflect.call(tag,"setInteger/func_74768_a","radius",radius);Reflect.call(t,"setChunkListDepth",(2*radius+1)*(2*radius+1));for(int dx=-radius;dx<=radius;dx++)for(int dz=-radius;dz<=radius;dz++)Reflect.call(Reflect.type("net.minecraftforge.common.ForgeChunkManager"),"forceChunk",t,Reflect.make("net.minecraft.util.math.ChunkPos",(x>>4)+dx,(z>>4)+dz));}
  public static void reconcile(LegacyRuntime r){Iterator<Map.Entry<String,Object>> entries=restored.entrySet().iterator();while(entries.hasNext()){Map.Entry<String,Object> e=entries.next();String[] values=e.getKey().split(":");Object w=r.world.world(Integer.parseInt(values[0]));Object p=Reflect.pos(Integer.parseInt(values[1]),Integer.parseInt(values[2]),Integer.parseInt(values[3]));if(w!=null&&Reflect.loaded(w,p)&&!Reflect.blockId(w,p).equals("dead_earth:zone_relay")){release(e.getValue());entries.remove();}}}
  public static void release(Object t){if(t!=null)Reflect.call(Reflect.type("net.minecraftforge.common.ForgeChunkManager"),"releaseTicket",t);}
 }
}