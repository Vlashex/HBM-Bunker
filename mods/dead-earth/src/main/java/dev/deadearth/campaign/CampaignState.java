package dev.deadearth.campaign;

import java.util.Map;
import java.util.UUID;

public record CampaignState(UUID campaignId, long revision, long activeTicks) {
    public static final int SCHEMA_VERSION = 1;

    public CampaignState {
        if (campaignId == null || revision < 0 || activeTicks < 0)
            throw new IllegalArgumentException("Invalid campaign state");
    }

    public Map<String, String> toValues() {
        return Map.of("schemaVersion", "1", "campaignId", campaignId.toString(),
                "revision", Long.toString(revision), "activeTicks", Long.toString(activeTicks));
    }

    public static CampaignState fromValues(Map<String, String> fields) {
        if (Integer.parseInt(fields.get("schemaVersion")) != SCHEMA_VERSION)
            throw new IllegalArgumentException("Unsupported campaign schema");
        String id = fields.get("campaignId");
        if (id == null) throw new IllegalArgumentException("Missing campaign identity");
        return new CampaignState(UUID.fromString(id), Long.parseLong(fields.get("revision")),
                Long.parseLong(fields.get("activeTicks")));
    }
}
