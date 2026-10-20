package dev.deadearth.legacy.worldgen;

import java.util.*;

/** Final-stage pure assertions. Authored during implementation; execute only in the final test stage. */
public final class WastelandChecks {
    private static int count;
    private static void check(boolean value,String why){count++;if(!value)throw new AssertionError(why);}
    public static void main(String[] args){run();System.out.println("WastelandChecks: "+count+" checks passed");}
    public static void run(){
        WastelandSettings s=new WastelandSettings();s.validate();
        s.trunkChance=0;
        // CE uses the same pending-job indexes for ordinary ores/plants and actual structures.
        for(String type:Arrays.asList("WorldGenMinableNonCascade","NTMFlowers","PlantReeds"))
            check(!HbmFootprints.reservesStructure("com.hbm.world.feature."+type),"native natural feature is not a building reservation: "+type);
        for(String type:Arrays.asList("com.hbm.world.Bunker","com.hbm.world.OilBubble","com.hbm.world.feature.BedrockOre","com.hbm.world.feature.DepthDeposit","com.hbm.world.feature.WorldGenMinableNonCascade$UnknownSubclass","future.hbm.Structure"))
            check(HbmFootprints.reservesStructure(type),"dedicated deposits, buildings and unknown generators retain protection: "+type);

        for(int cx=-3;cx<=3;cx++)for(int cz=-3;cz<=3;cz++){
            List<WastelandField.Crater> local=WastelandField.candidates(91241,s,cx,cz);
            List<WastelandField.Crater> east=WastelandField.candidates(91241,s,cx+1,cz);
            List<WastelandField.Crater> south=WastelandField.candidates(91241,s,cx,cz+1);
            for(int i=0;i<16;i++){
                int x=cx*16+15,z=cz*16+i;
                check(WastelandField.displacement(local,x,z)==WastelandField.displacement(east,x,z),"east overlap is coordinate deterministic");
                x=cx*16+i;z=cz*16+15;
                check(WastelandField.displacement(local,x,z)==WastelandField.displacement(south,x,z),"south overlap is coordinate deterministic");
            }
        }
        WastelandSettings reversed=new WastelandSettings();
        List<String> keys=new ArrayList<String>(reversed.palette.keySet());Collections.reverse(keys);
        LinkedHashMap<String,Integer> reordered=new LinkedHashMap<String,Integer>();for(String key:keys)reordered.put(key,reversed.palette.get(key));reversed.palette=reordered;
        for(int x=-16;x<16;x++)check(WastelandField.surface(91241,s,x,7).equals(WastelandField.surface(91241,reversed,x,7)),"JSON palette order does not change terrain");
        check(WastelandSurface.natural("minecraft:mossy_cobblestone#0"),"natural mossy cobble is cuttable");
        check(!WastelandSurface.architecture("minecraft:mossy_cobblestone#0"),"mossy cannot grant column immunity");
        check(!WastelandSurface.architecture("minecraft:cobblestone#0"),"cobble cannot grant column immunity");
        check(WastelandSurface.architecture("minecraft:chest#0"),"containers protect their footprint");
        check(WastelandSurface.architecture("hbm:brick_concrete#0"),"HBM architecture is protected");
        check(!WastelandSurface.architecture("hbm:plant_flower#0"),"native plants cannot disable chunk painting");
        check(WastelandSurface.natural("hbm:stone_gneiss#0"),"native geologic stone is not architecture");
        Fixture dirt=new Fixture();Fixture mossy=new Fixture();mossy.mossy=true;
        int band=WastelandSurface.minimumAffectedY(dirt,s);
        check(band==80-(22+3+2),"surface reservation floor includes deepest cut, coating and guard margin");
        check(HbmFootprints.maximumDeepWriteY("com.hbm.world.feature.DepthDeposit",2,5)==7,"native depth deposit maximum Y");
        check(HbmFootprints.maximumDeepWriteY("com.hbm.world.feature.BedrockOre",100,0)==6,"native bedrock generator writes at absolute Y0..6");
        check(HbmFootprints.maximumDeepWriteY("com.hbm.world.feature.DepthDeposit",2,5)<band,"deep geology does not reserve untouched surface");
        check(!(HbmFootprints.maximumDeepWriteY("com.hbm.world.feature.DepthDeposit",50,5)<band),"overlapping elevated deposit retains protection");
        check(HbmFootprints.maximumDeepWriteY("com.hbm.world.feature.DepthDeposit",125,5)==126,"native depth write clipping");
        check(HbmFootprints.maximumDeepWriteY("com.hbm.world.feature.BedrockOilDeposit",0,0)==Integer.MAX_VALUE,"oil surface callbacks never gain deep exemption");
        check(HbmFootprints.maximumDeepWriteY("com.hbm.world.feature.LanternBehemoth",0,0)==Integer.MAX_VALUE,"native multiblock remains protected");

        WastelandSurface.Plan baseline=WastelandSurface.plan(dirt,91241,s,0,0,false);
        WastelandSurface.Plan natural=WastelandSurface.plan(mossy,91241,s,0,0,false);
        check(!baseline.writes.isEmpty(),"a new natural chunk is painted");
        Map<String,String> a=overlay(baseline),b=overlay(natural);
        for(int x=0;x<16;x++)for(int z=0;z<16;z++){
            check(top(dirt,a,x,z)==top(mossy,b,x,z),"mossy cannot produce a hanging natural pillar");
        }
        check(baseline.writes.size()<=s.maxWrites,"whole chunk write bound");
        check(!WastelandSurface.plan(dirt,91241,s,0,0,true).craters,"reserved HBM chunks do not get craters");
        check(WastelandSurface.plan(dirt,91241,s,0,0,true).writes.isEmpty(),"reserved HBM chunks remain native");
        Fixture chest=new Fixture();chest.special="minecraft:chest#0";
        check(WastelandSurface.plan(chest,91241,s,0,0,false).writes.isEmpty(),"container protects complete local foundation");
        Fixture concrete=new Fixture();concrete.special="hbm:brick_concrete#0";
        check(WastelandSurface.plan(concrete,91241,s,0,0,false).writes.isEmpty(),"HBM architecture survives");
        Fixture tile=new Fixture();tile.hasTile=true;
        check(WastelandSurface.plan(tile,91241,s,0,0,false).writes.isEmpty(),"unknown tile entity fails closed");
        Fixture water=new Fixture();water.water=true;
        check(WastelandSurface.plan(water,91241,s,0,0,false).writes.isEmpty(),"underwater terrain is left native");
        for(WastelandSurface.Write w:baseline.writes){
            check(w.x>=0&&w.x<16&&w.z>=0&&w.z<16,"writes cannot escape current chunk");
            check(w.y>0&&w.y<255,"native build bounds");
            check(!w.state.startsWith("hbm:"),"no radioactive or HBM material added");
        }
        Fixture deepOre=new Fixture();deepOre.deepOre=true;
        check(WastelandSurface.plan(deepOre,91241,s,0,0,false).craters,"unaffected underground ores cannot disable all craters");
        oreRichBorders();
        for(String protectedOre:Arrays.asList("minecraft:bedrock#0","hbm:ore_bedrock_coltan#0","hbm:ore_bedrock_block#0","hbm:ore_bedrock_oil#0")){
            Fixture deposit=new Fixture();deposit.special=protectedOre;
            check(WastelandSurface.plan(deposit,91241,s,0,0,false).writes.isEmpty(),"dedicated bedrock/deposit is protected");
        }
        s.trunkChance=1;
        WastelandSurface.Plan trunk=WastelandSurface.plan(dirt,91241,s,0,0,false);
        boolean log=false;for(WastelandSurface.Write w:trunk.writes)if(w.state.startsWith("minecraft:log#"))log=true;
        check(log,"sparse dead trunks are present without leaves");
        s.maxWrites=1;boolean invalid=false;try{s.validate();}catch(IllegalArgumentException expected){invalid=true;}
        check(invalid,"unsafe whole-chunk budgets are rejected before generation");
    }
    private static void oreRichBorders(){
        WastelandSettings s=new WastelandSettings();s.trunkChance=0;s.small.chance=0;
        s.large=new WastelandSettings.Band(128,1,64,64,18,18,2);s.validate();
        long seed=993571;WastelandField.Crater crater=WastelandField.candidates(seed,s,0,0).get(0);
        int centerX=Math.floorDiv(crater.x,16),centerZ=Math.floorDiv(crater.z,16),cutColumns=0;
        Map<String,Integer> forward=new HashMap<String,Integer>(),reverse=new HashMap<String,Integer>();
        for(int order=0;order<2;order++)for(int ix=0;ix<5;ix++)for(int iz=0;iz<5;iz++){
            int cx=centerX+(order==0?ix:4-ix)-2,cz=centerZ+(order==0?iz:4-iz)-2;
            Fixture ordinary=new Fixture(),ore=new Fixture();ore.oreRich=new String[]{"minecraft:iron_ore#0","hbm:ore_iron#0","mysticalagriculture:prosperity_ore#0","ore_dictionary_fixture:resource#2"}[Math.floorMod(cx+cz,4)];
            WastelandSurface.Plan clean=WastelandSurface.plan(ordinary,seed,s,cx,cz,false);
            WastelandSurface.Plan rich=WastelandSurface.plan(ore,seed,s,cx,cz,false);
            check(rich.craters,"ordinary ores cannot suppress local displacement");
            Map<String,String> cleanWrites=overlay(clean),oreWrites=overlay(rich);
            for(int x=0;x<16;x++)for(int z=0;z<16;z++){
                int absoluteX=cx*16+x,absoluteZ=cz*16+z;
                int target=80+WastelandField.displacement(seed,s,absoluteX,absoluteZ);
                int actual=top(ore,oreWrites,x,z);
                check(actual==target,"ore-rich chunk matches absolute crater field at every border/interior column");
                check(actual==top(ordinary,cleanWrites,x,z),"ordinary ore cannot produce a raised pillar/flat patch");
                if(target<80&&order==0)cutColumns++;
                if(target<=80){
                    check(at(ore,oreWrites,x,target,z).equals(ore.oreRich),"ore at crater floor remains native");
                    check(at(ore,oreWrites,x,target-1,z).equals(ore.oreRich),"ore below crater floor remains native");
                }
                (order==0?forward:reverse).put(absoluteX+":"+absoluteZ,actual);
            }
            for(WastelandSurface.Write w:rich.writes){
                int target=80+WastelandField.displacement(seed,s,cx*16+w.x,cz*16+w.z);
                if(w.y<=80)check(w.y>target&&w.state.equals("minecraft:air#0"),"only ore inside excavated volume can be removed");
                check(w.x>=0&&w.x<16&&w.z>=0&&w.z<16,"ore-rich writes stay in current chunk");
            }
        }
        check(cutColumns>256,"deep ore-rich bowl crosses multiple chunks");
        check(forward.equals(reverse),"ore-rich crater is independent of forward/reverse chunk visitation");
    }
    private static String at(Fixture f,Map<String,String> overlay,int x,int y,int z){String s=overlay.get(key(x,y,z));return s==null?f.state(x,y,z):s;}
    private static String key(int x,int y,int z){return x+":"+y+":"+z;}
    private static Map<String,String> overlay(WastelandSurface.Plan p){Map<String,String> m=new HashMap<String,String>();for(WastelandSurface.Write w:p.writes)m.put(key(w.x,w.y,w.z),w.state);return m;}
    private static int top(Fixture f,Map<String,String> overlay,int x,int z){for(int y=100;y>0;y--){String s=overlay.get(key(x,y,z));if(s==null)s=f.state(x,y,z);if(!s.equals("minecraft:air#0"))return y;}return 0;}
    private static final class Fixture implements WastelandSurface.Terrain {
        boolean mossy,hasTile,water,deepOre;String special,oreRich;
        public int height(int x,int z){bounds(x,80,z);return water?85:81;}
        public String state(int x,int y,int z){
            bounds(x,y,z);if(water&&y>80&&y<=84)return "minecraft:water#0";
            if(special!=null&&x==8&&z==8&&y==80)return special;
            if(deepOre&&y==48)return "minecraft:iron_ore#0";
            if(oreRich!=null&&y<=80)return oreRich;
            if(y>80)return "minecraft:air#0";if(y==80&&mossy&&x==8&&z==8)return "minecraft:mossy_cobblestone#0";
            return y>=78?"minecraft:dirt#0":"minecraft:stone#0";
        }
        public boolean tile(int x,int y,int z){bounds(x,y,z);return hasTile&&x==8&&z==8&&y==80;}
        public boolean ordinaryOre(String state){return state.equals(oreRich)||WastelandSurface.Terrain.super.ordinaryOre(state);}
        private void bounds(int x,int y,int z){if(x<0||x>15||z<0||z>15||y<0||y>255)throw new AssertionError("nonlocal read");}
    }
}
