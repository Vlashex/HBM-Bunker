package dev.deadearth.legacy;
import java.lang.reflect.*;
import java.util.*;
/** Narrow 1.12.2 SRG boundary. Domain code never depends on obfuscated Minecraft types. */
public final class Reflect {
 private Reflect(){}
 public static Class<?> type(String n){try{return Class.forName(n);}catch(Exception e){throw fail(n,e);}}
 public static Object make(String n,Object...args){for(Constructor<?> c:type(n).getConstructors())if(matches(c.getParameterTypes(),args))try{return c.newInstance(args);}catch(Exception e){throw fail(n,e);}throw fail(n,null);}
 public static Object get(Object o,String names){Class<?> c=o instanceof Class?(Class<?>)o:o.getClass();for(String n:ordered(names))for(Class<?> t=c;t!=null;t=t.getSuperclass())try{Field f=t.getDeclaredField(n);f.setAccessible(true);return f.get(o instanceof Class?null:o);}catch(NoSuchFieldException e){}catch(Exception e){throw fail(n,e);}throw fail(c.getName()+"."+names,null);}
 public static void set(Object o,String names,Object v){Class<?> c=o instanceof Class?(Class<?>)o:o.getClass();for(String n:ordered(names))for(Class<?> t=c;t!=null;t=t.getSuperclass())try{Field f=t.getDeclaredField(n);f.setAccessible(true);f.set(o instanceof Class?null:o,v);return;}catch(NoSuchFieldException e){}catch(Exception e){throw fail(n,e);}throw fail(names,null);}
 public static Object call(Object o,String names,Object...args){Class<?> c=o instanceof Class?(Class<?>)o:o.getClass();
  if(names.contains("func_176223_P")||names.contains("func_149712_f"))c=type("net.minecraft.block.Block");
  if(names.equals("getRegistryName"))c=type("net.minecraftforge.registries.IForgeRegistryEntry");
  for(String n:ordered(names))for(Method m:c.getMethods())if(m.getName().equals(n)&&matches(m.getParameterTypes(),args))try{m.setAccessible(true);return m.invoke(o instanceof Class?null:o,args);}catch(InvocationTargetException e){throw fail(names,e.getCause());}catch(Exception e){throw fail(names,e);}
  throw fail(c.getName()+"."+names+"/"+args.length,null);
 }
 private static List<String> ordered(String names){List<String> out=new ArrayList<String>(Arrays.asList(names.split("/")));out.sort(Comparator.comparingInt(n->n.startsWith("field_")||n.startsWith("func_")?0:1));return out;}
 private static boolean matches(Class<?>[] ts,Object[] args){if(ts.length!=args.length)return false;for(int i=0;i<ts.length;i++){Class<?> t=ts[i];if(args[i]==null){if(t.isPrimitive())return false;continue;}if(t==int.class)t=Integer.class;if(t==long.class)t=Long.class;if(t==byte.class)t=Byte.class;if(t==float.class)t=Float.class;if(t==double.class)t=Double.class;if(t==boolean.class)t=Boolean.class;if(!t.isInstance(args[i]))return false;}return true;}
 public static Object registry(String kind,String id){return call(get(type("net.minecraftforge.fml.common.registry.ForgeRegistries"),kind),"getValue",make("net.minecraft.util.ResourceLocation",id));}
 public static Object pos(int x,int y,int z){return make("net.minecraft.util.math.BlockPos",x,y,z);}
 public static int x(Object p){return ((Number)call(p,"getX/func_177958_n")).intValue();}public static int y(Object p){return ((Number)call(p,"getY/func_177956_o")).intValue();}public static int z(Object p){return ((Number)call(p,"getZ/func_177952_p")).intValue();}
 public static Object world(Object e){return get(e,"world/field_70170_p");}public static Object position(Object e){return call(e,"getPosition/func_180425_c");}
 public static int dimension(Object world){return ((Number)call(get(world,"provider/field_73011_w"),"getDimension")).intValue();}
 public static Object nbt(Object entity){return call(entity,"getEntityData");}
 public static String entityId(Object e){Object id=call(type("net.minecraft.entity.EntityList"),"getKey/func_191301_a",e);return id==null?"":id.toString();}
 public static boolean loaded(Object w,Object p){return (Boolean)call(w,"isBlockLoaded/func_175667_e",p);}
 public static Object state(Object w,Object p){return call(w,"getBlockState/func_180495_p",p);}public static String blockId(Object w,Object p){return call(call(state(w,p),"getBlock/func_177230_c"),"getRegistryName").toString();}
 public static boolean is(Object o,String name){return type(name).isInstance(o);}
 public static IllegalStateException fail(String n,Throwable e){return new IllegalStateException("Legacy adapter: "+n,e);}
}
