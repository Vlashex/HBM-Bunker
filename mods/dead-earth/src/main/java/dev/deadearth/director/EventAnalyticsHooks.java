package dev.deadearth.director;

import dev.deadearth.DeadEarth;
import java.util.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;
import net.minecraft.world.damagesource.DamageSource;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.damagesource.DamageContainer;
import net.neoforged.neoforge.event.entity.living.*;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.event.PlayLevelSoundEvent;

/** Native pre/post health deltas and delayed committed death statistics; posting an event is not damage. */
public final class EventAnalyticsHooks {
    private record Before(DamageSource source,float hp,float absorption,int tick) {}
    private record Death(LivingDeathEvent event,int nativeDeaths,long tick,EventAnalytics.Point point,UUID owner) {}
    private static final Map<ServerPlayer,Deque<Before>> BEFORE=new WeakHashMap<>();
    private static final Map<ServerPlayer,Death> DEATHS=new WeakHashMap<>();
    private static final Set<ServerPlayer> CONFIRMED=Collections.newSetFromMap(new WeakHashMap<>());
    private static final ThreadLocal<Playback> PLAYBACK=new ThreadLocal<>();
    private static final class Playback {final net.minecraft.server.level.ServerLevel level;final net.minecraft.core.BlockPos point;PlayLevelSoundEvent.AtPosition event;Playback(net.minecraft.server.level.ServerLevel level,net.minecraft.core.BlockPos point){this.level=level;this.point=point;}}
    private EventAnalyticsHooks(){}
    public static void install() {
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST,false,LivingDamageEvent.Pre.class,EventAnalyticsHooks::before);
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST,false,LivingDamageEvent.Post.class,EventAnalyticsHooks::after);
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST,false,LivingDeathEvent.class,EventAnalyticsHooks::death);
        NeoForge.EVENT_BUS.addListener(EventAnalyticsHooks::tick);
        NeoForge.EVENT_BUS.addListener(EventAnalyticsHooks::stopped);
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST,true,PlayLevelSoundEvent.AtPosition.class,EventAnalyticsHooks::sound);
    }
    private static void sound(PlayLevelSoundEvent.AtPosition event){var scope=PLAYBACK.get();if(scope!=null&&event.getLevel()==scope.level&&event.getPosition().distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(scope.point))<.001)scope.event=event;}
    /** Final cancellation/null/mute state is checked after every listener and actual playSound return. */
    public static boolean confirmedSound(net.minecraft.server.level.ServerLevel level,net.minecraft.core.BlockPos point,Runnable emit) {
        var previous=PLAYBACK.get();var scope=new Playback(level,point);PLAYBACK.set(scope);
        try{emit.run();var e=scope.event;return e!=null&&!e.isCanceled()&&e.getSound()!=null&&Float.isFinite(e.getNewVolume())&&e.getNewVolume()>0;}
        finally{if(previous==null)PLAYBACK.remove();else PLAYBACK.set(previous);}
    }
    private static void before(LivingDamageEvent.Pre event) {
        if(!(event.getEntity() instanceof ServerPlayer p)||p.isSpectator()||p.getServer()==null||!p.getServer().isSameThread())return;
        if(p.isAlive())CONFIRMED.remove(p);
        if(BEFORE.size()>=256&&!BEFORE.containsKey(p))return;
        var queue=BEFORE.computeIfAbsent(p,k->new ArrayDeque<>());while(queue.size()>=8)queue.removeFirst();
        queue.addLast(new Before(event.getSource(),p.getHealth(),p.getAbsorptionAmount(),p.getServer().getTickCount()));
    }
    private static void after(LivingDamageEvent.Post event) {
        if(!(event.getEntity() instanceof ServerPlayer p)||p.getServer()==null)return;
        var queue=BEFORE.get(p);if(queue==null)return;Before prior=null;
        var it=queue.descendingIterator();while(it.hasNext()){var candidate=it.next();if(candidate.source==event.getSource()){prior=candidate;it.remove();break;}}
        if(queue.isEmpty())BEFORE.remove(p);if(prior==null||prior.tick!=p.getServer().getTickCount())return;
        double hp=Math.max(0,Math.min(prior.hp-p.getHealth(),event.getNewDamage()));
        double absorbed=Math.max(0,Math.min(prior.absorption-p.getAbsorptionAmount(),event.getReduction(DamageContainer.Reduction.ABSORPTION)));
        if(!Double.isFinite(hp)||!Double.isFinite(absorbed)||hp==0&&absorbed==0)return;
        try{var runtime=DeadEarth.runtime(p.getServer());if(runtime.enabled())runtime.cinematics().analytics().damage(p,event.getSource(),hp,absorbed);}catch(IllegalStateException unavailable){}
    }
    private static void death(LivingDeathEvent event) {
        if(!(event.getEntity() instanceof ServerPlayer p)||p.getServer()==null||!p.isDeadOrDying()||CONFIRMED.contains(p)||DEATHS.size()>=256&&!DEATHS.containsKey(p))return;
        try{var runtime=DeadEarth.runtime(p.getServer());if(runtime.enabled())DEATHS.putIfAbsent(p,new Death(event,p.getStats().getValue(Stats.CUSTOM.get(Stats.DEATHS)),runtime.campaign().campaign().activeTicks(),EventAnalyticsController.point(p),EventAnalyticsController.owned(event.getSource())));}catch(IllegalStateException unavailable){}
    }
    private static void tick(ServerTickEvent.Post event) {
        var it=DEATHS.entrySet().iterator();while(it.hasNext()) {
            var entry=it.next();var p=entry.getKey();if(p.getServer()!=event.getServer())continue;var candidate=entry.getValue();it.remove();
            if(!candidate.event.isCanceled()&&p.isDeadOrDying()&&p.getStats().getValue(Stats.CUSTOM.get(Stats.DEATHS))>candidate.nativeDeaths&&!CONFIRMED.contains(p)) {
                CONFIRMED.add(p);try{var runtime=DeadEarth.runtime(p.getServer());if(runtime.enabled())runtime.cinematics().analytics().confirmedDeath(p.getUUID(),candidate.tick,candidate.point,candidate.owner);}catch(IllegalStateException unavailable){}
            }
        }
        BEFORE.entrySet().removeIf(e->e.getKey().getServer()==event.getServer()&&e.getValue().stream().allMatch(b->b.tick<event.getServer().getTickCount()));
        CONFIRMED.removeIf(p->p.getServer()==event.getServer()&&p.isAlive());
    }
    private static void stopped(ServerStoppedEvent event){BEFORE.keySet().removeIf(p->p.getServer()==event.getServer());DEATHS.keySet().removeIf(p->p.getServer()==event.getServer());CONFIRMED.removeIf(p->p.getServer()==event.getServer());}
}
