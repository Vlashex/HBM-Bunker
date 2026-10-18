package dev.deadearth.gametest;

import dev.deadearth.director.*;
import java.util.*;
import java.util.function.Consumer;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.level.GameType;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.damagesource.DamageContainer;
import net.neoforged.neoforge.event.entity.living.*;
import net.neoforged.neoforge.gametest.*;

/** Uses the already included cinematic_review namespace; no additional launch mode or live world. */
@GameTestHolder("dead_earth_cinematic_review") @PrefixGameTestTemplate(false)
public final class EventAnalyticsGameTests {
    private static EventAnalytics.Event event(CinematicReviewGameTests.Fixture f){return f.director.analytics().analytics().view("ready").operations().stream().filter(e->e.operationId().equals(f.op.id())).findFirst().orElseThrow();}
    private static void activate(CinematicReviewGameTests.Fixture f){f.store.engine().tick(f.catalog,f.director.context(),f.director);}
    /** Native newly connected players have a separate 60-tick join grace, unrelated to hurt cooldown. */
    private static void finishJoinGrace(net.minecraft.server.level.ServerPlayer p)throws Exception {
        var field=net.minecraft.server.level.ServerPlayer.class.getDeclaredField("spawnInvulnerableTime");
        field.setAccessible(true);field.setInt(p,0);
    }
    @GameTest(template="empty",batch="analytics_actual_damage")
    public static void nativePostTracksActualHpAbsorptionAndRejectsSyntheticPost(GameTestHelper h)throws Exception {
        try(var f=new CinematicReviewGameTests.Fixture(h,2900000)) {
            activate(f);
            var p=f.client.player();finishJoinGrace(p);p.setGameMode(GameType.SURVIVAL);p.getAbilities().invulnerable=false;p.getInventory().clearContent();p.removeAllEffects();p.setHealth(20);p.setAbsorptionAmount(4);p.invulnerableTime=0;
            var mob=f.mob();mob.setNoAi(true);var source=p.damageSources().mobAttack(mob);float hp=p.getHealth(),absorbed=p.getAbsorptionAmount();
            h.assertTrue(p.hurt(source,7),"Actual fixture hit did not happen");var after=event(f).damage();
            h.assertTrue(hp-p.getHealth()>0,"Fixture had no actual HP loss");
            h.assertTrue(Math.abs(after.hp()-(hp-p.getHealth()))<.001,"Damage used incoming amount rather than lost HP");
            h.assertTrue(Math.abs(after.absorbedHp()-(absorbed-p.getAbsorptionAmount()))<.001,"Absorption was not measured separately");
            double before=after.hp();NeoForge.EVENT_BUS.post(new LivingDamageEvent.Post(p,new DamageContainer(source,100)));
            h.assertTrue(event(f).damage().hp()==before,"Posting a synthetic event created damage");
            h.assertTrue(event(f).damage().ownedAttributedHp()==before,"Owned source lost direct attribution");
        }h.succeed();
    }
    @GameTest(template="empty",batch="analytics_cancel_radius")
    public static void cancelledInvulnerableAndOutOfRadiusHitsAreNotClaimed(GameTestHelper h)throws Exception {
        try(var f=new CinematicReviewGameTests.Fixture(h,3000000)) {
            activate(f);
            var p=f.client.player();finishJoinGrace(p);p.setGameMode(GameType.SURVIVAL);p.setHealth(20);p.setAbsorptionAmount(0);p.invulnerableTime=0;
            Consumer<LivingIncomingDamageEvent> cancel=e->{if(e.getEntity()==p)e.setCanceled(true);};
            NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST,false,LivingIncomingDamageEvent.class,cancel);
            try{p.hurt(p.damageSources().generic(),4);}finally{NeoForge.EVENT_BUS.unregister(cancel);}
            h.assertTrue(event(f).damage().hp()==0&&p.getHealth()==20,"Canceled incoming damage was counted");
            p.getAbilities().invulnerable=true;p.invulnerableTime=0;p.hurt(p.damageSources().generic(),4);p.getAbilities().invulnerable=false;
            h.assertTrue(event(f).damage().hp()==0,"Invulnerable hit was counted");
            p.setPos(f.base.getX()+150,201,f.base.getZ());p.invulnerableTime=0;p.hurt(p.damageSources().generic(),4);
            h.assertTrue(p.getHealth()<20,"Distant fixture hit caused no actual HP loss");
            h.assertTrue(event(f).damage().hp()==0,"Unrelated distant actual hit was attributed to the operation");
        }h.succeed();
    }
    @GameTest(template="empty",batch="analytics_cancel_death",timeoutTicks=100)
    public static void cancelledDeathDoesNotCountButCommittedNativeDeathDoes(GameTestHelper h)throws Exception {
        var f=new CinematicReviewGameTests.Fixture(h,3100000);var p=f.client.player();
        activate(f);
        Consumer<LivingDeathEvent> cancel=e->{if(e.getEntity()==p)e.setCanceled(true);};
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST,false,LivingDeathEvent.class,cancel);
        try {
            p.setHealth(0);p.die(p.damageSources().generic());
            h.runAfterDelay(3,()->{
                try {
                    h.assertTrue(event(f).damage().deaths()==0,"Canceled death became an analytics fact");NeoForge.EVENT_BUS.unregister(cancel);
                    p.setHealth(20);p.setHealth(0);p.die(p.damageSources().generic());
                    h.runAfterDelay(3,()->{try{h.assertTrue(event(f).damage().deaths()==1,"Native committed death missing or duplicated");h.succeed();}finally{f.close();}});
                }catch(RuntimeException|Error failure){NeoForge.EVENT_BUS.unregister(cancel);f.close();throw failure;}
            });
        }catch(RuntimeException|Error failure){NeoForge.EVENT_BUS.unregister(cancel);f.close();throw failure;}
    }
    @GameTest(template="empty",batch="analytics_emit_guard")
    public static void canceledNativePlaybackCannotCreateASignalOrAnchor(GameTestHelper h)throws Exception {
        try(var f=new CinematicReviewGameTests.Fixture(h,3200000)) {
            activate(f);var sound=new CinematicCatalog.Action("sound",f.catalog.sounds().keySet().iterator().next(),"distraction",0);
            long before=event(f).observations().signalEmissions();
            Consumer<net.neoforged.neoforge.event.PlayLevelSoundEvent.AtPosition> cancel=e->{if(e.getLevel()==h.getLevel())e.setCanceled(true);};
            NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST,false,net.neoforged.neoforge.event.PlayLevelSoundEvent.AtPosition.class,cancel);
            try{f.director.emit(f.op,sound,0,f.catalog);}finally{NeoForge.EVENT_BUS.unregister(cancel);}
            h.assertTrue(event(f).observations().signalEmissions()==before,"Canceled sound became emitted signal");
            f.director.emit(f.op,sound,0,f.catalog);
            h.assertTrue(event(f).observations().signalEmissions()==before+1,"Uncanceled native playback was not observed");
        }h.succeed();
    }
}
