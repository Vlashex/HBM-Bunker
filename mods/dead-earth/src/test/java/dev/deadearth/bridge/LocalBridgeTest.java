package dev.deadearth.bridge;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.Socket;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class LocalBridgeTest {
    @Test void nonReadingPeerCannotHoldResponseWorker() throws Exception {
        var protocol = new BridgeProtocol(BridgeProtocolTest.CAMPAIGN, BridgeProtocolTest.EPOCH, BridgeProtocolTest.TOKEN);
        var firstResponse = new CountDownLatch(1);
        var calls = new AtomicInteger();
        try (var bridge = new LocalBridge(protocol, request -> {
            if (calls.incrementAndGet() == 1) {
                firstResponse.countDown();
                return CompletableFuture.completedFuture(new JsonPrimitive("x".repeat(230000)));
            }
            return CompletableFuture.completedFuture(new JsonPrimitive("ok"));
        }); var stalled = new Socket()) {
            stalled.setReceiveBufferSize(1024);
            stalled.connect(new InetSocketAddress("127.0.0.1", bridge.port()));
            stalled.getOutputStream().write((BridgeProtocolTest.request("get_snapshot") + "\n").getBytes(StandardCharsets.UTF_8));
            stalled.getOutputStream().flush();
            assertTrue(firstResponse.await(1, TimeUnit.SECONDS));
            var reply = call(bridge.port(), BridgeProtocolTest.request("get_snapshot"), 4000);
            assertTrue(reply.get("ok").getAsBoolean());
            assertEquals("ok", reply.get("result").getAsString());
            assertEquals(2, calls.get());
        }
    }
    @Test void fragmentedPeerCannotExtendTotalFrameDeadline() throws Exception {
        var protocol = new BridgeProtocol(BridgeProtocolTest.CAMPAIGN, BridgeProtocolTest.EPOCH, BridgeProtocolTest.TOKEN);
        try (var bridge = new LocalBridge(protocol, request -> CompletableFuture.completedFuture(new JsonPrimitive("unused")));
                var socket = new Socket("127.0.0.1", bridge.port())) {
            socket.getOutputStream().write('{'); socket.getOutputStream().flush();
            Thread.sleep(700); socket.getOutputStream().write('"'); socket.getOutputStream().flush();
            Thread.sleep(700); socket.getOutputStream().write('x'); socket.getOutputStream().flush();
            socket.setSoTimeout(1200);
            assertEquals(-1, socket.getInputStream().read(), "Fragmented frame held listener beyond total deadline");
        }
    }
    private JsonObject call(int port, JsonObject body) throws Exception {
        return call(port, body, 2000);
    }
    private JsonObject call(int port, JsonObject body, int timeout) throws Exception {
        try (var socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(timeout);
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
