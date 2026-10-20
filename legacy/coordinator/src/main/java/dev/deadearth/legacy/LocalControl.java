package dev.deadearth.legacy;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.deadearth.legacy.kernel.CinematicCatalog;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Bounded protocol-1 loopback transport. Only drain/publish access the runtime. */
public final class LocalControl implements AutoCloseable {
    public static final int MAX_INPUT=8192,MAX_OUTPUT=262144;
    private static final Gson JSON=new GsonBuilder().serializeNulls().create();
    private static final Set<String> ENVELOPE=fields("protocol","token","campaign_id","world_epoch","operation","arguments");
    private static final long FRAME_NANOS=TimeUnit.SECONDS.toNanos(2);

    private final LegacyRuntime runtime;
    private final String campaignId,worldEpoch;
    private final byte[] token;
    private final Path descriptor;
    private final ServerSocket listener;
    private final Thread worker;
    private final ArrayBlockingQueue<Request> pending=new ArrayBlockingQueue<Request>(32);
    private final ScheduledThreadPoolExecutor writeDeadlines=new ScheduledThreadPoolExecutor(1,new ThreadFactory() {
        @Override public Thread newThread(Runnable task) {
            Thread thread=new Thread(task,"DeadEarth-legacy-bridge-deadline");thread.setDaemon(true);return thread;
        }
    });
    private volatile Socket active;
    private volatile CachedSnapshot cached;
    private volatile boolean closed;
    private byte[] descriptorBytes;

    public LocalControl(LegacyRuntime runtime)throws IOException {
        this.runtime=java.util.Objects.requireNonNull(runtime);runtime.requireThread();
        campaignId=runtime.campaign.campaignId;worldEpoch=runtime.campaign.worldEpoch;
        canonicalContext(campaignId);canonicalContext(worldEpoch);
        descriptor=runtime.worldDirectory.resolve("dead_earth_bridge.json");
        byte[] secret=new byte[32];new SecureRandom().nextBytes(secret);
        token=hex(secret).getBytes(StandardCharsets.UTF_8);Arrays.fill(secret,(byte)0);
        listener=new ServerSocket();
        worker=new Thread(new Runnable(){@Override public void run(){serve();}},"DeadEarth-legacy-local-bridge");
        worker.setDaemon(true);writeDeadlines.setRemoveOnCancelPolicy(true);
        try {
            listener.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"),0),16);
            publish();writeDescriptor();worker.start();
        } catch(IOException|RuntimeException failed) {
            closed=true;closeSocket(listener);writeDeadlines.shutdownNow();removeOwnDescriptor();throw failed;
        }
    }

    /** Called on the server thread. Expired callers never begin a world action. */
    public void drain() {
        runtime.requireThread();
        for(int count=0;count<8&&!closed;count++) {
            Request request=pending.poll();if(request==null)break;
            if(request.result.isCancelled()||request.result.isDone())continue;
            try {
                // Copy the result while it still belongs to the owner thread;
                // the socket worker receives no mutable live world structures.
                JsonElement value=JSON.toJsonTree(runtime.dispatch(request.operation,request.arguments));
                request.result.complete(value);
            } catch(RuntimeException failed) {
                request.result.completeExceptionally(new Failure("request_failed"));
            }
        }
    }

    /** Publishes an immutable serialization, never a reference into Campaign. */
    public void publish() {
        runtime.requireThread();if(closed)return;
        String snapshot=JSON.toJson(runtime.snapshot());
        cached=new CachedSnapshot(snapshot,System.nanoTime());
    }

