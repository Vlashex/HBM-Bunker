package dev.deadearth.bridge;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

public final class LocalBridge implements AutoCloseable {
    private final ServerSocket listener = new ServerSocket();
    private final BridgeProtocol protocol;
    private final Function<BridgeProtocol.Request, CompletableFuture<JsonElement>> handler;
    private final Thread worker;
    private final ScheduledThreadPoolExecutor writeDeadlines = new ScheduledThreadPoolExecutor(1, task -> {
        var thread = new Thread(task, "DeadEarth-bridge-write-deadline");
        thread.setDaemon(true);
        return thread;
    });
    private volatile Socket active;
    private volatile boolean closed;
    public LocalBridge(BridgeProtocol protocol, Function<BridgeProtocol.Request, CompletableFuture<JsonElement>> handler) throws IOException {
        this.protocol = protocol; this.handler = handler;
        writeDeadlines.setRemoveOnCancelPolicy(true);
        try { listener.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 16); }
        catch (IOException failed) {
            writeDeadlines.shutdownNow();
            listener.close();
            throw failed;
        }
        worker = new Thread(this::serve, "DeadEarth-local-bridge"); worker.setDaemon(true); worker.start();
    }
    public String host() { return "127.0.0.1"; }
    public int port() { return listener.getLocalPort(); }
    private void serve() {
        while (!closed) {
            try (var socket = listener.accept()) {
                active = socket; if (closed) break;
                socket.setSendBufferSize(8192);
                socket.setSoTimeout(2000); JsonObject response;
                try {
                    var request = protocol.decode(readFrame(socket));
                    var pending = handler.apply(request);
                    try { response = protocol.success(pending.get(3, TimeUnit.SECONDS)); }
                    catch (TimeoutException expired) { pending.cancel(false); throw new BridgeFailure("request_timeout"); }
                    catch (ExecutionException failed) {
                        if (failed.getCause() instanceof BridgeFailure failure) throw failure;
                        throw new BridgeFailure("request_failed");
                    }
                } catch (BridgeFailure invalid) { response = BridgeProtocol.failure(invalid.code()); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); break; }
                catch (RuntimeException failed) { response = BridgeProtocol.failure("request_failed"); }
                var bytes = response.toString().getBytes(StandardCharsets.UTF_8);
                if (bytes.length > BridgeProtocol.MAX_OUTPUT) bytes = BridgeProtocol.failure("response_too_large").toString().getBytes(StandardCharsets.UTF_8);
                writeFrame(socket, bytes);
            } catch (IOException ignored) {
                // A disconnected or stalled local peer must not stop the listener.
            } finally { active = null; }
        }
    }
    private void writeFrame(Socket socket, byte[] bytes) throws IOException {
        // SO_TIMEOUT does not bound writes. Closing this specific socket releases a
        // blocked writer; the timer never touches the world or a later connection.
        java.util.concurrent.ScheduledFuture<?> deadline;
        try { deadline = writeDeadlines.schedule(() -> {
            try { socket.close(); } catch (IOException ignored) {}
        }, 2, TimeUnit.SECONDS); }
        catch (RejectedExecutionException stopped) { throw new IOException("Bridge stopped", stopped); }
        try {
            socket.getOutputStream().write(bytes);
            socket.getOutputStream().write('\n');
            socket.getOutputStream().flush();
        } finally { deadline.cancel(false); }
    }
    private static byte[] readFrame(Socket socket) throws IOException {
        var bytes = new ByteArrayOutputStream();
        long started = System.nanoTime();
        for (int i = 0; i <= BridgeProtocol.MAX_INPUT; i++) {
            long remaining = 2_000_000_000L - (System.nanoTime() - started);
            if (remaining <= 0) throw new BridgeFailure("frame_timeout");
            socket.setSoTimeout((int) Math.max(1, (remaining + 999_999L) / 1_000_000L));
            int next = socket.getInputStream().read();
            if (next == '\n') return bytes.toByteArray();
            if (next < 0) throw new BridgeFailure("incomplete_frame");
            if (i == BridgeProtocol.MAX_INPUT) throw new BridgeFailure("frame_too_large");
            bytes.write(next);
        }
        throw new BridgeFailure("frame_too_large");
    }
    @Override public void close() {
        closed = true;
        try { listener.close(); } catch (IOException ignored) {}
        var socket = active; if (socket != null) try { socket.close(); } catch (IOException ignored) {}
        worker.interrupt();
        try { worker.join(200); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        writeDeadlines.shutdownNow();
    }
}
