package dev.deadearth.gametest;

import com.google.gson.JsonObject;
import dev.deadearth.DeadEarth;
import dev.deadearth.bridge.*;
import dev.deadearth.config.DeadEarthConfig;
import java.util.UUID;
import net.minecraft.gametest.framework.*;
import net.neoforged.neoforge.gametest.*;

@GameTestHolder("dead_earth") @PrefixGameTestTemplate(false)
public final class DirectorGameTests {
    @GameTest(template="empty",batch="director")
    public static void serverGoalAndPreviewStayOnServerThreadWithoutWorldEffect(GameTestHelper h) {
        var server=h.getLevel().getServer();var runtime=DeadEarth.runtime(server);
        boolean previous=DeadEarthConfig.b(DeadEarthConfig.DIRECTOR_ENABLED);
        try(var player=FoundationGameTests.attach(h);var bridge=new BridgeController(server,runtime)) {
            DeadEarthConfig.DIRECTOR_ENABLED.set(true);
            bridge.start();player.messages();
            long before=runtime.campaign().campaign().activeTicks();
            var request=bridge.request(new BridgeProtocol.Request("get_director_brief",new JsonObject()));
            h.assertTrue(!request.isDone(),"Director read world outside server queue");
            bridge.tick();
            var brief=request.join().getAsJsonObject().getAsJsonObject("planning");
            h.assertTrue(brief.get("status").getAsString().equals("ready"),"No server goal");
            var args=new JsonObject();args.addProperty("request_id",UUID.randomUUID().toString());
            args.add("goal_id",brief.getAsJsonObject("goal").get("id"));
            args.addProperty("variant","radio_static");args.addProperty("text","Read-only draft.");
            var preview=bridge.request(new BridgeProtocol.Request("preview_director",args));
            bridge.tick();var verdict=preview.join().getAsJsonObject();
            h.assertTrue(verdict.get("accepted").getAsBoolean()&&verdict.get("previewOnly").getAsBoolean(),"Preview rejected");
            h.assertTrue(player.messages().isEmpty(),"Preview broadcast narrative");
            h.assertTrue(runtime.campaign().campaign().activeTicks()==before,"Preview advanced campaign");
            args.addProperty("goal_id",UUID.randomUUID().toString());
            var stale=bridge.request(new BridgeProtocol.Request("preview_director",args));bridge.tick();
            h.assertTrue(!stale.join().getAsJsonObject().get("accepted").getAsBoolean(),"Invented goal accepted");
        } finally {DeadEarthConfig.DIRECTOR_ENABLED.set(previous);}
        h.succeed();
    }
}
