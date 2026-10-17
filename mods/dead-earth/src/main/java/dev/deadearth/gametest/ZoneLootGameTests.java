package dev.deadearth.gametest;

import dev.deadearth.zone.*;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.*;
import net.minecraft.world.level.storage.loot.BuiltInLootTables;
import net.neoforged.neoforge.gametest.*;

@GameTestHolder("dead_earth_loot_review") @PrefixGameTestTemplate(false)
public final class ZoneLootGameTests {
    @GameTest(template="empty",batch="zones_sealed_loot")
    public static void sealedLootDoesNotBlockCompleteZoneAndCountsOnlyAfterPlayerOpensIt(GameTestHelper h) {
        var level=h.getLevel();var base=new BlockPos(2100008,230,2100008);var barrelPos=base.east(2);
        for(int x=-1;x<=1;x++)for(int z=-1;z<=1;z++)level.getChunk(base.getX()/16+x,base.getZ()/16+z);
        level.setBlock(base,Blocks.CHEST.defaultBlockState(),3);level.setBlock(barrelPos,Blocks.BARREL.defaultBlockState(),3);
        var chest=(ChestBlockEntity)level.getBlockEntity(base);var barrel=(BarrelBlockEntity)level.getBlockEntity(barrelPos);
        chest.setItem(0,new ItemStack(Items.DIAMOND,4));barrel.setLootTable(BuiltInLootTables.SIMPLE_DUNGEON);
        var before=barrel.saveWithoutMetadata(level.registryAccess()).copy();
        var zone=ZoneTopology.build(List.of(new ZoneTopology.Loader(level.dimension().location().toString(),base.getX(),base.getY(),base.getZ())));
        var scanner=new ZoneScanner();
        try(var client=FoundationGameTests.attach(h)) {
            for(int tick=0;tick<100;tick++)scanner.tick(level.getServer(),zone);
            var report=scanner.reports(level.getServer().getTickCount()).getFirst();
            h.assertTrue(report.finished()&&report.complete(),"Sealed random loot still blocks a fully observed zone");
            h.assertTrue(report.resourceEmc()==32768&&report.unknownContainers()==0,"Sealed loot affected known resource value");
            var summary=new com.google.gson.Gson().toJsonTree(scanner.summaries(level.getServer().getTickCount()).getFirst()).getAsJsonObject();
            h.assertTrue(summary.has("ignoredLootContainers")&&summary.get("ignoredLootContainers").getAsInt()==1,"Skipped sealed container is not reported");
            h.assertTrue(before.equals(barrel.saveWithoutMetadata(level.registryAccess()))&&barrel.getLootTable()!=null,"Valuation generated or changed loot");
            // Simulate an actual player opening this fixture, then use deterministic opened contents.
            barrel.unpackLootTable(client.player());barrel.clearContent();barrel.setItem(0,new ItemStack(Items.IRON_INGOT,16));
            scanner.request();for(int tick=0;tick<100;tick++)scanner.tick(level.getServer(),zone);
            var opened=scanner.reports(level.getServer().getTickCount()).getFirst();
            h.assertTrue(opened.complete()&&opened.resourceEmc()==36864,"Opened fixture inventory did not join zone value");
            h.assertTrue(barrel.getItem(0).getCount()==16,"Scanner consumed opened inventory");
        } finally {level.removeBlock(base,false);level.removeBlock(barrelPos,false);}
        h.succeed();
    }
}
