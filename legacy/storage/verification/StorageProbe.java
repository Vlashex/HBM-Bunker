package dev.deadearth.storageprobe;

import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLServerStartedEvent;
import com.google.gson.Gson;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;

@Mod(modid="deadearth_storage_probe", name="Dead Earth Storage Probe", version="0.1.0", acceptableRemoteVersions="*")
public class StorageProbe {
    private final Map<String,Object> report = new LinkedHashMap<String,Object>();
    private Class<?> itemClass, stackClass, posClass;
    private Object itemRegistry, blockRegistry;

    private static Object invoke(Object object, String names, Object... args) throws Exception {
        Class<?> owner=object instanceof Class ? (Class<?>)object : object.getClass();
        if(names.startsWith("getDefaultState"))owner=Class.forName("net.minecraft.block.Block");
        for(String name:names.split("/")) for(Method method:owner.getMethods()) {
            if(!method.getName().equals(name)||method.getParameterCount()!=args.length) continue;
            Class<?>[] types=method.getParameterTypes(); boolean ok=true;
            for(int i=0;i<args.length;i++) {
                if(args[i]==null) {if(types[i].isPrimitive())ok=false;continue;}
                Class<?> t=types[i];
                if(t==int.class)t=Integer.class; if(t==boolean.class)t=Boolean.class;
                if(!t.isInstance(args[i]))ok=false;
            }
            if(ok) {method.setAccessible(true); return method.invoke(object instanceof Class?null:object,args);}
        }
        throw new NoSuchMethodException(owner.getName()+" "+names+"/"+args.length);
    }
    private static Object registry(Object registry,String id)throws Exception {
        Object rl=Class.forName("net.minecraft.util.ResourceLocation").getConstructor(String.class).newInstance(id);
        return invoke(registry,"getValue",rl);
    }
    private Object stack(String full,int count)throws Exception {
        String[] p=full.split(":");String id=p[0]+":"+p[1];int meta=p.length>2?Integer.parseInt(p[2]):0;
        Object item=registry(itemRegistry,id);
        if(item==null||id.equals("minecraft:air"))throw new IllegalStateException("Missing item "+id);
        return stackClass.getConstructor(itemClass,int.class,int.class).newInstance(item,count,meta);
    }
    private int count(Object stack)throws Exception {return ((Number)invoke(stack,"getCount/func_190916_E")).intValue();}
    private void require(boolean ok,String name){if(!ok)throw new IllegalStateException(name);report.put(name,true);}
    private Object position(int x,int y,int z)throws Exception{return posClass.getConstructor(int.class,int.class,int.class).newInstance(x,y,z);}
    private Object place(Object world,int x,int y,int z,String id)throws Exception {
        Object b=registry(blockRegistry,id);if(b==null)throw new IllegalStateException("Missing block "+id);
        Object pos=position(x,y,z);Object state=invoke(b,"getDefaultState/func_176223_P");
        invoke(world,"setBlockState/func_180501_a",pos,state,3);
        return invoke(world,"getTileEntity/func_175625_s",pos);
    }
    private int total(Object master)throws Exception {int n=0;for(Object s:(List<?>)invoke(master,"getStacks"))n+=count(s);return n;}

