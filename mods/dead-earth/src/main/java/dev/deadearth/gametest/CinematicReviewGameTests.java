package dev.deadearth.gametest;

import dev.deadearth.*;
import dev.deadearth.content.*;
import dev.deadearth.director.*;
import dev.deadearth.zone.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.gametest.*;

@GameTestHolder("dead_earth_cinematic_review") @PrefixGameTestTemplate(false)
public final class CinematicReviewGameTests {
    @GameTest(template="empty",batch="cinematic_corrupt")
    public static void unreadableDirectorFilePreservesCoreAndOriginalBytes(GameTestHelper h)throws Exception {
        var server=h.getLevel().getServer();var level=server.overworld();var campaign=DeadEarth.runtime(server).campaign().campaign().campaignId();
        var factory=new SavedData.Factory<>(()->CinematicStore.create(campaign),(tag,reg)->CinematicStore.load(campaign,tag,reg));
        var original=level.getDataStorage().get(factory,CinematicStore.NAME);
        var file=server.getWorldPath(LevelResource.ROOT).resolve("data/"+CinematicStore.NAME+".dat");
        byte[] backup=Files.exists(file)?Files.readAllBytes(file):null;byte[] corrupt={10,20,30,40,50};
        try {
            Files.createDirectories(file.getParent());Files.write(file,corrupt);level.getDataStorage().set(CinematicStore.NAME,null);
            var isolated=new CoordinatorRuntime(server);
            h.assertTrue(isolated.enabled(),"Unreadable optional director disabled core seasons");
            h.assertTrue(((Map<?,?>)isolated.cinematics().status()).get("status").equals("unsupported_save"),"Unreadable director was silently recreated");
            h.assertTrue(Arrays.equals(corrupt,Files.readAllBytes(file)),"Unreadable original file was overwritten");isolated.close();
        } finally {level.getDataStorage().set(CinematicStore.NAME,original);if(backup==null)Files.deleteIfExists(file);else Files.write(file,backup);}
        h.succeed();
    }
    static final class Fixture implements AutoCloseable {
        final GameTestHelper h;final FoundationGameTests.AttachedClient client;final CinematicDirector director;
        final CinematicStore store;final CinematicEngine previous;final java.lang.reflect.Field engineField;
        final CinematicCatalog catalog;final CinematicEngine.Operation op;final BlockPos base;final boolean enabled;
        Fixture(GameTestHelper h,int origin)throws Exception {
            this.h=h;var level=h.getLevel();var server=level.getServer();base=new BlockPos(origin+8,200,origin+8);
            for(int x=-1;x<=1;x++)for(int z=-1;z<=1;z++)level.getChunk(base.getX()/16+x,base.getZ()/16+z);
            for(int x=-16;x<32;x++)for(int z=-16;z<32;z++)level.setBlock(new BlockPos(origin+x,200,origin+z),Blocks.STONE.defaultBlockState(),3);
            level.setBlock(base,ZoneLoaderContent.LOADER.get().defaultBlockState(),3);
            client=FoundationGameTests.attach(h);client.player().setPos(base.getX()+.5,201,base.getZ()+.5);client.player().setXRot(-90);
            director=DeadEarth.runtime(server).cinematics();catalog=CinematicGameTests.catalog();
            var sf=CinematicDirector.class.getDeclaredField("store");sf.setAccessible(true);store=(CinematicStore)sf.get(director);
            engineField=CinematicStore.class.getDeclaredField("engine");engineField.setAccessible(true);previous=(CinematicEngine)engineField.get(store);engineField.set(store,new CinematicEngine());
            enabled=dev.deadearth.config.DeadEarthConfig.b(dev.deadearth.config.DeadEarthConfig.DIRECTOR_ENABLED);dev.deadearth.config.DeadEarthConfig.DIRECTOR_ENABLED.set(true);
            ZoneLoaders.requestScan(server);for(int n=0;n<100;n++)ZoneLoaders.tick(server);
            var brief=director.brief();h.assertTrue(brief.goal()!=null,"No complete review fixture: "+brief.status());
            var option=brief.goal().options().getFirst();var verdict=director.choose(UUID.randomUUID(),brief.goal().id(),option.eventId(),option.zoneId());
            h.assertTrue(verdict.accepted(),"Review operation rejected");var actual=store.engine().operations().getFirst();
            op=new CinematicEngine.Operation(actual.id(),actual.requestId(),actual.frozenCatalog(),actual.eventId(),actual.zoneId(),actual.state(),actual.node(),actual.cursor(),actual.created(),actual.expires(),actual.entered(),actual.budget(),1,"",Set.of());
        }
        Zombie mob() {return own(EntityType.ZOMBIE.create(h.getLevel()));}
        MotionProbe moveProbe() {var probe=new MotionProbe(h.getLevel());own(probe);return probe;}
        private Zombie own(Zombie mob) {
            mob.setPos(base.getX()+2.5,201,base.getZ()+.5);
            mob.getPersistentData().putUUID("dead_earth:director_operation",op.id());mob.getPersistentData().putLong("dead_earth:director_expires",op.expires()+1200);
            h.getLevel().addFreshEntity(mob);director.observe(mob);return mob;
        }
        @Override public void close() {
            director.cancel(op.id());engineField.setAccessible(true);try{engineField.set(store,previous);}catch(IllegalAccessException e){throw new IllegalStateException(e);}
            var cleanup=new ArrayList<net.minecraft.world.entity.Entity>();h.getLevel().getAllEntities().forEach(entity->{if(entity!=null)cleanup.add(entity);});
            for(var entity:cleanup)if(entity.getPersistentData().hasUUID("dead_earth:director_operation")&&op.id().equals(entity.getPersistentData().getUUID("dead_earth:director_operation")))entity.discard();
            client.close();h.getLevel().removeBlock(base,false);dev.deadearth.config.DeadEarthConfig.DIRECTOR_ENABLED.set(enabled);
        }
    }
    @GameTest(template="empty",batch="cinematic_unload")
    public static void unloadedLivingWaveRemainsOutstandingAndDoesNotMeanClear(GameTestHelper h)throws Exception {
        try(var f=new Fixture(h,2400000)) {
            var mob=f.mob();int before=f.director.livingOwned();mob.remove(Entity.RemovalReason.UNLOADED_TO_CHUNK);f.director.tick();
            h.assertTrue(f.director.livingOwned()>=before,"Unloaded living enemy freed the finite population budget");
            h.assertTrue(!f.director.signals(f.op).contains("clear"),"Chunk unload was reported as a defeated wave");
        }h.succeed();
    }
    /** Records the actual navigation command; finding a native path is a separate mod-AI concern. */
    private static final class MotionProbe extends Zombie {
        BlockPos commanded;
        MotionProbe(net.minecraft.world.level.Level level){super(EntityType.ZOMBIE,level);}
        @Override protected net.minecraft.world.entity.ai.navigation.PathNavigation createNavigation(net.minecraft.world.level.Level level) {
            return new net.minecraft.world.entity.ai.navigation.GroundPathNavigation(this,level) {
                @Override public boolean moveTo(double x,double y,double z,double speed){commanded=BlockPos.containing(x,y,z);return true;}
            };
        }
    }
    @GameTest(template="empty",batch="cinematic_move_key")
    public static void movementUsesDocumentedDestinationKey(GameTestHelper h)throws Exception {
        try(var f=new Fixture(h,2500000)) {
            var mob=f.moveProbe();var method=CinematicDirector.class.getDeclaredMethod("resolvedAnchor",CinematicEngine.Operation.class,String.class);method.setAccessible(true);
            var rear=(BlockPos)method.invoke(f.director,f.op,"rear");var front=(BlockPos)method.invoke(f.director,f.op,"approach");
            h.assertTrue(rear!=null&&front!=null,"Movement fixture lacks both directions");
            f.director.emit(f.op,new CinematicCatalog.Action("move","rear","approach",0),0,f.catalog);
            var target=mob.commanded;h.assertTrue(target!=null&&target.distSqr(rear)<target.distSqr(front),"move destination: target="+target+", rear="+rear+", front="+front+", aggro="+(mob.getTarget()!=null));
        }h.succeed();
    }
    @GameTest(template="empty",batch="cinematic_cancel_mine")
    public static void laterCanceledMiningAttemptCannotTriggerAnAmbush(GameTestHelper h)throws Exception {
        var f=new Fixture(h,2600000);try {
            f.director.emit(f.op,new CinematicCatalog.Action("trace","spore_1","approach",0),0,f.catalog);
            TraceContent.TraceEntity trace=null;
            for(int x=-1;x<=1;x++)for(int z=-1;z<=1;z++)for(var be:h.getLevel().getChunk(f.base.getX()/16+x,f.base.getZ()/16+z).getBlockEntities().values())
                if(be instanceof TraceContent.TraceEntity t&&f.op.id().equals(t.operation()))trace=t;
            h.assertTrue(trace!=null,"Mining fixture has no clue");var pos=trace.getBlockPos().east();h.getLevel().setBlock(pos,Blocks.STONE.defaultBlockState(),3);
            var attempt=new BlockEvent.BreakEvent(h.getLevel(),pos,h.getLevel().getBlockState(pos),f.client.player());
            Consumer<BlockEvent.BreakEvent> cancel=e->{if(e==attempt)e.setCanceled(true);};
            NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST,false,BlockEvent.BreakEvent.class,cancel);
            try{NeoForge.EVENT_BUS.post(attempt);}finally{NeoForge.EVENT_BUS.unregister(cancel);}
            h.assertTrue(attempt.isCanceled()&&!f.director.signals(f.op).contains("mine"),"Canceled mining attempt already triggered mine");
            h.assertTrue(f.client.player().gameMode.destroyBlock(pos),"Real allowed mining was not performed");
            h.runAfterDelay(3,()->{
                try {
                    boolean observed=f.director.signals(f.op).contains("mine")||f.store.engine().operations().stream().anyMatch(o->o.id().equals(f.op.id())&&o.latched().contains("mine"));
                    h.assertTrue(observed,"Allowed physical mining did not reach the scenario");h.succeed();
                } finally {f.close();}
            });
        }catch(RuntimeException|Error failure){f.close();throw failure;}
    }
}
