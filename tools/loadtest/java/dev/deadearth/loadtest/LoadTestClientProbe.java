package dev.deadearth.loadtest;

import com.google.gson.*;
import dev.deadearth.DeadEarth;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import org.lwjgl.glfw.GLFW;

/** Opt-in instrumentation, compiled only by the separate deadEarthLoadTest source set. */
@EventBusSubscriber(modid="dead_earth",value=Dist.CLIENT)
public final class LoadTestClientProbe {
    private static final Gson JSON=new GsonBuilder().serializeNulls().create();
    private static final long SECOND=1_000_000_000L,SETTLE=3*SECOND;
    private static final Pattern ENTITIES=Pattern.compile("^E: (\\d+)/");
    private static boolean resolved;
    private static Probe probe;
    private LoadTestClientProbe() {}

    private static Probe probe() {
        if(resolved)return probe;resolved=true;
        String value=System.getProperty("deadearth.loadTestOutput");if(value==null||value.isBlank())return null;
        try {
            var path=Path.of(value);
            if(!path.isAbsolute()||Files.isSymbolicLink(path)||!Files.isDirectory(path,LinkOption.NOFOLLOW_LINKS))
                throw new IOException("loadTestOutput must be an existing absolute test directory");
            probe=new Probe(path.normalize());
        }catch(IOException|RuntimeException error){DeadEarth.LOGGER.error("Load-test probe output directory rejected",error);}
        return probe;
    }
    @SubscribeEvent public static void frame(RenderFrameEvent.Post event) {
        var current=probe();if(current!=null&&!current.finished)current.frame(Minecraft.getInstance());
    }
    @SubscribeEvent public static void tick(ClientTickEvent.Post event) {
        var current=probe();if(current!=null&&!current.finished)current.tick(Minecraft.getInstance());
    }

    private record ShaderState(boolean available,Boolean configuredEnabled,Boolean enabled,Boolean fallback,Boolean error,
            String pipeline,String pack,String nativeError,String probeError) {
        String signature(){return JSON.toJson(this);}
    }
    private static ShaderState shaderState() {
        try {
            var api=Class.forName("net.irisshaders.iris.api.v0.IrisApi");var instance=api.getMethod("getInstance").invoke(null);
            var config=api.getMethod("getConfig").invoke(instance);var configApi=Class.forName("net.irisshaders.iris.api.v0.IrisApiConfig");
            boolean configured=(boolean)configApi.getMethod("areShadersEnabled").invoke(config);
            boolean enabled=(boolean)api.getMethod("isShaderPackInUse").invoke(instance);
            var iris=Class.forName("net.irisshaders.iris.Iris");boolean fallback=(boolean)iris.getMethod("isFallback").invoke(null);
            var error=(Optional<?>)iris.getMethod("getStoredError").invoke(null);var manager=iris.getMethod("getPipelineManager").invoke(null);
            var pipeline=manager.getClass().getMethod("getPipelineNullable").invoke(manager);
            return new ShaderState(true,configured,enabled,fallback,error.isPresent(),pipeline==null?"none":pipeline.getClass().getName(),
                (String)iris.getMethod("getCurrentPackName").invoke(null),error.isPresent()?error.get().getClass().getName():null,null);
        }catch(ReflectiveOperationException|RuntimeException|LinkageError error) {
            return new ShaderState(false,null,null,null,null,null,null,null,error.getClass().getName());
        }
    }
    private static void applyShader(boolean enabled)throws ReflectiveOperationException {
        var api=Class.forName("net.irisshaders.iris.api.v0.IrisApi");var instance=api.getMethod("getInstance").invoke(null);
        var config=api.getMethod("getConfig").invoke(instance);
        Class.forName("net.irisshaders.iris.api.v0.IrisApiConfig").getMethod("setShadersEnabledAndApply",boolean.class).invoke(config,enabled);
    }

    private static final class Probe {
        private final Path directory;
        private final String session=UUID.randomUUID().toString();
        private final long pid=ProcessHandle.current().pid();
        private final Map<String,String> controlErrors=new HashMap<>();
        private BufferedWriter writer;
        private boolean finished,initialized,wasJoined,lastFrameEligible;
        private String phase="STARTUP",shaderCommand,lastShaderSignature;
        private long nextPoll,settleUntil,lastFrame,windowStart=System.nanoTime(),windowStartMs=System.currentTimeMillis();
        private long eligibleFrames,excludedFrames,droppedSamples,unfocusedFrames,iconifiedFrames;
        private long[] intervals=new long[1024];
        private int intervalCount;

