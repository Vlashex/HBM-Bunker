package dev.deadearth.campaign;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

public final class NbtFields {
    private NbtFields() {}
    public static void require(CompoundTag tag, String key, int type) {
        if (!tag.contains(key, type)) throw new IllegalArgumentException("Missing or invalid field: " + key);
    }
    public static String text(CompoundTag tag, String key) { require(tag, key, Tag.TAG_STRING); return tag.getString(key); }
    public static long number(CompoundTag tag, String key) { require(tag, key, Tag.TAG_LONG); return tag.getLong(key); }
    public static int integer(CompoundTag tag, String key) { require(tag, key, Tag.TAG_INT); return tag.getInt(key); }
    public static ListTag rows(CompoundTag tag, String key, int max) {
        require(tag, key, Tag.TAG_LIST);
        var rows = (ListTag) tag.get(key);
        if (rows.size() > max || (!rows.isEmpty() && rows.getElementType() != Tag.TAG_COMPOUND))
            throw new IllegalArgumentException("Invalid list: " + key);
        return rows;
    }
}
