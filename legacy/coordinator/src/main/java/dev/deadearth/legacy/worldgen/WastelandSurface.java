package dev.deadearth.legacy.worldgen;

import java.util.*;

/** Whole-chunk plan before the first write. The interface cannot read or write a neighbor. */
public final class WastelandSurface {
    private WastelandSurface(){}
    public interface Terrain {
        int height(int localX,int localZ);
        String state(int localX,int y,int localZ);
        boolean tile(int localX,int y,int localZ);
        default boolean ordinaryOre(String state){return ore(state)&&!dedicatedBedrock(state);}
    }
    public static final class Write {
        public final int x,y,z;public final String state;
        Write(int x,int y,int z,String state){this.x=x;this.y=y;this.z=z;this.state=state;}
    }
    public static final class Plan {
        public final List<Write> writes;public final String reason;public final boolean craters;
        public final int surfaceColumns,craterColumns,protectedColumns,unsafeColumns;
        Plan(List<Write> w,String reason,boolean c){this(w,reason,c,0,0,0,0);}
        Plan(List<Write> w,String reason,boolean c,int surfaces,int cuts,int guarded,int unsafe){
            writes=Collections.unmodifiableList(w);this.reason=reason;craters=c;
            surfaceColumns=surfaces;craterColumns=cuts;protectedColumns=guarded;unsafeColumns=unsafe;
        }
    }
    private static final Set<String> NATURAL=new HashSet<String>(Arrays.asList("minecraft:stone","minecraft:dirt","minecraft:grass",
        "minecraft:mycelium","minecraft:sand","minecraft:gravel","minecraft:clay","minecraft:sandstone","minecraft:red_sandstone",
        "minecraft:hardened_clay","minecraft:stained_hardened_clay","minecraft:cobblestone","minecraft:mossy_cobblestone",
        "minecraft:snow","minecraft:ice","minecraft:packed_ice","dead_earth:ash",
        "hbm:stone_gneiss","hbm:stone_depth","hbm:stone_cracked","hbm:stone_porous","hbm:basalt"));
    public static String id(String state){int n=state.indexOf('#');return n<0?state:state.substring(0,n);}
    public static boolean natural(String state){return NATURAL.contains(id(state))||state.equals("minecraft:stonebrick#2");}
    public static boolean water(String state){String id=id(state);return id.equals("minecraft:water")||id.equals("minecraft:flowing_water");}
    static boolean air(String s){return id(s).equals("minecraft:air");}
    static boolean plant(String s){String id=id(s);return id.equals("minecraft:tallgrass")||id.equals("minecraft:double_plant")
        ||id.equals("minecraft:deadbush")||id.equals("minecraft:yellow_flower")||id.equals("minecraft:red_flower")
        ||id.equals("minecraft:brown_mushroom")||id.equals("minecraft:red_mushroom")||id.equals("minecraft:snow_layer")
        ||id.equals("minecraft:reeds")||id.equals("minecraft:waterlily")
        ||id.equals("hbm:plant_flower")||id.equals("hbm:plant_tall")||id.equals("hbm:plant_dead")||id.equals("hbm:reeds");}
    static boolean ore(String s){String id=id(s);return id.endsWith("_ore")||id.endsWith(":ore")
        ||id.startsWith("hbm:ore_")||id.startsWith("hbm:stone_resource")||id.startsWith("hbm:ore")||id.equals("hbm:basalt_ore");}
    /** Ordinary ore follows excavated terrain; dedicated bedrock deposits remain protected. */
    public static boolean dedicatedBedrock(String s){String name=id(s);return name.equals("minecraft:bedrock")
        ||name.startsWith("hbm:ore_bedrock")||name.equals("hbm:sellafield_bedrock");}
    static boolean excavatable(Terrain terrain,String s){return !dedicatedBedrock(s)&&(natural(s)||terrain.ordinaryOre(s));}
    /** Natural mossy/cobble and ordinary ores are intentionally NOT architecture evidence. */
    public static boolean architecture(String s){return !air(s)&&!water(s)&&!natural(s)&&!plant(s)&&!ore(s)
        &&!id(s).equals("minecraft:bedrock")&&!id(s).equals("minecraft:lava")&&!id(s).equals("minecraft:flowing_lava");}
    private static Plan protectedPlan(String reason){return new Plan(new ArrayList<Write>(),reason,false);}
    /** Conservative lower edge of any surface edit/guard band, using only this already loaded chunk.
     * Mirrors plan's ground search, including plants above the true ground and low/wet exclusions. */
    static int minimumAffectedY(Terrain t,WastelandSettings s){
        int depth=Math.max(s.small.depthMax,s.large.depthMax)+s.coatingDepth+2,minimum=255;
        for(int x=0;x<16;x++)for(int z=0;z<16;z++){
            int y=Math.min(255,t.height(x,z)-1);if(y<1)continue;
            while(y>0&&(air(t.state(x,y,z))||plant(t.state(x,y,z))))y--;
            String ground=t.state(x,y,z);
            if(water(ground)||y<depth+2||y>=254||!excavatable(t,ground))continue;
            minimum=Math.min(minimum,Math.max(1,y-depth));
        }
        // No excavatable column: keep reservations conservative; surface-only soil/plant edits need no exemption.
        return minimum==255?0:minimum;
    }
    /** Soil/snow are evidence of exposed natural land, even when an unknown native job owns the chunk.
     * Masonry and native geology can also be natural, but are left exact beside structures/reservations. */
    public static boolean surfaceSoil(String state){
        String name=id(state);return name.equals("minecraft:dirt")||name.equals("minecraft:grass")||name.equals("minecraft:mycelium")
            ||name.equals("minecraft:sand")||name.equals("minecraft:gravel")||name.equals("minecraft:clay")
            ||name.equals("minecraft:hardened_clay")||name.equals("minecraft:stained_hardened_clay")
            ||name.equals("minecraft:snow")||name.equals("minecraft:ice")||name.equals("minecraft:packed_ice");
    }
    public static Plan plan(Terrain t,long seed,WastelandSettings s,int cx,int cz,boolean reserved){
        if(!s.enabled)return protectedPlan("disabled");
        int[] ground=new int[256],tops=new int[256];boolean[] wet=new boolean[256],guarded=new boolean[256];
        int maxDepth=Math.max(s.small.depthMax,s.large.depthMax)+s.coatingDepth+2;
        List<WastelandField.Crater> craters=WastelandField.candidates(seed,s,cx,cz);
        String reason=reserved?"hbm_footprint":"new_chunk";
        // Structural evidence guards a local foundation band, not the coating of the whole chunk.
        // Native deferred/bounding-box reservations separately forbid all excavation in this chunk.
        for(int x=0;x<16;x++)for(int z=0;z<16;z++){
            int i=x*16+z,top=Math.min(255,t.height(x,z)-1),y=top;
            if(top<1){ground[i]=0;tops[i]=top;continue;}
            while(y>0&&(air(t.state(x,y,z))||plant(t.state(x,y,z))))y--;
            wet[i]=water(t.state(x,y,z));ground[i]=y;tops[i]=top;
            for(int scan=Math.max(1,y-maxDepth);scan<=top;scan++){
                String old=t.state(x,scan,z);boolean deposit=dedicatedBedrock(old);
                if(t.tile(x,scan,z)||deposit||architecture(old)&&!t.ordinaryOre(old)){
                    // Two cells retain the immediate native footing and avoid a cut against its wall.
                    for(int dx=Math.max(0,x-2);dx<=Math.min(15,x+2);dx++)
                        for(int dz=Math.max(0,z-2);dz<=Math.min(15,z+2);dz++)guarded[dx*16+dz]=true;
                    if(!reserved)reason=deposit?"bedrock_deposit":"architecture";
                }
            }
        }
        List<Write> writes=new ArrayList<Write>();int surfaces=0,displaced=0,protectedCount=0,unsafe=0;boolean permitsCraters=false;
        for(int x=0;x<16;x++)for(int z=0;z<16;z++){
            int i=x*16+z,g=ground[i],target=g;if(guarded[i])protectedCount++;
            if(g<1||g>=255)continue;
            boolean editable=!wet[i]&&excavatable(t,t.state(x,g,z));
            boolean surfaceOnly=reserved||guarded[i]||g<maxDepth+2||g>=254;
            if(editable&&!surfaceOnly){
                int delta=WastelandField.displacement(craters,cx*16+x,cz*16+z);
                target=Math.max(s.coatingDepth+2,Math.min(253,g+delta));
                String failure=unsafeDisplacement(t,x,z,g,target,s.coatingDepth);
                if(failure!=null){
                    target=g;surfaceOnly=true;unsafe++;if(reason.equals("new_chunk"))reason=failure;
                }else permitsCraters=true;
            }
            int before=writes.size();String coating=WastelandField.surface(seed,s,cx*16+x,cz*16+z);
            if(editable){
                for(int y=Math.max(1,target-s.coatingDepth+1);y<=Math.max(g,target)&&y<255;y++){
                    String old=t.state(x,y,z),next;
                    if(t.tile(x,y,z)||dedicatedBedrock(old))continue;
                    if(y>target)next="minecraft:air#0"; // Only reached after complete cut/fill preflight.
                    else if(y>g)next=coating;
                    else if(natural(old)&&!t.ordinaryOre(old)&&(!surfaceOnly||surfaceSoil(old)))next=coating;
                    else continue; // Exact native ores, masonry beside structures and exposed caves survive.
                    if(!next.equals(old))writes.add(new Write(x,y,z,next));
                }
                if(target!=g)displaced++;
                if(writes.size()>before)surfaces++;
            }
            // Repaint-only/reserved columns also lose natural plants; water blocks are never touched.
            for(int y=Math.max(target+1,g+1);y<=tops[i]&&y<255;y++){
                String old=t.state(x,y,z);if(plant(old)&&!t.tile(x,y,z))writes.add(new Write(x,y,z,"minecraft:air#0"));
            }
        }
        if(writes.size()+s.trunkMax+1>s.maxWrites)return protectedPlan("write_budget");
        if(!reserved)addTrunk(t,writes,seed,s,cx,cz,guarded);
        return new Plan(writes,reason,permitsCraters,surfaces,displaced,protectedCount,unsafe);
    }
    /** Reject the whole column's displacement before writing; its safe surface coat still runs. */
    private static String unsafeDisplacement(Terrain t,int x,int z,int ground,int target,int coatingDepth){
        for(int y=Math.max(1,target-coatingDepth+1);y<=Math.max(ground,target);y++){
            String old=t.state(x,y,z);if(t.tile(x,y,z)||dedicatedBedrock(old))return "unsafe_cut";
            if(y>target&&!excavatable(t,old)&&!air(old)&&!plant(old))return "unsafe_cut";
            if(y>ground&&!air(old)&&!plant(old))return "unsafe_rim";
        }return null;
    }
    private static long key(int x,int y,int z){return (long)x<<12|(long)z<<8|y;}
    private static void addTrunk(Terrain t,List<Write> writes,long seed,WastelandSettings s,int cx,int cz,boolean[] guarded){
        long k=WastelandField.hash(seed,cx,cz,0x5452554e4bL);if(WastelandField.unit(k)>=s.trunkChance)return;
        int x=WastelandField.between(WastelandField.hash(k,cx,cz,1),3,12),z=WastelandField.between(WastelandField.hash(k,cx,cz,2),3,12);
        if(guarded[x*16+z])return;
        Map<Long,String> overlay=new HashMap<Long,String>();for(Write w:writes)overlay.put(key(w.x,w.y,w.z),w.state);
        int y=Math.min(254,t.height(x,z)+Math.max(s.small.rim,s.large.rim));while(y>1&&air(at(t,overlay,x,y,z)))y--;
        if(!natural(at(t,overlay,x,y,z))||water(at(t,overlay,x,y,z)))return;
        int height=WastelandField.between(WastelandField.hash(k,cx,cz,3),s.trunkMin,s.trunkMax);
        if(y+height>=255)return;
        String log="minecraft:log#"+(12+WastelandField.between(WastelandField.hash(k,cx,cz,4),0,1));
        for(int dy=1;dy<=height;dy++)if(!air(at(t,overlay,x,y+dy,z)))return;
        for(int dy=1;dy<=height;dy++)writes.add(new Write(x,y+dy,z,log));
        if(height>=5){int dx=(k&1)==0?1:-1;if(air(at(t,overlay,x+dx,y+height-1,z)))writes.add(new Write(x+dx,y+height-1,z,log));}
    }
    private static String at(Terrain t,Map<Long,String> overlay,int x,int y,int z){String s=overlay.get(key(x,y,z));return s==null?t.state(x,y,z):s;}
}
