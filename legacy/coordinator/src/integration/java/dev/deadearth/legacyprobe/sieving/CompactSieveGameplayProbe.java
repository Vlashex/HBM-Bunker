package dev.deadearth.legacyprobe.sieving;

import dev.deadearth.legacy.*;
import dev.deadearth.legacy.sieving.*;
import java.util.*;
import net.minecraftforge.items.CapabilityItemHandler;

/** Observe actual placement, player activation and normal server ticks in an isolated native fixture. */
public final class CompactSieveGameplayProbe {
 private CompactSieveGameplayProbe(){}
 private static void require(boolean value,String detail){if(!value)throw new AssertionError(detail);}
 private static Object stack(String id,int n){return Reflect.make("net.minecraft.item.ItemStack",Reflect.registry("ITEMS",id),n,0);}
 private static int count(Object stack){return (Boolean)Reflect.call(stack,"isEmpty/func_190926_b")?0:((Number)Reflect.call(stack,"getCount/func_190916_E")).intValue();}
 private static Object handler(Object tile){return Reflect.call(tile,"getCapability",CapabilityItemHandler.ITEM_HANDLER_CAPABILITY,null);}
 private static long number(Object tile,String field){return ((Number)Reflect.get(tile,field)).longValue();}
 public static Session begin(LegacyRuntime runtime,Object world,int x,int y,int z){return new Session(runtime,world,x,y,z);}

