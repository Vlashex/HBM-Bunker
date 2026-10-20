package dev.deadearth.legacyprobe;

import com.google.gson.*;
import dev.deadearth.legacy.*;
import dev.deadearth.legacy.defense.LegacyDefense;
import java.lang.management.ManagementFactory;
import java.nio.file.*;
import java.util.*;
import static dev.deadearth.legacy.JsonFiles.map;

/** Physical sentinels in an explicitly isolated world. No manufactured tile NBT or product state. */
public final class RestartSentinels {
    private static final Path EXPECTED=Paths.get("full-persistence-expected.json");
    private static final String[] IDS={"dead_earth:defense_wall_v2","dead_earth:crop_tray","dead_earth:fortune_processor","appliedenergistics2:drive","dead_earth:season_lake_ice","dead_earth:season_snow_0_8","dead_earth:season_rime_0_8","minecraft:gold_block","dead_earth:compact_sieve_v3"};
    private RestartSentinels(){}
    private static void require(boolean condition,String message){if(!condition)throw new IllegalStateException("Persistence sentinel: "+message);}
    private static void guard(LegacyRuntime r,Object world)throws Exception {
        r.requireThread();require(Boolean.getBoolean("deadearth.testControls"),"test controls disabled");
        Path root=Paths.get("").toRealPath(),marker=root.resolve(".dead-earth-isolated-native-test");
        require(Files.isRegularFile(marker)&&!Files.isSymbolicLink(marker),"isolated marker absent");
        require(r.worldDirectory.toRealPath().equals(root.resolve("probe_world").toRealPath()),"world is not this isolated probe_world");
        require(Reflect.dimension(world)==0&&r.world.world(0)==world,"unexpected world");
    }
    private static Object stack(String id,int count){return Reflect.make("net.minecraft.item.ItemStack",Reflect.registry("ITEMS",id),count,0);}
    private static boolean empty(Object s){return(Boolean)Reflect.call(s,"isEmpty/func_190926_b");}
    private static int count(Object s){return empty(s)?0:((Number)Reflect.call(s,"getCount/func_190916_E")).intValue();}
    private static Object capability(Object tile,String owner,String field){return Reflect.call(tile,"getCapability",Reflect.get(Reflect.type(owner),field),null);}
    private static Object inventory(Object tile){return capability(tile,"net.minecraftforge.items.CapabilityItemHandler","ITEM_HANDLER_CAPABILITY");}
    private static Object power(Object tile){return capability(tile,"net.minecraftforge.energy.CapabilityEnergy","ENERGY");}
    private static int number(Object target,String method){return((Number)Reflect.call(target,method)).intValue();}
    private static Object tag(Object stack){return Reflect.call(stack,"writeToNBT/func_77955_b",Reflect.make("net.minecraft.nbt.NBTTagCompound"));}
    private static Object tileTag(Object tile){return Reflect.call(tile,"writeToNBT/func_189515_b",Reflect.make("net.minecraft.nbt.NBTTagCompound"));}
    private static Object fieldTag(Object tile,String key){return Reflect.call(tileTag(tile),"getCompoundTag/func_74775_l",key);}
    private static void sameNbt(String expected,Object actual,String what){Object parsed=Reflect.call(Reflect.type("net.minecraft.nbt.JsonToNBT"),"getTagFromJson/func_180713_a",expected);require(parsed.equals(actual),what+" changed across restart");}
    private static List<String> items(Object handler){List<String> out=new ArrayList<String>();int n=number(handler,"getSlots");require(n<=64,"excessive slots");for(int i=0;i<n;i++)out.add(tag(Reflect.call(handler,"getStackInSlot",i)).toString());return out;}
    private static Object tile(Object world,Object pos){Object value=Reflect.call(world,"getTileEntity/func_175625_s",pos);require(value!=null,"missing tile at "+pos);return value;}
    private static void place(Object world,Object pos,String id){Object state=Reflect.call(Reflect.registry("BLOCKS",id),"getDefaultState/func_176223_P");require((Boolean)Reflect.call(world,"setBlockState/func_180501_a",pos,state,3),"placement refused: "+id);require(id.equals(Reflect.blockId(world,pos)),"wrong block after placement");}
    private static void fill(Object energy,int amount){for(int left=amount,attempts=0;left>0;attempts++){require(attempts<100000,"energy fill bound");int accepted=((Number)Reflect.call(energy,"receiveEnergy",left,false)).intValue();require(accepted>0&&accepted<=left,"FE receive rejected");left-=accepted;}}
    private static long iron(Object drive,LegacyRuntime r){InventoryValuation.Cursor cursor=InventoryValuation.open(drive,r.settings);long total=0;int steps=0;while(!cursor.done()&&steps++<1024){cursor.verify();InventoryValuation.Entry e=cursor.next();if(e!=null&&e.stack!=null&&e.count>0){String id=Reflect.call(Reflect.call(e.stack,"getItem/func_77973_b"),"getRegistryName").toString();if(id.equals("minecraft:iron_ingot"))total+=e.count;}}require(cursor.done(),"native cell traversal incomplete");return total;}

