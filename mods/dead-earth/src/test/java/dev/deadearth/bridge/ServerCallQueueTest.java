package dev.deadearth.bridge;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.util.ArrayList;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class ServerCallQueueTest {
    @Test void queueIsBoundedAndDrainsAtMostTwoPerTick() {
        var queue = new ServerCallQueue(() -> 0L); var replies = new ArrayList<java.util.concurrent.CompletableFuture<com.google.gson.JsonElement>>();
        for (int i = 0; i < 32; i++) replies.add(queue.submit("preview_scenario", new JsonObject()));
        var refused = queue.submit("preview_scenario", new JsonObject());
        assertInstanceOf(BridgeFailure.class, assertThrows(CompletionException.class, refused::join).getCause());
        var calls = new AtomicInteger(); queue.drain((op, args) -> new JsonPrimitive(calls.incrementAndGet()));
        assertEquals(2, calls.get()); assertEquals(2, replies.stream().filter(java.util.concurrent.CompletableFuture::isDone).count());
        queue.close(); assertTrue(replies.getLast().isCompletedExceptionally());
    }
    @Test void expiredAndCancelledCallsNeverReachWorldHandler() {
        var clock = new AtomicLong(); var queue = new ServerCallQueue(clock::get); var calls = new AtomicInteger();
        var expired = queue.submit("propose_scenario", new JsonObject()); var cancelled = queue.submit("propose_scenario", new JsonObject()); cancelled.cancel(false);
        clock.set(2_000_000_000L); queue.drain((op, args) -> new JsonPrimitive(calls.incrementAndGet()));
        assertEquals(0, calls.get()); assertTrue(expired.isCompletedExceptionally()); queue.close();
    }
    @Test void callerCannotMutateQueuedArguments() {
        var queue = new ServerCallQueue(() -> 0L); var args = new JsonObject(); args.addProperty("revision", 1);
        var reply = queue.submit("cancel_scenario", args); args.addProperty("revision", 999);
        queue.drain((op, saved) -> saved.get("revision")); assertEquals(1, reply.join().getAsInt()); queue.close();
    }
    @Test void handlerFailureDoesNotLoseFollowingCallAndCloseRejectsNewWork() {
        var queue = new ServerCallQueue(() -> 0L);
        var first = queue.submit("bad", new JsonObject()); var second = queue.submit("good", new JsonObject());
        queue.drain((op, args) -> { if (op.equals("bad")) throw new IllegalStateException("failed"); return new JsonPrimitive("ok"); });
        assertTrue(first.isCompletedExceptionally()); assertEquals("ok", second.join().getAsString());
        queue.close(); assertTrue(queue.submit("good", new JsonObject()).isCompletedExceptionally());
    }
}
