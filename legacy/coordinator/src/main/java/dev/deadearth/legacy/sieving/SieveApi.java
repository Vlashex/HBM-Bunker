package dev.deadearth.legacy.sieving;
import dev.deadearth.legacy.Reflect;
import java.util.*;
/** Calls the same native reward registry as ordinary Ex Nihilo, including additive HBM providers. */
final class SieveApi {
 private SieveApi(){}
 static Object registry(){return Reflect.get(Reflect.type("exnihilocreatio.registries.manager.ExNihiloRegistryManager"),"SIEVE_REGISTRY");}
 static Object rawState(String token){String[] parts=token.split("#");String id=parts[0];int meta=parts.length==2?Integer.parseInt(parts[1]):0;Object b=Reflect.registry("BLOCKS",id);if(b==null)throw new IllegalStateException("Missing sieve raw block "+id);return Reflect.call(b,"getStateFromMeta/func_176203_a",meta);}
 static boolean siftable(String token){String[] parts=token.split("#");Object stack=Reflect.make("net.minecraft.item.ItemStack",Reflect.registry("ITEMS",parts[0]),1,parts.length==2?Integer.parseInt(parts[1]):0);return (Boolean)Reflect.call(registry(),"canBeSifted",stack);}
 static boolean eligible(String id,int grade){String[] parts=id.split("#");Object stack=Reflect.make("net.minecraft.item.ItemStack",Reflect.registry("ITEMS",parts[0]),1,parts.length==2?Integer.parseInt(parts[1]):0);if(!(Boolean)Reflect.call(registry(),"canBeSifted",stack))return false;for(Object drop:(List<?>)Reflect.call(registry(),"getDrops",stack))if((Boolean)Reflect.call(Reflect.type("exnihilocreatio.registries.registries.SieveRegistry"),"canSieve",((Number)Reflect.call(drop,"getMeshLevel")).intValue(),grade))return true;return false;}
 static long rollSeed(long seed,int cursor){long z=seed+0x9E3779B97F4A7C15L*(cursor+1L);z=(z^(z>>>30))*0xBF58476D1CE4E5B9L;z=(z^(z>>>27))*0x94D049BB133111EBL;return z^(z>>>31);}
 static List<Object> draw(String raw,int grade,long seed,int cursor){List<?> nativeDrops=(List<?>)Reflect.call(registry(),"getRewardDrops",new Random(rollSeed(seed,cursor)),rawState(raw),grade,0);if(nativeDrops==null||nativeDrops.size()>256)throw new IllegalStateException("Native sieve reward exceeds bounded draw");List<Object> out=new ArrayList<Object>();for(Object s:nativeDrops)if(!SieveStacks.empty(s)){int n=SieveStacks.count(s);if(n<1||n>64||n>SieveStacks.max(s))throw new IllegalStateException("Invalid native sieve reward count");out.add(SieveStacks.copy(s));}return out;}
}
