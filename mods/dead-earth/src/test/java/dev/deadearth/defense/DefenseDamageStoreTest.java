package dev.deadearth.defense;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DefenseDamageStoreTest {
    private static final ResourceLocation WALL=ResourceLocation.parse("dead_earth:defense_wall_v1");
    @Test void damageIsSparseSharedAndSurvivesReloadWithoutConfigHealing() {
        var data=DefenseDamageStore.create();
        assertEquals(0,data.size());
        assertEquals(1000,data.remaining(42,WALL,DefenseTier.V1,1000));
        assertEquals(700,data.damage(42,WALL,DefenseTier.V1,1000,300));
        var reload=DefenseDamageStore.load(data.save(new CompoundTag(),null),null);
        assertEquals(700,reload.remaining(42,WALL,DefenseTier.V1,2000));
        assertEquals(600,reload.damage(42,WALL,DefenseTier.V1,2000,100));
        assertEquals(0,reload.damage(42,WALL,DefenseTier.V1,2000,Integer.MAX_VALUE));
        reload.removed(42,WALL);
        assertEquals(0,reload.size());
        assertEquals(2000,reload.remaining(42,WALL,DefenseTier.V1,2000));
    }
    @Test void identityMismatchDoesNotSilentlyRefill() {
        var data=DefenseDamageStore.create();data.damage(42,WALL,DefenseTier.V1,1000,100);
        assertThrows(IllegalStateException.class,()->data.remaining(42,ResourceLocation.parse("dead_earth:defense_wall_v2"),DefenseTier.V2,2000));
        assertThrows(IllegalArgumentException.class,()->data.damage(42,WALL,DefenseTier.V1,1000,-1));
        assertEquals(900,data.remaining(42,WALL,DefenseTier.V1,1000));
    }
    @Test void futureAndCorruptDataArePreservedAndDisableDamage() {
        var valid=DefenseDamageStore.create();valid.damage(9,WALL,DefenseTier.V1,1000,10);
        for(int mode=0;mode<3;mode++) {
            var tag=valid.save(new CompoundTag(),null);
            if(mode==0)tag.putInt("schemaVersion",99);
            if(mode==1)tag.putInt("policyVersion",99);
            if(mode==2)tag.getList("damage",10).getCompound(0).putInt("remaining",1001);
            var data=DefenseDamageStore.load(tag,null);
            assertFalse(data.enabled());assertFalse(data.isDirty());
            assertEquals(tag,data.save(new CompoundTag(),null));
            assertThrows(IllegalStateException.class,()->data.damage(9,WALL,DefenseTier.V1,1000,10));
        }
    }
    @Test void defaultsHaveThreeDistinctHpBudgets() {
        assertEquals(1000,DefenseTier.V1.defaultHp());assertEquals(2000,DefenseTier.V2.defaultHp());assertEquals(8000,DefenseTier.V3.defaultHp());
    }
    @Test void successfulFreshPlacementAfterRawNuclearRemovalStartsANewLifetime() {
        var data=DefenseDamageStore.create();data.damage(5,WALL,DefenseTier.V1,1000,700);
        data.placed(5);assertEquals(1000,data.remaining(5,WALL,DefenseTier.V1,1000));assertEquals(0,data.size());
    }
}
