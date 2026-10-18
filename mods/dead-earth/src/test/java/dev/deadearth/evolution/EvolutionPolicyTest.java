package dev.deadearth.evolution;

import static org.junit.jupiter.api.Assertions.*;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

class EvolutionPolicyTest {
    private final EvolutionCatalog config=EvolutionCatalog.defaults();
    @Test void sixPhasesHaveExactBoundariesAndDistinctUnlocks() {
        assertEquals(0,config.phase(5999.99));assertEquals(1,config.phase(6000));
        assertEquals(2,config.phase(18000));assertEquals(3,config.phase(36000));
        assertEquals(4,config.phase(72000));assertEquals(5,config.phase(120000));
        assertFalse(config.allowsEntity(0,"mutationcraft:assimilated_human"));
        assertTrue(config.allowsEntity(1,"mutationcraft:assimilated_human"));
        assertFalse(config.allowsEntity(3,"mutationcraft:human_stage_3"));
        assertTrue(config.allowsEntity(5,"mutationcraft:human_stage_3"));
        assertFalse(config.abilities(3).contains("putrid"));
        assertTrue(config.abilities(4).contains("putrid"));
    }
    @Test void migrationUsesOnlyActiveCampaignAndHappensOnce() {
        var id=UUID.randomUUID();var s=EvolutionStore.create(id,24000,config,774403);
        assertEquals(6000,s.points());assertEquals(1,config.phase(s.points()));
        assertEquals(774403,s.legacyTime());
        var restored=EvolutionStore.load(id,24000,s.save(new CompoundTag(),null),null);
        assertTrue(restored.enabled());assertEquals(s.points(),restored.points());
        restored.advance(24000,true,.25,config);
        assertEquals(s.points(),restored.points());
    }
    @Test void noPlayersDuplicateTicksAndDimensionCallbacksCannotAdvanceProgress() {
        var s=EvolutionStore.create(UUID.randomUUID(),0,config,0);
        s.advance(0,false,.25,config);assertEquals(0,s.points());
        s.advance(1,true,.25,config);s.advance(1,true,.25,config);
        assertEquals(.25,s.points(),1e-12);
        s.advance(1,false,.25,config);assertEquals(.25,s.points(),1e-12);
        assertThrows(IllegalArgumentException.class,()->s.advance(0,true,1,config));
        assertThrows(IllegalArgumentException.class,()->s.advance(2,true,Double.NaN,config));
    }
    @Test void manualShiftIsIdempotentAndDecreaseResetsToStartOfLowerPhase() {
        var s=EvolutionStore.create(UUID.randomUUID(),24000,config,0);
        var req=UUID.randomUUID();long rev=s.revision();
        var up=s.shift(req,rev,1,24000,config);
        assertTrue(up.accepted());assertEquals(2,config.phase(s.points()));
        assertEquals(up,s.shift(req,rev,1,24000,config));
        assertEquals(2,config.phase(s.points()));
        assertEquals("conflicting_request",s.shift(req,rev,-1,24000,config).code());
        assertEquals("stale_revision",s.shift(UUID.randomUUID(),rev,1,24000,config).code());
        assertTrue(s.shift(UUID.randomUUID(),s.revision(),-1,24000,config).accepted());
        assertEquals(6000,s.points());assertEquals(1,config.phase(s.points()));
        var restored=EvolutionStore.load(s.campaignId(),24000,s.save(new CompoundTag(),null),null);
        assertEquals(up,restored.shift(req,rev,1,24000,config));
    }
    @Test void invalidSaveIsRetainedAndRequiredFileCannotBeRemigrated() {
        var id=UUID.randomUUID();var s=EvolutionStore.create(id,0,config,0);
        var tag=s.save(new CompoundTag(),null);tag.putInt("schemaVersion",99);
        var bad=EvolutionStore.load(id,0,tag,null);
        assertFalse(bad.enabled());assertEquals(tag,bad.save(new CompoundTag(),null));
        var c=dev.deadearth.campaign.CampaignStore.create();c.requireEvolution();
        assertThrows(IllegalStateException.class,()->EvolutionStore.createForCampaign(c,config,0));
    }
    @Test void boundariesAndMalformedConfigurationAreRejected() {
        var s=EvolutionStore.create(UUID.randomUUID(),0,config,0);
        assertEquals("phase_limit",s.shift(UUID.randomUUID(),s.revision(),-1,0,config).code());
        assertThrows(IllegalArgumentException.class,()->EvolutionCatalog.parse("{}"));
        var json=config.source();
        assertThrows(IllegalArgumentException.class,()->EvolutionCatalog.parse(json.replace("120000","72000")));
        assertThrows(IllegalArgumentException.class,()->EvolutionCatalog.parse(json.replace("\"infection\"","\"arbitrary_command\"")));
        assertTrue(config.wave(3,"scouts").contains("mutationcraft:human_stage_1"));
        assertTrue(config.wave(0,"scouts").isEmpty());
    }
    @Test void queuedWaveIsResolvedAgainstCurrentPhaseAndBossesConsumeMoreBudget() {
        assertTrue(EvolutionWaves.plan(config,0,"scouts",java.util.List.of("mutationcraft:human_herder"),10).isEmpty());
        var early=EvolutionWaves.plan(config,1,"scouts",java.util.List.of("mutationcraft:human_herder"),10);
        assertFalse(early.isEmpty());assertTrue(early.stream().allMatch(id->config.allowsEntity(1,id)));
        var late=EvolutionWaves.plan(config,5,"scouts",java.util.List.of("mutationcraft:assimilated_human"),10);
        assertTrue(late.contains("mutationcraft:human_stage_3"));assertTrue(late.size()<10);
        var custom=EvolutionWaves.plan(config,1,"custom",java.util.List.of("mutationcraft:human_herder"),10);
        assertTrue(custom.isEmpty());
    }
    @Test void malformedJournalElementTypeCannotBecomeEmptyHistory() {
        var id=UUID.randomUUID();var s=EvolutionStore.create(id,0,config,0);
        var tag=s.save(new CompoundTag(),null);var wrong=new net.minecraft.nbt.ListTag();wrong.add(net.minecraft.nbt.StringTag.valueOf("bad"));tag.put("receipts",wrong);
        assertFalse(EvolutionStore.load(id,0,tag,null).enabled());
    }
    @Test void configRejectsDuplicateKeysAndCoercedNumericStrings() {
        var source=config.source();
        assertThrows(IllegalArgumentException.class,()->EvolutionCatalog.parse(source.replace("\"schema\": 1","\"schema\": 1, \"schema\": 1")));
        assertThrows(IllegalArgumentException.class,()->EvolutionCatalog.parse(source.replace("\"migrationRate\": 0.25","\"migrationRate\": \"0.25\"")));
        assertThrows(IllegalArgumentException.class,()->EvolutionCatalog.parse(source.replace("\"threshold\": 6000","\"threshold\": \"6000\"")));
    }
}
