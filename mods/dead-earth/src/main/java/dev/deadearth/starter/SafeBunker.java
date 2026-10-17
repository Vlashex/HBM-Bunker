package dev.deadearth.starter;

import static dev.deadearth.config.DeadEarthConfig.*;
import dev.deadearth.CoordinatorRuntime;
import dev.deadearth.DeadEarth;
import dev.deadearth.campaign.CampaignStore;
import dev.deadearth.campaign.SavedDataAccess;
import dev.deadearth.compat.BiosphereEvents;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.AbortableIterationConsumer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.EntityMobGriefingEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.living.MobSpawnEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.ExplosionEvent;
import net.neoforged.neoforge.event.level.PistonEvent;

/** Server hooks only. Never loads chunks, recreates a bunker, restores blocks, or generates loot. */
public final class SafeBunker {
    private static final Map<MinecraftServer,State> STATES=new ConcurrentHashMap<>();
    private static final java.util.Set<MinecraftServer> FAILED=ConcurrentHashMap.newKeySet();
    private static final ThreadLocal<Capture> CAPTURE=new ThreadLocal<>();
    private static boolean installed;
    private static final class State {
        final CampaignStore campaign;final SafeBunkerStore store;
        volatile long activeTicks;
        volatile boolean protecting,protectAll;
        SafeBunkerNative.Recovery recovery;boolean migrationFailed;int retryTicks,boxCursor;
        State(CampaignStore campaign,SafeBunkerStore store){this.campaign=campaign;this.store=store;}
    }
    private static final class Capture {
        final ServerLevel level;final List<SafeBunkerStore.Box> boxes=new ArrayList<>();
        final Map<Long,ResourceLocation> writes=new LinkedHashMap<>();
        int depth=1;boolean failed;
        Capture(ServerLevel level){this.level=level;}
    }
    private SafeBunker() {}
    public static void install() {
        if(installed)return;installed=true;
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST,SafeBunker::broken);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST,SafeBunker::explosion);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST,SafeBunker::piston);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST,SafeBunker::joined);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST,SafeBunker::griefing);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST,SafeBunker::spawn);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST,SafeBunker::damage);
    }
    private static State open(MinecraftServer server) {
        return STATES.computeIfAbsent(server,s->{
            var level=s.overworld();var campaign=SavedDataAccess.open(level,CampaignStore.NAME,new SavedData.Factory<>(CampaignStore::create,CampaignStore::load));
            if(!campaign.enabled())throw new IllegalStateException("Cannot protect bunker with unsupported campaign");
            var store=SavedDataAccess.open(level,SafeBunkerStore.NAME,new SavedData.Factory<>(
                ()->SafeBunkerStore.create(campaign.campaign().campaignId(),(long)i(BUNKER_PROTECTION_MINUTES)*1200),SafeBunkerStore::load));
            if(!store.enabled())DeadEarth.LOGGER.error("Safe bunker disabled: unsupported snapshot preserved");
            return new State(campaign,store);
        });
    }
    private static void publish(State state) {
        state.activeTicks=state.campaign.campaign().activeTicks();
        state.protectAll=b(BUNKER_PROTECT_ALL);
        state.protecting=b(BUNKER_PROTECTION_ENABLED)&&state.store.active(state.campaign.campaign().campaignId(),state.activeTicks);
    }
    private static boolean active(State state){return state!=null&&state.protecting;}
    private static State state(Level level) {
        return level instanceof ServerLevel server&&server==server.getServer().overworld()?STATES.get(server.getServer()):null;
    }
    public static void tick(MinecraftServer server,CoordinatorRuntime runtime) {
        if(!server.isSameThread())throw new IllegalStateException("Safe bunker tick outside server thread");
        if(!runtime.campaign().enabled()||FAILED.contains(server))return;
        State state;
        try{state=open(server);}catch(RuntimeException invalid){FAILED.add(server);DeadEarth.LOGGER.error("Safe bunker unavailable; saves preserved",invalid);return;}
        publish(state);
        if(!state.store.enabled()||!b(BUNKER_PROTECTION_ENABLED)||state.campaign.campaign().activeTicks()>=state.store.deadline())return;
        if(!state.store.recorded()&&!state.migrationFailed) {
            try {
                if(state.recovery==null&&state.retryTicks--<=0){state.retryTicks=200;state.recovery=SafeBunkerNative.recover(server.overworld());}
                if(state.recovery!=null&&state.recovery.advance(server.overworld(),i(BUNKER_SCAN_BUDGET))) {
                    state.store.record(state.recovery.boxes(),state.recovery.originals(),"native_migration");
                    SavedDataAccess.checkpoint(server.overworld(),SafeBunkerStore.NAME,state.store);state.recovery=null;
                    publish(state);
                    DeadEarth.LOGGER.info("Recovered safe bunker from native OutpostZero identity and verified templates");
                }
            }catch(Exception invalid){state.migrationFailed=true;DeadEarth.LOGGER.error("Safe bunker migration stopped; native data unchanged",invalid);}
        }
        if(active(state)&&!state.store.boxes().isEmpty()) {
            // One real room/shaft segment per tick; iteration aborts after a bounded number of entities.
            var box=state.store.boxes().get(state.boxCursor++%state.store.boxes().size()).aabb();
            int[] inspected={0};int budget=Math.min(256,i(BUNKER_SCAN_BUDGET));
            server.overworld().getEntities().get(EntityTypeTest.forClass(LivingEntity.class),box,mob->{
                if(hostile(mob))mob.discard();
                return ++inspected[0]>=budget?AbortableIterationConsumer.Continuation.ABORT:AbortableIterationConsumer.Continuation.CONTINUE;
            });
        }
    }
    public static void close(MinecraftServer server){var state=STATES.remove(server);if(state!=null)state.protecting=false;FAILED.remove(server);var capture=CAPTURE.get();if(capture!=null&&capture.level.getServer()==server)CAPTURE.remove();}

    /** Scope is the actual StarterBunkerLogic call, not the effective content kind or a loot table. */
    public static void beginCapture(ServerLevel level) {
        if(level!=level.getServer().overworld())return;
        var current=CAPTURE.get();if(current!=null){current.depth++;return;}CAPTURE.set(new Capture(level));
    }
    public static void endCapture(ServerLevel level,boolean success) {
        var capture=CAPTURE.get();if(capture==null||capture.level!=level)return;
        if(!success)capture.failed=true;if(--capture.depth>0)return;CAPTURE.remove();
        if(capture.failed){DeadEarth.LOGGER.warn("Native starter capture exceeded limits or placement failed; no partial safe zone was created");return;}
        if(capture.boxes.isEmpty())return;
        try {
            var state=open(level.getServer());if(!state.store.enabled()||state.store.recorded())return;
            capture.writes.entrySet().removeIf(e->{var p=BlockPos.of(e.getKey());return e.getValue().equals(BuiltInRegistries.BLOCK.getKey(Blocks.AIR))
                ||capture.boxes.stream().noneMatch(box->box.contains(p.getX(),p.getY(),p.getZ()));});
            state.store.record(capture.boxes,capture.writes,"native_capture");
            SavedDataAccess.checkpoint(level,SafeBunkerStore.NAME,state.store);
            publish(state);
        }catch(RuntimeException invalid){DeadEarth.LOGGER.error("Native starter capture unavailable; existing bunker saves preserved",invalid);}
    }
    public static void captureTemplate(StructureTemplate template,ServerLevelAccessor accessor,BlockPos origin,StructurePlaceSettings settings) {
        var capture=CAPTURE.get();if(capture==null||accessor.getLevel()!=capture.level||capture.failed)return;
        var bb=template.getBoundingBox(settings,origin);
        var box=new SafeBunkerStore.Box(bb.minX(),bb.minY(),bb.minZ(),bb.maxX(),bb.maxY(),bb.maxZ());
        if(!capture.boxes.contains(box))capture.boxes.add(box);
        if(capture.boxes.size()>SafeBunkerStore.MAX_BOXES||capture.boxes.stream().mapToLong(SafeBunkerStore.Box::volume).sum()>SafeBunkerStore.MAX_VOLUME)capture.failed=true;
    }
    public static void captureWrite(Level level,BlockPos pos,BlockState next) {
        var capture=CAPTURE.get();if(capture==null||capture.level!=level||capture.failed)return;
        if(capture.boxes.stream().noneMatch(box->box.contains(pos.getX(),pos.getY(),pos.getZ())))return;
        if(capture.writes.size()>=SafeBunkerStore.MAX_BLOCKS&&!capture.writes.containsKey(pos.asLong())){capture.failed=true;return;}
        capture.writes.put(pos.asLong(),BuiltInRegistries.BLOCK.getKey(next.getBlock()));
    }
    public static boolean blocksReplacement(Level level,BlockPos pos,BlockState current,BlockState next) {
        var capture=CAPTURE.get();if(capture!=null&&capture.level==level)return false;
        var state=state(level);return active(state)&&state.store.blocksReplacement(pos,BuiltInRegistries.BLOCK.getKey(current.getBlock()),BuiltInRegistries.BLOCK.getKey(next.getBlock()),state.protectAll);
    }
    /** Cached snapshot only: safe for HBM worker threads and does not read/load any chunk. */
    public static boolean intersectsSection(Level level,int chunkX,int chunkZ,int sectionIndex) {
        var state=state(level);
        if(!active(state)||sectionIndex<0||sectionIndex>=level.getSectionsCount())return false;
        int x=chunkX<<4,z=chunkZ<<4,y=level.getSectionYFromSectionIndex(sectionIndex)<<4;
        for(var box:state.store.boxes())if(box.minX()<=x+15&&box.maxX()>=x&&box.minY()<=y+15&&box.maxY()>=y&&box.minZ()<=z+15&&box.maxZ()>=z)return true;
        return false;
    }
    public static boolean protectsRemoval(Level level,BlockPos pos) {
        var state=state(level);return active(state)&&level.hasChunkAt(pos)&&blocksReplacement(level,pos,level.getBlockState(pos),Blocks.AIR.defaultBlockState());
    }
    public static boolean hostile(Entity entity) {
        if(!(entity instanceof LivingEntity)||entity instanceof net.minecraft.world.entity.player.Player||entity instanceof TamableAnimal pet&&pet.isTame())return false;
        return entity instanceof Monster||entity.getType().getCategory()==MobCategory.MONSTER||BiosphereEvents.mutant(entity);
    }
    public static boolean blocksPosition(Entity entity,double x,double y,double z) {
        if(!entity.isAddedToLevel()||!hostile(entity))return false;
        var state=state(entity.level());return active(state)&&state.store.blocksTravel(entity.getBoundingBox(),new Vec3(x-entity.getX(),y-entity.getY(),z-entity.getZ()));
    }
    private static boolean inside(Entity entity){var state=state(entity.level());return active(state)&&state.store.intersects(entity.getBoundingBox());}
    private static void broken(BlockEvent.BreakEvent event){if(event.getLevel() instanceof Level level&&protectsRemoval(level,event.getPos()))event.setCanceled(true);}
    private static void explosion(ExplosionEvent.Detonate event){event.getAffectedBlocks().removeIf(pos->protectsRemoval(event.getLevel(),pos));}
    private static void piston(PistonEvent.Pre event) {
        if(!(event.getLevel() instanceof Level level))return;var helper=event.getStructureHelper();if(helper==null||!helper.resolve())return;
        var movement=event.getPistonMoveType().isExtend?event.getDirection():event.getDirection().getOpposite();
        for(var pos:helper.getToPush())if(protectsRemoval(level,pos)||protectsRemoval(level,pos.relative(movement))){event.setCanceled(true);return;}
        for(var pos:helper.getToDestroy())if(protectsRemoval(level,pos)){event.setCanceled(true);return;}
    }
    private static void joined(EntityJoinLevelEvent event) {
        if(event.getEntity() instanceof FallingBlockEntity falling&&protectsRemoval(event.getLevel(),falling.getStartPos())){event.setCanceled(true);return;}
        if(!event.getLevel().isClientSide()&&hostile(event.getEntity())&&inside(event.getEntity()))event.setCanceled(true);
    }
    private static void griefing(EntityMobGriefingEvent event) {
        var mob=event.getEntity();var state=state(mob.level());
        if(active(state)&&hostile(mob)&&state.store.intersects(mob.getBoundingBox().inflate(8)))event.setCanGrief(false);
    }
    private static void spawn(MobSpawnEvent.PositionCheck event){if(hostile(event.getEntity())&&inside(event.getEntity()))event.setResult(MobSpawnEvent.PositionCheck.Result.FAIL);}
    private static void damage(LivingIncomingDamageEvent event){if(inside(event.getEntity())&&hostile(event.getSource().getEntity()))event.setCanceled(true);}
}
