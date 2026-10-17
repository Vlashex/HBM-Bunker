package dev.deadearth.starter;

import java.util.Objects;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.saveddata.SavedData;

/** One shared receipt per world, independent of player accounts and chest count. */
public final class StarterStock extends SavedData {
    public static final String NAME="dead_earth_starter_stock";
    private boolean claimed;
    private BlockPos chest;
    private UUID player;
    private long tick;
    private CompoundTag unsupported;
    private StarterStock() {}
    public static StarterStock create(){var stock=new StarterStock();stock.setDirty();return stock;}
    public static StarterStock load(CompoundTag tag,HolderLookup.Provider registries) {
        var stock=new StarterStock();
        try {
            if(!tag.contains("schemaVersion",Tag.TAG_INT)||tag.getInt("schemaVersion")!=1
                    ||!tag.contains("claimed",Tag.TAG_BYTE)||(tag.getByte("claimed")!=0&&tag.getByte("claimed")!=1))
                throw new IllegalArgumentException("Invalid starter receipt");
            stock.claimed=tag.getBoolean("claimed");
            if(stock.claimed) {
                if(!tag.contains("chest",Tag.TAG_LONG)||!tag.hasUUID("player")||!tag.contains("tick",Tag.TAG_LONG)||tag.getLong("tick")<0)
                    throw new IllegalArgumentException("Incomplete starter receipt");
                stock.chest=BlockPos.of(tag.getLong("chest"));stock.player=tag.getUUID("player");stock.tick=tag.getLong("tick");
            }
        } catch(IllegalArgumentException invalid){stock.unsupported=tag.copy();stock.setDirty(false);}
        return stock;
    }
    public boolean enabled(){return unsupported==null;}
    public boolean claimed(){return enabled()&&claimed;}
    public boolean claim(BlockPos chest,UUID player,long tick) {
        if(!enabled()||claimed)return false;
        Objects.requireNonNull(chest);Objects.requireNonNull(player);
        if(tick<0)throw new IllegalArgumentException("Negative receipt time");
        this.chest=chest.immutable();this.player=player;this.tick=tick;claimed=true;setDirty();return true;
    }
    @Override public CompoundTag save(CompoundTag tag,HolderLookup.Provider registries) {
        if(!enabled())return unsupported.copy();
        tag.putInt("schemaVersion",1);tag.putBoolean("claimed",claimed);
        if(claimed){tag.putLong("chest",chest.asLong());tag.putUUID("player",player);tag.putLong("tick",tick);}
        return tag;
    }
}
