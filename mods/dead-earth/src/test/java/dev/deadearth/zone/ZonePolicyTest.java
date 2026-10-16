package dev.deadearth.zone;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ZonePolicyTest {
    private ZoneTopology.Loader loader(String dim,int x,int z) {return new ZoneTopology.Loader(dim,x,70,z);}
    @Test void overlappingLoadersMergeWithoutCountingSharedChunksTwice() {
        var a=loader("overworld",0,0);var b=loader("overworld",16,0);
        var zones=ZoneTopology.build(List.of(a,b));
        assertEquals(1,zones.size());assertEquals(12,zones.getFirst().chunks().size());
        assertEquals(2,zones.getFirst().loaders().size());
        assertEquals(zones,ZoneTopology.build(List.of(b,a,a)));
    }
    @Test void touchingAndDifferentDimensionsRemainSeparateAndNegativeCoordinatesFloorCorrectly() {
        var a=loader("overworld",-1,-1);var b=loader("overworld",32,-1);var c=loader("nether",-1,-1);
        assertEquals(3,ZoneTopology.build(List.of(a,b,c)).size());
        assertTrue(ZoneTopology.build(List.of(a)).getFirst().chunks().contains(ZoneTopology.chunk(-2,-2)));
    }
    @Test void transitiveOverlapProducesStableIdentity() {
        var a=loader("overworld",0,0);var b=loader("overworld",32,0);var c=loader("overworld",64,0);
        var zone=ZoneTopology.build(List.of(c,b,a)).getFirst();
        assertEquals(21,zone.chunks().size());
        assertEquals(zone.id(),ZoneTopology.build(List.of(a,b,c)).getFirst().id());
    }
    @Test void exactRequestedFormulaAllowsNegativeAndUnknownDoesNotPretendToBeEmpty() {
        var score=new ZoneScore();score.stock(8192,4);score.turret(ZoneScore.Defense.ARMED,5);
        score.turret(ZoneScore.Defense.EMPTY,2);score.turret(ZoneScore.Defense.UNKNOWN,100);
        score.turret(ZoneScore.Defense.DISABLED,20);score.stock(0,64);
        assertEquals(32768,score.emc());assertEquals(-3,score.value(0),0);
        assertEquals(.2768,score.value(.0001),.000001);assertEquals(64,score.unpricedItems());
        assertEquals(1,score.unknownTurrets());
    }
    @Test void valuesSaturateWithoutWrappingAndInvalidInputsAreRejected() {
        var score=new ZoneScore();score.stock(Long.MAX_VALUE,2);score.stock(8192,64);
        assertEquals(Long.MAX_VALUE,score.emc());assertTrue(score.overflow());
        assertTrue(Double.isFinite(score.value(100)));
        assertThrows(IllegalArgumentException.class,()->score.stock(1,-1));
        assertThrows(IllegalArgumentException.class,()->score.value(Double.NaN));
    }
}
