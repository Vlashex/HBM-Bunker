package dev.deadearth.evolution;

import com.google.gson.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Immutable server-owned unlock table. No commands or executable expressions. */
public final class EvolutionCatalog {
    public record Phase(int id,String name,long threshold,int waveCost,List<String> entities,List<String> abilities,Map<String,List<String>> waves) {}
    public static final Set<String> ABILITIES=Set.of("infection","death_parasites","timed_evolution","hooks","putrid","teleport","explosions");
    private final String source,revision;
    private final boolean enabled;
    private final double migrationRate;
    private final List<Phase> phases;
    private final Map<String,Integer> entityPhases;
    private EvolutionCatalog(String source,boolean enabled,double migrationRate,List<Phase> phases,Map<String,Integer> entities) {
        this.source=source;this.enabled=enabled;this.migrationRate=migrationRate;this.phases=List.copyOf(phases);entityPhases=Map.copyOf(entities);
        try{revision=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(source.getBytes(StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException ex){throw new IllegalStateException(ex);}
    }
    public static EvolutionCatalog defaults() {
        try(var in=EvolutionCatalog.class.getResourceAsStream("/data/dead_earth/evolution.json")) {
            if(in==null)throw new IllegalStateException("Missing evolution defaults");return parse(new String(in.readNBytes(131073),StandardCharsets.UTF_8));
        }catch(java.io.IOException ex){throw new IllegalStateException(ex);}
    }
    public static EvolutionCatalog load(Path path) {
        if(!Files.exists(path))return defaults();
        try{if(Files.isSymbolicLink(path)||Files.size(path)>131072)throw new IllegalArgumentException("Invalid evolution configuration");return parse(Files.readString(path));}
        catch(java.io.IOException ex){throw new IllegalArgumentException("Unreadable evolution configuration",ex);}
    }
    private static void keys(JsonObject obj,String... names){if(!obj.keySet().equals(Set.of(names)))throw new IllegalArgumentException("Unknown/missing evolution fields");}
    private static double numeric(JsonElement n){if(n==null||!n.isJsonPrimitive()||!n.getAsJsonPrimitive().isNumber())throw new IllegalArgumentException("Invalid evolution number");double d=n.getAsDouble();if(!Double.isFinite(d))throw new IllegalArgumentException("Invalid evolution number");return d;}
    private static long integer(JsonElement n,long min,long max){numeric(n);long v=n.getAsBigDecimal().longValueExact();if(v<min||v>max)throw new IllegalArgumentException("Invalid evolution integer");return v;}
    private static List<String> strings(JsonArray a,int maximum){if(a.size()>maximum)throw new IllegalArgumentException("Evolution list too large");var out=new ArrayList<String>();for(var v:a){if(!v.isJsonPrimitive()||!v.getAsJsonPrimitive().isString())throw new IllegalArgumentException("Invalid evolution string");out.add(v.getAsString());}if(new HashSet<>(out).size()!=out.size())throw new IllegalArgumentException("Duplicate unlock");return List.copyOf(out);}
    public static EvolutionCatalog parse(String source) {
        try {
            if(source.getBytes(StandardCharsets.UTF_8).length>131072)throw new IllegalArgumentException("Evolution config too large");
            var root=dev.deadearth.director.CinematicCatalog.strict(source).getAsJsonObject();keys(root,"schema","enabled","migrationRate","phases");
            if(integer(root.get("schema"),1,1)!=1||!root.get("enabled").getAsJsonPrimitive().isBoolean())throw new IllegalArgumentException("Unsupported evolution config");
            double migration=numeric(root.get("migrationRate"));if(migration<0||migration>64)throw new IllegalArgumentException("Invalid migration rate");
            var a=root.getAsJsonArray("phases");if(a.size()!=6)throw new IllegalArgumentException("Expected six phases");
            var phases=new ArrayList<Phase>();var entities=new LinkedHashMap<String,Integer>();var abilities=new HashSet<String>();long previous=-1;
            for(int i=0;i<6;i++) {
                var p=a.get(i).getAsJsonObject();keys(p,"id","name","threshold","waveCost","entities","abilities","waves");
                int id=(int)integer(p.get("id"),0,5);long threshold=integer(p.get("threshold"),0,1_000_000_000_000L);
                if(!p.get("name").isJsonPrimitive()||!p.get("name").getAsJsonPrimitive().isString())throw new IllegalArgumentException("Invalid phase name");
                String name=p.get("name").getAsString();if(id!=i||threshold<=previous||(i==0&&threshold!=0)||name.isBlank()||name.length()>80)throw new IllegalArgumentException("Invalid phase ordering");previous=threshold;
                var ids=strings(p.getAsJsonArray("entities"),128);for(String entity:ids){if(!entity.matches("mutationcraft:[a-z0-9_]+")||entities.putIfAbsent(entity,id)!=null)throw new IllegalArgumentException("Invalid/duplicate mutant ID");}
                var skills=strings(p.getAsJsonArray("abilities"),16);for(var ability:skills)if(!ABILITIES.contains(ability)||!abilities.add(ability))throw new IllegalArgumentException("Unknown/duplicate ability");
                var waves=new LinkedHashMap<String,List<String>>();for(var e:p.getAsJsonObject("waves").entrySet()) {
                    if(!Set.of("scouts","flankers").contains(e.getKey()))throw new IllegalArgumentException("Unknown wave role");
                    var pool=strings(e.getValue().getAsJsonArray(),32);if(pool.stream().anyMatch(x->!entities.containsKey(x)))throw new IllegalArgumentException("Wave uses locked/unknown mutant");waves.put(e.getKey(),pool);
                }
                phases.add(new Phase(id,name,threshold,(int)integer(p.get("waveCost"),1,64),ids,skills,Map.copyOf(waves)));
            }
            if(!abilities.equals(ABILITIES))throw new IllegalArgumentException("Missing evolution abilities");
            return new EvolutionCatalog(source,root.get("enabled").getAsBoolean(),migration,phases,entities);
        }catch(RuntimeException ex){throw new IllegalArgumentException("Invalid evolution configuration",ex);}
    }
    public boolean enabled(){return enabled;} public String source(){return source;} public String revision(){return revision;} public double migrationRate(){return migrationRate;}
    public List<Phase> phases(){return phases;}
    public int phase(double points){if(!Double.isFinite(points)||points<0)throw new IllegalArgumentException("Invalid evolution points");int phase=0;for(var p:phases)if(points>=p.threshold())phase=p.id();return phase;}
    public boolean allowsEntity(int phase,String id){return !enabled||entityPhases.containsKey(id)&&entityPhases.get(id)<=phase;}
    public List<String> entities(int phase){return phases.stream().filter(p->p.id()<=phase).flatMap(p->p.entities().stream()).toList();}
    public List<String> abilities(int phase){return phases.stream().filter(p->p.id()<=phase).flatMap(p->p.abilities().stream()).toList();}
    public List<String> wave(int phase,String key){for(int p=phase;p>=0;p--)if(phases.get(p).waves().containsKey(key))return phases.get(p).waves().get(key);return List.of();}
    public int cost(String id){return phases.get(entityPhases.getOrDefault(id,5)).waveCost();}
}
