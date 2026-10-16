package dev.deadearth.climate;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.util.HashSet;

class WinterTerrainTest {
    private static final WinterTerrain.Settings S=new WinterTerrain.Settings(24,2,1,1,48,.25,4,8,3,6);
    @Test void disablingIceDoesNotPunchHolesInSnowAndZeroDisablesMaterialRelief() {
        var r=new WinterTerrain.Relief(2,1,1,8);
        var snow=WinterTerrain.thickness(r,0,1,1,1,true);
        assertEquals(0,snow.topIce());assertEquals(2,snow.topSnow());
        var ice=WinterTerrain.thickness(r,2,0,0,0,true);
        assertEquals(12,ice.topIce());assertEquals(0,ice.topSnow());assertEquals(0,ice.sideIce());assertEquals(0,ice.sideSnow());
        assertEquals(0,WinterTerrain.thickness(r,2,1,1,1,true).topSnow());
        assertEquals(2,WinterTerrain.thickness(r,2,1,1,1,false).topSnow());
    }
    @Test void sameWinterIsStableAcrossRestartAndChunkOrder() {
        long seed=WinterTerrain.seed(2026101610L,3);
        var a=WinterTerrain.sample(seed,-100003,20019,S);
        for(int i=0;i<100;i++)WinterTerrain.sample(seed,i*16,-i*16,S);
        assertEquals(a,WinterTerrain.sample(WinterTerrain.seed(2026101610L,3),-100003,20019,S));
        assertNotEquals(seed,WinterTerrain.seed(2026101610L,4));
    }
    @Test void reliefIsSmoothBoundedAndPeaksAreRareButPresent() {
        long seed=WinterTerrain.seed(2026101610L,0);int peaked=0;var ice=new HashSet<Integer>();
        for(int x=-128;x<128;x++)for(int z=-128;z<128;z++) {
            var p=WinterTerrain.sample(seed,x,z,S);ice.add(p.iceExtra());
            assertTrue(p.iceExtra()>=0&&p.iceExtra()<=2&&p.snowExtra()>=0&&p.snowExtra()<=1&&p.sideExtra()>=0&&p.sideExtra()<=1);
            assertTrue(p.peakExtra()>=0&&p.peakExtra()<=8);
            if(p.peakExtra()>0)peaked++;
            assertTrue(Math.abs(p.iceExtra()-WinterTerrain.sample(seed,x+1,z,S).iceExtra())<=1);
        }
        assertEquals(3,ice.size());assertTrue(peaked>0&&peaked<65536*.04,"Peaks must occupy a small fraction of terrain");
    }
    @Test void differentWinterChangesMapAndDisablingReliefIsFlat() {
        long a=WinterTerrain.seed(91,0),b=WinterTerrain.seed(91,1);int changed=0;
        var flat=new WinterTerrain.Settings(24,0,0,0,48,0,4,8,3,6);
        for(int x=-64;x<64;x+=4)for(int z=-64;z<64;z+=4) {
            if(!WinterTerrain.sample(a,x,z,S).equals(WinterTerrain.sample(b,x,z,S)))changed++;
            assertEquals(new WinterTerrain.Relief(0,0,0,0),WinterTerrain.sample(a,x,z,flat));
        }
        assertTrue(changed>500,"A new winter must produce a visibly different map");
    }
}
