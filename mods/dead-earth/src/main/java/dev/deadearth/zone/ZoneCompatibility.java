package dev.deadearth.zone;

import static dev.deadearth.config.DeadEarthConfig.*;
import java.lang.reflect.*;
import java.util.*;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.*;

/** Version-pinned, read-only adapters. No network aggregate, extraction, tick or loot generation. */
public final class ZoneCompatibility {
    public record Stock(ItemStack item,long amount) {}
    public record Cell(List<Stock> stock,boolean unknown) {}
    public record ContainerView(Container inventory,int slots,boolean unknown) {}
    public record Turret(String id,int rank,ZoneScore.Defense state,String reason) {}
    public record Price(long unit,boolean unknown) {}
    private static final Set<String> CELLS=Set.of("ae2:item_storage_cell_1k","ae2:item_storage_cell_4k","ae2:item_storage_cell_16k","ae2:item_storage_cell_64k","ae2:item_storage_cell_256k");
    private static final ClassValue<Map<String,Method>> METHODS=new ClassValue<>(){protected Map<String,Method> computeValue(Class<?> type){return new HashMap<>();}};
    private static Object emcProxy;private static Method emcValue,emcReadiness;
    private ZoneCompatibility() {}
    private static Object call(Object target,String name) throws ReflectiveOperationException {
        var methods=METHODS.get(target.getClass());var method=methods.get(name);
        if(method==null){method=target.getClass().getMethod(name);methods.put(name,method);}return method.invoke(target);
    }
    private static Object slot(Object target,String name,int index) throws ReflectiveOperationException {return target.getClass().getMethod(name,int.class).invoke(target,index);}
    private static boolean bool(Object target,String name) throws ReflectiveOperationException{return (Boolean)call(target,name);}
    private static long number(Object target,String name) throws ReflectiveOperationException{return ((Number)call(target,name)).longValue();}
    public static Price price(ItemStack stack) {
        if(stack.isEmpty())return new Price(0,false);
        try {
            if(emcReadiness==null)emcReadiness=Class.forName("moze_intel.projecte.emc.EMCMappingHandler").getMethod("getLoadIndex");
            if(((Number)emcReadiness.invoke(null)).intValue()<0)return new Price(0,true);
            if(emcProxy==null){dev.deadearth.compat.ServiceBootstrap.initialize("moze_intel.projecte.api.proxy.IEMCProxy");var api=Class.forName("moze_intel.projecte.api.proxy.IEMCProxy");emcProxy=api.getField("INSTANCE").get(null);emcValue=api.getMethod("getValue",ItemStack.class);}
            return new Price(Math.max(0,((Number)emcValue.invoke(emcProxy,stack.copyWithCount(1))).longValue()),false);
        } catch(ReflectiveOperationException|LinkageError|RuntimeException failure){return new Price(0,true);}
    }
    public static ContainerView container(BlockEntity be) {
        String id=BuiltInRegistries.BLOCK.getKey(be.getBlockState().getBlock()).toString();
        boolean supported=be instanceof ChestBlockEntity||be instanceof BarrelBlockEntity||be instanceof ShulkerBoxBlockEntity||id.startsWith("hbm:crate_");
        if(!supported||!(be instanceof Container container))return new ContainerView(null,0,false);
        if(deferredLoot(be)||container.getContainerSize()>4096)return new ContainerView(null,0,true);
        return new ContainerView(container,container.getContainerSize(),false);
    }
    public static boolean deferredLoot(BlockEntity be) {
        if(be instanceof RandomizableContainerBlockEntity random)return random.getLootTable()!=null;
        if(be.getClass().getName().startsWith("com.hbm."))try{return call(be,"getLootTable")!=null;}catch(ReflectiveOperationException failure){return true;}
        return false;
    }
    public static boolean me(BlockEntity be){String name=be.getClass().getName();return name.equals("appeng.blockentity.storage.DriveBlockEntity")||name.equals("appeng.blockentity.storage.MEChestBlockEntity");}
    public static int cellCount(BlockEntity be){return be.getClass().getName().endsWith("DriveBlockEntity")?10:1;}
    @SuppressWarnings("unchecked") public static Cell cell(BlockEntity be,int index) {
        try {
            ItemStack raw;
            if(be.getClass().getName().endsWith("DriveBlockEntity")){
                Object wrapper=call(be,"getInternalInventory");var field=wrapper.getClass().getDeclaredField("inv");field.setAccessible(true);
                raw=(ItemStack)slot(field.get(wrapper),"getStackInSlot",index);
            }else raw=(ItemStack)call(be,"getCell");
            if(raw.isEmpty())return new Cell(List.of(),false);
            if(raw.getCount()!=1||!CELLS.contains(BuiltInRegistries.ITEM.getKey(raw.getItem()).toString()))return new Cell(List.of(),true);
            Object original=slot(be,"getOriginalCellInventory",index);var result=new ArrayList<Stock>();var seen=new HashSet<Object>();
            if(original!=null) {
                if(!original.getClass().getName().equals("appeng.me.cells.BasicCellInventory")||number(original,"getStoredItemTypes")<0||number(original,"getStoredItemTypes")>63)return new Cell(List.of(),true);
                var counterType=Class.forName("appeng.api.stacks.KeyCounter");Object counter=counterType.getConstructor().newInstance();
                original.getClass().getMethod("getAvailableStacks",counterType).invoke(original,counter);
                if(number(counter,"size")>63)return new Cell(List.of(),true);
                for(Object entry:(Iterable<?>)counter){var pair=(it.unimi.dsi.fastutil.objects.Object2LongMap.Entry<?>)entry;addKey(result,seen,pair.getKey(),pair.getLongValue());}
            }else {
                var component=(DataComponentType<List<?>>)Class.forName("appeng.api.ids.AEComponents").getField("STORAGE_CELL_INV").get(null);
                var contents=raw.get(component);
                if(contents!=null){if(contents.size()>63)return new Cell(List.of(),true);for(Object entry:contents)addKey(result,seen,call(entry,"what"),number(entry,"amount"));}
            }
            return new Cell(List.copyOf(result),false);
        }catch(ReflectiveOperationException|RuntimeException|LinkageError failure){return new Cell(List.of(),true);}
    }
    private static void addKey(List<Stock> result,Set<Object> seen,Object key,long amount) throws ReflectiveOperationException {
        if(amount<=0||key==null||!key.getClass().getName().equals("appeng.api.stacks.AEItemKey")||!seen.add(key)||seen.size()>63)throw new IllegalArgumentException("Incompatible native item cell data");
        result.add(new Stock(((ItemStack)call(key,"toStack")).copyWithCount(1),amount));
    }
    private static int rank(String id){var list=SPEC.isLoaded()?ZONE_RANKS.get():ZONE_RANKS.getDefault();for(String entry:list){int split=entry.lastIndexOf('=');if(entry.substring(0,split).equals(id))return Integer.parseInt(entry.substring(split+1));}return -1;}
    public static Turret turret(BlockEntity be) {
        if(!be.getClass().getName().startsWith("com.hbm.tileentity.turret.BlockEntityTurret"))return null;
        String id=BuiltInRegistries.BLOCK.getKey(be.getBlockState().getBlock()).toString();int rank=rank(id);
        if(rank<0)return new Turret(id,0,ZoneScore.Defense.UNKNOWN,"unconfigured_rank");
        try {
            if(deferredLoot(be))return new Turret(id,rank,ZoneScore.Defense.UNKNOWN,"deferred_loot");
            boolean ammo;String type=be.getClass().getSimpleName();boolean enabled=bool(be,"isOn"),power=bool(be,"hasPower");
            if(type.endsWith("Damaged"))ammo=true;
            else if(type.equals("BlockEntityTurretMaxwell")){ammo=number(be,"getPower")>=Math.multiplyExact(11,number(be,"getConsumption"));power=ammo;}
            else if(type.equals("BlockEntityTurretHoward"))ammo=be.saveWithoutMetadata(be.getLevel().registryAccess()).getInt("loaded")>0||call(be,"getFirstConfigLoaded")!=null;
            else if(type.equals("BlockEntityTurretHIMARS"))ammo=bool(be,"hasAmmo")||number(be,"getSpareRocket")>=0;
            else if(type.equals("BlockEntityTurretArty"))ammo=!((ItemStack)call(be,"getShellLoaded")).isEmpty();
            else if(type.equals("BlockEntityTurretFritz")) {
                Object tank=be.getClass().getField("tank").get(be),fluid=call(tank,"getTankType");
                var traits=Class.forName("com.hbm.inventory.fluid.NTMFluidProperties").getMethod("hasTrait",net.minecraft.world.level.material.Fluid.class,Class.class);
                ammo=number(tank,"getFill")>=2&&fluid!=null&&(Boolean)traits.invoke(null,fluid,Class.forName("com.hbm.inventory.fluid.trait.FT_Flammable"))&&(Boolean)traits.invoke(null,fluid,Class.forName("com.hbm.inventory.fluid.trait.FluidTraitSimple$FT_Liquid"));
            }else ammo=call(be,"getFirstConfigLoaded")!=null;
            var state=!ammo?ZoneScore.Defense.EMPTY:enabled&&power?ZoneScore.Defense.ARMED:ZoneScore.Defense.DISABLED;
            return new Turret(id,rank,state,!ammo?"no_ammo_or_energy":!enabled?"switched_off":!power?"no_power":"equipped");
        }catch(ReflectiveOperationException|RuntimeException|LinkageError failure){return new Turret(id,rank,ZoneScore.Defense.UNKNOWN,"adapter_unavailable");}
    }
    public static boolean sentry(Entity entity){return entity.getClass().getName().equals("net.geforcemods.securitycraft.entity.sentry.Sentry");}
    public static Turret turret(Entity entity) {
        if(!sentry(entity))return null;String id=BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString();int rank=rank(id);
        if(rank<0)return new Turret(id,0,ZoneScore.Defense.UNKNOWN,"unconfigured_rank");
        try {boolean enabled=entity.isAlive()&&!bool(entity,"isShutDown")&&bool(call(entity,"getMode"),"attacksHostile");return new Turret(id,rank,enabled?ZoneScore.Defense.ARMED:ZoneScore.Defense.DISABLED,enabled?"infinite_basic_ammo":"switched_off_or_player_only");}
        catch(ReflectiveOperationException|RuntimeException|LinkageError failure){return new Turret(id,rank,ZoneScore.Defense.UNKNOWN,"adapter_unavailable");}
    }
}
