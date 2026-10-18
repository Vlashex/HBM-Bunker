package dev.deadearth.gametest;

import dev.deadearth.evolution.EvolutionCatalog;
import dev.deadearth.evolution.EvolutionState;
import java.lang.reflect.*;
import java.util.*;
import java.util.function.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.*;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.ExplosionEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Real pinned Mutationcraft entry points; no dependency on its classes at compile time. */
@GameTestHolder("dead_earth_evolution") @PrefixGameTestTemplate(false)
public final class EvolutionNativeGameTests {
    private static final String NATIVE="com.asestefan.mutationcraft.";
    private static Object call(String type,String name,Object... args)throws Exception{
        for(var method:Class.forName(NATIVE+type).getDeclaredMethods())if(method.getName().equals(name)&&method.getParameterCount()==args.length){
            method.setAccessible(true);
            try{return method.invoke(null,args);}catch(InvocationTargetException failed){throw new IllegalStateException(type+"."+name,failed.getCause());}
        }throw new NoSuchMethodException(type+"."+name);
    }
    private static EntityType<?> type(String name){return BuiltInRegistries.ENTITY_TYPE.get(ResourceLocation.parse("mutationcraft:"+name));}
    private static Mob mob(GameTestHelper h,String name,BlockPos local){
        var type=type(name);h.assertTrue(type!=null&&BuiltInRegistries.ENTITY_TYPE.getKey(type).toString().equals("mutationcraft:"+name),"Missing native mutant "+name);
        var entity=type.create(h.getLevel());h.assertTrue(entity instanceof Mob,"Native mutant is not a mob: "+name);
        var mob=(Mob)entity;mob.moveTo(Vec3.atBottomCenterOf(h.absolutePos(local)));mob.setNoAi(true);mob.setPersistenceRequired();
        h.getLevel().addFreshEntity(mob);return mob;
    }
    private record Phase(MinecraftServer server,EvolutionState.Published previous)implements AutoCloseable{
        static Phase open(ServerLevel level,int phase){var previous=EvolutionState.published(level.getServer());EvolutionState.publish(level.getServer(),EvolutionCatalog.defaults(),phase);return new Phase(level.getServer(),previous);}
        void set(int phase){EvolutionState.publish(server,EvolutionCatalog.defaults(),phase);}
        @Override public void close(){EvolutionState.restore(server,previous);}
    }
    /** Legacy adapter checks explicitly open skills instead of weakening production gates. */
    static AutoCloseable phaseFiveWithPlayer(GameTestHelper helper){
        var server=helper.getLevel().getServer();var previous=EvolutionState.published(server);
        var player=FoundationGameTests.attach(helper);
        try{EvolutionState.publish(server,EvolutionCatalog.defaults(),5);}
        catch(RuntimeException|Error failed){player.close();EvolutionState.restore(server,previous);throw failed;}
        return ()->{try{player.close();}finally{EvolutionState.restore(server,previous);}};
    }
    private static AutoCloseable calmSeason()throws ReflectiveOperationException{
        var active=dev.deadearth.climate.SeasonState.class.getDeclaredField("active");active.setAccessible(true);
        boolean wasActive=active.getBoolean(null);var previousPhase=dev.deadearth.climate.SeasonState.phase();double previousPressure=dev.deadearth.climate.SeasonState.pressure();
        dev.deadearth.climate.SeasonState.update(dev.deadearth.climate.ClimateCycle.Phase.CALM,1);
        return ()->{if(wasActive)dev.deadearth.climate.SeasonState.update(previousPhase,previousPressure);else dev.deadearth.climate.SeasonState.reset();};
    }
    private static final class NativeMap implements AutoCloseable{
        final Object map;final CompoundTag original;final boolean dirty;
        NativeMap(ServerLevel level)throws Exception{
            map=call("network.ModVariables$MapVariables","get",level);
            original=(CompoundTag)map.getClass().getMethod("write",CompoundTag.class).invoke(map,new CompoundTag());
            dirty=((net.minecraft.world.level.saveddata.SavedData)map).isDirty();
        }
        void values(double time,int stage,boolean paused)throws Exception{
            map.getClass().getField("time").setDouble(map,time);map.getClass().getField("stage").setInt(map,stage);map.getClass().getField("paused").setBoolean(map,paused);
        }
        CompoundTag snapshot()throws Exception{return (CompoundTag)map.getClass().getMethod("write",CompoundTag.class).invoke(map,new CompoundTag());}
        @Override public void close()throws Exception{map.getClass().getMethod("read",CompoundTag.class).invoke(map,original);((net.minecraft.world.level.saveddata.SavedData)map).setDirty(dirty);}
    }
    private static AutoCloseable toggle(String name,boolean value)throws Exception{
        var option=Class.forName(NATIVE+"config.MutationcraftConfig").getField(name).get(null);
        var field=option.getClass().getDeclaredField("value");field.setAccessible(true);var previous=field.get(option);
        var bind=option.getClass().getMethod("bind",BooleanSupplier.class);bind.invoke(option,(BooleanSupplier)()->value);
        return ()->bind.invoke(option,previous);
    }
    private static AutoCloseable number(String name,double value)throws Exception{
        var option=Class.forName(NATIVE+"config.MutationcraftConfig").getField(name).get(null);
        var field=option.getClass().getDeclaredField("value");field.setAccessible(true);var previous=field.get(option);
        var bind=option.getClass().getMethod("bind",DoubleSupplier.class);bind.invoke(option,(DoubleSupplier)()->value);
        return ()->bind.invoke(option,previous);
    }
    private static boolean natural(GameTestHelper h,String name){return SpawnPlacements.checkSpawnRules(type(name),h.getLevel(),MobSpawnType.NATURAL,h.absolutePos(new BlockPos(3,2,3)),h.getLevel().getRandom());}

