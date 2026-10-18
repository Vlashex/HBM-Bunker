package dev.deadearth.director;

import com.google.gson.JsonObject;
import dev.deadearth.CoordinatorRuntime;
import dev.deadearth.campaign.SavedDataAccess;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import static dev.deadearth.config.DeadEarthConfig.*;

/** Online-only server adapter. No world scans, entity names, chunk requests or model inputs. */
public final class EventAnalyticsController {
    private final MinecraftServer server;private final CoordinatorRuntime runtime;private final CinematicStore cinematic;
    private final EventAnalyticsStore store;private final Set<UUID> known=new HashSet<>();private long lastSample=-1;
    private static EventAnalytics.Settings settings(){return new EventAnalytics.Settings(i(ANALYTICS_OPERATIONS),i(ANALYTICS_TIMELINE),i(ANALYTICS_PARTICIPANTS),i(ANALYTICS_SAMPLE),d(ANALYTICS_RADIUS),d(ANALYTICS_NEAR),d(ANALYTICS_APPROACH),Math.max(i(ANALYTICS_SAMPLE),i(ANALYTICS_WINDOW)),d(ANALYTICS_HP_WEIGHT),d(ANALYTICS_DEATH_WEIGHT),d(ANALYTICS_ABSORBED_WEIGHT),d(ANALYTICS_APPROACH_WEIGHT),d(ANALYTICS_INTERACTION_WEIGHT),d(ANALYTICS_TRAP_WEIGHT));}
    public EventAnalyticsController(MinecraftServer server,CoordinatorRuntime runtime,CinematicStore cinematic){this.server=server;this.runtime=runtime;this.cinematic=cinematic;store=EventAnalyticsStore.open(server.overworld(),runtime.campaign().campaign().campaignId(),settings());sync(true);}
    private long now(){return runtime.campaign().campaign().activeTicks();}
    private void refresh(){if(store.reconfigure(settings()))lastSample=-1;}
    private boolean enabled(){return b(ANALYTICS_ENABLED)&&store.enabled()&&cinematic.enabled();}
    public EventAnalytics analytics(){return store.analytics();}
    private EventAnalytics.Point center(CinematicEngine.Operation op){var z=dev.deadearth.zone.ZoneLoaders.zones(server).stream().filter(v->v.id().toString().equals(op.zoneId())).findFirst().orElse(null);if(z==null||z.loaders().isEmpty())return null;var p=z.loaders().getFirst();return new EventAnalytics.Point(z.dimension(),p.x(),p.y(),p.z());}
    public void register(UUID id) {
        refresh();
        if(!enabled()||known.contains(id))return;
        cinematic.engine().operations().stream().filter(op->op.id().equals(id)).findFirst().ifPresent(op->{store.analytics().open(op.id(),op.eventId(),op.zoneId(),op.created(),op.expires(),op.state().name(),"complete",center(op));known.add(id);store.setDirty();});
    }
    private void sync(boolean legacy) {
        if(!store.enabled()||!cinematic.enabled())return;var model=store.analytics();var engine=cinematic.engine();
        if(legacy) {
            var history=engine.history();for(int n=Math.max(0,history.size()-model.settings().maxOperations());n<history.size();n++) {
                var h=history.get(n);if(!model.contains(h.operationId())){model.open(h.operationId(),h.eventId(),h.zoneId(),0,Math.max(1,h.tick()+1),h.outcome(),"unknown",null);model.finish(h.operationId(),h.tick(),h.outcome());}
            }
        }
        for(var op:engine.operations()) {
            if(!known.contains(op.id())&&!model.contains(op.id()))model.open(op.id(),op.eventId(),op.zoneId(),op.created(),op.expires(),op.state().name(),"unknown",center(op));
            model.state(op.id(),op.state().name());
        }
        for(var h:engine.history())model.finish(h.operationId(),h.tick(),h.outcome());
        known.clear();engine.operations().forEach(op->known.add(op.id()));engine.history().forEach(h->known.add(h.operationId()));store.setDirty();
    }
    public void tick() {
        refresh();
        if(!enabled()||server.getPlayerCount()==0||now()==lastSample||lastSample>=0&&now()-lastSample<store.analytics().settings().sampleTicks())return;
        sync(false);lastSample=now();
        var players=server.getPlayerList().getPlayers().stream().filter(p->p.isAlive()&&!p.isSpectator()).sorted(Comparator.comparing(Entity::getUUID)).limit(256)
            .map(p->new EventAnalytics.PlayerPoint(p.getUUID(),point(p))).toList();
        for(var op:cinematic.engine().operations())store.analytics().sample(op.id(),now(),players);store.setDirty();
    }
    public static EventAnalytics.Point point(ServerPlayer p){return new EventAnalytics.Point(p.level().dimension().location().toString(),p.getX(),p.getY(),p.getZ());}
    public static UUID owned(DamageSource source){for(var e:new Entity[]{source.getEntity(),source.getDirectEntity()})if(e!=null&&e.getPersistentData().hasUUID("dead_earth:director_operation"))return e.getPersistentData().getUUID("dead_earth:director_operation");return null;}
    public void damage(ServerPlayer p,DamageSource source,double hp,double absorbed){if(!enabled())return;sync(false);store.analytics().damage(now(),p.getUUID(),point(p),owned(source),hp,absorbed);store.setDirty();}
    public void confirmedDeath(UUID player,long tick,EventAnalytics.Point point,UUID owner){if(!enabled())return;store.analytics().death(tick,player,point,owner);store.setDirty();}
    public void interaction(UUID id,ServerPlayer p,String kind,String role){if(!enabled()||!p.isAlive()||p.isSpectator())return;store.analytics().interaction(id,now(),p.getUUID(),kind,role);store.setDirty();}
    public void action(CinematicEngine.Operation op,CinematicCatalog.Action action,int actual,net.minecraft.server.level.ServerLevel level,BlockPos pos,CinematicCatalog frozen) {
        if(!enabled()||actual<=0)return;var event=frozen.events().get(op.eventId());
        boolean trap=action.type().equals("wave")&&event!=null&&Set.of("lure","feint","fork","excavation_trap","seasonal_ambush","pursuit_trap").contains(event.tactic());
        store.analytics().action(op.id(),now(),action.type(),action.key(),action.role(),actual,new EventAnalytics.Point(level.dimension().location().toString(),pos.getX()+.5,pos.getY(),pos.getZ()+.5),trap);store.setDirty();
    }
    private String status(){return !store.enabled()?"unsupported_save":!b(ANALYTICS_ENABLED)?"disabled":"ready";}
    public JsonObject summary(int budget){refresh();var model=store.enabled()?store.analytics():new EventAnalytics(settings());return AnalyticsTransport.summary(model.view(status()),budget);}
    public JsonObject detail(UUID id){if(!store.enabled())return AnalyticsTransport.detail("unsupported_save",null);var op=store.analytics().view(status()).operations().stream().filter(e->e.operationId().equals(id)).findFirst().orElse(null);return AnalyticsTransport.detail(op==null?"not_found":"ready",op);}
    public void checkpoint(){sync(false);if(store.enabled())SavedDataAccess.checkpoint(server.overworld(),EventAnalyticsStore.NAME,store);}
}
