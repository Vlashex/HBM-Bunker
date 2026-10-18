package dev.deadearth.bridge;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Set;
import java.util.UUID;
import dev.deadearth.scenario.ScenarioChoice;

public final class BridgeProtocol {
    public static final int MAX_INPUT = 8192, MAX_OUTPUT = 262144;
    private static final Set<String> FIELDS = Set.of("protocol", "token", "campaign_id", "world_epoch", "operation", "arguments");
    private final UUID campaign, epoch;
    private final byte[] token;
    public record Request(String operation, JsonObject arguments) {
        public Request { arguments = arguments.deepCopy(); }
    }
    public BridgeProtocol(UUID campaign, UUID epoch, String token) {
        this.campaign = java.util.Objects.requireNonNull(campaign); this.epoch = java.util.Objects.requireNonNull(epoch);
        if (!token.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Invalid bridge secret");
        this.token = token.getBytes(StandardCharsets.UTF_8);
    }
    public Request decode(byte[] frame) {
        if (frame.length > MAX_INPUT) throw new BridgeFailure("frame_too_large");
        String raw;
        try { raw = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(frame)).toString(); }
        catch (CharacterCodingException invalid) { throw new BridgeFailure("invalid_utf8"); }
        depthCheck(raw);
        JsonObject input;
        try { input = JsonParser.parseString(raw).getAsJsonObject(); }
        catch (RuntimeException invalid) { throw new BridgeFailure("invalid_json"); }
        if (!input.keySet().equals(FIELDS) || !input.get("protocol").isJsonPrimitive()
                || !input.getAsJsonPrimitive("protocol").isNumber() || !input.get("protocol").getAsString().equals("1"))
            throw new BridgeFailure("invalid_envelope");
        if (!MessageDigest.isEqual(token, text(input, "token", "unauthorized").getBytes(StandardCharsets.UTF_8)))
            throw new BridgeFailure("unauthorized");
        if (!text(input, "campaign_id", "stale_context").equals(campaign.toString())
                || !text(input, "world_epoch", "stale_context").equals(epoch.toString())) throw new BridgeFailure("stale_context");
        var operation = text(input, "operation", "unknown_operation");
        if (!input.get("arguments").isJsonObject()) throw new BridgeFailure("invalid_arguments");
        var args = input.getAsJsonObject("arguments");
        switch (operation) {
            case "get_snapshot", "list_catalog", "get_director_brief", "get_cinematic_brief", "get_cinematic_queue" -> keys(args, Set.of());
            case "get_event_analytics" -> {keys(args,Set.of("operation_id"));uuid(args,"operation_id");if(!args.get("operation_id").getAsString().equals(UUID.fromString(args.get("operation_id").getAsString()).toString()))throw new BridgeFailure("invalid_arguments");}
            case "choose_cinematic" -> {
                keys(args,Set.of("request_id","goal_id","event_id","zone_id"));
                uuid(args,"request_id");uuid(args,"goal_id");uuid(args,"zone_id");
                if(!text(args,"event_id","invalid_arguments").matches("[a-z][a-z0-9_]{0,63}"))throw new BridgeFailure("invalid_arguments");
            }
            case "preview_director" -> {
                keys(args,Set.of("request_id","goal_id","variant","text"));uuid(args,"request_id");uuid(args,"goal_id");
                if (!dev.deadearth.director.DirectorPolicy.validNarrative(text(args,"variant","invalid_arguments"),text(args,"text","invalid_arguments")))
                    throw new BridgeFailure("invalid_arguments");
            }
            case "get_history" -> { keys(args, Set.of("limit")); long limit = integer(args, "limit"); if (limit < 1 || limit > 100) throw new BridgeFailure("invalid_arguments"); }
            case "preview_scenario", "propose_scenario" -> { uuid(args, "request_id"); choice(args); }
            case "get_scenario_status" -> { keys(args, Set.of("scenario_id")); uuid(args, "scenario_id"); }
            case "cancel_scenario" -> { keys(args, Set.of("scenario_id", "revision")); uuid(args, "scenario_id"); if (integer(args, "revision") < 0) throw new BridgeFailure("invalid_arguments"); }
            default -> throw new BridgeFailure("unknown_operation");
        }
        return new Request(operation, args);
    }
    public static ScenarioChoice choice(JsonObject args) {
        if (!args.has("request_id") || !Set.of("request_id","catalog_id","target_id","variant","delay_ticks").containsAll(args.keySet()))
            throw new BridgeFailure("invalid_arguments");
        long delay = args.has("delay_ticks") ? integer(args,"delay_ticks") : 200;
        if (delay < 200 || delay > 1200) throw new BridgeFailure("invalid_arguments");
        try { return new ScenarioChoice(optionalText(args,"catalog_id",ScenarioChoice.WARNING),
                optionalText(args,"target_id","test_anchor"),optionalText(args,"variant","standard"),(int) delay); }
        catch (IllegalArgumentException invalid) { throw new BridgeFailure("invalid_arguments"); }
    }
    private static String optionalText(JsonObject args, String field, String fallback) {
        return args.has(field) ? text(args,field,"invalid_arguments") : fallback;
    }
    public JsonObject success(JsonElement result) {
        var response = new JsonObject(); response.addProperty("protocol", 1); response.addProperty("campaign_id", campaign.toString());
        response.addProperty("world_epoch", epoch.toString()); response.addProperty("ok", true); response.add("result", result); return response;
    }
    public static JsonObject failure(String code) {
        var response = new JsonObject(); response.addProperty("ok", false); var error = new JsonObject(); error.addProperty("code", code); response.add("error", error); return response;
    }
    private static void keys(JsonObject object, Set<String> fields) {
        if (!object.keySet().equals(fields)) throw new BridgeFailure("invalid_arguments");
    }
    private static String text(JsonObject object, String field, String code) {
        var value = object.get(field);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) throw new BridgeFailure(code);
        return value.getAsString();
    }
    private static void uuid(JsonObject object, String field) {
        var value = text(object, field, "invalid_arguments");
        try { if (value.length() != 36 || !UUID.fromString(value).toString().equalsIgnoreCase(value)) throw new IllegalArgumentException(); }
        catch (IllegalArgumentException invalid) { throw new BridgeFailure("invalid_arguments"); }
    }
    private static long integer(JsonObject object, String field) {
        var value = object.get(field);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()
                || !value.getAsString().matches("-?(0|[1-9][0-9]{0,18})")) throw new BridgeFailure("invalid_arguments");
        try { return Long.parseLong(value.getAsString()); } catch (NumberFormatException invalid) { throw new BridgeFailure("invalid_arguments"); }
    }
    private static void depthCheck(String raw) {
        int depth = 0; boolean quoted = false, escaped = false;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (quoted) {
                if (escaped) escaped = false;
                else if (c == '\\') escaped = true;
                else if (c == '"') quoted = false;
            } else if (c == '"') quoted = true;
            else if (c == '{' || c == '[') { if (++depth > 16) throw new BridgeFailure("too_deep"); }
            else if (c == '}' || c == ']') { if (--depth < 0) throw new BridgeFailure("invalid_json"); }
        }
        if (quoted || depth != 0) throw new BridgeFailure("invalid_json");
    }
}
