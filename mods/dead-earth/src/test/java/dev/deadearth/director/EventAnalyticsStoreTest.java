package dev.deadearth.director;

import java.util.UUID;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import com.google.gson.Gson;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtAccounter;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class EventAnalyticsStoreTest {
    private EventAnalyticsStore filled(UUID campaign,EventAnalytics.Settings settings,String key) {
        var store=EventAnalyticsStore.create(campaign,settings);
        for(int i=0;i<settings.maxOperations();i++) {
            var id=new UUID(0,i+1);
            store.analytics().open(id,"false_siege",new UUID(1,i+1).toString(),0,10000,"WAITING","complete",new EventAnalytics.Point("minecraft:overworld",i,64,0));
            for(int j=0;j<settings.maxTimeline();j++)store.analytics().action(id,j+1,"sound",key,"approach",1,null,false);
        }
        return store;
    }
    private CompoundTag compressedRoundTrip(CompoundTag tag) throws Exception {
        var output=new ByteArrayOutputStream();NbtIo.writeCompressed(tag,output);
        return NbtIo.readCompressed(new ByteArrayInputStream(output.toByteArray()),NbtAccounter.unlimitedHeap());
    }
    @Test void defaultFilledLedgerSurvivesActualCompressedNbtBeyondUtfLimit() throws Exception {
        var campaign=UUID.randomUUID();var settings=EventAnalytics.Settings.defaults();
        var store=filled(campaign,settings,"ш".repeat(120));
        assertTrue(new Gson().toJson(store.analytics().snapshot()).getBytes(StandardCharsets.UTF_8).length>65535);
        var loaded=EventAnalyticsStore.load(campaign,settings,compressedRoundTrip(store.save(new CompoundTag(),null)),null);
        assertTrue(loaded.enabled(),"Native NBT must preserve a default filled ledger beyond writeUTF limits");
        assertEquals(settings.maxOperations(),loaded.analytics().snapshot().operations().size());
        assertEquals(settings.maxTimeline(),loaded.analytics().snapshot().operations().getFirst().timeline().size());
    }
    @Test void maximumLedgerLargerThanCatalogueLimitSurvivesNativeNbtAndStrictLoad() throws Exception {
        var campaign=UUID.randomUUID();
        var settings=new EventAnalytics.Settings(128,128,64,20,96,6,4,600,1,20,0,1,2,3);
        var store=filled(campaign,settings,"x".repeat(128));
        var bytes=new Gson().toJson(store.analytics().snapshot()).getBytes(StandardCharsets.UTF_8);
        assertTrue(bytes.length>1024*1024);assertTrue(bytes.length<8*1024*1024);
        assertTrue(EventAnalyticsStore.load(campaign,settings,store.save(new CompoundTag(),null),null).enabled(),"Analytics parser must accept its own bounded ledger beyond catalogue size");
        var loaded=EventAnalyticsStore.load(campaign,settings,compressedRoundTrip(store.save(new CompoundTag(),null)),null);
        assertTrue(loaded.enabled());assertEquals(128,loaded.analytics().snapshot().operations().size());
    }
    @Test void smallLegacyStringLedgerStillLoads() {
        var campaign=UUID.randomUUID();var store=EventAnalyticsStore.create(campaign,EventAnalytics.Settings.defaults());
        var old=new CompoundTag();old.putInt("schema",1);old.putString("campaign",campaign.toString());
        old.putString("ledger",new Gson().toJson(store.analytics().snapshot()));
        assertTrue(EventAnalyticsStore.load(campaign,EventAnalytics.Settings.defaults(),old,null).enabled());
    }

    @Test void separateSaveKeepsMeasurementsWeightsAndSequenceOnRestart() {
        var campaign=UUID.randomUUID();var op=UUID.randomUUID();var player=UUID.randomUUID();var defaults=EventAnalytics.Settings.defaults();
        var store=EventAnalyticsStore.create(campaign,defaults);var a=store.analytics();var point=new EventAnalytics.Point("minecraft:overworld",0,64,0);
        a.open(op,"false_siege",UUID.randomUUID().toString(),0,1000,"WAITING","complete",point);a.damage(5,player,point,null,4,2);a.death(6,player,point,null);
        var changed=new EventAnalytics.Settings(32,48,32,20,96,6,4,600,10,100,5,1,2,3);
        var loaded=EventAnalyticsStore.load(campaign,changed,store.save(new CompoundTag(),null),null);
        assertTrue(loaded.enabled());var event=loaded.analytics().view("ready").operations().getFirst();
        assertEquals(24,event.damage().points());assertEquals(1,event.damage().pointsWeights().hpWeight());
        assertEquals(10,loaded.analytics().view("ready").scoring().hpWeight());
        assertEquals(a.view("ready").operations().getFirst().timeline(),event.timeline());assertEquals("partial",event.coverage());
    }
    @Test void foreignUnknownAndMalformedFilesKeepOriginalNbtAndAreNotDirty() {
        var campaign=UUID.randomUUID();var settings=EventAnalytics.Settings.defaults();var raw=EventAnalyticsStore.create(campaign,settings).save(new CompoundTag(),null);
        for(int kind=0;kind<5;kind++) {
            var tag=raw.copy();var expected=campaign;
            if(kind==0)tag.putInt("schema",99);if(kind==1)expected=UUID.randomUUID();
            if(kind==2)tag.putByteArray("ledger","{\"operations\":[],\"unknown\":1}".getBytes(StandardCharsets.UTF_8));
            if(kind==3)tag.putByteArray("ledger","{\"operations\":[],\"operations\":[]}".getBytes(StandardCharsets.UTF_8));
            if(kind==4)tag.putByteArray("ledger",new byte[]{(byte)0xc3,0x28});
            var loaded=EventAnalyticsStore.load(expected,settings,tag,null);assertFalse(loaded.enabled());assertFalse(loaded.isDirty());assertEquals(tag,loaded.save(new CompoundTag(),null));
        }
    }
}
