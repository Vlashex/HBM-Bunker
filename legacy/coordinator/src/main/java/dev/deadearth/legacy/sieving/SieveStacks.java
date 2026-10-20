package dev.deadearth.legacy.sieving;
import dev.deadearth.legacy.Reflect;
import java.util.*;
final class SieveStacks {
 private SieveStacks(){}
 static Object empty(){return Reflect.get(Reflect.type("net.minecraft.item.ItemStack"),"EMPTY/field_190927_a");}
 static boolean empty(Object s){return s==null||(Boolean)Reflect.call(s,"isEmpty/func_190926_b");}
 static int count(Object s){return empty(s)?0:((Number)Reflect.call(s,"getCount/func_190916_E")).intValue();}
 static Object copy(Object s){return empty(s)?empty():Reflect.call(s,"copy/func_77946_l");}
 static Object count(Object s,int n){if(n<=0)return empty();Object c=copy(s);Reflect.call(c,"setCount/func_190920_e",n);return c;}
 static int max(Object s){return empty(s)?64:Math.min(64,((Number)Reflect.call(s,"getMaxStackSize/func_77976_d")).intValue());}
 static boolean same(Object a,Object b){return !empty(a)&&!empty(b)&&(Boolean)Reflect.call(Reflect.type("net.minecraft.item.ItemStack"),"areItemsEqual/func_179545_c",a,b)&&(Boolean)Reflect.call(Reflect.type("net.minecraft.item.ItemStack"),"areItemStackTagsEqual/func_77970_a",a,b);}
 static String id(Object s){return empty(s)?"":String.valueOf(Reflect.call(Reflect.call(s,"getItem/func_77973_b"),"getRegistryName"));}
 static Object make(String id,int n){return Reflect.make("net.minecraft.item.ItemStack",Reflect.registry("ITEMS",id),n,0);}
 static Object write(Object s){Object n=Reflect.make("net.minecraft.nbt.NBTTagCompound");if(!empty(s))Reflect.call(s,"writeToNBT/func_77955_b",n);return n;}
 static Object read(Object n){return Reflect.make("net.minecraft.item.ItemStack",n);}
 static List<Object> merge(List<Object> old,List<Object> additions){List<Object> out=new ArrayList<Object>();for(Object s:old)out.add(copy(s));for(Object s:additions){if(empty(s))continue;int left=count(s);for(int i=0;i<out.size()&&left>0;i++)if(same(s,out.get(i))){int n=Math.min(left,max(s)-count(out.get(i)));if(n>0){out.set(i,count(s,count(out.get(i))+n));left-=n;}}for(int i=0;i<out.size()&&left>0;i++)if(empty(out.get(i))){int n=Math.min(left,max(s));out.set(i,count(s,n));left-=n;}if(left>0)return null;}return out;}
}
