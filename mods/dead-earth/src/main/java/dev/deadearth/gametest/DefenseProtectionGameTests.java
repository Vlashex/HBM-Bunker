package dev.deadearth.gametest;

import dev.deadearth.campaign.SavedDataAccess;
import dev.deadearth.defense.*;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("dead_earth_defense") @PrefixGameTestTemplate(false)
public final class DefenseProtectionGameTests {
    private static Block block(String name){return BuiltInRegistries.BLOCK.get(ResourceLocation.parse(name));}
    private static void place(ServerLevel level,BlockPos pos,BlockState state) {
        DefensePolicy.scoped(DefensePolicy.Scope.ADMIN,()->{
            level.setBlock(pos.below(),Blocks.STONE.defaultBlockState(),2);level.setBlock(pos,state,2);
            if(state.getBlock() instanceof DoorBlock)level.setBlock(pos.above(),state.setValue(DoorBlock.HALF,DoubleBlockHalf.UPPER),2);
        });
    }
    private static void clear(ServerLevel level,BlockPos base) {
        DefensePolicy.scoped(DefensePolicy.Scope.ADMIN,()->{
            for(var pos:BlockPos.betweenClosed(base.offset(-2,-2,-2),base.offset(12,5,3)))level.setBlock(pos,Blocks.AIR.defaultBlockState(),2);
        });
    }
    @GameTest(template="empty",batch="defense_protection",timeoutTicks=300)
    public static void hpIsSharedAcrossDoorHalvesAndSurvivesDiskReload(GameTestHelper h) {
        var level=h.getLevel();var base=new BlockPos(2800008,220,2800008);level.getChunkAt(base);
        try {
            var wall=block("dead_earth:defense_wall_v1").defaultBlockState();place(level,base,wall);
            h.assertTrue(DefensePolicy.damage(level,base,300),"V1 did not accept HP damage");
            h.assertTrue(DefensePolicy.remaining(level,base)==DefenseConfig.hp(DefenseTier.V1)-300,"V1 HP was not reduced exactly");
            var door=base.east(2);var state=block("dead_earth:defense_door_v3").defaultBlockState();place(level,door,state);
            DefensePolicy.damage(level,door.above(),500);
            h.assertTrue(DefensePolicy.remaining(level,door)==DefenseConfig.hp(DefenseTier.V3)-500,"Upper half did not share lower HP");
            level.setBlock(door,level.getBlockState(door).setValue(BlockStateProperties.OPEN,true),3);
            h.assertTrue(DefensePolicy.remaining(level,door.above())==DefenseConfig.hp(DefenseTier.V3)-500,"Door state change healed HP");
            var store=DefensePolicy.store(level);SavedDataAccess.checkpoint(level,DefenseDamageStore.NAME,store);
            var server=level.getServer();var disk=new net.minecraft.world.level.storage.DimensionDataStorage(
                server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).resolve("data").toFile(),server.getFixerUpper(),level.registryAccess());
            var restored=disk.get(new SavedData.Factory<>(DefenseDamageStore::create,DefenseDamageStore::load),DefenseDamageStore.NAME);
            h.assertTrue(restored!=null&&restored.enabled()&&restored.remaining(door.asLong(),BuiltInRegistries.BLOCK.getKey(state.getBlock()),DefenseTier.V3,99999)==DefenseConfig.hp(DefenseTier.V3)-500,"Real disk reload refilled door HP");
            DefensePolicy.damage(level,base,Integer.MAX_VALUE);h.assertTrue(level.getBlockState(base).isAir(),"Zero HP did not remove wall");
            try(var client=FoundationGameTests.attach(h)) {
                h.assertTrue(client.player().gameMode.destroyBlock(door),"Legitimate native player demolition was blocked");
                h.assertTrue(level.getBlockState(door).isAir()&&level.getBlockState(door.above()).isAir(),"Player did not remove both door halves");
            }
        }finally{clear(level,base);}
        h.succeed();
    }
    @GameTest(template="empty",batch="defense_protection",timeoutTicks=300)
    public static void nativePutridTickGrowAndCorruptionRespectV3OnAllShapes(GameTestHelper h)throws Exception {
        var level=h.getLevel();var base=new BlockPos(2800040,220,2800008);level.getChunkAt(base);
        var putrid=block("mutationcraft:putrid_block");var type=Class.forName("com.asestefan.mutationcraft.block.PutridBlock");
        var predicate=type.getDeclaredMethod("canSpreadInto",ServerLevel.class,BlockPos.class,BlockPos.class);predicate.setAccessible(true);
        var grow=type.getDeclaredMethod("grow",ServerLevel.class,BlockPos.class,BlockPos.class,RandomSource.class);grow.setAccessible(true);
        var corruption=Class.forName("com.yayuy.overgrownvegetation.corruption.CorruptionRegistry");
        var canCorrupt=corruption.getMethod("canCorrupt",LevelReader.class,BlockPos.class,BlockState.class,boolean.class);
        var convert=corruption.getMethod("placeCorrupted",net.minecraft.world.level.LevelAccessor.class,BlockPos.class,BlockState.class,BlockState.class,int.class);
        try {
            for(var shape:List.of("wall","door","hatch"))for(int tier=1;tier<=3;tier++) {
                clear(level,base);var source=base;var target=source.above();var state=block("dead_earth:defense_"+shape+"_v"+tier).defaultBlockState();
                place(level,target,state);level.setBlock(source,putrid.defaultBlockState(),2);
                boolean allowed=(boolean)predicate.invoke(null,level,target,target);
                h.assertTrue(allowed==(tier!=3),"Putrid native predicate disagreed for "+shape+" V"+tier);
                putrid.defaultBlockState().randomTick(level,source,RandomSource.create(5188));
                h.assertTrue(tier==3?level.getBlockState(target).getBlock()==state.getBlock():level.getBlockState(target).is(putrid),"Putrid native randomTick disagreed for "+shape+" V"+tier);
                place(level,target,state);grow.invoke(null,level,target,target.above(),RandomSource.create(5188));
                h.assertTrue(tier==3?level.getBlockState(target).getBlock()==state.getBlock():level.getBlockState(target).is(putrid),"Direct native grow bypassed policy");
                if(tier==3) {
                    h.assertFalse((boolean)canCorrupt.invoke(null,level,target,state,true),"Overgrown predicate accepted V3");
                    convert.invoke(null,level,target,state,Blocks.STONE.defaultBlockState(),3);
                    h.assertTrue(level.getBlockState(target).getBlock()==state.getBlock(),"Direct native corruption replaced V3");
                    h.assertFalse(level.setBlock(target,Blocks.AIR.defaultBlockState(),3),"Unscoped setter removed V3");
                    h.assertTrue(level.getChunkAt(target).setBlockState(target,Blocks.STONE.defaultBlockState(),false)==null,"Raw chunk setter replaced V3");
                }
            }
        }finally{clear(level,base);}
        h.succeed();
    }
    @GameTest(template="empty",batch="defense_protection",timeoutTicks=300)
    public static void nativeImprovedBreakerConsumesHpBeforeRestorationCanRegisterDrops(GameTestHelper h)throws Exception {
        var level=h.getLevel();var base=new BlockPos(2800072,220,2800008);level.getChunkAt(base);var mob=EntityType.ZOMBIE.create(level);
        var config=Class.forName("io.github.flemmli97.improvedmobs.common.config.Config$CommonConfig");var restore=config.getField("restoreDelay");int before=restore.getInt(null);
        try {
            restore.setInt(null,200);
            var wall=block("dead_earth:defense_wall_v3").defaultBlockState();place(level,base,wall);
            var utils=Class.forName("io.github.flemmli97.improvedmobs.common.utils.Utils");
            h.assertTrue((boolean)utils.getMethod("canBreakState",LivingEntity.class,BlockState.class).invoke(null,mob,wall),"Native target selection excluded defense wall before HP adapter");
            var goalType=Class.forName("io.github.flemmli97.improvedmobs.common.entities.ai.BlockBreakGoal");var goal=goalType.getConstructor(Mob.class).newInstance(mob);
            var target=goalType.getDeclaredField("diggingPosition");target.setAccessible(true);var timer=goalType.getDeclaredField("digTimer");timer.setAccessible(true);
            var nativeTick=goalType.getMethod("tick");
            for(int hit=0;hit<2;hit++){target.set(goal,base);timer.setInt(goal,DefenseConfig.integer(DefenseConfig.BREAKER_TICKS)-1);nativeTick.invoke(goal);}
            h.assertTrue(DefensePolicy.remaining(level,base)==DefenseConfig.hp(DefenseTier.V3)-2*DefenseConfig.integer(DefenseConfig.BREAKER_DAMAGE),"Native breaker did not consume configured HP");
            var dataType=Class.forName("io.github.flemmli97.improvedmobs.common.utils.BlockRestorationData");var data=dataType.getMethod("get",ServerLevel.class).invoke(null,level);
            var field=dataType.getDeclaredField("toRestore");field.setAccessible(true);var chunks=(java.util.Map<?,?>)field.get(data);
            var entries=chunks.get(net.minecraft.world.level.ChunkPos.asLong(base));
            h.assertTrue(entries==null||!((java.util.Map<?,?>)entries).containsKey(base.asLong()),"Unsuccessful break registered restoration drops");
            Block.dropResources(wall,level,base);
            h.assertTrue(level.getEntitiesOfClass(ItemEntity.class,new AABB(base).inflate(2)).isEmpty(),"Unsuccessful native break/drop attempt duplicated wall item");
        }finally{restore.setInt(null,before);if(mob!=null)mob.discard();clear(level,base);}
        h.succeed();
    }
    @GameTest(template="empty",batch="defense_protection",timeoutTicks=300)
    public static void nativeHookTunnelAndSiegeUseDistinctImmunityAndHpPaths(GameTestHelper h)throws Exception {
        var level=h.getLevel();var base=new BlockPos(2800104,220,2800008);level.getChunkAt(base);
        var hookType=BuiltInRegistries.ENTITY_TYPE.get(ResourceLocation.parse("mutationcraft:light_hook"));var hook=hookType.create(level);
        var config=Class.forName("com.asestefan.mutationcraft.config.MutationcraftConfig");
        var setting=config.getField("HOOKS_PLACE_PUTRID_BLOCKS").get(null);
        h.assertTrue((boolean)setting.getClass().getMethod("get").invoke(setting),"Native Hook placement must be enabled in the fresh fixture config");
        try {
            var wall=block("dead_earth:defense_wall_v3").defaultBlockState();place(level,base,wall);
            h.assertTrue(hook!=null&&Class.forName("com.asestefan.mutationcraft.entity.HookEntity").isInstance(hook),"Native light Hook fixture missing");
            hook.moveTo(base.getX()+0.5,base.getY()+1,base.getZ()+0.5,0,0);
            Class.forName("com.asestefan.mutationcraft.behavior.HookBehavior").getMethod("onSpawn",Class.forName("com.asestefan.mutationcraft.entity.HookEntity"),net.minecraft.world.level.ServerLevelAccessor.class).invoke(null,hook,level);
            h.assertTrue(level.getBlockState(base).is(wall.getBlock()),"Real Hook onSpawn replaced V3");
            var tunnelType=Class.forName("com.yayuy.overgrownvegetation.entity.ArboraTunnelGoal");
            var canDig=tunnelType.getDeclaredMethod("canDig",ServerLevel.class,BlockPos.class);canDig.setAccessible(true);
            h.assertTrue((boolean)canDig.invoke(null,level,base),"Native tunnel could not select finite-hardness V3");
            var tunnel=tunnelType.getDeclaredMethod("breakAndCorrupt",ServerLevel.class,BlockPos.class);tunnel.setAccessible(true);tunnel.invoke(null,level,base);
            h.assertTrue(DefensePolicy.remaining(level,base)==DefenseConfig.hp(DefenseTier.V3)-DefenseConfig.integer(DefenseConfig.TUNNEL_DAMAGE),"Native tunnel did not consume HP");
            var crater=Class.forName("com.yayuy.overgrownvegetation.entity.SiegeBombEntity").getMethod("carveMinimalCrater",ServerLevel.class,BlockPos.class,int.class,float.class);
            crater.invoke(null,level,base,0,Float.MAX_VALUE);
            h.assertTrue(DefensePolicy.remaining(level,base)==DefenseConfig.hp(DefenseTier.V3)-DefenseConfig.integer(DefenseConfig.TUNNEL_DAMAGE)-DefenseConfig.integer(DefenseConfig.SIEGE_DAMAGE),"Native siege crater did not consume HP");
            h.assertTrue(level.getEntitiesOfClass(ItemEntity.class,new AABB(base).inflate(2)).isEmpty(),"Absorbed native attack dropped a wall");
            place(level,base,block("dead_earth:defense_wall_v1").defaultBlockState());
            hook.moveTo(base.getX()+0.5,base.getY()+1,base.getZ()+0.5,0,0);
            Class.forName("com.asestefan.mutationcraft.behavior.HookBehavior").getMethod("onSpawn",Class.forName("com.asestefan.mutationcraft.entity.HookEntity"),net.minecraft.world.level.ServerLevelAccessor.class).invoke(null,hook,level);
            h.assertTrue(level.getBlockState(base).is(block("mutationcraft:putrid_block")),"V1 incorrectly inherited V3 Hook immunity");
        }finally{if(hook!=null)hook.discard();clear(level,base);}
        h.succeed();
    }
    @GameTest(template="empty",batch="defense_protection",timeoutTicks=300)
    @SuppressWarnings({"rawtypes","unchecked"})
    public static void realBatchedNuclearWriterIsAllowedAndNewPlacementDoesNotInheritOldHp(GameTestHelper h)throws Exception {
        var level=h.getLevel();var base=new BlockPos(2800136,220,2800008);level.getChunkAt(base);
        try {
            var wall=block("dead_earth:defense_wall_v3").defaultBlockState();place(level,base,wall);DefensePolicy.damage(level,base,100);
            var type=Class.forName("com.hbm.explosion.ExplosionNukeRayBatched");
            var nuke=type.getConstructor(net.minecraft.world.level.Level.class,int.class,int.class,int.class,int.class,int.class).newInstance(level,base.getX(),base.getY(),base.getZ(),1,1);
            var bits=new java.util.BitSet();bits.set((base.getY()-level.getMinBuildHeight())*256+((base.getX()&15)<<4)+(base.getZ()&15));
            ((it.unimi.dsi.fastutil.longs.Long2ObjectMap)type.getMethod("getPerChunk").invoke(nuke)).put(net.minecraft.world.level.ChunkPos.asLong(base),bits);
            var order=type.getDeclaredMethod("orderChunks");order.setAccessible(true);order.invoke(nuke);
            var destruction=type.getDeclaredMethod("destructionTick",long.class);destruction.setAccessible(true);destruction.invoke(nuke,10L);
            h.assertTrue(level.getBlockState(base).isAir(),"V3 guard incorrectly blocked actual batched nuclear writer");
            place(level,base,wall);h.assertTrue(DefensePolicy.remaining(level,base)==DefenseConfig.hp(DefenseTier.V3),"New wall inherited destroyed wall HP");
        }finally{clear(level,base);}
        h.succeed();
    }
    @GameTest(template="empty",batch="defense_protection",timeoutTicks=300)
    public static void exactLegacyMigrationPreservesStarterIdentityDeadlineAndRollbackState(GameTestHelper h) {
        var level=h.getLevel();var server=level.getServer();var runtime=dev.deadearth.DeadEarth.runtime(server);
        long deadline=runtime.campaign().campaign().activeTicks()+1000;
        var factory=new SavedData.Factory<>(()->dev.deadearth.starter.SafeBunkerStore.create(runtime.campaign().campaign().campaignId(),deadline),dev.deadearth.starter.SafeBunkerStore::load);
        var previous=SavedDataAccess.open(server.overworld(),dev.deadearth.starter.SafeBunkerStore.NAME,factory);
        var base=new BlockPos(2800168,220,2800008);level.getChunkAt(base);
        try {
            var legacy=block("outpostzero:steel_block").defaultBlockState();place(level,base,legacy);
            var store=dev.deadearth.starter.SafeBunkerStore.create(runtime.campaign().campaign().campaignId(),deadline);
            store.record(List.of(new dev.deadearth.starter.SafeBunkerStore.Box(base.getX(),base.getY(),base.getZ(),base.getX(),base.getY(),base.getZ())),java.util.Map.of(base.asLong(),ResourceLocation.parse("outpostzero:steel_block")),"native_capture");
            server.overworld().getDataStorage().set(dev.deadearth.starter.SafeBunkerStore.NAME,store);
            dev.deadearth.starter.SafeBunker.close(server);dev.deadearth.starter.SafeBunker.tick(server,runtime);
            h.assertTrue(dev.deadearth.starter.SafeBunker.protectsRemoval(level,base),"Legacy fixture was not originally protected");
            h.assertTrue(DefenseMigration.migrateAt(level,base),"Exact loaded steel wall was not migrated");
            h.assertTrue(level.getBlockState(base).is(block("dead_earth:defense_wall_v1")),"Migration chose wrong target");
            h.assertTrue(store.deadline()==deadline&&dev.deadearth.starter.SafeBunker.protectsRemoval(level,base),"Migration reset deadline or lost original wall protection");
            var journal=level.getDataStorage().get(new SavedData.Factory<>(DefenseMigrationStore::create,DefenseMigrationStore::load),DefenseMigrationStore.NAME);
            h.assertTrue(journal!=null&&journal.enabled()&&journal.save(new CompoundTag(),level.registryAccess()).getList("originals",10).stream().anyMatch(row->((CompoundTag)row).getLong("pos")==base.asLong()&&((CompoundTag)row).getCompound("state").getString("Name").equals("outpostzero:steel_block")),"Migration did not preserve exact old native state");
            place(level,base.east(2),block("securitycraft:reinforced_glass").defaultBlockState());
            h.assertFalse(DefenseMigration.migrateAt(level,base.east(2)),"Unrelated SecurityCraft glass was migrated");
        }finally {
            server.overworld().getDataStorage().set(dev.deadearth.starter.SafeBunkerStore.NAME,previous);previous.setDirty();SavedDataAccess.checkpoint(server.overworld(),dev.deadearth.starter.SafeBunkerStore.NAME,previous);
            dev.deadearth.starter.SafeBunker.close(server);dev.deadearth.starter.SafeBunker.tick(server,runtime);clear(level,base);
        }
        h.succeed();
    }
    @GameTest(template="empty",batch="defense_protection",timeoutTicks=300)
    public static void localScopesCannotLendNeighborOrOtherWorldV3Permission(GameTestHelper h) {
        var level=h.getLevel();var base=new BlockPos(2800200,220,2800008);level.getChunkAt(base);
        try {
            var wall=block("dead_earth:defense_wall_v3").defaultBlockState();var neighbor=base.east(2);
            for(var reason:List.of(DefensePolicy.Scope.PLAYER,DefensePolicy.Scope.HP_BREAK,DefensePolicy.Scope.MIGRATION)) {
                place(level,base,wall);place(level,neighbor,wall);
                DefensePolicy.scoped(reason,level,base,wall,()->{
                    Runnable nestedCallback=()->{
                        h.assertFalse(level.setBlock(neighbor,Blocks.AIR.defaultBlockState(),3),"Local "+reason+" scope leaked into nested neighbor replacement");
                        h.assertFalse(DefensePolicy.allowed(level,neighbor),"Neighbor received destruction permission");
                        var other=level.getServer().getLevel(net.minecraft.world.level.Level.NETHER);
                        if(other!=null)h.assertTrue(DefensePolicy.blocksReplacement(other,base,wall,Blocks.AIR.defaultBlockState()),"Local scope leaked into another dimension");
                    };
                    nestedCallback.run();
                    h.assertTrue(level.setBlock(base,Blocks.AIR.defaultBlockState(),3),"Local scope could not mutate its own anchor");
                });
                h.assertTrue(level.getBlockState(neighbor).is(wall.getBlock()),"Nested callback removed neighboring V3");
            }
        }finally{clear(level,base);}
        h.succeed();
    }
    @GameTest(template="empty",batch="defense_protection",timeoutTicks=300)
    public static void upperDoorPlayerBreakDropsOneItemAndHpDepletionDropsNone(GameTestHelper h) {
        // Entity queries ignore hidden sections of distant, merely FULL chunks. Use the test's forced chunk.
        var level=h.getLevel();var base=h.absolutePos(new BlockPos(0,2,0));
        try {
            var door=block("dead_earth:defense_door_v3").defaultBlockState();place(level,base,door);
            DefensePolicy.damage(level,base.above(),100);
            level.setBlock(base.above(),level.getBlockState(base.above()).setValue(BlockStateProperties.OPEN,true),3);
            level.setBlock(base,level.getBlockState(base).setValue(BlockStateProperties.OPEN,false),3);
            h.assertTrue(DefensePolicy.remaining(level,base.above())==DefenseConfig.hp(DefenseTier.V3)-100,"Upper/lower open and close healed shared HP");
            try(var client=FoundationGameTests.attach(h)) {
                client.player().setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
                client.player().setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND,new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIAMOND_PICKAXE));
                h.assertTrue(client.player().hasCorrectToolForDrops(level.getBlockState(base.above()),level,base.above()),"Native diamond pickaxe cannot harvest defense door");
                var calculated=Block.getDrops(door,level,base,null,client.player(),client.player().getMainHandItem());
                h.assertTrue(calculated.stream().mapToInt(stack->stack.is(door.getBlock().asItem())?stack.getCount():0).sum()==1,"Defense door lower-half loot table does not produce one own item: "+calculated);
                h.assertTrue(client.player().gameMode.destroyBlock(base.above()),"Native player upper-door demolition failed");
            }
            h.assertTrue(level.getBlockState(base).isAir()&&level.getBlockState(base.above()).isAir(),"Player left a door half behind");
            var drops=level.getEntitiesOfClass(ItemEntity.class,new AABB(base).inflate(3));
            h.assertTrue(drops.stream().mapToInt(item->item.getItem().is(door.getBlock().asItem())?item.getItem().getCount():0).sum()==1,"Native upper-door demolition must drop exactly one door item; actual="+drops.stream().map(item->item.getItem().toString()).toList());
            drops.forEach(ItemEntity::discard);
            // Regression: pass the UPPER position/state once; the scope must anchor only once.
            place(level,base,door);DefensePolicy.damage(level,base.above(),DefenseConfig.hp(DefenseTier.V3));
            h.assertTrue(level.getBlockState(base).isAir()&&level.getBlockState(base.above()).isAir(),"HP depletion left a door half behind");
            h.assertTrue(level.getEntitiesOfClass(ItemEntity.class,new AABB(base).inflate(3)).isEmpty(),"HP depletion dropped reusable door material");
        }finally {clear(level,base);level.getEntitiesOfClass(ItemEntity.class,new AABB(base).inflate(3)).forEach(ItemEntity::discard);}
        h.succeed();
    }
    @GameTest(template="empty",batch="defense_protection",timeoutTicks=300)
    public static void realVanillaAndHbmVntExplosionsConsumeHpBeforeProcessorAndDrops(GameTestHelper h)throws Exception {
        var level=h.getLevel();var base=new BlockPos(2800264,220,2800008);level.getChunkAt(base);
        try {
            var type=Class.forName("com.hbm.explosion.vanillant.ExplosionVNT");float power=4;
            int damage=(int)Math.ceil(power*DefenseConfig.number(DefenseConfig.HBM_VNT_DAMAGE));
            int vanillaDamage=(int)Math.ceil(power*DefenseConfig.number(DefenseConfig.EXPLOSION_DAMAGE));
            for(int tier=1;tier<=3;tier++) {
                var wall=block("dead_earth:defense_wall_v"+tier).defaultBlockState();place(level,base,wall);
                level.explode(null,base.getX()+0.5,base.getY()+0.5,base.getZ()+0.5,power,net.minecraft.world.level.Level.ExplosionInteraction.BLOCK);
                h.assertTrue(DefensePolicy.remaining(level,base)==DefenseConfig.hp(DefenseTier.valueOf("V"+tier))-vanillaDamage,"Actual vanilla explosion did not consume configured HP");
                var explosion=type.getConstructor(net.minecraft.world.level.Level.class,BlockPos.class,float.class).newInstance(level,base,power);
                type.getMethod("makeStandard").invoke(explosion);type.getMethod("explode").invoke(explosion);
                var allocated=(java.util.List<?>)type.getField("allocatedBlocks").get(explosion);
                h.assertTrue(allocated.contains(base),"Actual VNT allocator did not reach control target");
                h.assertTrue(level.getBlockState(base).is(wall.getBlock()),"HBM VNT processor bypassed HP");
                h.assertTrue(DefensePolicy.remaining(level,base)==DefenseConfig.hp(DefenseTier.valueOf("V"+tier))-vanillaDamage-damage,"Actual HBM VNT did not consume configured HP");
                h.assertTrue(level.getEntitiesOfClass(ItemEntity.class,new AABB(base).inflate(3)).isEmpty(),"Absorbed HBM blast dropped defense material");
            }
        }finally{clear(level,base);}
        h.succeed();
    }
    @GameTest(template="empty",batch="defense_protection",timeoutTicks=300)
    public static void legacyBreakersOnlyEnqueueAndWaitForBudgetedMigrationTick(GameTestHelper h)throws Exception {
        var level=h.getLevel();var base=new BlockPos(2800296,220,2800008);level.getChunkAt(base);var mob=EntityType.ZOMBIE.create(level);
        var type=Class.forName("io.github.flemmli97.improvedmobs.common.entities.ai.BlockBreakGoal");var goal=type.getConstructor(Mob.class).newInstance(mob);
        var target=type.getDeclaredField("diggingPosition");target.setAccessible(true);var timer=type.getDeclaredField("digTimer");timer.setAccessible(true);
        var nativeTick=type.getMethod("tick");
        var file=level.getServer().getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).resolve("data").resolve(DefenseMigrationStore.NAME+".dat");
        try {
            var legacy=block("outpostzero:steel_block").defaultBlockState();place(level,base,legacy);
            byte[] before=java.nio.file.Files.exists(file)?java.nio.file.Files.readAllBytes(file):null;
            for(int n=0;n<8;n++){target.set(goal,base);timer.setInt(goal,DefenseConfig.integer(DefenseConfig.BREAKER_TICKS)-1);nativeTick.invoke(goal);}
            byte[] after=java.nio.file.Files.exists(file)?java.nio.file.Files.readAllBytes(file):null;
            h.assertTrue(level.getBlockState(base).is(legacy.getBlock()),"Mob.tick synchronously migrated legacy wall outside the migration budget");
            h.assertTrue(java.util.Arrays.equals(before,after),"Mob.tick synchronously checkpointed migration journal");
            h.runAfterDelay(40,()->{
                try {
                    var wall=block("dead_earth:defense_wall_v1");
                    h.assertTrue(level.getBlockState(base).is(wall),"Queued legacy wall did not pass through normal bounded migration ticks");
                    target.set(goal,base);timer.setInt(goal,DefenseConfig.integer(DefenseConfig.BREAKER_TICKS)-1);nativeTick.invoke(goal);
                    h.assertTrue(DefensePolicy.remaining(level,base)==DefenseConfig.hp(DefenseTier.V1)-DefenseConfig.integer(DefenseConfig.BREAKER_DAMAGE),"Breaker did not resume HP attacks after migration");
                    h.succeed();
                }catch(ReflectiveOperationException error){throw new IllegalStateException(error);}
                finally{if(mob!=null)mob.discard();clear(level,base);}
            });
        }catch(Exception|AssertionError error){if(mob!=null)mob.discard();clear(level,base);throw error;}
    }
}
