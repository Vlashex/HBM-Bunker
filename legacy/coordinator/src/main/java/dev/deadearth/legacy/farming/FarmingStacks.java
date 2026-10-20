package dev.deadearth.legacy.farming;

import dev.deadearth.legacy.Reflect;
import java.util.*;

final class FarmingStacks {
    private FarmingStacks() {}
    static Object empty(){return Reflect.get(Reflect.type("net.minecraft.item.ItemStack"),"EMPTY/field_190927_a");}
    static final InventoryPacking.Ops<Object> OPS=new InventoryPacking.Ops<Object>() {
        public Object copy(Object a){return count(a)==0?empty():Reflect.call(a,"copy/func_77946_l");}
        public int count(Object a){return a==null||(Boolean)Reflect.call(a,"isEmpty/func_190926_b")?0:((Number)Reflect.call(a,"getCount/func_190916_E")).intValue();}
        public void count(Object a,int n){if(count(a)>0)Reflect.call(a,"setCount/func_190920_e",n);}
        public int limit(Object a){return ((Number)Reflect.call(a,"getMaxStackSize/func_77976_d")).intValue();}
        public boolean same(Object a,Object b){return (Boolean)Reflect.call(Reflect.type("net.minecraftforge.items.ItemHandlerHelper"),"canItemStacksStack",a,b);}
        public boolean sameSeed(Object a,Object b){return Reflect.call(a,"getItem/func_77973_b")==Reflect.call(b,"getItem/func_77973_b")&&Reflect.call(a,"getMetadata/func_77960_j").equals(Reflect.call(b,"getMetadata/func_77960_j"));}
        public Object empty(){return FarmingStacks.empty();}
    };
    static Object copy(Object a){return OPS.copy(a);}static int count(Object a){return OPS.count(a);}
    static List<Object> slots(Object handler){List<Object> out=new ArrayList<Object>();int n=((Number)Reflect.call(handler,"getSlots")).intValue();for(int i=0;i<n;i++)out.add(copy(Reflect.call(handler,"getStackInSlot",i)));return out;}
    static void commit(Object handler,List<Object> slots){for(int i=0;i<slots.size();i++)Reflect.call(handler,"setStackInSlot",i,slots.get(i));}
    static Object one(Object stack){Object c=copy(stack);OPS.count(c,1);return c;}
    static String id(Object stack){return Reflect.call(Reflect.call(stack,"getItem/func_77973_b"),"getRegistryName").toString();}
    static Object tag(Object stack){Object tag=Reflect.make("net.minecraft.nbt.NBTTagCompound");return Reflect.call(stack,"writeToNBT/func_77955_b",tag);}
    static Object fromTag(Object tag){return Reflect.make("net.minecraft.item.ItemStack",tag);}
}
