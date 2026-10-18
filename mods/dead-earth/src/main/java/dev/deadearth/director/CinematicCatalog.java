package dev.deadearth.director;

import com.google.gson.*;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** A finite, typed vocabulary. Content files are data, never commands or executable expressions. */
public record CinematicCatalog(String revision, Rules rules, Map<String,Sound> sounds,
        Map<String,Trace> traces, Map<String,Wave> waves, Map<String,Event> events) {
    public static final int MAX_BYTES=1048576;
    public static final Set<String> ROLES=Set.of("approach","flank","rear","distraction");
    public static final Set<String> TRIGGERS=Set.of("near","far","inspect","break","burn","water","mine","repair","clear");
    public static final Set<String> OUTCOMES=Set.of("repelled","contained","avoided","engaged","inconclusive");
    public record Rules(int decisionTicks,int goalTicks,int maxActive,int maxQueued,int historyLimit,
            int zoneCooldownTicks,int recoveryTicks,int heavyCooldownTicks,int maximumZoneAgeTicks,
            int maxWave,int maxEntities,int baseBudget,double scoreWeight,double playerWeight,
            double pressureWeight,double lossWeight,int signalRadius,int spawnMinDistance,int maxEffectsPerTick,Map<String,Double> coefficients) {
        public Rules {coefficients=Map.copyOf(coefficients);}
        public double coefficient(String key){return coefficients.get(key);}
        public int count(String key){return coefficients.get(key).intValue();}
    }
    public record Sound(String sound,double volume,double pitch) {}
    public record Trace(List<String> mask,int color) {}
    public record Wave(List<String> entities) {}
    public record Action(String type,String key,String role,double portion) {}
    public record Branch(String when,String next) {}
    public record Node(String id,List<Action> actions,int waitTicks,List<Branch> branches,String timeout) {}
    public record Event(String id,String title,String tactic,String faction,double weight,double minScore,
            int minPlayers,Set<String> seasons,int cooldownTicks,int lifetimeTicks,boolean heavy,
            Map<String,Double> affinity,String start,Map<String,Node> nodes) {}

    public static CinematicCatalog parse(String source) {
        var root=object(strict(source),"catalog");
        fields(root,"schema","rules","sounds","traces","waves","events");
        if(integer(root,"schema",1,1)!=1)throw bad("schema");
        var r=object(root.get("rules"),"rules");
        fields(r,"decisionTicks","goalTicks","maxActive","maxQueued","historyLimit","zoneCooldownTicks","recoveryTicks",
                "heavyCooldownTicks","maximumZoneAgeTicks","maxWave","maxEntities","baseBudget","scoreWeight","playerWeight",
                "pressureWeight","lossWeight","signalRadius","spawnMinDistance","maxEffectsPerTick","coefficients");
        var rules=new Rules(integer(r,"decisionTicks",200,144000),integer(r,"goalTicks",200,72000),integer(r,"maxActive",1,3),
            integer(r,"maxQueued",1,16),integer(r,"historyLimit",16,512),integer(r,"zoneCooldownTicks",200,1728000),
            integer(r,"recoveryTicks",200,1728000),integer(r,"heavyCooldownTicks",200,1728000),
            integer(r,"maximumZoneAgeTicks",20,72000),integer(r,"maxWave",1,32),integer(r,"maxEntities",1,64),
            integer(r,"baseBudget",1,16),number(r,"scoreWeight",0,10),number(r,"playerWeight",0,10),
            number(r,"pressureWeight",0,10),number(r,"lossWeight",0,10),integer(r,"signalRadius",8,96),
            integer(r,"spawnMinDistance",12,64),integer(r,"maxEffectsPerTick",1,8),coefficients(r));
        var sounds=new TreeMap<String,Sound>();
        for(var e:entries(root,"sounds",1,160)) {
            var o=object(e.getValue(),"sound");fields(o,"sound","volume","pitch");
            sounds.put(id(e.getKey()),new Sound(resource(text(o,"sound",128)),number(o,"volume",.05,2),number(o,"pitch",.5,2)));
        }
        var traces=new TreeMap<String,Trace>();
        for(var e:entries(root,"traces",1,96)) {
            var o=object(e.getValue(),"trace");fields(o,"mask","color");var rows=new ArrayList<String>();
            for(var row:array(o,"mask",4,16)) {String s=string(row,16);if(!s.matches("[.123]{4,16}"))throw bad("trace pixels");rows.add(s);}
            int width=rows.getFirst().length();if(rows.stream().anyMatch(s->s.length()!=width)||rows.stream().allMatch(s->s.chars().allMatch(c->c=='.')))throw bad("trace shape");
            traces.put(id(e.getKey()),new Trace(List.copyOf(rows),integer(o,"color",0,0xffffff)));
        }
        var waves=new TreeMap<String,Wave>();
        for(var e:entries(root,"waves",1,32)) {
            var o=object(e.getValue(),"wave");fields(o,"entities");var entities=new ArrayList<String>();
            for(var v:array(o,"entities",1,8))entities.add(resource(string(v,128)));
            waves.put(id(e.getKey()),new Wave(List.copyOf(entities)));
        }
        var events=new TreeMap<String,Event>();
        for(var v:array(root,"events",1,64)) {
            var o=object(v,"event");
            fields(o,"id","title","tactic","faction","weight","minScore","minPlayers","seasons","cooldownTicks","lifetimeTicks","heavy","affinity","start","nodes");
            String name=id(text(o,"id",64));var seasons=new TreeSet<String>();
            for(var s:array(o,"seasons",1,3)) {String phase=string(s,32);if(!Set.of("CALM","ASH_WINTER","HEAT").contains(phase))throw bad("season");seasons.add(phase);}
            var affinity=new TreeMap<String,Double>();
            for(var a:entries(o,"affinity",0,6)) {
                if(!Set.of("value","defense","empty_defense","pressure","players","losses").contains(a.getKey()))throw bad("affinity");
                affinity.put(a.getKey(),numeric(a.getValue(),-4,4));
            }
            var nodes=new TreeMap<String,Node>();int waveActions=0;
            for(var n:array(o,"nodes",1,24)) {
                var node=object(n,"node");fields(node,"id","actions","waitTicks","branches","timeout");
                String nodeId=id(text(node,"id",64));var actions=new ArrayList<Action>();
                for(var a:array(node,"actions",0,8)) {
                    var action=object(a,"action");fields(action,"type","key","role","portion");
                    String type=text(action,"type",32), key=text(action,"key",128), role=text(action,"role",32);
                    if(!ROLES.contains(role))throw bad("role");double portion=number(action,"portion",0,1);
                    boolean valid=switch(type) {
                        case "sound" -> sounds.containsKey(key);
                        case "trace" -> traces.containsKey(key);
                        case "wave" -> waves.containsKey(key)&&portion>0;
                        case "particles" -> Set.of("spores","ash","frost","dust").contains(key);
                        case "move" -> ROLES.contains(key);
                        case "finish" -> OUTCOMES.contains(key);
                        default -> false;
                    };
                    if(!valid)throw bad("action reference: "+type+"/"+key);
                    if(type.equals("wave"))waveActions++;
                    actions.add(new Action(type,key,role,portion));
                }
                if(waveActions>8)throw bad("wave actions");
                var branches=new ArrayList<Branch>();var seen=new HashSet<String>();
                for(var b:array(node,"branches",0,9)) {
                    var branch=object(b,"branch");fields(branch,"when","next");String when=text(branch,"when",32);
                    if(!validTrigger(when)||!seen.add(when))throw bad("branch");
                    branches.add(new Branch(when,id(text(branch,"next",64))));
                }
                String timeout=text(node,"timeout",64);if(!timeout.isEmpty())id(timeout);
                int wait=integer(node,"waitTicks",0,72000);
                boolean terminal=actions.stream().anyMatch(a->a.type.equals("finish"));
                if(terminal) {
                    if(actions.size()!=1||!branches.isEmpty()||!timeout.isEmpty()||wait!=0)throw bad("terminal");
                } else if(timeout.isEmpty()||wait<1)throw bad("missing timeout");
                if(nodes.put(nodeId,new Node(nodeId,List.copyOf(actions),wait,List.copyOf(branches),timeout))!=null)throw bad("duplicate node");
            }
            String start=id(text(o,"start",64));validateGraph(nodes,start);
            var event=new Event(name,text(o,"title",120),id(text(o,"tactic",64)),text(o,"faction",32),number(o,"weight",.01,100),
                number(o,"minScore",-1e12,1e12),integer(o,"minPlayers",1,6),Set.copyOf(seasons),integer(o,"cooldownTicks",200,1728000),
                integer(o,"lifetimeTicks",200,1728000),bool(o,"heavy"),Map.copyOf(affinity),start,Map.copyOf(nodes));
            if(!Set.of("parasites","forest","mixed").contains(event.faction))throw bad("faction");
            if(events.put(name,event)!=null)throw bad("duplicate event");
        }
        try {
            String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(new Gson().toJson(root).getBytes(StandardCharsets.UTF_8)));
            return new CinematicCatalog(hash,rules,Map.copyOf(sounds),Map.copyOf(traces),Map.copyOf(waves),Map.copyOf(events));
        } catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
    private static Map<String,Double> coefficients(JsonObject rules) {
        var raw=object(rules.get("coefficients"),"coefficients").deepCopy();
        if(!raw.has("maximumApproachHeightDifference"))raw.addProperty("maximumApproachHeightDifference",12);
        fields(raw,"valueNormalization","defenseNormalization","defenseBudgetWeight","playerLevelWeight","playerArmorWeight","deathMemoryTicks","playerRadius","nearRadius","farRadius","restAfter","minimumTraceDistance","maximumLoadedTraces","entityLifetimeTailTicks","approachAttempts","modelOptionLimit","optionsPerZone","affinityLimit","playerScoreMaximum","lossMaximum","retryTicks","maximumApproachHeightDifference");
        var values=new TreeMap<String,Double>();
        values.put("valueNormalization",(double)number(raw,"valueNormalization",0.1,1000));
        values.put("defenseNormalization",(double)number(raw,"defenseNormalization",0.1,1000));
        values.put("defenseBudgetWeight",(double)number(raw,"defenseBudgetWeight",0,10));
        values.put("playerLevelWeight",(double)number(raw,"playerLevelWeight",0,10));
        values.put("playerArmorWeight",(double)number(raw,"playerArmorWeight",0,10));
        values.put("deathMemoryTicks",(double)integer(raw,"deathMemoryTicks",200,72000));
        values.put("playerRadius",(double)integer(raw,"playerRadius",16,256));
        values.put("nearRadius",(double)integer(raw,"nearRadius",1,16));
        values.put("farRadius",(double)integer(raw,"farRadius",16,256));
        values.put("restAfter",(double)integer(raw,"restAfter",1,10));
        values.put("minimumTraceDistance",(double)integer(raw,"minimumTraceDistance",2,32));
        values.put("maximumLoadedTraces",(double)integer(raw,"maximumLoadedTraces",1,128));
        values.put("entityLifetimeTailTicks",(double)integer(raw,"entityLifetimeTailTicks",0,72000));
        values.put("approachAttempts",(double)integer(raw,"approachAttempts",1,32));
        values.put("maximumApproachHeightDifference",(double)integer(raw,"maximumApproachHeightDifference",1,64));
        values.put("modelOptionLimit",(double)integer(raw,"modelOptionLimit",1,8));
        values.put("optionsPerZone",(double)integer(raw,"optionsPerZone",1,8));
        values.put("affinityLimit",(double)number(raw,"affinityLimit",0.1,4));
        values.put("playerScoreMaximum",(double)number(raw,"playerScoreMaximum",1,100));
        values.put("lossMaximum",(double)number(raw,"lossMaximum",1,100));
        values.put("retryTicks",(double)integer(raw,"retryTicks",20,72000));
        return Map.copyOf(values);
    }
    public static boolean validTrigger(String value) {
        var parts=value.split(":",-1);return parts.length<=2&&TRIGGERS.contains(parts[0])&&(parts.length==1||ROLES.contains(parts[1]));
    }
    private static void validateGraph(Map<String,Node> nodes,String start) {
        var complete=new HashSet<String>();visit(nodes,start,new HashSet<>(),complete);
        if(complete.size()!=nodes.size())throw bad("unreachable node");
    }
    private static void visit(Map<String,Node> nodes,String id,Set<String> path,Set<String> complete) {
        if(complete.contains(id))return;
        if(!nodes.containsKey(id)||!path.add(id))throw bad("missing node or cycle");
        var n=nodes.get(id);for(var b:n.branches)visit(nodes,b.next,path,complete);
        if(!n.timeout.isEmpty())visit(nodes,n.timeout,path,complete);
        path.remove(id);complete.add(id);
    }
    public static JsonElement strict(String source) {
        return strict(source,MAX_BYTES);
    }
    /** The catalogue keeps its own limit; bounded SavedData may use a larger envelope. */
    static JsonElement strict(String source,int maximumBytes) {
        if(maximumBytes<1||maximumBytes>8*1024*1024||source==null||source.getBytes(StandardCharsets.UTF_8).length>maximumBytes)throw bad("file size");
        try(var reader=new JsonReader(new StringReader(source))) {
            reader.setLenient(false);var value=read(reader,0);
            if(reader.peek()!=JsonToken.END_DOCUMENT)throw bad("trailing data");return value;
        } catch(IOException|IllegalStateException error){throw bad("invalid JSON");}
    }
    private static JsonElement read(JsonReader in,int depth)throws IOException {
        if(depth>20)throw bad("JSON depth");
        return switch(in.peek()) {
            case BEGIN_OBJECT -> {var o=new JsonObject();in.beginObject();while(in.hasNext()){String k=in.nextName();if(o.has(k))throw bad("duplicate key");o.add(k,read(in,depth+1));}in.endObject();yield o;}
            case BEGIN_ARRAY -> {var a=new JsonArray();in.beginArray();while(in.hasNext()){if(a.size()>=2048)throw bad("array size");a.add(read(in,depth+1));}in.endArray();yield a;}
            case STRING -> new JsonPrimitive(in.nextString());
            case NUMBER -> {String n=in.nextString();if(n.length()>64)throw bad("number size");try{yield new JsonPrimitive(new java.math.BigDecimal(n));}catch(NumberFormatException error){throw bad("number");}}
            case BOOLEAN -> new JsonPrimitive(in.nextBoolean());
            default -> throw bad("unexpected token");
        };
    }
    private static JsonObject object(JsonElement v,String where){if(v==null||!v.isJsonObject())throw bad(where);return v.getAsJsonObject();}
    private static void fields(JsonObject o,String... keys){if(!o.keySet().equals(Set.of(keys)))throw bad("unknown or missing fields");}
    private static Set<Map.Entry<String,JsonElement>> entries(JsonObject o,String key,int min,int max){var map=object(o.get(key),key);if(map.size()<min||map.size()>max)throw bad(key);return map.entrySet();}
    private static JsonArray array(JsonObject o,String key,int min,int max){var v=o.get(key);if(v==null||!v.isJsonArray()||v.getAsJsonArray().size()<min||v.getAsJsonArray().size()>max)throw bad(key);return v.getAsJsonArray();}
    private static String string(JsonElement v,int max){if(v==null||!v.isJsonPrimitive()||!v.getAsJsonPrimitive().isString()||v.getAsString().length()>max)throw bad("string");String s=v.getAsString();if(s.codePoints().anyMatch(c->Character.isISOControl(c)||c==0x00a7||c>=0x202a&&c<=0x202e||c>=0x2066&&c<=0x2069))throw bad("text controls");return s;}
    private static String text(JsonObject o,String k,int max){return string(o.get(k),max);}
    private static String id(String s){if(!s.matches("[a-z][a-z0-9_]{0,63}"))throw bad("id");return s;}
    private static String resource(String s){if(!s.matches("[a-z0-9_.-]+:[a-z0-9_./-]+"))throw bad("resource");return s;}
    private static double numeric(JsonElement v,double min,double max){if(v==null||!v.isJsonPrimitive()||!v.getAsJsonPrimitive().isNumber())throw bad("number");double n=v.getAsDouble();if(!Double.isFinite(n)||n<min||n>max)throw bad("number range");return n;}
    private static double number(JsonObject o,String k,double min,double max){return numeric(o.get(k),min,max);}
    private static int integer(JsonObject o,String k,int min,int max){double n=number(o,k,min,max);if(n!=Math.rint(n))throw bad("integer");return (int)n;}
    private static boolean bool(JsonObject o,String k){var v=o.get(k);if(v==null||!v.isJsonPrimitive()||!v.getAsJsonPrimitive().isBoolean())throw bad(k);return v.getAsBoolean();}
    private static IllegalArgumentException bad(String reason){return new IllegalArgumentException("Director catalog: "+reason);}
}
