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
        Plan(List<Write> w,String reason,boolean c){writes=Collections.unmodifiableList(w);this.reason=reason;craters=c;}
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
        // No editable column: retain conservative reservations; the normal plan makes no edits anyway.
        return minimum==255?0:minimum;
    }
    public static Plan plan(Terrain t,long seed,WastelandSettings s,int cx,int cz,boolean reserved){
        if(!s.enabled)return protectedPlan("disabled");if(reserved)return protectedPlan("hbm_footprint");
        int[] ground=new int[256],tops=new int[256];boolean[] wet=new boolean[256];
        int maxDepth=Math.max(s.small.depthMax,s.large.depthMax)+s.coatingDepth+2;
        List<WastelandField.Crater> craters=WastelandField.candidates(seed,s,cx,cz);
        for(int x=0;x<16;x++)for(int z=0;z<16;z++){
            int i=x*16+z,top=Math.min(255,t.height(x,z)-1),y=top;
            if(top<1){ground[i]=0;tops[i]=top;continue;}
            while(y>0&&(air(t.state(x,y,z))||plant(t.state(x,y,z))))y--;
            wet[i]=water(t.state(x,y,z));ground[i]=y;tops[i]=top;
            // Preserve structures, all block entities and dedicated bedrock deposits in the affected band.
            // Ordinary ore cells do not reserve a column/chunk or alter the absolute crater field.
            for(int scan=Math.max(1,y-maxDepth);scan<=top;scan++){
                String old=t.state(x,scan,z);
                if(t.tile(x,scan,z)||architecture(old)&&!t.ordinaryOre(old))return protectedPlan("architecture");
                if(dedicatedBedrock(old))return protectedPlan("bedrock_deposit");
            }
        }
        List<Write> writes=new ArrayList<Write>();
        for(int x=0;x<16;x++)for(int z=0;z<16;z++){
            int i=x*16+z,g=ground[i];if(wet[i]||g<maxDepth+2||g>=254||!excavatable(t,t.state(x,g,z)))continue;
            int delta=WastelandField.displacement(craters,cx*16+x,cz*16+z);
            int target=Math.max(s.coatingDepth+2,Math.min(253,g+delta));
            String coating=WastelandField.surface(seed,s,cx*16+x,cz*16+z);
            for(int y=target-s.coatingDepth+1;y<=Math.max(g,target);y++){
                String old=t.state(x,y,z),next;
                if(y>target){if(!excavatable(t,old)&&!air(old)&&!plant(old))return protectedPlan("unsafe_cut");next="minecraft:air#0";}
                else if(y>g){if(!air(old)&&!plant(old))return protectedPlan("unsafe_rim");next=coating;}
                else if(natural(old)&&!t.ordinaryOre(old))next=coating;
                else continue; // Keep native ores at/below the bowl and never fill an exposed cave.
                if(!next.equals(old))writes.add(new Write(x,y,z,next));
            }
            // A snow layer/grass immediately above removed terrain must not remain suspended.
            if(target!=g)for(int y=Math.max(target+1,g+1);y<=tops[i]&&y<256;y++){
                String old=t.state(x,y,z);if(plant(old)&&!water(old))writes.add(new Write(x,y,z,"minecraft:air#0"));
            }
        }
        if(writes.size()+s.trunkMax+1>s.maxWrites)return protectedPlan("write_budget");
        addTrunk(t,writes,seed,s,cx,cz);
        return new Plan(writes,"new_chunk",true);
    }
    private static long key(int x,int y,int z){return (long)x<<12|(long)z<<8|y;}
    private static void addTrunk(Terrain t,List<Write> writes,long seed,WastelandSettings s,int cx,int cz){
        long k=WastelandField.hash(seed,cx,cz,0x5452554e4bL);if(WastelandField.unit(k)>=s.trunkChance)return;
        int x=WastelandField.between(WastelandField.hash(k,cx,cz,1),3,12),z=WastelandField.between(WastelandField.hash(k,cx,cz,2),3,12);
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