    @GameTest(template="empty",batch="evolution_native")
    public static void nativeClockIsPausedWithoutPlayersAndDoesNotRewriteNativeTimeStageOrPause(GameTestHelper h)throws Exception{
        h.assertTrue(h.getLevel().getServer().getPlayerCount()==0,"Clock fixture must not attach a player");
        try(var phase=Phase.open(h.getLevel(),0);var nativeMap=new NativeMap(h.getLevel());var running=toggle("OUTBREAK_TIMER_RUNS",true)){
            nativeMap.values(123456.25,4,false);var before=nativeMap.snapshot();
            for(int tick=0;tick<40;tick++)call("network.ModVariables","tickTime",h.getLevel());
            h.assertTrue(before.equals(nativeMap.snapshot()),"Controller changed the native time/stage/paused save instead of intercepting tickTime");
        }h.succeed();
    }

    @GameTest(template="empty",batch="evolution_native")
    public static void individualTimersAndKillProgressPauseWithoutPlayersThenResumeWithPlayer(GameTestHelper h)throws Exception{
        var level=h.getLevel();h.assertTrue(level.getServer().getPlayerCount()==0,"Counter fixture must begin without players");
        var human=mob(h,"human_stage_1",new BlockPos(2,2,2));var roamer=mob(h,"assimilated_roamer",new BlockPos(3,2,2));
        var miter=mob(h,"miter",new BlockPos(4,2,2));var necroptor=mob(h,"necroptor",new BlockPos(5,2,2));
        try(var phase=Phase.open(level,5);var season=calmSeason();var a=toggle("HUMAN_STAGES_EVOLVE",true);var b=toggle("ROAMER_DEVELOPS",true);var c=toggle("MITER_EVOLVES",true);var d=toggle("NECROPTOR_EVOLVES",true);var limit=number("NECROPTOR_EVOLVE_KILLS",100)){
            human.getPersistentData().putInt("Evolve",7);roamer.getPersistentData().putInt("Develop",7);miter.getPersistentData().putInt("Timer",7);necroptor.getPersistentData().putInt("mutationcraft:necroptor_kills",0);
            Runnable counters=()->{try{call("behavior.HumanStageEvolution","tick",human,"Evolve",type("human_stage_2"));call("behavior.AssimilatedRoamerDevelop","tick",roamer);call("entity.MiterEntity","miterEvolution",miter);call("behavior.NecroptorBehavior","countZombieKill",level,necroptor);}catch(Exception failed){throw new IllegalStateException(failed);}};
            for(int tick=0;tick<8;tick++)counters.run();
            h.assertTrue(human.getPersistentData().getInt("Evolve")==7&&roamer.getPersistentData().getInt("Develop")==7&&miter.getPersistentData().getInt("Timer")==7&&necroptor.getPersistentData().getInt("mutationcraft:necroptor_kills")==0,"Native individual evolution advanced without any players");
            try(var player=FoundationGameTests.attach(h)){
                for(int tick=0;tick<8;tick++)counters.run();
                h.assertTrue(human.getPersistentData().getInt("Evolve")>7&&roamer.getPersistentData().getInt("Develop")>7&&miter.getPersistentData().getInt("Timer")>7&&necroptor.getPersistentData().getInt("mutationcraft:necroptor_kills")>0,
                        "Unlocked native individual counters did not resume: human="+human.getPersistentData().getInt("Evolve")+", roamer="+roamer.getPersistentData().getInt("Develop")+", miter="+miter.getPersistentData().getInt("Timer")+", kills="+necroptor.getPersistentData().getInt("mutationcraft:necroptor_kills")
                        +", factor="+dev.deadearth.climate.SeasonState.parasiteEvolution()+", phase="+EvolutionState.phase(level)+", players="+level.getServer().getPlayerCount()+", humanGate="+dev.deadearth.compat.EvolutionHooks.progress(human,type("human_stage_2")));
            }
        }finally{human.discard();roamer.discard();miter.discard();necroptor.discard();}h.succeed();
    }

