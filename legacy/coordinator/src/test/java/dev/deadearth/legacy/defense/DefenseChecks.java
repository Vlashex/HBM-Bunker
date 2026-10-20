package dev.deadearth.legacy.defense;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
/** Deferred pure-state regression executable. This file does not load Minecraft. */
public final class DefenseChecks {
 private static void require(boolean b,String detail){if(!b)throw new AssertionError(detail);}
 public static void main(String[] args)throws Exception{
  Path dir=Files.createTempDirectory("dead-earth-defense-check-");Path file=dir.resolve("hp.json");DefenseDamageStore store=new DefenseDamageStore(file,64);String a="0:1:64:1",wall="dead_earth:defense_wall_v1";
  require(DefenseDamageStore.hp(1)==1000&&DefenseDamageStore.hp(2)==2000&&DefenseDamageStore.hp(3)==8000,"exact tier HP");
  require(store.remaining(a,wall,1)==1000&&store.size()==0,"pristine HP consumes no sparse record");
  require(store.damage(a,wall,1,100,100,20,true)==900,"first hostile attack");require(store.damage(a,wall,1,100,101,20,true)==900,"cooldown absorbs repeated writes");
  store=new DefenseDamageStore(file,64);require(store.remaining(a,wall,1)==900,"actual HP reloads");require(store.damage(a,wall,1,100,119,20,true)==900,"cooldown reloads");require(store.damage(a,wall,1,100,120,20,true)==800,"cooldown boundary accepts hit");require(store.damage(a,wall,1,100,120,20,false)==700,"explosion damage has independent attack admission");
  String v3="dead_earth:defense_wall_v3";require(store.damage(a,v3,3,100,120,20,true)==7900,"replaced material does not inherit old tier HP");require(store.damage(a,v3,3,100000,121,20,false)==0,"finite terminal HP");store.removed(a);require(store.remaining(a,v3,3)==8000,"removed/remodeled block clears damage");
  for(int i=0;i<64;i++)store.damage("0:"+i+":64:0",wall,1,1,1,20,false);require(store.size()==64,"bounded sparse records");require(store.damage("1:999:64:0",wall,1,1,1,20,false)==1000&&store.size()==64,"capacity rejection cannot fabricate terminal HP or destroy a pristine block");
  Path batchPath=dir.resolve("batch.json");DefenseDamageStore batch=new DefenseDamageStore(batchPath,64);java.util.List<DefenseDamageStore.Hit> hits=new java.util.ArrayList<DefenseDamageStore.Hit>();java.util.List<String> removed=new java.util.ArrayList<String>();for(int i=0;i<64;i++){String key="0:"+i+":80:1";hits.add(new DefenseDamageStore.Hit(key,wall,1,1000,200,false));removed.add(key);}batch.damageBatch(hits,20);require(batch.checkpoints()==1,"many simultaneous attacks create one snapshot");DefenseDamageStore durable=new DefenseDamageStore(batchPath,64);require(durable.remaining(removed.get(0),wall,1)==0&&durable.remaining(removed.get(63),wall,1)==0,"all terminal HP is durable before physical removal");batch.removedBatch(removed);require(batch.checkpoints()==2&&batch.size()==0,"one cleanup snapshot covers all terminal blocks");
  byte[] future="{\"schema\":2,\"blocks\":{}}".getBytes(StandardCharsets.UTF_8);Files.write(file,future);boolean refused=false;try{new DefenseDamageStore(file,64);}catch(IllegalStateException expected){refused=true;}require(refused&&java.util.Arrays.equals(future,Files.readAllBytes(file)),"unknown/corrupt format remains untouched");
  System.out.println("Defense HP/cooldown/reload/finite bounds assertions passed.");
 }
}
