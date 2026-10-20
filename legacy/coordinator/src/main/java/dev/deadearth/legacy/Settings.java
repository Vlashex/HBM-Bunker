package dev.deadearth.legacy;
import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
public final class Settings {
 public int[] controlledDimensions={0};
 public boolean enabled=true;public long introTicks=72000,bunkerGraceTicks=216000,seasonTicks=144000;
 public double xpWeight=.1,armorWeight=.25,killWeight=.1,deathWeight=2,damageWeight=.01;
 public double introMinimum=.1,scoreCoefficient=.025,maximumDeviation=.75,evolutionBaseRate=.0125,evolutionMaximumMultiplier=2,healthScoreCoefficient=.2,damageScoreCoefficient=.15;
 public double winterParasites=1.8,summerParasites=.5,resourceCoefficient=1;
 public int scanTicks=200,tilesPerTick=32,maximumZoneTiles=4096,zoneRadiusChunks=1,maximumZones=6;
 public Map<String,Long> resourcePrices=new LinkedHashMap<String,Long>();public Map<String,Integer> turretRanks=new LinkedHashMap<String,Integer>();
 public String revision;
 public static Settings load(Path p)throws Exception{JsonObject raw=JsonFiles.read(p,65536);Settings s=JsonFiles.JSON.fromJson(raw,Settings.class);for(Map.Entry<String,JsonElement> e:raw.entrySet())try{Settings.class.getField(e.getKey());}catch(NoSuchFieldException ex){throw new IllegalArgumentException("Unknown setting "+e.getKey());}s.revision=JsonFiles.sha(raw.toString());s.validate();return s;}
 public boolean controls(int dimension){for(int value:controlledDimensions)if(value==dimension)return true;return false;}
 public void validate(){if(controlledDimensions==null||controlledDimensions.length<1||controlledDimensions.length>8)throw new IllegalArgumentException("Invalid controlled dimensions");if(!finite(xpWeight,0,10)||!finite(armorWeight,0,10)||!finite(killWeight,0,10)||!finite(deathWeight,0,100)||!finite(damageWeight,0,10)||introTicks<20||bunkerGraceTicks<0||seasonTicks<200||!finite(introMinimum,.01,1)||!finite(scoreCoefficient,0,10)||!finite(maximumDeviation,0,3)||!finite(evolutionBaseRate,0,100)||!finite(evolutionMaximumMultiplier,.01,10)||!finite(healthScoreCoefficient,0,10)||!finite(damageScoreCoefficient,0,10)||!finite(winterParasites,.01,10)||!finite(summerParasites,.01,10)||!finite(resourceCoefficient,0,100)||scanTicks<20||scanTicks>2400||tilesPerTick<1||tilesPerTick>128||maximumZoneTiles<1||maximumZoneTiles>65536||zoneRadiusChunks<0||zoneRadiusChunks>2||maximumZones<1||maximumZones>16)throw new IllegalArgumentException("Invalid coordinator config");for(Map.Entry<String,Long> e:resourcePrices.entrySet())if(!e.getKey().matches("[a-z0-9_]+:[a-z0-9_./]+(:[0-9]+)?")||e.getValue()<0||e.getValue()>1000000000)throw new IllegalArgumentException("Invalid resource price");}
 static boolean finite(double x,double low,double high){return Double.isFinite(x)&&x>=low&&x<=high;}
 public static double clamp(double x,double a,double b){return Math.max(a,Math.min(b,x));}
 public double intro(long ticks){double t=clamp((double)ticks/introTicks,0,1);return introMinimum+(1-introMinimum)*(1-(1-t)*(1-t)*(1-t));}
 public String season(long ticks){int index=(int)((ticks/seasonTicks)%3);return index==0?"CALM":index==1?"HEAT":"ASH_WINTER";}
 public double seasonMultiplier(long ticks){String v=season(ticks);return v.equals("HEAT")?summerParasites:v.equals("ASH_WINTER")?winterParasites:1;}
}
