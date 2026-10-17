package dev.deadearth.director;

import com.google.gson.Gson;
import java.util.UUID;
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
            if(!JSON.toJsonTree(snapshot).equals(raw))throw new IllegalArgumentException("Director snapshot fields");
            store.campaign=expected;store.engine=CinematicEngine.restore(snapshot);store.setDirty();
        } catch(RuntimeException invalid){store.unsupported=tag.copy();store.setDirty(false);}
        return store;
    }
    public boolean enabled(){return unsupported==null;}
    public CinematicEngine engine(){if(!enabled())throw new IllegalStateException("Unsupported director save");return engine;}
    @Override public CompoundTag save(CompoundTag tag,HolderLookup.Provider registries) {
        if(!enabled())return unsupported.copy();
        String data=JSON.toJson(engine.snapshot());CinematicCatalog.strict(data);
        tag.putInt("schema",1);tag.putString("campaign",campaign.toString());tag.putString("queue",data);return tag;
    }
}
