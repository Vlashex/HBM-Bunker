package dev.deadearth.director;

import com.google.gson.JsonParser;
import dev.deadearth.operator.CatalogStaging;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class OperatorAuthoringTest {
    @TempDir Path directory;
    private static String outsideShortlist(CinematicEngine engine,CinematicCatalog catalog,CinematicEngine.Context context) {
        var listed=engine.options(catalog,context).stream().map(CinematicEngine.Option::eventId).toList();
        return catalog.events().values().stream().filter(e->!e.heavy()&&e.seasons().contains(context.season())&&e.minScore()<=100&&e.minPlayers()<=2)
            .map(CinematicCatalog.Event::id).filter(id->!listed.contains(id)).findFirst().orElseThrow();
    }
    @Test void manualPreviewAndAdmissionSelectAnEligibleEventOutsideModelShortlist()throws Exception {
        var engine=new CinematicEngine();var catalog=CinematicEngineTest.catalog();var context=CinematicEngineTest.context(0,"CALM");
        var event=outsideShortlist(engine,catalog,context);var before=engine.snapshot();
        var preview=engine.previewManual(catalog,context,true,event,CinematicEngineTest.ZONE);
        assertTrue(preview.accepted(),preview.code());assertEquals(event,preview.option().eventId());assertEquals(before,engine.snapshot());
        var result=engine.admitManual(UUID.randomUUID(),event,CinematicEngineTest.ZONE,catalog,context,true);
        assertTrue(result.accepted(),result.code());assertEquals(preview.option().budget(),engine.operations().getFirst().budget());
        assertEquals(event,engine.operations().getFirst().eventId());
    }
    @Test void manualAdmissionRecomputesBudgetAndRequestReplayNeverQueuesTwice()throws Exception {
        var engine=new CinematicEngine();var catalog=CinematicEngineTest.catalog();var context=CinematicEngineTest.context(0,"CALM");
        var event=outsideShortlist(engine,catalog,context);var preview=engine.previewManual(catalog,context,true,event,CinematicEngineTest.ZONE);
        var hurt=new CinematicEngine.Context(100,2,"CALM",1.2,List.of(new CinematicEngine.Zone(CinematicEngineTest.ZONE,100,16,0,0,true,false,2,3,3)));
        var request=UUID.randomUUID();var result=engine.admitManual(request,event,CinematicEngineTest.ZONE,catalog,hurt,true);
        assertTrue(result.accepted());assertTrue(engine.operations().getFirst().budget()<preview.option().budget());
        var restored=CinematicEngine.restore(engine.snapshot());
        assertEquals(result.operationId(),restored.admitManual(request,event,CinematicEngineTest.ZONE,catalog,hurt,true).operationId());
        assertFalse(restored.admitManual(request,"different",CinematicEngineTest.ZONE,catalog,hurt,true).accepted());assertEquals(1,restored.operations().size());
        var port=new CinematicEngineTest.Port();port.engine=restored;restored.cancel(result.operationId(),100,catalog.rules(),port);
        assertEquals("already_recorded",restored.admitManual(request,event,CinematicEngineTest.ZONE,catalog,hurt,true).code());assertTrue(restored.operations().isEmpty());
    }
    @Test void manualAdmissionPreservesRecoveryQueueAndAllZoneEligibilityRules()throws Exception {
        var catalog=CinematicEngineTest.catalog();var context=CinematicEngineTest.context(0,"CALM");var engine=new CinematicEngine();
        var event=outsideShortlist(engine,catalog,context);
        assertEquals("disabled",engine.previewManual(catalog,context,false,event,CinematicEngineTest.ZONE).code());
        var offline=new CinematicEngine.Context(0,0,"CALM",1,List.of());
        assertEquals("no_players",engine.previewManual(catalog,offline,true,event,CinematicEngineTest.ZONE).code());
        var recovering=CinematicEngine.restore(new CinematicEngine.Snapshot(List.of(),List.of(),0,200,0,0));
        assertEquals("recovery",recovering.admitManual(UUID.randomUUID(),event,CinematicEngineTest.ZONE,catalog,context,true).code());
        for(var zone:List.of(new CinematicEngine.Zone(CinematicEngineTest.ZONE,100,0,0,0,false,false,2,3,0),
                new CinematicEngine.Zone(CinematicEngineTest.ZONE,100,0,0,2401,true,false,2,3,0),
                new CinematicEngine.Zone(CinematicEngineTest.ZONE,Double.NaN,0,0,0,true,false,2,3,0),
                new CinematicEngine.Zone(CinematicEngineTest.ZONE,100,0,0,0,true,true,2,3,0)))
            assertFalse(engine.previewManual(catalog,new CinematicEngine.Context(0,2,"CALM",1,List.of(zone)),true,event,CinematicEngineTest.ZONE).accepted());
        for(int n=0;n<catalog.rules().maxQueued();n++) {
            String zone=UUID.randomUUID().toString();var next=new CinematicEngine.Context(0,2,"CALM",1.2,List.of(new CinematicEngine.Zone(zone,100,1,2,0,true,false,2,3,0)));
            assertTrue(engine.admitManual(UUID.randomUUID(),event,zone,catalog,next,true).accepted());
        }
        assertEquals("queue_full",engine.admitManual(UUID.randomUUID(),event,CinematicEngineTest.ZONE,catalog,context,true).code());
    }
    @Test void exactUtf8SourceHashIsDistinctFromSemanticRevision()throws Exception {
        String source=CinematicEngineTest.source();var parsed=CatalogStaging.parse(source);var spaced=CatalogStaging.parse(source+"\n");
        assertEquals(parsed.catalog().revision(),spaced.catalog().revision());assertNotEquals(parsed.sourceHash(),spaced.sourceHash());
        assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(source.getBytes(StandardCharsets.UTF_8))),parsed.sourceHash());
    }
    @Test void fullStagedCatalogRejectsMalformedGraphAndFailedApplyPreservesOriginal()throws Exception {
        var file=directory.resolve("catalog.json");String source=CinematicEngineTest.source();Files.writeString(file,source);
        var loaded=CatalogStaging.read(file);var stage=UUID.randomUUID();Files.createDirectories(directory.resolve("staging"));
        var raw=JsonParser.parseString(source).getAsJsonObject();var node=raw.getAsJsonArray("events").get(0).getAsJsonObject().getAsJsonArray("nodes").get(0).getAsJsonObject();
        node.addProperty("timeout",node.get("id").getAsString());Files.writeString(directory.resolve("staging/"+stage+".json"),raw.toString());
        assertThrows(IllegalArgumentException.class,()->CatalogStaging.stage(file,stage));assertEquals(source,Files.readString(file));
        var next=CatalogStaging.parse(source+"\n");Files.writeString(file,source+" ");
        assertThrows(CatalogStaging.SourceChangedException.class,()->CatalogStaging.publish(file,loaded,true,next));
        assertEquals(source+" ",Files.readString(file));
        assertFalse(Files.exists(directory.resolve("backups")));
    }
    @Test void successfulAtomicPublicationBacksUpExactSourceAndPreservesFrozenGraph()throws Exception {
        String source=CinematicEngineTest.source();var file=directory.resolve("catalog.json");Files.writeString(file,source);
        var loaded=CatalogStaging.read(file);var engine=new CinematicEngine();var context=CinematicEngineTest.context(0,"CALM");
        var event=outsideShortlist(engine,loaded.catalog(),context);assertTrue(engine.admitManual(UUID.randomUUID(),event,CinematicEngineTest.ZONE,loaded.catalog(),context,true).accepted());
        String frozen=engine.operations().getFirst().frozenCatalog();var next=CatalogStaging.parse(source+"\n");CatalogStaging.publish(file,loaded,true,next);
        assertEquals(source+"\n",Files.readString(file));try(var backups=Files.list(directory.resolve("backups"))) {assertEquals(source,Files.readString(backups.findFirst().orElseThrow()));}
        assertEquals(frozen,engine.operations().getFirst().frozenCatalog());assertEquals(loaded.catalog().events().get(event),engine.frozen(engine.operations().getFirst()).events().get(event));
    }
    @Test void backupFailureCannotReplaceTheCurrentCatalog()throws Exception {
        String source=CinematicEngineTest.source();var file=directory.resolve("catalog.json");Files.writeString(file,source);Files.writeString(directory.resolve("backups"),"blocked");
        assertThrows(java.io.IOException.class,()->CatalogStaging.publish(file,CatalogStaging.read(file),true,CatalogStaging.parse(source+"\n")));
        assertEquals(source,Files.readString(file));
    }
}
