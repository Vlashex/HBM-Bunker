package dev.deadearth.gametest;

import com.google.gson.JsonObject;
import dev.deadearth.DeadEarth;
import dev.deadearth.bridge.BridgeController;
import dev.deadearth.bridge.BridgeProtocol;
import dev.deadearth.scenario.ScenarioChoice;
import dev.deadearth.scenario.RelayRadioExecutor;
import dev.deadearth.scenario.ScenarioTypes.*;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("dead_earth")
@PrefixGameTestTemplate(false)
public final class RadioGameTests {
    @GameTest(template="empty",batch="radio",timeoutTicks=520)
    public static void discoveredRelayRunsSelectedRadioOnceThroughBridge(GameTestHelper helper) {
        var level=helper.getLevel();var origin=new BlockPos(3600,96,3200);ExpeditionGameTests.prepare(level,origin);
        level.setChunkForced(origin.getX()>>4,origin.getZ()>>4,true);
        var runtime=DeadEarth.runtime(level.getServer());var site=runtime.placeRelay(UUID.randomUUID(),origin);
        var choice=new ScenarioChoice(ScenarioChoice.RADIO,"relay:"+site.id(),"survey",200);
        helper.assertTrue(!runtime.preview(UUID.randomUUID(),choice).accepted(),"Undiscovered target accepted");
        var client=FoundationGameTests.attach(helper);client.player().teleportTo(origin.getX()+4.5,origin.getY()+1,origin.getZ()+3.5);
        helper.runAfterDelay(210,()->{
            var bridge=new BridgeController(level.getServer(),runtime);bridge.start();
            var args=new JsonObject();args.addProperty("request_id",UUID.randomUUID().toString());
            args.addProperty("catalog_id",choice.catalogId());args.addProperty("target_id",choice.targetId());args.addProperty("variant",choice.variant());args.addProperty("delay_ticks",choice.delayTicks());
            var pending=bridge.request(new BridgeProtocol.Request("propose_scenario",args));bridge.tick();
            var receipt=pending.join().getAsJsonObject();
            helper.assertTrue(receipt.get("state").getAsString().equals("SCHEDULED"),"Discovered radio not scheduled");
            helper.runAfterDelay(25,()->{
                var repeated=bridge.request(new BridgeProtocol.Request("propose_scenario",args));bridge.tick();
                helper.assertTrue(receipt.get("scenarioId").equals(repeated.join().getAsJsonObject().get("scenarioId")),"Later retry changed schedule identity");
                var conflict=args.deepCopy();conflict.addProperty("variant","rally");
                var rejected=bridge.request(new BridgeProtocol.Request("propose_scenario",conflict));bridge.tick();
                helper.assertTrue(rejected.join().getAsJsonObject().get("reason").getAsString().equals("conflicting_request"),"UUID reused with another selection");
            });
            helper.runAfterDelay(230,()->{
                try {
                    helper.assertTrue(runtime.findScenario(UUID.fromString(receipt.get("scenarioId").getAsString())).orElseThrow().state()==State.COMPLETED,"Radio did not complete");
                    helper.assertTrue(client.messages().stream().filter(s->s.contains("запрос на обследование") && s.contains(site.id().toString())).count()==1,"Selected radio absent or duplicated");
                    helper.succeed();
                } finally {bridge.close();client.close();level.setChunkForced(origin.getX()>>4,origin.getZ()>>4,false);}
            });
        });
    }
    @GameTest(template="empty",batch="radio",timeoutTicks=260)
    public static void damagedPhysicalRelayVetoesStaleReadyRecord(GameTestHelper helper) {
        var level=helper.getLevel();var origin=new BlockPos(3648,96,3200);ExpeditionGameTests.prepare(level,origin);
        level.setChunkForced(origin.getX()>>4,origin.getZ()>>4,true);
        var runtime=DeadEarth.runtime(level.getServer());var site=runtime.placeRelay(UUID.randomUUID(),origin);
        var client=FoundationGameTests.attach(helper);client.player().teleportTo(origin.getX()+4.5,origin.getY()+1,origin.getZ()+3.5);
        helper.runAfterDelay(210,()->{
            try {
                var known=runtime.snapshot().discoveredSites().stream().filter(s->s.id().equals(site.id())).findFirst().orElseThrow();
                level.setBlock(origin.offset(4,5,4),Blocks.AIR.defaultBlockState(),2|16);
                var choice=new ScenarioChoice(ScenarioChoice.RADIO,"relay:"+site.id(),"check_in",200);
                var result=new RelayRadioExecutor(level.getServer(),known,choice).execute(UUID.randomUUID());
                helper.assertTrue(result==StepResult.UNCERTAIN,"Stale READY bypassed physical check");
                helper.assertTrue(client.messages().stream().noneMatch(s->s.contains("контрольный сигнал")),"Broken relay transmitted");
                helper.succeed();
            } finally {client.close();level.setChunkForced(origin.getX()>>4,origin.getZ()>>4,false);}
        });
    }
}
