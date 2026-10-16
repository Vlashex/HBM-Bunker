package dev.deadearth.gametest;

import dev.deadearth.climate.ClimateCycle.Phase;
import dev.deadearth.climate.SeasonWeather;
import dev.deadearth.content.SeasonBlocks;
import dev.deadearth.content.SeasonalCoatingBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.*;

@GameTestHolder("dead_earth_review") @PrefixGameTestTemplate(false)
public final class WinterCoatingGameTests {
    private static void prepareFixture(GameTestHelper h,BlockPos base) {
        var level=h.getLevel();dev.deadearth.climate.WinterCoating.clearWinter(level);
        try {
            for(String name:new String[]{"ACTIVE","THAW"}) {
                var field=dev.deadearth.climate.WinterCoating.class.getDeclaredField(name);field.setAccessible(true);
                ((java.util.Map<?,?>)field.get(null)).remove(level);
            }
        } catch(ReflectiveOperationException error){throw new IllegalStateException("Winter fixture isolation failed",error);}
        for(int x=-1;x<=1;x++)for(int z=-1;z<=1;z++)level.getChunkAt(base.offset(x*16,0,z*16));
    }
    @GameTest(template="empty",batch="winter_cells")
    public static void exhaustedCellBudgetDefersRatherThanLosesAColumn(GameTestHelper h) {
        var level=h.getLevel();var o=h.absolutePos(new BlockPos(3,0,3));var base=new BlockPos(o.getX(),250,o.getZ());
        prepareFixture(h,base);
        try {
            level.setBlock(base,Blocks.STONE.defaultBlockState(),2|16);
            var budget=new dev.deadearth.climate.WinterCoating.Budget(24,2);
            h.assertTrue(budget.reserveCells(dev.deadearth.config.DeadEarthConfig.i(dev.deadearth.config.DeadEarthConfig.COATING_CELLS)),"Cannot reserve configured work limit");
            dev.deadearth.climate.WinterCoating.column(level,base.getX(),base.getZ(),true,budget);
            h.assertTrue(level.isEmptyBlock(base.above())&&budget.changes()==0,"Exhausted work budget still scans and builds coating");
            for(int tick=0;tick<100;tick++)dev.deadearth.climate.WinterCoating.drainActive(level,true,new dev.deadearth.climate.WinterCoating.Budget(24,2));
            h.assertTrue(level.getBlockState(base.above(3)).is(SeasonBlocks.SNOW.get()),"Deferred work never resumes with a fresh cell budget");
        } finally {for(var p:BlockPos.betweenClosed(base,base.above(38)))level.setBlock(p,Blocks.AIR.defaultBlockState(),2|16);}
        h.succeed();
    }
    @GameTest(template="empty",batch="winter_deep")
    public static void highCanopyDoesNotLeaveLowerTrunksAndForestFloorBare(GameTestHelper h) {
        var level=h.getLevel();var o=h.absolutePos(new BlockPos(3,0,3));var base=new BlockPos(o.getX(),200,o.getZ());var target=base.east();
        prepareFixture(h,base);
        try {
            for(var p:BlockPos.betweenClosed(base.offset(-2,0,-2),base.offset(2,0,2)))level.setBlock(p,Blocks.STONE.defaultBlockState(),2|16);
            for(int y=1;y<80;y++)level.setBlock(base.above(y),Blocks.OAK_LOG.defaultBlockState(),2|16);
            for(var p:BlockPos.betweenClosed(base.offset(-1,80,-1),base.offset(1,80,1)))level.setBlock(p,Blocks.OAK_LEAVES.defaultBlockState(),2|16);
            for(int pass=0;pass<160;pass++)SeasonWeather.surfaceColumn(level,target.getX(),target.getZ(),Phase.ASH_WINTER);
            h.assertTrue(level.getBlockState(target.above(20)).is(SeasonBlocks.RIME.get()),"High canopy leaves lower exposed trunk without ice");
            h.assertTrue(level.getBlockState(target.above(3)).is(SeasonBlocks.SNOW.get()),"High canopy prevents snow on its outdoor forest floor");
        } finally {for(var p:BlockPos.betweenClosed(base.offset(-3,0,-3),base.offset(3,110,3)))level.setBlock(p,Blocks.AIR.defaultBlockState(),2|16);}
        h.succeed();
    }
    @GameTest(template="empty",batch="winter_priority_review")
    public static void deferredOuterLayerRetriesAfterSectionBudgetRefusal(GameTestHelper h) {
        var level=h.getLevel();var o=h.absolutePos(new BlockPos(3,0,3));var base=new BlockPos(o.getX(),250,o.getZ());var occupied=base.above(40);
        prepareFixture(h,base);
        try {
            level.setBlock(base,Blocks.STONE.defaultBlockState(),2|16);
            for(int pass=0;pass<5;pass++)SeasonWeather.surfaceColumn(level,base.getX(),base.getZ(),Phase.ASH_WINTER);
            h.assertTrue(level.getBlockState(base.above()).getValue(SeasonalCoatingBlock.LAYERS)==8&&level.isEmptyBlock(base.above(2)),"Fixture must have a complete inner layer and no outer layer");
            var denied=new dev.deadearth.climate.WinterCoating.Budget(24,1);
            denied.write(level,occupied,Blocks.STONE.defaultBlockState());
            level.setBlock(occupied,Blocks.AIR.defaultBlockState(),2|16);
            // An earlier chunk section has consumed the allowed section slot.
            for(int pass=0;pass<40;pass++)dev.deadearth.climate.WinterCoating.drainActive(level,true,denied);
            for(int pass=0;pass<80;pass++)dev.deadearth.climate.WinterCoating.drainActive(level,true,new dev.deadearth.climate.WinterCoating.Budget(24,2));
            h.assertTrue(level.getBlockState(base.above(3)).is(SeasonBlocks.SNOW.get()),"Deferred next layer was dropped instead of retried");
        } finally {for(var p:BlockPos.betweenClosed(base.below(),base.above(40)))level.setBlock(p,Blocks.AIR.defaultBlockState(),2|16);}
        h.succeed();
    }
    @GameTest(template="empty",batch="winter_relief")
    public static void winterHeightFieldBuildsRealIcePeakAndRestoresSameProfile(GameTestHelper h) {
        var level=h.getLevel();var o=h.absolutePos(new BlockPos(3,0,3));var base=new BlockPos(o.getX(),250,o.getZ());
        prepareFixture(h,base);
        try {
            int winter=0;dev.deadearth.climate.WinterTerrain.Thickness profile=null;
            for(;winter<10000;winter++) {
                dev.deadearth.climate.WinterCoating.selectWinter(level,winter);
                profile=dev.deadearth.climate.WinterCoating.profile(level,base.getX(),base.getZ());
                if(profile.topSnow()==0&&profile.topIce()>=6)break;
            }
            h.assertTrue(winter<10000,"No deterministic rare peak found for physical fixture");
            var expected=profile;
            dev.deadearth.climate.WinterCoating.clearWinter(level);
            dev.deadearth.climate.WinterCoating.selectWinter(level,winter);
            h.assertTrue(expected.equals(dev.deadearth.climate.WinterCoating.profile(level,base.getX(),base.getZ())),"Restart changes the selected winter profile");
            level.setBlock(base,Blocks.STONE.defaultBlockState(),2|16);
            for(int pass=0;pass<160;pass++)SeasonWeather.surfaceColumn(level,base.getX(),base.getZ(),Phase.ASH_WINTER);
            var tip=level.getBlockState(base.above(expected.topIce()));
            h.assertTrue(tip.is(SeasonBlocks.RIME.get())&&tip.getValue(SeasonalCoatingBlock.LAYERS)==8,"Height field failed to build a real full ice peak");
            h.assertTrue(level.isEmptyBlock(base.above(expected.topIce()+1)),"Ice peak exceeds its deterministic height");
            for(int pass=0;pass<160;pass++)SeasonWeather.surfaceColumn(level,base.getX(),base.getZ(),Phase.CALM);
            h.assertTrue(level.isEmptyBlock(base.above())&&level.isEmptyBlock(base.above(expected.topIce())),"Peak does not thaw to air");
        } finally {
            dev.deadearth.climate.WinterCoating.clearWinter(level);
            for(var p:BlockPos.betweenClosed(base.below(),base.above(38)))level.setBlock(p,Blocks.AIR.defaultBlockState(),2|16);
        }
        h.succeed();
    }
    @GameTest(template="empty",batch="winter_coating")
    public static void snowCoversOrdinaryForestGrassButPreservesSaplings(GameTestHelper h) {
        var level=h.getLevel();var o=h.absolutePos(new BlockPos(3,0,3));var base=new BlockPos(o.getX(),250,o.getZ());var sapling=base.east(4);
        prepareFixture(h,base);
        try {
            level.setBlock(base,Blocks.STONE.defaultBlockState(),2|16);level.setBlock(base.above(),Blocks.SHORT_GRASS.defaultBlockState(),2|16);
            level.setBlock(sapling,Blocks.STONE.defaultBlockState(),2|16);level.setBlock(sapling.above(),Blocks.OAK_SAPLING.defaultBlockState(),2|16);
            for(int pass=0;pass<40;pass++) {
                SeasonWeather.surfaceColumn(level,base.getX(),base.getZ(),Phase.ASH_WINTER);
                SeasonWeather.surfaceColumn(level,sapling.getX(),sapling.getZ(),Phase.ASH_WINTER);
            }
            h.assertTrue(level.getBlockState(base.above(3)).is(SeasonBlocks.SNOW.get()),"Ordinary grass leaves a bare hole in the winter surface");
            h.assertTrue(level.getBlockState(sapling.above()).is(Blocks.OAK_SAPLING),"Winter destroys saplings");
        } finally {for(var p:BlockPos.betweenClosed(base.below(),sapling.above(8)))level.setBlock(p,Blocks.AIR.defaultBlockState(),2|16);}
        h.succeed();
    }
    @GameTest(template="empty",batch="winter_active",timeoutTicks=100)
    public static void activeColumnsFinishGrowthAndHiddenCoatingThawsWithoutFullWorldRescan(GameTestHelper h) {
        var level=h.getLevel();var o=h.absolutePos(new BlockPos(3,0,3));var base=new BlockPos(o.getX(),245,o.getZ());
        prepareFixture(h,base);
        level.setBlock(base,Blocks.STONE.defaultBlockState(),2|16);
        SeasonWeather.surfaceColumn(level,base.getX(),base.getZ(),Phase.ASH_WINTER);
        h.runAfterDelay(3,()->{
            try {
                for(int tick=0;tick<80;tick++)dev.deadearth.climate.WinterCoating.drainActive(level,true,new dev.deadearth.climate.WinterCoating.Budget(24,2));
                var top=level.getBlockState(base.above(3));
                h.assertTrue(top.is(SeasonBlocks.SNOW.get())&&top.getValue(SeasonalCoatingBlock.LAYERS)==8,"Active column needs another full world rescan to finish");
                level.setBlock(base.above(60),Blocks.STONE.defaultBlockState(),2|16);
                for(int tick=0;tick<30;tick++) {
                    for(int y=1;y<=3;y++)dev.deadearth.climate.WinterCoating.requestThaw(level,base.above(y));
                    dev.deadearth.climate.WinterCoating.drainActive(level,false,new dev.deadearth.climate.WinterCoating.Budget(24,2));
                }
                h.assertTrue(level.isEmptyBlock(base.above())&&level.isEmptyBlock(base.above(3)),"Roofed-over coating is trapped below the scan window");
            } finally {for(var p:BlockPos.betweenClosed(base,base.above(60)))level.setBlock(p,Blocks.AIR.defaultBlockState(),2|16);}
            h.succeed();
        });
    }
    @GameTest(template="empty",batch="winter_coating")
    public static void growthIsGradualAndSkipsOccupiedSpaceAndContainers(GameTestHelper h) {
        var level=h.getLevel();var o=h.absolutePos(new BlockPos(3,0,3));var base=new BlockPos(o.getX(),250,o.getZ());
        prepareFixture(h,base);
        var mob=net.minecraft.world.entity.EntityType.COW.create(level);
        try {
            level.setBlock(base,Blocks.STONE.defaultBlockState(),2|16);
            SeasonWeather.surfaceColumn(level,base.getX(),base.getZ(),Phase.ASH_WINTER);
            var first=level.getBlockState(base.above());
            h.assertTrue(first.is(SeasonBlocks.RIME.get())&&first.getValue(SeasonalCoatingBlock.LAYERS)==1,"First frost appears as a whole cube");
            h.assertTrue(level.isEmptyBlock(base.above(2)),"Outer layer grows before its support is complete");
            level.setBlock(base.above(),Blocks.AIR.defaultBlockState(),2|16);
            mob.moveTo(base.above().getBottomCenter());level.addFreshEntity(mob);
            SeasonWeather.surfaceColumn(level,base.getX(),base.getZ(),Phase.ASH_WINTER);
            h.assertTrue(level.isEmptyBlock(base.above()),"Coating intersects a living mob");
            mob.discard();level.setBlock(base.above(),Blocks.CHEST.defaultBlockState(),2|16);
            for(int pass=0;pass<5;pass++)SeasonWeather.surfaceColumn(level,base.getX(),base.getZ(),Phase.ASH_WINTER);
            h.assertTrue(level.getBlockState(base.above()).is(Blocks.CHEST)&&level.isEmptyBlock(base.above(2)),"Container is overwritten or used as an anchor");
        } finally {mob.discard();for(var p:BlockPos.betweenClosed(base.below(),base.above(6)))level.setBlock(p,Blocks.AIR.defaultBlockState(),2|16);}
        h.succeed();
    }
    @GameTest(template="empty",batch="winter_coating")
    public static void sharedBudgetCapsEditsAndChunkSections(GameTestHelper h) {
        var level=h.getLevel();var o=h.absolutePos(new BlockPos(3,0,3));var base=new BlockPos(o.getX(),240,o.getZ());
        prepareFixture(h,base);
        var budget=new dev.deadearth.climate.WinterCoating.Budget(2,1);
        var local=(base.getX()&15)==15?base.west():base.east();
        var third=(base.getZ()&15)==15?base.north():base.south();
        try {
            h.assertTrue(budget.write(level,base,Blocks.STONE.defaultBlockState()),"First write rejected");
            h.assertTrue(!budget.write(level,base.above(32),Blocks.STONE.defaultBlockState()),"Changed-section budget exceeded");
            h.assertTrue(budget.write(level,local,Blocks.STONE.defaultBlockState()),"Second local write rejected");
            h.assertTrue(!budget.write(level,third,Blocks.STONE.defaultBlockState())&&budget.changes()==2,"Global edit budget exceeded");
            var remote=new BlockPos(20000003,250,20000003);boolean before=level.hasChunkAt(remote);
            h.assertTrue(!before,"Test boundary unexpectedly loaded");
            SeasonWeather.surfaceColumn(level,remote.getX(),remote.getZ(),Phase.ASH_WINTER);
            h.assertTrue(!level.hasChunkAt(remote),"Winter force-loaded a remote chunk");
        } finally {for(var p:new BlockPos[]{base,local,third,base.above(32)})level.setBlock(p,Blocks.AIR.defaultBlockState(),2|16);}
        h.succeed();
    }
    @GameTest(template="empty",batch="winter_coating")
    public static void winterBuildsThickTopAndSideShellWithoutGrowingForever(GameTestHelper h) {
        var level=h.getLevel();var o=h.absolutePos(new BlockPos(3,0,3));var base=new BlockPos(o.getX(),250,o.getZ());
        prepareFixture(h,base);
        try {
            for(int y=0;y<6;y++) level.setBlock(base.above(y),Blocks.OAK_LOG.defaultBlockState(),2|16);
            for(int pass=0;pass<40;pass++) for(int x=-2;x<=2;x++) for(int z=-2;z<=2;z++)
                SeasonWeather.surfaceColumn(level,base.getX()+x,base.getZ()+z,Phase.ASH_WINTER);
            h.assertTrue(level.getBlockState(base.above(6)).isCollisionShapeFullBlock(level,base.above(6)),"Winter top is still a thin carpet");
            h.assertTrue(!level.isEmptyBlock(base.above(8)),"Winter top is less than three blocks thick");
            h.assertTrue(!level.isEmptyBlock(base.above(3).east())&&!level.isEmptyBlock(base.above(3).east(2)),"Exposed trunk sides have no thick coating");
            for(int pass=0;pass<20;pass++) for(int x=-2;x<=2;x++) for(int z=-2;z<=2;z++)
                SeasonWeather.surfaceColumn(level,base.getX()+x,base.getZ()+z,Phase.ASH_WINTER);
            h.assertTrue(level.isEmptyBlock(base.above(9)),"Repeated winter scans grow an unlimited tower");
            h.assertTrue(level.isEmptyBlock(base.above(3).east(3)),"Side coating grows beyond its original anchor");
            for(int pass=0;pass<40;pass++) for(int x=-2;x<=2;x++) for(int z=-2;z<=2;z++)
                SeasonWeather.surfaceColumn(level,base.getX()+x,base.getZ()+z,Phase.CALM);
            h.assertTrue(level.isEmptyBlock(base.above(6))&&level.isEmptyBlock(base.above(8))&&level.isEmptyBlock(base.above(3).east()),"Thaw leaves coating or floods the forest");
            h.assertTrue(level.getBlockState(base.above(3)).is(Blocks.OAK_LOG),"Coating replaced the original trunk");
        } finally {for(var p:BlockPos.betweenClosed(base.offset(-3,-1,-3),base.offset(3,12,3))) level.setBlock(p,Blocks.AIR.defaultBlockState(),2|16);}
        h.succeed();
    }
    @GameTest(template="empty",batch="winter_coating")
    public static void coatingReachesForestFloorButLeavesRoofedRoomsAlone(GameTestHelper h) {
        var level=h.getLevel();var o=h.absolutePos(new BlockPos(3,0,3));var base=new BlockPos(o.getX(),240,o.getZ());
        prepareFixture(h,base);
        try {
            for(var p:BlockPos.betweenClosed(base.offset(-2,0,-2),base.offset(2,0,2))) level.setBlock(p,Blocks.STONE.defaultBlockState(),2|16);
            for(var p:BlockPos.betweenClosed(base.offset(-2,7,-2),base.offset(2,7,2))) level.setBlock(p,Blocks.OAK_LEAVES.defaultBlockState(),2|16);
            for(int pass=0;pass<3;pass++) SeasonWeather.surfaceColumn(level,base.getX(),base.getZ(),Phase.ASH_WINTER);
            h.assertTrue(!level.isEmptyBlock(base.above()),"Canopy prevents snow and ice on the forest floor");
            for(var p:BlockPos.betweenClosed(base.above(),base.above(6))) level.setBlock(p,Blocks.AIR.defaultBlockState(),2|16);
            for(var p:BlockPos.betweenClosed(base.offset(-2,7,-2),base.offset(2,7,2))) level.setBlock(p,Blocks.STONE.defaultBlockState(),2|16);
            SeasonWeather.surfaceColumn(level,base.getX(),base.getZ(),Phase.ASH_WINTER);
            h.assertTrue(level.isEmptyBlock(base.above()),"Winter fills a roofed room");
        } finally {for(var p:BlockPos.betweenClosed(base.offset(-3,-1,-3),base.offset(3,12,3))) level.setBlock(p,Blocks.AIR.defaultBlockState(),2|16);}
        h.succeed();
    }
}
