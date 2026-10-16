package dev.deadearth.bridge;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;
import java.util.function.LongSupplier;

public final class ServerCallQueue implements AutoCloseable {
    private record Call(String operation, JsonObject arguments, long created, CompletableFuture<JsonElement> reply) {}
    private final ArrayBlockingQueue<Call> pending = new ArrayBlockingQueue<>(32);
    private final LongSupplier clock;
    private volatile boolean closed;
    public ServerCallQueue(LongSupplier clock) { this.clock = clock; }
    public synchronized CompletableFuture<JsonElement> submit(String operation, JsonObject args) {
        var reply = new CompletableFuture<JsonElement>();
        if (closed) reply.completeExceptionally(new BridgeFailure("bridge_stopped"));
        else if (!pending.offer(new Call(operation, args.deepCopy(), clock.getAsLong(), reply)))
            reply.completeExceptionally(new BridgeFailure("server_busy"));
        return reply;
    }
    public void drain(BiFunction<String, JsonObject, JsonElement> handler) {
        for (int i = 0; i < 2 && !closed; i++) {
            var call = pending.poll(); if (call == null) return;
            if (call.reply().isCancelled()) continue;
            if (clock.getAsLong() - call.created() >= 2_000_000_000L) {
                call.reply().completeExceptionally(new BridgeFailure("request_expired")); continue;
            }
            try { call.reply().complete(handler.apply(call.operation(), call.arguments())); }
            catch (RuntimeException failed) { call.reply().completeExceptionally(failed); }
        }
    }
    @Override public synchronized void close() {
        closed = true;
        Call call; while ((call = pending.poll()) != null) call.reply().completeExceptionally(new BridgeFailure("bridge_stopped"));
    }
}