    @GameTest(template="empty",batch="evolution_native")
    public static void realSpawnPlacementsUseExactPhaseAndKeepNativeTogglesAndDimensions(GameTestHelper h)throws Exception{
        try(var phase=Phase.open(h.getLevel(),0);var nativeMap=new NativeMap(h.getLevel());var global=toggle("MOBS_SPAWN_NATURALLY",true);var common=toggle("COMMON_MUTANTS_SPAWN",true);var elite=toggle("ELITE_MUTANTS_SPAWN",true)){
            nativeMap.values(1_000_000,4,false);
            h.assertTrue(!natural(h,"assimilated_human"),"Old native late timer opened common mutants in phase0");
            phase.set(1);nativeMap.values(0,0,false);
            h.assertTrue(natural(h,"assimilated_human"),"Common native spawn remains locked by the retired native timer");
            h.assertTrue(!natural(h,"human_stage_2"),"Exact late entity bypassed its phase through native tier unlock");
            phase.set(5);h.assertTrue(natural(h,"human_stage_2"),"Late native spawn did not unlock in phase5");
            h.assertTrue(!natural(h,"assimilated_piglin"),"Native Nether-only dimension restriction was lost");
            try(var off=toggle("COMMON_MUTANTS_SPAWN",false)){h.assertTrue(!natural(h,"assimilated_human"),"Controller bypassed native common-mutant disable toggle");}
            try(var off=toggle("MOBS_SPAWN_NATURALLY",false)){h.assertTrue(!natural(h,"assimilated_human"),"Controller bypassed native global spawn disable toggle");}
            EvolutionState.clear(h.getLevel().getServer());nativeMap.values(1_000_000,4,false);
            h.assertTrue(!natural(h,"assimilated_human"),"Missing published state silently opened the native late timer");
            var disabled=com.google.gson.JsonParser.parseString(EvolutionCatalog.defaults().source()).getAsJsonObject();disabled.addProperty("enabled",false);
            EvolutionState.publish(h.getLevel().getServer(),EvolutionCatalog.parse(disabled.toString()),0);
            h.assertTrue(natural(h,"assimilated_human"),"Explicit controller disable did not restore the native late timer");
            nativeMap.values(0,0,false);h.assertTrue(!natural(h,"assimilated_human"),"Disabled controller bypassed the original native time requirement");
        }h.succeed();
    }

