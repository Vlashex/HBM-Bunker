package dev.deadearth.legacy;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
/** Narrow 1.12.2 SRG boundary. Domain code never depends on obfuscated Minecraft types. */
public final class Reflect {
 private Reflect(){}
 private static final ConcurrentMap<String,Class<?>> TYPES=new ConcurrentHashMap<String,Class<?>>();
 private static final ConcurrentMap<Key,Method> METHODS=new ConcurrentHashMap<Key,Method>();
 private static final ConcurrentMap<Key,Constructor<?>> CONSTRUCTORS=new ConcurrentHashMap<Key,Constructor<?>>();
 private static final ConcurrentMap<Key,Field> FIELDS=new ConcurrentHashMap<Key,Field>();
 private static final AtomicLong HITS=new AtomicLong(),MISSES=new AtomicLong();
 private static final class Key {
  final Class<?> owner;final String names;final List<Class<?>> arguments;final int hash;
  Key(Class<?> owner,String names,Object[] args){this.owner=owner;this.names=names;List<Class<?>> types=new ArrayList<Class<?>>(args.length);for(Object arg:args)types.add(arg==null?Void.class:arg.getClass());arguments=Collections.unmodifiableList(types);hash=Objects.hash(owner,names,arguments);}
  public int hashCode(){return hash;}public boolean equals(Object other){if(!(other instanceof Key))return false;Key key=(Key)other;return owner==key.owner&&names.equals(key.names)&&arguments.equals(key.arguments);}
 }
 private static <T> void cache(ConcurrentMap<Key,T> map,Key key,T value){if(map.size()>=8192)map.clear();map.putIfAbsent(key,value);}
 public static long[] lookupStatistics(){return new long[]{HITS.get(),MISSES.get(),METHODS.size(),FIELDS.size(),CONSTRUCTORS.size()};}
 public static Class<?> type(String n){Class<?> known=TYPES.get(n);if(known!=null)return known;try{Class<?> found=Class.forName(n);TYPES.putIfAbsent(n,found);return found;}catch(Exception e){throw fail(n,e);}}
 public static Object make(String n,Object...args){Class<?> owner=type(n);Key key=new Key(owner,"<init>",args);Constructor<?> constructor=CONSTRUCTORS.get(key);
  if(constructor==null){MISSES.incrementAndGet();for(Constructor<?> candidate:owner.getConstructors())if(matches(candidate.getParameterTypes(),args)){constructor=candidate;cache(CONSTRUCTORS,key,candidate);break;}}else HITS.incrementAndGet();
  if(constructor==null)throw fail(n,null);try{return constructor.newInstance(args);}catch(InvocationTargetException e){throw fail(n,e.getCause());}catch(Exception e){throw fail(n,e);}
 }
 private static Field field(Object o,String names){Class<?> owner=o instanceof Class?(Class<?>)o:o.getClass();Key key=new Key(owner,names,new Object[0]);Field field=FIELDS.get(key);if(field!=null){HITS.incrementAndGet();return field;}MISSES.incrementAndGet();
  for(String n:ordered(names))for(Class<?> current=owner;current!=null;current=current.getSuperclass())try{field=current.getDeclaredField(n);field.setAccessible(true);cache(FIELDS,key,field);return field;}catch(NoSuchFieldException missing){}catch(Exception e){throw fail(n,e);}throw fail(owner.getName()+"."+names,null);
 }
 public static Object get(Object o,String names){try{return field(o,names).get(o instanceof Class?null:o);}catch(Exception e){throw fail(names,e);}}
 public static void set(Object o,String names,Object value){try{field(o,names).set(o instanceof Class?null:o,value);}catch(Exception e){throw fail(names,e);}}
 public static Object call(Object o,String names,Object...args){Class<?> owner=o instanceof Class?(Class<?>)o:o.getClass();
  // HBM block subclasses may expose unannotated client-only signatures. All public
  // vanilla/Forge Block APIs resolve on the safe owner; Method.invoke still dispatches overrides.
  Class<?> vanillaBlockOwner=null;for(Class<?> base=owner;base!=null;base=base.getSuperclass()){if(vanillaBlockOwner==null&&base.getName().startsWith("net.minecraft.block."))vanillaBlockOwner=base;if(base.getName().equals("net.minecraft.block.Block")){owner=vanillaBlockOwner;break;}}
  if(names.contains("func_176223_P")||names.contains("func_149712_f"))owner=type("net.minecraft.block.Block");
  if(names.equals("getRegistryName"))owner=type("net.minecraftforge.registries.IForgeRegistryEntry");
  Key key=new Key(owner,names,args);Method method=METHODS.get(key);
  if(method==null){MISSES.incrementAndGet();outer:for(String n:ordered(names))for(Method candidate:owner.getMethods())if(candidate.getName().equals(n)&&matches(candidate.getParameterTypes(),args)){method=candidate;method.setAccessible(true);cache(METHODS,key,method);break outer;}}else HITS.incrementAndGet();
  if(method==null)throw fail(owner.getName()+"."+names+"/"+args.length,null);
  try{return method.invoke(o instanceof Class?null:o,args);}catch(InvocationTargetException e){throw fail(names,e.getCause());}catch(Exception e){throw fail(names,e);}
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
