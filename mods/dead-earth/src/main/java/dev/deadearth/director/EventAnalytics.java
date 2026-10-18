package dev.deadearth.director;

import java.util.*;

/** Pure bounded measurements. Spatial attribution is association, never proof of causation. */
public final class EventAnalytics {
    public static final int HARD_OPERATIONS=128,HARD_TIMELINE=128,HARD_PARTICIPANTS=64,HARD_ANCHORS=16;
    public record Settings(int maxOperations,int maxTimeline,int maxParticipants,int sampleTicks,double spatialRadius,
            double nearRadius,double approachDelta,int reactionWindowTicks,double hpWeight,double deathWeight,
            double absorbedWeight,double approachWeight,double interactionWeight,double trapWeight) {
        public Settings {
            require(maxOperations>=1&&maxOperations<=HARD_OPERATIONS&&maxTimeline>=1&&maxTimeline<=HARD_TIMELINE
                &&maxParticipants>=1&&maxParticipants<=HARD_PARTICIPANTS&&sampleTicks>=1&&sampleTicks<=1200
                &&reactionWindowTicks>=sampleTicks&&reactionWindowTicks<=72000,"settings limits");
            for(double n:new double[]{spatialRadius,nearRadius,approachDelta,hpWeight,deathWeight,absorbedWeight,approachWeight,interactionWeight,trapWeight})require(Double.isFinite(n)&&n>=0&&n<=10000,"settings number");
            require(nearRadius>0&&approachDelta>0&&spatialRadius>=nearRadius,"settings radii");
        }
        public static Settings defaults(){return new Settings(32,48,32,20,96,6,4,600,1,20,0,1,2,3);}
    }
    /** Backend-only position. Never serialized through the public View. */
    public record Point(String dimension,double x,double y,double z) {
        public Point {require(dimension!=null&&dimension.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")&&Double.isFinite(x)&&Double.isFinite(y)&&Double.isFinite(z)&&Math.abs(x)<=30000000&&Math.abs(z)<=30000000&&Math.abs(y)<=100000,"point");}
        double distance(Point p){return dimension.equals(p.dimension)?Math.sqrt(Math.pow(x-p.x,2)+Math.pow(y-p.y,2)+Math.pow(z-p.z,2)):Double.POSITIVE_INFINITY;}
    }
    public record PlayerPoint(UUID player,Point point) {}
    public record Timeline(long tick,String kind,String role,String detail,int count,double hp,String attribution,long seq) {
        public Timeline(long tick,String kind,String role,String detail,int count,double hp,String attribution){this(tick,kind,role,detail,count,hp,attribution,0);}
    }
    public record Scoring(double hpWeight,double deathWeight,double absorbedWeight) {
        public Scoring {for(double n:new double[]{hpWeight,deathWeight,absorbedWeight})require(Double.isFinite(n)&&n>=0&&n<=10000,"scoring");}
    }
    public record Damage(double hp,double absorbedHp,long deaths,double ownedAttributedHp,double spatialAttributedHp,double points,Scoring pointsWeights) {}
    public record Observations(long signalEmissions,long tracePlacements,long traceInteractions,long nearEntries,long approaches,long trapEntries,long retreats,long samplePairs) {}
    public record Proxy(String distraction,String deception,double score,String reason) {}
    public record Event(UUID operationId,String eventId,String zoneId,long createdTick,long expiresTick,Long endedTick,String state,
            String coverage,int participants,boolean participantsTruncated,Damage damage,Observations observations,Proxy proxy,
            List<Timeline> timeline,long droppedTimeline) {}
    public record Totals(int operations,double hp,double absorbedHp,long deaths,double points) {}
    public record View(int schema,String status,int sampleTicks,int maxOperations,Scoring scoring,List<Event> operations,Totals totals) {}
    public record RawDamage(double hp,double absorbedHp,long deaths,double ownedAttributedHp,double spatialAttributedHp) {}
    public record Participant(UUID id,long lastDeathTick) {}
    public record Anchor(String kind,String role,long tick,Point point) {}
    public record StoredEvent(UUID operationId,String eventId,String zoneId,long createdTick,long expiresTick,Long endedTick,
            String state,String coverage,Point center,Scoring scoring,List<Participant> participants,boolean participantsTruncated,RawDamage damage,
            Observations observations,List<Anchor> anchors,List<Timeline> timeline,long droppedTimeline) {}
    public record Stored(List<StoredEvent> operations) {}
    private record Distance(long tick,double distance,boolean approached) {}
    private static final Set<String> COVERAGE=Set.of("complete","partial","unknown");
    private final Settings settings;
    private final LinkedHashMap<UUID,Entry> entries=new LinkedHashMap<>();
    private static final class Entry {
        UUID id;String event,zone,state,coverage;long created,expires;Long ended;Point center;Scoring scoring;
        final LinkedHashMap<UUID,Long> players=new LinkedHashMap<>();boolean truncated;
        double hp,absorption,owned,spatial;long deaths,signals,traces,interactions,near,approaches,traps,retreats,dropped;
        final List<Timeline> timeline=new ArrayList<>();final List<Anchor> anchors=new ArrayList<>();
        final Map<UUID,Map<Anchor,Distance>> distances=new HashMap<>();
        long sampled=-1,samplePairs;
    }
    public EventAnalytics(Settings settings){this.settings=Objects.requireNonNull(settings);}
    public Settings settings(){return settings;}
    private static void require(boolean value,String name){if(!value)throw new IllegalArgumentException("Event analytics: "+name);}
    private static boolean identifier(String value){return value!=null&&value.matches("[a-z][a-z0-9_]{0,63}");}
    private static boolean uuid(String value){try{return UUID.fromString(value).toString().equals(value);}catch(RuntimeException invalid){return false;}}
    private static boolean window(Entry e,long now){return e!=null&&e.ended==null&&now>=e.created&&now<e.expires&&!e.state.equals("QUEUED");}
    public boolean contains(UUID id){return entries.containsKey(id);}
    public void open(UUID id,String event,String zone,long created,long expires,String state,String coverage,Point center) {
        open(id,event,zone,created,expires,state,coverage,center,true);
    }
    private void open(UUID id,String event,String zone,long created,long expires,String state,String coverage,Point center,boolean enforceLimit) {
        require(id!=null&&identifier(event)&&uuid(zone)&&created>=0&&expires>created&&state!=null&&state.length()<=32&&COVERAGE.contains(coverage),"operation");
        if(entries.containsKey(id)){state(id,state);return;}
        var e=new Entry();e.id=id;e.event=event;e.zone=zone;e.created=created;e.expires=expires;e.state=state;e.coverage=coverage;e.center=center;
        e.scoring=new Scoring(settings.hpWeight,settings.deathWeight,settings.absorbedWeight);
        if(center==null&&coverage.equals("complete"))e.coverage="partial";
        entries.put(id,e);if(enforceLimit)trim();
    }
    public void state(UUID id,String state){var e=entries.get(id);if(e!=null&&e.ended==null)e.state=state;}
    public void finish(UUID id,long tick,String outcome){var e=entries.get(id);if(e==null||e.ended!=null)return;require(tick>=e.created&&identifier(outcome),"finish");e.ended=tick;e.state=outcome;e.distances.clear();line(e,new Timeline(tick,"finished","","operation_outcome="+outcome,0,0,"none"));}
    private void trim(){while(entries.size()>settings.maxOperations){var victim=entries.values().stream().filter(e->e.ended!=null).findFirst().orElse(entries.values().iterator().next());entries.remove(victim.id);}}
    private void line(Entry e,Timeline value){long seq=e.dropped+e.timeline.size();if(e.timeline.size()>=settings.maxTimeline){e.timeline.removeFirst();e.dropped++;}e.timeline.add(new Timeline(value.tick,value.kind,value.role,value.detail,value.count,value.hp,value.attribution,seq));}
    private boolean participant(Entry e,UUID player){if(e.players.containsKey(player))return true;if(e.players.size()>=settings.maxParticipants){e.truncated=true;if(e.coverage.equals("complete"))e.coverage="partial";return false;}e.players.put(player,-1L);return true;}
    private Entry attribute(long tick,Point location,UUID owned) {
        if(owned!=null){var direct=entries.get(owned);return window(direct,tick)?direct:null;}
        return entries.values().stream().filter(e->window(e,tick)&&e.center!=null&&e.center.distance(location)<=settings.spatialRadius)
            .min(Comparator.comparingDouble((Entry e)->e.center.distance(location)).thenComparingLong(e->e.created).thenComparing(e->e.id)).orElse(null);
    }
    /** One invocation is one confirmed native damage sequence, routed to at most one operation. */
    public UUID damage(long tick,UUID player,Point location,UUID owned,double hp,double absorbed) {
        require(player!=null&&location!=null&&Double.isFinite(hp)&&Double.isFinite(absorbed)&&hp>=0&&absorbed>=0,"damage");
        if(hp==0&&absorbed==0)return null;var e=attribute(tick,location,owned);if(e==null)return null;
        participant(e,player);e.hp+=hp;e.absorption+=absorbed;if(owned!=null)e.owned+=hp;else e.spatial+=hp;
        line(e,new Timeline(tick,"damage","","actual_hp_loss; absorbed_hp="+absorbed,1,hp,owned!=null?"owned_attacker":"spatial_association"));return e.id;
    }
    public UUID death(long tick,UUID player,Point location,UUID owned) {
        var e=attribute(tick,location,owned);if(e==null)return null;
        if(!participant(e,player))return null; // A bounded identity ledger cannot safely deduplicate an omitted participant.
        if(e.players.get(player)==tick)return null;e.players.put(player,tick);e.deaths++;
        line(e,new Timeline(tick,"death","","confirmed_native_death",1,0,owned!=null?"owned_attacker":"spatial_association"));return e.id;
    }
    /** Called only after the world adapter confirmed emission or accepted navigation. */
    public void action(UUID id,long tick,String kind,String key,String role,int count,Point point,boolean trap) {
        var e=entries.get(id);if(!window(e,tick)||count<=0)return;
        require(identifier(kind)&&key!=null&&key.length()<=128&&role!=null&&role.length()<=32,"action");
        if(kind.equals("sound"))e.signals++;if(kind.equals("trace"))e.traces++;
        line(e,new Timeline(tick,"action_"+kind,role,key,count,0,"confirmed_emit"));
        if(point!=null&&(kind.equals("sound")||kind.equals("trace")||trap)) {
            if(e.anchors.size()>=HARD_ANCHORS){var oldest=e.anchors.removeFirst();e.distances.values().forEach(m->m.remove(oldest));if(e.coverage.equals("complete"))e.coverage="partial";}
            e.anchors.add(new Anchor(trap?"trap":kind,role,tick,point));
        }
    }
    public void interaction(UUID id,long tick,UUID player,String kind,String role) {
        var e=entries.get(id);if(!window(e,tick)||player==null)return;
        require(identifier(kind)&&role!=null&&role.length()<=32,"interaction");participant(e,player);e.interactions++;
        line(e,new Timeline(tick,"trace_"+kind,role,"actual_trace_interaction",1,0,"observed_action"));
    }
    /** Samples only supplied online living players; first proximity is a baseline, never a reaction. */
    public void sample(UUID id,long tick,List<PlayerPoint> players) {
        var e=entries.get(id);if(!window(e,tick)||tick<=e.sampled)return;e.sampled=tick;
        var present=new HashSet<UUID>();
        for(var player:players) {
            if(e.center==null||e.center.distance(player.point)>settings.spatialRadius)continue;
            if(!participant(e,player.player))continue;present.add(player.player);
            var distances=e.distances.computeIfAbsent(player.player,k->new HashMap<>());
            for(var anchor:e.anchors) {
                if(tick<anchor.tick||tick-anchor.tick>settings.reactionWindowTicks)continue;
                double distance=anchor.point.distance(player.point);var previous=distances.get(anchor);
                boolean approached=previous!=null&&previous.approached;
                if(previous!=null&&tick-previous.tick<=settings.sampleTicks*2L) {
                    e.samplePairs++;
                    if(!approached&&previous.distance-distance>=settings.approachDelta){e.approaches++;approached=true;line(e,new Timeline(tick,"approach",anchor.role,"distance_reduced_after_emitted_cue",1,0,"sampled_proxy"));}
                    if(previous.distance>settings.nearRadius&&distance<=settings.nearRadius){e.near++;if(anchor.kind.equals("trap"))e.traps++;line(e,new Timeline(tick,anchor.kind.equals("trap")?"trap_entry":"near_entry",anchor.role,"observed_radius_crossing",1,0,"sampled_proxy"));}
                    if(previous.distance<=settings.nearRadius&&distance>settings.nearRadius+settings.approachDelta){e.retreats++;line(e,new Timeline(tick,"retreat",anchor.role,"observed_radius_exit",1,0,"sampled_proxy"));}
                }
                distances.put(anchor,new Distance(tick,distance,approached));
            }
        }
        e.distances.keySet().retainAll(present);
    }
    private Observations observations(Entry e){return new Observations(e.signals,e.traces,e.interactions,e.near,e.approaches,e.traps,e.retreats,e.samplePairs);}
    public View view(String status) {
        var events=new ArrayList<Event>();double hp=0,absorbed=0,points=0;long deaths=0;
        for(var e:entries.values()) {
            double cost=e.hp*e.scoring.hpWeight+e.deaths*e.scoring.deathWeight+e.absorption*e.scoring.absorbedWeight;
            var damage=new Damage(e.hp,e.absorption,e.deaths,e.owned,e.spatial,cost,e.scoring);
            String distraction=e.approaches>0||e.interactions>0?"observed":e.samplePairs>0&&e.ended!=null&&e.coverage.equals("complete")?"not_observed":"insufficient_data";
            String deception=e.traps>0?"observed":e.samplePairs>0&&e.ended!=null&&e.anchors.stream().anyMatch(a->a.kind.equals("trap"))&&e.coverage.equals("complete")?"not_observed":"insufficient_data";
            var proxy=new Proxy(distraction,deception,e.approaches*settings.approachWeight+e.interactions*settings.interactionWeight+e.traps*settings.trapWeight,
                "Observable movement/interaction only; no proof of hearing, intent, distraction or deception. Spatial damage is association. Outcome is independent.");
            events.add(new Event(e.id,e.event,e.zone,e.created,e.expires,e.ended,e.state,e.coverage,e.players.size(),e.truncated,damage,observations(e),proxy,List.copyOf(e.timeline),e.dropped));
            hp+=e.hp;absorbed+=e.absorption;deaths+=e.deaths;points+=cost;
        }
        return new View(1,status,settings.sampleTicks,settings.maxOperations,new Scoring(settings.hpWeight,settings.deathWeight,settings.absorbedWeight),List.copyOf(events),new Totals(events.size(),hp,absorbed,deaths,points));
    }
    public Stored snapshot() {
        return new Stored(entries.values().stream().map(e->new StoredEvent(e.id,e.event,e.zone,e.created,e.expires,e.ended,e.state,e.coverage,e.center,e.scoring,
            e.players.entrySet().stream().map(p->new Participant(p.getKey(),p.getValue())).toList(),e.truncated,
            new RawDamage(e.hp,e.absorption,e.deaths,e.owned,e.spatial),observations(e),List.copyOf(e.anchors),List.copyOf(e.timeline),e.dropped)).toList());
    }
    /** Validates before use. Missing sampling baselines create an explicit restart coverage gap. */
    public static EventAnalytics restore(Settings settings,Stored stored) {
        require(stored!=null&&stored.operations!=null&&stored.operations.size()<=HARD_OPERATIONS,"stored operations");
        var result=new EventAnalytics(settings);var seen=new HashSet<UUID>();
        for(var row:stored.operations) {
            require(row!=null&&seen.add(row.operationId)&&row.participants!=null&&row.participants.size()<=HARD_PARTICIPANTS
                &&row.timeline!=null&&row.timeline.size()<=HARD_TIMELINE&&row.anchors!=null&&row.anchors.size()<=HARD_ANCHORS&&row.damage!=null&&row.observations!=null&&row.scoring!=null,"stored record bounds");
            // Restore lifecycle flags before trimming, so a finished row cannot evict an active row.
            result.open(row.operationId,row.eventId,row.zoneId,row.createdTick,row.expiresTick,row.state,row.coverage,row.center,false);
            var e=result.entries.get(row.operationId);require(row.endedTick==null||row.endedTick>=row.createdTick,"stored ending");e.ended=row.endedTick;
            e.scoring=row.scoring;
            if(e.ended==null&&e.coverage.equals("complete"))e.coverage="partial";
            var identities=new HashSet<UUID>();for(var p:row.participants){require(p!=null&&p.id!=null&&identities.add(p.id)&&p.lastDeathTick>=-1,"stored participant");if(e.players.size()<settings.maxParticipants)e.players.put(p.id,p.lastDeathTick);else e.truncated=true;}
            e.truncated|=row.participantsTruncated;
            var d=row.damage;for(double n:new double[]{d.hp,d.absorbedHp,d.ownedAttributedHp,d.spatialAttributedHp})require(Double.isFinite(n)&&n>=0,"stored damage");
            require(d.deaths>=0&&Math.abs(d.hp-d.ownedAttributedHp-d.spatialAttributedHp)<.001,"stored attribution");e.hp=d.hp;e.absorption=d.absorbedHp;e.owned=d.ownedAttributedHp;e.spatial=d.spatialAttributedHp;e.deaths=d.deaths;
            var o=row.observations;for(long n:new long[]{o.signalEmissions,o.tracePlacements,o.traceInteractions,o.nearEntries,o.approaches,o.trapEntries,o.retreats,o.samplePairs,row.droppedTimeline})require(n>=0,"stored counters");
            e.signals=o.signalEmissions;e.traces=o.tracePlacements;e.interactions=o.traceInteractions;e.near=o.nearEntries;e.approaches=o.approaches;e.traps=o.trapEntries;e.retreats=o.retreats;e.samplePairs=o.samplePairs;e.dropped=row.droppedTimeline;
            for(var anchor:row.anchors){require(anchor!=null&&Set.of("sound","trace","trap").contains(anchor.kind)&&anchor.point!=null&&anchor.tick>=e.created&&anchor.role!=null&&anchor.role.length()<=32,"stored anchor");e.anchors.add(anchor);}
            long expectedSeq=row.droppedTimeline;
            for(var line:row.timeline){require(line!=null&&line.seq==expectedSeq++&&line.tick>=e.created&&line.kind!=null&&line.kind.length()<=64&&line.role!=null&&line.role.length()<=32&&line.detail!=null&&line.detail.length()<=256&&line.count>=0&&Double.isFinite(line.hp)&&line.hp>=0&&line.attribution!=null&&line.attribution.length()<=64,"stored timeline");result.line(e,line);}
            if(e.truncated&&e.coverage.equals("complete"))e.coverage="partial";
        }
        result.trim();return result;
    }
}