    @GameTest(template="empty",batch="evolution_native")
    public static void deniedNativeConversionAndEvolutionKeepOriginalUuidEquipmentAndConvertedMarker(GameTestHelper h)throws Exception{
        var level=h.getLevel();var original=mob(h,"human_stage_1",new BlockPos(3,2,3));Mob grown=null;
        var pig=EntityType.PIG.create(level);pig.moveTo(original.position().add(2,0,0));level.addFreshEntity(pig);
        original.setCustomName(Component.literal("Keep original"));original.setItemSlot(EquipmentSlot.MAINHAND,new ItemStack(Items.IRON_SWORD));
        try(var phase=Phase.open(level,0)){
            var uuid=original.getUUID();var equipment=original.getMainHandItem().copy();
            h.assertTrue(call("behavior.MutantConversion","evolve",original,type("human_stage_2"),null)==null,"Locked native evolution returned a replacement");
            h.assertTrue(original.isAlive()&&!original.isRemoved()&&level.getEntity(uuid)==original&&ItemStack.matches(equipment,original.getMainHandItem()),"Denied evolution deleted or changed the original creature");
            h.assertTrue(call("behavior.MutantConversion","convert",pig,type("assimilated_pig"),null)==null,"Locked native infection converted the victim");
            h.assertTrue(pig.isAlive()&&!pig.getPersistentData().getBoolean("mutationcraft:converted"),"Denied infection wrote the native converted marker");
            phase.set(5);try(var player=FoundationGameTests.attach(h)){grown=(Mob)call("behavior.MutantConversion","evolve",original,type("human_stage_2"),null);}
            h.assertTrue(grown!=null&&grown.getType()==type("human_stage_2")&&original.isRemoved(),"Unlocked native evolve path did not replace the original");
        }finally{pig.discard();original.discard();if(grown!=null)grown.discard();}h.succeed();
    }

    @GameTest(template="empty",batch="evolution_native")
    public static void nativeFinalizeRejectsFreshLockedMutantsWithoutDeletingLoadedEntitiesOrBlockingNonMutants(GameTestHelper h)throws Exception{
        var level=h.getLevel();var loaded=mob(h,"human_stage_2",new BlockPos(2,2,2));
        var fresh=(Mob)type("human_stage_2").create(level);fresh.moveTo(Vec3.atBottomCenterOf(h.absolutePos(new BlockPos(4,2,2))));
        var villager=EntityType.VILLAGER.create(level);villager.moveTo(Vec3.atBottomCenterOf(h.absolutePos(new BlockPos(6,2,2))));
        try(var phase=Phase.open(level,0)){
            call("ModUtil","finalizeSpawn",fresh,level,level.getCurrentDifficultyAt(fresh.blockPosition()),MobSpawnType.MOB_SUMMONED);
            h.assertTrue(!level.addFreshEntity(fresh)&&level.getEntity(fresh.getUUID())==null,"Native finalize/add path admitted a fresh locked mutant");
            h.assertTrue(loaded.isAlive()&&!loaded.isRemoved()&&level.getEntity(loaded.getUUID())==loaded,"Native spawn gate removed an already loaded late mutant");
            call("ModUtil","finalizeSpawn",villager,level,level.getCurrentDifficultyAt(villager.blockPosition()),MobSpawnType.MOB_SUMMONED);
            h.assertTrue(level.addFreshEntity(villager)&&level.getEntity(villager.getUUID())==villager,"Native helper blocked a non-mutant entity");
        }finally{loaded.discard();fresh.discard();villager.discard();}h.succeed();
    }