        Probe(Path directory) {
            this.directory=directory;
            try {writer=Files.newBufferedWriter(directory.resolve("client-metrics.jsonl"),StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,StandardOpenOption.APPEND,LinkOption.NOFOLLOW_LINKS);}
            catch(IOException|RuntimeException error){DeadEarth.LOGGER.error("Load-test metrics unavailable; FINISH control remains active",error);}
        }
        private JsonObject row(String type,long nanos) {
            var row=new JsonObject();row.addProperty("schema",1);row.addProperty("type",type);row.addProperty("sessionId",session);
            row.addProperty("pid",pid);row.addProperty("phase",phase);row.addProperty("timestampMs",System.currentTimeMillis());row.addProperty("nanoTime",nanos);return row;
        }
        private void write(JsonObject row) {
            if(writer==null)return;
            try {writer.write(JSON.toJson(row));writer.newLine();writer.flush();}
            catch(IOException|RuntimeException error) {
                DeadEarth.LOGGER.error("Load-test metrics write failed; FINISH control remains active",error);close();
            }
        }
        private void close() {
            var old=writer;writer=null;if(old!=null)try{old.close();}catch(IOException ignored){}
        }
        private String control(String name) {
            Path path=directory.resolve(name);
            try {
                if(!Files.exists(path,LinkOption.NOFOLLOW_LINKS))return null;
                if(!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)||Files.isSymbolicLink(path)||Files.size(path)>256)
                    throw new IOException("Control must be a bounded regular file");
                byte[] bytes;try(var input=Files.newInputStream(path,LinkOption.NOFOLLOW_LINKS)){bytes=input.readNBytes(257);}
                if(bytes.length>256)throw new IOException("Control exceeds size limit");
                String value=StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString().strip();
                if(value.chars().anyMatch(Character::isISOControl))throw new IOException("Control contains embedded control characters");
                controlErrors.remove(name);return value.isEmpty()?null:value;
            }catch(IOException|RuntimeException error) {
                String code=error.getClass().getName();
                if(!code.equals(controlErrors.put(name,code))) {
                    var row=row("control_error",System.nanoTime());row.addProperty("file",name);row.addProperty("error",code);write(row);
                }return null;
            }
        }
        private void settle(long now){settleUntil=now+SETTLE;lastFrameEligible=false;}
        private static boolean joined(Minecraft client){return client.player!=null&&client.level!=null;}
        private void initialize(Minecraft client) {
            client.options.enableVsync().set(false);client.options.framerateLimit().set(260);
            // Apply even when OptionInstance already holds this value and suppresses its change callback.
            client.getWindow().updateVsync(false);client.getWindow().setFramerateLimit(260);
            // Native option checked by GameRenderer before automatically opening the pause menu in the background.
            client.options.pauseOnLostFocus=false;
            client.options.renderDistance().set(8);client.options.simulationDistance().set(8);client.options.broadcastOptions();
            initialized=true;long now=System.nanoTime();settle(now);
            var row=row("settings",now);row.addProperty("vsync",client.options.enableVsync().get());
            row.addProperty("fpsLimit",client.options.framerateLimit().get());row.addProperty("renderDistance",client.options.renderDistance().get());
            row.addProperty("simulationDistance",client.options.simulationDistance().get());row.addProperty("settleMillis",SETTLE/1_000_000);
            row.addProperty("pauseOnLostFocus",client.options.pauseOnLostFocus);
            row.addProperty("windowWidth",client.getWindow().getWidth());row.addProperty("windowHeight",client.getWindow().getHeight());
            row.addProperty("screenWidth",client.getWindow().getScreenWidth());row.addProperty("screenHeight",client.getWindow().getScreenHeight());write(row);
        }
        void tick(Minecraft client) {
            boolean joined=joined(client);if(joined&&!wasJoined)initialize(client);wasJoined=joined;
            long now=System.nanoTime();if(now<nextPoll)return;
            if(nextPoll!=0)window(client,now);
            String nextPhase=control("phase.txt");
            if(nextPhase!=null&&!nextPhase.equals(phase)) {
                String previous=phase;phase=nextPhase;lastFrameEligible=false;
                var row=row("transition",System.nanoTime());row.addProperty("kind","phase");row.addProperty("previousPhase",previous);write(row);
            }
            if(phase.equals("FINISH")) {
                var row=row("finish",System.nanoTime());row.add("shader",JSON.toJsonTree(shaderState()));write(row);finished=true;close();
                // This branch exists only in the process explicitly given loadTestOutput.
                client.stop();return;
            }
            String requested=control("shader.txt");
            if(requested!=null&&!requested.equalsIgnoreCase("on")&&!requested.equalsIgnoreCase("off")) {
                if(!requested.equals(controlErrors.put("shader_value",requested))) {
                    var row=row("control_error",System.nanoTime());row.addProperty("file","shader.txt");row.addProperty("error","expected_on_or_off");write(row);
                }
            }else if(requested!=null&&joined&&initialized&&!requested.equalsIgnoreCase(shaderCommand))switchShader(requested.toLowerCase(Locale.ROOT));
            windowStart=System.nanoTime();windowStartMs=System.currentTimeMillis();nextPoll=windowStart+SECOND;
            eligibleFrames=excludedFrames=droppedSamples=unfocusedFrames=iconifiedFrames=0;intervalCount=0;
        }
        private void switchShader(String requested) {
            shaderCommand=requested;boolean wanted=requested.equals("on");var before=shaderState();long started=System.nanoTime();
            boolean needsReload=!Objects.equals(before.configuredEnabled(),wanted)||!Objects.equals(before.enabled(),wanted);
            var start=row("transition",started);start.addProperty("kind","shader_start");start.addProperty("requested",requested);start.add("shader",JSON.toJsonTree(before));write(start);
            String failure=null;lastFrameEligible=false;
            if(needsReload)try{applyShader(wanted);}catch(ReflectiveOperationException|RuntimeException|LinkageError error){failure=error.getClass().getName();}
            long ended=System.nanoTime();settle(ended);var actual=shaderState();lastShaderSignature=actual.signature();
            var end=row("transition",ended);end.addProperty("kind","shader_complete");end.addProperty("requested",requested);end.addProperty("reloadAttempted",needsReload);
            end.addProperty("durationMs",(ended-started)/1_000_000d);end.addProperty("settleMillis",SETTLE/1_000_000);end.addProperty("probeError",failure);
            end.addProperty("actualMatchesRequest",actual.available()&&Objects.equals(actual.configuredEnabled(),wanted)&&Objects.equals(actual.enabled(),wanted)
                &&Boolean.FALSE.equals(actual.fallback())&&Boolean.FALSE.equals(actual.error()));end.add("shader",JSON.toJsonTree(actual));write(end);
        }
        void frame(Minecraft client) {
            long now=System.nanoTime();boolean eligible=initialized&&joined(client)&&now>=settleUntil;
            long window=client.getWindow().getWindow();
            if(GLFW.glfwGetWindowAttrib(window,GLFW.GLFW_FOCUSED)!=GLFW.GLFW_TRUE)unfocusedFrames++;
            if(GLFW.glfwGetWindowAttrib(window,GLFW.GLFW_ICONIFIED)==GLFW.GLFW_TRUE)iconifiedFrames++;
            if(eligible) {
                eligibleFrames++;
                if(lastFrameEligible&&lastFrame!=0&&now>lastFrame) {
                    if(intervalCount==intervals.length&&intervals.length<65536)intervals=Arrays.copyOf(intervals,Math.min(65536,intervals.length*2));
                    if(intervalCount<intervals.length)intervals[intervalCount++]=now-lastFrame;else droppedSamples++;
                }
            }else excludedFrames++;
            lastFrame=now;lastFrameEligible=eligible;
        }
        private void window(Minecraft client,long now) {
            var shader=shaderState();String signature=shader.signature();boolean nativeTransition=lastShaderSignature!=null&&!lastShaderSignature.equals(signature);
            if(nativeTransition) {
                var transition=row("transition",now);transition.addProperty("kind","native_shader_state");transition.add("shader",JSON.toJsonTree(shader));write(transition);
                // Unknown transition time: discard this entire mixed window, then settle the new pipeline.
                excludedFrames+=eligibleFrames;eligibleFrames=0;intervalCount=0;settle(now);
            }
            lastShaderSignature=signature;var row=row("window",now);row.addProperty("windowStartMs",windowStartMs);row.addProperty("windowStartNanos",windowStart);
            row.addProperty("windowEndNanos",now);row.addProperty("windowMillis",(now-windowStart)/1_000_000d);row.addProperty("joined",joined(client));
            row.addProperty("measurementEligible",now>windowStart&&initialized&&joined(client)&&excludedFrames==0&&intervalCount>0&&droppedSamples==0&&!nativeTransition);
            row.addProperty("frameCount",eligibleFrames);row.addProperty("excludedFrameCount",excludedFrames);row.addProperty("droppedSamples",droppedSamples);
            row.addProperty("settleRemainingMs",Math.max(0,settleUntil-now)/1_000_000d);row.add("frameIntervalsMs",summary(intervals,intervalCount));
            row.add("frameIntervalsNanos",JSON.toJsonTree(Arrays.copyOf(intervals,intervalCount)));
            row.addProperty("nativeFPS",client.getFps());row.addProperty("nativeFrameTimeNs",client.getFrameTimeNs());
            var memory=Runtime.getRuntime();row.addProperty("heapUsed",memory.totalMemory()-memory.freeMemory());row.addProperty("heapMax",memory.maxMemory());
            row.addProperty("vsync",client.options.enableVsync().get());row.addProperty("fpsLimit",client.options.framerateLimit().get());
            row.addProperty("pauseOnLostFocus",client.options.pauseOnLostFocus);
            row.addProperty("renderDistance",client.options.renderDistance().get());row.addProperty("simulationDistance",client.options.simulationDistance().get());
            row.addProperty("framebufferWidth",client.getWindow().getWidth());row.addProperty("framebufferHeight",client.getWindow().getHeight());
            row.addProperty("windowWidth",client.getWindow().getWidth());row.addProperty("windowHeight",client.getWindow().getHeight());
            row.addProperty("screenWidth",client.getWindow().getScreenWidth());row.addProperty("screenHeight",client.getWindow().getScreenHeight());
            long window=client.getWindow().getWindow();
            row.addProperty("windowFocused",GLFW.glfwGetWindowAttrib(window,GLFW.GLFW_FOCUSED)==GLFW.GLFW_TRUE);
            row.addProperty("windowIconified",GLFW.glfwGetWindowAttrib(window,GLFW.GLFW_ICONIFIED)==GLFW.GLFW_TRUE);
            row.addProperty("unfocusedFrameCount",unfocusedFrames);row.addProperty("iconifiedFrameCount",iconifiedFrames);
            row.addProperty("requestedShader",shaderCommand);row.add("shader",JSON.toJsonTree(shader));
            if(joined(client)) {
                var camera=client.gameRenderer.getMainCamera();var position=camera.getPosition();
                var pose=new JsonObject();pose.addProperty("x",position.x);pose.addProperty("y",position.y);pose.addProperty("z",position.z);
                pose.addProperty("yaw",camera.getYRot());pose.addProperty("pitch",camera.getXRot());row.add("camera",pose);
                row.addProperty("serverSimulationDistance",client.level.getServerSimulationDistance());row.addProperty("clientEntities",client.level.getEntityCount());
                String statistics=client.levelRenderer.getEntityStatistics();row.addProperty("entityStatistics",statistics);var match=ENTITIES.matcher(statistics);
                if(match.find())try{row.addProperty("entitiesRendered",Integer.parseInt(match.group(1)));}catch(NumberFormatException ignored){}
            }write(row);
        }
    }
    /** Nearest-rank percentiles of completed eligible render-frame intervals, in milliseconds. */
    private static JsonObject summary(long[] intervals,int count) {
        var row=new JsonObject();row.addProperty("count",count);long[] sorted=Arrays.copyOf(intervals,count);Arrays.sort(sorted);double sum=0;
        for(long interval:sorted)sum+=interval/1_000_000d;row.addProperty("sum",sum);
        for(var percentile:Map.of("p50",.50,"p95",.95,"p99",.99).entrySet())
            if(count==0)row.add(percentile.getKey(),JsonNull.INSTANCE);else row.addProperty(percentile.getKey(),sorted[(int)Math.ceil(percentile.getValue()*count)-1]/1_000_000d);
        if(count==0){row.add("max",JsonNull.INSTANCE);row.add("mean",JsonNull.INSTANCE);row.add("derivedFPS",JsonNull.INSTANCE);}
        else {row.addProperty("max",sorted[count-1]/1_000_000d);row.addProperty("mean",sum/count);row.addProperty("derivedFPS",count*1000d/sum);}
        return row;
    }
}