    private JsonElement cachedSnapshot() {
        CachedSnapshot current=cached;if(current==null)throw new Failure("snapshot_unavailable");
        long age=Math.max(0,TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-current.captured));
        JsonObject result=new JsonObject();result.add("snapshot",new JsonParser().parse(current.json));
        result.addProperty("cache_age_millis",age);return result;
    }

    private void serve() {
        while(!closed) {
            try(Socket socket=listener.accept()) {
                active=socket;if(closed)break;
                socket.setTcpNoDelay(true);socket.setSendBufferSize(8192);socket.setSoTimeout(2000);
                JsonObject response;
                try {
                    Request request=decode(readFrame(socket));
                    JsonElement value;
                    if(request.operation.equals("get_snapshot"))value=cachedSnapshot();
                    else {
                        if(closed)throw new Failure("bridge_stopped");
                        if(!pending.offer(request))throw new Failure("queue_full");
                        try {value=request.result.get(3,TimeUnit.SECONDS);}
                        catch(TimeoutException expired){request.result.cancel(false);throw new Failure("request_timeout");}
                        catch(CancellationException stopped){throw new Failure("bridge_stopped");}
                        catch(ExecutionException failed) {
                            Throwable cause=failed.getCause();
                            throw cause instanceof Failure?(Failure)cause:new Failure("request_failed");
                        }
                    }
                    response=success(value);
                } catch(Failure invalid) {response=failure(invalid.code);}
                catch(InterruptedException stopped){Thread.currentThread().interrupt();break;}
                catch(RuntimeException failed){response=failure("request_failed");}
                byte[] bytes=JSON.toJson(response).getBytes(StandardCharsets.UTF_8);
                if(bytes.length>MAX_OUTPUT)bytes=JSON.toJson(failure("response_too_large")).getBytes(StandardCharsets.UTF_8);
                writeFrame(socket,bytes);
            } catch(IOException disconnected) {
                // A stalled or disconnected local peer cannot stop the listener.
            } finally {active=null;}
        }
    }

    private Request decode(byte[] bytes) {
        if(bytes.length>MAX_INPUT)throw new Failure("frame_too_large");
        String source;
        try {
            source=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch(CharacterCodingException invalid){throw new Failure("invalid_utf8");}
        depthCheck(source);
        JsonObject input;
        try {
            // The strict reader rejects duplicate keys before Gson's ordinary
            // tree reader can erase them. Keep lexical number forms for integers.
            JsonElement strict=CinematicCatalog.strict(source,MAX_INPUT);
            if(!strict.isJsonObject())throw new IllegalArgumentException();
            input=new JsonParser().parse(source).getAsJsonObject();
        } catch(RuntimeException invalid){throw new Failure("invalid_json");}
        if(!keys(input).equals(ENVELOPE)||!numeric(input.get("protocol"))||!"1".equals(input.get("protocol").getAsString()))
            throw new Failure("invalid_envelope");
        byte[] supplied=text(input,"token","unauthorized").getBytes(StandardCharsets.UTF_8);
        if(!MessageDigest.isEqual(token,supplied))throw new Failure("unauthorized");
        if(!campaignId.equals(text(input,"campaign_id","stale_context"))||!worldEpoch.equals(text(input,"world_epoch","stale_context")))
            throw new Failure("stale_context");
        String operation=text(input,"operation","unknown_operation");
        JsonElement rawArguments=input.get("arguments");
        if(rawArguments==null||!rawArguments.isJsonObject())throw new Failure("invalid_arguments");
        JsonObject arguments=rawArguments.getAsJsonObject();
        switch(operation) {
            case "get_snapshot":
            case "get_cinematic_brief":
            case "get_cinematic_queue":
            case "list_catalog":
                exact(arguments);break;
            case "get_history":
                exact(arguments,"limit");long limit=integer(arguments,"limit");
                if(limit<1||limit>100)throw new Failure("invalid_arguments");break;
            case "choose_cinematic":
                exact(arguments,"request_id","goal_id","event_id","zone_id");
                uuid(arguments,"request_id",false);uuid(arguments,"goal_id",false);uuid(arguments,"zone_id",false);
                if(!text(arguments,"event_id","invalid_arguments").matches("[a-z][a-z0-9_]{0,63}"))throw new Failure("invalid_arguments");
                break;
            case "get_event_analytics":
                exact(arguments,"operation_id");uuid(arguments,"operation_id",true);break;
            case "cancel_scenario":
                exact(arguments,"scenario_id","revision");uuid(arguments,"scenario_id",false);
                if(integer(arguments,"revision")<0)throw new Failure("invalid_arguments");break;
            default: throw new Failure("unknown_operation");
        }
        return new Request(operation,arguments);
    }

    private JsonObject success(JsonElement value) {
        JsonObject result=new JsonObject();result.addProperty("protocol",1);result.addProperty("campaign_id",campaignId);
        result.addProperty("world_epoch",worldEpoch);result.addProperty("ok",true);result.add("result",value==null?JsonNull.INSTANCE:value);return result;
    }

    private static JsonObject failure(String code) {
        JsonObject result=new JsonObject();result.addProperty("ok",false);JsonObject error=new JsonObject();
        error.addProperty("code",code);result.add("error",error);return result;
    }

    private static byte[] readFrame(Socket socket)throws IOException {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();long started=System.nanoTime();InputStream input=socket.getInputStream();
        for(int count=0;count<=MAX_INPUT;count++) {
            long remaining=FRAME_NANOS-(System.nanoTime()-started);
            if(remaining<=0)throw new Failure("frame_timeout");
            socket.setSoTimeout((int)Math.max(1,(remaining+999999L)/1000000L));
            int value;
            try{value=input.read();}catch(SocketTimeoutException expired){throw new Failure("frame_timeout");}
            if(value=='\n')return bytes.toByteArray();
            if(value<0)throw new Failure("incomplete_frame");
            if(count==MAX_INPUT)throw new Failure("frame_too_large");bytes.write(value);
        }
        throw new Failure("frame_too_large");
    }

    private void writeFrame(final Socket socket,byte[] bytes)throws IOException {
        ScheduledFuture<?> deadline;
        try {
            deadline=writeDeadlines.schedule(new Runnable(){@Override public void run(){closeSocket(socket);}},2,TimeUnit.SECONDS);
        } catch(RejectedExecutionException stopped){throw new IOException("Bridge stopped",stopped);}
        try {
            socket.getOutputStream().write(bytes);socket.getOutputStream().write('\n');socket.getOutputStream().flush();
        } finally {deadline.cancel(false);}
    }

    private void writeDescriptor()throws IOException {
        if(Files.isSymbolicLink(descriptor))throw new IOException("Bridge descriptor is a symbolic link");
        JsonObject value=new JsonObject();value.addProperty("protocol",1);value.addProperty("host","127.0.0.1");
        value.addProperty("port",listener.getLocalPort());value.addProperty("token",new String(token,StandardCharsets.UTF_8));
        value.addProperty("campaign_id",campaignId);value.addProperty("world_epoch",worldEpoch);
        descriptorBytes=JSON.toJson(value).getBytes(StandardCharsets.UTF_8);
        Path temporary=Files.createTempFile(descriptor.toAbsolutePath().getParent(),"dead-earth-bridge-",".tmp");
        try {
            Files.write(temporary,descriptorBytes);
            try{Files.move(temporary,descriptor,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}
            catch(AtomicMoveNotSupportedException unsupported){Files.move(temporary,descriptor,StandardCopyOption.REPLACE_EXISTING);}
        } finally {Files.deleteIfExists(temporary);}
    }

    private void removeOwnDescriptor() {
        if(descriptorBytes==null)return;
        try {
            if(Files.isSymbolicLink(descriptor)||!Files.isRegularFile(descriptor,LinkOption.NOFOLLOW_LINKS))return;
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();
            try(InputStream input=Files.newInputStream(descriptor,LinkOption.NOFOLLOW_LINKS)) {
                for(int count=0;count<=4096;count++) {
                    int value=input.read();if(value<0)break;if(count==4096)return;bytes.write(value);
                }
            }
            if(MessageDigest.isEqual(descriptorBytes,bytes.toByteArray()))Files.deleteIfExists(descriptor);
        } catch(IOException ignored) {
            // Never delete a descriptor that no longer matches this instance.
        }
    }

    @Override public synchronized void close() {
        if(closed)return;closed=true;closeSocket(listener);Socket socket=active;if(socket!=null)closeSocket(socket);
        worker.interrupt();Request request;
        while((request=pending.poll())!=null)request.result.cancel(false);
        writeDeadlines.shutdownNow();
        if(Thread.currentThread()!=worker)try{worker.join(200);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}
        removeOwnDescriptor();
    }

    private static void closeSocket(java.io.Closeable socket){try{socket.close();}catch(IOException ignored){}}
    private static void canonicalContext(String value) {
        if(value==null||value.length()!=36||!UUID.fromString(value).toString().equals(value))throw new IllegalArgumentException("Invalid bridge context");
    }
    private static String hex(byte[] bytes) {
        char[] digits="0123456789abcdef".toCharArray(),result=new char[bytes.length*2];
        for(int i=0;i<bytes.length;i++){int value=bytes[i]&255;result[i*2]=digits[value>>>4];result[i*2+1]=digits[value&15];}return new String(result);
    }
    private static Set<String> fields(String... values){return new HashSet<String>(Arrays.asList(values));}
    private static Set<String> keys(JsonObject object) {
        Set<String> result=new HashSet<String>();for(Map.Entry<String,JsonElement> entry:object.entrySet())result.add(entry.getKey());return result;
    }
    private static void exact(JsonObject object,String... names){if(!keys(object).equals(fields(names)))throw new Failure("invalid_arguments");}
    private static String text(JsonObject object,String key,String code) {
        JsonElement value=object.get(key);
        if(value==null||!value.isJsonPrimitive()||!value.getAsJsonPrimitive().isString())throw new Failure(code);return value.getAsString();
    }
    private static boolean numeric(JsonElement value){return value!=null&&value.isJsonPrimitive()&&value.getAsJsonPrimitive().isNumber();}
    private static long integer(JsonObject object,String key) {
        JsonElement value=object.get(key);
        if(!numeric(value)||!value.getAsString().matches("-?(0|[1-9][0-9]{0,18})"))throw new Failure("invalid_arguments");
        try{return Long.parseLong(value.getAsString());}catch(NumberFormatException invalid){throw new Failure("invalid_arguments");}
    }
    private static void uuid(JsonObject object,String key,boolean canonical) {
        String value=text(object,key,"invalid_arguments");
        try {
            String normalized=UUID.fromString(value).toString();
            if(value.length()!=36||!(canonical?normalized.equals(value):normalized.equalsIgnoreCase(value)))throw new IllegalArgumentException();
        } catch(IllegalArgumentException invalid){throw new Failure("invalid_arguments");}
    }
    private static void depthCheck(String source) {
        int depth=0;boolean quoted=false,escaped=false;
        for(int index=0;index<source.length();index++) {
            char value=source.charAt(index);
            if(quoted) {
                if(escaped)escaped=false;else if(value=='\\')escaped=true;else if(value=='"')quoted=false;
            } else if(value=='"')quoted=true;
            else if(value=='{'||value=='['){if(++depth>16)throw new Failure("too_deep");}
            else if(value=='}'||value==']'){if(--depth<0)throw new Failure("invalid_json");}
        }
        if(quoted||depth!=0)throw new Failure("invalid_json");
    }

    private static final class Request {
        final String operation;final JsonObject arguments;final CompletableFuture<JsonElement> result=new CompletableFuture<JsonElement>();
        Request(String operation,JsonObject arguments){this.operation=operation;this.arguments=arguments;}
    }
    private static final class CachedSnapshot {
        final String json;final long captured;
        CachedSnapshot(String json,long captured){this.json=json;this.captured=captured;}
    }
    private static final class Failure extends RuntimeException {
        final String code;Failure(String code){super(code);this.code=code;}
    }
}
