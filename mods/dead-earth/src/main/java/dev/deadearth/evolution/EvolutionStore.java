package dev.deadearth.evolution;

import java.util.*;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.*;
import net.minecraft.world.level.saveddata.SavedData;
import dev.deadearth.campaign.*;

/** Campaign-owned clock and durable human operator receipts. */
public final class EvolutionStore extends SavedData {
    public static final String NAME="dead_earth_evolution";
    public record Transition(long atTick,int from,int to,String source,UUID requestId) {}
    public record Receipt(UUID requestId,long expectedRevision,int delta,boolean accepted,String code,int from,int to,long revision) {}
    private UUID campaignId;
    private long lastTick,revision;
    private double points,legacyTime;
    private String migration;
    private CompoundTag unsupported;
    private final List<Transition> history=new ArrayList<>();
    private final LinkedHashMap<UUID,Receipt> receipts=new LinkedHashMap<>();
    private EvolutionStore(){}
    public static EvolutionStore create(UUID campaign,long now,EvolutionCatalog config,double legacyTime) {
        if(campaign==null||now<0||!Double.isFinite(legacyTime)||legacyTime<0)throw new IllegalArgumentException("Invalid evolution baseline");
        var s=new EvolutionStore();s.campaignId=campaign;s.lastTick=now;s.points=Math.min(1e18,now*config.migrationRate());s.legacyTime=legacyTime;
        s.migration=now==0?"new_campaign":"active_campaign_recalculation";s.setDirty();return s;
    }
    public static EvolutionStore createForCampaign(CampaignStore campaign,EvolutionCatalog config,double legacyTime) {
        if(campaign.evolutionRequired())throw new IllegalStateException("Required evolution save missing");
        return create(campaign.campaign().campaignId(),campaign.campaign().activeTicks(),config,legacyTime);
    }
    public static EvolutionStore load(UUID expected,long now,CompoundTag tag,HolderLookup.Provider registries) {
        var s=new EvolutionStore();
        try {
            if(NbtFields.integer(tag,"schemaVersion")!=1)throw new IllegalArgumentException("Unsupported evolution save");
            s.campaignId=UUID.fromString(NbtFields.text(tag,"campaignId"));if(!s.campaignId.equals(expected))throw new IllegalArgumentException("Foreign evolution campaign");
            s.lastTick=NbtFields.number(tag,"lastTick");s.revision=NbtFields.number(tag,"revision");
            s.points=number(tag,"points");s.legacyTime=number(tag,"legacyTime");s.migration=NbtFields.text(tag,"migration");
            if(s.lastTick<0||s.lastTick>now||s.revision<0||!Set.of("new_campaign","active_campaign_recalculation").contains(s.migration))throw new IllegalArgumentException("Invalid evolution checkpoint");
            if(!tag.contains("history",Tag.TAG_LIST)||!tag.contains("receipts",Tag.TAG_LIST))throw new IllegalArgumentException("Missing evolution journal");
            for(String key:List.of("history","receipts")) {
                var list=(ListTag)tag.get(key);
                if(!list.isEmpty()&&list.getElementType()!=Tag.TAG_COMPOUND)throw new IllegalArgumentException("Invalid evolution journal type");
            }
            var hs=tag.getList("history",Tag.TAG_COMPOUND);var rs=tag.getList("receipts",Tag.TAG_COMPOUND);
            if(hs.size()>128||rs.size()>1024)throw new IllegalArgumentException("Evolution journal exceeds limit");
            for(var item:hs){var h=(CompoundTag)item;long tick=NbtFields.number(h,"atTick");int from=NbtFields.integer(h,"from"),to=NbtFields.integer(h,"to");String source=NbtFields.text(h,"source");
                if(tick<0||tick>s.lastTick||from<0||from>5||to<0||to>5||!Set.of("automatic","operator").contains(source))throw new IllegalArgumentException("Invalid transition");
                s.history.add(new Transition(tick,from,to,source,h.contains("requestId")?UUID.fromString(NbtFields.text(h,"requestId")):null));}
            for(var item:rs){var n=(CompoundTag)item;var id=UUID.fromString(NbtFields.text(n,"requestId"));long er=NbtFields.number(n,"expectedRevision"),rev=NbtFields.number(n,"revision");int delta=NbtFields.integer(n,"delta"),from=NbtFields.integer(n,"from"),to=NbtFields.integer(n,"to");String code=NbtFields.text(n,"code");
                if(!n.contains("accepted",Tag.TAG_BYTE)||Math.abs(delta)!=1||er<0||rev<0||rev>s.revision||from<0||from>5||to<0||to>5||!Set.of("shifted","phase_limit").contains(code))throw new IllegalArgumentException("Invalid receipt");
                if(s.receipts.putIfAbsent(id,new Receipt(id,er,delta,n.getBoolean("accepted"),code,from,to,rev))!=null)throw new IllegalArgumentException("Duplicate receipt");}
        }catch(RuntimeException ex){s.unsupported=tag.copy();s.setDirty(false);}
        return s;
    }
    private static double number(CompoundTag t,String key){NbtFields.require(t,key,Tag.TAG_DOUBLE);double n=t.getDouble(key);if(!Double.isFinite(n)||n<0||n>1e18)throw new IllegalArgumentException("Invalid evolution number");return n;}
    public boolean enabled(){return unsupported==null;}private void require(){if(!enabled())throw new IllegalStateException("Evolution save unsupported");}
    public UUID campaignId(){require();return campaignId;}public double points(){require();return points;}public long revision(){require();return revision;}public double legacyTime(){require();return legacyTime;}public String migration(){require();return migration;}
    public List<Transition> history(){require();return List.copyOf(history.subList(Math.max(0,history.size()-32),history.size()));}
    private void transition(long now,int from,int to,String source,UUID request){if(from==to)return;history.add(new Transition(now,from,to,source,request));while(history.size()>128)history.removeFirst();}
    public boolean advance(long now,boolean online,double rate,EvolutionCatalog config) {
        require();if(now<lastTick||!Double.isFinite(rate)||rate<0||rate>64)throw new IllegalArgumentException("Invalid evolution advance");
        long delta=now-lastTick;int before=config.phase(points);lastTick=now;
        if(online&&config.enabled()&&delta>0)points=Math.min(1e18,points+delta*rate);
        int after=config.phase(points);if(after!=before){revision=Math.incrementExact(revision);transition(now,before,after,"automatic",null);}
        if(delta>0)setDirty();return after!=before;
    }
    public Receipt shift(UUID request,long expectedRevision,int delta,long now,EvolutionCatalog config) {
        require();if(request==null||Math.abs(delta)!=1||expectedRevision<0||now<lastTick)throw new IllegalArgumentException("Invalid phase shift");
        int from=config.phase(points);var previous=receipts.get(request);
        if(previous!=null)return previous.delta()==delta&&previous.expectedRevision()==expectedRevision?previous:new Receipt(request,expectedRevision,delta,false,"conflicting_request",from,from,revision);
        if(expectedRevision!=revision)return new Receipt(request,expectedRevision,delta,false,"stale_revision",from,from,revision);
        if(receipts.size()>=1024)return new Receipt(request,expectedRevision,delta,false,"journal_full",from,from,revision);
        int target=from+delta;boolean accepted=target>=0&&target<6;
        if(accepted){points=config.phases().get(target).threshold();revision=Math.incrementExact(revision);lastTick=now;transition(now,from,target,"operator",request);}
        var result=new Receipt(request,expectedRevision,delta,accepted,accepted?"shifted":"phase_limit",from,accepted?target:from,revision);
        receipts.put(request,result);setDirty();return result;
    }
    @Override public CompoundTag save(CompoundTag tag,HolderLookup.Provider registries) {
        if(!enabled())return unsupported.copy();tag.putInt("schemaVersion",1);tag.putString("campaignId",campaignId.toString());tag.putLong("lastTick",lastTick);tag.putLong("revision",revision);tag.putDouble("points",points);tag.putDouble("legacyTime",legacyTime);tag.putString("migration",migration);
        var hs=new ListTag();for(var h:history){var n=new CompoundTag();n.putLong("atTick",h.atTick());n.putInt("from",h.from());n.putInt("to",h.to());n.putString("source",h.source());if(h.requestId()!=null)n.putString("requestId",h.requestId().toString());hs.add(n);}tag.put("history",hs);
        var rs=new ListTag();for(var r:receipts.values()){var n=new CompoundTag();n.putString("requestId",r.requestId().toString());n.putLong("expectedRevision",r.expectedRevision());n.putInt("delta",r.delta());n.putBoolean("accepted",r.accepted());n.putString("code",r.code());n.putInt("from",r.from());n.putInt("to",r.to());n.putLong("revision",r.revision());rs.add(n);}tag.put("receipts",rs);return tag;
    }
}
