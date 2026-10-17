package dev.deadearth.director;

import java.nio.charset.StandardCharsets;
import java.util.*;

/** D1 only: server-owned observation goals and read-only validation. No world effects. */
public final class DirectorPolicy {
    public static final List<String> VARIANTS = List.of("distant_sound", "radio_static", "spore_trace");
    public record Settings(boolean enabled, long intervalTicks, long lifetimeTicks, long maximumZoneAgeTicks) {
        public Settings {
            if (intervalTicks < 200 || lifetimeTicks < 200 || lifetimeTicks > intervalTicks || maximumZoneAgeTicks < 0)
                throw new IllegalArgumentException("Invalid director settings");
        }
        String revision() { return UUID.nameUUIDFromBytes(toString().getBytes(StandardCharsets.UTF_8)).toString(); }
    }
    public record Candidate(String id, double value, long ageTicks, boolean complete, boolean overflow) {
        public Candidate { Objects.requireNonNull(id); }
        boolean usable(Settings settings) {
            return complete && !overflow && Double.isFinite(value) && ageTicks >= 0 && ageTicks <= settings.maximumZoneAgeTicks();
        }
    }
    public record Goal(UUID id, UUID campaignId, UUID worldEpoch, String policyRevision, String targetId,
                       String tactic, long createdActiveTicks, long expiresActiveTicks, List<String> variants) {}
    public record Brief(String status, long activeTicks, long nextDecisionActiveTicks, int zonesSeen,
                        List<Candidate> candidates, Goal goal) {}
    public record Verdict(UUID requestId, UUID goalId, boolean accepted, String code, boolean previewOnly,
                          String targetId, String variant, String text) {}
    private Goal current;
    private long nextDecision;
    public Brief brief(UUID campaign, UUID epoch, long now, int online, Settings settings, List<Candidate> candidates) {
        Objects.requireNonNull(campaign); Objects.requireNonNull(epoch);
        var ranked = ranked(candidates, settings);
        if (!settings.enabled()) { current = null; return new Brief("disabled", now, nextDecision, candidates.size(), ranked, null); }
        if (online <= 0) return new Brief("no_players", now, nextDecision, candidates.size(), ranked, null);
        if (current != null && !valid(current, campaign, epoch, now, online, settings, candidates)) current = null;
        if (current == null && now >= nextDecision && now <= Long.MAX_VALUE - settings.intervalTicks()) {
            String target = ranked.isEmpty() ? "campaign" : "zone:" + ranked.getFirst().id();
            current = new Goal(UUID.randomUUID(), campaign, epoch, settings.revision(), target,
                    ranked.isEmpty() ? "observe_campaign" : "observe_zone", now, now + settings.lifetimeTicks(), VARIANTS);
            nextDecision = now + settings.intervalTicks();
        }
        return new Brief(current == null ? "waiting" : "ready", now, nextDecision, candidates.size(), ranked, current);
    }
    public Verdict preview(UUID request, UUID goal, String variant, String text, UUID campaign, UUID epoch,
                           long now, int online, Settings settings, List<Candidate> candidates) {
        String error = null;
        if (!validNarrative(variant, text)) error = "invalid_narrative";
        else if (current == null || !current.id().equals(goal)) error = "unknown_goal";
        else if (!valid(current, campaign, epoch, now, online, settings, candidates)) error = "stale_goal";
        if (error != null) return new Verdict(request, goal, false, error, true, null, null, null);
        return new Verdict(request, goal, true, "preview_accepted", true, current.targetId(), variant, text);
    }
    private static boolean valid(Goal goal, UUID campaign, UUID epoch, long now, int online, Settings settings, List<Candidate> candidates) {
        return settings.enabled() && online > 0 && goal.campaignId().equals(campaign) && goal.worldEpoch().equals(epoch)
                && goal.policyRevision().equals(settings.revision()) && now >= goal.createdActiveTicks() && now < goal.expiresActiveTicks()
                && (goal.targetId().equals("campaign") || candidates.stream().anyMatch(c ->
                        goal.targetId().equals("zone:" + c.id()) && c.usable(settings)));
    }
    private static List<Candidate> ranked(List<Candidate> candidates, Settings settings) {
        return candidates.stream().filter(c -> c.usable(settings))
                .sorted(Comparator.comparingDouble(Candidate::value).reversed().thenComparing(Candidate::id)).limit(3).toList();
    }
    public static boolean validNarrative(String variant, String text) {
        return variant != null && VARIANTS.contains(variant) && text != null && !text.isBlank() && text.equals(text.strip())
                && text.codePointCount(0, text.length()) <= 180
                && text.codePoints().noneMatch(c -> Character.isISOControl(c) || c == 0xA7
                        || c >= 0xD800 && c <= 0xDFFF || c >= 0x202A && c <= 0x202E || c >= 0x2066 && c <= 0x2069);
    }
}
