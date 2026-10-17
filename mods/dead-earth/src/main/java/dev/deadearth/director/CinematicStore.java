package dev.deadearth.director;

import com.google.gson.Gson;
import java.util.*;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.saveddata.SavedData;

/** Separate save: an unsupported director does not disable seasons or replace its original bytes. */
public final class CinematicStore extends SavedData {
    public static final String NAME="dead_earth_director";
    private static final Gson JSON=new Gson();
    private UUID campaign;
    private CinematicEngine engine;
    private CompoundTag unsupported;
    private record OwnedWave(Set<UUID> remaining,long expires) {OwnedWave{remaining=Set.copyOf(remaining);}}
    private final Map<UUID,OwnedWave> ownedWaves=new LinkedHashMap<>();
    public static CinematicStore open(net.minecraft.server.level.ServerLevel level,UUID campaign) {
        try{return dev.deadearth.campaign.SavedDataAccess.open(level,NAME,new SavedData.Factory<>(
            ()->create(campaign),(tag,reg)->load(campaign,tag,reg)));}
        catch(RuntimeException invalid) {
            dev.deadearth.DeadEarth.LOGGER.error("Director save unreadable; original retained, core remains active",invalid);
            var disabled=new CinematicStore();disabled.unsupported=new CompoundTag();return disabled;
        }
    }
    /** Only set-valued signal arrays are order independent; all other fields remain exact and strict. */
    private static com.google.gson.JsonElement normalized(com.google.gson.JsonElement source) {
        var copy=source.deepCopy().getAsJsonObject();
        for(var value:copy.getAsJsonArray("operations")) {
            var row=value.getAsJsonObject();var values=new TreeSet<String>();
            for(var signal:row.getAsJsonArray("latched")) {
                if(!signal.isJsonPrimitive()||!signal.getAsJsonPrimitive().isString()||!CinematicCatalog.validTrigger(signal.getAsString())||!values.add(signal.getAsString()))
                    throw new IllegalArgumentException("Director retained signals");
            }
            var sorted=new com.google.gson.JsonArray();values.forEach(sorted::add);row.add("latched",sorted);
        }
        return copy;
    }
    public void observeOwned(UUID operation,UUID entity,long expires) {
        if(!enabled()||expires<=0)return;
        var old=ownedWaves.get(operation);var remaining=new HashSet<>(old==null?Set.<UUID>of():old.remaining());
        if(!remaining.add(entity))return;
        if(remaining.size()>32||old==null&&ownedWaves.size()>=128)throw new IllegalStateException("Director owned population limit");
        ownedWaves.put(operation,new OwnedWave(remaining,old==null?expires:Math.max(old.expires(),expires)));setDirty();
    }
    public void confirmedGone(UUID operation,UUID entity) {
        var old=ownedWaves.get(operation);if(old==null||!old.remaining().contains(entity))return;
        var next=new HashSet<>(old.remaining());next.remove(entity);ownedWaves.put(operation,new OwnedWave(next,old.expires()));setDirty();
    }
    private void expireOwned(long now) {
        boolean changed=ownedWaves.entrySet().removeIf(e->now>=e.getValue().expires()
            ||e.getValue().remaining().isEmpty()&&engine.operations().stream().noneMatch(o->o.id().equals(e.getKey())));
        if(changed)setDirty();
    }
    public int outstandingOwned(long now) {
        expireOwned(now);int total=ownedWaves.values().stream().mapToInt(v->v.remaining().size()).sum();
        // Old development saves without a ledger remain unknown, never empty.
        for(var op:engine.operations())if(op.spent()>0&&!ownedWaves.containsKey(op.id()))total+=op.spent();
        return total;
    }
    public boolean ownedCleared(UUID operation,long now) {
        expireOwned(now);var wave=ownedWaves.get(operation);return wave!=null&&wave.remaining().isEmpty();
    }
    private static UUID canonicalUuid(String raw) {
        var id=UUID.fromString(raw);if(!id.toString().equals(raw))throw new IllegalArgumentException("Director owned UUID");return id;
    }
    private void readOwned(String source) {
        var raw=CinematicCatalog.strict(source).getAsJsonObject();if(raw.size()>128)throw new IllegalArgumentException("Director owned waves");
        var all=new HashSet<UUID>();
        for(var entry:raw.entrySet()) {
            var row=entry.getValue().getAsJsonObject();if(!row.keySet().equals(Set.of("remaining","expires")))throw new IllegalArgumentException("Director owned fields");
            var remaining=new HashSet<UUID>();var list=row.getAsJsonArray("remaining");if(list.size()>32)throw new IllegalArgumentException("Director owned population");
            for(var item:list) {
                if(!item.isJsonPrimitive()||!item.getAsJsonPrimitive().isString())throw new IllegalArgumentException("Director owned entity");
                var id=canonicalUuid(item.getAsString());if(!remaining.add(id)||!all.add(id))throw new IllegalArgumentException("Duplicate director entity");
            }
            var expiry=row.get("expires");if(!expiry.isJsonPrimitive()||!expiry.getAsJsonPrimitive().isNumber())throw new IllegalArgumentException("Director owned expiry");
            long expires=expiry.getAsBigDecimal().longValueExact();if(expires<=0)throw new IllegalArgumentException("Director owned expiry");
            ownedWaves.put(canonicalUuid(entry.getKey()),new OwnedWave(remaining,expires));
        }
    }
    private CinematicStore() {}
    public static CinematicStore create(UUID campaign) {
        var store=new CinematicStore();store.campaign=campaign;store.engine=new CinematicEngine();store.setDirty();return store;
    }
    public static CinematicStore load(UUID expected,CompoundTag tag,HolderLookup.Provider registries) {
        var store=new CinematicStore();
        try {
            if(!tag.contains("schema",Tag.TAG_INT)||tag.getInt("schema")!=1||!tag.contains("campaign",Tag.TAG_STRING)
                    ||!expected.toString().equals(tag.getString("campaign"))||!tag.contains("queue",Tag.TAG_STRING))throw new IllegalArgumentException("Director schema");
            var raw=CinematicCatalog.strict(tag.getString("queue"));
            var snapshot=JSON.fromJson(raw,CinematicEngine.Snapshot.class);
            if(!normalized(JSON.toJsonTree(snapshot)).equals(normalized(raw)))throw new IllegalArgumentException("Director snapshot fields");
            store.campaign=expected;store.engine=CinematicEngine.restore(snapshot);
            if(tag.contains("owned_waves")){if(!tag.contains("owned_waves",Tag.TAG_STRING))throw new IllegalArgumentException("Director owned format");store.readOwned(tag.getString("owned_waves"));}
            store.setDirty();
        } catch(RuntimeException invalid){store.unsupported=tag.copy();store.setDirty(false);}
        return store;
    }
    public boolean enabled(){return unsupported==null;}
    public CinematicEngine engine(){if(!enabled())throw new IllegalStateException("Unsupported director save");return engine;}
    @Override public CompoundTag save(CompoundTag tag,HolderLookup.Provider registries) {
        if(!enabled())return unsupported.copy();
        String data=JSON.toJson(engine.snapshot());CinematicCatalog.strict(data);
        tag.putInt("schema",1);tag.putString("campaign",campaign.toString());tag.putString("queue",data);
        String owned=JSON.toJson(ownedWaves);CinematicCatalog.strict(owned);tag.putString("owned_waves",owned);return tag;
    }
}