    @GameTest(template="empty",batch="evolution_native")
    public static void hookSummonAndPutridWritesStayClosedThenResumeWithoutDeletingLoadedHook(GameTestHelper h)throws Exception{
        var level=h.getLevel();var local=new BlockPos(4,2,4);var hook=mob(h,"light_hook",local);var target=h.absolutePos(local.east());
        level.setBlock(target,Blocks.STONE.defaultBlockState(),3);var box=new AABB(h.absolutePos(local)).inflate(6);
        try(var phase=Phase.open(level,0)){
            call("block.PutridBlock","grow",level,target,target.above(),level.getRandom());
            h.assertTrue(level.getBlockState(target).is(Blocks.STONE),"Locked native Putrid grow wrote a block");
            call("behavior.HookBehavior","summonOne",level,hook,type("miter"));
            h.assertTrue(level.getEntities(type("miter"),box,Entity::isAlive).isEmpty(),"Locked loaded Hook summoned a parasite");
            h.assertTrue(hook.isAlive()&&!hook.isRemoved(),"Downgrade deleted an existing Hook");
            phase.set(5);call("block.PutridBlock","grow",level,target,target.above(),level.getRandom());
            h.assertTrue(BuiltInRegistries.BLOCK.getKey(level.getBlockState(target).getBlock()).toString().equals("mutationcraft:putrid_block"),"Unlocked native Putrid grow did not run");
            // spawnAt is the shared native summon path and avoids a random terrain-search fixture.
            var spawned=(Mob)call("behavior.AberrationSpawns","spawnAt",level,type("miter"),(double)target.getX(),(double)target.getY()+1,(double)target.getZ());
            h.assertTrue(spawned!=null&&level.getEntity(spawned.getUUID())==spawned,"Unlocked native summon was not admitted");spawned.discard();
        }finally{hook.discard();}h.succeed();
    }

    @GameTest(template="empty",batch="evolution_native")
    public static void nativeInfectionAndDeathParasiteHelpersRespectAbilityPhases(GameTestHelper h)throws Exception{
        var level=h.getLevel();var victim=EntityType.PIG.create(level);victim.moveTo(Vec3.atBottomCenterOf(h.absolutePos(new BlockPos(3,2,3))));level.addFreshEntity(victim);
        var effect=BuiltInRegistries.MOB_EFFECT.getHolder(ResourceLocation.parse("mutationcraft:mutagen_sickness")).orElseThrow();
        var box=victim.getBoundingBox().inflate(3);
        try(var phase=Phase.open(level,0);var toggle=toggle("KILLING_MUTANTS_GIVES_SICKNESS",true)){
            call("behavior.AberrationSpawns","mutagenSickness",victim,200);
            h.assertTrue(!victim.hasEffect(effect),"Locked infection ability applied native sickness");
            call("behavior.AnimalDeath","spawn",level,type("miter"),victim.getX(),victim.getY(),victim.getZ());
            h.assertTrue(level.getEntities(type("miter"),box,Entity::isAlive).isEmpty(),"Locked death parasite helper spawned a Miter");
            phase.set(5);call("behavior.AberrationSpawns","mutagenSickness",victim,200);
            h.assertTrue(victim.hasEffect(effect),"Unlocked native sickness was not applied");
            call("behavior.AnimalDeath","spawn",level,type("miter"),victim.getX(),victim.getY(),victim.getZ());
            var spawned=level.getEntities(type("miter"),box,Entity::isAlive);h.assertTrue(spawned.size()==1,"Unlocked native death helper did not spawn exactly one Miter");spawned.forEach(Entity::discard);
        }finally{victim.discard();}h.succeed();
    }

    @GameTest(template="empty",batch="evolution_native")
    public static void deniedRottenSkeletonAwakeningPreservesOriginalBeforeNativeKill(GameTestHelper h)throws Exception{
        var level=h.getLevel();var skeleton=mob(h,"rotten_skeleton",new BlockPos(3,2,3));var box=skeleton.getBoundingBox().inflate(2);
        try(var phase=Phase.open(level,0)){
            h.assertTrue(skeleton.isAlive(),"Native awakening fixture is not alive");
            call("entity.RottenSkeletonEntity","rottenSkeletonAwaken",skeleton);
            h.assertTrue(skeleton.isAlive()&&!skeleton.isRemoved()&&level.getEntity(skeleton.getUUID())==skeleton,"Denied awakening killed its original before denying the boss");
            h.assertTrue(level.getEntities(type("the_intoxicator"),box,Entity::isAlive).isEmpty(),"Locked boss was spawned");
            phase.set(5);try(var player=FoundationGameTests.attach(h)){call("entity.RottenSkeletonEntity","rottenSkeletonAwaken",skeleton);}
            var bosses=level.getEntities(type("the_intoxicator"),box,Entity::isAlive);
            h.assertTrue(!skeleton.isAlive()&&bosses.size()==1,"Unlocked native awakening did not produce exactly one boss");bosses.forEach(Entity::discard);
        }finally{skeleton.discard();}h.succeed();
    }

