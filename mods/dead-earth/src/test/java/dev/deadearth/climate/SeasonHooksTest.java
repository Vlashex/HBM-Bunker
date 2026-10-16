package dev.deadearth.climate;
import dev.deadearth.compat.SeasonHooks;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SeasonHooksTest {
    @Test void futureCounterProgressUsesFractionalCarryWithoutChangingPast() {
        var data=new CompoundTag();data.putInt("Timer",100);
        for(int i=0;i<10;i++) data.putInt("Timer",SeasonHooks.counterRead(data,"Timer",1.8)+1);
        assertEquals(118,data.getInt("Timer"));
        for(int i=0;i<10;i++) data.putInt("Timer",SeasonHooks.counterRead(data,"Timer",.5)+1);
        assertEquals(123,data.getInt("Timer"));
        data.putInt("Timer",0);
        assertEquals(0,data.getInt("Timer"));
    }
    @Test void unrelatedKeysStayUnchangedAndCounterDoesNotOverflow() {
        var data=new CompoundTag();data.putInt("other",42);
        assertEquals(42,SeasonHooks.counterRead(data,"other",1.8));
        data.putInt("Timer",Integer.MAX_VALUE);
        assertEquals(Integer.MAX_VALUE,SeasonHooks.counterRead(data,"Timer",1.8)+1);
    }
    @Test void probabilityCopiesDoNotMutateNativeTable() {
        double[] nativeTable={.25,.5,.75,1};
        var winter=SeasonHooks.chances(nativeTable,.25);
        assertArrayEquals(new double[]{.0625,.125,.1875,.25},winter);
        assertArrayEquals(new double[]{.25,.5,.75,1},nativeTable);
        assertArrayEquals(new double[]{.375,.75,1,1},SeasonHooks.chances(nativeTable,1.5));
    }
}
