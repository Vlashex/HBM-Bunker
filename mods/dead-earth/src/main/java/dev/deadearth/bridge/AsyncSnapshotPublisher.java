package dev.deadearth.bridge;

import com.google.gson.JsonObject;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Function;

/** One encoder and one replaceable pending capture. Payloads must be deeply immutable. */
public final class AsyncSnapshotPublisher<T> implements AutoCloseable {
    private record Work<T>(T snapshot, long captured, CompletableFuture<Boolean> completion) {}
    private final Object lifecycle = new Object();
    private final SnapshotCache cache;
    private final Function<T, JsonObject> encoder;
    private final Consumer<RuntimeException> onFailure;
    private Work<T> pending, current;
    private boolean closed;

    public AsyncSnapshotPublisher(SnapshotCache cache, Function<T, JsonObject> encoder,
            Consumer<RuntimeException> onFailure) {
        this.cache = Objects.requireNonNull(cache); this.encoder = Objects.requireNonNull(encoder);
        this.onFailure = Objects.requireNonNull(onFailure);
        var worker = new Thread(this::run, "dead-earth-bridge-snapshot");
        worker.setDaemon(true); worker.start();
    }

    /** True means published; false means superseded or closed. Failures retain the previous cache. */
    public CompletableFuture<Boolean> submit(T snapshot, long capturedNanos) {
        var completion = new CompletableFuture<Boolean>(); Work<T> replaced;
        synchronized (lifecycle) {
            if (closed) return CompletableFuture.completedFuture(false);
            replaced = pending; pending = new Work<>(Objects.requireNonNull(snapshot), capturedNanos, completion);
            lifecycle.notifyAll();
        }
        if (replaced != null) replaced.completion().complete(false);
        return completion;
    }

    private void run() {
        while (true) {
            Work<T> work;
            synchronized (lifecycle) {
                while (!closed && pending == null) {
                    try { lifecycle.wait(); }
                    catch (InterruptedException ignored) { /* Recheck lifecycle before accepting work. */ }
                }
                if (closed) return;
                work = pending; pending = null; current = work;
            }
            try {
                // JSON ownership and projection are prepared outside the close/publication barrier.
                var prepared = SnapshotCache.prepare(encoder.apply(work.snapshot()), work.captured());
                boolean published;
                synchronized (lifecycle) {
                    published = !closed;
                    if (published) cache.publishPrepared(prepared);
                    if (current == work) current = null;
                }
                work.completion().complete(published);
            } catch (RuntimeException failure) {
                boolean report;
                synchronized (lifecycle) {
                    report = !closed;
                    if (current == work) current = null;
                }
                if (report) {
                    try { onFailure.accept(failure); }
                    catch (RuntimeException reportingFailure) { failure.addSuppressed(reportingFailure); }
                }
                work.completion().completeExceptionally(failure);
            }
        }
    }

    /** Never waits for the encoder. A late result cannot cross the closed publication barrier. */
    @Override public void close() {
        Work<T> discarded, active;
        synchronized (lifecycle) {
            if (closed) return;
            closed = true; discarded = pending; active = current; pending = null; current = null;
            lifecycle.notifyAll();
        }
        // Completion callbacks may run arbitrary caller code, so never invoke them under the barrier.
        if (discarded != null) discarded.completion().complete(false);
        if (active != null) active.completion().complete(false);
    }
}
