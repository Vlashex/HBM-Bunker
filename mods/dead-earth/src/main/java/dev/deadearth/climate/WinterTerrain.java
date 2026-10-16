package dev.deadearth.climate;

/** Stateless height field, algorithm1: world seed + saved campaign winter number. */
public final class WinterTerrain {
    public record Settings(int scale,int iceRelief,int snowRelief,int sideRelief,int peakSpacing,double peakChance,
                           int peakMinHeight,int peakMaxHeight,int peakMinRadius,int peakMaxRadius) {
        public Settings {
            if(scale<8||scale>128||iceRelief<0||iceRelief>4||snowRelief<0||snowRelief>2||sideRelief<0||sideRelief>2
                    ||peakSpacing<16||peakSpacing>256||!Double.isFinite(peakChance)||peakChance<0||peakChance>1
                    ||peakMinHeight<1||peakMaxHeight>16||peakMinHeight>peakMaxHeight
                    ||peakMinRadius<1||peakMaxRadius>8||peakMinRadius>peakMaxRadius)throw new IllegalArgumentException("Invalid winter relief settings");
        }
    }
    public record Relief(int iceExtra,int snowExtra,int sideExtra,int peakExtra) {}
    public record Thickness(int topIce,int topSnow,int sideIce,int sideSnow) {public int top(){return topIce+topSnow;}}
    private WinterTerrain() {}
    public static Thickness thickness(Relief r,int ice,int snow,int sideIce,int sideSnow,boolean bareIceTips) {
        return new Thickness(ice>0?ice+r.iceExtra()+r.peakExtra():0,
                snow>0&&!(ice>0&&bareIceTips&&r.peakExtra()>0)?snow+r.snowExtra():0,
                sideIce>0?sideIce+r.sideExtra():0,sideSnow);
    }
    public static long seed(long worldSeed,long winterNumber) {return mix(worldSeed^mix(winterNumber+0x4445414457494E31L));}
    public static Relief sample(long seed,int x,int z,Settings s) {
        int ice=(int)Math.round(noise(seed,x,z,s.scale())*s.iceRelief());
        int snow=(int)Math.round(noise(seed^0x15236A1L,x,z,s.scale())*s.snowRelief());
        int side=(int)Math.round(noise(seed^0x4C6933DL,x,z,s.scale())*s.sideRelief());
        int best=0,cx=Math.floorDiv(x,s.peakSpacing()),cz=Math.floorDiv(z,s.peakSpacing());
        if(s.peakChance()>0)for(int dx=-1;dx<=1;dx++)for(int dz=-1;dz<=1;dz++) {
            long cell=hash(seed^0x5045414B5331L,cx+dx,cz+dz);
            if(unit(cell)>=s.peakChance())continue;
            int px=(cx+dx)*s.peakSpacing()+(int)(unit(mix(cell+1))*s.peakSpacing());
            int pz=(cz+dz)*s.peakSpacing()+(int)(unit(mix(cell+2))*s.peakSpacing());
            int radius=s.peakMinRadius()+(int)(unit(mix(cell+3))*(s.peakMaxRadius()-s.peakMinRadius()+1));
            int height=s.peakMinHeight()+(int)(unit(mix(cell+4))*(s.peakMaxHeight()-s.peakMinHeight()+1));
            double distance=Math.hypot((double)x-px,(double)z-pz);
            if(distance<radius)best=Math.max(best,(int)Math.round(height*Math.pow(1-distance/radius,1.6)));
        }
        return new Relief(ice,snow,side,best);
    }
    private static double noise(long seed,int x,int z,int scale) {
        int gx=Math.floorDiv(x,scale),gz=Math.floorDiv(z,scale);
        double tx=Math.floorMod(x,scale)/(double)scale,tz=Math.floorMod(z,scale)/(double)scale;
        tx=tx*tx*(3-2*tx);tz=tz*tz*(3-2*tz);
        return lerp(lerp(unit(hash(seed,gx,gz)),unit(hash(seed,gx+1,gz)),tx),
                lerp(unit(hash(seed,gx,gz+1)),unit(hash(seed,gx+1,gz+1)),tx),tz);
    }
    private static double lerp(double a,double b,double t) {return a+(b-a)*t;}
    private static long hash(long seed,int x,int z) {return mix(seed^(x*0x9E3779B97F4A7C15L)^(z*0xC2B2AE3D27D4EB4FL));}
    private static double unit(long value) {return (value>>>11)*0x1.0p-53;}
    private static long mix(long v) {v=(v^(v>>>30))*0xBF58476D1CE4E5B9L;v=(v^(v>>>27))*0x94D049BB133111EBL;return v^(v>>>31);}
}
