package dev.deadearth.director;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class EventAnalyticsTest {
    @Test void restoreReducedLimitEvictsFinishedBeforeActiveMeasurements() {
        var a=model();open(a,first,0);a.damage(5,player,origin,first,7,0);
        open(a,second,5);a.finish(second,10,"contained");
        var reduced=new EventAnalytics.Settings(1,48,32,20,96,6,4,600,1,20,0,1,2,3);
        var restored=EventAnalytics.restore(reduced,a.snapshot());
        assertTrue(restored.contains(first));assertFalse(restored.contains(second));
        assertEquals(7,event(restored,first).damage().hp());
    }

    final UUID first=new UUID(0,1),second=new UUID(0,2),player=new UUID(1,1);
    final EventAnalytics.Point origin=new EventAnalytics.Point("minecraft:overworld",0,64,0);
    EventAnalytics model(){return new EventAnalytics(EventAnalytics.Settings.defaults());}
    void open(EventAnalytics a,UUID id,double x){a.open(id,"false_siege",new UUID(2,1).toString(),0,1000,"WAITING","complete",new EventAnalytics.Point("minecraft:overworld",x,64,0));}
    EventAnalytics.Event event(EventAnalytics a,UUID id){return a.view("ready").operations().stream().filter(e->e.operationId().equals(id)).findFirst().orElseThrow();}

    @Test void ownedAttackerWinsAndOverlapNeverMultipliesActualLoss() {
        var a=model();open(a,first,0);open(a,second,5);
        assertEquals(second,a.damage(10,player,origin,second,3,2));
        assertEquals(0,event(a,first).damage().hp());assertEquals(3,event(a,second).damage().hp());
        assertEquals(2,event(a,second).damage().absorbedHp());
        assertEquals(3,event(a,second).damage().points());
        assertEquals(first,a.damage(11,player,origin,null,4,0));
        assertEquals(7,a.view("ready").totals().hp());
        assertEquals(3,event(a,second).damage().ownedAttributedHp());
        assertEquals(4,event(a,first).damage().spatialAttributedHp());
    }
    @Test void distantForeignAndExpiredOperationsDoNotClaimDamageOrDeaths() {
        var a=model();open(a,first,0);open(a,second,5);
        assertNull(a.damage(2,player,new EventAnalytics.Point("minecraft:overworld",500,64,0),null,3,0));
        assertNull(a.damage(3,player,new EventAnalytics.Point("minecraft:the_nether",0,64,0),null,3,0));
        a.finish(first,5,"engaged");
        assertNull(a.damage(6,player,origin,first,3,0),"stale owned source must not fall back to another operation");
        assertNull(a.damage(1001,player,origin,null,3,0));
        assertEquals(second,a.death(8,player,origin,null));
        a.death(8,player,origin,null);
        assertEquals(1,event(a,second).damage().deaths());
        assertEquals(20,event(a,second).damage().points());
    }
    @Test void proximityAloneIsInsufficientAndMovementRequiresActualEmittedCue() {
        var a=model();open(a,first,0);
        a.sample(first,10,List.of(new EventAnalytics.PlayerPoint(player,new EventAnalytics.Point("minecraft:overworld",1,64,0))));
        assertEquals("insufficient_data",event(a,first).proxy().distraction());
        a.action(first,20,"sound","decoy","distraction",1,origin,false);
        a.sample(first,20,List.of(new EventAnalytics.PlayerPoint(player,new EventAnalytics.Point("minecraft:overworld",20,64,0))));
        a.sample(first,40,List.of(new EventAnalytics.PlayerPoint(player,new EventAnalytics.Point("minecraft:overworld",2,64,0))));
        assertEquals(1,event(a,first).observations().approaches());
        assertEquals(1,event(a,first).observations().nearEntries());
        assertEquals("observed",event(a,first).proxy().distraction());
        assertEquals("insufficient_data",event(a,first).proxy().deception());
        a.action(first,45,"wave","trap","approach",2,origin,true);
        a.sample(first,60,List.of(new EventAnalytics.PlayerPoint(player,new EventAnalytics.Point("minecraft:overworld",20,64,0))));
        a.sample(first,80,List.of(new EventAnalytics.PlayerPoint(player,new EventAnalytics.Point("minecraft:overworld",2,64,0))));
        assertEquals(1,event(a,first).observations().trapEntries());
        assertEquals("observed",event(a,first).proxy().deception());
    }
    @Test void restartKeepsRawMeasurementsAndBoundsButMarksSamplingGap() {
        var a=model();open(a,first,0);a.damage(10,player,origin,null,5,0);
        for(int n=0;n<100;n++)a.interaction(first,20+n,player,"inspect","approach");
        var before=event(a,first);assertTrue(before.droppedTimeline()>0);
        var restored=EventAnalytics.restore(a.settings(),a.snapshot());
        var after=event(restored,first);assertEquals(5,after.damage().hp());
        assertEquals(before.observations(),after.observations());assertEquals(before.timeline(),after.timeline());
        assertEquals("partial",after.coverage());assertEquals(1,after.participants());
        assertTrue(after.timeline().size()<=a.settings().maxTimeline());
        assertFalse(after.toString().contains(player.toString()),"public event DTO exposes a player UUID");
    }
    @Test void retainedHistoryIsBoundedAndMalformedRestoreIsRejected() {
        var a=model();for(int n=0;n<80;n++){var id=new UUID(0,n+1);open(a,id,0);a.finish(id,10,"avoided");}
        assertEquals(a.settings().maxOperations(),a.view("ready").operations().size());
        var data=a.snapshot();var duplicate=new ArrayList<>(data.operations());duplicate.add(data.operations().getFirst());
        assertThrows(IllegalArgumentException.class,()->EventAnalytics.restore(a.settings(),new EventAnalytics.Stored(duplicate)));
    }
}
