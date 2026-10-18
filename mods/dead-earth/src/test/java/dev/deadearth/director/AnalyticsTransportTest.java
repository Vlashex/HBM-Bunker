package dev.deadearth.director;

import com.google.gson.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AnalyticsTransportTest {
    @Test void worstRetainedAnalyticsAndFullHistoryFitUnchangedBridgeFrameWithVisibleSlices() {
        var defaults=EventAnalytics.Settings.defaults();var settings=new EventAnalytics.Settings(128,128,64,20,96,6,4,600,1,20,0,1,2,3);
        var a=new EventAnalytics(settings);var point=new EventAnalytics.Point("minecraft:overworld",0,64,0);
        for(int n=0;n<128;n++) {
            var op=new UUID(0,n+1);a.open(op,"x".repeat(64),new UUID(1,n).toString(),0,1000,"WAITING","complete",point);
            for(int line=0;line<130;line++)a.interaction(op,line,new UUID(2,1),"inspect","distraction");a.finish(op,200,"inconclusive");
        }
        var queue=new JsonObject();var history=new JsonArray();
        for(int n=0;n<512;n++){var h=new JsonObject();h.addProperty("requestId",new UUID(3,n).toString());h.addProperty("operationId",new UUID(4,n).toString());h.addProperty("eventId","x".repeat(64));h.addProperty("zoneId",new UUID(5,n).toString());h.addProperty("tick",Long.MAX_VALUE);h.addProperty("outcome","inconclusive");h.addProperty("spent",32);history.add(h);}
        var active=new JsonArray();for(int n=0;n<19;n++){var op=new JsonObject();op.addProperty("id",new UUID(6,n).toString());op.addProperty("eventId","x".repeat(64));op.addProperty("zoneId",new UUID(7,n).toString());op.addProperty("state","APPLYING");op.addProperty("node","n".repeat(64));op.addProperty("budget",32);op.addProperty("spent",32);op.addProperty("expires",Long.MAX_VALUE);active.add(op);}
        queue.add("history",history);queue.add("operations",active);queue.addProperty("enabled",true);
        int budget=dev.deadearth.bridge.BridgeProtocol.MAX_OUTPUT-AnalyticsTransport.bytes(queue)-4096;
        var summary=AnalyticsTransport.summary(a.view("ready"),budget);queue.add("analytics",summary);
        assertTrue(AnalyticsTransport.bytes(queue)+4096<=dev.deadearth.bridge.BridgeProtocol.MAX_OUTPUT);
        assertTrue(AnalyticsTransport.bytes(summary)<=AnalyticsTransport.SUMMARY_BYTES);
        assertEquals(128,summary.getAsJsonObject("totals").get("operations").getAsInt());
        assertTrue(summary.get("omittedOperations").getAsInt()>=112);
        var row=summary.getAsJsonArray("operations").get(0).getAsJsonObject();assertTrue(row.get("wireTruncated").getAsBoolean());assertTrue(row.getAsJsonArray("timeline").size()<=16);
        assertEquals(3,row.get("droppedTimeline").getAsLong());assertTrue(row.get("omittedTimeline").getAsInt()>=112);
        var full=AnalyticsTransport.detail("ready",a.view("ready").operations().getLast());
        assertTrue(AnalyticsTransport.bytes(full)<=AnalyticsTransport.DETAIL_BYTES);
        var detail=full.getAsJsonObject("operation");assertFalse(detail.get("wireTruncated").getAsBoolean());
        assertEquals(3,detail.getAsJsonArray("timeline").get(0).getAsJsonObject().get("seq").getAsLong());
    }
}
