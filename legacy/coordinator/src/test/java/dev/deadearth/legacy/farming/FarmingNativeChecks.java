package dev.deadearth.legacy.farming;

import dev.deadearth.legacy.Reflect;
import java.util.*;

/** Call only from the final, isolated full-pack probe with a real loaded world, test player and tray position. */
public final class FarmingNativeChecks {
    private FarmingNativeChecks() {}
    private static void require(boolean b,String why){if(!b)throw new AssertionError("Native farming: "+why);}
    public static void nativeCrop(Object world,Object pos,String itemId) {
        Object seed=Reflect.make("net.minecraft.item.ItemStack",Reflect.registry("ITEMS",itemId),1,0);
        Object initial=CropTrayTile.crop(seed,world,pos);require(initial!=null,"native IPlantable crop "+itemId);
        Object block=Reflect.call(initial,"getBlock/func_177230_c");
        Object mature=Reflect.call(block,"withAge/func_185528_e",Reflect.call(block,"getMaxAge/func_185526_g"));
        Object drops=Reflect.call(Reflect.type("net.minecraft.util.NonNullList"),"create/func_191196_a");
        Reflect.call(block,"getDrops",drops,world,pos,mature,0);
        require(!((List<?>)drops).isEmpty(),"native mature drops "+itemId);
        List<Object> all=new ArrayList<Object>();for(Object stack:(List<?>)drops)all.add(stack);
        List<Object> after=InventoryPacking.reserveSeed(all,seed,FarmingStacks.OPS);
        int before=0,left=0;for(Object stack:all)before+=FarmingStacks.count(stack);for(Object stack:after)left+=FarmingStacks.count(stack);
        require(before-left>=0&&before-left<=1,"reserve at most one native planting seed");
    }
    public static void persistedTray(Object world,Object pos,Object player,String seedId) {
        require(Reflect.loaded(world,pos),"caller supplies an already loaded test position");
        Object block=Reflect.registry("BLOCKS","dead_earth:crop_tray");
        Reflect.call(world,"setBlockState/func_180501_a",pos,Reflect.call(block,"getDefaultState/func_176223_P"),3);
        CropTrayTile tile=(CropTrayTile)Reflect.call(world,"getTileEntity/func_175625_s",pos);
        Object hand=Reflect.get(Reflect.type("net.minecraft.util.EnumHand"),"MAIN_HAND");
        Object dirt=Reflect.make("net.minecraft.item.ItemStack",Reflect.registry("ITEMS","minecraft:dirt"),1,0);
        Reflect.call(player,"setHeldItem/func_184611_a",hand,dirt);tile.interact(player,hand);require(FarmingStacks.count(dirt)==0,"one soil consumed");
        Object seed=Reflect.make("net.minecraft.item.ItemStack",Reflect.registry("ITEMS",seedId),1,0);
        Reflect.call(player,"setHeldItem/func_184611_a",hand,seed);tile.interact(player,hand);require(FarmingStacks.count(seed)==0,"one seed inserted");
        Object nbt=tile.func_189515_b((net.minecraft.nbt.NBTTagCompound)Reflect.make("net.minecraft.nbt.NBTTagCompound"));
        Reflect.call(nbt,"setInteger/func_74768_a","Growth",LegacyFarming.settings.cycleTicks);
        tile.func_145839_a((net.minecraft.nbt.NBTTagCompound)nbt);
        boolean requireLight=LegacyFarming.settings.requireLight;
        try {
            LegacyFarming.settings.requireLight=false;
            for(int i=0;i<=LegacyFarming.settings.pollTicks;i++)tile.func_73660_a();
            tile.harvest(world);
        } finally {LegacyFarming.settings.requireLight=requireLight;}
        Object cap=Reflect.get(Reflect.type("net.minecraftforge.items.CapabilityItemHandler"),"ITEM_HANDLER_CAPABILITY");
        Object handler=Reflect.call(tile,"getCapability",cap,null);
        int total=0;for(Object stack:FarmingStacks.slots(handler))total+=FarmingStacks.count(stack);
        require(total>0,"mature native harvest entered real inventory");
        Object rejected=Reflect.make("net.minecraft.item.ItemStack",Reflect.registry("ITEMS","minecraft:dirt"),1,0);
        require(FarmingStacks.count(Reflect.call(handler,"insertItem",0,rejected,false))==1,"automation cannot insert into outputs");
        Object saved=tile.func_189515_b((net.minecraft.nbt.NBTTagCompound)Reflect.make("net.minecraft.nbt.NBTTagCompound"));
        CropTrayTile restored=new CropTrayTile();restored.func_145839_a((net.minecraft.nbt.NBTTagCompound)saved);
        Object savedAgain=restored.func_189515_b((net.minecraft.nbt.NBTTagCompound)Reflect.make("net.minecraft.nbt.NBTTagCompound"));
        for(String key:new String[]{"Seed","Soil","Output"})require(Reflect.call(saved,"getCompoundTag/func_74775_l",key).equals(Reflect.call(savedAgain,"getCompoundTag/func_74775_l",key)),"NBT round trip "+key);
    }
}
