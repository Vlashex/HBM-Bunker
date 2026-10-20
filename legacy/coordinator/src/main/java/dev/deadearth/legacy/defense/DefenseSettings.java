package dev.deadearth.legacy.defense;
import com.google.gson.JsonObject;
import dev.deadearth.legacy.JsonFiles;
import java.nio.file.*;
final class DefenseSettings {
 final int hostileDamage,cooldownTicks,explosionDamagePerPower,maximumDamagedBlocks,hostileAnchorsPerTick,maximumPendingAttacks;
 DefenseSettings(int damage,int cooldown,int blast,int maximum,int anchors,int pending){hostileDamage=damage;cooldownTicks=cooldown;explosionDamagePerPower=blast;maximumDamagedBlocks=maximum;hostileAnchorsPerTick=anchors;maximumPendingAttacks=pending;}
 static DefenseSettings load(Path path){try{JsonObject j=JsonFiles.read(path,16384);if(j.get("schema").getAsInt()!=1)throw new IllegalArgumentException("Unknown defense config schema");return new DefenseSettings(integer(j,"hostileDamage",1,8000),integer(j,"cooldownTicks",1,1200),integer(j,"explosionDamagePerPower",1,100000),integer(j,"maximumDamagedBlocks",64,65536),integer(j,"hostileAnchorsPerTick",1,256),integer(j,"maximumPendingAttacks",64,4096));}catch(Exception e){throw new IllegalStateException("Invalid defense configuration; original file preserved",e);}}
 private static int integer(JsonObject j,String key,int min,int max){int v=j.get(key).getAsInt();if(v<min||v>max||!j.get(key).getAsString().equals(Integer.toString(v)))throw new IllegalArgumentException(key);return v;}
}
