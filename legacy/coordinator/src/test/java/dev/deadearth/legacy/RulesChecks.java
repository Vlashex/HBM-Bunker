package dev.deadearth.legacy;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import dev.deadearth.legacy.kernel.*;
public final class RulesChecks {
 static int count;static void check(boolean c){count++;if(!c)throw new AssertionError("Rule check "+count);}
 public static void main(String[] args)throws Exception{Settings s=new Settings();s.validate();check(Math.abs(s.intro(0)-.1)<1e-9);check(s.intro(s.introTicks)==1);check(s.intro(s.introTicks/2)>.55);check(s.season(0).equals("CALM"));check(s.season(s.seasonTicks).equals("HEAT"));check(s.season(s.seasonTicks*2).equals("ASH_WINTER"));check(s.seasonMultiplier(s.seasonTicks)==.5);check(s.seasonMultiplier(s.seasonTicks*2)==1.8);check(s.controls(0)&&!s.controls(-1));
 check(CinematicCatalog.strict("{\"blocks\":{}}",16*1024*1024,true).getAsJsonObject().has("blocks"));
 Path dir=Files.createTempDirectory("dead-earth-rules-");Path file=dir.resolve("campaign.json");try{Campaign campaign=Campaign.open(file);campaign.cinematic=new CinematicEngine().snapshot();campaign.receipts.put(UUID.randomUUID().toString(),new Campaign.Receipt("test",JsonFiles.map("phase",10,"revision",7L)));JsonFiles.write(file,campaign);Campaign restored=Campaign.open(file);check(restored.campaignId.equals(campaign.campaignId));String encoded=JsonFiles.JSON.toJson(restored.receipts);check(encoded.contains("\"phase\":10")&&!encoded.contains("10.0"));for(String invalid:Arrays.asList("{}","{\"schema\":1}","{\"schema\":1,\"schema\":1}")){Files.write(file,invalid.getBytes(StandardCharsets.UTF_8));boolean failed=false;try{Campaign.open(file);}catch(Exception e){failed=true;}check(failed);check(new String(Files.readAllBytes(file),StandardCharsets.UTF_8).equals(invalid));}}finally{Files.deleteIfExists(file);Files.deleteIfExists(dir);}System.out.println("RulesChecks: "+count+" checks passed");}
}