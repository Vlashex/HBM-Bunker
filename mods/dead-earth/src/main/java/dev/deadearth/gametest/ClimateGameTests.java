package dev.deadearth.gametest;

import com.google.gson.JsonObject;
import dev.deadearth.DeadEarth;
import dev.deadearth.bridge.BridgeController;
import dev.deadearth.bridge.BridgeProtocol;
import dev.deadearth.campaign.SavedDataAccess;
import dev.deadearth.climate.ClimateStore;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("dead_earth")
@PrefixGameTestTemplate(false)
public final class ClimateGameTests {
    @GameTest(template="empty",batch="climate")
    public static void worldDayTimeAndReadsDoNotAdvanceClimate(GameTestHelper helper) {
        var level=helper.getLevel();var runtime=DeadEarth.runtime(level.getServer());
        var before=runtime.climateStatus();long dayTime=level.getDayTime();
        try {
            level.setDayTime(dayTime+1000000);runtime.climateForecast();
            helper.assertTrue(before.equals(runtime.climateStatus()),"World dayTime or read advanced climate");
            boolean rejected=CompletableFuture.supplyAsync(runtime::climateStatus).handle((r,e)->e!=null).join();
            helper.assertTrue(rejected,"Live climate read allowed off server thread");
        } finally {level.setDayTime(dayTime);}
        helper.succeed();
    }
    @GameTest(template="empty",batch="climate")
    public static void climateCheckpointPreservesNotices(GameTestHelper helper) throws Exception {
        var server=helper.getLevel().getServer();UUID campaign=UUID.randomUUID();
        var store=ClimateStore.create(campaign,0);store.advance(0,true);store.advance(42000,true);
        SavedDataAccess.checkpoint(helper.getLevel(),"dead_earth_gametest_climate",store);
        var path=server.getWorldPath(LevelResource.ROOT).resolve("data/dead_earth_gametest_climate.dat");
        var raw=NbtIo.readCompressed(path,NbtAccounter.create(1024*1024));
        var restored=ClimateStore.load(campaign,42000,raw.getCompound("data"),server.registryAccess());
        helper.assertTrue(restored.enabled() && restored.advance(42000,true).isEmpty(),"Checkpoint replayed climate notice");
        helper.assertTrue(restored.cycle().view(48000).phase()==dev.deadearth.climate.ClimateCycle.Phase.HEAT,"Restored cycle changed boundary");
        helper.succeed();
    }
    @GameTest(template="empty",batch="climate")
    public static void climateProjectionTravelsThroughExistingCache(GameTestHelper helper) {
        var server=helper.getLevel().getServer();var runtime=DeadEarth.runtime(server);
        try(var bridge=new BridgeController(server,runtime)) {
            bridge.start();var before=runtime.climateStatus();
            var result=CompletableFuture.supplyAsync(()->bridge.request(new BridgeProtocol.Request("get_snapshot",new JsonObject())).join()).join();
            var climate=result.getAsJsonObject().getAsJsonObject("snapshot").getAsJsonObject("climate");
            helper.assertTrue(climate.get("nextTransitionTick").getAsLong()>climate.get("observedActiveTick").getAsLong(),"Cached climate window is invalid");
            helper.assertTrue(before.equals(runtime.climateStatus()),"Worker cache read consumed climate state");
        }
        helper.succeed();
    }
}
