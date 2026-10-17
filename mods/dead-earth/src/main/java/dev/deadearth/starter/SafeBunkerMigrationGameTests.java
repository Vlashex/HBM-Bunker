package dev.deadearth.starter;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("dead_earth_starter") @PrefixGameTestTemplate(false)
public final class SafeBunkerMigrationGameTests {
    @GameTest(template="empty",batch="safe_bunker_migration",timeoutTicks=400)
    @SuppressWarnings({"unchecked","rawtypes"})
    public static void readOnlyAdapterRecoversActualNativePlanEquipmentAndHatch(GameTestHelper h)throws Exception {
        var level=h.getLevel();Class<?> kindType=Class.forName("dev.mostlyharmless.outpostzero.world.bunker.BunkerKind");
        Object kind=Enum.valueOf((Class)kindType,"STARTER");
        // No capture scope: this reproduces a pre-v5 bunker with only native identity available.
        Object placed=Class.forName("dev.mostlyharmless.outpostzero.world.bunker.BunkerPlacer")
            .getMethod("placeBunker",ServerLevel.class,BlockPos.class,kindType).invoke(null,level,new BlockPos(2560008,40,2560008),kind);
        Class<?> placedType=placed.getClass();BlockPos hub=(BlockPos)placedType.getMethod("hubOrigin").invoke(placed);
        BlockPos top=(BlockPos)placedType.getMethod("hatchTopPos").invoke(placed);
        BlockPos spawn=(BlockPos)placedType.getMethod("barracksSpawnPos").invoke(placed);
        var spoke=(net.minecraft.core.Direction)placedType.getMethod("barracksSpokeDir").invoke(placed);
        Class<?> dataType=Class.forName("dev.mostlyharmless.outpostzero.world.bunker.StarterBunkerSavedData");
        Object nativeData=dataType.getConstructor().newInstance();
        dataType.getMethod("setPlaced",boolean.class).invoke(nativeData,true);
        dataType.getMethod("setFinalised",boolean.class).invoke(nativeData,true);
        dataType.getMethod("setOrigin",BlockPos.class).invoke(nativeData,hub);
        dataType.getMethod("setBarracksSpawn",BlockPos.class,String.class).invoke(nativeData,spawn,spoke.getName());
        var nativeTag=((net.minecraft.world.level.saveddata.SavedData)nativeData).save(new CompoundTag(),level.registryAccess());
        var before=nativeTag.copy();var recovery=SafeBunkerNative.recover(level,nativeTag);
        h.assertTrue(recovery!=null,"Native identity was not recognized");
        int passes=0;while(!recovery.advance(level,4096)){if(++passes>256)throw new IllegalStateException("Native recovery did not finish within bounded work");}
        h.assertTrue(nativeTag.equals(before),"Read-only migration mutated native data");
        h.assertTrue(recovery.boxes().size()>=7,"Actual native modules or shaft were not recovered");
        h.assertTrue(recovery.boxes().stream().anyMatch(box->box.contains(top.getX(),top.getY(),top.getZ())),"Native top outside migration bounds");
        h.assertTrue(recovery.originals().size()>1000,"Native construction was not recovered");
        h.assertTrue(recovery.originals().values().stream().anyMatch(id->List.of("minecraft:chest","outpostzero:crate").contains(id.toString())),"Native equipment missing after migration");
        h.succeed();
    }
}
