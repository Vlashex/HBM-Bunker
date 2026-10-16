package dev.deadearth.difficulty;
import java.util.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;
public final class PlayerStatistics {
 public record Counters(long kills,long damage,long deaths,double health,double x,double z,String dimension) {
  public Counters {if(kills<0||damage<0||deaths<0||!Double.isFinite(health)||health<0||health>1||!Double.isFinite(x)||!Double.isFinite(z)||dimension==null)
   throw new IllegalArgumentException("Invalid player counters");}
 }
 public record Sample(long kills,double damage,long deaths,double health,boolean active) {}
 private final Map<UUID,Counters> previous=new HashMap<>();
 public Optional<Sample> sample(UUID id,Counters now) {
  Objects.requireNonNull(id);if(!previous.containsKey(id)&&previous.size()>=64) return Optional.empty();
  var old=previous.put(id,now);if(old==null) return Optional.empty();
  long kills=delta(now.kills,old.kills),deaths=delta(now.deaths,old.deaths);double damage=delta(now.damage,old.damage)/10d;
  double moved=(now.x-old.x)*(now.x-old.x)+(now.z-old.z)*(now.z-old.z);
  boolean active=kills>0||deaths>0||damage>0||now.health<.8||(now.dimension.equals(old.dimension)&&moved>=1&&moved<=4096);
  return Optional.of(new Sample(kills,damage,deaths,now.health,active));
 }
 public void forget(UUID id){previous.remove(id);}
 public void retain(Set<UUID> online){previous.keySet().retainAll(online);}
 public List<Sample> collect(List<ServerPlayer> players) {
  var online=new HashSet<UUID>();var samples=new ArrayList<Sample>();
  for(var p:players) if(!p.isCreative()&&!p.isSpectator()) {
   online.add(p.getUUID());var stats=p.getStats();
   var now=new Counters(stats.getValue(Stats.CUSTOM.get(Stats.MOB_KILLS)),stats.getValue(Stats.CUSTOM.get(Stats.DAMAGE_TAKEN)),
    stats.getValue(Stats.CUSTOM.get(Stats.DEATHS)),Math.clamp(p.getHealth()/Math.max(1,p.getMaxHealth()),0,1),p.getX(),p.getZ(),p.level().dimension().location().toString());
   sample(p.getUUID(),now).ifPresent(samples::add);
  }
  retain(online);return List.copyOf(samples);
 }
 private static long delta(long now,long old){return now>=old?now-old:0;}
}
