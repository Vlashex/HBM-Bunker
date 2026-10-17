package dev.deadearth.director;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.util.*;
import static dev.deadearth.director.CinematicCatalog.*;

/** Pure finite-state queue. Every physical effect is checkpointed BEFORE the world adapter runs. */
public final class CinematicEngine {
    private static final Gson JSON=new Gson();
    public enum State { QUEUED, WAITING, APPLYING, COMPLETED, EXPIRED, CANCELLED, UNCERTAIN }
    public record Zone(String id,double value,long armed,long empty,long age,boolean complete,boolean overflow,
            int players,double playerScore,double losses) {}
    public record Context(long now,int online,String season,double pressure,List<Zone> zones) {}
    public record Option(String eventId,String zoneId,String title,String tactic,String faction,double weight,int budget) {}
    public record Goal(UUID id,UUID epoch,String catalogRevision,long created,long expires,List<Option> options) {}
    public record History(UUID requestId,UUID operationId,String eventId,String zoneId,long tick,String outcome,int spent) {}
    public record Operation(UUID id,UUID requestId,String frozenCatalog,String eventId,String zoneId,
            State state,String node,int cursor,long created,long expires,long entered,int budget,int spent,String outcome,Set<String> latched) {
        public Operation {latched=Set.copyOf(latched);if(latched.size()>CinematicCatalog.TRIGGERS.size()*(CinematicCatalog.ROLES.size()+1)||latched.stream().anyMatch(v->!CinematicCatalog.validTrigger(v)))throw bad("latched signals");}
    }
    public record Snapshot(List<Operation> operations,List<History> history,long nextDecision,long recoveryUntil,
            long heavyUntil,int pressuresSinceRest) {}
    public record Verdict(UUID requestId,UUID goalId,boolean accepted,String code,UUID operationId) {}
    public record ManualPreview(boolean accepted,String code,Option option) {}
    public interface World {
        boolean ready(Operation operation);
        Set<String> signals(Operation operation);
        int livingOwned();
        /** Count is the remaining finite wave allocation. Never replace casualties. */
        void emit(Operation operation,Action action,int count,CinematicCatalog frozen);
        void checkpoint();
    }
    private final LinkedHashMap<UUID,Operation> operations=new LinkedHashMap<>();
    private final ArrayList<History> history=new ArrayList<>();
    private final HashMap<UUID,CinematicCatalog> frozen=new HashMap<>();
    private long nextDecision,recoveryUntil,heavyUntil;
    private int pressuresSinceRest;
    private Goal goal;

