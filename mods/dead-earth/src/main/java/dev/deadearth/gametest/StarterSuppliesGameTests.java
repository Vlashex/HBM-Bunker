package dev.deadearth.gametest;

import dev.deadearth.content.ZoneLoaderContent;
import dev.deadearth.starter.StarterStock;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.*;
import net.minecraft.resources.*;
import net.minecraft.world.Container;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.storage.loot.*;
import net.neoforged.neoforge.gametest.*;

@GameTestHolder("dead_earth_starter") @PrefixGameTestTemplate(false)
public final class StarterSuppliesGameTests {
    private static int count(Container c,Item item){int n=0;for(int slot=0;slot<c.getContainerSize();slot++)if(c.getItem(slot).is(item))n+=c.getItem(slot).getCount();return n;}
    @GameTest(template="empty",batch="shared_starter",timeoutTicks=200)
    public static void nativeStarterLootGivesOneTeamStockAcrossSixPlayersAndDiskReload(GameTestHelper h) {
        var level=h.getLevel();var server=level.getServer();var base=new BlockPos(2240008,220,2240008);
        var factory=new SavedData.Factory<>(StarterStock::create,StarterStock::load);
        var clients=new ArrayList<FoundationGameTests.AttachedClient>();var chests=new ArrayList<ChestBlockEntity>();
        try {
            level.getChunkAt(base);level.getChunkAt(base.east(16));
            for(int n=0;n<8;n++) {
                var pos=base.east(n*2);level.setBlock(pos,Blocks.CHEST.defaultBlockState(),3);
                var chest=(ChestBlockEntity)level.getBlockEntity(pos);chests.add(chest);
                if(n==7)for(int slot=0;slot<20;slot++)chest.setItem(slot,new ItemStack(Items.GOLD_BLOCK,64));
                chest.setLootTable(n==0?BuiltInLootTables.SIMPLE_DUNGEON:ResourceKey.create(Registries.LOOT_TABLE,ResourceLocation.parse("outpostzero:chests/hub_starter")));
            }
            for(int n=0;n<6;n++)clients.add(FoundationGameTests.attach(h));
            var stock=dev.deadearth.campaign.SavedDataAccess.open(server.overworld(),StarterStock.NAME,factory);
            h.assertFalse(stock.claimed(),"Stock was issued merely because players joined");
            chests.getFirst().unpackLootTable(clients.getFirst().player());
            h.assertFalse(stock.claimed(),"Unrelated dungeon loot consumed the starter stock");
            chests.get(7).unpackLootTable(clients.getFirst().player());
            h.assertFalse(stock.claimed(),"Cramped chest consumed stock it could not hold");
            h.assertTrue(count(chests.get(7),Items.GOLD_BLOCK)==1280,"Existing chest contents were overwritten");
            chests.get(1).unpackLootTable(clients.getFirst().player());
            h.assertTrue(count(chests.get(1),ZoneLoaderContent.ITEM.get())==6,"First starter chest did not contain exactly six loaders");
            h.assertTrue(count(chests.get(1),Items.DIRT)==256,"Starter soil was lost or truncated");
            for(var tree:List.of(Items.OAK_SAPLING,Items.SPRUCE_SAPLING,Items.BIRCH_SAPLING,Items.JUNGLE_SAPLING,Items.ACACIA_SAPLING,Items.DARK_OAK_SAPLING,Items.CHERRY_SAPLING,Items.MANGROVE_PROPAGULE))
                h.assertTrue(count(chests.get(1),tree)==4,"Starter saplings missing: "+tree);
            h.assertTrue(chests.get(1).getLootTable()==null,"Native chest loot was not consumed");
            // Re-read the acknowledged receipt from the real world file, not an in-memory boolean.
            var disk=new net.minecraft.world.level.storage.DimensionDataStorage(
                server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).resolve("data").toFile(),server.getFixerUpper(),level.registryAccess());
            stock=disk.get(factory,StarterStock.NAME);
            h.assertTrue(stock!=null&&stock.enabled()&&stock.claimed(),"World receipt did not survive disk reload");
            server.overworld().getDataStorage().set(StarterStock.NAME,stock);
            for(int n=1;n<6;n++)chests.get(n+1).unpackLootTable(clients.get(n).player());
            int total=chests.stream().mapToInt(c->count(c,ZoneLoaderContent.ITEM.get())).sum();
            h.assertTrue(total==6,"Each player or chest received another starter stock");
            for(var client:clients)h.assertTrue(count(client.player().getInventory(),ZoneLoaderContent.ITEM.get())==0,"Stock was given to personal inventory");
            chests.get(1).unpackLootTable(clients.getFirst().player());
            h.assertTrue(count(chests.get(1),ZoneLoaderContent.ITEM.get())==6,"Reopening a chest duplicated supplies");
        } finally {for(var c:clients)c.close();for(var c:chests)level.removeBlock(c.getBlockPos(),false);}
        h.succeed();
    }
}
