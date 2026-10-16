package dev.deadearth.climate;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SeasonPolicyTest {
    @Test void exactUserMultipliersAreRelativeToBase() {
        var winter=SeasonPolicy.forPhase(ClimateCycle.Phase.ASH_WINTER);
        assertEquals(1.8,winter.parasites()); assertEquals(.25,winter.forest());
        var summer=SeasonPolicy.forPhase(ClimateCycle.Phase.HEAT);
        assertEquals(.5,summer.parasites()); assertEquals(1.5,summer.forest());
        assertEquals(1,SeasonPolicy.forPhase(ClimateCycle.Phase.CALM).parasites());
        assertEquals(1,SeasonPolicy.forPhase(ClimateCycle.Phase.CALM).forest());
        assertEquals(3.6,SeasonPolicy.scale(2,winter.parasites()),1e-9);
        assertEquals(1,SeasonPolicy.scale(2,summer.parasites()),1e-9);
    }
    @Test void shelterAndArmorRecoverInsteadOfAddingExposure() {
        assertEquals(7,Exposure.step(10,ClimateCycle.Phase.HEAT,false,true,false));
        assertEquals(-7,Exposure.step(-10,ClimateCycle.Phase.ASH_WINTER,true,true,true));
        assertEquals(0,Exposure.step(2,ClimateCycle.Phase.CALM,true,true,false));
        assertEquals(11,Exposure.step(10,ClimateCycle.Phase.HEAT,true,true,false));
        assertEquals(-12,Exposure.step(-10,ClimateCycle.Phase.ASH_WINTER,true,false,false));
        assertEquals(7,Exposure.step(10,ClimateCycle.Phase.HEAT,true,false,false));
    }
    @Test void exposureIsBoundedAndRejectsNonFiniteModifiers() {
        assertEquals(60,Exposure.step(60,ClimateCycle.Phase.HEAT,true,true,false));
        assertEquals(-60,Exposure.step(-60,ClimateCycle.Phase.ASH_WINTER,true,true,false));
        assertThrows(IllegalArgumentException.class,()->SeasonPolicy.scale(1,Double.NaN));
        assertThrows(IllegalArgumentException.class,()->SeasonPolicy.scale(Double.POSITIVE_INFINITY,1));
    }
}