    @GameTest(template="empty",batch="evolution_native")
    public static void queuedNativeEvolutionRechecksPlayersAfterLogoutBeforeDiscardingOriginal(GameTestHelper h)throws Exception{
        var level=h.getLevel();var original=mob(h,"necroptor",new BlockPos(3,2,3));var box=original.getBoundingBox().inflate(3);
        try(var phase=Phase.open(level,5)){
            var player=FoundationGameTests.attach(h);player.close();
            h.assertTrue(level.getServer().getPlayerCount()==0,"Queued callback fixture must execute after logout");
            call("behavior.NecroptorBehavior","lambda$countZombieKill$0",original);
            h.assertTrue(original.isAlive()&&!original.isRemoved()&&level.getEntity(original.getUUID())==original,"Queued evolution after logout discarded its original");
            h.assertTrue(level.getEntities(type("reductor"),box,Entity::isAlive).isEmpty(),"Queued evolution advanced without players");
            try(var next=FoundationGameTests.attach(h)){call("behavior.NecroptorBehavior","lambda$countZombieKill$0",original);}
            var replacements=level.getEntities(type("reductor"),box,Entity::isAlive);
            h.assertTrue(original.isRemoved()&&replacements.size()==1,"Native queued evolution did not resume with a player");replacements.forEach(Entity::discard);
        }finally{original.discard();}h.succeed();
    }

    @GameTest(template="empty",batch="evolution_native")
    public static void nativeWarpAndQueuedCreeperExplosionRecheckCurrentAbilityPhase(GameTestHelper h)throws Exception{
        var level=h.getLevel();var enderman=mob(h,"assimilated_enderman",new BlockPos(2,2,2));var creeper=mob(h,"assimilated_creeper",new BlockPos(6,2,6));
        var destination=h.absolutePos(new BlockPos(4,2,2));level.setBlock(destination.below(),Blocks.STONE.defaultBlockState(),3);
        var explosions=new java.util.concurrent.atomic.AtomicInteger();Consumer<ExplosionEvent.Start> listener=event->{if(event.getLevel()==level&&event.getExplosion().center().distanceToSqr(creeper.position())<1)explosions.incrementAndGet();};
        NeoForge.EVENT_BUS.addListener(listener);
        try(var phase=Phase.open(level,0);var power=number("ASSIMILATED_CREEPER_EXPLOSION_POWER",1)){
            var before=enderman.position();
            h.assertTrue(!(Boolean)call("behavior.AssimilatedEndermanBehavior","warp",enderman,(double)destination.getX(),(double)destination.getY(),(double)destination.getZ())&&enderman.position().equals(before),"Locked native teleport moved an existing mutant");
            call("entity.AssimilatedCreeperEntity","lambda$assimilatedCreeperFuse$0",creeper,level);
            h.assertTrue(explosions.get()==0,"Queued native explosion ignored the current closed ability");
            phase.set(5);
            h.assertTrue((Boolean)call("behavior.AssimilatedEndermanBehavior","warp",enderman,(double)destination.getX(),(double)destination.getY(),(double)destination.getZ()),"Unlocked native warp did not run");
            call("entity.AssimilatedCreeperEntity","lambda$assimilatedCreeperFuse$0",creeper,level);
            h.assertTrue(explosions.get()==1,"Unlocked queued native callback did not produce one actual explosion");
        }finally{NeoForge.EVENT_BUS.unregister(listener);enderman.discard();creeper.discard();}h.succeed();
    }
}
