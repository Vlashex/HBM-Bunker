package dev.deadearth.gametest;

import dev.deadearth.DeadEarth;
import dev.deadearth.campaign.SavedDataAccess;
import dev.deadearth.starter.*;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.lang.reflect.Proxy;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.saveddata.SavedData;
import net.neoforged.neoforge.gametest.*;

@GameTestHolder("dead_earth_starter") @PrefixGameTestTemplate(false)
public final class HbmSafeBunkerGameTests {
    @GameTest(template="empty",batch="hbm_safe_bunker",timeoutTicks=200)
    public static void nativeParallelNukeCannotCarveProtectedBlocksFromSnapshot(GameTestHelper h) throws Exception {
        var level=h.getLevel();var server=level.getServer();var runtime=DeadEarth.runtime(server);
        var factory=new SavedData.Factory<>(()->SafeBunkerStore.create(runtime.campaign().campaign().campaignId(),216000),SafeBunkerStore::load);
        var previous=SavedDataAccess.open(server.overworld(),SafeBunkerStore.NAME,factory);
        var pos=new BlockPos(2480008,220,2480008);var other=pos.east();var chunk=level.getChunkAt(pos);
        try {
            level.setBlock(pos,Blocks.STONE_BRICKS.defaultBlockState(),2);
            level.setBlock(other,Blocks.STONE.defaultBlockState(),2);
            var store=SafeBunkerStore.create(runtime.campaign().campaign().campaignId(),216000);
            store.record(List.of(new SafeBunkerStore.Box(pos.getX(),pos.getY(),pos.getZ(),pos.getX(),pos.getY()+2,pos.getZ())),
                Map.of(pos.asLong(),BuiltInRegistries.BLOCK.getKey(Blocks.STONE_BRICKS)),"native_capture");
            server.overworld().getDataStorage().set(SafeBunkerStore.NAME,store);SafeBunker.close(server);SafeBunker.tick(server,runtime);
            var type=Class.forName("com.hbm.util.SectionSnapshot");
            int sectionIndex=level.getSectionIndex(pos.getY());
            var snapshot=type.getMethod("capture",LevelChunk.class,long.class).invoke(null,chunk,1L<<sectionIndex);
            var maskType=Class.forName("com.hbm.interfaces.BitMask");var bits=new BitSet(4096);
            bits.set(index(pos));bits.set(index(other));
            var mask=Proxy.newProxyInstance(maskType.getClassLoader(),new Class<?>[]{maskType},(proxy,method,args)->switch(method.getName()) {
                case "get"->bits.get((int)args[0]);case "nextSetBit"->bits.nextSetBit((int)args[0]);
                case "isEmpty"->bits.isEmpty();case "cardinality"->(long)bits.cardinality();
                case "size","logicalSize"->4096;case "length"->bits.length();case "toLongArray"->bits.toLongArray();
                default->throw new UnsupportedOperationException(method.getName());
            });
            var util=Class.forName("com.hbm.util.ChunkUtil");
            var carve=Arrays.stream(util.getMethods()).filter(m->m.getName().equals("carveSnapshot")).findFirst().orElseThrow();
            var removed=new Long2ObjectOpenHashMap<BlockState>();
            carve.invoke(null,level,chunk.getPos().x,chunk.getPos().z,sectionIndex,snapshot,mask,null,removed);
            var sections=(LevelChunkSection[])type.getField("sections").get(snapshot);
            h.assertTrue(sections[sectionIndex].getBlockState(pos.getX()&15,pos.getY()&15,pos.getZ()&15).is(Blocks.STONE_BRICKS),"HBM parallel nuke bypassed safe bunker through section snapshot");
            h.assertTrue(sections[sectionIndex].getBlockState(other.getX()&15,other.getY()&15,other.getZ()&15).isAir(),"Protection incorrectly disabled the whole nuke section");
            h.assertFalse(removed.containsKey(pos.asLong()),"Protected block entered native destruction/drop ledger");
            var changes=new Long2ObjectOpenHashMap<BlockState>();changes.put(pos.asLong(),Blocks.AIR.defaultBlockState());
            var old=new Long2ObjectOpenHashMap<BlockState>();var deferred=new Long2ObjectOpenHashMap<BlockState>();
            Arrays.stream(util.getMethods()).filter(m->m.getName().equals("applyToSnapshot")).findFirst().orElseThrow().invoke(null,snapshot,changes,old,deferred);
            h.assertTrue(sections[sectionIndex].getBlockState(pos.getX()&15,pos.getY()&15,pos.getZ()&15).is(Blocks.STONE_BRICKS),"HBM replacement snapshot bypassed safe bunker");
            h.assertTrue(old.isEmpty()&&deferred.isEmpty(),"Rejected native change generated side effects");
        } finally {
            server.overworld().getDataStorage().set(SafeBunkerStore.NAME,previous);SafeBunker.close(server);SafeBunker.tick(server,runtime);
            level.removeBlock(pos,false);level.removeBlock(other,false);
        }
        h.succeed();
    }
    private static int index(BlockPos pos){return (pos.getX()&15)|((pos.getZ()&15)<<4)|((pos.getY()&15)<<8);}
}
