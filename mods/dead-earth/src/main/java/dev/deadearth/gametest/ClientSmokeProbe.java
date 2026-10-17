package dev.deadearth.gametest;

import dev.deadearth.DeadEarth;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.TooltipFlag;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;

/** Explicitly enabled development smoke run; never active in ordinary gameplay. */
public final class ClientSmokeProbe {
    private final long openedAt=System.nanoTime();
    private int joinedTicks;
    private boolean started, finished;
    private int finishTicks;
    private final int holdTicks = Math.clamp(Integer.getInteger("deadearth.clientSmokeHoldTicks", 160), 20, 2400);
    private ClientSmokeProbe() {}
    public static void install() {
        if (Boolean.getBoolean("deadearth.clientSmoke")||Boolean.getBoolean("deadearth.defenseClientSmoke")) {
            var probe = new ClientSmokeProbe();
            NeoForge.EVENT_BUS.addListener(probe::tick);
        }
    }
    private static boolean captureAllowed(){return !Boolean.getBoolean("deadearth.clientNoCapture")&&!Boolean.getBoolean("deadearth.defenseClientSmoke");}
    /** Runs on the client thread after the native model manager and item overrides are ready. */
    private static boolean defenseModels(Minecraft client){
        var report=new java.util.LinkedHashMap<String,Object>();
        var missingItems=new java.util.ArrayList<String>();var missingStates=new java.util.ArrayList<String>();
        var missingParticles=new java.util.ArrayList<String>();var statesByBlock=new java.util.TreeMap<String,Integer>();
        int items=0,states=0;String failure=null;
        try{
            var missing=client.getModelManager().getMissingModel();
            var shaper=client.getBlockRenderer().getBlockModelShaper();
            var missingTexture=net.minecraft.client.renderer.texture.MissingTextureAtlasSprite.getLocation();
            for(var holder:dev.deadearth.content.DefenseContent.ALL){
                var block=holder.get();String id=net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(block).toString();
                var model=client.getItemRenderer().getModel(new ItemStack(block),client.level,client.player,0);items++;
                if(model==null||model==missing)missingItems.add(id);
                else if(model.getParticleIcon(net.neoforged.neoforge.client.model.data.ModelData.EMPTY).contents().name().equals(missingTexture))missingParticles.add("item:"+id);
                var possible=block.getStateDefinition().getPossibleStates();statesByBlock.put(id,possible.size());
                for(var state:possible){
                    states++;var baked=shaper.getBlockModel(state);
                    if(baked==null||baked==missing)missingStates.add(state.toString());
                    else if(baked.getParticleIcon(net.neoforged.neoforge.client.model.data.ModelData.EMPTY).contents().name().equals(missingTexture))missingParticles.add(state.toString());
                }
            }
        }catch(RuntimeException|LinkageError error){failure=error.toString();}
        boolean ready=failure==null&&items==9&&statesByBlock.size()==9&&states>0&&missingItems.isEmpty()&&missingStates.isEmpty()&&missingParticles.isEmpty();
        report.put("ready",ready);report.put("pid",ProcessHandle.current().pid());report.put("itemCount",items);report.put("blockStateCount",states);
        report.put("statesByBlock",statesByBlock);report.put("missingItems",missingItems);report.put("missingStates",missingStates);
        report.put("missingParticles",missingParticles);report.put("error",failure);report.put("screenshots",false);
        String json=new com.google.gson.GsonBuilder().serializeNulls().create().toJson(report);
        DeadEarth.LOGGER.info("DEAD_EARTH_DEFENSE_CLIENT_SMOKE {}",json);
        try{java.nio.file.Files.writeString(client.gameDirectory.toPath().resolve("client-defense-result.json"),json);}
        catch(java.io.IOException error){DeadEarth.LOGGER.error("Defense client marker failed",error);return false;}
        return ready;
    }
    private void tick(ClientTickEvent.Post event) {
        var client = Minecraft.getInstance();
        if(System.nanoTime()-openedAt>240_000_000_000L) {
            DeadEarth.LOGGER.error("Test client exceeded startup bound");client.stop();return;
        }
        if(Boolean.getBoolean("deadearth.seasonScreenshot")||Boolean.getBoolean("deadearth.cinematicScreenshot")) client.options.hideGui=true;
        if (client.player == null || client.level == null) return;
        if (finished) {
            if(Boolean.getBoolean("deadearth.seasonScreenshot")&&finishTicks%200==199) {
                DeadEarth.LOGGER.info("DEAD_EARTH_VISUAL_SMOKE fps={}",client.getFps());
                if(captureAllowed())Screenshot.grab(client.gameDirectory,client.getMainRenderTarget(),message->DeadEarth.LOGGER.info("Smoke screenshot: {}",message.getString()));
            }
            if (++finishTicks == holdTicks) {
                DeadEarth.LOGGER.info("DEAD_EARTH_CLIENT_SMOKE completed; shutting down owned test client");
                client.stop();
            }
            return;
        }
        if (started || ++joinedTicks < 300) return;
        started = true;
        var player = client.player;
        var context = Item.TooltipContext.of(client.level);
        CompletableFuture.runAsync(() -> {
            var thread = Thread.currentThread(); var previous = thread.getContextClassLoader();
            try {
                thread.setContextClassLoader(ClassLoader.getPlatformClassLoader());
                for (var item : new ItemStack[] {new ItemStack(Items.IRON_INGOT), new ItemStack(Items.DIAMOND), new ItemStack(Items.STONE),new ItemStack(dev.deadearth.content.ZoneLoaderContent.ITEM.get())})
                    if (item.getTooltipLines(context, player, TooltipFlag.Default.NORMAL).isEmpty()) throw new IllegalStateException("Empty tooltip");
            } finally { thread.setContextClassLoader(previous); }
        }).whenComplete((unused, error) -> client.execute(() -> {
            if (error != null) {
                DeadEarth.LOGGER.error("DEAD_EARTH_CLIENT_SMOKE failed", error);
                client.stop();
            } else {
                var loaderStack=new ItemStack(dev.deadearth.content.ZoneLoaderContent.ITEM.get());
                if(client.getItemRenderer().getModel(loaderStack,client.level,player,0)==client.getModelManager().getMissingModel()) {
                    DeadEarth.LOGGER.error("DEAD_EARTH_ZONE_CLIENT missing loader model");client.stop();return;
                }
                if(Boolean.getBoolean("deadearth.defenseClientSmoke")&&!defenseModels(client)){
                    DeadEarth.LOGGER.error("DEAD_EARTH_DEFENSE_CLIENT_SMOKE failed; shutting down owned test client");client.stop();return;
                }
                DeadEarth.LOGGER.info("DEAD_EARTH_CLIENT_SMOKE worker tooltips passed");
                DeadEarth.LOGGER.info("DEAD_EARTH_ZONE_CLIENT loaderName={} coefficient={}",new ItemStack(dev.deadearth.content.ZoneLoaderContent.ITEM.get()).getHoverName().getString(),dev.deadearth.config.DeadEarthConfig.d(dev.deadearth.config.DeadEarthConfig.ZONE_EMC));
                DeadEarth.LOGGER.info("DEAD_EARTH_CONFIG_SMOKE phaseMinutes={} maxAbove={} healthInfluence={}",
                    dev.deadearth.config.DeadEarthConfig.i(dev.deadearth.config.DeadEarthConfig.PHASE_MINUTES),
                    dev.deadearth.config.DeadEarthConfig.d(dev.deadearth.config.DeadEarthConfig.ADAPTIVE_ABOVE),
                    dev.deadearth.config.DeadEarthConfig.d(dev.deadearth.config.DeadEarthConfig.HEALTH_INFLUENCE));
                if(captureAllowed())Screenshot.grab(client.gameDirectory, client.getMainRenderTarget(), message -> DeadEarth.LOGGER.info("Smoke screenshot: {}", message.getString()));
                if(Boolean.getBoolean("deadearth.clientShaderSmoke")) {
                    try {
                        var api=Class.forName("net.irisshaders.iris.api.v0.IrisApi");
                        var instance=api.getMethod("getInstance").invoke(null);
                        boolean shader=(boolean)api.getMethod("isShaderPackInUse").invoke(instance);
                        var iris=Class.forName("net.irisshaders.iris.Iris");
                        boolean fallback=(boolean)iris.getMethod("isFallback").invoke(null);
                        boolean shaderError=((java.util.Optional<?>)iris.getMethod("getStoredError").invoke(null)).isPresent();
                        var manager=iris.getMethod("getPipelineManager").invoke(null);
                        var pipeline=manager.getClass().getMethod("getPipelineNullable").invoke(manager);
                        String pipelineName=pipeline==null?"none":pipeline.getClass().getName();
                        var required=java.util.List.of("sodium","iris","jade","inventorysorter","blockoutlinecustomizer","ferritecore","modernfix");
                        var present=new java.util.TreeMap<String,Boolean>();
                        for(var id:required)present.put(id,net.neoforged.fml.ModList.get().isLoaded(id));
                        int glError=org.lwjgl.opengl.GL11.glGetError();
                        boolean ready=shader&&!fallback&&!shaderError&&pipelineName.endsWith(".IrisRenderingPipeline")&&present.values().stream().allMatch(Boolean::booleanValue)&&glError==0;
                        var report=java.util.Map.of("ready",ready,"shaderPackInUse",shader,"mods",present,"glError",glError,"fps",client.getFps(),"screenshots",false,"fallback",fallback,"shaderError",shaderError,"pipeline",pipelineName);
                        java.nio.file.Files.writeString(client.gameDirectory.toPath().resolve("client-shader-result.json"),new com.google.gson.Gson().toJson(report));
                        DeadEarth.LOGGER.info("DEAD_EARTH_SHADER_SMOKE {}",new com.google.gson.Gson().toJson(report));
                        if(!ready){client.stop();return;}
                    }catch(Exception invalid){DeadEarth.LOGGER.error("DEAD_EARTH_SHADER_SMOKE failed",invalid);client.stop();return;}
                }
                if(Boolean.getBoolean("deadearth.cinematicScreenshot")) {
                    int traces=0;
                    for(int x=-8;x<25;x++)for(int z=-8;z<25;z++)if(client.level.getBlockEntity(new net.minecraft.core.BlockPos(x+4096,71,z+4096)) instanceof dev.deadearth.content.TraceContent.TraceEntity)traces++;
                    DeadEarth.LOGGER.info("DEAD_EARTH_CINEMATIC_CLIENT traces={} fps={}",traces,client.getFps());
                    try {java.nio.file.Files.writeString(client.gameDirectory.toPath().resolve("client-cinematic-result.json"),
                        new com.google.gson.Gson().toJson(java.util.Map.of("traces",traces,"fps",client.getFps(),"ready",traces>=6)));}
                    catch(java.io.IOException ioFailure){DeadEarth.LOGGER.error("Cinematic client marker failed",ioFailure);}
                }
                finished = true;
            }
        }));
    }
}