    /** Call immediately before save/stop, with no later climate ticks: own coatings otherwise legitimately thaw. */
    public static Map<String,Object> create(LegacyRuntime r,Object world,Object player,int x,int y,int z)throws Exception {
        guard(r,world);require(!Files.exists(EXPECTED),"expected manifest already exists; refusing replacement");require(y>=2&&y<=253,"unsafe fixture height");
        List<Object> positions=new ArrayList<Object>();
        for(int i=0;i<IDS.length;i++){
            Object p=Reflect.pos(x+i*2,y,z),below=Reflect.pos(x+i*2,y-1,z);require(Reflect.loaded(world,p)&&Reflect.loaded(world,below),"fixture must already be loaded");
            boolean inZone=false;for(Campaign.Zone zone:r.campaign.zones)if(zone.dimension==0&&Math.abs((zone.x>>4)-((x+i*2)>>4))<=r.settings.zoneRadiusChunks&&Math.abs((zone.z>>4)-(z>>4))<=r.settings.zoneRadiusChunks)inZone=true;
            require(inZone,"fixture outside loaded relay zones");require("minecraft:air".equals(Reflect.blockId(world,p))&&"minecraft:air".equals(Reflect.blockId(world,below)),"fixture would overwrite existing block");positions.add(p);
        }
        require(!r.protectedAt(world,positions.get(0)),"defense fixture inside grace area");
        for(int i=0;i<IDS.length;i++){place(world,Reflect.pos(x+i*2,y-1,z),"minecraft:stone");place(world,positions.get(i),IDS[i]);}
        int full=LegacyDefense.remaining(world,positions.get(0));require(full==2000,"V2 starting HP");LegacyDefense.damage(world,positions.get(0),250,false);require(LegacyDefense.remaining(world,positions.get(0))==1750,"real defense damage did not persist");
        Object tray=tile(world,positions.get(1)),hand=Reflect.get(Reflect.type("net.minecraft.util.EnumHand"),"MAIN_HAND"),held=Reflect.call(player,"getHeldItem/func_184586_b",hand);
        try{for(String id:new String[]{"minecraft:dirt","minecraft:wheat_seeds"}){Reflect.call(player,"setHeldItem/func_184611_a",hand,stack(id,1));Reflect.call(tray,"interact",player,hand);require(empty(Reflect.call(player,"getHeldItem/func_184586_b",hand)),"tray did not accept "+id);}}finally{Reflect.call(player,"setHeldItem/func_184611_a",hand,held);}
        sameNbt(tag(stack("minecraft:wheat_seeds",1)).toString(),fieldTag(tray,"Seed"),"initial planted seed");sameNbt(tag(stack("minecraft:dirt",1)).toString(),fieldTag(tray,"Soil"),"initial planted soil");
        Object processor=tile(world,positions.get(2)),inv=inventory(processor),energy=power(processor),cfg=Reflect.get(Reflect.type("dev.deadearth.legacy.machinery.LegacyMachinery"),"settings");
        int cost=((Number)Reflect.get(cfg,"processorFEPerCycle")).intValue(),cycle=((Number)Reflect.get(cfg,"processorCycleTicks")).intValue();require(cost>1&&cycle<=12000,"unsupported processor fixture settings");
        require(empty(Reflect.call(inv,"insertItem",0,stack("minecraft:coal_ore",2),false)),"native processor input rejected");fill(energy,cost);for(int tick=0;tick<cycle;tick++)Reflect.call(processor,"update/func_73660_a");
        require(count(Reflect.call(inv,"getStackInSlot",0))==1&&number(energy,"getEnergyStored")==0,"real processor cycle did not settle once");
        Object output=Reflect.call(inv,"getStackInSlot",1);require(!empty(output)&&"minecraft:coal".equals(Reflect.call(Reflect.call(output,"getItem/func_77973_b"),"getRegistryName").toString()),"native Fortune output absent");fill(energy,cost-1);
        Object drive=tile(world,positions.get(3)),cell=stack("appliedenergistics2:storage_cell_1k",1),api=Reflect.call(Reflect.type("appeng.api.AEApi"),"instance"),storage=Reflect.call(api,"storage");
        Object channel=Reflect.call(storage,"getStorageChannel",Reflect.type("appeng.api.storage.channels.IItemStorageChannel")),cells=Reflect.call(Reflect.call(api,"registries"),"cell"),handler=Reflect.call(cells,"getCellInventory",cell,null,channel);
        require(handler!=null,"native cell unavailable");Object iron=Reflect.call(Reflect.type("appeng.util.item.AEItemStack"),"fromItemStack",stack("minecraft:iron_ingot",1));Reflect.call(iron,"setStackSize",1234L);
        require(Reflect.call(handler,"injectItems",iron,Reflect.get(Reflect.type("appeng.api.config.Actionable"),"MODULATE"),null)==null,"native cell injection incomplete");Reflect.call(Reflect.call(handler,"getCellInv"),"persist");Reflect.call(Reflect.call(drive,"getInternalInventory"),"setStackInSlot",0,cell);require(iron(drive,r)==1234,"physical native cell quantity");
        Object sieve=tile(world,positions.get(8)),sieveInventory=inventory(sieve);
        require(empty(Reflect.call(sieveInventory,"insertItem",1,stack("dead_earth:compact_mesh_diamond_v3",1),false)),"matching compact mesh rejected");
        require(empty(Reflect.call(sieveInventory,"insertItem",0,stack("dead_earth:compressed_gravel_2",1),false)),"matching compact compressed input rejected");
        // Isolated blocked-output fixture; the pending reward itself is produced by the native registry.
        @SuppressWarnings("unchecked") List<Object> sieveSlots=(List<Object>)Reflect.get(sieve,"slots");for(int i=2;i<29;i++)sieveSlots.set(i,stack("minecraft:barrier",64));
        java.lang.reflect.Method step=Reflect.type("dev.deadearth.legacy.sieving.SieveTile").getDeclaredMethod("step");step.setAccessible(true);
        for(int i=0;i<81&&Reflect.get(sieve,"pending")==null;i++)step.invoke(sieve);
        require(Reflect.get(sieve,"pending")!=null&&empty(Reflect.call(sieveInventory,"getStackInSlot",0)),"compact input not spent once into a blocked native reward");
        Reflect.call(sieve,"markDirty/func_70296_d");
        for(Object t:new Object[]{tray,processor,drive})Reflect.call(t,"markDirty/func_70296_d");
        Map<String,Object> expected=map("schema",1,"worldEpoch",r.campaign.worldEpoch,"campaignId",r.campaign.campaignId,"createdJvmStart",ManagementFactory.getRuntimeMXBean().getStartTime(),"worldDirectory",r.worldDirectory.toRealPath().toString(),"x",x,"y",y,"z",z,"blocks",Arrays.asList(IDS),"defenseHP",1750,"seed",fieldTag(tray,"Seed").toString(),"soil",fieldTag(tray,"Soil").toString(),"machineItems",items(inv),"machineEnergy",number(energy,"getEnergyStored"),"cell",tag(Reflect.call(Reflect.call(drive,"getInternalInventory"),"getStackInSlot",0)).toString(),"storedIron",1234,"cropPendingRestartTested",false,"machinePendingRestartTested",false,"coatingGenerationTestedHere",false,"compactSieveTile",tileTag(sieve).toString(),"compactPendingRestartTested",true);
        JsonFiles.write(EXPECTED,expected);return map("created",true,"manifest",EXPECTED.toString(),"cropScope","retained seed and soil","machineScope","real produced output, retained input and FE","coatingScope","physical own block identities only","pendingRestartTested",false);
    }