    @Mod.EventHandler public void started(FMLServerStartedEvent event) {
        try {
            Class<?> fr=Class.forName("net.minecraftforge.fml.common.registry.ForgeRegistries");
            itemRegistry=fr.getField("ITEMS").get(null); blockRegistry=fr.getField("BLOCKS").get(null);
            Object recipes=fr.getField("RECIPES").get(null);
            itemClass=Class.forName("net.minecraft.item.Item");stackClass=Class.forName("net.minecraft.item.ItemStack");posClass=Class.forName("net.minecraft.util.math.BlockPos");
            Class<?> invClass=Class.forName("net.minecraft.inventory.InventoryCrafting");
            Class<?> containerClass=Class.forName("net.minecraft.inventory.Container");
            Object common=invoke(Class.forName("net.minecraftforge.fml.common.FMLCommonHandler"),"instance");
            Object server=invoke(common,"getMinecraftServerInstance");Object world=invoke(server,"getWorld/func_71218_a",0);
            Properties fixture=new Properties();fixture.load(StorageProbe.class.getResourceAsStream("/fixture.properties"));
            for(String name:fixture.getProperty("names").split(",")) {
                Object expected=stack(fixture.getProperty(name+".output"),Integer.parseInt(fixture.getProperty(name+".count")));
                Object inv=invClass.getConstructor(containerClass,int.class,int.class).newInstance(null,3,3);
                List<Object> matrix=null;
                for(Field f:invClass.getDeclaredFields())if(List.class.isAssignableFrom(f.getType())){f.setAccessible(true);matrix=(List<Object>)f.get(inv);break;}
                if(matrix==null)throw new IllegalStateException("No crafting matrix");
                String[] ingredients=fixture.getProperty(name+".grid").split(",",-1);
                for(int i=0;i<9;i++) if(!ingredients[i].isEmpty())matrix.set(i,stack(ingredients[i],1));
                int outputRecipes=0;Object match=null;
                for(Object recipe:(Collection<?>)invoke(recipes,"getValuesCollection")) {
                    Object result=invoke(recipe,"getRecipeOutput/func_77571_b");
                    if(Boolean.TRUE.equals(invoke(result,"isItemEqual/func_77969_a",expected))) {
                        outputRecipes++;
                        if(Boolean.TRUE.equals(invoke(recipe,"matches/func_77569_a",inv,world)))match=recipe;
                    }
                }
                require(outputRecipes==1,"single_recipe_"+name);
                require(match!=null,"native_match_"+name);
                Object result=invoke(match,"getCraftingResult/func_77572_b",inv);
                require(count(result)==count(expected),"native_output_count_"+name);
                for(int i=0;i<9;i++)if(ingredients[i].equals("hbm:plate_iron")) {
                    Object old=matrix.get(i);matrix.set(i,stack("minecraft:iron_ingot",1));
                    require(!Boolean.TRUE.equals(invoke(match,"matches/func_77569_a",inv,world)),"requires_HBM_plate_"+name);matrix.set(i,old);break;
                }
            }
            // A real master/request table/two separately connected vanilla chests.
            Object master=place(world,8,65,8,"storagenetwork:master");
            place(world,9,65,8,"storagenetwork:kabel");
            Object request=place(world,8,65,9,"storagenetwork:request");
            Object chest1=place(world,10,64,8,"minecraft:chest"),chest2=place(world,9,64,9,"minecraft:chest");
            Object link1=place(world,10,65,8,"storagenetwork:storage_kabel"),link2=place(world,9,65,9,"storagenetwork:storage_kabel");
            Object down=Enum.valueOf((Class)Class.forName("net.minecraft.util.EnumFacing"),"DOWN");
            invoke(link1,"setDirection",down);invoke(link2,"setDirection",down);
            invoke(chest1,"setInventorySlotContents/func_70299_a",0,stack("minecraft:iron_ingot",19));
            invoke(chest2,"setInventorySlotContents/func_70299_a",0,stack("minecraft:iron_ingot",13));
            invoke(master,"refreshNetwork");invoke(master,"update/func_73660_a");
            require(total(master)==32,"network_aggregates_two_chests");
            Class<?> matcherClass=Class.forName("mrriegel.storagenetwork.data.ItemStackMatcher");
            Object matcher=matcherClass.getConstructor(stackClass).newInstance(stack("minecraft:iron_ingot",1));
            Object simulated=invoke(master,"request",matcher,7,true);
            require(count(simulated)==7&&total(master)==32,"simulate_extract_is_read_only");
            Object taken=invoke(master,"request",matcher,7,false);
            require(count(taken)==7&&total(master)==25,"extract_exactly_once");
            int remaining=((Number)invoke(master,"insertStack",taken,false)).intValue();
            require(remaining==0&&total(master)==32,"return_without_duplication");
            // Request table points at this actual network; same backend used by the UI.
            Object masterPos=invoke(request,"getMaster");
            Object fromRequest=invoke(masterPos,"getTileEntity",master.getClass());
            require(fromRequest==master,"request_table_resolves_master");
            Object tag=Class.forName("net.minecraft.nbt.NBTTagCompound").newInstance();
            invoke(master,"writeToNBT/func_189515_b",tag);
            invoke(master,"readFromNBT/func_145839_a",tag);invoke(master,"refreshNetwork");invoke(master,"update/func_73660_a");
            require(total(master)==32,"network_NBT_round_trip");
            // Removing one connection does not destroy its chest items.
            int firstBefore=count(invoke(chest1,"getStackInSlot/func_70301_a",0));
            int secondBefore=count(invoke(chest2,"getStackInSlot/func_70301_a",0));
            place(world,10,65,8,"minecraft:air");invoke(master,"refreshNetwork");invoke(master,"update/func_73660_a");
            require(total(master)==secondBefore,"disconnected_chest_excluded");
            require(count(invoke(chest1,"getStackInSlot/func_70301_a",0))==firstBefore,"disconnected_items_preserved");
            report.put("passed",true);
        }catch(Throwable error){report.put("passed",false);report.put("error",error.toString());error.printStackTrace();}
        try {Files.write(Paths.get("storage-probe.json"),new Gson().toJson(report).getBytes("UTF-8"));}catch(Exception e){e.printStackTrace();}
    }
}
