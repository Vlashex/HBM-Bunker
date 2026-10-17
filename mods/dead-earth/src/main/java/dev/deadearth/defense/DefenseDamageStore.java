package dev.deadearth.defense;

import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.saveddata.SavedData;

/** Sparse damage only. A recorded material keeps its original HP budget across config changes. */
public final class DefenseDamageStore extends SavedData {
    public static final String NAME="dead_earth_defense_damage";
    public static final int POLICY_VERSION=1,MAX_RECORDS=262144;
    private record Damage(ResourceLocation block,DefenseTier tier,int maximum,int remaining){}
    private final Map<Long,Damage> damage=new LinkedHashMap<>();
    private CompoundTag unsupported;
    private DefenseDamageStore(){}
    public static DefenseDamageStore create(){return new DefenseDamageStore();}
    public static DefenseDamageStore load(CompoundTag tag,HolderLookup.Provider registries) {
        var data=new DefenseDamageStore();
        try {
            if(!tag.contains("schemaVersion",Tag.TAG_INT)||tag.getInt("schemaVersion")!=1
                    ||!tag.contains("policyVersion",Tag.TAG_INT)||tag.getInt("policyVersion")!=POLICY_VERSION
                    ||!tag.contains("damage",Tag.TAG_LIST))throw new IllegalArgumentException("Unknown defense data");
            var entries=tag.getList("damage",Tag.TAG_COMPOUND);
            if(entries.size()!=((ListTag)tag.get("damage")).size()||entries.size()>MAX_RECORDS)throw new IllegalArgumentException("Invalid damage count");
            for(var entry:entries) {
                var row=(CompoundTag)entry;
                if(!row.contains("anchor",Tag.TAG_LONG)||!row.contains("block",Tag.TAG_STRING)||!row.contains("tier",Tag.TAG_STRING)
                        ||!row.contains("maximum",Tag.TAG_INT)||!row.contains("remaining",Tag.TAG_INT))throw new IllegalArgumentException("Incomplete damage");
                var block=ResourceLocation.parse(row.getString("block"));var tier=DefenseTier.valueOf(row.getString("tier"));
                int max=row.getInt("maximum"),remaining=row.getInt("remaining");
                if(max<1||max>100000000||remaining<0||remaining>=max||data.damage.putIfAbsent(row.getLong("anchor"),new Damage(block,tier,max,remaining))!=null)
                    throw new IllegalArgumentException("Invalid damage");
            }
        }catch(RuntimeException invalid){data.damage.clear();data.unsupported=tag.copy();data.setDirty(false);}
        return data;
    }
    public boolean enabled(){return unsupported==null;}
    public int size(){return damage.size();}
    public int remaining(long anchor,ResourceLocation block,DefenseTier tier,int maximum) {
        if(!enabled())throw new IllegalStateException("Unsupported damage data preserved");
        var row=damage.get(anchor);
        if(row==null)return maximum;
        if(!row.block.equals(block)||row.tier!=tier)throw new IllegalStateException("Damage material mismatch; refusing implicit refill");
        return row.remaining;
    }
    public int damage(long anchor,ResourceLocation block,DefenseTier tier,int maximum,int amount) {
        if(amount<0||maximum<1||maximum>100000000)throw new IllegalArgumentException("Invalid HP change");
        int before=remaining(anchor,block,tier,maximum);
        if(amount==0)return before;
        if(!damage.containsKey(anchor)&&damage.size()>=MAX_RECORDS)throw new IllegalStateException("Defense damage budget exhausted");
        int after=(int)Math.max(0,(long)before-amount);
        var old=damage.get(anchor);
        damage.put(anchor,new Damage(block,tier,old==null?maximum:old.maximum,after));setDirty();return after;
    }
    public void removed(long anchor,ResourceLocation block) {
        if(!enabled())return;
        var row=damage.get(anchor);
        if(row!=null&&row.block.equals(block)){damage.remove(anchor);setDirty();}
    }
    /** A successful new placement starts a new lifetime, including after a raw nuclear snapshot. */
    public void placed(long anchor) {
        if(enabled()&&damage.remove(anchor)!=null)setDirty();
    }
    @Override public CompoundTag save(CompoundTag tag,HolderLookup.Provider registries) {
        if(!enabled())return unsupported.copy();
        tag.putInt("schemaVersion",1);tag.putInt("policyVersion",POLICY_VERSION);var rows=new ListTag();
        damage.forEach((anchor,value)->{var row=new CompoundTag();row.putLong("anchor",anchor);row.putString("block",value.block.toString());row.putString("tier",value.tier.name());row.putInt("maximum",value.maximum);row.putInt("remaining",value.remaining);rows.add(row);});
        tag.put("damage",rows);return tag;
    }
}
