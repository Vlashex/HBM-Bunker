package dev.deadearth.legacy.climate;

import dev.deadearth.legacy.*;
import java.nio.file.*;
import java.util.*;

/** Authored for the final isolated native probe. Never called by production runtime. */
public final class ClimateNativeChecks {
    private ClimateNativeChecks() {}
    private static void require(boolean value,String text){if(!value)throw new AssertionError("Native climate: "+text);}
    public static Map<String,Object> verify(LegacyRuntime runtime,Object world,Object player,int x,int y,int z) {
        runtime.requireThread();
        require(Boolean.getBoolean("deadearth.testControls")&&Files.isRegularFile(Paths.get(".dead-earth-isolated-native-test")),"isolated final-stage controls");
        require(y>=80&&y<210,"air test site above generated terrain");
        LegacyClimate.NativeWorld nativeWorld=new LegacyClimate.NativeWorld(world);
        for(int dx=-12;dx<=12;dx++)for(int dz=-12;dz<=12;dz++)require(nativeWorld.loaded(x+dx,z+dz),"test area and borders are already loaded");
        for(int dy=0;dy<=12;dy++)for(int dx=0;dx<=4;dx++)require(nativeWorld.read(x+dx,y+dy,z)==CoatingEngine.Cell.AIR,"test fixture does not replace existing geometry");
        Object anchor=Reflect.pos(x,y,z);set(world,anchor,"minecraft:stone");
        Object provider=Reflect.call(world,"getChunkProvider/func_72863_F");int loadedBefore=((Collection<?>)Reflect.call(provider,"getLoadedChunks/func_189548_a")).size();
        ClimateConfig c=new ClimateConfig();c.topIce=2;c.topSnow=1;c.sideIce=2;c.sideSnow=1;c.iceRelief=0;c.snowRelief=0;c.sideRelief=0;c.peakChance=0;c.operationClearance=1;
        CoatingEngine engine=new CoatingEngine(c);long seed=WinterField.seed(((Number)Reflect.call(world,"getSeed/func_72905_C")).longValue(),2);
        CoatingEngine.Column top=new CoatingEngine.Column(x,z),side1=new CoatingEngine.Column(x+1,z),side2=new CoatingEngine.Column(x+2,z),side3=new CoatingEngine.Column(x+3,z);
        int changes=0,maxCells=0,maxSections=0;
        for(int pass=0;pass<24;pass++) {
            ClimateBudget budget=new ClimateBudget(65536,256,16);
            for(CoatingEngine.Column column:Arrays.asList(top,side1,side2,side3))engine.column(nativeWorld,column,true,seed,budget);
            changes+=budget.changes;maxCells=Math.max(maxCells,budget.reads);maxSections=Math.max(maxSections,budget.sections());
            require(budget.reads<=65536&&budget.changes<=256&&budget.sections()<=16,"native work stays within read/edit/section quotas");
        }
        require(nativeWorld.read(x,y+1,z).own&&!nativeWorld.read(x,y+1,z).snow,"first top rime");
        require(nativeWorld.read(x,y+2,z).own&&nativeWorld.read(x,y+3,z).snow,"several blocks of layered top ice and snow");
        require(nativeWorld.read(x+1,y,z).own&&nativeWorld.read(x+2,y,z).own&&nativeWorld.read(x+3,y,z).snow,"several blocks on a vertical side");
        Object chest=Reflect.pos(x+2,y,z);set(world,chest,"minecraft:chest");
        engine.column(nativeWorld,new CoatingEngine.Column(x+1,z),true,seed,new ClimateBudget(65536,256,16));
        require(nativeWorld.read(x+1,y,z)==CoatingEngine.Cell.AIR,"operation access beside a newly placed inventory is cleared");
        Object replacement=Reflect.pos(x,y+1,z);set(world,replacement,"minecraft:ice");
        for(int pass=0;pass<8;pass++)for(int dx=0;dx<=4;dx++)engine.column(nativeWorld,new CoatingEngine.Column(x+dx,z),false,seed,new ClimateBudget(65536,256,16));
        require(Reflect.blockId(world,replacement).equals("minecraft:ice"),"native/player ice is not thawed");
        require(Reflect.blockId(world,chest).equals("minecraft:chest"),"player container is preserved");
        require(Reflect.blockId(world,anchor).equals("minecraft:stone"),"original anchor survives thaw");
        require(!nativeWorld.read(x,y+2,z).own&&!nativeWorld.read(x,y+3,z).own,"own top coating thaws");
        require(!nativeWorld.read(x+3,y,z).own,"own side coating thaws");
        require(((Collection<?>)Reflect.call(provider,"getLoadedChunks/func_189548_a")).size()==loadedBefore,"coating and thaw did not load more terrain");
        Object lake=Reflect.pos(x+7,y,z);require(nativeWorld.read(x+7,y,z)==CoatingEngine.Cell.AIR,"lake assertion site is empty");
        set(world,lake,"minecraft:water");LegacyClimate.surfaceWater(nativeWorld,x+7,z,new ClimateBudget(65536,256,16));
        require(nativeWorld.read(x+7,y,z)==CoatingEngine.Cell.FROZEN_WATER,"source water becomes own lake ice");
        engine.column(nativeWorld,new CoatingEngine.Column(x+7,z),false,seed,new ClimateBudget(65536,256,16));
        require(Reflect.blockId(world,lake).equals("minecraft:water"),"own lake ice restores source water");
        return JsonFiles.map("topAndSides",true,"nativeIcePreserved",true,"inventoryOperable",true,"changes",changes,"maximumCells",maxCells,"maximumSections",maxSections,"forceLoads",0);
    }
    private static void set(Object world,Object pos,String id){Reflect.call(world,"setBlockState/func_180501_a",pos,Reflect.call(Reflect.registry("BLOCKS",id),"getDefaultState/func_176223_P"),3);}
    /** A real-engine, region-scoped showcase driver. Call once per ordinary server tick; never paints blocks. */
    public static final class AreaSession {
        private final LegacyRuntime runtime;private final Object world;private final LegacyClimate.NativeWorld adapter;
        private final ClimateConfig config;private final CoatingEngine engine;private final CoatingEngine.Column[] columns;
        private int cursor;private long lastWorldTick=-1,totalChanges;private Boolean previousWinter;
        public AreaSession(LegacyRuntime runtime,Object world,int minX,int minZ,int maxX,int maxZ) {
            runtime.requireThread();require(Boolean.getBoolean("deadearth.testControls")&&Files.isRegularFile(Paths.get(".dead-earth-isolated-native-test")),"isolated showcase controls");
            int width=maxX-minX+1,depth=maxZ-minZ+1;require(width>0&&depth>0&&width<=64&&depth<=64&&width*depth<=1024,"bounded showcase area");
            this.runtime=runtime;this.world=world;this.adapter=new LegacyClimate.NativeWorld(world);this.config=LegacyClimate.config;this.engine=new CoatingEngine(config);
            columns=new CoatingEngine.Column[width*depth];int index=0;
            for(int z=minZ;z<=maxZ;z++)for(int x=minX;x<=maxX;x++) {
                require(adapter.loaded(x,z),"showcase target already loaded");columns[index++]=new CoatingEngine.Column(x,z);
            }
        }
        public Map<String,Object> tick(boolean winter) {
            runtime.requireThread();long worldTick=((Number)Reflect.call(world,"getTotalWorldTime/func_82737_E")).longValue();
            require(worldTick!=lastWorldTick,"one area budget per ordinary server tick");lastWorldTick=worldTick;
            if(previousWinter==null||previousWinter.booleanValue()!=winter)for(int i=0;i<columns.length;i++)columns[i]=new CoatingEngine.Column(columns[i].x,columns[i].z);
            previousWinter=winter;
            ClimateBudget budget=new ClimateBudget(config.cellsPerTick,config.editsPerTick,config.sectionsPerTick);
            long winterNumber=Math.floorDiv(runtime.campaign.activeTicks,runtime.settings.seasonTicks*3);
            long seed=WinterField.seed(((Number)Reflect.call(world,"getSeed/func_72905_C")).longValue(),winterNumber);
            for(int n=0;n<Math.min(columns.length,config.columnsPerTick)&&!budget.exhausted();n++) {
                CoatingEngine.Column column=columns[cursor++%columns.length];
                if(engine.column(adapter,column,winter,seed,budget)&&winter&&config.freezeWater)LegacyClimate.surfaceWater(adapter,column.x,column.z,budget);
            }
            totalChanges+=budget.changes;
            return JsonFiles.map("changes",budget.changes,"totalChanges",totalChanges,"cells",budget.reads,"sections",budget.sections(),"columns",columns.length,"winter",winter);
        }
    }
}
