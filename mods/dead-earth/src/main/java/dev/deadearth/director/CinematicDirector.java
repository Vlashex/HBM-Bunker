package dev.deadearth.director;

import com.google.gson.Gson;
import dev.deadearth.CoordinatorRuntime;
import dev.deadearth.DeadEarth;
import dev.deadearth.campaign.SavedDataAccess;
import dev.deadearth.content.TraceContent;
import dev.deadearth.zone.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.*;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.loading.FMLPaths;
import static dev.deadearth.config.DeadEarthConfig.*;

/** Server adapter. All positions and amounts are resolved here, never by the language model. */
public final class CinematicDirector implements CinematicEngine.World {
    private final MinecraftServer server;
    private final CoordinatorRuntime runtime;
    private final CinematicStore store;
    private CinematicCatalog catalog;
    private String fault;
    private final Map<UUID,Set<String>> signals=new HashMap<>();
    private final Map<UUID,Long> armedBaseline=new HashMap<>();
    private final Map<UUID,List<TraceContent.TraceEntity>> marks=new HashMap<>();
    private final Map<UUID,LivingEntity> owned=new HashMap<>();
    private long lastTick=-1;
    public record Brief(String status,String catalogRevision,long activeTicks,String season,double pressure,
            CinematicEngine.Goal goal,List<CinematicEngine.History> recent,int active,int queued) {}
    public CinematicDirector(MinecraftServer server,CoordinatorRuntime runtime) {
        this.server=server;this.runtime=runtime;
        store=SavedDataAccess.open(server.overworld(),CinematicStore.NAME,new SavedData.Factory<>(
            ()->CinematicStore.create(runtime.campaign().campaign().campaignId()),
            (tag,registries)->CinematicStore.load(runtime.campaign().campaign().campaignId(),tag,registries)));
        try{reload();}catch(RuntimeException invalid){fault="catalog_unavailable";DeadEarth.LOGGER.error("Director catalog unavailable",invalid);}
        // Entities already loaded before ServerStarted are registered once; later joins use observe().
        for(var level:server.getAllLevels())for(var entity:level.getAllEntities())observe(entity);
    }
    public String reload() {
        runtime.requireServerThread();
        try {
            Path path=FMLPaths.CONFIGDIR.get().resolve("dead-earth/director/catalog.json");String source;
            if(Files.exists(path)) {
                if(!Files.isRegularFile(path)||Files.isSymbolicLink(path)||Files.size(path)>CinematicCatalog.MAX_BYTES)throw new IllegalArgumentException("Catalog file");
                source=Files.readString(path,StandardCharsets.UTF_8);
            } else try(var input=CinematicDirector.class.getResourceAsStream("/data/dead_earth/director/catalog.json")) {
                if(input==null)throw new IllegalStateException("Bundled catalog missing");source=new String(input.readNBytes(CinematicCatalog.MAX_BYTES+1),StandardCharsets.UTF_8);
            }
            var next=CinematicCatalog.parse(source);
            for(var sound:next.sounds().values())if(!BuiltInRegistries.SOUND_EVENT.containsKey(ResourceLocation.parse(sound.sound())))throw new IllegalArgumentException("Unknown sound: "+sound.sound());
            for(var wave:next.waves().values())for(var id:wave.entities())
                if(!BuiltInRegistries.ENTITY_TYPE.containsKey(ResourceLocation.parse(id)))throw new IllegalArgumentException("Unknown entity: "+id);
            catalog=next;fault=null;return catalog.revision();
        } catch(java.io.IOException invalid){throw new IllegalArgumentException("Cannot read director catalog",invalid);}
    }
    private long now(){return runtime.campaign().campaign().activeTicks();}
    private boolean enabled(){return b(DIRECTOR_ENABLED)&&catalog!=null&&store.enabled()&&fault==null;}
    public CinematicEngine.Context context() {
        var summaries=ZoneLoaders.summaries(server);var zones=new ArrayList<CinematicEngine.Zone>();
        for(var z:ZoneLoaders.zones(server)) {
            var scan=summaries.stream().filter(s->s.id().equals(z.id())).findFirst();if(scan.isEmpty())continue;
            var s=scan.get();var center=z.loaders().getFirst();
            var players=server.getPlayerList().getPlayers().stream().filter(p->!p.isSpectator()&&p.isAlive()
                &&p.level().dimension().location().toString().equals(z.dimension())
                &&p.distanceToSqr(center.x(),center.y(),center.z())<=96*96).toList();
            double playerScore=players.stream().mapToDouble(p->Math.min(5,p.experienceLevel/10d)+p.getArmorValue()/4d).average().orElse(0);
            long deaths=runtime.snapshot().observations().history().stream().filter(h->h.kind()==dev.deadearth.observation.ObservationLog.Kind.DEATH
                &&now()-h.tick()<=6000&&players.stream().anyMatch(p->p.getUUID().equals(h.player()))).count();
            zones.add(new CinematicEngine.Zone(z.id().toString(),s.value(),s.armedRank(),s.emptyRank(),s.ageTicks(),s.complete(),s.overflow(),players.size(),playerScore,deaths));
        }
        return new CinematicEngine.Context(now(),server.getPlayerCount(),runtime.effectiveSeason().name(),runtime.difficultyStatus().pressure(),List.copyOf(zones));
    }
    public Brief brief() {
        runtime.requireServerThread();var context=context();var engine=store.enabled()?store.engine():null;
        var goal=enabled()?engine.brief(runtime.epoch(),catalog,context,true):null;
        if(engine!=null)store.setDirty();
        var history=engine==null?List.<CinematicEngine.History>of():engine.history();
        return new Brief(!enabled()?fault!=null?fault:"disabled":context.online()==0?"no_players":goal==null?"waiting":"ready",
            catalog==null?"":catalog.revision(),now(),context.season(),context.pressure(),goal,
            history.subList(Math.max(0,history.size()-6),history.size()),engine==null?0:(int)engine.operations().stream().filter(o->o.state()!=CinematicEngine.State.QUEUED).count(),
            engine==null?0:(int)engine.operations().stream().filter(o->o.state()==CinematicEngine.State.QUEUED).count());
    }
    public Object status(){return store.enabled()?Map.of("operations",store.engine().operations().stream().map(o->Map.of(
        "id",o.id(),"eventId",o.eventId(),"zoneId",o.zoneId(),"state",o.state(),"node",o.node(),"budget",o.budget(),"spent",o.spent(),"expires",o.expires())).toList(),
        "history",store.engine().history(),"enabled",enabled()):Map.of("status","unsupported_save");}
    public CinematicEngine.Verdict choose(UUID request,UUID goal,String eventId,String zoneId) {
        runtime.requireServerThread();if(!enabled())return new CinematicEngine.Verdict(request,goal,false,"disabled",null);
        var verdict=store.engine().admit(request,goal,eventId,zoneId,runtime.epoch(),catalog,context(),true);
        if(verdict.accepted())checkpoint();return verdict;
    }
    public boolean cancel(UUID id){runtime.requireServerThread();return store.enabled()&&store.engine().cancel(id,now(),catalog.rules(),this);}
    public void signal(UUID op,String trigger,String role) {
        runtime.requireServerThread();if(!CinematicCatalog.TRIGGERS.contains(trigger)||!CinematicCatalog.ROLES.contains(role))return;
        if(!store.enabled()||store.engine().operations().stream().noneMatch(o->o.id().equals(op)))return;
        var facts=signals.computeIfAbsent(op,k->new HashSet<>());facts.add(trigger);facts.add(trigger+":"+role);
    }
    public void mined(ServerPlayer player,BlockPos pos) {
        if(!enabled())return;
        for(var op:store.engine().operations())if(level(op)==player.serverLevel())for(var mark:marks.getOrDefault(op.id(),List.of()))
            if(!mark.isRemoved()&&mark.getBlockPos().distSqr(pos)<=36){signal(op.id(),"mine",mark.role());break;}
    }
    public void observeTrace(TraceContent.TraceEntity trace) {
        if(trace.operation()==null||trace.isRemoved()||trace.getLevel()==null)return;
        var list=marks.computeIfAbsent(trace.operation(),id->new ArrayList<>());
        if(!list.contains(trace))list.add(trace);
    }
    public void observe(Entity entity) {
        if(entity instanceof LivingEntity living&&entity.getPersistentData().hasUUID("dead_earth:director_operation"))owned.put(entity.getUUID(),living);
    }
    public void tick() {
        if(catalog==null||!store.enabled()||fault!=null)return;
        // Existing owned entities have a finite campaign lifetime, including after chunk reload.
        var it=owned.values().iterator();int inspected=0;
        while(it.hasNext()&&inspected++<64) {
            var entity=it.next();if(entity.isRemoved()||!entity.isAlive()){it.remove();continue;}
            if(now()>=entity.getPersistentData().getLong("dead_earth:director_expires")){entity.discard();it.remove();}
        }
        marks.values().forEach(list->list.removeIf(TraceContent.TraceEntity::isRemoved));marks.values().removeIf(List::isEmpty);
        if(!enabled()||now()==lastTick||now()%20!=0)return;lastTick=now();
        try {
            var b=brief();
            // Model outage does not stop the finite server automaton; weighted shortlist provides a deterministic fallback.
            if(b.goal()!=null&&now()>=b.goal().expires()-20) {
                var first=b.goal().options().getFirst();
                choose(UUID.nameUUIDFromBytes((b.goal().id()+"/fallback").getBytes(StandardCharsets.UTF_8)),b.goal().id(),first.eventId(),first.zoneId());
            }
            store.engine().tick(catalog,context(),this);
            signals.clear();
            var active=store.engine().operations().stream().map(CinematicEngine.Operation::id).collect(java.util.stream.Collectors.toSet());
            armedBaseline.keySet().retainAll(active);
        } catch(RuntimeException invalid){fault="runtime_fault";DeadEarth.LOGGER.error("Cinematic director stopped; seasons remain active",invalid);}
    }
    private ZoneTopology.Zone zone(CinematicEngine.Operation op){return ZoneLoaders.zones(server).stream().filter(z->z.id().toString().equals(op.zoneId())).findFirst().orElse(null);}
    private ServerLevel level(CinematicEngine.Operation op){var z=zone(op);return z==null?null:server.getLevel(ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION,ResourceLocation.parse(z.dimension())));}
    @Override public boolean ready(CinematicEngine.Operation op) {
        var z=zone(op);var level=level(op);if(z==null||level==null)return false;var center=z.loaders().getFirst();
        return level.hasChunkAt(new BlockPos(center.x(),center.y(),center.z()))&&level.players().stream().anyMatch(p->!p.isSpectator()&&p.isAlive()&&p.distanceToSqr(center.x(),center.y(),center.z())<=96*96);
    }
    @Override public Set<String> signals(CinematicEngine.Operation op) {
        var facts=new HashSet<>(signals.getOrDefault(op.id(),Set.of()));var level=level(op);if(level==null)return facts;
        boolean near=false;
        for(var trace:marks.getOrDefault(op.id(),List.of()))if(!trace.isRemoved()&&trace.getLevel()==level) {
            if(level.players().stream().anyMatch(p->!p.isSpectator()&&p.isAlive()&&p.distanceToSqr(Vec3.atCenterOf(trace.getBlockPos()))<=36)) {
                facts.add("near:"+trace.role());facts.add("near");near=true;
            }
        }
        for(String role:CinematicCatalog.ROLES) {
            var pos=anchor(op,role,0);if(pos==null)continue;
            if(level.players().stream().anyMatch(p->!p.isSpectator()&&p.isAlive()&&p.distanceToSqr(Vec3.atCenterOf(pos))<=36)){facts.add("near:"+role);facts.add("near");near=true;}
        }
        if(!near&&level.players().stream().noneMatch(p->!p.isSpectator()&&p.isAlive()&&p.distanceToSqr(Vec3.atCenterOf(center(op)))<=64*64))facts.add("far");
        long armed=ZoneLoaders.summaries(server).stream().filter(z->z.id().toString().equals(op.zoneId())&&z.complete()).mapToLong(ZoneScanner.Summary::armedRank).findFirst().orElse(-1);
        if(armed>=0){long old=armedBaseline.computeIfAbsent(op.id(),id->armed);if(armed>old)facts.add("repair");}
        if(op.spent()>0&&owned.values().stream().noneMatch(e->e.isAlive()&&!e.isRemoved()&&op.id().equals(e.getPersistentData().getUUID("dead_earth:director_operation"))))facts.add("clear");
        return facts;
    }
    private BlockPos center(CinematicEngine.Operation op){var l=zone(op).loaders().getFirst();return new BlockPos(l.x(),l.y(),l.z());}
    private BlockPos anchor(CinematicEngine.Operation op,String role,int attempt) {
        var level=level(op);if(level==null)return null;var center=center(op);
        int index=switch(role){case "flank"->1;case "rear"->2;case "distraction"->3;default->0;};
        double yaw=(op.id().getLeastSignificantBits()&65535)/65536d*Math.PI*2+index*Math.PI/2+(attempt%5-2)*.18;
        int radius=Math.max(12,catalog.rules().signalRadius()-8)+attempt/5*2;
        int x=center.getX()+(int)Math.round(Math.cos(yaw)*radius),z=center.getZ()+(int)Math.round(Math.sin(yaw)*radius);
        var check=new BlockPos(x,center.getY(),z);if(!level.hasChunkAt(check))return null;
        int y=level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,x,z);var pos=new BlockPos(x,y,z);
        boolean ownTrace=level.getBlockEntity(pos) instanceof TraceContent.TraceEntity trace&&op.id().equals(trace.operation());
        if(!level.getWorldBorder().isWithinBounds(pos)||(!level.getBlockState(pos).isAir()&&!ownTrace)||!level.getFluidState(pos).isEmpty()
                ||!level.getBlockState(pos.below()).isFaceSturdy(level,pos.below(),Direction.UP))return null;
        return pos;
    }
    private boolean unseen(ServerLevel level,Vec3 point,int minimum) {
        return level.players().stream().filter(p->!p.isSpectator()&&p.isAlive()).noneMatch(p->p.distanceToSqr(point)<minimum*minimum
                ||p.getViewVector(1).dot(point.subtract(p.getEyePosition()).normalize())>.25);
    }
    @Override public int livingOwned(){return (int)owned.values().stream().filter(e->e.isAlive()&&!e.isRemoved()).count();}
    @Override public void emit(CinematicEngine.Operation op,CinematicCatalog.Action action,int count,CinematicCatalog frozen) {
        var level=level(op);if(level==null)return;var point=anchor(op,action.role(),0);if(point==null)return;
        switch(action.type()) {
            case "sound" -> {
                var s=frozen.sounds().get(action.key());var sound=BuiltInRegistries.SOUND_EVENT.get(ResourceLocation.parse(s.sound()));
                level.playSound(null,point,sound,SoundSource.AMBIENT,(float)s.volume(),(float)s.pitch());
            }
            case "trace" -> {
                if(marks.values().stream().mapToInt(List::size).sum()>=48)return;
                for(int attempt=0;attempt<12;attempt++) {
                    var pos=anchor(op,action.role(),attempt);if(pos==null||!level.getBlockState(pos).isAir()||!unseen(level,Vec3.atCenterOf(pos),8))continue;
                    if(level.setBlock(pos,TraceContent.TRACE.get().defaultBlockState(),3)&&level.getBlockEntity(pos) instanceof TraceContent.TraceEntity trace) {
                        trace.configure(op.id(),action.role(),frozen.traces().get(action.key()),op.expires());observeTrace(trace);break;
                    }
                }
            }
            case "particles" -> {
                var particle=switch(action.key()){case "spores"->ParticleTypes.SPORE_BLOSSOM_AIR;case "ash"->ParticleTypes.ASH;case "frost"->ParticleTypes.SNOWFLAKE;default->ParticleTypes.POOF;};
                level.sendParticles(particle,point.getX()+.5,point.getY()+.3,point.getZ()+.5,32,2,.5,2,.015);
            }
            case "wave" -> {
                var types=frozen.waves().get(action.key()).entities();
                for(int i=0;i<count;i++)for(int attempt=0;attempt<12;attempt++) {
                    var pos=anchor(op,action.role(),attempt+i*3);if(pos==null||!unseen(level,Vec3.atCenterOf(pos),catalog.rules().spawnMinDistance()))continue;
                    var entity=BuiltInRegistries.ENTITY_TYPE.get(ResourceLocation.parse(types.get(i%types.size()))).create(level);
                    if(!(entity instanceof Mob mob)){if(entity!=null)entity.discard();break;}
                    mob.moveTo(pos.getX()+.5,pos.getY(),pos.getZ()+.5,0,0);
                    if(!level.noCollision(mob)||level.containsAnyLiquid(mob.getBoundingBox())){mob.discard();continue;}
                    mob.getPersistentData().putUUID("dead_earth:director_operation",op.id());mob.getPersistentData().putLong("dead_earth:director_expires",op.expires()+1200);
                    mob.finalizeSpawn(level,level.getCurrentDifficultyAt(pos),MobSpawnType.EVENT,null);
                    if(level.addFreshEntity(mob))owned.put(mob.getUUID(),mob);break;
                }
            }
            case "move" -> {
                int moved=0;for(var e:owned.values())if(moved<16&&e instanceof Mob mob&&op.id().equals(e.getPersistentData().getUUID("dead_earth:director_operation"))&&mob.getTarget()==null) {
                    mob.getNavigation().moveTo(point.getX()+.5,point.getY(),point.getZ()+.5,1);moved++;
                }
            }
            default -> throw new IllegalArgumentException("Unknown world primitive");
        }
    }
    @Override public void checkpoint(){runtime.checkpointCinematic();store.setDirty();SavedDataAccess.checkpoint(server.overworld(),CinematicStore.NAME,store);}
}
