package dev.deadearth.defense;

import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.saveddata.SavedData;

/** Rollback journal written before legacy blocks/ownable block entities are replaced. */
public final class DefenseMigrationStore extends SavedData {
    public static final String NAME="dead_earth_defense_migration";
    private static final int MAX_RECORDS=262144;
    private final Map<Long,CompoundTag> originals=new LinkedHashMap<>();
    private CompoundTag unsupported;
    private DefenseMigrationStore(){}
    public static DefenseMigrationStore create(){return new DefenseMigrationStore();}
    public boolean enabled(){return unsupported==null;}
    public static DefenseMigrationStore load(CompoundTag tag,HolderLookup.Provider registries) {
        var data=new DefenseMigrationStore();
        try {
            if(!tag.contains("schemaVersion",Tag.TAG_INT)||tag.getInt("schemaVersion")!=1||!tag.contains("originals",Tag.TAG_LIST))throw new IllegalArgumentException();
            var rows=tag.getList("originals",Tag.TAG_COMPOUND);
            if(rows.size()!=((ListTag)tag.get("originals")).size()||rows.size()>MAX_RECORDS)throw new IllegalArgumentException();
            for(var entry:rows) {
                var row=(CompoundTag)entry;
                if(!row.contains("pos",Tag.TAG_LONG)||!row.contains("state",Tag.TAG_COMPOUND)||!row.getCompound("state").contains("Name",Tag.TAG_STRING)
                        ||!row.contains("target",Tag.TAG_STRING)||!row.contains("blockEntity",Tag.TAG_COMPOUND))throw new IllegalArgumentException();
                ResourceLocation.parse(row.getString("target"));ResourceLocation.parse(row.getCompound("state").getString("Name"));
                if(originalsDuplicate(data,row))throw new IllegalArgumentException();
            }
        }catch(RuntimeException invalid){data.originals.clear();data.unsupported=tag.copy();data.setDirty(false);}
        return data;
    }
    private static boolean originalsDuplicate(DefenseMigrationStore data,CompoundTag row){return data.originals.putIfAbsent(row.getLong("pos"),row.copy())!=null;}
    public void record(long pos,CompoundTag state,ResourceLocation target,CompoundTag blockEntity) {
        if(!enabled())throw new IllegalStateException("Unsupported migration journal preserved");
        var row=new CompoundTag();row.putLong("pos",pos);row.put("state",state.copy());row.putString("target",target.toString());row.put("blockEntity",blockEntity.copy());
        var prior=originals.get(pos);
        if(prior!=null){if(!prior.equals(row))throw new IllegalStateException("Conflicting legacy backup; migration stopped");return;}
        if(originals.size()>=MAX_RECORDS)throw new IllegalStateException("Migration journal budget exhausted");
        originals.put(pos,row);setDirty();
    }
    @Override public CompoundTag save(CompoundTag tag,HolderLookup.Provider registries) {
        if(!enabled())return unsupported.copy();
        tag.putInt("schemaVersion",1);var rows=new ListTag();originals.values().forEach(row->rows.add(row.copy()));tag.put("originals",rows);return tag;
    }
}
