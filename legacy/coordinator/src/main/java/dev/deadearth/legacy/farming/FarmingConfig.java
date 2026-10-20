package dev.deadearth.legacy.farming;

import com.google.gson.*;
import dev.deadearth.legacy.JsonFiles;
import java.nio.file.*;
import java.util.Map;

public final class FarmingConfig {
    public boolean enabled=true,requireLight=true;
    public int cycleTicks=1200,pollTicks=20,minimumLight=9,outputSlots=9,harvestAttemptsPerTick=16,maxNativeDrops=32,maxQueuedTrays=4096;
    public static FarmingConfig load(Path path) {
        try {
            if(!Files.exists(path)){FarmingConfig c=new FarmingConfig();JsonFiles.write(path,c);return c;}
            JsonObject raw=JsonFiles.read(path,16384);
            for(Map.Entry<String,JsonElement> e:raw.entrySet())FarmingConfig.class.getField(e.getKey());
            FarmingConfig c=JsonFiles.JSON.fromJson(raw,FarmingConfig.class);c.validate();return c;
        }catch(Exception e){throw new IllegalStateException("Invalid dead-earth/farming.json",e);}
    }
    public void validate() {
        if(cycleTicks<50||cycleTicks>72000||pollTicks<1||pollTicks>200||minimumLight<0||minimumLight>15||outputSlots<1||outputSlots>27||harvestAttemptsPerTick<1||harvestAttemptsPerTick>128||maxNativeDrops<1||maxNativeDrops>128||maxQueuedTrays<16||maxQueuedTrays>65536)throw new IllegalArgumentException("Farming configuration bounds");
    }
}
