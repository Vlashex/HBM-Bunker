package dev.deadearth.legacy.climate;

/** Stable absolute-coordinate, smooth value noise, with rare tapered ice peaks per saved winter. */
public final class WinterField {
    private WinterField() {}
    public static final class Profile {
        public final int topIce,topSnow,sideIce,sideSnow,peak;
        Profile(int a,int b,int c,int d,int e) { topIce=a;topSnow=b;sideIce=c;sideSnow=d;peak=e; }
        public int top() { return topIce+topSnow; }
        public int side() { return sideIce+sideSnow; }
    }
    public static long seed(long worldSeed,long winterNumber) { return mix(worldSeed^mix(winterNumber+0x4445414457494E31L)); }
    public static Profile sample(long seed,int x,int z,ClimateConfig c) {
        int ice=(int)Math.round(noise(seed,x,z,c.reliefScale)*c.iceRelief);
        int snow=(int)Math.round(noise(seed^0x15236A1L,x,z,c.reliefScale)*c.snowRelief);
        int side=(int)Math.round(noise(seed^0x4C6933DL,x,z,c.reliefScale)*c.sideRelief);
        int peak=0,cx=Math.floorDiv(x,c.peakSpacing),cz=Math.floorDiv(z,c.peakSpacing);
        for(int dx=-1;dx<=1;dx++)for(int dz=-1;dz<=1;dz++) {
            long cell=hash(seed^0x5045414B5331L,cx+dx,cz+dz);
            if(unit(cell)>=c.peakChance)continue;
            int px=(cx+dx)*c.peakSpacing+(int)(unit(mix(cell+1))*c.peakSpacing);
            int pz=(cz+dz)*c.peakSpacing+(int)(unit(mix(cell+2))*c.peakSpacing);
            int radius=c.peakMinRadius+(int)(unit(mix(cell+3))*(c.peakMaxRadius-c.peakMinRadius+1));
            int height=c.peakMinHeight+(int)(unit(mix(cell+4))*(c.peakMaxHeight-c.peakMinHeight+1));
            double distance=Math.hypot((double)x-px,(double)z-pz);
            if(distance<radius)peak=Math.max(peak,(int)Math.round(height*Math.pow(1-distance/radius,1.6)));
        }
        return new Profile(c.topIce==0?0:c.topIce+ice+peak,c.topSnow==0||c.bareIceTips&&peak>0?0:c.topSnow+snow,
                c.sideIce==0?0:c.sideIce+side,c.sideSnow,peak);
    }
    public static double unit(long value) { return (value>>>11)*0x1.0p-53; }
    public static long mix(long v) { v=(v^(v>>>30))*0xBF58476D1CE4E5B9L; v=(v^(v>>>27))*0x94D049BB133111EBL; return v^(v>>>31); }
    private static long hash(long seed,int x,int z) { return mix(seed^x*0x9E3779B97F4A7C15L^z*0xC2B2AE3D27D4EB4FL); }
    private static double noise(long seed,int x,int z,int scale) {
        int gx=Math.floorDiv(x,scale),gz=Math.floorDiv(z,scale);
        double tx=Math.floorMod(x,scale)/(double)scale,tz=Math.floorMod(z,scale)/(double)scale;
        tx=tx*tx*(3-2*tx);tz=tz*tz*(3-2*tz);
        return lerp(lerp(unit(hash(seed,gx,gz)),unit(hash(seed,gx+1,gz)),tx),lerp(unit(hash(seed,gx,gz+1)),unit(hash(seed,gx+1,gz+1)),tx),tz);
    }
    private static double lerp(double a,double b,double t) { return a+(b-a)*t; }
}
