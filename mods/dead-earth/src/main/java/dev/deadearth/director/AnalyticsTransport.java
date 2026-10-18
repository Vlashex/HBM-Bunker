package dev.deadearth.director;

import com.google.gson.*;
import java.nio.charset.StandardCharsets;

/** Explicit wire slices; retained totals and ledger loss counters are never recomputed from a slice. */
public final class AnalyticsTransport {
    public static final int SUMMARY_BYTES=80*1024,DETAIL_BYTES=60*1024;
    private static final Gson JSON=new Gson();
    private AnalyticsTransport(){}
    public static int bytes(JsonElement value){return value.toString().getBytes(StandardCharsets.UTF_8).length;}
    public static JsonObject summary(EventAnalytics.View view,int budget) {
        var out=JSON.toJsonTree(view).getAsJsonObject();var all=out.getAsJsonArray("operations");var selected=new JsonArray();
        for(int n=Math.max(0,all.size()-16);n<all.size();n++)selected.add(slice(all.get(n).getAsJsonObject(),16));
        out.add("operations",selected);out.addProperty("omittedOperations",all.size()-selected.size());
        int cap=Math.min(SUMMARY_BYTES,budget);
        while(bytes(out)>cap&&!selected.isEmpty()) {
            boolean reduced=false;
            for(var item:selected){var row=item.getAsJsonObject();if(row.getAsJsonArray("timeline").size()>0){removeLine(row);reduced=true;break;}}
            if(!reduced){selected.remove(0);out.addProperty("omittedOperations",out.get("omittedOperations").getAsInt()+1);}
        }
        return out;
    }
    public static JsonObject detail(String status,EventAnalytics.Event operation) {
        var out=new JsonObject();out.addProperty("schema",1);out.addProperty("status",status);
        if(operation==null){out.add("operation",JsonNull.INSTANCE);return out;}
        var row=slice(JSON.toJsonTree(operation).getAsJsonObject(),EventAnalytics.HARD_TIMELINE);out.add("operation",row);
        while(bytes(out)>DETAIL_BYTES&&!row.getAsJsonArray("timeline").isEmpty())removeLine(row);
        return out;
    }
    private static JsonObject slice(JsonObject row,int maximum) {
        var old=row.getAsJsonArray("timeline");var kept=new JsonArray();for(int n=Math.max(0,old.size()-maximum);n<old.size();n++)kept.add(old.get(n));
        row.add("timeline",kept);row.addProperty("omittedTimeline",old.size()-kept.size());row.addProperty("wireTruncated",old.size()!=kept.size());return row;
    }
    private static void removeLine(JsonObject row){row.getAsJsonArray("timeline").remove(0);row.addProperty("omittedTimeline",row.get("omittedTimeline").getAsInt()+1);row.addProperty("wireTruncated",true);}
}
