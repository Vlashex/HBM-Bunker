package dev.deadearth.gametest;

import com.google.gson.*;
import dev.deadearth.DeadEarth;
import dev.deadearth.director.*;
import dev.deadearth.operator.CatalogStaging;
import java.nio.file.*;
import java.util.*;
import net.minecraft.gametest.framework.*;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.gametest.*;

@GameTestHolder("dead_earth_cinematic_review") @PrefixGameTestTemplate(false)
public final class OperatorAuthoringGameTests {
    @GameTest(template="empty",batch="operator_contract")
    public static void statusDoesNotCreateGoalAndStaleEpochRevisionCannotMutateQueue(GameTestHelper h)throws Exception {
        var server=h.getLevel().getServer();var runtime=DeadEarth.runtime(server);var director=runtime.cinematics();
        var field=CinematicDirector.class.getDeclaredField("store");field.setAccessible(true);var store=(CinematicStore)field.get(director);
        var before=store.engine().snapshot();var status=director.operatorStatus();
        h.assertTrue(status.protocol()==1&&status.worldEpoch().equals(runtime.epoch()),"Operator protocol/world epoch missing");
        h.assertTrue(status.sourceHash().matches("[0-9a-f]{64}"),"Loaded exact source hash missing");
        h.assertTrue(before.equals(store.engine().snapshot()),"Read-only operator status created a goal or changed queue");
        var stale=director.operatorRun(UUID.randomUUID(),status.catalogRevision(),UUID.randomUUID(),"spore_lure",UUID.randomUUID().toString());
        h.assertTrue(!stale.accepted()&&stale.code().equals("stale_epoch"),"Old epoch was admitted");
        stale=director.operatorRun(status.worldEpoch(),"stale",UUID.randomUUID(),"spore_lure",UUID.randomUUID().toString());
        h.assertTrue(!stale.accepted()&&stale.code().equals("stale_catalog"),"Old catalog revision was admitted");
        h.assertTrue(before.equals(store.engine().snapshot()),"Rejected operator run changed queue");
        var branch=server.getCommands().getDispatcher().getRoot().getChild("deadearth").getChild("operator");
        h.assertTrue(branch!=null&&branch.getChildren().stream().map(n->n.getName()).collect(java.util.stream.Collectors.toSet()).equals(Set.of("status","preview","run","cancel","validate","apply")),"Unexpected operator command surface");h.succeed();
    }
    @GameTest(template="empty",batch="operator_catalog")
    public static void stagedCatalogChecksRegistryAndFailedApplyKeepsLoadedSource(GameTestHelper h)throws Exception {
        var director=DeadEarth.runtime(h.getLevel().getServer()).cinematics();var before=director.operatorStatus();
        var root=FMLPaths.CONFIGDIR.get().resolve("dead-earth/director");Files.createDirectories(root.resolve("staging"));var stage=UUID.randomUUID();var file=root.resolve("staging/"+stage+".json");
        try {
            String source;try(var in=OperatorAuthoringGameTests.class.getResourceAsStream("/data/dead_earth/director/catalog.json")){source=new String(in.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);}
            var raw=JsonParser.parseString(source).getAsJsonObject();raw.getAsJsonObject("sounds").entrySet().iterator().next().getValue().getAsJsonObject().addProperty("sound","dead_earth:missing_operator_sound");Files.writeString(file,raw.toString());
            var verdict=director.operatorValidate(before.worldEpoch(),stage);h.assertTrue(!verdict.accepted()&&verdict.code().equals("invalid_catalog"),"Unknown registry sound was validated");
            verdict=director.operatorApply(before.worldEpoch(),before.catalogRevision(),stage);h.assertTrue(!verdict.accepted(),"Invalid staged catalog was applied");
            h.assertTrue(before.sourceHash().equals(director.operatorStatus().sourceHash())&&before.catalogRevision().equals(director.operatorStatus().catalogRevision()),"Failed apply changed loaded catalog");
        }finally{Files.deleteIfExists(file);}h.succeed();
    }
    @GameTest(template="empty",batch="operator_publication")
    public static void successfulPublicationChangesCatalogAndKeepsInflightFrozenGraph(GameTestHelper h)throws Exception {
        var director=DeadEarth.runtime(h.getLevel().getServer()).cinematics();var before=director.operatorStatus();
        var sourceField=CinematicDirector.class.getDeclaredField("catalogSource");sourceField.setAccessible(true);var loaded=(CatalogStaging.Document)sourceField.get(director);
        var storeField=CinematicDirector.class.getDeclaredField("store");storeField.setAccessible(true);var store=(CinematicStore)storeField.get(director);
        var engineField=CinematicStore.class.getDeclaredField("engine");engineField.setAccessible(true);var previous=store.engine();var engine=new CinematicEngine();engineField.set(store,engine);
        var root=FMLPaths.CONFIGDIR.get().resolve("dead-earth/director");var file=root.resolve("catalog.json");byte[] original=Files.exists(file)?Files.readAllBytes(file):null;
        var stage=UUID.randomUUID();Files.createDirectories(root.resolve("staging"));var staged=root.resolve("staging/"+stage+".json");
        try {
            var raw=JsonParser.parseString(loaded.source()).getAsJsonObject();var event=raw.getAsJsonArray("events").get(0).getAsJsonObject();String eventId=event.get("id").getAsString();
            var zone=UUID.randomUUID().toString();var definition=loaded.catalog().events().get(eventId);
            var context=new CinematicEngine.Context(before.activeTicks(),2,definition.seasons().iterator().next(),1,List.of(new CinematicEngine.Zone(zone,100,0,0,0,true,false,2,3,0)));
            h.assertTrue(engine.admitManual(UUID.randomUUID(),eventId,zone,loaded.catalog(),context,true).accepted(),"Frozen fixture not admitted");
            var operation=engine.operations().getFirst();String frozen=operation.frozenCatalog();
            event.addProperty("title","Operator publication test");Files.writeString(staged,raw.toString());
            var validate=director.operatorValidate(before.worldEpoch(),stage);h.assertTrue(validate.accepted(),"Valid staged full catalog rejected");
            h.assertTrue(!director.operatorApply(before.worldEpoch(),"outdated",stage).accepted(),"Stale catalog apply accepted");
            var applied=director.operatorApply(before.worldEpoch(),before.catalogRevision(),stage);h.assertTrue(applied.accepted(),"Atomic publication failed: "+applied.code());
            h.assertTrue(validate.revision().equals(director.operatorStatus().catalogRevision())&&validate.sourceHash().equals(director.operatorStatus().sourceHash()),"Publication returned a different catalog than validation");
            h.assertTrue(frozen.equals(engine.operations().getFirst().frozenCatalog())&&engine.frozen(operation).events().get(eventId).title().equals(definition.title()),"Inflight event graph was replaced");
            h.assertTrue(Files.isDirectory(root.resolve("backups")),"Original source was not backed up");
        }finally {
            engineField.set(store,previous);if(original==null)Files.deleteIfExists(file);else Files.write(file,original);Files.deleteIfExists(staged);director.reload();
        }h.succeed();
    }
}
