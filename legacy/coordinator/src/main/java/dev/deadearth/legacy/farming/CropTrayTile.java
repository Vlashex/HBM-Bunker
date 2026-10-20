package dev.deadearth.legacy.farming;

import dev.deadearth.legacy.Reflect;
import java.lang.reflect.Proxy;
import java.util.*;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ITickable;
import net.minecraft.util.EnumFacing;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraftforge.common.capabilities.Capability;

/** One retained native seed + soil, nine real output slots, durable pending harvest; all world work is server-side. */
public final class CropTrayTile extends TileEntity implements ITickable {
    private Object outputs,outputView;
    private Object seed=FarmingStacks.empty(),soil=FarmingStacks.empty();
    private List<Object> pending=new ArrayList<Object>();
    private int progress,poll;
    private boolean light,drained;

    private Object outputs() {
        if(outputs==null)outputs=Reflect.make("net.minecraftforge.items.ItemStackHandler",LegacyFarming.settings.outputSlots);
        return outputs;
    }
    private Object outputView() {
        if(outputView==null) {
            Class<?> type=Reflect.type("net.minecraftforge.items.IItemHandler");
            outputView=Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},(proxy,method,args)->{
                String name=method.getName();
                if(name.equals("insertItem"))return args[1]; // Automation sees extraction-only real output inventory.
                if(name.equals("extractItem")) {
                    Object result=Reflect.call(outputs(),name,args);
                    if(!(Boolean)args[2]&&FarmingStacks.count(result)>0)dirty();return result;
                }
                if(name.equals("toString"))return "DeadEarthCropTrayOutputs";
                if(name.equals("hashCode"))return System.identityHashCode(proxy);
                if(name.equals("equals"))return proxy==args[0];
                return Reflect.call(outputs(),name,args==null?new Object[0]:args);
            });
        }
        return outputView;
    }
    private Object capability() {return Reflect.get(Reflect.type("net.minecraftforge.items.CapabilityItemHandler"),"ITEM_HANDLER_CAPABILITY");}
    public boolean hasCapability(Capability<?> capability,EnumFacing face){return capability==capability()||super.hasCapability(capability,face);}
    @SuppressWarnings("unchecked") public <T>T getCapability(Capability<T> capability,EnumFacing face){return capability==capability()?(T)outputView():super.getCapability(capability,face);}
    private Object world(){return Reflect.call(this,"getWorld/func_145831_w");}
    private Object position(){return Reflect.call(this,"getPos/func_174877_v");}
    private void dirty(){Reflect.call(this,"markDirty/func_70296_d");}

    public void func_73660_a() {
        Object world=world();if(world==null||(Boolean)Reflect.get(world,"isRemote/field_72995_K")||drained||!LegacyFarming.settings.enabled)return;
        if(!pending.isEmpty()) {
            if(++poll<LegacyFarming.settings.pollTicks)return;poll=0;
            LegacyFarming.request(world,this);return;
        }
        if(FarmingStacks.count(seed)==0||FarmingStacks.count(soil)==0)return;
        if(light&&progress<LegacyFarming.settings.cycleTicks) {progress++;if(progress%100==0)dirty();}
        if(++poll<LegacyFarming.settings.pollTicks)return;poll=0;
        Object at=position();
        light=!LegacyFarming.settings.requireLight||((Number)Reflect.call(world,"getLightFromNeighbors/func_175671_l",Reflect.pos(Reflect.x(at),Reflect.y(at)+1,Reflect.z(at)))).intValue()>=LegacyFarming.settings.minimumLight;
        if(light&&progress>=LegacyFarming.settings.cycleTicks)LegacyFarming.request(world,this);
    }
    boolean loadedIn(Object world) {
        if(drained||world()!=world||(Boolean)Reflect.call(this,"isInvalid/func_145837_r"))return false;
        Object pos=position();return Reflect.loaded(world,pos)&&Reflect.call(world,"getTileEntity/func_175625_s",pos)==this;
    }
    void harvest(Object world) {
        if(!pending.isEmpty()){commitPending();return;}
        if(!light||progress<LegacyFarming.settings.cycleTicks||FarmingStacks.count(seed)==0||FarmingStacks.count(soil)==0)return;
        Object at=position();
        Object crop=crop(seed,world,at);if(crop==null)return;
        Object block=Reflect.call(crop,"getBlock/func_177230_c");
        int maximum=((Number)Reflect.call(block,"getMaxAge/func_185526_g")).intValue();
        Object mature=Reflect.call(block,"withAge/func_185528_e",maximum);
        Object drops=Reflect.call(Reflect.type("net.minecraft.util.NonNullList"),"create/func_191196_a");
        Reflect.call(block,"getDrops",drops,world,at,mature,0);
        if(((List<?>)drops).size()>LegacyFarming.settings.maxNativeDrops)throw new IllegalStateException("Crop returned excessive native drops");
        List<Object> actual=new ArrayList<Object>();
        for(Object stack:(List<?>)drops)if(FarmingStacks.count(stack)>0)actual.add(FarmingStacks.copy(stack));
        pending=InventoryPacking.reserveSeed(actual,seed,FarmingStacks.OPS);
        // Native RNG is sampled exactly once. A full tray retains this harvest instead of rerolling it.
        dirty();
        if(pending.isEmpty()){progress=0;dirty();}else commitPending();
    }
    private void commitPending() {
        List<Object> planned=InventoryPacking.all(FarmingStacks.slots(outputs()),pending,FarmingStacks.OPS);
        if(planned==null)return;
        FarmingStacks.commit(outputs(),planned);pending.clear();progress=0;dirty();
    }
    static Object crop(Object seed,Object world,Object at) {
        if(FarmingStacks.count(seed)==0)return null;
        Object item=Reflect.call(seed,"getItem/func_77973_b");
        if(!Reflect.is(item,"net.minecraftforge.common.IPlantable"))return null;
        Object state=Reflect.call(item,"getPlant",world,at);
        Object block=Reflect.call(state,"getBlock/func_177230_c");
        // These native crop families cover vanilla food, XL Food and Mystical Agriculture.
        // Trees, cactus and stems require different physical harvest rules and are deliberately refused.
        return Reflect.is(block,"net.minecraft.block.BlockCrops")?state:null;
    }
    public void interact(Object player,Object hand) {
        if(drained)return;
        Object held=Reflect.call(player,"getHeldItem/func_184586_b",hand);
        if(FarmingStacks.count(held)==0) {
            if((Boolean)Reflect.call(player,"isSneaking/func_70093_af"))retrieve(player);
            else collect(player);
            status(player);return;
        }
        if(FarmingStacks.count(soil)==0&&FarmingStacks.id(held).equals("minecraft:dirt")&&((Number)Reflect.call(held,"getMetadata/func_77960_j")).intValue()==0) {
            soil=FarmingStacks.one(held);Reflect.call(held,"shrink/func_190918_g",1);dirty();status(player);return;
        }
        if(FarmingStacks.count(seed)==0&&FarmingStacks.count(soil)>0&&crop(held,world(),position())!=null) {
            seed=FarmingStacks.one(held);Reflect.call(held,"shrink/func_190918_g",1);progress=0;light=false;dirty();status(player);return;
        }
        LegacyFarming.message(player,"dead_earth.farming.instructions");
    }
    private List<Object> playerSlots(Object inventory) {
        List<Object> result=new ArrayList<Object>();for(int i=0;i<36;i++)result.add(FarmingStacks.copy(Reflect.call(inventory,"getStackInSlot/func_70301_a",i)));return result;
    }
    private void playerCommit(Object inventory,List<Object> planned) {
        for(int i=0;i<36;i++)Reflect.call(inventory,"setInventorySlotContents/func_70299_a",i,planned.get(i));
        Reflect.call(inventory,"markDirty/func_70296_d");
    }
    private void retrieve(Object player) {
        Object inventory=Reflect.get(player,"inventory/field_71071_by");
        List<Object> additions=new ArrayList<Object>();if(FarmingStacks.count(seed)>0)additions.add(seed);if(FarmingStacks.count(soil)>0)additions.add(soil);
        List<Object> planned=InventoryPacking.all(playerSlots(inventory),additions,FarmingStacks.OPS);
        if(planned==null){LegacyFarming.message(player,"dead_earth.farming.inventory_full");return;}
        playerCommit(inventory,planned);seed=FarmingStacks.empty();soil=FarmingStacks.empty();progress=0;light=false;dirty();
    }
    private void collect(Object player) {
        Object inventory=Reflect.get(player,"inventory/field_71071_by");List<Object> planned=playerSlots(inventory),nextOutput=new ArrayList<Object>();boolean changed=false;
        for(Object stack:FarmingStacks.slots(outputs())) {
            InventoryPacking.Result<Object> result=InventoryPacking.insert(planned,stack,FarmingStacks.OPS);
            if(FarmingStacks.count(result.remainder)!=FarmingStacks.count(stack))changed=true;
            planned=result.slots;nextOutput.add(result.remainder);
        }
        if(changed){playerCommit(inventory,planned);FarmingStacks.commit(outputs(),nextOutput);dirty();}
        else if(!pending.isEmpty())LegacyFarming.message(player,"dead_earth.farming.inventory_full");
    }
    private void status(Object player) {
        if(FarmingStacks.count(soil)==0){LegacyFarming.message(player,"dead_earth.farming.needs_soil");return;}
        if(FarmingStacks.count(seed)==0){LegacyFarming.message(player,"dead_earth.farming.needs_seed");return;}
        if(!pending.isEmpty()){LegacyFarming.message(player,"dead_earth.farming.output_full");return;}
        if(!light){LegacyFarming.message(player,"dead_earth.farming.needs_light");return;}
        LegacyFarming.message(player,"dead_earth.farming.growing",(LegacyFarming.settings.cycleTicks-progress+19)/20);
    }
    public void dropContents(Object world,Object at) {
        if(drained)return;drained=true;
        List<Object> all=FarmingStacks.slots(outputs());all.add(FarmingStacks.copy(seed));all.add(FarmingStacks.copy(soil));all.addAll(InventoryPacking.copy(pending,FarmingStacks.OPS));
        seed=FarmingStacks.empty();soil=FarmingStacks.empty();pending.clear();
        List<Object> empty=new ArrayList<Object>();for(int i=0;i<((Number)Reflect.call(outputs(),"getSlots")).intValue();i++)empty.add(FarmingStacks.empty());FarmingStacks.commit(outputs(),empty);dirty();
        for(Object stack:all)if(FarmingStacks.count(stack)>0) {
            Object entity=Reflect.make("net.minecraft.entity.item.EntityItem",world,Reflect.x(at)+.5,Reflect.y(at)+.5,Reflect.z(at)+.5,stack);
            Reflect.call(world,"spawnEntity/func_72838_d",entity);
        }
    }
    public NBTTagCompound func_189515_b(NBTTagCompound tag) {
        super.func_189515_b(tag);Reflect.call(tag,"setInteger/func_74768_a","DeadEarthTraySchema",1);
        Reflect.call(tag,"setTag/func_74782_a","Seed",FarmingStacks.tag(seed));Reflect.call(tag,"setTag/func_74782_a","Soil",FarmingStacks.tag(soil));
        Reflect.call(tag,"setTag/func_74782_a","Output",Reflect.call(outputs(),"serializeNBT"));Reflect.call(tag,"setInteger/func_74768_a","Growth",progress);
        Object list=Reflect.make("net.minecraft.nbt.NBTTagList");for(Object stack:pending)Reflect.call(list,"appendTag/func_74742_a",FarmingStacks.tag(stack));
        Reflect.call(tag,"setTag/func_74782_a","Pending",list);return tag;
    }
    public void func_145839_a(NBTTagCompound tag) {
        super.func_145839_a(tag);int version=((Number)Reflect.call(tag,"getInteger/func_74762_e","DeadEarthTraySchema")).intValue();
        if(version!=1&&version!=0)throw new IllegalStateException("Unknown crop tray save schema");
        if(version==0)return;
        Object out=Reflect.call(tag,"getCompoundTag/func_74775_l","Output");int size=((Number)Reflect.call(out,"getInteger/func_74762_e","Size")).intValue();
        if(size<1||size>27)throw new IllegalStateException("Crop tray inventory size is invalid");
        outputs=Reflect.make("net.minecraftforge.items.ItemStackHandler",size);Reflect.call(outputs,"deserializeNBT",out);
        seed=FarmingStacks.fromTag(Reflect.call(tag,"getCompoundTag/func_74775_l","Seed"));soil=FarmingStacks.fromTag(Reflect.call(tag,"getCompoundTag/func_74775_l","Soil"));
        if(FarmingStacks.count(seed)>1||FarmingStacks.count(soil)>1)throw new IllegalStateException("Crop tray planted inputs are not single items");
        progress=Math.max(0,Math.min(72000,((Number)Reflect.call(tag,"getInteger/func_74762_e","Growth")).intValue()));
        Object list=Reflect.call(tag,"getTagList/func_150295_c","Pending",10);int count=((Number)Reflect.call(list,"tagCount/func_74745_c")).intValue();
        if(count>128)throw new IllegalStateException("Crop tray pending harvest is excessive");pending.clear();
        for(int i=0;i<count;i++){Object stack=FarmingStacks.fromTag(Reflect.call(list,"getCompoundTagAt/func_150305_b",i));if(FarmingStacks.count(stack)>0)pending.add(stack);}
        light=false;drained=false;
    }
    public NBTTagCompound func_189517_E_(){return func_189515_b((NBTTagCompound)Reflect.make("net.minecraft.nbt.NBTTagCompound"));}
}
