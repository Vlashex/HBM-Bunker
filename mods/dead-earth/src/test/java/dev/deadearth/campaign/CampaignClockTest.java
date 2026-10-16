package dev.deadearth.campaign;

import org.junit.jupiter.api.Test;
import java.util.Map;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class CampaignClockTest {
    @Test void advancesOnlyWithOnlinePlayers() {
        assertEquals(101L, CampaignClock.advance(100L, true));
        assertEquals(100L, CampaignClock.advance(100L, false));
    }
    @Test void rejectsNegativeTicks() {
        assertThrows(IllegalArgumentException.class, () -> CampaignClock.advance(-1L, true));
    }
    @Test void doesNotWrapAtLongLimit() {
        assertEquals(Long.MAX_VALUE, CampaignClock.advance(Long.MAX_VALUE, true));
    }
    @Test void rejectsUnknownSaveSchemaWithoutWriting() {
        var fields = Map.of("schemaVersion", "2", "campaignId", "00000000-0000-0000-0000-000000000001",
                "revision", "8", "activeTicks", "500");
        assertThrows(IllegalArgumentException.class, () -> CampaignState.fromValues(fields));
        assertEquals("2", fields.get("schemaVersion"));
        assertEquals("500", fields.get("activeTicks"));
    }
    @Test void roundTripsCampaignState() {
        var original = new CampaignState(UUID.fromString("00000000-0000-0000-0000-000000000001"), 8L, 500L);
        var restored = CampaignState.fromValues(original.toValues());
        assertEquals(original, restored);
        assertEquals("1", original.toValues().get("schemaVersion"));
    }
    @Test void rejectsMalformedOrMissingState() {
        assertThrows(IllegalArgumentException.class, () -> CampaignState.fromValues(Map.of()));
        assertThrows(IllegalArgumentException.class, () -> new CampaignState(UUID.randomUUID(), 0L, -1L));
    }
}
