package dev.deadearth.worldgen;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WastelandFieldTest {
    private final WastelandSettings settings=WastelandSettings.defaults();
    @Test void absoluteFieldIsDeterministicAcrossNegativeCoordinatesAndChunkOrder() {
        long seed=731991;var seen=new ArrayList<Integer>();
        for(int cx=-4;cx<=4;cx++)for(int cz=-4;cz<=4;cz++) {
            var candidates=WastelandField.candidates(seed,settings,cx,cz);assertTrue(candidates.size()<=18);
            var reverse=new ArrayList<>(candidates);Collections.reverse(reverse);
            for(int x=cx*16;x<cx*16+16;x+=3)for(int z=cz*16;z<cz*16+16;z+=3) {
                int expected=WastelandField.displacement(candidates,x,z);assertEquals(expected,WastelandField.displacement(reverse,x,z));
                assertEquals(expected,WastelandField.displacement(seed,settings,x,z));seen.add(expected);
            }
        }
        assertTrue(seen.stream().anyMatch(v->v<0));assertTrue(seen.stream().anyMatch(v->v>0));
    }
    @Test void smallCratersAreCommonAndLargeOnesRareButPresent() {
        var all=new HashSet<WastelandField.Crater>();
        for(int x=-64;x<64;x+=2)for(int z=-64;z<64;z+=2)all.addAll(WastelandField.candidates(42,settings,x,z));
        long large=all.stream().filter(WastelandField.Crater::large).count(),small=all.size()-large;
        assertTrue(large>=2);assertTrue(small>large*40);
        for(var c:all) {
            assertEquals(-c.depth(),WastelandField.displacement(List.of(c),c.x(),c.z()));
            assertEquals(0,WastelandField.displacement(List.of(c),c.x()+c.radius()*2,c.z()));
            assertTrue(c.depth()>0&&c.depth()<=22);assertTrue(c.radius()>=3&&c.radius()<=64);
        }
    }
    @Test void geometryAndPaletteRespectConfiguredBoundsAndVaryWithSeed() {
        var first=new ArrayList<String>();var second=new ArrayList<String>();var palette=EnumSet.noneOf(WastelandSettings.Surface.class);
        for(int x=-128;x<128;x+=3)for(int z=-128;z<128;z+=3) {
            int d=WastelandField.displacement(42,settings,x,z);assertTrue(d>=-22&&d<=3);
            var type=WastelandField.surface(42,settings,x,z);palette.add(type);
            first.add(d+"/"+type);second.add(WastelandField.displacement(43,settings,x,z)+"/"+WastelandField.surface(43,settings,x,z));
        }
        assertEquals(EnumSet.allOf(WastelandSettings.Surface.class),palette);assertNotEquals(first,second);
        assertTrue(palette.stream().allMatch(v->v.id().startsWith("minecraft:")||v.id().equals("dead_earth:ash")));
    }
    @Test void malformedConfigurationIsRejectedAndZeroChanceMeansNoCraters() {
        assertThrows(IllegalArgumentException.class,()->new WastelandSettings.Band(16,.5,3,12,1,2,1));
        assertThrows(IllegalArgumentException.class,()->new WastelandSettings.Band(24,Double.NaN,3,9,1,2,1));
        var off=new WastelandSettings.Band(256,0,3,9,1,2,1);
        var c=new WastelandSettings(off,off,0,0,3,9,3,16384,settings.waterColor(),settings.waterFogColor(),Map.of(WastelandSettings.Surface.STONE,1));
        assertTrue(WastelandField.candidates(42,c,-1,-1).isEmpty());
        assertEquals(WastelandSettings.Surface.STONE,WastelandField.surface(42,c,-20,-30));
    }
}
