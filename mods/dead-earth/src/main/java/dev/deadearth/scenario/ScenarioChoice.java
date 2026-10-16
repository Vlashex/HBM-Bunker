package dev.deadearth.scenario;

import java.util.Set;
import java.util.UUID;

/** Server-owned catalog choices, never arbitrary narrative or coordinates. */
public record ScenarioChoice(String catalogId, String targetId, String variant, int delayTicks) {
    public static final String WARNING = "warning_beacon_v1", RADIO = "relay_broadcast_v1";
    public static final Set<String> RADIO_VARIANTS = Set.of("check_in", "survey", "rally");
    public ScenarioChoice {
        if (catalogId == null || targetId == null || variant == null || delayTicks < 200 || delayTicks > 1200 || delayTicks % 20 != 0)
            throw new IllegalArgumentException("Invalid scenario choice");
        if (catalogId.equals(WARNING)) {
            if (!targetId.equals("test_anchor") || !variant.equals("standard")) throw new IllegalArgumentException("Invalid warning choice");
        } else if (catalogId.equals(RADIO)) {
            if (!RADIO_VARIANTS.contains(variant) || !targetId.startsWith("relay:") || targetId.length() != 42)
                throw new IllegalArgumentException("Invalid radio choice");
            var id = targetId.substring(6);
            if (!UUID.fromString(id).toString().equals(id)) throw new IllegalArgumentException("Invalid relay identity");
        } else throw new IllegalArgumentException("Unknown catalog entry");
    }
    public static ScenarioChoice warning() { return new ScenarioChoice(WARNING,"test_anchor","standard",200); }
    public boolean radio() { return catalogId.equals(RADIO); }
}
