package dev.deadearth.compat;

import dev.deadearth.climate.SeasonState;
import java.util.Set;
import net.minecraft.nbt.CompoundTag;

/** Pinned native adapters never write third-party configuration or stage state. */
public final class SeasonHooks {
    private static final Set<String> COUNTERS=Set.of("Evolve","Evolution","Develop","Timer","medium","heavy",
            "mutationcraft:necroptor_kills","mutationcraft:skeleton_kills");
    private static final java.util.Map<String,Double> FOREST_CARRY=new java.util.HashMap<>();
    private static java.lang.reflect.Method nativeSummon;
    private SeasonHooks() {}
    /** Scoped native read immediately followed by +1: comparison and write see the same progress. */
    public static int counterRead(CompoundTag tag,String key) {return counterRead(tag,key,SeasonState.parasites());}
    public static int counterRead(CompoundTag tag,String key,double factor) {
        int previous=tag.getInt(key);
        if (!COUNTERS.contains(key)) return previous;
        if(!Double.isFinite(factor)||factor<0||factor>16) throw new IllegalArgumentException("Invalid counter factor");
        String own="dead_earth:carry/"+key;
        double carry=tag.getDouble(own);
        if(!Double.isFinite(carry)||carry<0||carry>=1) carry=0;
        double total=carry+factor;
        int delta=(int)Math.floor(total+1e-9);
        tag.putDouble(own,Math.max(0,total-delta));
        return (int)Math.min(Integer.MAX_VALUE,(long)previous+delta)-1;
    }
    public static double[] chances(double[] base,double factor) {
        var result=base.clone();for(int i=0;i<result.length;i++) result[i]=Math.clamp(result[i]*factor,0,1);return result;
    }
    public static int forestBudget(String channel,int base) {
        double total=Math.max(0,base)*SeasonState.forest()+FOREST_CARRY.getOrDefault(channel,0d);
        int amount=(int)Math.min(Integer.MAX_VALUE,Math.floor(total+1e-9));
        FOREST_CARRY.put(channel,Math.max(0,total-amount));return amount;
    }
    public static void hookSummon(net.minecraft.server.level.ServerLevel level,Object hook) {
        var data=((net.minecraft.world.entity.Entity)hook).getPersistentData();
        double total=data.getDouble("dead_earth:summon_carry")+SeasonState.parasites();
        int attempts=(int)Math.floor(total+1e-9);data.putDouble("dead_earth:summon_carry",Math.max(0,total-attempts));
        try {
            if(nativeSummon==null) {
                var type=Class.forName("com.asestefan.mutationcraft.behavior.HookBehavior");
                nativeSummon=java.util.Arrays.stream(type.getDeclaredMethods()).filter(m->m.getName().equals("summon")&&m.getParameterCount()==2).findFirst().orElseThrow();
                nativeSummon.setAccessible(true);
            }
            for(int i=0;i<attempts;i++) nativeSummon.invoke(null,level,hook);
        } catch(ReflectiveOperationException error) {throw new IllegalStateException("Unsupported native hook summons",error);}
    }
    public static void reset() {FOREST_CARRY.clear();nativeSummon=null;}
}
