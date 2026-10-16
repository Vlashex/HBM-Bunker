package dev.deadearth.climate;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import dev.deadearth.climate.ClimateCycle.*;

class ClimateCycleTest {
    @Test void fullDefaultCycleHasThreePhasesAndTwoCalmDirections() {
        var cycle=new ClimateCycle(100,48000);
        var expected=List.of(Phase.CALM,Phase.HEAT,Phase.CALM,Phase.ASH_WINTER,Phase.CALM);
        var trends=List.of(Trend.WARMING,Trend.NONE,Trend.COOLING,Trend.NONE,Trend.WARMING);
        for(int i=0;i<5;i++) {
            var view=cycle.view(100L+i*48000);
            assertEquals(expected.get(i),view.phase());assertEquals(trends.get(i),view.trend());
            assertEquals(48000,view.ticksRemaining());assertEquals(i/4,view.cycle());
            if(i>0)assertEquals(expected.get(i-1),cycle.view(99L+i*48000).phase());
        }
    }
    @Test void originIsCurrentCampaignTimeAndLargeJumpDoesNotReplayOldPhases() {
        var cycle=new ClimateCycle(1234,48000);
        assertEquals(Phase.CALM,cycle.view(1234).phase());
        long now=1234+48000L*1000001;
        var notices=cycle.advance(now,true);assertEquals(1,notices.size());
        assertEquals(Phase.HEAT,notices.getFirst().snapshot().phase());
        assertEquals(1000001,cycle.lastAnnouncedPhase());assertTrue(cycle.advance(now,true).isEmpty());
    }
    @Test void readsAndOfflineCallsDoNotConsumeNotices() {
        var cycle=new ClimateCycle(0,48000);
        cycle.view(0);cycle.forecast(0,4);assertTrue(cycle.advance(0,false).isEmpty());
        assertEquals(-1,cycle.lastAnnouncedPhase());assertEquals(-1,cycle.lastWarnedPhase());
        assertEquals(NoticeKind.PHASE,cycle.advance(0,true).getFirst().kind());
        assertTrue(cycle.advance(0,true).isEmpty());
    }
    @Test void forecastNoticeOccursOnceAndMarkersSurviveRestart() {
        var cycle=new ClimateCycle(0,48000);cycle.advance(0,true);
        assertTrue(cycle.advance(41999,true).isEmpty());
        assertEquals(NoticeKind.FORECAST,cycle.advance(42000,true).getFirst().kind());
        var restored=ClimateCycle.restore(0,48000,cycle.lastAnnouncedPhase(),cycle.lastWarnedPhase(),42000);
        assertTrue(restored.advance(42001,true).isEmpty());
        assertEquals(NoticeKind.PHASE,restored.advance(48000,true).getFirst().kind());
        assertEquals(NoticeKind.FORECAST,restored.advance(90000,true).getFirst().kind());
    }
    @Test void boundedForecastIsOrderedAndDoesNotChangeMarkers() {
        var cycle=new ClimateCycle(100,48000);var windows=cycle.forecast(200,4);
        assertEquals(4,windows.size());assertEquals(Phase.HEAT,windows.getFirst().phase());
        assertEquals(48100,windows.getFirst().startTick());assertEquals(240100,windows.getLast().endTick());
        for(int i=1;i<4;i++)assertEquals(windows.get(i-1).endTick(),windows.get(i).startTick());
        assertEquals(-1,cycle.lastAnnouncedPhase());
        assertThrows(IllegalArgumentException.class,()->cycle.forecast(200,5));
    }
    @Test void invalidDurationsTimesAndMarkerOrderAreRejected() {
        for(long duration:new long[]{0,199,201,1920020})assertThrows(IllegalArgumentException.class,()->new ClimateCycle(0,duration));
        assertThrows(IllegalArgumentException.class,()->new ClimateCycle(-1,48000));
        assertThrows(IllegalArgumentException.class,()->new ClimateCycle(100,48000).view(99));
        assertThrows(IllegalArgumentException.class,()->ClimateCycle.restore(0,48000,1,0,0));
        assertThrows(IllegalArgumentException.class,()->ClimateCycle.restore(0,48000,0,1,48000));
        assertThrows(ArithmeticException.class,()->new ClimateCycle(Long.MAX_VALUE-10,48000).view(Long.MAX_VALUE-10));
    }
    @Test void shortProfileUsesSameBoundaryAndWarningRules() {
        var cycle=new ClimateCycle(0,200);cycle.advance(0,true);
        assertTrue(cycle.advance(149,true).isEmpty());assertEquals(NoticeKind.FORECAST,cycle.advance(150,true).getFirst().kind());
        assertEquals(Phase.HEAT,cycle.advance(200,true).getFirst().snapshot().phase());
    }
}
