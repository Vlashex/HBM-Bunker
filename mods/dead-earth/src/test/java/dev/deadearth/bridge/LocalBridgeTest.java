package dev.deadearth.bridge;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class LocalBridgeTest {
    private JsonObject call(int port, JsonObject body) throws Exception {
        try (var socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(2000);
            socket.getOutputStream().write((body.toString() + "\n").getBytes(StandardCharsets.UTF_8));
            return JsonParser.parseString(new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8)).readLine()).getAsJsonObject();
        }
    }
    @Test void unauthorizedClientCannotInvokeHandlerAndBridgeKeepsServing() throws Exception {
        var calls = new AtomicInteger();
        var protocol = new BridgeProtocol(BridgeProtocolTest.CAMPAIGN, BridgeProtocolTest.EPOCH, BridgeProtocolTest.TOKEN);
        try (var bridge = new LocalBridge(protocol, request -> { calls.incrementAndGet(); return CompletableFuture.completedFuture(new JsonPrimitive("ok")); })) {
            assertEquals("127.0.0.1", bridge.host());
            var bad = BridgeProtocolTest.request("get_snapshot"); bad.addProperty("token", "b".repeat(64));
            assertFalse(call(bridge.port(), bad).get("ok").getAsBoolean()); assertEquals(0, calls.get());
            var good = call(bridge.port(), BridgeProtocolTest.request("get_snapshot"));
            assertTrue(good.get("ok").getAsBoolean()); assertEquals("ok", good.get("result").getAsString()); assertEquals(1, calls.get());
        }
    }
    @Test void oversizedResponseIsRejectedAndCloseReleasesListener() throws Exception {
        var protocol = new BridgeProtocol(BridgeProtocolTest.CAMPAIGN, BridgeProtocolTest.EPOCH, BridgeProtocolTest.TOKEN);
        var bridge = new LocalBridge(protocol, request -> CompletableFuture.completedFuture(new JsonPrimitive("x".repeat(262144))));
        int port = bridge.port();
        try {
            var reply = call(port, BridgeProtocolTest.request("get_snapshot"));
            assertFalse(reply.get("ok").getAsBoolean()); assertEquals("response_too_large", reply.getAsJsonObject("error").get("code").getAsString());
        } finally { bridge.close(); }
        assertThrows(java.io.IOException.class, () -> { try (var socket = new Socket("127.0.0.1", port)) {} });
    }
}