 public static final class Session {
  private final LegacyRuntime runtime;private final Object world,hand,up;
  private final List<Object> positions=new ArrayList<Object>(),tiles=new ArrayList<Object>(),actors=new ArrayList<Object>(),interactions=new ArrayList<Object>(),networks=new ArrayList<Object>();
  private final List<Map<String,Object>> cases=new ArrayList<Map<String,Object>>();private final Map<String,Object> report=new LinkedHashMap<String,Object>();
  private Object hopperInput,hopperOutput;private long hopperOutputItems;private long globalPrevious,worldPrevious,observedTicks,maxDrawsPerTick;private boolean done;private int chatPackets,completedGrace;
  private Session(LegacyRuntime runtime,Object world,int x,int y,int z){
   this.runtime=runtime;this.world=world;runtime.requireThread();
   require(Boolean.getBoolean("deadearth.testControls")&&java.nio.file.Files.isRegularFile(java.nio.file.Paths.get(".dead-earth-isolated-native-test")),"isolated native test only");
   require(!(Boolean)Reflect.get(world,"isRemote/field_72995_K"),"logical server fixture");
   hand=Reflect.get(Reflect.type("net.minecraft.util.EnumHand"),"MAIN_HAND");up=Reflect.get(Reflect.type("net.minecraft.util.EnumFacing"),"UP");
   for(int i=0;i<7;i++){Object p=Reflect.pos(x+i*3,y,z);require(Reflect.loaded(world,p),"fixture chunks already loaded");require(Reflect.blockId(world,p).equals("minecraft:air")&&Reflect.call(world,"getTileEntity/func_175625_s",p)==null,"fixture must be empty air");positions.add(p);}
   try{
    for(int i=0;i<6;i++)prepare(i);prepareHoppers();
    globalPrevious=LegacySieving.nativeDrawCount();worldPrevious=worldTime();
    report.put("normalPlacementOnly",true);report.put("manualLifecycleOrProgressCalls",false);report.put("cases",cases);
   }catch(Throwable failure){cleanup();throw failure;}
  }
  private long worldTime(){return ((Number)Reflect.call(world,"getTotalWorldTime/func_82737_E")).longValue();}
  private void prepare(int i){
   int level=3+i/2;boolean creative=i%2==1;Object p=positions.get(i),block=Reflect.registry("BLOCKS","dead_earth:compact_sieve_v"+level);
   require((Boolean)Reflect.call(world,"setBlockState/func_180501_a",p,Reflect.call(block,"getDefaultState/func_176223_P"),3),"compact frame placed through native World");
   Object tile=Reflect.call(world,"getTileEntity/func_175625_s",p);require(tile instanceof SieveTile,"native frame creates own tile");tiles.add(tile);
   boolean tracked=((Set<?>)Reflect.get(LegacySieving.class,"TILES")).contains(tile);require(tracked,"native onLoad tracks placed sieve without a manual callback");
   Object server=runtime.server,interaction=Reflect.make("net.minecraft.server.management.PlayerInteractionManager",world);interactions.add(interaction);
   Object profile=Reflect.make("com.mojang.authlib.GameProfile",new UUID(0x731a520e44554c00L,i+1),"CompactProbe"+i);
   Object actor=Reflect.make("net.minecraft.entity.player.EntityPlayerMP",server,world,profile,interaction);actors.add(actor);
   Object direction=Reflect.get(Reflect.type("net.minecraft.network.EnumPacketDirection"),"SERVERBOUND");Object network=Reflect.make("net.minecraft.network.NetworkManager",direction);networks.add(network);
   Object connection=Reflect.make("net.minecraft.network.NetHandlerPlayServer",server,network,actor);Reflect.set(actor,"connection/field_71135_a",connection);
   Object mode=Reflect.get(Reflect.type("net.minecraft.world.GameType"),creative?"CREATIVE":"SURVIVAL");Reflect.call(interaction,"setGameType/func_73076_a",mode);
   Reflect.call(actor,"setPosition/func_70107_b",(double)Reflect.x(p)+.5,(double)Reflect.y(p)+1,(double)Reflect.z(p)+.5);
   Object nativeMesh=Reflect.make("net.minecraft.item.ItemStack",Reflect.registry("ITEMS","exnihilocreatio:item_mesh"),1,4);
   click(interaction,actor,p,nativeMesh);require(count(Reflect.call(actor,"getHeldItem/func_184586_b",hand))==1&&count(Reflect.call(handler(tile),"getStackInSlot",1))==0,"plain native diamond mesh rejected unchanged");
   click(interaction,actor,p,stack("dead_earth:compact_mesh_diamond_v"+(level==5?3:5),1));require(count(Reflect.call(handler(tile),"getStackInSlot",1))==0,"wrong compact mesh tier rejected");
   click(interaction,actor,p,stack("dead_earth:compact_mesh_diamond_v"+level,1));require(count(Reflect.call(handler(tile),"getStackInSlot",1))==1,"matching compact diamond mesh installed through player activation");
   Object actual=Reflect.call(block,"getActualState/func_176221_a",Reflect.state(world,p),world,p);require(((Number)Reflect.call(actual,"getValue/func_177229_b",Reflect.get(block.getClass(),"MESH"))).intValue()==4,"actual block state displays the installed diamond mesh");require(((Number)Reflect.call(block,"getMetaFromState/func_176201_c",actual)).intValue()==0,"visual mesh property retains stable frame metadata0");
   Object update=Reflect.call(tile,"getUpdateTag/func_189517_E_");require(((Number)Reflect.call(update,"getInteger/func_74762_e","VisibleMesh")).intValue()==4&&!(Boolean)Reflect.call(update,"hasKey/func_74764_b","SieveItems"),"client update contains mesh state without server inventory");require(Reflect.is(Reflect.call(tile,"getUpdatePacket/func_189518_D_"),"net.minecraft.network.play.server.SPacketUpdateTileEntity"),"native tile update packet exists");
   click(interaction,actor,p,stack("minecraft:gravel",1));require(count(Reflect.call(handler(tile),"getStackInSlot",0))==0,"plain raw gravel cannot buy compressed rewards");
   click(interaction,actor,p,stack("dead_earth:compressed_gravel_"+(level-1),1));require(count(Reflect.call(actor,"getHeldItem/func_184586_b",hand))==0&&count(Reflect.call(handler(tile),"getStackInSlot",0))==1,"matching compression enters native frame inventory");
   require(number(tile,"draws")==0,"loading waits for an ordinary server tick");require(fill(block,p)==4,"newly loaded material is visibly full");
   cases.add(JsonFiles.map("tier",level,"mode",creative?"creative":"survival","naturallyTracked",tracked,"nativePlainMeshRejected",true,"wrongTierRejected",true,"matchingEquipmentAccepted",true));
  }
  private void prepareHoppers(){
   Object p=positions.get(6),input=Reflect.pos(Reflect.x(p),Reflect.y(p)+1,Reflect.z(p)),output=Reflect.pos(Reflect.x(p),Reflect.y(p)-1,Reflect.z(p));
   for(Object hp:Arrays.asList(input,output)){require(Reflect.loaded(world,hp)&&Reflect.blockId(world,hp).equals("minecraft:air"),"hopper fixture must be loaded empty air");}
   Object block=Reflect.registry("BLOCKS","dead_earth:compact_sieve_v3");require((Boolean)Reflect.call(world,"setBlockState/func_180501_a",p,Reflect.call(block,"getDefaultState/func_176223_P"),3),"hopper frame native placement");Object tile=Reflect.call(world,"getTileEntity/func_175625_s",p);tiles.add(tile);require(((Set<?>)Reflect.get(LegacySieving.class,"TILES")).contains(tile),"hopper frame naturally tracked");
   click(interactions.get(0),actors.get(0),p,stack("dead_earth:compact_mesh_diamond_v3",1));
   Object hopperState=Reflect.call(Reflect.registry("BLOCKS","minecraft:hopper"),"getDefaultState/func_176223_P");
   for(Object hp:Arrays.asList(input,output)){positions.add(hp);require((Boolean)Reflect.call(world,"setBlockState/func_180501_a",hp,hopperState,3),"native hopper placement");}
   hopperInput=Reflect.call(world,"getTileEntity/func_175625_s",input);hopperOutput=Reflect.call(world,"getTileEntity/func_175625_s",output);require(Reflect.is(hopperInput,"net.minecraft.tileentity.TileEntityHopper")&&Reflect.is(hopperOutput,"net.minecraft.tileentity.TileEntityHopper"),"real vanilla feeder and output hoppers");
   cases.add(JsonFiles.map("tier",3,"mode","native_hoppers","naturallyTracked",true));
  }
  private void click(Object interaction,Object actor,Object p,Object held){click(interaction,actor,p,held,false);}
  private void click(Object interaction,Object actor,Object p,Object held,boolean collecting){
   Reflect.call(actor,"setPosition/func_70107_b",(double)Reflect.x(p)+.5,(double)Reflect.y(p)+1,(double)Reflect.z(p)+.5);Reflect.call(actor,"setSneaking/func_70095_a",false);
   Reflect.call(actor,"setHeldItem/func_184611_a",hand,held);boolean loaded=Reflect.loaded(world,p);Object tile=loaded?Reflect.call(world,"getTileEntity/func_175625_s",p):null;
   Map<String,Object> context=JsonFiles.map("loaded",loaded,"block",loaded?Reflect.blockId(world,p):"unloaded","tile",tile==null?"absent":tile.getClass().getName(),"heldCountBefore",count(held),"sneaking",Reflect.call(actor,"isSneaking/func_70093_af"),"mode",String.valueOf(Reflect.call(interaction,"getGameType/func_73081_b")),"collection",collecting);
   Object result=Reflect.call(interaction,"processRightClickBlock/func_187251_a",actor,world,held,hand,p,up,.5f,.5f,.5f);context.put("result",String.valueOf(result));context.put("heldCountAfter",count(Reflect.call(actor,"getHeldItem/func_184586_b",hand)));report.put("lastPlayerInteraction",context);
   // Forge1.12 retains vanilla's empty-stack PASS return after successful block activation.
   // Collection therefore asserts the actual inventory transfer below, in addition to this native return contract.
   require(result.toString().equals("SUCCESS")||collecting&&count(held)==0&&result.toString().equals("PASS"),"actual PlayerInteractionManager right-click accepted: "+context);
  }
  private long matchingInventory(Object actor,Object expected){long total=0;for(Object stack:(Iterable<?>)Reflect.get(Reflect.get(actor,"inventory/field_71071_by"),"mainInventory/field_70462_a"))if(count(stack)>0&&(Boolean)Reflect.call(Reflect.type("net.minecraft.item.ItemStack"),"areItemsEqual/func_179545_c",stack,expected)&&(Boolean)Reflect.call(Reflect.type("net.minecraft.item.ItemStack"),"areItemStackTagsEqual/func_77970_a",stack,expected))total+=count(stack);return total;}
  private void captureEvidence(){report.put("observedServerTicks",observedTicks);report.put("maximumGlobalNativeDrawsPerTick",maxDrawsPerTick);report.put("nativeHopperOutputItems",hopperOutputItems);for(int i=0;i<tiles.size();i++){Object tile=tiles.get(i);cases.get(i).put("normalTickDraws",number(tile,"draws"));cases.get(i).put("remainingRawDraws",number(tile,"remaining"));cases.get(i).put("completedBatches",number(tile,"completed"));}}
  private int fill(Object block,Object p){Object actual=Reflect.call(block,"getActualState/func_176221_a",Reflect.state(world,p),world,p);return((Number)Reflect.call(actual,"getValue/func_177229_b",Reflect.get(block.getClass(),"FILL"))).intValue();}
  /** Call once at LOWEST server END priority, after the production runtime tick. Never advances a clock. */
  public boolean poll(){
   runtime.requireThread();if(done)return true;long time=worldTime();if(time==worldPrevious)return false;
   require(time==worldPrevious+1,"observe every actual server tick");worldPrevious=time;observedTicks++;
   try{
    long total=LegacySieving.nativeDrawCount(),used=total-globalPrevious;globalPrevious=total;maxDrawsPerTick=Math.max(maxDrawsPerTick,used);require(used>=0&&used<=256,"native runtime shares at most256 draws per actual server tick");
    if(observedTicks<=12)require(count(Reflect.call(handler(tiles.get(6)),"getStackInSlot",1))==1,"output hopper cannot steal mesh from idle frame");
    if(observedTicks==12)Reflect.call(hopperInput,"setInventorySlotContents/func_70299_a",0,stack("dead_earth:compressed_gravel_2",1));
    for(int slot=0;slot<5;slot++){Object output=Reflect.call(hopperOutput,"getStackInSlot/func_70301_a",slot);if(count(output)>0){String id=String.valueOf(Reflect.call(Reflect.call(output,"getItem/func_77973_b"),"getRegistryName"));require(!id.contains("compact_mesh")&&!id.contains("compressed_gravel"),"output hopper transports rewards only");hopperOutputItems+=count(output);Reflect.call(hopperOutput,"setInventorySlotContents/func_70299_a",slot,Reflect.get(Reflect.type("net.minecraft.item.ItemStack"),"EMPTY/field_190927_a"));}}
    boolean allComplete=true;
    for(int i=0;i<tiles.size();i++){
     Object tile=tiles.get(i),h=handler(tile);require(!(Boolean)Reflect.get(tile,"fault"),"normal ticking does not fault a sieve");int level=i==6?3:3+i/2,target=level==3?81:level==4?729:6561;long draws=number(tile,"draws");require(draws<=target,"one compressed input cannot produce a second batch");
     allComplete&=draws==target&&number(tile,"completed")==1&&number(tile,"remaining")==0;require(count(Reflect.call(h,"getStackInSlot",1))==1,"normal ticking retains the installed mesh");
     if(draws>0)require(count(Reflect.call(h,"getStackInSlot",0))==0,"automatic batch consumed exactly one matching compressed input");
     if(i!=6)for(int slot=2;slot<29;slot++)Reflect.call(h,"extractItem",slot,64,false);
    }
    if(allComplete)completedGrace++;else completedGrace=0;
    if(completedGrace>=4){finish();return true;}
    require(observedTicks<240,"mesh plus input and bounded normal ticks complete all81/729/6561 batches automatically");return false;
   }catch(Throwable failure){captureEvidence();report.put("passed",false);report.put("error",failure.toString());report.put("observedServerTicks",observedTicks);done=true;cleanup();return true;}
  }
  private void finish(){
   captureEvidence();
   Object hopperFrame=tiles.get(6),h=handler(hopperFrame);int rewardSlot=-1;for(int slot=2;slot<29&&rewardSlot<0;slot++)if(count(Reflect.call(h,"getStackInSlot",slot))>0)rewardSlot=slot;require(rewardSlot>=0,"completed native batch has a real reward left for hand collection");
   Object actor=actors.get(0),reward=Reflect.call(h,"getStackInSlot",rewardSlot),empty=Reflect.get(Reflect.type("net.minecraft.item.ItemStack"),"EMPTY/field_190927_a");int rewardCount=count(reward);Reflect.call(actor,"setHeldItem/func_184611_a",hand,empty);long inventoryBefore=matchingInventory(actor,reward);
   click(interactions.get(0),actor,positions.get(6),empty,true);require(number(hopperFrame,"draws")==81&&count(Reflect.call(h,"getStackInSlot",rewardSlot))==0&&matchingInventory(actor,reward)-inventoryBefore==rewardCount,"empty hand transfers the exact real reward stack into player inventory without duplicating output");report.put("emptyHandCollectsNativeReward",true);report.put("handCollectedItemCount",rewardCount);
   for(Object network:networks)for(Object tuple:(Iterable<?>)Reflect.get(network,"outboundPacketsQueue/field_150745_j"))if(Reflect.is(Reflect.get(tuple,"packet/field_150774_a"),"net.minecraft.network.play.server.SPacketChat"))chatPackets++;
   require(hopperOutputItems>0&&count(Reflect.call(hopperInput,"getStackInSlot/func_70301_a",0))==0,"real hopper transferred input and extracted native rewards");
   report.put("automaticBatchAfterMeshAndInput",true);report.put("allTierEquivalentCounts",true);report.put("nativeHopperAutomaticInput",true);report.put("nativeHopperOutputItems",hopperOutputItems);report.put("idleMeshProtectedFromHopper",true);report.put("chatPackets",chatPackets);report.put("observedServerTicks",observedTicks);report.put("maximumGlobalNativeDrawsPerTick",maxDrawsPerTick);
   for(int i=0;i<tiles.size();i++){Object tile=tiles.get(i);cases.get(i).put("normalTickDraws",number(tile,"draws"));cases.get(i).put("completedBatches",number(tile,"completed"));}
   require(chatPackets==0,"compact sieve activations emit zero chat packets including rejected input and empty hand");
   report.put("passed",true);done=true;cleanup();
  }
  public Map<String,Object> result(){require(done,"wait for ordinary END ticks before reporting gameplay proof");return report;}
  private void cleanup(){Object air=Reflect.call(Reflect.registry("BLOCKS","minecraft:air"),"getDefaultState/func_176223_P");for(Object p:positions)if(Reflect.loaded(world,p)&&(Reflect.blockId(world,p).startsWith("dead_earth:compact_sieve_")||Reflect.blockId(world,p).equals("minecraft:hopper"))){Reflect.call(world,"removeTileEntity/func_175713_t",p);Reflect.call(world,"setBlockState/func_180501_a",p,air,2);}}
 }
}
