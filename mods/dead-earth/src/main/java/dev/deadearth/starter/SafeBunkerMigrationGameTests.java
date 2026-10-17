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
        var oldSteel=net.minecraft.resources.ResourceLocation.parse("outpostzero:steel_block");
        var v1=net.minecraft.resources.ResourceLocation.parse("dead_earth:defense_wall_v1");
        var walls=recovery.originals().entrySet().stream().filter(entry->entry.getValue().equals(v1)).map(java.util.Map.Entry::getKey).sorted().toList();
        h.assertTrue(walls.size()>100,"Native template replacement did not provide V1 walls for mixed legacy recovery fixture");
        var converted=new java.util.HashSet<Long>();
        dev.deadearth.defense.DefensePolicy.scoped(dev.deadearth.defense.DefensePolicy.Scope.ADMIN,()->{
            for(int n=0;n<walls.size();n+=2){long pos=walls.get(n);converted.add(pos);level.setBlock(BlockPos.of(pos),net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(oldSteel).defaultBlockState(),2);}
        });
        var mixed=SafeBunkerNative.recover(level,nativeTag);passes=0;
        while(!mixed.advance(level,4096)){if(++passes>256)throw new IllegalStateException("Mixed old/new recovery did not finish within bounded work");}
        h.assertTrue(converted.stream().allMatch(pos->oldSteel.equals(mixed.originals().get(pos))),"Recovery lost actual legacy identities during partial migration");
        h.assertTrue(mixed.originals().values().stream().anyMatch(v1::equals),"Mixed recovery did not retain actual V1 identities");
        h.assertTrue(SafeBunkerNative.equivalentWall(oldSteel,v1)&&SafeBunkerNative.equivalentWall(v1,oldSteel),"Exact legacy equivalence must work in both recovery directions");
        h.assertFalse(SafeBunkerNative.equivalentWall(net.minecraft.resources.ResourceLocation.parse("securitycraft:reinforced_glass"),v1),"Recovery guessed an unrelated reinforced material");
        h.assertTrue(nativeTag.equals(before),"Mixed recovery changed native identity/deadline data");
        h.succeed();
    }
}