    public static Map<String,Object> verify(LegacyRuntime r,Object world)throws Exception {
        guard(r,world);JsonObject e=JsonFiles.read(EXPECTED,1048576);require(e.get("schema").getAsInt()==1,"unsupported manifest");
        require(e.get("createdJvmStart").getAsLong()!=ManagementFactory.getRuntimeMXBean().getStartTime(),"verification requires a different JVM launch");
        require(e.get("worldEpoch").getAsString().equals(r.campaign.worldEpoch)&&e.get("campaignId").getAsString().equals(r.campaign.campaignId),"campaign identity changed");require(e.get("worldDirectory").getAsString().equals(r.worldDirectory.toRealPath().toString()),"world path changed");
        int x=e.get("x").getAsInt(),y=e.get("y").getAsInt(),z=e.get("z").getAsInt();List<Object> positions=new ArrayList<Object>();
        require(e.getAsJsonArray("blocks").size()==IDS.length,"block manifest shape");for(int i=0;i<IDS.length;i++){require(IDS[i].equals(e.getAsJsonArray("blocks").get(i).getAsString()),"unexpected block manifest");Object p=Reflect.pos(x+i*2,y,z);require(Reflect.loaded(world,p),"sentinel chunk is not loaded; no force load allowed");require(IDS[i].equals(Reflect.blockId(world,p)),"physical block changed: "+IDS[i]);require("minecraft:stone".equals(Reflect.blockId(world,Reflect.pos(x+i*2,y-1,z))),"physical support changed");positions.add(p);}
        require(LegacyDefense.remaining(world,positions.get(0))==e.get("defenseHP").getAsInt(),"damaged V2 HP changed");Object tray=tile(world,positions.get(1));sameNbt(e.get("seed").getAsString(),fieldTag(tray,"Seed"),"seed");sameNbt(e.get("soil").getAsString(),fieldTag(tray,"Soil"),"soil");
        Object processor=tile(world,positions.get(2)),inv=inventory(processor);JsonArray expectedItems=e.getAsJsonArray("machineItems");require(number(inv,"getSlots")==expectedItems.size(),"processor slot count changed");for(int i=0;i<expectedItems.size();i++)sameNbt(expectedItems.get(i).getAsString(),tag(Reflect.call(inv,"getStackInSlot",i)),"processor slot "+i);require(number(power(processor),"getEnergyStored")==e.get("machineEnergy").getAsInt(),"processor FE changed");
        Object drive=tile(world,positions.get(3));sameNbt(e.get("cell").getAsString(),tag(Reflect.call(Reflect.call(drive,"getInternalInventory"),"getStackInSlot",0)),"physical native AE2 cell");require(iron(drive,r)==e.get("storedIron").getAsLong(),"native AE2 contents changed");
        sameNbt(e.get("compactSieveTile").getAsString(),tileTag(tile(world,positions.get(8))),"compact sieve spent input, locked mesh, pending draw and blocked inventory");
        return map("realJvmRestart",true,"damagedDefenseHP",true,"cropSeedAndSoil",true,"machineNativeOutputInputAndFE",true,"physicalAe2CellAndContent",true,"ownLakeIceSnowRime",true,"cropPendingRestartTested",false,"machinePendingRestartTested",false,"coatingGenerationTestedHere",false,"compactSievePendingAcrossRealJvmRestart",true);
    }
}