    public List<Operation> operations(){return List.copyOf(operations.values());}
    public List<History> history(){return List.copyOf(history);}
    public Snapshot snapshot(){return new Snapshot(operations(),history(),nextDecision,recoveryUntil,heavyUntil,pressuresSinceRest);}
    public CinematicCatalog frozen(Operation op){return frozen.get(op.id);}
    public static CinematicEngine restore(Snapshot s) {
        if(s==null||s.operations==null||s.history==null||s.operations.size()>19||s.history.size()>512||s.nextDecision<0
                ||s.recoveryUntil<0||s.heavyUntil<0||s.pressuresSinceRest<0||s.pressuresSinceRest>10)throw bad("snapshot");
        var engine=new CinematicEngine();engine.nextDecision=s.nextDecision;engine.recoveryUntil=s.recoveryUntil;
        engine.heavyUntil=s.heavyUntil;engine.pressuresSinceRest=s.pressuresSinceRest;
        var requests=new HashSet<UUID>();
        for(var op:s.operations) {
            if(op==null||op.id==null||op.requestId==null||op.state==null||op.outcome==null||!op.outcome.isEmpty()
                    ||op.created<0||op.expires<=op.created||op.entered<op.created||op.entered>op.expires
                    ||op.budget<1||op.budget>32||op.spent<0||op.spent>op.budget||!uuid(op.zoneId)
                    ||!requests.add(op.requestId)||engine.operations.containsKey(op.id))throw bad("operation");
            var catalog=CinematicCatalog.parse(op.frozenCatalog);var event=catalog.events().get(op.eventId);
            if(event==null||catalog.events().size()!=1||!event.nodes().containsKey(op.node)
                    ||op.cursor<0||op.cursor>event.nodes().get(op.node).actions().size()
                    ||op.expires-op.created!=event.lifetimeTicks()
                    ||!Set.of(State.QUEUED,State.WAITING,State.APPLYING).contains(op.state))throw bad("frozen operation");
            engine.frozen.put(op.id,catalog);
            if(op.state==State.APPLYING)engine.history.add(new History(op.requestId,op.id,op.eventId,op.zoneId,op.entered,"uncertain",op.spent));
            else engine.operations.put(op.id,op);
        }
        for(var h:s.history) {
            if(h==null||h.requestId==null||h.operationId==null||h.eventId==null||!h.eventId.matches("[a-z][a-z0-9_]{0,63}")
                    ||!uuid(h.zoneId)||h.tick<0||h.spent<0||h.spent>32||!validOutcome(h.outcome)
                    ||!requests.add(h.requestId))throw bad("history");
            engine.history.add(h);
        }
        engine.trim(512);return engine;
    }
    private static boolean uuid(String s){try{return s!=null&&UUID.fromString(s).toString().equals(s);}catch(IllegalArgumentException e){return false;}}
    private static boolean validOutcome(String s){return OUTCOMES.contains(s)||Set.of("uncertain","expired","cancelled").contains(s);}
    private static IllegalArgumentException bad(String s){return new IllegalArgumentException("Director queue: "+s);}
    public Goal brief(UUID epoch,CinematicCatalog catalog,Context c,boolean enabled) {
        if(!enabled||c.online==0||c.now<recoveryUntil){goal=null;return null;}
        if(goal!=null&&goal.epoch.equals(epoch)&&goal.catalogRevision.equals(catalog.revision())&&c.now<goal.expires) return goal;
        goal=null;
        if(c.now<nextDecision||operations.size()>=catalog.rules().maxQueued()+catalog.rules().maxActive())return null;
        var options=options(catalog,c);nextDecision=Math.addExact(c.now,options.isEmpty()?catalog.rules().count("retryTicks"):catalog.rules().decisionTicks());
        if(options.isEmpty())return null;
        goal=new Goal(UUID.randomUUID(),epoch,catalog.revision(),c.now,Math.addExact(c.now,catalog.rules().goalTicks()),options);
        return goal;
    }
    public List<Option> options(CinematicCatalog catalog,Context c) {
        var out=new ArrayList<>(eligibleOptions(catalog,c));var r=catalog.rules();
        out.sort(Comparator.comparingDouble((Option o)->lottery(o,c.now/r.decisionTicks()))
                .thenComparing(Option::eventId).thenComparing(Option::zoneId));
        // Diversity across tactics and zones, bounded model context.
        var shortlist=new ArrayList<Option>();var counts=new HashMap<String,Integer>();
        for(var o:out)if(counts.getOrDefault(o.zoneId,0)<r.count("optionsPerZone")){shortlist.add(o);counts.merge(o.zoneId,1,Integer::sum);if(shortlist.size()==r.count("modelOptionLimit"))break;}
        return List.copyOf(shortlist);
    }
    private List<Option> eligibleOptions(CinematicCatalog catalog,Context c) {
        var out=new ArrayList<Option>();var r=catalog.rules();
        for(var z:c.zones) {
            // Incomplete scans stay UNKNOWN, including otherwise tempting high scores.
            if(!z.complete||z.overflow||z.age<0||z.age>r.maximumZoneAgeTicks()||z.players<1
                    ||!uuid(z.id)||!Double.isFinite(z.value)||!Double.isFinite(z.playerScore)||!Double.isFinite(z.losses))continue;
            if(operations.values().stream().anyMatch(op->op.zoneId.equals(z.id)))continue;
            if(history.stream().anyMatch(h->h.zoneId.equals(z.id)&&c.now-h.tick<r.zoneCooldownTicks()))continue;
            for(var e:catalog.events().values()) {
                if(!e.seasons().contains(c.season)||z.value<e.minScore()||z.players<e.minPlayers()||e.heavy()&&c.now<heavyUntil)continue;
                if(history.stream().anyMatch(h->h.eventId.equals(e.id())&&c.now-h.tick<e.cooldownTicks()))continue;
                var factors=Map.of("value",Math.log1p(Math.max(0,z.value))/r.coefficient("valueNormalization"),"defense",Math.min(z.armed,32)/r.coefficient("defenseNormalization"),
                        "empty_defense",Math.min(z.empty,32)/r.coefficient("defenseNormalization"),"pressure",Math.clamp(c.pressure-1,-1,2),
                        "players",Math.min(z.players,6)/6d,"losses",Math.clamp(z.losses,0,r.coefficient("lossMaximum")));
                double affinity=e.affinity().entrySet().stream().mapToDouble(a->a.getValue()*factors.get(a.getKey())).sum();
                double weight=e.weight()*Math.exp(Math.clamp(affinity,-r.coefficient("affinityLimit"),r.coefficient("affinityLimit")));
                // Losses lower pressure and the allotted finite head count; defense also subtracts.
                int budget=(int)Math.floor(r.baseBudget()+r.scoreWeight()*Math.log1p(Math.max(0,z.value))
                        +r.playerWeight()*Math.clamp(z.playerScore,0,r.coefficient("playerScoreMaximum"))+r.pressureWeight()*(c.pressure-1)
                        -r.lossWeight()*Math.max(0,z.losses)-Math.min(z.armed,16)*r.coefficient("defenseBudgetWeight"));
                out.add(new Option(e.id(),z.id,e.title(),e.tactic(),e.faction(),weight,Math.clamp(budget,1,r.maxWave())));
            }
        }
        return List.copyOf(out);
    }
    /** Read-only exact selection; the operator receives the same eligibility and finite allocation as the model. */
    public ManualPreview previewManual(CinematicCatalog catalog,Context context,boolean enabled,String eventId,String zoneId) {
        if(!enabled)return new ManualPreview(false,"disabled",null);
        if(context.online==0)return new ManualPreview(false,"no_players",null);
        if(context.now<recoveryUntil)return new ManualPreview(false,"recovery",null);
        if(queueFull(catalog))return new ManualPreview(false,"queue_full",null);
        var option=eligibleOptions(catalog,context).stream().filter(o->o.eventId.equals(eventId)&&o.zoneId.equals(zoneId)).findFirst();
        return option.map(o->new ManualPreview(true,"ready",o)).orElseGet(()->new ManualPreview(false,"ineligible",null));
    }
    public Verdict admitManual(UUID request,String eventId,String zoneId,CinematicCatalog catalog,Context context,boolean enabled) {
        var replay=replay(request,null,eventId,zoneId);if(replay!=null)return replay;
        var preview=previewManual(catalog,context,enabled,eventId,zoneId);
        if(!preview.accepted)return new Verdict(request,null,false,preview.code,null);
        return enqueue(request,null,eventId,zoneId,catalog,context,preview.option.budget);
    }
    private boolean queueFull(CinematicCatalog catalog) {
        return operations.size()>=catalog.rules().maxQueued()+catalog.rules().maxActive()
            ||operations.values().stream().filter(o->o.state==State.QUEUED).count()>=catalog.rules().maxQueued();
    }
    private Verdict replay(UUID request,UUID goalId,String eventId,String zoneId) {
        for(var op:operations.values())if(op.requestId.equals(request))return new Verdict(request,goalId,op.eventId.equals(eventId)&&op.zoneId.equals(zoneId),op.eventId.equals(eventId)&&op.zoneId.equals(zoneId)?"already_queued":"conflicting_request",op.id);
        for(var h:history)if(h.requestId.equals(request))return new Verdict(request,goalId,h.eventId.equals(eventId)&&h.zoneId.equals(zoneId),h.eventId.equals(eventId)&&h.zoneId.equals(zoneId)?"already_recorded":"conflicting_request",h.operationId);
        return null;
    }
    private static double lottery(Option o,long cycle) {
        long seed=UUID.nameUUIDFromBytes((o.eventId+"/"+o.zoneId+"/"+cycle).getBytes(java.nio.charset.StandardCharsets.UTF_8)).getMostSignificantBits();
        double unit=((seed>>>11)+.5)*0x1.0p-53;return -Math.log(unit)/o.weight;
    }
    public Verdict admit(UUID request,UUID goalId,String eventId,String zoneId,UUID epoch,CinematicCatalog catalog,Context context,boolean enabled) {
        var replay=replay(request,goalId,eventId,zoneId);if(replay!=null)return replay;
        if(!enabled||context.online==0||goal==null||!goal.id.equals(goalId)||!goal.epoch.equals(epoch)||context.now>=goal.expires
                ||!goal.catalogRevision.equals(catalog.revision()))return new Verdict(request,goalId,false,"stale_goal",null);
        var selected=goal.options.stream().filter(o->o.eventId.equals(eventId)&&o.zoneId.equals(zoneId)).findFirst();
        var current=options(catalog,context).stream().filter(o->o.eventId.equals(eventId)&&o.zoneId.equals(zoneId)).findFirst();
        if(selected.isEmpty()||current.isEmpty())return new Verdict(request,goalId,false,"ineligible",null);
        if(queueFull(catalog))return new Verdict(request,goalId,false,"queue_full",null);
        return enqueue(request,goalId,eventId,zoneId,catalog,context,current.get().budget);
    }
    private Verdict enqueue(UUID request,UUID goalId,String eventId,String zoneId,CinematicCatalog catalog,Context context,int budget) {
        var event=catalog.events().get(eventId);String source=freeze(catalog,eventId);var copy=CinematicCatalog.parse(source);UUID id=UUID.randomUUID();
        var op=new Operation(id,request,source,eventId,zoneId,State.QUEUED,event.start(),0,context.now,
                Math.addExact(context.now,event.lifetimeTicks()),context.now,budget,0,"",Set.of());
        operations.put(id,op);frozen.put(id,copy);goal=null;
        if(event.heavy())heavyUntil=Math.addExact(context.now,catalog.rules().heavyCooldownTicks());
        return new Verdict(request,goalId,true,"queued",id);
    }
    public static String freeze(CinematicCatalog catalog,String eventId) {
        var root=JSON.toJsonTree(catalog).getAsJsonObject();root.remove("revision");root.addProperty("schema",1);
        var event=catalog.events().get(eventId);var list=new com.google.gson.JsonArray();
        // Convert internal maps to the public list-based node/event schema.
        var e=JSON.toJsonTree(event).getAsJsonObject();var nodes=new com.google.gson.JsonArray();
        event.nodes().values().stream().sorted(Comparator.comparing(Node::id)).forEach(n->nodes.add(JSON.toJsonTree(n)));
        e.add("nodes",nodes);list.add(e);root.add("events",list);
        var sounds=new JsonObject();var traces=new JsonObject();var waves=new JsonObject();
        for(var n:event.nodes().values())for(var a:n.actions())switch(a.type()) {
            case "sound" -> sounds.add(a.key(),JSON.toJsonTree(catalog.sounds().get(a.key())));
            case "trace" -> traces.add(a.key(),JSON.toJsonTree(catalog.traces().get(a.key())));
            case "wave" -> waves.add(a.key(),JSON.toJsonTree(catalog.waves().get(a.key())));
        }
        // Empty libraries are legal in a frozen single-event document via a minimal unused default.
        if(sounds.isEmpty()){var k=catalog.sounds().keySet().stream().sorted().findFirst().orElseThrow();sounds.add(k,JSON.toJsonTree(catalog.sounds().get(k)));}
        if(traces.isEmpty()){var k=catalog.traces().keySet().stream().sorted().findFirst().orElseThrow();traces.add(k,JSON.toJsonTree(catalog.traces().get(k)));}
        if(waves.isEmpty()){var k=catalog.waves().keySet().stream().sorted().findFirst().orElseThrow();waves.add(k,JSON.toJsonTree(catalog.waves().get(k)));}
        root.add("sounds",sounds);root.add("traces",traces);root.add("waves",waves);return JSON.toJson(root);
    }
    public void tick(CinematicCatalog catalog,Context c,World world) {
        if(c.online==0)return;
        int effects=0;
        for(var original:List.copyOf(operations.values())) {
            var op=operations.get(original.id);var frozenCatalog=frozen.get(op.id);var event=frozenCatalog.events().get(op.eventId);
            if(op.state==State.APPLYING){finish(op,"uncertain",c.now,catalog.rules(),world);continue;}
            if(c.now>=op.expires){finish(op,"expired",c.now,catalog.rules(),world);continue;}
            if(!world.ready(op))continue; // Loaded-only, bounded pause shares the same lifetime.
            if(op.state==State.QUEUED) {
                long active=operations.values().stream().filter(x->x.state!=State.QUEUED).count();
                if(active>=catalog.rules().maxActive()||c.now<recoveryUntil)continue;
                op=copy(op,State.WAITING,op.node,0,c.now,op.spent);operations.put(op.id,op);world.checkpoint();
            }
            var observed=world.signals(op);var remembered=new HashSet<>(op.latched);
            for(var value:observed)if(CinematicCatalog.validTrigger(value)&&!Set.of("near","far","clear").contains(value.split(":")[0]))remembered.add(value);
            if(!remembered.equals(op.latched)){op=withSignals(op,remembered);operations.put(op.id,op);world.checkpoint();}
            var n=event.nodes().get(op.node);
            if(op.cursor<n.actions().size()) {
                if(effects>=catalog.rules().maxEffectsPerTick())continue;
                var a=n.actions().get(op.cursor);
                if(a.type().equals("finish")){finish(op,a.key(),c.now,catalog.rules(),world);continue;}
                int count=a.type().equals("wave")?Math.min(op.budget-op.spent,Math.min((int)Math.ceil(op.budget*a.portion()),Math.max(0,catalog.rules().maxEntities()-world.livingOwned()))):0;
                // Persist APPLYING first. A crash after this point becomes UNCERTAIN, never an automatic replay.
                var pending=copy(op,State.APPLYING,op.node,op.cursor,c.now,op.spent+count);operations.put(op.id,pending);world.checkpoint();
                try {
                    world.emit(pending,a,count,frozenCatalog);effects++;
                    operations.put(op.id,copy(op,State.WAITING,op.node,op.cursor+1,op.cursor+1==n.actions().size()?c.now:op.entered,op.spent+count));world.checkpoint();
                } catch(RuntimeException failed){finish(pending,"uncertain",c.now,catalog.rules(),world);}
                continue;
            }
            var signals=new HashSet<>(observed);signals.addAll(op.latched);String next=null;
            for(var branch:n.branches())if(signals.contains(branch.when())){next=branch.next();break;}
            if(next==null&&c.now-op.entered>=n.waitTicks())next=n.timeout();
            if(next!=null){operations.put(op.id,withSignals(copy(op,State.WAITING,next,0,c.now,op.spent),Set.of()));world.checkpoint();}
        }
    }
    public boolean cancel(UUID id,long now,Rules rules,World world) {
        var op=operations.get(id);if(op==null)return false;finish(op,"cancelled",now,rules,world);return true;
    }
    private void finish(Operation op,String outcome,long now,Rules rules,World world) {
        operations.remove(op.id);frozen.remove(op.id);history.add(new History(op.requestId,op.id,op.eventId,op.zoneId,now,outcome,op.spent));
        pressuresSinceRest++;
        if(pressuresSinceRest>=rules.count("restAfter")||outcome.equals("repelled")||outcome.equals("contained")) {
            recoveryUntil=Math.max(recoveryUntil,Math.addExact(now,rules.recoveryTicks()));pressuresSinceRest=0;
        }
        trim(rules.historyLimit());world.checkpoint();
    }
    private void trim(int size){while(history.size()>size)history.removeFirst();}
    private static Operation withSignals(Operation op,Set<String> signals) {
        return new Operation(op.id,op.requestId,op.frozenCatalog,op.eventId,op.zoneId,op.state,op.node,op.cursor,op.created,op.expires,op.entered,op.budget,op.spent,op.outcome,signals);
    }
    private static Operation copy(Operation op,State state,String node,int cursor,long entered,int spent) {
        return new Operation(op.id,op.requestId,op.frozenCatalog,op.eventId,op.zoneId,state,node,cursor,op.created,op.expires,entered,op.budget,spent,"",op.latched);
    }
}
