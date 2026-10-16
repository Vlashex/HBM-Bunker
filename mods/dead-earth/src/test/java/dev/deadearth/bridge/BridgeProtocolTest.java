package dev.deadearth.bridge;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class BridgeProtocolTest {
    static final UUID CAMPAIGN = UUID.fromString("00000000-0000-4000-8000-000000000001");
    static final UUID EPOCH = UUID.fromString("00000000-0000-4000-8000-000000000002");
    static final String TOKEN = "a".repeat(64);
    private final BridgeProtocol protocol = new BridgeProtocol(CAMPAIGN, EPOCH, TOKEN);
    static JsonObject request(String operation) {
        var request = new JsonObject(); request.addProperty("protocol", 1); request.addProperty("token", TOKEN);
        request.addProperty("campaign_id", CAMPAIGN.toString()); request.addProperty("world_epoch", EPOCH.toString());
        request.addProperty("operation", operation); request.add("arguments", new JsonObject()); return request;
    }
    static byte[] bytes(JsonObject request) { return request.toString().getBytes(StandardCharsets.UTF_8); }
    private void rejects(JsonObject request, String code) {
        assertEquals(code, assertThrows(BridgeFailure.class, () -> protocol.decode(bytes(request))).code());
    }
    @Test void radioArgumentsAreBoundedAndLegacyWarningStillDecodes() {
        var protocol = new BridgeProtocol(CAMPAIGN,EPOCH,TOKEN);
        var raw=request("propose_scenario");
        var args=raw.getAsJsonObject("arguments");
        args.addProperty("request_id",CAMPAIGN.toString());
        args.addProperty("catalog_id","relay_broadcast_v1");args.addProperty("target_id","relay:"+EPOCH);
        args.addProperty("variant","rally");args.addProperty("delay_ticks",400);
        assertEquals("rally",protocol.decode(raw.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)).arguments().get("variant").getAsString());
        args.addProperty("delay_ticks",201);
        assertThrows(BridgeFailure.class,()->protocol.decode(raw.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }
    @Test void authenticSnapshotIsDecodedWithoutExposingCredentials() {
        var parsed = protocol.decode(bytes(request("get_snapshot")));
        assertEquals("get_snapshot", parsed.operation()); assertTrue(parsed.arguments().isEmpty());
        assertFalse(parsed.toString().contains(TOKEN));
    }
    @Test void invalidTokenAndStaleContextCannotReachTools() {
        var request = request("get_snapshot"); request.addProperty("token", "b".repeat(64)); rejects(request, "unauthorized");
        request.addProperty("token", TOKEN); request.addProperty("world_epoch", UUID.randomUUID().toString()); rejects(request, "stale_context");
        request.addProperty("world_epoch", EPOCH.toString()); request.addProperty("campaign_id", UUID.randomUUID().toString()); rejects(request, "stale_context");
    }
    @Test void unknownOperationsAndArgumentsAreNotConsoleEscapeHatches() {
        rejects(request("execute_command"), "unknown_operation");
        var request = request("get_snapshot"); request.getAsJsonObject("arguments").addProperty("command", "stop"); rejects(request, "invalid_arguments");
        request = request("get_snapshot"); request.addProperty("extra", "ignored?"); rejects(request, "invalid_envelope");
    }
    @Test void identifiersAndNumericLimitsAreValidated() {
        var request = request("get_history"); request.getAsJsonObject("arguments").addProperty("limit", 101); rejects(request, "invalid_arguments");
        request.getAsJsonObject("arguments").addProperty("limit", 100); assertEquals(100, protocol.decode(bytes(request)).arguments().get("limit").getAsInt());
        request = request("propose_scenario"); request.getAsJsonObject("arguments").addProperty("request_id", "1-1-1-1-1"); rejects(request, "invalid_arguments");
        request = request("cancel_scenario"); request.getAsJsonObject("arguments").addProperty("scenario_id", CAMPAIGN.toString());
        request.getAsJsonObject("arguments").addProperty("revision", -1); rejects(request, "invalid_arguments");
    }
    @Test void oversizedAndDeepFramesAreRejectedBeforeJsonParsing() {
        assertEquals("frame_too_large", assertThrows(BridgeFailure.class, () -> protocol.decode(new byte[8193])).code());
        var request = request("get_snapshot"); var nested = request.getAsJsonObject("arguments");
        for (int i = 0; i < 20; i++) { var next = new JsonObject(); nested.add("x", next); nested = next; }
        rejects(request, "too_deep");
    }
    @Test void invalidUtf8IsNotSilentlyRepaired() {
        assertEquals("invalid_utf8", assertThrows(BridgeFailure.class, () -> protocol.decode(new byte[] {(byte) 0xff})).code());
    }
}
