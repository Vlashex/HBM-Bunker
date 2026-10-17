package dev.deadearth.worldgen;

import java.util.*;

/** Immutable generation snapshot, shared by all worker threads of a server run. */
public record WastelandSettings(Band small,Band large,double ashPatches,double trunkChance,int trunkMin,int trunkMax,
        int coatingDepth,int maxWrites,int waterColor,int waterFogColor,Map<Surface,Integer> palette) {
    public enum Surface {
        STONE("minecraft:stone"),ASH("dead_earth:ash"),COBBLESTONE("minecraft:cobblestone"),ANDESITE("minecraft:andesite"),
        TUFF("minecraft:tuff"),GRAVEL("minecraft:gravel"),BLACKSTONE("minecraft:blackstone"),CRACKED_BRICKS("minecraft:cracked_stone_bricks");
        private final String id;Surface(String id){this.id=id;}public String id(){return id;}
    }
    public record Band(int spacing,double chance,int radiusMin,int radiusMax,int depthMin,int depthMax,int rim) {
        public Band {
            if(spacing<16||spacing>1024||!Double.isFinite(chance)||chance<0||chance>1||radiusMin<2||radiusMax<radiusMin||radiusMax>128
                    ||spacing<2*radiusMax||depthMin<1||depthMax<depthMin||depthMax>32||rim<0||rim>4)throw new IllegalArgumentException("Invalid crater band");
        }
    }
    public WastelandSettings {
        Objects.requireNonNull(small);Objects.requireNonNull(large);palette=Map.copyOf(palette);
        if(!Double.isFinite(ashPatches)||ashPatches<0||ashPatches>1||!Double.isFinite(trunkChance)||trunkChance<0||trunkChance>1
                ||trunkMin<2||trunkMax<trunkMin||trunkMax>14||coatingDepth<1||coatingDepth>5||maxWrites<1024||maxWrites>32768
                ||maxWrites<256*(Math.max(small.depthMax(),large.depthMax())+Math.max(small.rim(),large.rim())+coatingDepth+1)
                ||waterColor<0||waterColor>0xffffff||waterFogColor<0||waterFogColor>0xffffff||palette.isEmpty()
                ||palette.values().stream().anyMatch(v->v==null||v<0||v>1000)||palette.values().stream().mapToInt(Integer::intValue).sum()<1)
            throw new IllegalArgumentException("Invalid wasteland settings");
    }
    public static WastelandSettings defaults() {
        return new WastelandSettings(new Band(24,.85,3,9,2,5,1),new Band(384,.35,32,64,10,22,3),.28,.35,3,9,3,16384,
            0x62613f,0x343a29,Map.of(Surface.STONE,38,Surface.ASH,22,Surface.COBBLESTONE,13,Surface.ANDESITE,8,
                Surface.TUFF,7,Surface.GRAVEL,7,Surface.BLACKSTONE,3,Surface.CRACKED_BRICKS,2));
    }
}
