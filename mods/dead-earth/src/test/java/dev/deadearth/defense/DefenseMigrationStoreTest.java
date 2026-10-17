package dev.deadearth.defense;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DefenseMigrationStoreTest {
    @Test void rollbackKeepsNativeNbtAndRejectsConflictingBackups() {
        var data=DefenseMigrationStore.create();var state=new CompoundTag();state.putString("Name","outpostzero:steel_block");
        var be=new CompoundTag();be.putString("owner","preserved native owner");
        var target=ResourceLocation.parse("dead_earth:defense_wall_v1");
        data.record(42,state,target,be);var tag=data.save(new CompoundTag(),null);
        var reload=DefenseMigrationStore.load(tag,null);assertTrue(reload.enabled());assertEquals(tag,reload.save(new CompoundTag(),null));
        be.putString("owner","different");assertThrows(IllegalStateException.class,()->reload.record(42,state,target,be));
        assertEquals(tag,reload.save(new CompoundTag(),null));
    }
    @Test void futureJournalIsNotResetOrMigrated() {
        var tag=new CompoundTag();tag.putInt("schemaVersion",99);tag.putString("future","keep");
        var data=DefenseMigrationStore.load(tag,null);assertFalse(data.enabled());assertFalse(data.isDirty());
        assertEquals(tag,data.save(new CompoundTag(),null));
        assertThrows(IllegalStateException.class,()->data.record(1,new CompoundTag(),ResourceLocation.parse("dead_earth:defense_wall_v1"),new CompoundTag()));
    }
}
