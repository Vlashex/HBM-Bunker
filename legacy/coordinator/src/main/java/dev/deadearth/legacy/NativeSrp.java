package dev.deadearth.legacy;
import java.util.*;
/** All changes use SRP's public SaveData API, including its native phase ability hooks. */
public final class NativeSrp {
 private final Class<?> save=Reflect.type("com.dhanantry.scapeandrunparasites.world.SRPSaveData");
 private final Class<?> systems=Reflect.type("com.dhanantry.scapeandrunparasites.util.config.SRPConfigSystems");
 private static final String[] NUM={"One","Two","Three","Four","Five","Six","Seven","Eight","Nine","Ten"};
 public final int[] thresholds=new int[11];
 public NativeSrp(){if(!(Boolean)Reflect.get(systems,"useEvolution"))throw new IllegalStateException("SRP evolution must be enabled");for(int i=1;i<=10;i++){thresholds[i]=((Number)Reflect.get(systems,"phaseKills"+NUM[i-1])).intValue();if(thresholds[i]<=thresholds[i-1])throw new IllegalStateException("Non-increasing SRP thresholds");}Reflect.set(systems,"evolutionNoPlayerMultipler",true);}
 public Object data(Object world){return Reflect.call(save,"get",world);}
 public void lock(Object world){Object d=data(world);int dim=Reflect.dimension(world);if((Boolean)Reflect.call(d,"getCanGain",dim))Reflect.call(d,"setGaining",false,dim);if(!(Boolean)Reflect.call(d,"getCanLoss",dim))Reflect.call(d,"setLoss",true,dim);}
 public int phase(Object world){return ((Number)Reflect.call(data(world),"getEvolutionPhase",Reflect.dimension(world))).intValue();}
 public void sync(Object world,int phase,double points){lock(world);Object d=data(world);int dim=Reflect.dimension(world);if(phase(world)!=phase)Reflect.call(d,"setEvolutionPhase",dim,(byte)phase,false,world,true);int wanted=(int)Math.min(Integer.MAX_VALUE,Math.floor(points));if(((Number)Reflect.call(d,"getTotalKills",dim)).intValue()!=wanted){boolean pause=(Boolean)Reflect.get(systems,"evolutionNoPlayerMultipler");try{Reflect.set(systems,"evolutionNoPlayerMultipler",false);if(!(Boolean)Reflect.call(d,"setTotalKills",dim,wanted,false,world,true))throw new IllegalStateException("SRP refused absolute sync");}finally{Reflect.set(systems,"evolutionNoPlayerMultipler",pause);}}}
 public int phaseFor(double points){int result=0;while(result<10&&points>=thresholds[result+1])result++;return result;}
}
