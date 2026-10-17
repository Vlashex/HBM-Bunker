package dev.deadearth.worldgen;

import java.util.*;

/** Pure absolute-coordinate field: independent of worldgen worker and chunk visitation order. */
public final class WastelandField {
    public record Crater(int x,int z,int radius,int depth,int rim,boolean large,long shapeSeed) {}
    private WastelandField(){}
    public static long hash(long seed,int x,int z,long salt) {
        long value=seed^((long)x*0x9e3779b97f4a7c15L)^Long.rotateLeft((long)z*0xc2b2ae3d27d4eb4fL,27)^salt;
        value=(value^(value>>>30))*0xbf58476d1ce4e5b9L;value=(value^(value>>>27))*0x94d049bb133111ebL;return value^(value>>>31);
    }
    public static double unit(long value){return ((value>>>11)+.5)*0x1.0p-53;}
    private static int between(long value,int min,int max){return min+(int)Math.floor(unit(value)*(max-min+1));}
    public static List<Crater> candidates(long seed,WastelandSettings settings,int chunkX,int chunkZ) {
        var result=new ArrayList<Crater>();append(result,seed,settings.small(),chunkX,chunkZ,false);
        append(result,seed,settings.large(),chunkX,chunkZ,true);return List.copyOf(result);
    }
    private static void append(List<Crater> result,long seed,WastelandSettings.Band band,int chunkX,int chunkZ,boolean large) {
        if(band.chance()==0)return;
        int minX=Math.multiplyExact(chunkX,16),minZ=Math.multiplyExact(chunkZ,16),reach=(int)Math.ceil(band.radiusMax()*1.2)+2;
        long salt=large?0x4c41524745435241L:0x534d414c4c435241L;
        for(int gx=Math.floorDiv(minX-reach,band.spacing());gx<=Math.floorDiv(minX+15+reach,band.spacing());gx++)
            for(int gz=Math.floorDiv(minZ-reach,band.spacing());gz<=Math.floorDiv(minZ+15+reach,band.spacing());gz++) {
                long key=hash(seed,gx,gz,salt);if(unit(key)>=band.chance())continue;
                int x=gx*band.spacing()+between(hash(key,gx,gz,1),0,band.spacing()-1);
                int z=gz*band.spacing()+between(hash(key,gx,gz,2),0,band.spacing()-1);
                int radius=between(hash(key,gx,gz,3),band.radiusMin(),band.radiusMax());
                int depth=between(hash(key,gx,gz,4),band.depthMin(),band.depthMax());
                result.add(new Crater(x,z,radius,depth,band.rim(),large,key));
            }
    }
    public static int displacement(List<Crater> craters,int x,int z) {
        int cut=0,rim=0;
        for(var c:craters) {
            double dx=(double)x-c.x(),dz=(double)z-c.z(),angle=Math.atan2(dz,dx),phase=unit(c.shapeSeed())*Math.PI*2;
            double rough=1+.04*Math.sin(angle*3+phase)+.02*Math.sin(angle*7-phase);
            double distance=Math.hypot(dx,dz)/(c.radius()*rough);if(distance>1.2)continue;
            double bowl=distance<1?-c.depth()*Math.pow(1-distance*distance,1.5):0;
            double lip=c.rim()*Math.max(0,1-Math.abs(distance-1)/.18);
            int value=(int)Math.round(bowl+lip);cut=Math.min(cut,value);rim=Math.max(rim,value);
        }
        return cut<0?cut:rim;
    }
    public static int displacement(long seed,WastelandSettings settings,int x,int z) {
        return displacement(candidates(seed,settings,Math.floorDiv(x,16),Math.floorDiv(z,16)),x,z);
    }
    public static WastelandSettings.Surface surface(long seed,WastelandSettings settings,int x,int z) {
        var weights=settings.palette();
        if(weights.getOrDefault(WastelandSettings.Surface.ASH,0)>0&&unit(hash(seed,Math.floorDiv(x,7),Math.floorDiv(z,7),0x415348))<settings.ashPatches())
            return WastelandSettings.Surface.ASH;
        int total=weights.values().stream().mapToInt(Integer::intValue).sum();
        int choice=(int)(unit(hash(seed,x,z,0x525542424c45L))*total);
        // Enum order is stable, Map.copyOf iteration order is deliberately not used.
        for(var type:WastelandSettings.Surface.values()){choice-=weights.getOrDefault(type,0);if(choice<0)return type;}
        throw new IllegalStateException("Empty wasteland palette");
    }
}
