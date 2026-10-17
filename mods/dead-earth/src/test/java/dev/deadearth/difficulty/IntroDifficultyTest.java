package dev.deadearth.difficulty;

import static org.junit.jupiter.api.Assertions.*;
import dev.deadearth.climate.*;
import dev.deadearth.compat.SeasonHooks;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

class IntroDifficultyTest {
    @Test void firstHourUsesQuadraticEaseOutRegardlessOfPerformance() {
        assertEquals(.1, IntroDifficulty.pressure(0, 1.5), 1e-9);
        assertEquals(.49375, IntroDifficulty.pressure(18000, .75), 1e-9);
        assertEquals(.775, IntroDifficulty.pressure(36000, 1.5), 1e-9);
        assertEquals(.94375, IntroDifficulty.pressure(54000, .75), 1e-9);
        assertEquals(1, IntroDifficulty.pressure(72000, 1.5), 1e-9);
    }
    @Test void adaptiveHandoffIsContinuousAndPreservesOriginalBounds() {
        assertEquals(1.25, IntroDifficulty.pressure(75000, 1.5), 1e-9);
        assertEquals(1.5, IntroDifficulty.pressure(78000, 1.5), 1e-9);
        assertEquals(.75, IntroDifficulty.pressure(300000, .75), 1e-9);
        assertThrows(IllegalArgumentException.class, () -> IntroDifficulty.pressure(-1, 1));
        assertThrows(IllegalArgumentException.class, () -> IntroDifficulty.pressure(0, Double.NaN));
    }
    @Test void quarterSpeedOnlyChangesFutureEvolutionAndKeepsCombatSeasonal() {
        try {
            SeasonState.update(ClimateCycle.Phase.ASH_WINTER, 1);
            assertEquals(.45, SeasonState.parasiteEvolution(), 1e-9);
            assertEquals(1.8, SeasonState.parasiteHealth(), 1e-9);
            assertEquals(1.8, SeasonState.parasiteDamage(), 1e-9);
            var tag = new CompoundTag(); tag.putInt("Timer", 100);
            for (int n=0; n<20; n++) tag.putInt("Timer", SeasonHooks.counterRead(tag,"Timer")+1);
            assertEquals(109, tag.getInt("Timer"));
        } finally { SeasonState.reset(); }
    }
    @Test void effectiveSnapshotSurvivesReloadWithoutRestartingIntroOrNativeProgress() {
        var id = java.util.UUID.randomUUID();
        var store = AdaptiveStore.create(id,36000);
        var tag = store.save(new CompoundTag(),null);
        var restored = AdaptiveStore.load(id,36000,tag,null);
        var before = store.view(ClimateCycle.Phase.CALM,36000);
        assertEquals(.775,before.pressure(),1e-9);
        assertEquals(.775*.25,before.parasiteEvolutionRate(),1e-9);
        assertEquals(36000,before.introTicksRemaining());
        assertEquals(before,restored.view(ClimateCycle.Phase.CALM,36000));
        assertEquals(tag,restored.save(new CompoundTag(),null));
    }
    @Test void largestConfiguredEvolutionProductCannotCrashCounterHooks() {
        var tag = new CompoundTag(); tag.putInt("Timer",100);
        // season<=4, pressure<=4 and native evolution multiplier<=4.
        assertEquals(164,SeasonHooks.counterRead(tag,"Timer",64)+1);
    }
}
